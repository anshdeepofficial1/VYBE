package com.theveloper.pixelplay.data.network.ytmusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.OnlineAudioQuality
import com.theveloper.pixelplay.utils.isIsoCountry
import com.theveloper.pixelplay.utils.resolveRequestRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val newPipeStreamResolver: NewPipeStreamResolver
) {
    private data class StructuredSearchBuckets(
        val songs: List<Song>,
        val albums: List<YouTubeAlbum>,
        val artists: List<YouTubeArtist>,
        val videos: List<Song>,
        val topResult: YouTubeTopResult? = null,
    )

    companion object {
        private const val TAG = "YouTubeMusicEngine"
        private const val INNERTUBE_MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
        private const val INNERTUBE_MAIN_BASE = "https://www.youtube.com/youtubei/v1"
        private const val WEB_REMIX_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private const val FALLBACK_WEB_REMIX_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        // Keep this aligned with the current public WEB_REMIX client. It is only used when
        // music.youtube.com temporarily throttles the lightweight bootstrap request.
        private const val FALLBACK_WEB_REMIX_CLIENT_VERSION = "1.20260915.14.00"
        private const val WEB_REMIX_CLIENT_NAME_HEADER = "67"
        private val WEB_CONFIG_TTL_MS = TimeUnit.HOURS.toMillis(6)
        private val WEB_CONFIG_FAILURE_BACKOFF_MS = TimeUnit.MINUTES.toMillis(10)
        private val API_KEY_REGEX = Regex("""\"INNERTUBE_API_KEY\":\"([^\"]+)\"""")
        private val CLIENT_VERSION_REGEX = Regex("""\"INNERTUBE_(?:CONTEXT_)?CLIENT_VERSION\":\"([^\"]+)\"""")
        private val VISITOR_DATA_REGEX = Regex("""\"VISITOR_DATA\":\"([^\"]+)\"""")
        // The bootstrap page declares the country YouTube resolved for our IP. This is the same
        // signal the real youtube.com/music.youtube.com front end uses, so matching it is what
        // keeps this app's Home, charts and search in step with the website.
        private val COUNTRY_CODE_REGEX = Regex("""\"GL\":\"([A-Za-z]{2})\"""")
        private val DURATION_TEXT_REGEX = Regex("""^(?:\d{1,2}:)?\d{1,2}:\d{2}$""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz"
        )

        private val INVIDIOUS_INSTANCES = listOf(
            "https://invidious.nerdvpn.de",
            "https://invidious.drgns.space"
        )

        // Filter out non-music video titles
        private val NON_MUSIC_KEYWORDS = listOf(
            "tutorial", "interview", "podcast", "keynote", "vlog", "gameplay",
            "reaction", "review", "news", "how to", "unboxing", "full episode",
            "episode", "episodes", "audiobook", "audio book", "highlights",
            "press conference", "analysis", "talk show"
        )
        private val RESULT_TYPE_LABELS = setOf(
            "song", "video", "music video", "single", "album", "ep"
        )
        // Whole-word matching only. The previous substring test on title+artist+album dropped
        // legitimate music whose metadata merely contained a keyword (a song called "The News",
        // an artist called "Episode", a track on an album named "Review").
        private val NON_MUSIC_TITLE_REGEX = Regex(
            "\\b(" + NON_MUSIC_KEYWORDS.joinToString("|") { Regex.escape(it) } + ")\\b"
        )
        private const val SEARCH_FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val SEARCH_FILTER_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"

        // Stream-resolution race tuning. The preferred tier (ANDROID_VR + NewPipe) gets a short
        // head start; if neither has answered by then the availability mirrors join the race so a
        // single dead client can never add its full round trip to the tap→audio gap.
        private const val STREAM_RACE_PREFERRED_GRACE_MS = 1_500L
        private const val STREAM_RACE_TIMEOUT_MS = 8_000L
    }

    // value.second is an absolute expiry deadline (epoch millis) derived from the stream URL
    // itself, not the moment it was resolved.
    private val streamUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(4)
    private val STREAM_URL_SAFETY_MARGIN_MS = TimeUnit.MINUTES.toMillis(2)

    // Single-flight state. Several independent callers warm the same track at once: the
    // direct-play warm-up, the queue prefetch, MusicService's next-track pre-load and the
    // player's own DataSource. Without this they each started their own resolver race.
    private val streamResolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightResolves = ConcurrentHashMap<String, Deferred<String?>>()

    // Resolver calls run on their own client with a hard call timeout. A race cancels the
    // losing resolvers as soon as the winner arrives, but they are blocked inside OkHttp's
    // synchronous execute(), which coroutine cancellation cannot interrupt - so without a
    // deadline they keep burning the link for several more seconds and starve the next track.
    private val streamResolveClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var webRemixConfig: WebRemixConfig? = null

    @Volatile
    private var lastWebConfigRefreshFailureMs: Long = 0L

    /**
     * Country YouTube Music resolved for this network, read from the bootstrap page. This is an
     * IP-derived value, which matters because the website renders against the same value while a
     * device locale can disagree with it: an en-US device on an Indian connection would otherwise
     * fetch a US feed and stop matching the site the user is looking at.
     */
    @Volatile
    private var geographicRegion: String? = null

    @Volatile
    private var dataSaverEnabled: Boolean = false

    @Volatile
    private var onlineAudioQuality: OnlineAudioQuality = OnlineAudioQuality.AUTO

    fun setDataSaverEnabled(enabled: Boolean) {
        if (dataSaverEnabled == enabled) return
        dataSaverEnabled = enabled
        streamUrlCache.clear()
    }

    fun setOnlineAudioQuality(quality: OnlineAudioQuality) {
        if (onlineAudioQuality == quality) return
        onlineAudioQuality = quality
        // Resolved URLs encode the chosen format, so a quality change must not keep using
        // a cached stream selected under the previous preference.
        streamUrlCache.clear()
    }

    init {
        // Warm the music.youtube.com bootstrap off the main thread as soon as the engine exists.
        // The response tells us the country YouTube resolved for this IP, which is what the real
        // website renders against; without it the first Home/search call would fall back to the
        // device locale and could fetch a completely different feed than the site the user sees.
        Thread(
            {
                runCatching { getWebRemixConfig() }
                    .onFailure { error -> Log.w(TAG, "Initial YouTube Music bootstrap failed: ${error.message}") }
            },
            "vybe-ytm-bootstrap",
        ).apply { isDaemon = true }.start()
    }

    /**
     * Picks the Innertube country for a request.
     *
     * 1. A valid explicit preference always wins: the user chose it deliberately.
     * 2. Otherwise the country YouTube resolved for this connection, so Home, charts and search
     *    stay in step with the website on the same network.
     * 3. Otherwise the device locale, then IN as a last resort.
     */
    fun resolveRegion(configured: String?): String {
        configured?.trim()?.uppercase()?.let { if (isIsoCountry(it)) return it }
        geographicRegion?.let { if (isIsoCountry(it)) return it }
        return resolveRequestRegion(null)
    }

    /** [resolveRegion] after making sure the bootstrap has had a chance to report our country. */
    suspend fun resolveRegionAwaitingNetwork(configured: String?): String =
        withContext(Dispatchers.IO) {
            if (geographicRegion == null) runCatching { getWebRemixConfig() }
            resolveRegion(configured)
        }

    /** Returns the last usable Home snapshot without performing network I/O. */
    fun getCachedHomeShelves(region: String = "IN"): List<YouTubeHomeShelf> {
        val normalizedRegion = region.uppercase()
        val preferences = context.getSharedPreferences("ytm_home_feed", Context.MODE_PRIVATE)
        val accountScope = currentWebAuthSession()?.cookie
            ?.let(::sha1Hex)?.take(12) ?: "guest"
        val cacheKey = "home_v7_${normalizedRegion}_$accountScope"
        val payload = preferences.getString(cacheKey, null)
            ?: preferences.getString("${cacheKey}_first_page", null)
            ?: return emptyList()
        return runCatching { parseHomeShelves(JSONObject(payload)) }.getOrDefault(emptyList())
    }

    /** Fetches the provider-authored Music Home feed, including personalised shelves. */
    suspend fun getHomeShelves(region: String = "IN"): List<YouTubeHomeShelf> = withContext(Dispatchers.IO) {
        // Resolve the country before anything else: Home is the cold-start screen, and using the
        // device locale here instead of YouTube's own answer returns a completely different shelf
        // set than the website shows on the same connection.
        val normalizedRegion = resolveRegionAwaitingNetwork(region)
        val preferences = context.getSharedPreferences("ytm_home_feed", Context.MODE_PRIVATE)
        val accountScope = currentWebAuthSession()?.cookie
            ?.let(::sha1Hex)?.take(12) ?: "guest"
        // Bump whenever authenticated request semantics change.  Earlier builds could cache
        // a guest-looking response under an account key, which then masked a fixed auth path
        // for 30 minutes after updating the APK.
        val cacheKey = "home_v7_${normalizedRegion}_$accountScope"
        val firstPageKey = "${cacheKey}_first_page"
        val cachedPayload = preferences.getString(cacheKey, null)
        val cachedAt = preferences.getLong("${cacheKey}_fetched_at", 0L)
        val cachedComplete = preferences.getBoolean("${cacheKey}_complete", false)
        val cachedShelves = cachedPayload?.let { payload ->
            runCatching { parseHomeShelves(JSONObject(payload)) }.getOrDefault(emptyList())
        }.orEmpty().ifEmpty {
            preferences.getString(firstPageKey, null)?.let { payload ->
                runCatching { parseHomeShelves(JSONObject(payload)) }.getOrDefault(emptyList())
            }.orEmpty()
        }
        // A recent authentic snapshot makes Home instant on relaunch and avoids unnecessary
        // provider calls. Pull/periodic refresh naturally replaces it after this short TTL.
        if (currentWebAuthSession() == null && cachedComplete && cachedShelves.isNotEmpty() &&
            System.currentTimeMillis() - cachedAt < TimeUnit.MINUTES.toMillis(30)
        ) return@withContext cachedShelves

        val requireAccount = currentWebAuthSession() != null
        Log.w(TAG, "Home request scope=${if (requireAccount) "account" else "guest"}, cachedShelves=${cachedShelves.size}")
        val homePayload: (WebRemixConfig) -> JSONObject = { config ->
            JSONObject().apply {
                put("context", createWebRemixContext(region, config))
                put("browseId", "FEmusic_home")
            }
        }
        val response = executeWebRemixRequest("browse", region, requireAuthentication = requireAccount, payload = homePayload)
            // An expired WebView session must not make Home empty. Show the real public
            // catalogue while the account session refreshes, instead of mock/random data.
            ?: if (requireAccount) executeWebRemixRequest("browse", region, payload = homePayload) else null
            ?: return@withContext cachedShelves
        val responses = mutableListOf(response)
        // Persist the authenticated first page immediately.  A slow continuation must never
        // make callers lose valid personalised Quick Picks/Listen Again data to a timeout.
        runCatching { parseHomeShelves(JSONObject(response)) }
            .getOrDefault(emptyList())
            .also { parsed ->
                Log.w(TAG, "Home first page parsed shelves=${parsed.map { it.title }.take(12)}")
            }
            .takeIf(List<YouTubeHomeShelf>::isNotEmpty)
            ?.let {
                preferences.edit().putString(firstPageKey, response).apply()
            }
        val visitedContinuations = mutableSetOf<String>()
        var continuation = extractContinuationToken(JSONObject(response))
        // Mirror the provider's lazy Home feed far beyond the first viewport. Each token is
        // visited once, so malformed/repeated continuations cannot loop forever.
        // Bound cold-start latency: eight sequential continuations could exceed the caller's
        // timeout and discard an otherwise valid first page. The snapshot is cached afterwards.
        // WEB_REMIX returns the rest of the current Home feed in its first continuation.
        // Chasing additional tokens here made the whole call exceed Home's 15-second budget on
        // slower networks, discarding a valid personalised first page. Later refreshes can
        // replace this bounded snapshot without blocking initial content.
        // The first continuation carries the next viewport of the personalised home feed.
        // More sequential calls made Home feel frozen and were usually cancelled by callers.
        val continuationBudget = 1
        repeat(continuationBudget) {
            if (continuation.isNullOrBlank()) return@repeat
            val token = continuation ?: return@repeat
            if (!visitedContinuations.add(token)) return@repeat
            val next = executeWebRemixRequest("browse", region, requireAuthentication = requireAccount) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("continuation", token)
                }
            } ?: return@repeat
            responses += next
            continuation = runCatching { extractContinuationToken(JSONObject(next)) }.getOrNull()
        }
        val combinedResponse = JSONObject().apply {
            put("responses", JSONArray().apply { responses.forEach { put(JSONObject(it)) } })
        }.toString()
        runCatching { parseHomeShelves(JSONObject(combinedResponse)) }
            .onFailure { Log.w(TAG, "YouTube Music Home parsing failed", it) }
            .getOrDefault(emptyList())
            .ifEmpty { cachedShelves }
            .also { shelves ->
                if (shelves.isNotEmpty()) {
                    preferences.edit()
                        .putString(cacheKey, combinedResponse)
                        .putLong("${cacheKey}_fetched_at", System.currentTimeMillis())
                        .putBoolean("${cacheKey}_complete", true)
                        .apply()
                }
            }
    }

    internal fun extractContinuationToken(root: JSONObject): String? {
        var found: String? = null
        fun visit(node: Any?) {
            if (found != null) return
            when (node) {
                is JSONObject -> {
                    val token = node.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                        .orEmpty()
                        .ifBlank { node.optJSONObject("nextContinuationData")?.optString("continuation").orEmpty() }
                    if (token.isNotBlank()) {
                        found = token
                        return
                    }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return found
    }

    internal fun parseHomeShelves(root: JSONObject): List<YouTubeHomeShelf> {
        val shelves = mutableListOf<YouTubeHomeShelf>()
        fun title(renderer: JSONObject): String {
            val header = renderer.optJSONObject("header")
            return extractRunsText(
                header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title")
                    ?: header?.optJSONObject("musicShelfBasicHeaderRenderer")?.optJSONObject("title")
                    ?: renderer.optJSONObject("title")
            )
        }
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val renderer = node.optJSONObject("musicCarouselShelfRenderer")
                        ?: node.optJSONObject("musicShelfRenderer")
                    if (renderer != null) {
                        val shelfTitle = title(renderer).trim()
                        val tracks = mutableListOf<YouTubeTrack>()
                        collectHomeTracks(renderer.optJSONArray("contents"), tracks)
                        val songs = tracks.distinctBy(YouTubeTrack::videoId).map(YouTubeTrack::toSong)
                        val collections = mutableListOf<YouTubeHomeCollection>()
                        collectHomeCollections(renderer.optJSONArray("contents"), collections)
                        if (shelfTitle.isNotBlank() && (songs.isNotEmpty() || collections.isNotEmpty()) &&
                            !shelfTitle.contains("podcast", ignoreCase = true)
                        ) shelves += YouTubeHomeShelf(
                            title = shelfTitle,
                            songs = songs,
                            collections = collections.distinctBy(YouTubeHomeCollection::browseId),
                        )
                        return
                    }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return shelves.distinctBy { it.title.lowercase() }.take(24)
    }

    private fun collectHomeTracks(node: Any?, tracks: MutableList<YouTubeTrack>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")
                    ?.let { parseListItem(it) }?.let(tracks::add)
                node.optJSONObject("musicTwoRowItemRenderer")
                    ?.let { parseListItem(it) }?.let(tracks::add)
                node.keys().forEach { collectHomeTracks(node.opt(it), tracks) }
            }
            is JSONArray -> for (index in 0 until node.length()) collectHomeTracks(node.opt(index), tracks)
        }
    }

    private fun collectHomeCollections(node: Any?, result: MutableList<YouTubeHomeCollection>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicTwoRowItemRenderer")?.let { item ->
                    val endpoint = item.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                    val browseId = endpoint?.optString("browseId").orEmpty()
                    val pageType = endpoint
                        ?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType").orEmpty()
                    if (browseId.isNotBlank()) {
                        val itemTitle = extractRunsText(item.optJSONObject("title"))
                        if (itemTitle.isNotBlank()) {
                            result += YouTubeHomeCollection(
                                browseId = browseId,
                                title = itemTitle,
                                subtitle = extractRunsText(item.optJSONObject("subtitle")),
                                thumbnailUrl = extractThumbnail(item.optJSONObject("thumbnail"))
                                    ?: extractBestThumbnail(item),
                                pageType = pageType,
                            )
                        }
                    }
                }
                node.keys().forEach { collectHomeCollections(node.opt(it), result) }
            }
            is JSONArray -> for (index in 0 until node.length()) collectHomeCollections(node.opt(index), result)
        }
    }

    /**
     * Explicit Data Saver always wins. Otherwise VYBE automatically selects an efficient
     * stream when Android reports a constrained/slow link, so a user does not need to find
     * the setting before playback becomes usable.
     */
    private fun preferEfficientStream(): Boolean {
        if (dataSaverEnabled) return true
        if (onlineAudioQuality == OnlineAudioQuality.DATA_SAVER) return true
        if (onlineAudioQuality != OnlineAudioQuality.AUTO) return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        val downstreamKbps = capabilities.linkDownstreamBandwidthKbps
        return downstreamKbps in 1 until 3_000 ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
    }

    private fun audioStreamScore(bitrateValue: Int, mimeType: String = ""): Long {
        if (bitrateValue <= 0) return Long.MIN_VALUE
        val bitrate = if (bitrateValue < 1_000) bitrateValue * 1_000L else bitrateValue.toLong()
        val quality = if (dataSaverEnabled) OnlineAudioQuality.DATA_SAVER else onlineAudioQuality
        return when (quality) {
            OnlineAudioQuality.AUTO -> if (preferEfficientStream()) -bitrate else bitrate
            OnlineAudioQuality.DATA_SAVER -> -bitrate
            OnlineAudioQuality.NORMAL -> -kotlin.math.abs(bitrate - 128_000L)
            OnlineAudioQuality.HIGH -> bitrate
            OnlineAudioQuality.LOSSLESS -> {
                val mime = mimeType.lowercase()
                val isLossless = listOf("flac", "alac", "audio/wav", "audio/x-wav", "aiff")
                    .any(mime::contains)
                (if (isLossless) 1_000_000_000_000L else 0L) + bitrate
            }
        }
    }

    private fun targetAudioBitrate(): Int? = when {
        dataSaverEnabled || onlineAudioQuality == OnlineAudioQuality.DATA_SAVER -> null
        onlineAudioQuality == OnlineAudioQuality.NORMAL -> 128_000
        else -> null
    }

    // Persistent visitor data token — YouTube uses this for session continuity.
    // InnerTune/OuterTune/Meld all send this in payload + header.
    @Volatile
    private var visitorData: String = generateVisitorData()

    private fun generateVisitorData(): String {
        // Generate a base64-like visitor token similar to YouTube's format
        val random = java.util.UUID.randomUUID().toString().replace("-", "")
        return "Cgt${random.take(22)}%3D%3D"
    }

    /**
     * Invalidate cached stream URL for a video (e.g. on HTTP 403 or stream expiry).
     */
    fun invalidateStreamUrl(videoId: String) {
        val cleanId = videoId.removePrefix("yt_")
        streamUrlCache.remove(cleanId)
    }

    /**
     * Every googlevideo URL carries its own `expire=<epoch seconds>` parameter and is bound to
     * the IP that requested it. Storing a fixed 4 hour TTL from the resolve moment meant a long
     * queue could later hand Media3 a URL that had already died, which surfaced as a mid-song
     * HTTP 403. Deriving the deadline from the URL (minus a two minute safety margin) lets
     * [cachedStreamUrl] re-resolve before playback breaks instead of after.
     */
    private fun cacheStreamUrl(videoId: String, url: String) {
        streamUrlCache[videoId] = url to streamUrlExpiry(url)
    }

    private fun streamUrlExpiry(url: String): Long {
        val now = System.currentTimeMillis()
        val advertised = url.toHttpUrlOrNull()
            ?.queryParameter("expire")
            ?.toLongOrNull()
            ?.times(1000L)
            ?: return now + CACHE_TTL_MS
        return minOf(advertised - STREAM_URL_SAFETY_MARGIN_MS, now + CACHE_TTL_MS)
    }

    /** Returns a still-valid cached URL, evicting it when its own expiry has passed. */
    private fun cachedStreamUrl(videoId: String): String? {
        val cached = streamUrlCache[videoId] ?: return null
        if (cached.second > System.currentTimeMillis()) return cached.first
        streamUrlCache.remove(videoId)
        return null
    }

    /**
     * Perform music-only search and categorize results into:
     * 1. Songs
     * 2. Albums
     * 3. Artists
     */
    suspend fun searchMusicStructured(query: String, region: String = "IN"): YouTubeSearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext YouTubeSearchResult()

        val songs = mutableListOf<Song>()
        val albums = mutableListOf<YouTubeAlbum>()
        val artists = mutableListOf<YouTubeArtist>()
        // Fetch exact song results and the mixed summary concurrently. The filtered
        // endpoint prevents similarly named uploads from outranking the real song,
        // while parallel execution keeps the search latency bounded.
        var providerTopResult: YouTubeTopResult? = null

        coroutineScope {
            val exactSongs = async(Dispatchers.IO) {
                mutableListOf<Song>().also { filtered ->
                    searchStructuredEndpoint(cleanQuery, region, filtered, mutableListOf(), mutableListOf(), mutableListOf(), SEARCH_FILTER_SONGS)
                }
            }
            val exactAlbums = async(Dispatchers.IO) {
                mutableListOf<YouTubeAlbum>().also { filtered ->
                    searchStructuredEndpoint(cleanQuery, region, mutableListOf(), filtered, mutableListOf(), mutableListOf(), SEARCH_FILTER_ALBUMS)
                }
            }
            // The dedicated artist filter token is rejected by the current WEB_REMIX profile and
            // returns a single row. The mixed query already yields the canonical artist entities,
            // so requesting artists separately was a wasted round trip on every search.
            val mixedResults = async(Dispatchers.IO) {
                val mixedSongs = mutableListOf<Song>()
                val mixedAlbums = mutableListOf<YouTubeAlbum>()
                val mixedArtists = mutableListOf<YouTubeArtist>()
                val topResults = mutableListOf<YouTubeTopResult>()
                searchStructuredEndpoint(
                    cleanQuery, region, mixedSongs, mixedAlbums, mixedArtists, mutableListOf(),
                    topResultOut = topResults,
                )
                StructuredSearchBuckets(
                    mixedSongs, mixedAlbums, mixedArtists, emptyList(), topResults.firstOrNull()
                )
            }
            // YouTube's own "All" ordering is what the website shows and it already encodes
            // which upload is the real song. The dedicated Songs filter ranks lower-reach
            // uploads (covers, lyric clips) far higher for an artist query, so its rows are
            // only appended as a supplement instead of leading the list.
            val mixed = mixedResults.await()
            providerTopResult = mixed.topResult
            songs += mixed.songs
            albums += exactAlbums.await()
            albums += mixed.albums
            artists += mixed.artists
            songs += exactSongs.await()
        }

        // Some WEB_REMIX profiles ignore the album filter token and return an empty
        // filtered shelf. A music-scoped intent query still returns real album browse
        // entities; use it only to fill the missing album bucket.
        if (albums.isEmpty()) {
            searchStructuredEndpoint(
                "$cleanQuery albums", region,
                mutableListOf(), albums, mutableListOf(), mutableListOf(),
            )
        }

        // Filter out non-music items from songs. No relevance re-sorting happens here: the
        // provider already ordered the results, and re-ranking by title text made uploads that
        // merely repeat the query in their name outrank the artist's actual top songs.
        val filteredSongs = songs
            .filter(::isMusicOnlySong)
            .distinctBy { it.id }

        val distinctAlbums = albums.distinctBy { it.browseId }
        val normalizedQuery = cleanQuery.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val distinctArtists = artists
            .groupBy { it.browseId }
            .values
            .map { matches ->
                matches.maxByOrNull { artist ->
                    (if (!artist.thumbnailUrl.isNullOrBlank()) 2 else 0) +
                        (if (!artist.subscriberCount.isNullOrBlank()) 1 else 0)
                } ?: matches.first()
            }
        val canonicalArtists = distinctArtists
            .sortedByDescending { artist ->
                val normalizedName = artist.name.lowercase()
                    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                (if (normalizedName == normalizedQuery) 100 else 0) +
                    (if (normalizedName.startsWith(normalizedQuery)) 20 else 0) +
                    (if (artist.browseId.startsWith("UC")) 12 else 0) +
                    (if (!artist.subscriberCount.isNullOrBlank()) 8 else 0) +
                    (if (!artist.thumbnailUrl.isNullOrBlank()) 4 else 0)
            }
            .let { ranked ->
                ranked.filter { artist ->
                    artist.name.lowercase()
                        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() == normalizedQuery
                }.takeIf { it.isNotEmpty() }
                    ?.take(1)
                    ?: ranked.filter { artist ->
                        val name = artist.name.lowercase()
                            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                        name.contains(normalizedQuery) || normalizedQuery.contains(name)
                    }.take(8)
            }

        YouTubeSearchResult(
            songs = filteredSongs,
            albums = distinctAlbums,
            artists = canonicalArtists,
            videos = emptyList(),
            topResult = providerTopResult,
        )
    }

    private fun isMusicOnlySong(song: Song): Boolean {
        // Only the title is inspected, and only whole words match: a non-music upload is almost
        // always identifiable from its title, while a keyword hiding in the artist or album name
        // is a false positive.
        return song.title.isNotBlank() &&
            song.artist.isNotBlank() &&
            !NON_MUSIC_TITLE_REGEX.containsMatchIn(song.title)
    }

    private fun searchStructuredEndpoint(
        query: String,
        region: String,
        songs: MutableList<Song>,
        albums: MutableList<YouTubeAlbum>,
        artists: MutableList<YouTubeArtist>,
        videos: MutableList<Song>,
        params: String? = null,
        topResultOut: MutableList<YouTubeTopResult>? = null,
    ) {
        try {
            val responseBody = executeWebRemixRequest("search", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("query", query)
                    if (!params.isNullOrBlank()) put("params", params)
                }
            } ?: return
            parseStructuredSearchResponse(
                responseBody, songs, albums, artists, videos, topResultOut ?: mutableListOf()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Structured search failed for '$query': ${e.message}")
        }
    }

    /**
     * Search YouTube Music for songs, tracks, artists, and remixes with multi-tier fallback.
     */
    suspend fun search(query: String, region: String = "IN"): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val tracks = mutableListOf<YouTubeTrack>()

        // Tier 1: YouTube Music WEB_REMIX
        try {
            executeWebRemixRequest("search", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("query", cleanQuery)
                    // WEB_REMIX's filter token is release-specific. The encoded token used by
                    // older builds is now rejected with INVALID_ARGUMENT, so fetch the normal
                    // music search response and apply VYBE's music-only filter below.
                }
            }?.let { bodyString -> tracks.addAll(parseSearchResponse(bodyString)) }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 search failed: ${e.message}")
        }

        tracks.filter { track -> !NON_MUSIC_TITLE_REGEX.containsMatchIn(track.title) }
            .distinctBy { it.videoId }
            .sortedByDescending { searchRelevanceScore(cleanQuery, it) }
    }

    /** Returns live query completions from YouTube Music, without requiring an account. */
    suspend fun getSearchSuggestions(query: String, region: String = "IN"): List<String> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()
            runCatching {
                executeWebRemixRequest("music/get_search_suggestions", region) { config ->
                    JSONObject()
                        .put("context", createWebRemixContext(region, config))
                        .put("input", cleanQuery)
                }?.let { parseSearchSuggestions(it, cleanQuery) }.orEmpty()
            }.onFailure { error ->
                Log.w(TAG, "YouTube Music suggestions failed: ${error.message}")
            }.getOrDefault(emptyList())
        }

    /**
     * Builds YouTube Music's watch-next/radio continuation for one seed track.
     * This is intentionally independent from search so a tapped search result does not turn
     * the remaining keyword matches into the playback queue.
     */
    suspend fun getAutoplayTracks(seedVideoId: String, region: String = "IN"): List<YouTubeTrack> =
        withContext(Dispatchers.IO) {
            val cleanId = seedVideoId.removePrefix("yt_").trim()
            if (cleanId.isBlank()) return@withContext emptyList()
            runCatching {
                executeWebRemixRequest("next", region) { config ->
                    JSONObject().apply {
                        put("context", createWebRemixContext(region, config))
                        put("videoId", cleanId)
                        put("playlistId", "RDAMVM$cleanId")
                        put("isAudioOnly", true)
                        put("enablePersistentPlaylistPanel", true)
                        put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
                    }
                }?.let(::parseSearchResponse).orEmpty()
                    .filter(::isMusicTrack)
                    .filterNot { it.videoId == cleanId }
                    .distinctBy { it.videoId }
                    .take(50)
            }.onFailure { error ->
                Log.w(TAG, "Autoplay continuation failed for $cleanId: ${error.message}")
            }.getOrDefault(emptyList())
        }

    suspend fun getTrackDetails(videoId: String, region: String = "IN"): YouTubeTrack? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isBlank()) return@withContext null
        val musicDetails = runCatching {
            executeWebRemixRequest("next", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("videoId", cleanId)
                    put("isAudioOnly", true)
                }
            }?.let(::parseSearchResponse).orEmpty()
                .firstOrNull { it.videoId == cleanId }
        }.getOrNull()
        if (musicDetails != null) return@withContext musicDetails

        // `next` occasionally omits the selected item. Resolve the immutable YouTube id
        // through YouTube's public metadata endpoint instead of exposing placeholders.
        runCatching {
            val request = Request.Builder()
                .url("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$cleanId&format=json")
                .header("User-Agent", WEB_REMIX_USER_AGENT)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body.string())
                val rawTitle = json.optString("title").trim()
                val author = json.optString("author_name").removeSuffix(" - Topic").trim()
                if (rawTitle.isBlank() || author.isBlank()) null else YouTubeTrack(
                    videoId = cleanId,
                    title = rawTitle,
                    artist = author,
                    thumbnailUrl = json.optString("thumbnail_url").takeIf(String::isNotBlank)
                        ?: "https://i.ytimg.com/vi/$cleanId/maxresdefault.jpg",
                    resultType = YouTubeMusicEntityType.MUSIC_VIDEO,
                    isOfficial = true,
                )
            }
        }.getOrNull()
    }

    private fun isMusicTrack(track: YouTubeTrack): Boolean {
        val metadata = "${track.title} ${track.artist} ${track.album}".lowercase()
        return track.title.isNotBlank() && track.artist.isNotBlank() &&
            NON_MUSIC_KEYWORDS.none(metadata::contains)
    }

    /**
     * Fetch real-time Top Charts and Trending tracks for India / Global.
     */
    suspend fun getTrendingTracks(region: String = "IN"): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YouTubeTrack>()

        // Tier 1: YouTube Music Charts Browse
        try {
            val config = getWebRemixConfig()
            val jsonPayload = JSONObject().apply {
                put("context", createWebRemixContext(region, config))
                put("browseId", "FEmusic_charts")
            }

            val request = Request.Builder()
                .url("$INNERTUBE_MUSIC_BASE/browse?key=${config.apiKey}&prettyPrint=false")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_NAME_HEADER)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .header("X-VYBE-Public-YouTube", "1")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    tracks.addAll(parseBrowseResponse(bodyString))

                    // The current chart endpoint exposes ranked chart playlists rather than
                    // embedding song videoIds on the landing page. Resolve the highest-priority
                    // song chart (for example "Trending 20 India") before falling back to the
                    // cached snapshot. Without this step the Charts button opened an empty page.
                    if (tracks.isEmpty()) {
                        data class ChartPlaylist(val title: String, val browseId: String)
                        val playlists = mutableListOf<ChartPlaylist>()
                        fun collectChartPlaylists(node: Any?) {
                            when (node) {
                                is JSONObject -> {
                                    node.optJSONObject("musicTwoRowItemRenderer")?.let { renderer ->
                                        val title = extractRunsText(renderer.optJSONObject("title"))
                                        val browseId = renderer.optJSONObject("navigationEndpoint")
                                            ?.optJSONObject("browseEndpoint")
                                            ?.optString("browseId")
                                            .orEmpty()
                                        if (browseId.startsWith("VL") && title.isNotBlank()) {
                                            playlists += ChartPlaylist(title, browseId)
                                        }
                                    }
                                    node.keys().forEach { collectChartPlaylists(node.opt(it)) }
                                }
                                is JSONArray -> for (index in 0 until node.length()) {
                                    collectChartPlaylists(node.opt(index))
                                }
                            }
                        }
                        runCatching { collectChartPlaylists(JSONObject(bodyString)) }
                        val rankedPlaylists = playlists.distinctBy { it.browseId }.sortedBy { playlist ->
                            val title = playlist.title.lowercase()
                            when {
                                "trending" in title && "video" !in title -> 0
                                "top" in title && "song" in title -> 1
                                "video" !in title -> 2
                                else -> 3
                            }
                        }
                        for (playlist in rankedPlaylists.take(3)) {
                            val playlistBody = executeWebRemixRequest("browse", region) { playlistConfig ->
                                JSONObject().apply {
                                    put("context", createWebRemixContext(region, playlistConfig))
                                    put("browseId", playlist.browseId)
                                }
                            }
                            if (!playlistBody.isNullOrBlank()) {
                                tracks.addAll(parseBrowseResponse(playlistBody))
                            }
                            if (tracks.size >= 20) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Browse charts failed: ${e.message}")
        }

        val official = tracks.distinctBy { it.videoId }
            .filter { isCleanTrendingTrack(it.title, it.artist) }
        if (official.isNotEmpty()) {
            saveOfficialChart(region, official)
            official
        } else {
            // Never label broad text-search results as an official chart. Reuse the last
            // successfully parsed provider-ranked snapshot when the chart endpoint is down.
            loadOfficialChart(region)
        }
    }

    private fun saveOfficialChart(region: String, tracks: List<YouTubeTrack>) {
        val payload = JSONArray().apply {
            tracks.take(100).forEach { track ->
                put(JSONObject().apply {
                    put("id", track.videoId)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("duration", track.durationSeconds)
                    put("thumbnail", track.thumbnailUrl)
                    put("type", track.resultType.name)
                    put("official", track.isOfficial)
                })
            }
        }
        context.getSharedPreferences("ytm_official_charts", Context.MODE_PRIVATE).edit()
            .putString("chart_${region.uppercase()}", payload.toString())
            .putLong("chart_${region.uppercase()}_fetched_at", System.currentTimeMillis())
            .apply()
    }

    private fun loadOfficialChart(region: String): List<YouTubeTrack> {
        val raw = context.getSharedPreferences("ytm_official_charts", Context.MODE_PRIVATE)
            .getString("chart_${region.uppercase()}", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        YouTubeTrack(
                            videoId = item.getString("id"),
                            title = item.getString("title"),
                            artist = item.getString("artist"),
                            album = item.optString("album", "YouTube Music"),
                            durationSeconds = item.optLong("duration"),
                            thumbnailUrl = item.optString("thumbnail").takeIf(String::isNotBlank),
                            resultType = runCatching {
                                YouTubeMusicEntityType.valueOf(item.optString("type"))
                            }.getOrDefault(YouTubeMusicEntityType.SONG),
                            isOfficial = item.optBoolean("official"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Fetches the real YouTube Music New Releases browse feed. */
    suspend fun getLatestReleases(region: String = "IN"): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YouTubeTrack>()
        val releaseAlbums = mutableListOf<YouTubeAlbum>()
        runCatching {
            val responsePayload = executeWebRemixRequest(
                endpoint = "browse",
                region = region,
                requireAuthentication = false,
            ) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    // YouTube Music's current album-release destination. The older
                    // FEmusic_new_releases landing page now returns navigation shells on
                    // some regions, which left Explore's New releases destination empty.
                    put("browseId", "FEmusic_new_releases_albums")
                }
            }.orEmpty()
            if (responsePayload.isNotBlank()) {
                tracks += parseBrowseResponse(responsePayload)
                parseStructuredSearchResponse(
                    responsePayload,
                    mutableListOf(),
                    releaseAlbums,
                    mutableListOf(),
                    mutableListOf()
                )
                // New Releases is primarily made of two-row album cards, not search result
                // renderers. Preserve their canonical browse IDs so they can be resolved to
                // dated tracks below.
                val collections = mutableListOf<YouTubeHomeCollection>()
                collectHomeCollections(JSONObject(responsePayload), collections)
                releaseAlbums += collections
                    .filter { collection ->
                        collection.browseId.startsWith("MPRE", ignoreCase = true) ||
                            collection.pageType.contains("ALBUM", ignoreCase = true)
                    }
                    .map { collection ->
                        YouTubeAlbum(
                            browseId = collection.browseId,
                            title = collection.title,
                            artist = collection.subtitle.substringBefore(" • ").ifBlank { "Various Artists" },
                            thumbnailUrl = collection.thumbnailUrl,
                        )
                    }
            }
        }.onFailure { error ->
            Log.w(TAG, "New releases browse failed: ${error.message}")
        }

        // Resolve catalog albums because the browse cards themselves do not reliably carry
        // an exact date. Undated releases are deliberately excluded from Release Radar.
        if (releaseAlbums.isNotEmpty()) {
            tracks.clear()
            val resolvedAlbums = coroutineScope {
                releaseAlbums.distinctBy { it.browseId }.take(12).map { album ->
                    async(Dispatchers.IO) {
                        val details = runCatching { getAlbumDetails(album.browseId) }.getOrNull()
                            ?: return@async null
                        val song = details.tracks.firstOrNull() ?: return@async null
                        YouTubeTrack(
                            videoId = song.id.removePrefix("yt_"),
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            durationSeconds = song.duration / 1_000L,
                            thumbnailUrl = song.albumArtUriString ?: album.thumbnailUrl,
                            isOfficial = true,
                            albumBrowseId = details.browseId,
                            releaseDateEpochMillis = details.releaseDateEpochMillis,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            tracks += resolvedAlbums
        }

        // Some WEB_REMIX regions return only navigation endpoints (or no album cards at
        // all) for the new-releases browse ID. Keep the date guarantee by falling back to
        // fresh music searches, then validating every candidate against its provider
        // publish date below. This is never an undated/random fallback.
        if (tracks.isEmpty()) {
            val now = java.time.LocalDate.now()
            val month = now.month.getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.ENGLISH,
            )
            val candidates = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { search("$month ${now.year} new songs", region) },
                    async(Dispatchers.IO) { search("new music releases this week", region) },
                ).awaitAll().flatten()
            }
            tracks += candidates.distinctBy { it.videoId }.take(36)
        }

        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val oldestAllowed = today.minusDays(29)
        // Validate each card against the video's provider publication date. Album browse
        // responses may contain dates from unrelated recommendation shelves, which previously
        // allowed years-old tracks into Release Radar.
        val datedTracks = tracks.distinctBy { it.videoId }.chunked(6).flatMap { chunk ->
            coroutineScope {
                chunk.map { track ->
                    async(Dispatchers.IO) {
                        val published = fetchVideoPublishedDate(track.videoId, region)
                        val resolved = when {
                            track.releaseDateEpochMillis > 0L && published > 0L ->
                                minOf(track.releaseDateEpochMillis, published)
                            published > 0L -> published
                            else -> track.releaseDateEpochMillis
                        }
                        track.copy(releaseDateEpochMillis = resolved)
                    }
                }.awaitAll()
            }
        }
        val filtered = datedTracks
            .filter { track -> NON_MUSIC_KEYWORDS.none { track.title.lowercase().contains(it) } }
            .distinctBy { it.videoId }

        val inWindow = filtered.filter { track ->
            if (track.releaseDateEpochMillis <= 0L) return@filter false
            val date = java.time.Instant.ofEpochMilli(track.releaseDateEpochMillis).atZone(zone).toLocalDate()
            !date.isBefore(oldestAllowed) && !date.isAfter(today)
        }.sortedByDescending { it.releaseDateEpochMillis }

        // Unknown publish date means the metadata call failed (usually rate limiting), not that the
        // release is old: these candidates came from the provider's new-releases shelf or a
        // month-scoped search. Keeping them means the shelf can never be empty just because one
        // per-track request was throttled. Genuinely older releases are the last resort only.
        val undated = filtered.filter { it.releaseDateEpochMillis <= 0L }
        val inWindowIds = inWindow.map { it.videoId }.toSet()
        val older = filtered.filter { it.releaseDateEpochMillis > 0L && it.videoId !in inWindowIds }
            .sortedByDescending { it.releaseDateEpochMillis }

        (inWindow + undated + older).take(20)
    }

    private fun fetchVideoPublishedDate(videoId: String, region: String): Long {
        if (videoId.isBlank()) return 0L
        return runCatching {
            val responsePayload = executeWebRemixRequest(
                endpoint = "player",
                region = region,
                requireAuthentication = false,
            ) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("videoId", videoId.removePrefix("yt_"))
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
            } ?: return@runCatching 0L
            val root = JSONObject(responsePayload)
            val renderer = root.optJSONObject("microformat")
                ?.optJSONObject("playerMicroformatRenderer")
            val raw = renderer?.optString("publishDate").orEmpty()
                .ifBlank { renderer?.optString("uploadDate").orEmpty() }
            val date = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull()
                ?: return@runCatching 0L
            date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun isCleanTrendingTrack(title: String, artist: String): Boolean {
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()
        val forbiddenKeywords = listOf(
            "dj", "remix", "mashup", "nonstop", "non-stop", "mix", "slowed", "reverb", "karaoke", "instrumental", "loop"
        )
        return forbiddenKeywords.none { keyword ->
            lowerTitle.contains(keyword) || lowerArtist.contains(keyword)
        }
    }

    /**
     * Resolve direct playable audio stream URL (Opus / AAC) with high availability multi-client failover.
     */
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        resolveStreamUrlShared(videoId.removePrefix("yt_"))
    }

    /**
     * Single-flight wrapper around [resolveStreamUrlParallel].
     *
     * Four independent callers warm the same track at roughly the same moment: the direct-play
     * warm-up, the queue prefetch, MusicService's next-track pre-load fired from
     * [androidx.media3.Player.Listener.onTimelineChanged], and the player's own DataSource. Each
     * used to start its own race, so one cold track fanned out into dozens of concurrent player
     * POSTs and mirror fetches. That self-inflicted thundering herd competes with itself for
     * bandwidth, which is exactly what turns a resolver that should answer in ~1 s into one that
     * answers in 4-7 s on an already slow link. Sharing a single in-flight race per id keeps the
     * network budget on the track the user actually asked for, and every later caller simply
     * joins the work already running.
     *
     * The shared [Deferred] lives in [streamResolveScope], not the caller's scope, so one caller
     * being cancelled mid-wait cannot pull the URL out from under the others.
     */
    private suspend fun resolveStreamUrlShared(cleanId: String): String? {
        cachedStreamUrl(cleanId)?.let { return it }
        // LAZY start guarantees the body (and therefore its `finally` eviction) cannot run
        // before computeIfAbsent has registered it; otherwise a race that finished first would
        // be removed before insertion and a dead entry would be published instead, leaving every
        // later caller awaiting a resolution that never happens.
        var created: Deferred<String?>? = null
        val deferred = inFlightResolves.computeIfAbsent(cleanId) {
            streamResolveScope.async(start = CoroutineStart.LAZY) {
                try {
                    resolveStreamUrlParallel(cleanId)
                } finally {
                    inFlightResolves.remove(cleanId)
                }
            }.also { created = it }
        }
        // No suspension point between computeIfAbsent and here, so the creator always reaches
        // this line and the shared race can never be left unstarted for other waiters.
        created?.start()
        return deferred.await()
    }

    /**
     * Synchronous stream resolution helper for background DataSource loader threads.
     *
     * This runs on Media3's loading thread and therefore sits directly in the tap→audio gap. It
     * blocks on the same single-flight race the async path uses instead of a serial failover
     * walk, which is what makes a cold, uncached track start in a single resolver round trip
     * rather than the sum of all of them. When a warm-up already started the race this resolves
     * to waiting on that one rather than issuing a competing request.
     */
    fun resolveStreamUrlSync(videoId: String): String? {
        val cleanId = videoId.removePrefix("yt_")
        // 0. Honour the cached URL only while its own advertised expiry still holds.
        cachedStreamUrl(cleanId)?.let { return it }

        return runBlocking(Dispatchers.IO) {
            resolveStreamUrlShared(cleanId)
        }
    }

    /**
     * Resolves a playable audio URL by racing only the resolvers that are verified to work.
     *
     * YouTube has retired most of the older Innertube clients this app used to race: measured
     * against the live API, ANDROID_TESTSUITE answers `UNPLAYABLE` with zero audio streams, the
     * TVHTML5 embedded client answers `ERROR`, the iOS client rejects the current payload with
     * HTTP 400, and ANDROID_MUSIC answers `LOGIN_REQUIRED` for guests. The race is ANDROID_VR
     * (ad-free, unthrottled direct audio) and the maintained NewPipe extractor as the preferred
     * tier, with a signed-in ANDROID_MUSIC call plus the public Piped/Invidious mirrors held in
     * reserve as a pure availability net.
     *
     * Ordering here is not cosmetic. The resolvers are launched concurrently and the first
     * non-blank answer wins, because running them strictly one after another meant a slow or
     * dead client added its entire round trip to every later, working one. On a congested link
     * that serial walk stretched a cold start to 15-19 s, and it was the single largest
     * contributor to the tap→audio gap.
     *
     * The preferred tier is started first and the fallback tier only after
     * [preferredGraceMs], so on a healthy link the mirrors are never contacted and the audio
     * stays on the best available (ad-free) format instead of whichever mirror answers first.
     */
    private suspend fun resolveStreamUrlParallel(
        cleanId: String,
        preferredGraceMs: Long = STREAM_RACE_PREFERRED_GRACE_MS,
        overallTimeoutMs: Long = STREAM_RACE_TIMEOUT_MS,
    ): String? = coroutineScope {
        val winner = Channel<String>(Channel.UNLIMITED)
        // Blocking extractors may ignore cancellation. Do not join losing network calls
        // before returning the winning URL to Media3.
        val workers = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
        val raceStart = System.currentTimeMillis()
        val preferredResolvers: List<Pair<String, () -> String?>> = listOf(
            "ANDROID_VR" to { resolveViaAndroidVr(cleanId) },
            "IOS" to { resolveViaIos(cleanId) },
            "NewPipe" to { newPipeStreamResolver.resolve(cleanId, preferEfficientStream(), targetAudioBitrate()) },
        )
        val fallbackResolvers: List<Pair<String, () -> String?>> = buildList {
            if (currentWebAuthSession() != null) add("ANDROID_MUSIC" to { resolveViaAndroidMusic(cleanId) })
            PIPED_INSTANCES.forEach { base -> add("Piped($base)" to { resolveViaPiped(cleanId, base) }) }
            INVIDIOUS_INSTANCES.forEach { base -> add("Invidious($base)" to { resolveViaInvidious(cleanId, base) }) }
        }

        val startRace: (List<Pair<String, () -> String?>>) -> Unit = { resolvers ->
            resolvers.forEach { (name, resolver) ->
                workers += streamResolveScope.launch {
                    val startedAt = System.currentTimeMillis()
                    val candidate = runCatching { resolver() }.getOrNull()
                    val took = System.currentTimeMillis() - startedAt
                    // A URL is only a win if it is actually servable. Several clients (notably
                    // IOS) hand out a well-formed googlevideo URL that answers HTTP 403 for the
                    // very session that requested it, and trusting one is far worse than being
                    // slow: the player accepts it, gets a 403, invalidates, re-resolves, is handed
                    // the same unusable URL again and loops forever without ever producing sound.
                    // One cheap ranged probe keeps a dead-but-fast resolver from poisoning the
                    // winner slot that a slower but working resolver would have taken.
                    if (!candidate.isNullOrBlank() && isServableAudioUrl(candidate)) {
                        Log.d(TAG, "Stream race: $name WON $cleanId in ${took}ms (race+${System.currentTimeMillis() - raceStart}ms)")
                        winner.trySend(candidate)
                    } else {
                        Log.d(TAG, "Stream race: $name miss for $cleanId after ${took}ms")
                    }
                }
            }
        }

        startRace(preferredResolvers)
        if (fallbackResolvers.isNotEmpty()) {
            launch {
                if (preferredGraceMs > 0L) delay(preferredGraceMs)
                startRace(fallbackResolvers)
            }
        }

        try {
            withTimeoutOrNull(overallTimeoutMs) { winner.receive() }
                ?.also { cacheStreamUrl(cleanId, it) }
        } finally {
            coroutineContext.cancelChildren()
            workers.forEach { it.cancel() }
            winner.close()
        }
    }

    /**
     * Cheap liveness probe for a resolved stream URL, issued exactly as the player will fetch it.
     *
     * Any 2xx means the URL serves audio for this session; a 403/404/410 means the resolver handed
     * out a URL that is dead on arrival and it must not be allowed to take the winner slot. Without
     * this a fast-but-broken client makes the player accept an unusable URL, invalidate, re-resolve
     * and loop forever without producing sound.
     *
     * The request must mirror [com.theveloper.pixelplay.data.service.player.DualPlayerEngine]'s
     * initial load: no Range header (Media3 only adds one for a non-zero offset), plus its Origin,
     * Referer and Chrome user agent. Deviating here is not cosmetic - googlevideo answers 403 to the
     * same URL both with and without `Range`, so a ranged probe was validating URLs that were
     * guaranteed to fail in the player.
     */
    private fun isServableAudioUrl(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                )
                .build()
            streamResolveClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveViaIos(videoId: String): String? = runCatching {
        val payload = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "20.10.4")
                put("deviceModel", "iPhone16,2")
            }))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val request = Request.Builder()
            .url("$INNERTUBE_MAIN_BASE/player?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        streamResolveClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val root = JSONObject(response.body.string())
            extractAudioUrlFromStreamingData(root.optJSONObject("streamingData"))
        }
    }.getOrNull()

    private fun resolveViaAndroidVr(videoId: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.60.19")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("androidSdkVersion", 32)
                        put("hl", "en")
                        put("gl", "US")
                        put("visitorData", visitorData)
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$INNERTUBE_MAIN_BASE/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/32.0.0.0.0 Safari/537.36")
                .header("X-Goog-Visitor-Id", visitorData)
                .build()

            val response = streamResolveClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                extractAudioUrlFromStreamingData(json.optJSONObject("streamingData"))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveViaAndroidMusic(videoId: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "6.42.52")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                        put("visitorData", visitorData)
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$INNERTUBE_MUSIC_BASE/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "6.42.52")
                .header("X-Goog-Visitor-Id", visitorData)
                .build()

            val response = streamResolveClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                extractAudioUrlFromStreamingData(json.optJSONObject("streamingData"))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveViaPiped(videoId: String, instanceUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url("$instanceUrl/streams/$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = streamResolveClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = JSONObject(response.body?.string().orEmpty())
                val audioStreams = body.optJSONArray("audioStreams") ?: return null
                var bestUrl: String? = null
                var bestScore = Long.MIN_VALUE
                for (i in 0 until audioStreams.length()) {
                    val stream = audioStreams.getJSONObject(i)
                    val bitrate = stream.optInt("bitrate", 0)
                    val url = stream.optString("url")
                    val score = audioStreamScore(bitrate, stream.optString("mimeType", stream.optString("format")))
                    if (url.isNotBlank() && score > bestScore) {
                        bestScore = score
                        bestUrl = url
                    }
                }
                bestUrl
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveViaInvidious(videoId: String, instanceUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url("$instanceUrl/api/v1/videos/$videoId")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = streamResolveClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = JSONObject(response.body?.string().orEmpty())
                val adaptiveFormats = body.optJSONArray("adaptiveFormats") ?: return null
                var bestUrl: String? = null
                var bestScore = Long.MIN_VALUE
                for (i in 0 until adaptiveFormats.length()) {
                    val stream = adaptiveFormats.getJSONObject(i)
                    val type = stream.optString("type", "")
                    if (type.startsWith("audio/")) {
                        val bitrate = stream.optInt("bitrate", 0)
                        val url = stream.optString("url")
                        val score = audioStreamScore(bitrate, type)
                        if (url.isNotBlank() && score > bestScore) {
                            bestScore = score
                            bestUrl = url
                        }
                    }
                }
                bestUrl
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractAudioUrlFromStreamingData(streamingData: JSONObject?): String? {
        if (streamingData == null) return null
        val formats = streamingData.optJSONArray("adaptiveFormats") ?: streamingData.optJSONArray("formats") ?: return null

        var bestUrl: String? = null
        var bestScore = Long.MIN_VALUE

        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            val mimeType = format.optString("mimeType", "")
            val url = format.optString("url", "")
            val bitrate = format.optInt("bitrate", 0)

            // Skip formats with blank URL (e.g. signatureCipher/cipher formats) since we don't support JS signature deciphering
            if (mimeType.startsWith("audio/") && url.isNotBlank()) {
                val score = audioStreamScore(bitrate, mimeType)
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = url
                }
            }
        }
        return bestUrl
    }

    /**
     * Fetch complete artist profile: header, bio, top songs, albums/singles, related artists.
     */
    suspend fun getArtistProfile(browseId: String): YouTubeArtistProfile? = withContext(Dispatchers.IO) {
        val cleanBrowseId = if (browseId.startsWith("yt_artist_")) browseId.removePrefix("yt_artist_") else browseId
        try {
            val config = getWebRemixConfig()
            val jsonPayload = JSONObject().apply {
                put("context", createWebRemixContext("IN", config))
                put("browseId", cleanBrowseId)
            }

            val request = Request.Builder()
                .url("$INNERTUBE_MUSIC_BASE/browse?key=${config.apiKey}&prettyPrint=false")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_NAME_HEADER)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .header("X-VYBE-Public-YouTube", "1")
                .build()

            val bodyString = okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string().orEmpty() else ""
            }
            if (bodyString.isBlank()) return@withContext null

            val baseProfile = parseArtistProfileResponse(cleanBrowseId, bodyString)
                ?: return@withContext null
            val expansionRequests = collectArtistExpansionRequests(JSONObject(bodyString), cleanBrowseId)
            if (expansionRequests.isEmpty()) return@withContext baseProfile

            val expansionPayloads = coroutineScope {
                expansionRequests.map { (expansionId, params) ->
                    async {
                        fetchBrowsePages(
                            browseId = expansionId,
                            params = params,
                            region = "IN",
                        )
                    }
                }.awaitAll().flatten()
            }

            val extraTracks = mutableListOf<YouTubeTrack>()
            val extraAlbums = mutableListOf<YouTubeAlbum>()
            val extraArtists = mutableListOf<YouTubeArtist>()
            expansionPayloads.forEach { payload ->
                collectTracksRecursively(JSONObject(payload), extraTracks)
                parseStructuredSearchResponse(
                    payload,
                    mutableListOf(),
                    extraAlbums,
                    extraArtists,
                    mutableListOf()
                )
            }
            val extraSongs = extraTracks.filter { it.resultType != YouTubeMusicEntityType.MUSIC_VIDEO }.map { it.toSong() }
            val extraVideos = extraTracks.filter { it.resultType == YouTubeMusicEntityType.MUSIC_VIDEO }.map { it.toSong() }

            val cleanArtistName = baseProfile.name.trim().lowercase()
            fun isCleanArtistContent(song: Song): Boolean {
                val title = song.title.lowercase()
                val isJukebox = title.contains("jukebox") ||
                    title.contains("mashup") ||
                    title.contains("non stop") ||
                    title.contains("nonstop") ||
                    title.contains("megamix") ||
                    title.contains("all songs") ||
                    title.contains("full album") ||
                    title.contains("hits collection") ||
                    title.contains("compilation")
                if (isJukebox || song.duration > 900_000L) return false
                if (cleanArtistName.isBlank() || cleanArtistName == "artist") return true
                val songArtist = song.artist.trim().lowercase()
                return songArtist.contains(cleanArtistName) || cleanArtistName.contains(songArtist) || title.contains(cleanArtistName)
            }

            return@withContext baseProfile.copy(
                topSongs = (baseProfile.topSongs + extraSongs)
                    .filter(::isMusicOnlySong)
                    .filter(::isCleanArtistContent)
                    .distinctBy { it.id },
                videos = (baseProfile.videos + extraVideos)
                    .filter(::isCleanArtistContent)
                    .distinctBy { it.id },
                albums = (baseProfile.albums + extraAlbums).distinctBy { it.browseId },
                relatedArtists = (baseProfile.relatedArtists + extraArtists)
                    .filter { it.browseId != cleanBrowseId }
                    .distinctBy { it.browseId },
            )
        } catch (e: Exception) {
            Log.e(TAG, "getArtistProfile failed: ${e.message}")
        }
        null
    }

    private fun collectArtistExpansionRequests(
        root: Any?,
        artistBrowseId: String,
    ): List<Pair<String, String?>> {
        val result = linkedSetOf<Pair<String, String?>>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    for (key in listOf("moreContentButton", "bottomEndpoint")) {
                        val container = node.opt(key) ?: continue
                        val endpoint = findFirstBrowseEndpoint(container) ?: continue
                        val id = endpoint.optString("browseId")
                        if (id.isNotBlank() && id != artistBrowseId && !id.startsWith("MPRE")) {
                            result += id to endpoint.optString("params").takeIf(String::isNotBlank)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return result.toList()
    }

    private fun fetchBrowsePages(
        browseId: String,
        params: String?,
        region: String,
    ): List<String> {
        val pages = mutableListOf<String>()
        val visitedContinuations = mutableSetOf<String>()
        var continuation: String? = null
        do {
            val page = executeWebRemixRequest("browse", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    if (continuation.isNullOrBlank()) {
                        put("browseId", browseId)
                        if (!params.isNullOrBlank()) put("params", params)
                    } else {
                        put("continuation", continuation)
                    }
                }
            } ?: break
            pages += page
            continuation = findContinuationTokens(JSONObject(page))
                .firstOrNull { visitedContinuations.add(it) }
        } while (continuation != null && pages.size < 40)
        return pages
    }

    private fun findContinuationTokens(root: Any?): List<String> {
        val tokens = linkedSetOf<String>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("continuationCommand")
                        ?.optString("token")
                        ?.takeIf(String::isNotBlank)
                        ?.let(tokens::add)
                    node.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                        ?.takeIf(String::isNotBlank)
                        ?.let(tokens::add)
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return tokens.toList()
    }

    /**
     * Fetch complete album details: title, artist, cover, track list.
     */
    suspend fun getAlbumDetails(browseId: String): YouTubeAlbumDetails? = withContext(Dispatchers.IO) {
        val cleanBrowseId = if (browseId.startsWith("yt_album_")) browseId.removePrefix("yt_album_") else browseId
        try {
            val pages = fetchBrowsePages(cleanBrowseId, params = null, region = "IN")
            if (pages.isNotEmpty()) {
                val combined = JSONObject().put(
                    "pages",
                    JSONArray().apply { pages.forEach { put(JSONObject(it)) } },
                )
                return@withContext parseAlbumDetailsResponse(cleanBrowseId, combined.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAlbumDetails failed: ${e.message}")
        }
        null
    }

    private fun parseStructuredSearchResponse(
        jsonString: String,
        songs: MutableList<Song>,
        albums: MutableList<YouTubeAlbum>,
        artists: MutableList<YouTubeArtist>,
        videos: MutableList<Song>,
        topResults: MutableList<YouTubeTopResult> = mutableListOf(),
    ) {
        try {
            val root = JSONObject(jsonString)
            collectTopResult(root, topResults)
            parseStructuredRecursive(root, songs, albums, artists, videos)
        } catch (e: Exception) {
            Log.e(TAG, "parseStructuredSearchResponse error: ${e.message}")
        }
    }

    /**
     * Reads the hero card (`musicCardShelfRenderer`) that YouTube Music renders above a search
     * result list. It carries the canonical entity for the query plus the first playable track,
     * so the UI can show what the website shows instead of guessing with "first song".
     */
    private fun collectTopResult(root: JSONObject, out: MutableList<YouTubeTopResult>) {
        if (out.isNotEmpty()) return
        var found: JSONObject? = null
        fun visit(node: Any?, depth: Int) {
            if (found != null || depth > 40) return
            when (node) {
                is JSONObject -> {
                    val card = node.optJSONObject("musicCardShelfRenderer")
                    if (card != null) {
                        found = card
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        visit(node.opt(keys.next()), depth + 1)
                        if (found != null) return
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) {
                    visit(node.opt(i), depth + 1)
                    if (found != null) return
                }
            }
        }
        visit(root, 0)
        val card = found ?: return

        val titleRun = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
        val title = titleRun?.optString("text").orEmpty().ifBlank {
            extractRunsText(card.optJSONObject("title"))
        }
        if (title.isBlank()) return

        val browseEndpoint = titleRun?.optJSONObject("browseEndpoint")
            ?: findFirstBrowseEndpoint(card)
        val browseId = browseEndpoint?.optString("browseId").orEmpty()
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
        val subtitle = extractRunsText(card.optJSONObject("subtitle"))
        val typeLabel = subtitle.split(" • ").firstOrNull().orEmpty().trim()

        // The card's inline rows render only title + duration, so they carry no artist of
        // their own. Take it from the card instead: the entity name when the card IS an
        // artist, otherwise everything after the type label in the subtitle.
        val cardArtist = run {
            val resolvedType = typeLabel.ifBlank { guessTopResultType(pageType, browseId) }
            val source = if (resolvedType.equals("Artist", ignoreCase = true)) {
                title
            } else {
                subtitle.split(Regex("[•·]"))
                    .map(String::trim)
                    .filter { it.isNotBlank() && it.lowercase() !in RESULT_TYPE_LABELS }
                    .joinToString(", ")
            }
            source.trim().takeIf { it.isNotBlank() && !DURATION_TEXT_REGEX.matches(it) }.orEmpty()
        }

        val seedSongs = card.optJSONArray("contents")
            ?.let { contents ->
                buildList {
                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemRenderer")
                            ?: continue
                        val track = parseListItem(item) ?: continue
                        // parseListItem parks the placeholder "YouTube Music" in the artist
                        // slot when a row has no linked artist, and the hero card's inline rows
                        // carry only title + duration. Neither is a real artist, so the card's
                        // own credit is used instead of letting the placeholder surface as
                        // "Unknown Artist" under every inline track.
                        val hasCredit = track.artist.isNotBlank() &&
                            !track.artist.equals("YouTube Music", ignoreCase = true) &&
                            !DURATION_TEXT_REGEX.matches(track.artist)
                        val resolved = if (hasCredit) track else track.copy(artist = cardArtist)
                        val song = resolved.toSong().takeIf { it.id.isNotBlank() } ?: continue
                        add(song)
                    }
                }
            }
            .orEmpty()

        out += YouTubeTopResult(
            typeLabel = typeLabel.ifBlank { guessTopResultType(pageType, browseId) },
            title = title,
            subtitle = subtitle,
            thumbnailUrl = extractBestThumbnail(card)
                ?: seedSongs.firstOrNull()?.albumArtUriString?.takeIf { it.isNotBlank() },
            browseId = browseId,
            pageType = pageType,
            seedSongs = seedSongs,
        )
    }

    private fun guessTopResultType(pageType: String, browseId: String): String = when {
        pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> "Artist"
        pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> "Album"
        pageType == "MUSIC_PAGE_TYPE_PLAYLIST" -> "Playlist"
        else -> "Song"
    }

    private fun parseStructuredRecursive(
        json: Any?,
        songs: MutableList<Song>,
        albums: MutableList<YouTubeAlbum>,
        artists: MutableList<YouTubeArtist>,
        videos: MutableList<Song>
    ) {
        when (json) {
            is JSONObject -> {
                if (json.has("musicResponsiveListItemRenderer")) {
                    val item = json.optJSONObject("musicResponsiveListItemRenderer")!!
                    categorizeAndAddItem(item, songs, albums, artists, videos)
                } else if (json.has("musicTwoRowItemRenderer")) {
                    val item = json.optJSONObject("musicTwoRowItemRenderer")!!
                    categorizeTwoRowItem(item, albums, artists)
                }

                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parseStructuredRecursive(json.opt(key), songs, albums, artists, videos)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    parseStructuredRecursive(json.opt(i), songs, albums, artists, videos)
                }
            }
        }
    }

    private fun categorizeAndAddItem(
        item: JSONObject,
        songs: MutableList<Song>,
        albums: MutableList<YouTubeAlbum>,
        artists: MutableList<YouTubeArtist>,
        videos: MutableList<Song>
    ) {
        parseListItem(item)?.let { track ->
            if (track.resultType == YouTubeMusicEntityType.MUSIC_VIDEO) {
                videos.add(track.toSong())
            } else {
                songs.add(track.toSong())
            }
            collectSongArtists(item, artists)
            return
        }

        val navEndpoint = item.optJSONObject("navigationEndpoint")
        val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
            ?: findFirstBrowseEndpoint(item)
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")

        val browseId = browseEndpoint?.optString("browseId", "") ?: ""
        val title = responsiveColumnText(item, 0)
            .ifBlank { extractRunsText(item.optJSONObject("title")) }
        val subtitle = responsiveColumnText(item, 1)
            .ifBlank { extractRunsText(item.optJSONObject("subtitle")) }
        val thumb = extractThumbnail(item.optJSONObject("thumbnail"))

        when {
            pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> {
                if (title.isNotBlank() && browseId.isNotBlank()) {
                    artists.add(
                        YouTubeArtist(
                            browseId = browseId,
                            name = title,
                            subscriberCount = subtitle.takeIf { it.isNotBlank() },
                            thumbnailUrl = thumb
                        )
                    )
                }
            }
            pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> {
                if (title.isNotBlank() && browseId.isNotBlank()) {
                    albums.add(
                        YouTubeAlbum(
                            browseId = browseId,
                            title = title,
                            artist = subtitle.substringBefore(" • ").ifBlank { "Various Artists" },
                            thumbnailUrl = thumb
                        )
                    )
                }
            }
            pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" -> Unit
            else -> Unit
        }
    }

    private fun responsiveColumnText(item: JSONObject, index: Int): String {
        val text = item.optJSONArray("flexColumns")
            ?.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        return extractRunsText(text)
    }

    /** Adds every linked singer/performer from a song result as an openable profile result. */
    private fun collectSongArtists(item: JSONObject, artists: MutableList<YouTubeArtist>) {
        val artistText = item.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?: item.optJSONObject("subtitle")
            ?: return
        val runs = artistText.optJSONArray("runs") ?: return
        for (index in 0 until runs.length()) {
            val run = runs.optJSONObject(index) ?: continue
            val name = run.optString("text").trim()
            val browseEndpoint = run.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint") ?: continue
            val browseId = browseEndpoint.optString("browseId")
            val pageType = browseEndpoint
                .optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType")
            val isArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                browseId.startsWith("UC") || browseId.startsWith("MPLA")
            if (isArtist && name.isNotBlank() && browseId.isNotBlank()) {
                artists.add(YouTubeArtist(browseId = browseId, name = name))
            }
        }
    }

    private fun findFirstBrowseEndpoint(root: Any?): JSONObject? {
        when (root) {
            is JSONObject -> {
                root.optJSONObject("browseEndpoint")?.let { return it }
                val keys = root.keys()
                while (keys.hasNext()) {
                    findFirstBrowseEndpoint(root.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until root.length()) {
                findFirstBrowseEndpoint(root.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun categorizeTwoRowItem(
        item: JSONObject,
        albums: MutableList<YouTubeAlbum>,
        artists: MutableList<YouTubeArtist>
    ) {
        val navEndpoint = item.optJSONObject("navigationEndpoint")
        val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        val browseId = browseEndpoint?.optString("browseId", "") ?: ""

        val title = extractRunsText(item.optJSONObject("title"))
        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val thumb = extractThumbnail(item.optJSONObject("thumbnailRenderer"))

        if (title.isBlank() || browseId.isBlank()) return

        if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC")) {
            artists.add(
                YouTubeArtist(
                    browseId = browseId,
                    name = title,
                    subscriberCount = subtitle.takeIf { it.isNotBlank() },
                    thumbnailUrl = thumb
                )
            )
        } else if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE")) {
            // Subtitle reads "Album • Artist • 2023". The old substringBefore(" • ") promoted
            // the literal word "Album" (or the album type label) into the artist slot, so
            // search results credited the row type instead of the performer. Skip type
            // labels and bare years to reach the real credit.
            val subtitleArtist = subtitle.split(Regex("[•·]"))
                .map(String::trim)
                .firstOrNull {
                    it.isNotBlank() && it.lowercase() !in RESULT_TYPE_LABELS &&
                        !Regex("^(19|20)\\d{2}$").matches(it)
                }
                .orEmpty()
            albums.add(
                YouTubeAlbum(
                    browseId = browseId,
                    title = title,
                    artist = subtitleArtist,
                    thumbnailUrl = thumb
                )
            )
        }
    }

    private fun parseArtistProfileResponse(browseId: String, jsonString: String): YouTubeArtistProfile? {
        try {
            val root = JSONObject(jsonString)
            val header = root.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: root.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")

            val artistName = extractRunsText(header?.optJSONObject("title"))
            val description = extractRunsText(header?.optJSONObject("description"))
            val subscribers = extractRunsText(header?.optJSONObject("subscriptionButton")
                ?.optJSONObject("subscribeButtonRenderer")?.optJSONObject("subscriberCountText"))

            val avatarUrl = extractThumbnail(header?.optJSONObject("thumbnail"))
            val bannerUrl = extractThumbnail(header?.optJSONObject("foregroundThumbnail")) ?: avatarUrl

            val topSongs = mutableListOf<Song>()
            val videos = mutableListOf<Song>()
            val albums = mutableListOf<YouTubeAlbum>()
            val singles = mutableListOf<YouTubeAlbum>()
            val relatedArtists = mutableListOf<YouTubeArtist>()

            val rawTracks = mutableListOf<YouTubeTrack>()
            collectTracksRecursively(root, rawTracks)

            // The artist page's inline rows carry only title + duration, the same shape that
            // blanked the credit on album tracklists. Every credit-less track belongs to the
            // artist whose page we are reading, so the header name fills the artist slot and
            // the strict-artist filter can actually recognise them as this artist's songs.
            val creditedArtist = artistName.trim().takeIf(String::isNotBlank)
            fun toCreditedSong(track: YouTubeTrack): Song {
                val base = track.toSong()
                val hasCredit = track.artist.isNotBlank() &&
                    !track.artist.equals("YouTube Music", ignoreCase = true) &&
                    !DURATION_TEXT_REGEX.matches(track.artist)
                return if (!hasCredit && creditedArtist != null) base.copy(artist = creditedArtist) else base
            }
            val rawSongs = rawTracks.filter { it.resultType != YouTubeMusicEntityType.MUSIC_VIDEO }
                .mapNotNull { track -> toCreditedSong(track).takeIf { it.id.isNotBlank() } }
            val rawVideos = rawTracks.filter { it.resultType == YouTubeMusicEntityType.MUSIC_VIDEO }
                .mapNotNull { track -> toCreditedSong(track).takeIf { it.id.isNotBlank() } }

            val cleanName = artistName.trim().lowercase()
            fun isStrictlyThisArtist(s: Song): Boolean {
                val title = s.title.lowercase()
                val isJukebox = title.contains("jukebox") ||
                    title.contains("mashup") ||
                    title.contains("non stop") ||
                    title.contains("nonstop") ||
                    title.contains("megamix") ||
                    title.contains("all songs") ||
                    title.contains("full album") ||
                    title.contains("hits collection") ||
                    title.contains("compilation")
                if (isJukebox || s.duration > 900_000L) return false
                if (cleanName.isBlank() || cleanName == "artist") return true
                val a = s.artist.trim().lowercase()
                return a.contains(cleanName) || cleanName.contains(a) || title.contains(cleanName)
            }

            topSongs.addAll(rawSongs.filter(::isStrictlyThisArtist))
            videos.addAll(rawVideos.filter(::isStrictlyThisArtist))

            // Collect release sections
            parseStructuredRecursive(root, mutableListOf(), albums, relatedArtists, mutableListOf())

            return YouTubeArtistProfile(
                browseId = browseId,
                name = artistName.ifBlank { "Artist" },
                bannerUrl = bannerUrl,
                avatarUrl = avatarUrl,
                description = description.takeIf { it.isNotBlank() },
                subscribers = subscribers.takeIf { it.isNotBlank() },
                topSongs = topSongs.distinctBy { it.id },
                videos = videos.distinctBy { it.id },
                albums = albums.distinctBy { it.browseId },
                singles = singles.distinctBy { it.browseId },
                relatedArtists = relatedArtists.filter { it.browseId != browseId }.distinctBy { it.browseId }
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseArtistProfileResponse error: ${e.message}")
        }
        return null
    }

    internal fun parseAlbumDetailsResponse(browseId: String, jsonString: String): YouTubeAlbumDetails? {
        try {
            val root = JSONObject(jsonString)
            val header = findFirstObjectForKeys(
                root,
                setOf(
                    "musicDetailHeaderRenderer",
                    "musicResponsiveHeaderRenderer",
                    "musicImmersiveHeaderRenderer",
                    "musicVisualHeaderRenderer",
                ),
            )

            val title = extractRunsText(header?.optJSONObject("title"))
            val subtitle = extractRunsText(header?.optJSONObject("subtitle"))
            val headerArtist = extractRunsText(header?.optJSONObject("straplineTextOne"))
                .ifBlank { subtitle.substringBefore("•").trim() }

            val year = Regex("\\b(19|20)\\d{2}\\b").find(subtitle)?.value?.toIntOrNull()
            // Restrict date parsing to the album header. Scanning the full response can pick a
            // date from a recommended album and incorrectly label an old release as new.
            val releaseDateEpochMillis = extractExactReleaseDate(header?.toString().orEmpty())
            val albumType = when {
                subtitle.contains("single", ignoreCase = true) -> "Single"
                Regex("\\bEP\\b", RegexOption.IGNORE_CASE).containsMatchIn(subtitle) -> "EP"
                else -> "Album"
            }
            val headerCoverUrl = extractBestThumbnail(header)

            val tracks = mutableListOf<Song>()
            val rawTracks = mutableListOf<YouTubeTrack>()
            collectTracksRecursively(root, rawTracks)

            val resolvedTitle = title.ifBlank {
                rawTracks.firstNotNullOfOrNull { track ->
                    track.album.takeUnless { it.isBlank() || it.equals("YouTube Music", ignoreCase = true) }
                }.orEmpty()
            }.ifBlank { "Album" }
            val resolvedArtist = headerArtist.ifBlank {
                rawTracks.map { it.artist.trim() }.filter(String::isNotBlank)
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            }.ifBlank { "Various Artists" }
            val coverUrl = headerCoverUrl
                ?: rawTracks.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }

            rawTracks.forEachIndexed { index, track ->
                val base = track.toSong()
                val lacksCredit = base.displayArtist.isBlank() ||
                    base.displayArtist.equals("Unknown Artist", ignoreCase = true) ||
                    base.displayArtist.equals("YouTube Music", ignoreCase = true)
                // Album tracklist rows usually carry only title + duration. The page header
                // knows the real credit, so the album's own artist fills the song instead
                // of letting every row on the album screen read "Unknown Artist".
                val inheritedArtist = resolvedArtist.takeUnless {
                    it.isBlank() || it.equals("Various Artists", ignoreCase = true)
                }
                val song = base.copy(
                    album = resolvedTitle,
                    artist = if (lacksCredit && inheritedArtist != null) inheritedArtist else base.artist,
                    albumArtUriString = track.thumbnailUrl?.takeIf(String::isNotBlank) ?: coverUrl,
                    trackNumber = index + 1
                )
                tracks.add(song)
            }

            return YouTubeAlbumDetails(
                browseId = browseId,
                title = resolvedTitle,
                artist = resolvedArtist,
                year = year,
                coverUrl = coverUrl,
                albumType = albumType,
                trackCount = tracks.size,
                tracks = tracks.distinctBy { it.id },
                releaseDateEpochMillis = releaseDateEpochMillis,
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseAlbumDetailsResponse error: ${e.message}")
        }
        return null
    }

    /** Reads only an exact provider date. A year by itself is never accepted. */
    private fun extractExactReleaseDate(json: String): Long {
        val iso = Regex("\\\"(?:releaseDate|publishDate|uploadDate)\\\"\\s*:\\s*\\\"(\\d{4}-\\d{2}-\\d{2})")
            .find(json)?.groupValues?.getOrNull(1)
        val textual = Regex(
            "(?:Released(?: on)?|Release date)[:\\s]+([A-Z][a-z]+\\s+\\d{1,2},\\s+\\d{4})",
            RegexOption.IGNORE_CASE,
        ).find(json.replace("\\u0026", "&"))?.groupValues?.getOrNull(1)
        val date = iso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: textual?.let {
                runCatching {
                    java.time.LocalDate.parse(
                        it,
                        java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.ENGLISH),
                    )
                }.getOrNull()
            }
            ?: return 0L
        return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun findFirstObjectForKeys(root: Any?, keys: Set<String>): JSONObject? {
        when (root) {
            is JSONObject -> {
                for (key in keys) root.optJSONObject(key)?.let { return it }
                val iterator = root.keys()
                while (iterator.hasNext()) {
                    findFirstObjectForKeys(root.opt(iterator.next()), keys)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until root.length()) {
                findFirstObjectForKeys(root.opt(index), keys)?.let { return it }
            }
        }
        return null
    }

    private fun extractBestThumbnail(root: Any?): String? {
        var bestUrl: String? = null
        var bestArea = -1L
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val url = node.optString("url").takeIf {
                        // ytimg.com is YouTube's video-frame CDN - always a 16:9 still of the
                        // video, never a cover. Only YouTube Music's artwork CDNs qualify.
                        it.contains("googleusercontent.com", true) ||
                            it.contains("ggpht.com", true)
                    }
                    if (url != null) {
                        val area = node.optLong("width", 1L) * node.optLong("height", 1L)
                        if (area >= bestArea) {
                            bestArea = area
                            bestUrl = url
                        }
                    }
                    val iterator = node.keys()
                    while (iterator.hasNext()) visit(node.opt(iterator.next()))
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return bestUrl?.let(::highQualityArtworkUrl)
    }

    /**
     * YouTube Music rotates its public WEB_REMIX client version. Search requests made with
     * the old hard-coded profile return an empty 404 response, so bootstrap the current
     * public profile from the Music homepage and cache it briefly. The bundled values are
     * only a network-failure fallback; a rejected request forces one fresh bootstrap/retry.
     */
    private fun getWebRemixConfig(forceRefresh: Boolean = false): WebRemixConfig {
        val now = System.currentTimeMillis()
        val authSession = currentWebAuthSession()
        val sessionScope = authSession?.cookie?.let(::sha1Hex)?.take(12) ?: "guest"
        val refreshIsCoolingDown = now - lastWebConfigRefreshFailureMs < WEB_CONFIG_FAILURE_BACKOFF_MS
        webRemixConfig?.takeIf {
            it.sessionScope == sessionScope &&
                ((!forceRefresh && now - it.loadedAtMs < WEB_CONFIG_TTL_MS) || refreshIsCoolingDown)
        }
            ?.let { return it }

        return synchronized(this) {
            webRemixConfig?.takeIf {
                it.sessionScope == sessionScope &&
                    ((!forceRefresh && now - it.loadedAtMs < WEB_CONFIG_TTL_MS) || refreshIsCoolingDown)
            }?.let { return@synchronized it }

            val fallback = WebRemixConfig(
                apiKey = FALLBACK_WEB_REMIX_API_KEY,
                clientVersion = FALLBACK_WEB_REMIX_CLIENT_VERSION,
                visitorData = visitorData,
                loadedAtMs = now,
                sessionScope = sessionScope,
            )
            val resolved = runCatching {
                val requestBuilder = Request.Builder()
                    .url("https://music.youtube.com/")
                    .header("User-Agent", WEB_REMIX_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (authSession != null) {
                    requestBuilder.header("Cookie", authSession.cookie)
                } else {
                    requestBuilder.header("X-VYBE-Public-YouTube", "1")
                }
                val request = requestBuilder.build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Music bootstrap failed (${response.code})")
                    val page = response.body?.string().orEmpty()
                    COUNTRY_CODE_REGEX.find(page)?.groupValues?.getOrNull(1)
                        ?.uppercase()
                        ?.takeIf(String::isNotBlank)
                        ?.let { code -> geographicRegion = code }
                    WebRemixConfig(
                        apiKey = API_KEY_REGEX.find(page)?.groupValues?.getOrNull(1)
                            ?.takeIf(String::isNotBlank) ?: fallback.apiKey,
                        clientVersion = CLIENT_VERSION_REGEX.find(page)?.groupValues?.getOrNull(1)
                            ?.takeIf(String::isNotBlank) ?: fallback.clientVersion,
                        visitorData = VISITOR_DATA_REGEX.find(page)?.groupValues?.getOrNull(1)
                            ?.takeIf(String::isNotBlank) ?: fallback.visitorData,
                        loadedAtMs = now,
                        sessionScope = sessionScope,
                    )
                }
            }.onSuccess {
                lastWebConfigRefreshFailureMs = 0L
            }.onFailure { error ->
                lastWebConfigRefreshFailureMs = System.currentTimeMillis()
                Log.w(TAG, "Could not refresh YouTube Music web configuration: ${error.message}")
            }.getOrDefault(fallback)

            visitorData = resolved.visitorData
            webRemixConfig = resolved
            resolved
        }
    }

    private fun executeWebRemixRequest(
        endpoint: String,
        region: String,
        requireAuthentication: Boolean = false,
        payload: (WebRemixConfig) -> JSONObject,
    ): String? {
        val authSession = currentWebAuthSession()
        // Public catalogue endpoints do not need the WebView cookie. Avoid trying a stale
        // session first: it causes a visible multi-second delay before the usable public
        // response, especially on Home mood chips and Search.
        val attemptCount = if (requireAuthentication && authSession != null) 2 else 1
        repeat(attemptCount) { attempt ->
            val useAuthentication = authSession != null && requireAuthentication
            val config = getWebRemixConfig(forceRefresh = attempt == 1)
            // The web bootstrap visitor token is tied to the browser session that issued it.
            // Replaying it from a fresh mobile install makes Innertube reject otherwise-valid
            // public search/browse payloads with INVALID_ARGUMENT. Guest calls intentionally
            // use the documented client context only; signed-in calls retain their session.
            val requestConfig = if (useAuthentication) {
                config
            } else {
                // Use one known-compatible public key/client pair for every guest call.
                // Mixing a bootstrap key/version with a stale browser visitor state is what
                // produced intermittent INVALID_ARGUMENT responses on fresh devices.
                config.copy(
                    apiKey = FALLBACK_WEB_REMIX_API_KEY,
                    clientVersion = FALLBACK_WEB_REMIX_CLIENT_VERSION,
                    visitorData = "",
                )
            }
            val requestPayload = payload(requestConfig)
            val requestDescriptor = when {
                requestPayload.has("browseId") -> "browseId=${requestPayload.optString("browseId")}" 
                requestPayload.has("continuation") -> "continuation=true"
                requestPayload.has("query") -> "query=true"
                else -> "payload=other"
            }
            val requestBuilder = Request.Builder()
                .url("$INNERTUBE_MUSIC_BASE/$endpoint?key=${requestConfig.apiKey}&prettyPrint=false")
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", WEB_REMIX_USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_NAME_HEADER)
                .header("X-YouTube-Client-Version", requestConfig.clientVersion)
            if (useAuthentication) {
                val session = authSession ?: return@repeat
                val timestamp = System.currentTimeMillis() / 1000L
                requestBuilder
                    .header("Cookie", session.cookie)
                    .header(
                        "Authorization",
                        "SAPISIDHASH ${timestamp}_${sha1Hex("$timestamp ${session.sapisid} https://music.youtube.com")}",
                    )
                    .header("X-Origin", "https://music.youtube.com")
                    .header("X-Goog-AuthUser", "0")
            } else {
                // Explicitly prevent the shared interceptor from attaching a stale account
                // cookie during the final regional/public fallback.
                requestBuilder.header("X-VYBE-Public-YouTube", "1")
            }
            if (requestConfig.visitorData.isNotBlank()) {
                requestBuilder.header("X-Goog-Visitor-Id", requestConfig.visitorData)
            }
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful && responseBody.isNotBlank()) return responseBody
                Log.w(
                    TAG,
                    "YouTube Music $endpoint failed (${response.code}), " +
                        "attempt ${attempt + 1}, authenticated=$useAuthentication, " +
                        "$requestDescriptor, " +
                        "reason=${responseBody.replace(Regex("\\s+"), " ").take(240)}",
                )
                if (response.code !in setOf(400, 401, 403, 404)) return null
            }
        }
        return null
    }

    private data class WebAuthSession(val cookie: String, val sapisid: String)

    private fun currentWebAuthSession(): WebAuthSession? {
        val preferences = YouTubeAuthPreferences.create(context)
        if (!preferences.getBoolean("is_logged_in", false)) return null
        val cookie = preferences.getString("auth_cookie", null)?.trim().orEmpty()
        if (cookie.isBlank()) return null
        val values = cookie.split(';').mapNotNull { part ->
            val pieces = part.trim().split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        val sapisid = values["SAPISID"]
            ?: values["__Secure-3PAPISID"]
            ?: values["APISID"]
            ?: return null
        return WebAuthSession(cookie = cookie, sapisid = sapisid)
    }

    private fun sha1Hex(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun createWebRemixContext(
        region: String,
        config: WebRemixConfig? = null,
    ): JSONObject {
        // The UI uses "GLOBAL" as a display choice, but Innertube accepts only an
        // ISO-3166 country code in client.gl. Sending GLOBAL caused every public
        // search/browse request to fail with INVALID_ARGUMENT.
        val countryCode = region.trim().uppercase()
            .takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: "IN"
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                // Keep the public fallback in step with WEB_REMIX.  A stale client version
                // makes otherwise-valid browse requests fail with HTTP 400.
                put("clientVersion", config?.clientVersion ?: FALLBACK_WEB_REMIX_CLIENT_VERSION)
                put("hl", "en")
                put("gl", countryCode)
                config?.visitorData?.takeIf(String::isNotBlank)?.let { put("visitorData", it) }
            })
        }
    }

    private data class WebRemixConfig(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String,
        val loadedAtMs: Long,
        val sessionScope: String,
    )

    private fun createAndroidContext(region: String): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "19.34.42")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", region.uppercase())
            })
        }
    }

    private fun parseSearchResponse(jsonString: String): List<YouTubeTrack> {
        val list = mutableListOf<YouTubeTrack>()
        try {
            val root = JSONObject(jsonString)
            collectTracksRecursively(root, list)
        } catch (e: Exception) {
            Log.e(TAG, "parseSearchResponse error: ${e.message}")
        }
        return list
    }

    private fun parseBrowseResponse(jsonString: String): List<YouTubeTrack> {
        val list = mutableListOf<YouTubeTrack>()
        try {
            val root = JSONObject(jsonString)
            collectTracksRecursively(root, list)
        } catch (e: Exception) {
            Log.e(TAG, "parseBrowseResponse error: ${e.message}")
        }
        return list
    }

    fun getCachedMoodTracks(moodBrowseId: String, region: String = "IN"): List<YouTubeTrack> {
        val accountScope = currentWebAuthSession()?.cookie?.let(::sha1Hex)?.take(12) ?: "guest"
        val key = "${moodBrowseId}_${region.uppercase()}_$accountScope"
        val raw = context.getSharedPreferences("ytm_mood_feeds", Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(YouTubeTrack(
                        videoId = item.getString("videoId"),
                        title = item.getString("title"),
                        artist = item.optString("artist", "Unknown Artist"),
                        album = item.optString("album", "YouTube Music"),
                        durationSeconds = item.optLong("durationSeconds"),
                        thumbnailUrl = item.optString("thumbnailUrl").ifBlank { null },
                        albumBrowseId = item.optString("albumBrowseId").ifBlank { null },
                        isOfficial = item.optBoolean("isOfficial"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistMoodTracks(moodBrowseId: String, region: String, tracks: List<YouTubeTrack>) {
        if (tracks.isEmpty()) return
        val accountScope = currentWebAuthSession()?.cookie?.let(::sha1Hex)?.take(12) ?: "guest"
        val key = "${moodBrowseId}_${region.uppercase()}_$accountScope"
        val payload = JSONArray().apply {
            tracks.distinctBy(YouTubeTrack::videoId).take(50).forEach { track ->
                put(JSONObject().apply {
                    put("videoId", track.videoId)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationSeconds", track.durationSeconds)
                    put("thumbnailUrl", track.thumbnailUrl.orEmpty())
                    put("albumBrowseId", track.albumBrowseId.orEmpty())
                    put("isOfficial", track.isOfficial)
                })
            }
        }
        context.getSharedPreferences("ytm_mood_feeds", Context.MODE_PRIVATE)
            .edit().putString(key, payload.toString()).apply()
    }

    suspend fun getMoodTracks(moodBrowseId: String, region: String = "IN"): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YouTubeTrack>()
        val cachedTracks = getCachedMoodTracks(moodBrowseId, region)
        val moodLabel = moodBrowseId.substringAfterLast('_').let {
            when (it) {
                "happy" -> "feel good"
                "romance" -> "romance"
                else -> it
            }
        }
        try {
            data class MoodEndpoint(val browseId: String, val params: String?)
            // YouTube Music's real mood/category destination is authoritative. A keyword search is
            // only a fallback when that page returns nothing, because a plain text search happily
            // returns any upload whose title merely contains the mood word.
            var resolvedEndpoint: MoodEndpoint? = null
            val moodsRoot = executeWebRemixRequest("browse", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("browseId", "FEmusic_moods_and_genres")
                }
            }
            if (!moodsRoot.isNullOrBlank()) {
                fun findEndpoint(node: Any?) {
                    if (resolvedEndpoint != null) return
                    when (node) {
                        is JSONObject -> {
                            val renderer = node.optJSONObject("musicNavigationButtonRenderer")
                            if (renderer != null && renderer.toString().contains(moodLabel, ignoreCase = true)) {
                                val endpoint = renderer.optJSONObject("clickCommand")
                                    ?.optJSONObject("browseEndpoint")
                                    ?: renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                val id = endpoint?.optString("browseId").orEmpty()
                                if (id.isNotBlank()) resolvedEndpoint = MoodEndpoint(id, endpoint?.optString("params")?.ifBlank { null })
                            }
                            node.keys().forEach { findEndpoint(node.opt(it)) }
                        }
                        is JSONArray -> for (index in 0 until node.length()) findEndpoint(node.opt(index))
                    }
                }
                findEndpoint(JSONObject(moodsRoot))
            }
            val target = resolvedEndpoint ?: MoodEndpoint(moodBrowseId, null)
            val bodyString = executeWebRemixRequest("browse", region) { config ->
                JSONObject().apply {
                    put("context", createWebRemixContext(region, config))
                    put("browseId", target.browseId)
                    target.params?.let { put("params", it) }
                }
            }
            if (!bodyString.isNullOrBlank()) {
                // Try to find tracks directly on the mood page
                tracks.addAll(parseBrowseResponse(bodyString))
                
                // If the mood page only contains playlists, let's extract the first playlist browseId
                if (tracks.isEmpty()) {
                    val root = JSONObject(bodyString)
                    val playlistIds = mutableListOf<String>()
                    fun findPlaylists(d: Any?) {
                        when (d) {
                            is JSONObject -> {
                                val browseId = d.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")
                                    ?.optString("browseId")
                                if (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD"))) {
                                    playlistIds.add(browseId)
                                }
                                d.keys().forEach { findPlaylists(d.opt(it)) }
                            }
                            is JSONArray -> {
                                for (i in 0 until d.length()) findPlaylists(d.opt(i))
                            }
                        }
                    }
                    findPlaylists(root)
                    
                    // Fetch tracks from the first 2 playlists found
                    for (pid in playlistIds.distinct().take(2)) {
                        val playlistBody = executeWebRemixRequest("browse", region) { config ->
                            JSONObject().apply {
                                put("context", createWebRemixContext(region, config))
                                put("browseId", pid)
                            }
                        }
                        if (!playlistBody.isNullOrBlank()) {
                            tracks.addAll(parseBrowseResponse(playlistBody))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMoodTracks failed for $moodBrowseId: ${e.message}")
        }
        val resolved = tracks.distinctBy { it.videoId }
            .filterNot { it.title.trim().equals(moodLabel, ignoreCase = true) }
        if (resolved.isNotEmpty()) {
            persistMoodTracks(moodBrowseId, region, resolved)
            return@withContext resolved
        }
        // Last resort: a plain keyword search, kept after the authoritative browse page so it can
        // never shadow real mood content.
        val searchedTracks = search("$moodLabel songs", region)
            .filterNot { it.title.trim().equals(moodLabel, ignoreCase = true) }
        searchedTracks
            .also { fresh -> if (fresh.isNotEmpty()) persistMoodTracks(moodBrowseId, region, fresh) }
            .ifEmpty { cachedTracks }
    }

    private fun parseSearchSuggestions(jsonString: String, query: String): List<String> {
        val suggestions = mutableListOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("searchSuggestionRenderer")
                        ?.optJSONObject("suggestion")
                        ?.let(::extractRunsText)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(suggestions::add)
                    value.keys().forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        runCatching { visit(JSONObject(jsonString)) }
        return suggestions
            .distinctBy(String::lowercase)
            .filterNot { it.equals(query, ignoreCase = true) }
            .take(8)
    }

    private fun collectTracksRecursively(json: Any?, list: MutableList<YouTubeTrack>) {
        when (json) {
            is JSONObject -> {
                if (json.has("musicResponsiveListItemRenderer")) {
                    parseListItem(json.optJSONObject("musicResponsiveListItemRenderer")!!)?.let { list.add(it) }
                }
                if (json.has("musicTwoRowItemRenderer")) {
                    parseListItem(json.optJSONObject("musicTwoRowItemRenderer")!!)?.let { list.add(it) }
                }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectTracksRecursively(json.opt(key), list)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    collectTracksRecursively(json.opt(i), list)
                }
            }
        }
    }

    private fun parseListItem(item: JSONObject): YouTubeTrack? {
        var title = ""
        var artist = ""

        val flexColumns = item.optJSONArray("flexColumns")
        if (flexColumns != null && flexColumns.length() > 0) {
            val col1 = flexColumns.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            val col2 = flexColumns.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            title = extractRunsText(col1?.optJSONObject("text"))
            artist = extractMusicArtist(col2?.optJSONObject("text"))
        }

        if (title.isBlank()) {
            title = extractRunsText(item.optJSONObject("title"))
        }
        if (artist.isBlank()) {
            artist = extractMusicArtist(item.optJSONObject("subtitle"))
        }
        if (artist.isBlank()) {
            artist = extractMusicArtist(item.optJSONObject("shortBylineText"))
        }
        if (artist.isBlank()) {
            artist = extractMusicArtist(item.optJSONObject("longBylineText"))
        }

        var videoId: String? = null
        if (item.has("playlistItemData")) {
            videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
        }

        if (videoId.isNullOrBlank()) {
            val menu = item.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
            if (menu != null) {
                for (m in 0 until menu.length()) {
                    val nav = menu.optJSONObject(m)?.optJSONObject("menuNavigationItemRenderer")?.optJSONObject("navigationEndpoint")
                    val vid = nav?.optJSONObject("watchEndpoint")?.optString("videoId")
                    if (!vid.isNullOrBlank()) {
                        videoId = vid
                        break
                    }
                }
            }
        }

        if (videoId.isNullOrBlank()) {
            val nav = item.optJSONObject("navigationEndpoint")
            videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")
        }

        if (videoId.isNullOrBlank()) {
            val doubleTap = item.optJSONObject("doubleTapEndpoint")
            videoId = doubleTap?.optJSONObject("watchEndpoint")?.optString("videoId")
        }

        if (videoId.isNullOrBlank() || title.isBlank()) return null

        val metadataText = buildString {
            append(title)
            append(' ')
            append(artist)
            append(' ')
            append(responsiveColumnText(item, 1))
            append(' ')
            append(extractRunsText(item.optJSONObject("subtitle")))
        }.lowercase()
        if (NON_MUSIC_KEYWORDS.any(metadataText::contains)) return null

        // YouTube Music catalogue only.
        //
        // The music shelves also carry ordinary YouTube uploads. Those are the rows whose cover is
        // a 16:9 video still - a face, a title card, a lyric sheet - and whose audio is frequently
        // a re-upload rather than the released recording. A row that names a video type is a video,
        // so it is rejected even for OMV: an official music video still has the wrong artwork and
        // the wrong edit for a music player. Only catalogue album tracks (ATV) and untyped rows
        // from the music shelves survive.
        val musicVideoType = findFirstString(item, "musicVideoType")
        val isAlbumTrack = musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        val isOfficialMusicVideo = musicVideoType == "MUSIC_VIDEO_TYPE_OMV"
        if (musicVideoType != null && !isAlbumTrack) return null
        // Home Quick Picks rows often omit playlistItemData even though they carry an exact
        // watchEndpoint and normal music flex columns. Requiring playlistItemData discarded the
        // signed-in user's real Quick Picks and made Home fall back to generic chart tracks.
        if (!isAlbumTrack && !item.has("flexColumns")) return null

        val thumbnail = extractThumbnail(item.optJSONObject("thumbnail"))
            ?: extractBestThumbnail(item)

        val linkedArtists = extractLinkedArtists(item)
        val albumLink = extractLinkedAlbum(item)

        return YouTubeTrack(
            videoId = videoId,
            title = title,
            // Never brand a track with the platform name. When a row genuinely carries no
            // artist, the field stays blank and the shared Song model shows the honest
            // "Unknown Artist" label instead of saving "YouTube Music" as the artist.
            artist = linkedArtists.joinToString(", ") { it.name }.ifBlank { artist },
            album = albumLink?.second ?: "YouTube Music",
            durationSeconds = extractDurationSeconds(item),
            thumbnailUrl = thumbnail,
            resultType = if (isOfficialMusicVideo) YouTubeMusicEntityType.MUSIC_VIDEO else YouTubeMusicEntityType.SONG,
            isOfficial = isAlbumTrack || isOfficialMusicVideo,
            linkedArtists = linkedArtists,
            albumBrowseId = albumLink?.first,
        )
    }

    private fun extractDurationSeconds(item: JSONObject): Long {
        val candidates = mutableListOf<String>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val text = node.optString("text").trim()
                    val simpleText = node.optString("simpleText").trim()
                    if (text.isNotBlank()) candidates += text
                    if (simpleText.isNotBlank()) candidates += simpleText
                    node.keys().forEach { key -> visit(node.opt(key)) }
                }
                is org.json.JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(item.optJSONArray("fixedColumns"))
        visit(item.optJSONArray("flexColumns"))
        visit(item.optJSONObject("lengthText"))
        val duration = candidates.lastOrNull { DURATION_TEXT_REGEX.matches(it) } ?: return 0L
        return duration.split(':').fold(0L) { total, part -> total * 60L + (part.toLongOrNull() ?: 0L) }
    }

    private fun extractLinkedArtists(item: JSONObject): List<YouTubeArtist> {
        val candidates = buildList {
            val flexColumns = item.optJSONArray("flexColumns")
            if (flexColumns != null) {
                for (index in 0 until flexColumns.length()) {
                    flexColumns.optJSONObject(index)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.let(::add)
                }
            }
            item.optJSONObject("subtitle")?.let(::add)
        }
        return candidates.flatMap { text ->
            val runs = text.optJSONArray("runs") ?: return@flatMap emptyList()
            buildList {
                for (index in 0 until runs.length()) {
                    val run = runs.optJSONObject(index) ?: continue
                    val browseEndpoint = run.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint") ?: continue
                    val browseId = browseEndpoint.optString("browseId")
                    val pageType = browseEndpoint
                        .optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType")
                    if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") || browseId.startsWith("MPLA")) {
                        val name = run.optString("text").trim()
                        if (name.isNotBlank() && browseId.isNotBlank()) {
                            add(YouTubeArtist(browseId = browseId, name = name))
                        }
                    }
                }
            }
        }.distinctBy { it.browseId }
    }

    private fun extractLinkedAlbum(item: JSONObject): Pair<String, String>? {
        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        for (columnIndex in 0 until flexColumns.length()) {
            val runs = flexColumns.optJSONObject(columnIndex)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: continue
            for (runIndex in 0 until runs.length()) {
                val run = runs.optJSONObject(runIndex) ?: continue
                val browseEndpoint = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint") ?: continue
                val browseId = browseEndpoint.optString("browseId")
                val pageType = browseEndpoint
                    .optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")
                if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE")) {
                    return browseId to run.optString("text").trim()
                }
            }
        }
        return null
    }

    private fun extractMusicArtist(textObj: JSONObject?): String {
        val runs = textObj?.optJSONArray("runs")
        if (runs != null) {
            val artistNames = buildList {
                for (index in 0 until runs.length()) {
                    val run = runs.optJSONObject(index) ?: continue
                    val value = run.optString("text").trim()
                    val browseId = findFirstString(run, "browseId")
                    if (value.isNotBlank() && (browseId?.startsWith("UC") == true || browseId?.startsWith("MPLA") == true)) {
                        add(value)
                    }
                }
            }
            if (artistNames.isNotEmpty()) return artistNames.distinct().joinToString(", ")
        }
        return asArtistLabel(extractRunsText(textObj))
    }

    /**
     * Takes the first usable segment of a `type • artist • album` line as the artist.
     *
     * The column beside a title holds the *duration* on the hero card's inline tracks, so
     * reading that segment back verbatim reported "Same Beef" as being by "4:51" instead of
     * Bohemia & Sidhu Moose Wala. That wrong name then flowed into the player, the listening
     * history and - because this app syncs to the connected account - the recommendation
     * profile, so a bare duration is never accepted as an artist.
     */
    private fun asArtistLabel(rawText: String): String {
        val segment = rawText.split(Regex("[•·]"))
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && it.lowercase() !in RESULT_TYPE_LABELS }
            .orEmpty()
        return if (segment.isNotEmpty() && DURATION_TEXT_REGEX.matches(segment)) "" else segment
    }

    private fun findFirstString(root: Any?, targetKey: String): String? {
        when (root) {
            is JSONObject -> {
                root.optString(targetKey).takeIf { it.isNotBlank() }?.let { return it }
                root.keys().forEach { key -> findFirstString(root.opt(key), targetKey)?.let { return it } }
            }
            is JSONArray -> for (index in 0 until root.length()) {
                findFirstString(root.opt(index), targetKey)?.let { return it }
            }
        }
        return null
    }

    private fun searchRelevanceScore(query: String, track: YouTubeTrack): Int {
        val wanted = normalizeSearchText(query)
        val title = normalizeSearchText(track.title)
        val artist = normalizeSearchText(track.artist)
        val exactTitle = when {
            title == wanted -> 120
            title.startsWith(wanted) -> 90
            title.contains(wanted) -> 70
            else -> 0
        }
        val tokenCoverage = wanted.split(' ').filter(String::isNotBlank)
            .count { token -> token in title || token in artist } * 8
        return exactTitle + tokenCoverage + (if (track.isOfficial) 35 else 0) +
            (if (track.resultType == YouTubeMusicEntityType.SONG) 10 else 0)
    }

    private fun searchRelevanceScore(query: String, song: Song): Int {
        val wanted = normalizeSearchText(query)
        val title = normalizeSearchText(song.title)
        val artist = normalizeSearchText(song.displayArtist)
        val exactTitle = when {
            title == wanted -> 1_000
            title.startsWith(wanted) -> 750
            title.contains(wanted) -> 550
            wanted.startsWith(title) && title.length >= 4 -> 400
            else -> 0
        }
        val tokens = wanted.split(' ').filter { it.length > 1 }
        val coverage = tokens.count { it in title || it in artist } * 45
        val missingPenalty = tokens.count { it !in title && it !in artist } * 60
        val providerBonus = if (song.id.startsWith("yt_")) 80 else 0
        return exactTitle + coverage + providerBonus - missingPenalty
    }

    private fun normalizeSearchText(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun extractRunsText(textObj: JSONObject?): String {
        if (textObj == null) return ""
        val runs = textObj.optJSONArray("runs") ?: return textObj.optString("simpleText", "")
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun extractThumbnail(thumbnailObj: JSONObject?): String? {
        val renderer = thumbnailObj?.optJSONObject("musicThumbnailRenderer")
            ?: thumbnailObj?.optJSONObject("croppedSquareThumbnailRenderer")
        val thumbnails = renderer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: thumbnailObj?.optJSONArray("thumbnails") ?: return null
        if (thumbnails.length() == 0) return null
        val last = thumbnails.optJSONObject(thumbnails.length() - 1)
        val url = last?.optString("url") ?: return null
        return highQualityArtworkUrl(url)
    }

    private fun highQualityArtworkUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            "googleusercontent.com" in url || "ggpht.com" in url -> url
                .replace(Regex("=w\\d+-h\\d+[^?&]*"), "=w1200-h1200-l90-rj")
                .replace(Regex("=s\\d+[^?&]*"), "=s1200")
            "ytimg.com/vi/" in url -> url.replace("/hqdefault.jpg", "/sddefault.jpg")
            else -> url
        }
    }

}
