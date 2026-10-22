package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.database.OnlineSongCacheDao
import com.theveloper.pixelplay.data.database.toOnlineSongCacheEntity
import com.theveloper.pixelplay.data.network.saavn.JioSaavnEngine
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeAlbumDetails
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeArtistProfile
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeMusicEngine
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeHomeShelf
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeSearchResult
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnlineMusicRepository @Inject constructor(
    private val youTubeEngine: YouTubeMusicEngine,
    private val saavnEngine: JioSaavnEngine,
    private val onlineSongCacheDao: OnlineSongCacheDao,
    private val userPreferencesRepository: com.theveloper.pixelplay.data.preferences.UserPreferencesRepository,
) {
    suspend fun getYouTubeMusicHome(region: String = "IN"): List<YouTubeHomeShelf> =
        youTubeEngine.getHomeShelves(normalizedRegion(region))

    fun getCachedYouTubeMusicHome(region: String = "IN"): List<YouTubeHomeShelf> =
        youTubeEngine.getCachedHomeShelves(normalizedRegion(region))

    private data class MoodSource(
        val browseId: String,
        val aliases: Set<String>,
        val searchQueries: List<String>,
    )
    private data class MoodCacheEntry(val songs: List<Song>, val storedAtMs: Long)

    private val moodSources = listOf(
        MoodSource("FEmusic_moods_and_genres_chill", setOf("chill", "calm", "easy"), listOf("chill songs", "chill music")),
        MoodSource("FEmusic_moods_and_genres_happy", setOf("happy", "feel good", "joy"), listOf("happy feel good songs", "happy songs")),
        MoodSource("FEmusic_moods_and_genres_workout", setOf("workout", "gym", "energy", "energise", "energize"), listOf("workout gym songs", "high energy songs")),
        MoodSource("FEmusic_moods_and_genres_focus", setOf("focus", "study", "work"), listOf("focus study music", "deep focus music")),
        MoodSource("FEmusic_moods_and_genres_romance", setOf("romantic", "romance", "love"), listOf("romantic love songs", "love songs")),
        MoodSource("FEmusic_moods_and_genres_sad", setOf("sad", "heartbreak", "melancholy"), listOf("sad heartbreak songs", "melancholy songs")),
        MoodSource("FEmusic_moods_and_genres_party", setOf("party", "dance", "celebration"), listOf("party dance songs", "celebration songs")),
        MoodSource("FEmusic_moods_and_genres_relax", setOf("relax", "relaxing", "unwind"), listOf("relaxing songs", "unwind music")),
        MoodSource("FEmusic_moods_and_genres_sleep", setOf("sleep", "bedtime", "night"), listOf("sleep music", "bedtime calming music")),
        // Languages/regions. These are real categories on the same YouTube Music
        // "moods and genres" page, so they resolve to genuine songs of that language instead of a
        // keyword search that could land on an upload whose title is just the word "Punjabi".
        MoodSource("FEmusic_moods_and_genres_punjabi", setOf("punjabi"), listOf("punjabi songs", "punjabi hits")),
        MoodSource("FEmusic_moods_and_genres_hindi", setOf("hindi", "bollywood"), listOf("hindi songs", "bollywood hits")),
        MoodSource("FEmusic_moods_and_genres_indian", setOf("indian"), listOf("indian songs", "indian hits")),
        MoodSource("FEmusic_moods_and_genres_hip-hop", setOf("hip-hop", "hip hop", "rap"), listOf("hip hop songs", "rap songs")),
        MoodSource("FEmusic_moods_and_genres_tamil", setOf("tamil"), listOf("tamil songs", "tamil hits")),
        MoodSource("FEmusic_moods_and_genres_telugu", setOf("telugu"), listOf("telugu songs", "telugu hits")),
        MoodSource("FEmusic_moods_and_genres_english", setOf("english"), listOf("english songs", "english hits")),
    )
    private val moodCache = ConcurrentHashMap<String, MoodCacheEntry>()

    private fun moodSource(label: String): MoodSource? {
        val normalized = label.trim().lowercase()
        return moodSources.firstOrNull { source -> normalized in source.aliases }
    }

    fun getCachedMoodDiscovery(label: String): List<Song> {
        val source = moodSource(label) ?: return emptyList()
        return youTubeEngine.getCachedMoodTracks(source.browseId)
            .map(YouTubeTrack::toSong)
            .distinctBy(Song::id)
    }
    suspend fun searchSongs(query: String, region: String = "IN"): List<Song> = withContext(Dispatchers.IO) {
        var results = youTubeEngine.search(query, region).map { it.toSong() }
        
        if (results.isEmpty()) {
            val suggestions = youTubeEngine.getSearchSuggestions(query, region)
            if (suggestions.isNotEmpty() && suggestions.first().isNotBlank()) {
                val correctedQuery = suggestions.first()
                if (correctedQuery.lowercase() != query.lowercase()) {
                    results = youTubeEngine.search(correctedQuery, region).map { it.toSong() }
                }
            }
        }
        
        results
    }

    suspend fun searchMusicStructured(query: String, region: String = "IN"): YouTubeSearchResult = withContext(Dispatchers.IO) {
        var youtubeResult = youTubeEngine.searchMusicStructured(query, region)
        
        // Typo tolerance: if no results, try first suggestion
        if (youtubeResult.songs.isEmpty() && youtubeResult.albums.isEmpty() && youtubeResult.artists.isEmpty()) {
            val suggestions = youTubeEngine.getSearchSuggestions(query, region)
            if (suggestions.isNotEmpty() && suggestions.first().isNotBlank()) {
                val correctedQuery = suggestions.first()
                if (correctedQuery.lowercase() != query.lowercase()) {
                    youtubeResult = youTubeEngine.searchMusicStructured(correctedQuery, region)
                }
            }
        }

        if (youtubeResult.songs.isNotEmpty() || youtubeResult.albums.isNotEmpty() || youtubeResult.artists.isNotEmpty()) {
            youtubeResult
        } else {
            // Keep search useful during temporary YouTube Music endpoint/profile failures.
            // Playback already supports these music-only Saavn results through the same player.
            YouTubeSearchResult(songs = saavnEngine.searchSongs(query))
        }
    }

    suspend fun searchYouTubeMusicStructured(
        query: String,
        region: String = "IN"
    ): YouTubeSearchResult = withContext(Dispatchers.IO) {
        youTubeEngine.searchMusicStructured(query, normalizedRegion(region))
    }

    suspend fun searchFallbackSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        saavnEngine.searchSongs(query)
    }

    
    private suspend fun filterAndPrioritizeRecommendations(songs: List<Song>): List<Song> {
        val blocked = userPreferencesRepository.blockedArtists.first().map { it.lowercase() }
        val preferred = userPreferencesRepository.preferredArtists.first().map { it.lowercase() }
        
        return songs.filter { song ->
            val songArtist = song.artist.lowercase()
            blocked.none { songArtist.contains(it) }
        }.sortedByDescending { song ->
            val songArtist = song.artist.lowercase()
            if (preferred.any { songArtist.contains(it) }) 1 else 0
        }
    }

suspend fun getFastPersonalizedDiscovery(interestLabels: List<String>): List<Song> = coroutineScope {
        val moods = interestLabels
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .take(3)

        val region = youTubeEngine.resolveRegionAwaitingNetwork(
            runCatching { userPreferencesRepository.userRegionFlow.first() }.getOrDefault("")
        )

        if (moods.isEmpty()) {
            return@coroutineScope getTrendingTracks(region)
        }

        val resolvedMoodSources = moods.mapNotNull(::moodSource).distinctBy(MoodSource::browseId)
        // This API also powers artist/genre discovery labels. Only apply strict mood behavior
        // when the caller supplied a known mood; non-mood interests retain normal discovery.
        if (resolvedMoodSources.isEmpty()) {
            // Interest labels are commonly artist names restored from account/history.  The
            // previous implementation discarded them and returned a generic chart, which made
            // one listened song unable to grow into a real taste-based discovery pool.
            val interestTracks = moods.map { interest ->
                async(Dispatchers.IO) {
                    runCatching {
                        youTubeEngine.searchMusicStructured("$interest songs", region).songs
                    }.getOrDefault(emptyList())
                }
            }.map { it.await() }.flatten()
                .filter(::isUsefulDiscoverySong)
                .distinctBy(Song::id)
            return@coroutineScope filterAndPrioritizeRecommendations(
                interestTracks.ifEmpty { getTrendingTracks(region) }.take(50)
            )
        }

        val ytTracks = resolvedMoodSources.map { source ->
            async(Dispatchers.IO) {
                val cacheKey = "${source.browseId}:$region"
                val cached = moodCache[cacheKey]
                    ?.takeIf { System.currentTimeMillis() - it.storedAtMs < MOOD_CACHE_TTL_MS }
                    ?.songs
                if (cached != null) return@async cached

                val songs = runCatching {
                    // YouTube Music's real mood/category destination is authoritative. Generic
                    // text search is only a same-provider supplement when that page is sparse.
                    val browseSongs = youTubeEngine.getMoodTracks(source.browseId, region)
                        .map(YouTubeTrack::toSong)
                    val searchedSongs = if (browseSongs.size < 12) {
                        source.searchQueries.flatMap { query ->
                            youTubeEngine.searchMusicStructured(query, region).songs
                        }
                    } else emptyList()
                    (browseSongs + searchedSongs)
                        .filter(::isUsefulDiscoverySong)
                        .distinctBy(Song::id)
                        .take(50)
                }.getOrDefault(emptyList())
                if (songs.isNotEmpty()) moodCache[cacheKey] = MoodCacheEntry(songs, System.currentTimeMillis())
                songs.ifEmpty { moodCache[cacheKey]?.songs.orEmpty() }
            }
        }.map { it.await() }.flatten()

        // A mood page must never silently turn into a generic trending list. If YouTube Music
        // cannot supply that mood, return the last valid cache (or empty) so the UI can retry.
        val rawList = ytTracks.distinctBy(Song::id).take(50)
        
        return@coroutineScope filterAndPrioritizeRecommendations(rawList)
    }

    suspend fun getTrendingTracks(region: String = "IN"): List<Song> = withContext(Dispatchers.IO) {
        val tracks = youTubeEngine.getTrendingTracks(normalizedRegion(region))
        val blocked = userPreferencesRepository.blockedArtists.first().map(String::lowercase)
        // Official provider rank is authoritative. User preferences may remove blocked artists,
        // but must never reorder a chart and turn it into a personalised shelf.
        tracks.map { it.toSong() }.filter { song ->
            val artist = song.artist.lowercase()
            blocked.none(artist::contains)
        }
    }

    suspend fun getLatestReleases(region: String = "IN"): List<Song> = withContext(Dispatchers.IO) {
        val tracks = youTubeEngine.getLatestReleases(normalizedRegion(region))
        val rawList = tracks.map { it.toSong() }
        filterAndPrioritizeRecommendations(rawList)
            // The provider can list the same release more than once (an album card plus its lead
            // track, or two uploads of the same single), which showed up as the same card repeated
            // in the New releases grid. It is a title shelf, so collapse to one card per title.
            // Rows whose artist the provider could not resolve are placeholders rather than songs
            // the listener can identify, so they are dropped instead of listing "Unknown Artist".
            .filter { song ->
                song.title.isNotBlank() &&
                    song.artist.isNotBlank() &&
                    !song.artist.equals("Unknown Artist", ignoreCase = true) &&
                    !song.artist.equals("Unknown", ignoreCase = true)
            }
            .distinctBy { it.title.trim().lowercase() }
    }

    /** Returns a seed-first continuation queue, never the raw search-result collection. */
    suspend fun getAutoplayQueue(seed: Song, region: String = "IN"): List<Song> = withContext(Dispatchers.IO) {
        if (seed.id.startsWith("yt_") || seed.id.startsWith("saavn_")) {
            onlineSongCacheDao.upsertAll(listOf(seed.toOnlineSongCacheEntity()))
        }
        val normalized = normalizedRegion(region)
        val providerContinuation = if (seed.id.startsWith("yt_") || seed.contentUriString.startsWith("yt://")) {
            youTubeEngine.getAutoplayTracks(seed.id, normalized).map { it.toSong() }
        } else {
            emptyList()
        }

        val relatedFallback = if (providerContinuation.size >= 5) emptyList() else {
            val albumSignal = seed.album
                .takeUnless { it.isBlank() || it.equals("YouTube Music", ignoreCase = true) }
                .orEmpty()
            val relatedQuery = listOf(seed.artist, albumSignal).filter(String::isNotBlank).joinToString(" ")
            if (relatedQuery.isBlank()) emptyList() else {
                val yt = runCatching { youTubeEngine.search(relatedQuery, normalized).map { it.toSong() } }
                    .getOrDefault(emptyList())
                yt.filter(::isUsefulDiscoverySong)
            }
        }

        val continuation = keepSeedStyle(seed, providerContinuation + relatedFallback)
        val rawList = listOf(seed) + continuation
            .filterNot { it.id == seed.id }
            .distinctBy { it.id }
            .take(49)
            
        filterAndPrioritizeRecommendations(rawList)
    }

    /**
     * Builds a temporary continuation for a user playlist. The saved playlist is never mutated:
     * callers append these tracks only to the playback queue after every original item.
     */
    suspend fun getPlaylistContinuation(
        playlistSongs: List<Song>,
        limit: Int = 20,
    ): List<Song> = coroutineScope {
        if (playlistSongs.isEmpty() || userPreferencesRepository.dataSaverEnabledFlow.first()) {
            return@coroutineScope emptyList()
        }
        val region = normalizedRegion(userPreferencesRepository.userRegionFlow.first())
        val originalIds = playlistSongs.map(Song::id).toSet()
        val representativeSeeds = buildList {
            playlistSongs.lastOrNull()?.let(::add)
            playlistSongs.groupBy { it.artist.trim().lowercase() }
                .maxByOrNull { it.value.size }?.value?.firstOrNull()?.let(::add)
            playlistSongs.getOrNull(playlistSongs.size / 2)?.let(::add)
        }.distinctBy(Song::id).take(3)

        val candidates = representativeSeeds.map { seed ->
            async(Dispatchers.IO) {
                runCatching { getAutoplayQueue(seed, region) }.getOrDefault(emptyList())
            }
        }.map { it.await() }.flatten()

        candidates.asSequence()
            .filterNot { it.id in originalIds }
            .filter(::isUsefulDiscoverySong)
            .distinctBy(Song::id)
            .take(limit)
            .toList()
    }

    /** Prevents autoplay from jumping between unrelated languages/styles. */
    private fun keepSeedStyle(seed: Song, candidates: List<Song>): List<Song> {
        val seedText = "${seed.title} ${seed.artist} ${seed.album} ${seed.genre.orEmpty()}".lowercase()
        val styleTerms = listOf(
            "punjabi" to listOf("punjabi", "ਪੰਜਾਬੀ", "sidhu", "diljit", "karan aujla", "ap dhillon", "shubh"),
            "haryanvi" to listOf("haryanvi", "हरियाणवी"),
            "funk" to listOf("funk", "phonk"),
            "lofi" to listOf("lofi", "lo-fi"),
            "sufi" to listOf("sufi", "qawwali"),
            "kpop" to listOf("k-pop", "kpop"),
        )
        val detected = styleTerms.firstOrNull { (_, terms) -> terms.any(seedText::contains) }?.second
        val seedArtist = seed.artist.substringBefore(',').substringBefore('&').trim()
        if (detected == null && seedArtist.isBlank()) return candidates
        val strict = candidates.filter { song ->
            val text = "${song.title} ${song.artist} ${song.album} ${song.genre.orEmpty()}".lowercase()
            (detected?.any(text::contains) == true) ||
                seedArtist.length >= 3 && song.artist.contains(seedArtist, ignoreCase = true)
        }
        return if (strict.size >= 5) strict else candidates.sortedByDescending { song ->
            val text = "${song.title} ${song.artist} ${song.album} ${song.genre.orEmpty()}".lowercase()
            (detected?.count(text::contains) ?: 0) * 10 +
                if (seedArtist.length >= 3 && song.artist.contains(seedArtist, true)) 5 else 0
        }
    }

    private fun normalizedRegion(region: String): String = youTubeEngine.resolveRegion(region)

    private fun isUsefulDiscoverySong(song: Song): Boolean {
        val metadata = "${song.title} ${song.artist} ${song.album}".lowercase()
        val junkPhrases = listOf(
            "global trending",
            "global headlines",
            "breaking news",
            "stress relief",
            "meditation music",
            "kids sleep",
            "sleep baby",
            "natural dreams",
        )
        return song.title.isNotBlank() && song.artist.isNotBlank() && junkPhrases.none(metadata::contains)
    }

    suspend fun getArtistProfile(browseId: String): YouTubeArtistProfile? = withContext(Dispatchers.IO) {
        youTubeEngine.getArtistProfile(browseId)
    }

    suspend fun getAlbumDetails(browseId: String): YouTubeAlbumDetails? = withContext(Dispatchers.IO) {
        youTubeEngine.getAlbumDetails(browseId)
    }

    suspend fun getTrackDetails(videoId: String, region: String = "IN"): Song? = withContext(Dispatchers.IO) {
        if (videoId.startsWith("saavn_") || videoId.startsWith("saavn://")) return@withContext null
        val cleanId = videoId.removePrefix("yt_").removePrefix("yt://")
        cleanId.takeIf { it.isNotBlank() }
            ?.let { youTubeEngine.getTrackDetails(it, normalizedRegion(region))?.toSong() }
    }

    suspend fun resolvePlaybackUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // Provider identity wins over a persisted direct URL. Signed stream URLs expire and a
        // URL copied onto the wrong metadata row can otherwise play a different same-title song.
        // Re-resolving the immutable provider ID keeps artwork, title and audio bound together.
        if (song.id.startsWith("saavn_") || song.path.startsWith("saavn://")) {
            return@withContext saavnEngine.resolveStreamById(song.id)
        }
        
        val isExactYouTubeTrack = song.id.startsWith("yt_") || song.contentUriString.startsWith("yt://")
        if (isExactYouTubeTrack) {
            // Never replace a known YouTube video ID with a title-based provider match. That
            // could play a different recording which happens to have the same song title.
            val exactId = song.contentUriString.removePrefix("yt://")
                .takeIf { song.contentUriString.startsWith("yt://") && it.isNotBlank() }
                ?: song.id.removePrefix("yt_")
            return@withContext youTubeEngine.resolveStreamUrl(exactId)
        }

        if (song.path.startsWith("http://") || song.path.startsWith("https://") || song.path.startsWith("/")) {
            return@withContext song.path
        }

        // 2. Fallback to Saavn by query
        val query = "${song.title} ${song.artist}".trim()
        if (query.isNotBlank()) {
            val saavnUrl = saavnEngine.resolveStreamByQuery(query)
            if (!saavnUrl.isNullOrBlank()) return@withContext saavnUrl
        }
        null
    }

    /** Resolves a provider URL at download time instead of trusting a possibly expired signed URL. */
    suspend fun resolveFreshDownloadUrl(song: Song): String? = withContext(Dispatchers.IO) {
        when {
            song.id.startsWith("yt_") || song.contentUriString.startsWith("yt://") -> {
                youTubeEngine.invalidateStreamUrl(song.id)
                youTubeEngine.resolveStreamUrl(song.id)
            }
            song.id.startsWith("saavn_") || song.contentUriString.startsWith("saavn://") -> {
                saavnEngine.invalidateCache(song.id)
                saavnEngine.resolveStreamById(song.id)
            }
            else -> resolvePlaybackUrl(song)
        }
    }

    /**
     * Warms provider stream URLs before the user presses play.
     *
     * [com.theveloper.pixelplay.data.service.player.DualPlayerEngine] only resolves a stream
     * inside [androidx.media3.datasource.ResolvingDataSource], i.e. when ExoPlayer begins
     * loading the item. On a cold cache that puts a full Innertube round trip - typically 1-3 s,
     * far worse on a congested link - between the tap and the first audio byte, and it has to
     * finish before any buffering can start. Resolving ahead of time fills the engine's URL
     * cache, so that step becomes a hit and the request that used to be serial now overlaps the
     * queue/metadata call already in flight.
     *
     * Failures are swallowed deliberately: a cold resolve at play time still succeeds, so
     * prefetching can only ever make playback start sooner.
     */
    suspend fun prefetchStreamUrls(songs: List<Song>, limit: Int = 2) {
        val eligible = songs
            .filter { song ->
                song.id.startsWith("yt_") || song.id.startsWith("saavn_") ||
                    song.contentUriString.startsWith("yt://") ||
                    song.contentUriString.startsWith("saavn://")
            }
            .distinctBy { it.id.ifBlank { it.contentUriString } }
            .take(limit.coerceAtLeast(0))
        if (eligible.isEmpty()) return
        // Deliberately sequential. Warming several tracks in parallel launches one full resolver
        // race per track, and those requests contend for the same link as the track the user is
        // actually waiting to hear - measured on a congested link the parallel warm added ~17 s
        // of buffering to a tap. The tap's own race is never queued behind this: it is keyed by
        // video id through resolveStreamUrlShared, so it starts immediately either way.
        eligible.forEach { song ->
            runCatching { resolvePlaybackUrl(song) }
        }
    }

    fun resolvePlaybackUrlSync(videoId: String, title: String, artist: String, isSaavn: Boolean): String? {
        if (isSaavn) {
            return kotlinx.coroutines.runBlocking(Dispatchers.IO) { saavnEngine.resolveStreamById(videoId) }
        }

        // A non-Saavn item reaching this path carries an exact YouTube video ID. Preserve that
        // identity even when one resolver temporarily fails; a title query is not equivalent.
        return youTubeEngine.resolveStreamUrlSync(videoId)
    }

    fun invalidateStreamUrl(videoId: String) {
        youTubeEngine.invalidateStreamUrl(videoId)
        saavnEngine.invalidateCache(videoId)
    }

    suspend fun getSearchSuggestions(query: String, region: String): List<String> {
        return youTubeEngine.getSearchSuggestions(query, region)
    }

    private companion object {
        const val MOOD_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    }
}
