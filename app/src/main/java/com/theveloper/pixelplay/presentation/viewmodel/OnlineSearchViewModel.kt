package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.ai.AiPlaylistGenerator
import com.theveloper.pixelplay.data.database.AudiusFavoriteDao
import com.theveloper.pixelplay.data.database.toAudiusFavoriteEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeAlbum
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeArtist
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeMusicEngine
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeSearchResult
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeTopResult
import com.theveloper.pixelplay.data.network.ytmusic.isRealSongEntry
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeAccountManager
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.OnlineMusicRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.recommendation.RecommendationProfile
import com.theveloper.pixelplay.data.recommendation.RecommendationSurface
import com.theveloper.pixelplay.data.recommendation.UnifiedRecommendationEngine
import com.theveloper.pixelplay.data.spotify.SpotifyAccountRepository
import com.theveloper.pixelplay.data.cache.SearchDiscoveryCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

enum class OnlineSearchFilter {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS
}

/**
 * Normalized identity of a recording. The same song is routinely published under several video
 * ids (album, single, topic channel, regional re-upload) and YouTube Music treats those as
 * different items. Grouping by this key is what stops "the same song again" from appearing in
 * Quick Picks, Recommended for you and charts.
 */
internal fun trackKeyOf(title: String, artist: String): String {
    fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    val leadArtist = artist.substringBefore(",").substringBefore("&").trim()
    return normalize(title) + "|" + normalize(leadArtist)
}

/** Collapses the same recording under different video ids into a single entry. */
internal fun List<Song>.distinctByTrack(): List<Song> = distinctBy { trackKeyOf(it.title, it.artist) }

/** A focused mood/genre destination opened as a popup so it never replaces the Explore feed. */
data class DiscoveryDetailState(
    val title: String,
    val isLoading: Boolean,
    val songs: List<Song>,
)

/** Moods are backed by YouTube Music mood destination pages. */private val MOOD_LABELS = setOf(
    "Workout", "Feel good", "Energize", "Relax", "Romance", "Romantic",
    "Focus", "Party", "Chill", "Sleep", "Sad", "Happy"
)

/** Languages/regions are genre destinations: they must resolve to real songs of that genre. */
private val GENRE_LABELS = setOf(
    "Punjabi", "Indian", "Hip-hop", "Hindi", "Bollywood", "Tamil", "Telugu", "English"
)

/**
 * Precomputed lowercase form of [MOOD_LABELS] and [GENRE_LABELS]. The title filter runs for every
 * candidate track returned by a browse page, so the set is built once instead of per song.
 */
private val MOOD_AND_GENRE_LABELS_LOWER: Set<String> =
    (MOOD_LABELS + GENRE_LABELS).mapTo(HashSet()) { it.lowercase() }

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    private val aiPlaylistGenerator: AiPlaylistGenerator,
    private val userPrefs: UserPreferencesRepository,
    private val favoriteDao: AudiusFavoriteDao,
    private val repository: OnlineMusicRepository,
    private val engine: YouTubeMusicEngine,
    private val musicRepository: MusicRepository,
    private val youTubeAccountManager: YouTubeAccountManager,
    private val recommendationEngine: UnifiedRecommendationEngine,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val spotifyAccountRepository: SpotifyAccountRepository,
    private val searchDiscoveryCache: com.theveloper.pixelplay.data.cache.SearchDiscoveryCache,
) : ViewModel() {

    private val _searchFilter = MutableStateFlow(OnlineSearchFilter.ALL)
    val searchFilter: StateFlow<OnlineSearchFilter> = _searchFilter.asStateFlow()

    private val _searchResultsSongs = MutableStateFlow<List<Song>>(emptyList())
    val searchResultsSongs: StateFlow<List<Song>> = _searchResultsSongs.asStateFlow()

    private val _searchResultsAlbums = MutableStateFlow<List<YouTubeAlbum>>(emptyList())
    val searchResultsAlbums: StateFlow<List<YouTubeAlbum>> = _searchResultsAlbums.asStateFlow()

    private val _searchResultsArtists = MutableStateFlow<List<YouTubeArtist>>(emptyList())
    val searchResultsArtists: StateFlow<List<YouTubeArtist>> = _searchResultsArtists.asStateFlow()

    /**
     * The provider-authored hero card for the current query, so the "Top result" block matches
     * YouTube Music instead of falling back to whichever song happened to be first.
     */
    private val _searchTopResult = MutableStateFlow<YouTubeTopResult?>(null)
    val searchTopResult: StateFlow<YouTubeTopResult?> = _searchTopResult.asStateFlow()

    // Backward compatibility for legacy observers
    val searchResults: StateFlow<List<Song>> = _searchResultsSongs

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _trendingTracks = MutableStateFlow<List<Song>>(emptyList())
    val trendingTracks: StateFlow<List<Song>> = _trendingTracks.asStateFlow()
    private var baseTrendingTracks: List<Song> = emptyList()

    private val _latestReleaseTracks = MutableStateFlow<List<Song>>(emptyList())
    val latestReleaseTracks: StateFlow<List<Song>> = _latestReleaseTracks.asStateFlow()

    private val _discoveryArtists = MutableStateFlow<List<YouTubeArtist>>(emptyList())
    val discoveryArtists: StateFlow<List<YouTubeArtist>> = _discoveryArtists.asStateFlow()

    private val _discoveryTitle = MutableStateFlow("Best for You")
    val discoveryTitle: StateFlow<String> = _discoveryTitle.asStateFlow()

    private val _aiRecommendations = MutableStateFlow<List<Song>>(emptyList())
    val aiRecommendations: StateFlow<List<Song>> = _aiRecommendations.asStateFlow()

    /**
     * Incremented by the refresh button. The ranked pool is the same on every fetch, so without
     * a generation the button re-rendered an identical grid and looked broken.
     */
    private var recommendationGeneration = 0

    /**
     * Charts live in their own state so a background Home/trending refresh can never overwrite a
     * chart request and end up rendering non-chart content under the "Top Charts" heading.
     */
    private val _chartTracks = MutableStateFlow<List<Song>>(emptyList())
    val chartTracks: StateFlow<List<Song>> = _chartTracks.asStateFlow()

    /** Currently open mood/genre popup. Null means the Explore feed is the active surface. */
    private val _discoveryDetail = MutableStateFlow<DiscoveryDetailState?>(null)
    val discoveryDetail: StateFlow<DiscoveryDetailState?> = _discoveryDetail.asStateFlow()
    private var discoveryDetailJob: Job? = null

    private val _recentSearches = MutableStateFlow<List<com.theveloper.pixelplay.data.model.SearchHistoryItem>>(emptyList())
    val recentSearches: StateFlow<List<com.theveloper.pixelplay.data.model.SearchHistoryItem>> = _recentSearches.asStateFlow()

    val searchHistoryEnabled: StateFlow<Boolean> = userPrefs.searchHistoryEnabledFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val isYouTubeMusicConnected: StateFlow<Boolean> = youTubeAccountManager.isLoggedInFlow
    val accountInterestLabels: StateFlow<List<String>> = youTubeAccountManager.interestLabelsFlow

    private val _querySuggestions = MutableStateFlow<List<String>>(emptyList())
    val querySuggestions: StateFlow<List<String>> = _querySuggestions.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    val favoriteIds = favoriteDao.getAllFavorites()

    private var currentQuery: String = ""
    private var searchJob: Job? = null
    private var discoveryJob: Job? = null
    private var suggestionJob: Job? = null
    private val structuredSearchCache = ConcurrentHashMap<String, YouTubeSearchResult>()

    private fun discoveryAccountScope(): String =
        if (!youTubeAccountManager.isLoggedInFlow.value) "guest"
        else "account_${youTubeAccountManager.accountIdentityFlow.value.hashCode()}"

    fun setFilter(filter: OnlineSearchFilter) {
        _searchFilter.value = filter
    }

    fun toggleFavorite(song: Song, isFav: Boolean) {
        viewModelScope.launch {
            if (isFav) favoriteDao.delete(song.id) else favoriteDao.insert(song.toAudiusFavoriteEntity())
        }
    }

    init {
        // Instant Search Area: Pre-populate from in-memory snapshot in 0ms so user never waits!
        try {
            val cached = searchDiscoveryCache.get()
            // A persisted discovery snapshot is presentation cache, not account data.  Show it
            // immediately even while account identity is being restored, then replace it with
            // the signed-in feed below.  Requiring the identity scope here caused a blank
            // Explore screen during cold starts and after a token refresh.
            if (cached != null &&
                (cached.bestForYouTracks.isNotEmpty() || cached.aiRecommendations.isNotEmpty())) {
                _trendingTracks.value = cached.bestForYouTracks.filter(::isCleanOriginalAudio)
                baseTrendingTracks = _trendingTracks.value
                _aiRecommendations.value = cached.aiRecommendations.filter(::isCleanOriginalAudio)
                _latestReleaseTracks.value = cached.latestReleases.filter(::isCleanOriginalAudio)
                _discoveryTitle.value = "Best for You"
                _isLoading.value = false
            }
        } catch (_: Throwable) {
            // Safe fallback: fresh recommendations will load below
        }
        viewModelScope.launch {
            loadTrendingAndRecommendations()
        }
        viewModelScope.launch {
            searchHistoryEnabled.collectLatest { enabled ->
                if (enabled) loadRecentSearches() else _recentSearches.value = emptyList()
            }
        }
        viewModelScope.launch {
            combine(
                youTubeAccountManager.isLoggedInFlow,
                youTubeAccountManager.interestLabelsFlow,
                youTubeAccountManager.syncRevisionFlow,
            ) { loggedIn, interests, revision -> Triple(loggedIn, interests, revision) }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { (loggedIn, _, _) ->
                    // Replace the guest discovery snapshot as soon as account sync publishes
                    // its identity/taste signals. Never show guest recommendations as if they
                    // belonged to the connected account.
                    if (loggedIn) {
                        _aiRecommendations.value = emptyList()
                    }
                    loadTrendingAndRecommendations()
                }
        }
    }

    private fun loadRecentSearches() {
        viewModelScope.launch {
            _recentSearches.value = if (searchHistoryEnabled.value) {
                musicRepository.getRecentSearchHistory(12)
            } else {
                emptyList()
            }
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch {
            musicRepository.deleteSearchHistoryItemByQuery(query)
            loadRecentSearches()
        }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        if (searchHistoryEnabled.value) {
            viewModelScope.launch {
                musicRepository.addSearchHistoryItem(trimmed)
                loadRecentSearches()
            }
        }
        search(trimmed, debounce = false)
    }

    fun rememberSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        if (searchHistoryEnabled.value) {
            viewModelScope.launch {
                musicRepository.addSearchHistoryItem(trimmed)
                loadRecentSearches()
            }
        }
    }

    fun fetchTrending() {
        loadTrendingAndRecommendations()
    }

    /** User-triggered refresh of the personalized Explore feed. */
    fun refreshRecommendations() {
        _discoveryDetail.value = null
        // A plain reload returns exactly the same ranked pool, so the grid never changed. Advance
        // the generation first so the next result is rotated onto a different window of the pool.
        recommendationGeneration += 1
        loadTrendingAndRecommendations()
    }

    /**
     * Opens a focused mood/genre destination as a popup. The Explore feed behind it is untouched,
     * so switching between moods never rewrites the main "Recommended for you" shelf.
     */
    fun openDiscoveryDetail(label: String) {
        discoveryDetailJob?.cancel()
        val title = if (label in MOOD_LABELS) "$label for you" else "$label songs"
        _discoveryDetail.value = DiscoveryDetailState(title = title, isLoading = true, songs = emptyList())
        discoveryDetailJob = viewModelScope.launch {
            try {
                val resolved = repository
                    .getFastPersonalizedDiscovery(listOf(label))
                    .filter(::isCleanOriginalAudio)
                    .distinctByTrack()
                _discoveryDetail.value = DiscoveryDetailState(title, isLoading = false, songs = resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _discoveryDetail.value = DiscoveryDetailState(title, isLoading = false, songs = emptyList())
            }
        }
    }

    fun closeDiscoveryDetail() {
        discoveryDetailJob?.cancel()
        _discoveryDetail.value = null
    }

    /**
     * Moves the visible window of the ranked pool on every explicit refresh.
     *
     * The recommendation engine is deterministic, so reloading returned the exact same ordered
     * list and the refresh button appeared to do nothing. Rotating by a generation-derived offset
     * shows genuinely different tracks while keeping the engine's ranking intact.
     */
    private fun rotateForRefresh(pool: List<Song>, generation: Int): List<Song> {
        if (pool.size <= 1 || generation <= 0) return pool
        val offset = (generation * 7) % pool.size
        if (offset == 0) return pool
        return pool.drop(offset) + pool.take(offset)
    }

    private fun isCleanOriginalAudio(song: Song): Boolean {
        // 1. Strictly exclude videos (only pure audio tracks)
        if (song.isMusicVideo || song.contentUriString.startsWith("yt_video:") || song.id.startsWith("yt_video_")) {
            return false
        }

        val title = song.title.lowercase()
        val artist = song.artist.lowercase()
        val album = song.album.lowercase()
        val combined = "$title $artist $album"

        // 2. Strictly filter out remixes, dhol, and fan-made modifications
        val bannedTerms = listOf(
            "remix", "dhol", "slowed", "reverb", "bass boosted", "boosted",
            "dj ", " dj", "mashup", "club mix", "flip", "sped up", "speed up",
            "trap mix", "lo-fi", "lofi", "cover", "acoustic", "karaoke",
            "ringtone", "status", "whatsapp", "tik tok", "tiktok", "reels", "shorts",
            "bgm", "instrumental", "jukebox", "non stop", "nonstop", "megamix",
            "compilation", "full album", "all songs", "audios", "edit", "vibes mix",
            "music video", "official video", "lyric video",
            // Spatial/novelty fan edits. These are re-uploads of someone else's recording with a
            // filter applied, so they are never the real song a listener searched for.
            "8d audio", "8d version", "8d music", "8d song", "8d beat", "8d sound",
            "3d audio", "3d version", "nightcore", "chipmunk", "sped up version",
            "reverb version", "slowed version", "tiktok version", "reels version",
            "whatsapp status", "ringtone version", "8-bit", "8bit version",
            // Devotional / discourse content. It lives in the regional YouTube Music Home
            // shelves but is not something a listener asked for, so it must never be
            // promoted into "Recommended for you" or a chart.
            "aarti", "arti ", "aarti ", "bhajan", "bhajans", "chalisa", "kirtan",
            "devotional", "pravachan", "katha", "mantra", "shloka", "stotram",
            "puja ", "pooja ", "havan", "satsang", "kirtan", "dhun", "vandana",
        )
        if (bannedTerms.any { combined.contains(it) }) {
            return false
        }

        // 2b. A track whose whole title is just the search/mood word ("Punjabi", "Feel good")
        // is a keyword artefact, never the song the listener meant.
        if (title.trim() in MOOD_AND_GENRE_LABELS_LOWER) {
            return false
        }

        // 3. Must have normal song duration (1 to 10 minutes)
        // Home/chart browse responses sometimes omit duration even for valid music tracks.
        // Reject known outliers, but do not discard provider-authored songs with unknown length.
        if (song.duration > 0L && song.duration !in 60_000L..600_000L) {
            return false
        }

        return true
    }

    private fun loadTrendingAndRecommendations() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            if (_trendingTracks.value.isEmpty() && _aiRecommendations.value.isEmpty()) {
                _isLoading.value = true
            }
            try {
                // Fetch recent listening history
                val historyEntries = runCatching { playbackStatsRepository.loadPlaybackHistory(50) }.getOrDefault(emptyList())
                val historySongs = historyEntries.mapNotNull { it.track?.toSong(it.songId) }
                    .filter(::isCleanOriginalAudio)
                    .distinctBy { it.id }
                // A local history is available without network access.  Use it as a temporary
                // first paint instead of leaving Explore blank while the remote shelves refresh.
                if (_trendingTracks.value.isEmpty() && historySongs.isNotEmpty()) {
                    _trendingTracks.value = historySongs
                    baseTrendingTracks = historySongs
                    // Do not seed "Recommended for you" with the listening history. Replaying what
                    // the listener just heard under a recommendation header is exactly what made
                    // the shelf feel wrong; it stays empty until the ranked pool is ready.
                    _isLoading.value = false
                }
                val listenedSongIds = historySongs.map { it.id }.toSet()
                val listenedArtists = historySongs.map { it.artist }.filter(String::isNotBlank).distinct()

                // Spotify recommendations / top tracks (Meld style)
                val spotifyTopTracks = if (spotifyAccountRepository.isLoggedIn.value) {
                    runCatching { spotifyAccountRepository.getTopTracksForRecommendations() }.getOrDefault(emptyList())
                } else emptyList()
                val spotifyArtists = spotifyTopTracks.map { it.artists }.filter(String::isNotBlank).distinct()

                val preferredArtists = userPrefs.preferredArtists.first().toList()
                val interests = (listenedArtists + spotifyArtists + youTubeAccountManager.interestLabelsFlow.value + preferredArtists)
                    .filter(String::isNotBlank)
                    .distinctBy(String::lowercase)

                val region = normalizedRegion(userPrefs.userRegionFlow.first())

                // 1. Radio seed exploration (RDAMVM).
                // Seeds come from the most recent listening history in order, never a random
                // sample: discovery must stay a continuation of what the user actually plays,
                // and a shuffle here is what previously surfaced songs with no relation to them.
                val historySeeds = historySongs.take(2)
                val seedRadioRequests = historySeeds.map { seed ->
                    async {
                        runCatching { repository.getAutoplayQueue(seed, region) }.getOrDefault(emptyList())
                    }
                }

                val fastRequest = async { repository.getFastPersonalizedDiscovery(interests) }
                val trendingRequest = async { runCatching { repository.getTrendingTracks(region) } }
                val releasesRequest = async { runCatching { repository.getLatestReleases(region) } }
                val homeRequest = async { runCatching { repository.getYouTubeMusicHome(region) } }

                val seedRadioTracks = seedRadioRequests.map { it.await() }.flatten()
                val fastTracks = withTimeoutOrNull(3_500L) { fastRequest.await() }.orEmpty()

                // 2. EchoMusic Anti-History Deduplication + Strict Original Audio Filter:
                // Strictly exclude songs already listened to in recent history, remixes, and videos!
                val freshRadioTracks = seedRadioTracks
                    .filter(::isCleanOriginalAudio)
                    .filterNot { it.id in listenedSongIds }

                val freshFastTracks = fastTracks
                    .filter(::isCleanOriginalAudio)
                    .filterNot { it.id in listenedSongIds }

                val trendingResult = withTimeoutOrNull(3_500L) { trendingRequest.await() }
                    ?: Result.success(emptyList())
                // Release validation resolves provider dates and is intentionally slower than
                // charts. It runs independently, so give it enough time without blocking the
                // already-visible cached Explore feed.
                val releasesResult = withTimeoutOrNull(12_000L) { releasesRequest.await() }
                    ?: Result.success(emptyList())
                // Explore is a browse surface: it must never wait behind a slow Home endpoint.
                // A later refresh can enrich it, while this bounded request keeps first paint fast.
                val homeShelves = withTimeoutOrNull(4_500L) { homeRequest.await() }
                    ?.getOrDefault(emptyList())
                    .orEmpty()
                val providerHomeTracks = homeShelves.asSequence()
                    .flatMap { it.songs.asSequence() }
                    .distinctBy { it.id }
                    .filter(::isCleanOriginalAudio)
                    .toList()
                val chartTracks = trendingResult.getOrDefault(emptyList()).distinctBy { it.id }
                    .filter(::isCleanOriginalAudio)
                val releases = releasesResult.getOrDefault(emptyList()).distinctBy { it.id }
                    .filter(::isCleanOriginalAudio)

                if (providerHomeTracks.isNotEmpty()) {
                    // The account's own YouTube Music Home graph is the authoritative
                    // "Best for You" surface. Regional charts are a separate, explicitly
                    // labelled destination and must not become the default feed.
                    baseTrendingTracks = providerHomeTracks.distinctByTrack()
                    _trendingTracks.value = baseTrendingTracks
                } else if (chartTracks.isNotEmpty()) {
                    baseTrendingTracks = chartTracks.distinctByTrack()
                    _trendingTracks.value = baseTrendingTracks
                } else if (releases.isNotEmpty()) {
                    _trendingTracks.value = releases.distinctByTrack()
                } else if (historySongs.isNotEmpty()) {
                    _trendingTracks.value = historySongs.distinctByTrack()
                }

                _latestReleaseTracks.value = releases.distinctByTrack()
                _discoveryTitle.value = "Best for You"

                // Combined fresh discovery pool
                val accountConnected = youTubeAccountManager.isLoggedInFlow.value
                val freshDiscoveries = (if (accountConnected) {
                    // In account mode, Explore recommendations must originate from that
                    // account's YouTube Music Home/history graph. Charts remain a separate,
                    // explicitly labelled surface and must not leak into "Best for You".
                    providerHomeTracks.filterNot { it.id in listenedSongIds }
                } else if (historySongs.isNotEmpty()) {
                    // Once a user has supplied even one real taste signal, do not dilute the
                    // Explore shelf with unrelated regional charts. The seed radio and artist
                    // searches are the authoritative recommendation pool.
                    freshRadioTracks + freshFastTracks
                } else {
                    chartTracks + providerHomeTracks
                })
                    .distinctByTrack()

                val finalRecommendationPool = if (freshDiscoveries.isNotEmpty()) {
                    freshDiscoveries
                } else if (accountConnected) {
                    historySongs
                } else {
                    // Graceful fallback if completely offline or empty
                    (historySongs + chartTracks + releases).distinctBy { it.id }
                }.filter(::isCleanOriginalAudio)

                val rankedRecommendations = rankForInterests(finalRecommendationPool, interests)
                    .filter(::isCleanOriginalAudio)
                    .distinctByTrack()
                    .let { pool -> rotateForRefresh(pool, recommendationGeneration) }
                    .take(15)

                _aiRecommendations.value = rankedRecommendations
                _searchError.value = null

                // Save snapshot for next instant startup
                searchDiscoveryCache.put(
                    SearchDiscoveryCache.DiscoverySnapshot(
                        // A chip/mood request may finish while this refresh is running. Never
                        // persist that focused result as the next launch's generic Top songs.
                        bestForYouTracks = baseTrendingTracks.ifEmpty { _trendingTracks.value },
                        aiRecommendations = rankedRecommendations,
                        latestReleases = releases,
                        accountScope = discoveryAccountScope(),
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_trendingTracks.value.isEmpty()) {
                    val fallback = runCatching { playbackStatsRepository.loadPlaybackHistory(20) }
                        .getOrDefault(emptyList())
                        .mapNotNull { it.track?.toSong(it.songId) }
                        .filter(::isCleanOriginalAudio)
                    _trendingTracks.value = fallback
                    _aiRecommendations.value = fallback.take(12)
                }
                _searchError.value = error.message ?: "Could not load YouTube Music discovery"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun generateAiRecommendations(candidates: List<Song>) {
        viewModelScope.launch {
            val genres: Set<String> = userPrefs.preferredGenres.first()
            val artists: Set<String> = userPrefs.preferredArtists.first()
            if (genres.isEmpty() && artists.isEmpty()) {
                _aiRecommendations.value = candidates.take(12)
                return@launch
            }

            val genreStr = genres.joinToString(", ")
            val artistStr = artists.joinToString(", ")
            val prompt = "User prefers genres: $genreStr. Favorite artists: $artistStr. " +
                "Filter and rank the best matching songs for them from the candidate list."

            val result = aiPlaylistGenerator.generate(
                userPrompt = prompt,
                allSongs = emptyList(),
                minLength = 5,
                maxLength = 20,
                candidateSongs = candidates,
                type = com.theveloper.pixelplay.data.ai.AiSystemPromptType.PLAYLIST
            )

            result.onSuccess { recommended ->
                _aiRecommendations.value = recommended
            }.onFailure {
                _aiRecommendations.value = candidates.take(12)
            }
        }
    }

    /** Search YouTube Music for a genre chip or keyword. */
    fun searchByGenre(genre: String) {
        search(genre, debounce = false)
    }

    /** Loads an idle-screen discovery feed without turning the chip into a search query. */
    fun selectDiscovery(label: String) {
        searchJob?.cancel()
        when (label) {
            "Best for You", "Trending" -> {
                _discoveryArtists.value = emptyList()
                _trendingTracks.value = baseTrendingTracks
                _discoveryTitle.value = "Best for You"
                if (baseTrendingTracks.isEmpty()) loadTrendingAndRecommendations()
            }
            "Charts" -> {
                _discoveryArtists.value = emptyList()
                _discoveryTitle.value = "Top Charts"
                searchJob = viewModelScope.launch {
                    _isLoading.value = true
                    _searchError.value = null
                    try {
                        val region = normalizedRegion(userPrefs.userRegionFlow.first())
                        val charts = repository.getTrendingTracks(region)
                            .filter(::isCleanOriginalAudio)
                            .distinctByTrack()
                        _chartTracks.value = charts
                        if (charts.isEmpty()) {
                            _searchError.value = "YouTube Music did not return a chart for this region."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _chartTracks.value = emptyList()
                        _searchError.value = error.message ?: "Could not load YouTube Music charts"
                    } finally {
                        _isLoading.value = false
                    }
                }
            }
            "Latest Releases" -> {
                _discoveryArtists.value = emptyList()
                _discoveryTitle.value = "Latest Releases"
                if (_latestReleaseTracks.value.isNotEmpty()) {
                    _trendingTracks.value = _latestReleaseTracks.value
                } else {
                    searchJob = viewModelScope.launch {
                        _isLoading.value = true
                        try {
                            val region = normalizedRegion(userPrefs.userRegionFlow.first())
                            val releases = repository.getLatestReleases(region)
                            _latestReleaseTracks.value = releases
                            _trendingTracks.value = releases
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _trendingTracks.value = emptyList()
                            _searchError.value = error.message ?: "Could not load latest releases"
                        } finally {
                            _isLoading.value = false
                        }
                    }
                }
            }
            else -> {
                searchJob = viewModelScope.launch {
                    _isLoading.value = true
                    _searchError.value = null
                    try {
                        val region = normalizedRegion(userPrefs.userRegionFlow.first())
                        val resolved = repository
                            .getFastPersonalizedDiscovery(listOf(label))
                            .filter(::isCleanOriginalAudio)
                            .distinctByTrack()
                        _trendingTracks.value = resolved
                        _discoveryArtists.value = emptyList()
                        _discoveryTitle.value = when {
                            label in MOOD_LABELS -> "$label for you"
                            else -> "$label songs"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _trendingTracks.value = emptyList()
                        _searchError.value = error.message ?: "Could not load personalized discovery"
                    } finally {
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    /** Clear search results and return to trending/recommendation view. */
    fun clearSearch() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        currentQuery = ""
        _searchResultsSongs.value = emptyList()
        _searchResultsAlbums.value = emptyList()
        _searchResultsArtists.value = emptyList()
        _searchTopResult.value = null
        _discoveryArtists.value = emptyList()
        _querySuggestions.value = emptyList()
        _searchError.value = null
        _isLoading.value = false
        if (_trendingTracks.value.isEmpty()) loadTrendingAndRecommendations()
    }

    fun search(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            clearSearch()
            return
        }

        // Search has priority over the heavier discovery feed. Running both at once was
        // exhausting the same HTTP pool and making interactive queries appear stuck/empty.
        discoveryJob?.cancel()
        currentQuery = trimmed
        _searchError.value = null
        _querySuggestions.value = emptyList()

        // Cache hit
        structuredSearchCache[trimmed.lowercase()]?.let { cached ->
            _searchResultsSongs.value = cached.songs
            _searchResultsAlbums.value = cached.albums
            _searchResultsArtists.value = rankSearchArtists(trimmed, cached.artists)
            _searchTopResult.value = resolveSearchTopResult(cached.topResult, cached.songs)
            _isLoading.value = false
            loadSuggestions(trimmed)
            return
        }

        searchJob = viewModelScope.launch {
            if (debounce) {
                delay(250) // Fast 250ms debounce
            }
            _isLoading.value = true
            try {
                val region = normalizedRegion(userPrefs.userRegionFlow.first())
                val result = supervisorScope {
                    val youtubeRequest = async {
                        runCatching { repository.searchYouTubeMusicStructured(trimmed, region) }
                            .getOrDefault(YouTubeSearchResult())
                    }
                    val fallbackRequest = async {
                        runCatching { repository.searchFallbackSongs(trimmed) }.getOrDefault(emptyList())
                    }

                    // Await the primary YouTube search first. It is usually much faster than Saavn.
                    val youtubeResult = withTimeoutOrNull(10_000L) { youtubeRequest.await() }
                        ?: YouTubeSearchResult()

                    // If YouTube returned results, we can show them immediately.
                    if (youtubeResult.songs.isNotEmpty() || youtubeResult.videos.isNotEmpty() || youtubeResult.albums.isNotEmpty() || youtubeResult.artists.isNotEmpty()) {
                        if (currentQuery == trimmed) {
                            _searchResultsSongs.value = rankSearchSongs(trimmed, youtubeResult.songs, retainRelated = true)
                            _searchResultsAlbums.value = rankSearchAlbums(trimmed, youtubeResult.albums)
                            _searchResultsArtists.value = rankSearchArtists(
                                trimmed, youtubeResult.artists, youtubeResult.songs
                            )
                            _searchTopResult.value = resolveSearchTopResult(
                                youtubeResult.topResult,
                                _searchResultsSongs.value,
                            )
                            _isLoading.value = false
                            warmTopResults(_searchResultsSongs.value)
                        }
                    }

                    // A fallback provider must not inject a second same-title recording into a
                    // successful YouTube Music result set. Use it only when YouTube has no songs.
                    val fallbackSongs = if (youtubeResult.songs.isEmpty()) {
                        withTimeoutOrNull(3_500L) { fallbackRequest.await() }
                            .orEmpty()
                            .let { rankSearchSongs(trimmed, it, retainRelated = false) }
                    } else {
                        fallbackRequest.cancel()
                        emptyList()
                    }

                    youtubeResult.copy(
                        songs = rankSearchSongs(trimmed, youtubeResult.songs, retainRelated = true)
                            .plus(fallbackSongs)
                            .distinctBy { it.id }
                    )
                }
                if (currentQuery != trimmed) return@launch
                val rankedResult = result.copy(
                    videos = rankSearchSongs(trimmed, result.videos, retainRelated = true)
                        .map { it.copy(isMusicVideo = true) },
                    albums = rankSearchAlbums(trimmed, result.albums),
                    artists = rankSearchArtists(trimmed, result.artists, result.songs + result.videos),
                )
                if (rankedResult.songs.isNotEmpty() || rankedResult.videos.isNotEmpty() || rankedResult.albums.isNotEmpty() || rankedResult.artists.isNotEmpty()) {
                    structuredSearchCache[trimmed.lowercase()] = rankedResult
                }
                _searchResultsSongs.value = rankedResult.songs
                _searchResultsAlbums.value = rankedResult.albums
                _searchResultsArtists.value = rankedResult.artists
                _searchTopResult.value = resolveSearchTopResult(rankedResult.topResult, rankedResult.songs)
                warmTopResults(_searchResultsSongs.value)
                loadSuggestions(trimmed)
            } catch (cancelled: CancellationException) {
                // A newer query superseded this request. Cancellation is expected and
                // must never be rendered as a search error.
                throw cancelled
            } catch (error: Exception) {
                if (currentQuery == trimmed) {
                    _searchResultsSongs.value = emptyList()
                    _searchResultsAlbums.value = emptyList()
                    _searchResultsArtists.value = emptyList()
                    _searchTopResult.value = null
                    _searchError.value = error.message ?: "Search could not reach YouTube Music"
                }
            } finally {
                if (currentQuery == trimmed) _isLoading.value = false
            }
        }
    }

    /**
     * Warms the stream URLs of the first few search hits so tapping a row starts playing
     * straight away rather than waiting on an Innertube round trip first. Fire-and-forget and
     * bounded: results stay usable even if the link cannot support the extra requests.
     */
    private fun warmTopResults(songs: List<Song>) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.prefetchStreamUrls(songs, limit = 2) }
        }
    }

    private fun loadSuggestions(query: String) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(120)
            val region = normalizedRegion(runCatching { userPrefs.userRegionFlow.first() }.getOrDefault(""))
            val suggestions = withTimeoutOrNull(3_000L) { engine.getSearchSuggestions(query, region) }.orEmpty()
            if (currentQuery == query) _querySuggestions.value = suggestions
        }
    }

    private fun normalizedRegion(region: String): String = engine.resolveRegion(region)

    /**
     * Orders song results.
     *
     * Classification is deliberately coarse (a handful of tiers) and the sort is stable, so
     * inside each tier the provider's own ordering survives. YouTube already ranks its results;
     * re-scoring every row by text similarity was promoting uploads that merely repeat the query
     * in the title over the artist's real releases — the "same name, wrong song" problem.
     */
    private fun rankSearchSongs(
        query: String,
        songs: List<Song>,
        retainRelated: Boolean,
    ): List<Song> {
        val normalizedQuery = query.trim().lowercase()
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }
        return songs.filter { isDisplayableMusicSong(it, normalizedQuery) }.distinctBy { it.id }
            .distinctByTrack()
            .mapNotNull { song ->
                val title = song.title.lowercase()
                val artist = song.artist.lowercase()
                val album = song.album.lowercase()
                val covered = tokens.count { token ->
                    title.contains(token) || artist.contains(token) || album.contains(token)
                }
                // Keep only rows that actually relate to the query. With a single-token query any
                // hit counts; a multi-token query needs most of its tokens to be present.
                val related = when {
                    tokens.isEmpty() -> true
                    retainRelated -> covered >= (tokens.size + 1) / 2
                    else -> covered >= 1
                }
                if (!related) return@mapNotNull null
                val tier = when {
                    title == normalizedQuery -> 0
                    artist == normalizedQuery -> 1
                    artist.contains(normalizedQuery) -> 1
                    album.contains(normalizedQuery) -> 2
                    title.startsWith(normalizedQuery) -> 3
                    title.contains(normalizedQuery) -> 4
                    else -> 5
                }
                // Tie-break on how much of the query the row actually matches. Searching
                // "295 Sidhu Moose Wala" should not leave the real "295" behind a same-numbered
                // re-upload, and both land in tier 5 because the title alone is just "295".
                Triple(song, tier, covered)
            }
            .sortedWith(compareBy({ it.second }, { -it.third }))
            .map { it.first }
    }

    private fun rankSearchAlbums(query: String, albums: List<YouTubeAlbum>): List<YouTubeAlbum> {
        val normalizedQuery = query.trim().lowercase()
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }
        return albums
            .filter { album ->
                album.browseId.isNotBlank() && album.title.isNotBlank() &&
                    album.type.lowercase() in setOf("album", "single", "ep", "soundtrack")
            }
            .distinctBy { it.browseId }
            .map { album ->
                val title = album.title.lowercase()
                val artist = album.artist.lowercase()
                val score = when {
                    title == normalizedQuery -> 10000
                    "$artist $title".contains(normalizedQuery) -> 8000
                    title.startsWith(normalizedQuery) -> 5000
                    title.contains(normalizedQuery) -> 2000
                    artist == normalizedQuery -> 1000
                    else -> tokens.sumOf { token ->
                        (if (title.contains(token)) 24 else 0) + (if (artist.contains(token)) 12 else 0)
                    }
                }
                val titleCoverage = tokens.count(title::contains)
                val artistCoverage = tokens.count(artist::contains)
                album to if (titleCoverage > 0 && (artistCoverage > 0 || tokens.size == 1)) score else 0
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun rankSearchArtists(
        query: String,
        artists: List<YouTubeArtist>,
        matchedTracks: List<Song> = emptyList(),
    ): List<YouTubeArtist> {
        val wanted = query.trim().lowercase()
        val wantedCompact = wanted.replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val tokens = wanted.split(Regex("\\s+")).filter { it.length > 1 }
        val linkedArtistIds = matchedTracks.flatMap(Song::artists)
            .mapNotNull { it.remoteBrowseId?.takeIf(String::isNotBlank) }
            .toSet()
        val ranked = artists
            .filter { it.browseId.isNotBlank() && it.name.isNotBlank() }
            .filter { artist ->
                val coverage = tokens.count(artist.name.lowercase()::contains)
                artist.browseId in linkedArtistIds || coverage >= (tokens.size.coerceAtLeast(1) + 1) / 2
            }
            .distinctBy { it.browseId }
            .sortedByDescending { artist ->
                val name = artist.name.trim().lowercase()
                val compactName = name.replace(Regex("[^\\p{L}\\p{N}]+"), "")
                val base = when {
                    compactName == wantedCompact && !artist.subscriberCount.isNullOrBlank() -> 400_000
                    compactName == wantedCompact && artist.browseId in linkedArtistIds -> 350_000
                    name == wanted -> 300_000
                    artist.browseId in linkedArtistIds -> 200_000
                    name.startsWith(wanted) -> 50_000
                    name.contains(wanted) -> 20_000
                    else -> tokens.count(name::contains) * 100 +
                        if (!artist.thumbnailUrl.isNullOrBlank()) 10 else 0
                }
                val songNumberPenalty = if (
                    tokens.any { token -> token.all(Char::isDigit) && name.contains(token) } &&
                    artist.browseId !in linkedArtistIds
                ) 75_000 else 0
                base - songNumberPenalty + if (!artist.thumbnailUrl.isNullOrBlank()) 25 else 0
            }
        // A full artist-name query should resolve to the canonical provider entity,
        // not a grid of fan/re-upload channels carrying the same keywords.
        return ranked.filter { artist ->
            artist.name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "") == wantedCompact
        }.takeIf { it.isNotEmpty() }
            ?.take(1)
            ?: ranked.take(8)
    }

    /**
     * Keeps only rows that are the real recording, and never a video upload.
     *
     * [query] is the user's own search text, so an explicit "lofi" / "instrumental" request still
     * works while a song search cannot surface a slowed/reverb/8D copy of someone else's upload.
     */
    private fun isDisplayableMusicSong(song: Song, query: String = ""): Boolean =
        isRealSongEntry(song, query)

    /**
     * Chooses the card shown above the results.
     *
     * YouTube Music happily returns a *video* as the "top result" for a song query, and that entry
     * usually carries no playable seed at all, so the hero row looked like a song but did nothing.
     * Whenever that happens the best real song becomes the hero instead.
     */
    private fun resolveSearchTopResult(
        providerTop: YouTubeTopResult?,
        songs: List<Song>,
    ): YouTubeTopResult? {
        val videoTop = providerTop != null &&
            (providerTop.typeLabel.equals("Video", ignoreCase = true) ||
                providerTop.pageType == "MUSIC_PAGE_TYPE_VIDEO")

        if (providerTop != null && !videoTop) {
            val openable = providerTop.representsArtist || providerTop.representsAlbum
            val playable = providerTop.seedSong?.let { isDisplayableMusicSong(it) } == true
            if (openable || playable) return providerTop
        }

        val bestSong = songs.firstOrNull()
            ?: return if (videoTop) null else providerTop

        return YouTubeTopResult(
            typeLabel = "Song",
            title = bestSong.title,
            subtitle = bestSong.artist,
            thumbnailUrl = bestSong.albumArtUriString,
            browseId = "",
            pageType = "",
            seedSongs = listOf(bestSong),
        )
    }

    private suspend fun rankForInterests(songs: List<Song>, interests: List<String>): List<Song> {
        val tasteSongs = runCatching {
            playbackStatsRepository.loadPlaybackHistory(200)
                .mapNotNull { it.track?.toSong(it.songId) }
        }.getOrDefault(emptyList())
        val profile = RecommendationProfile.fromTaste(
            songs = tasteSongs,
            preferredArtists = interests.toSet() + userPrefs.preferredArtists.first(),
            preferredGenres = interests.toSet() + userPrefs.preferredGenres.first(),
            blockedArtists = userPrefs.blockedArtists.first(),
        )
        return recommendationEngine.rank(songs, profile, RecommendationSurface.SEARCH)
    }

    fun loadMore() {
        // High density results loaded in one shot
    }

    suspend fun resolveStreamUrl(song: Song): String? {
        return engine.resolveStreamUrl(song.id)
    }

    /** Resolve missing album navigation metadata for search/fallback results on demand. */
    suspend fun resolveAlbumBrowseId(song: Song): String? {
        song.remoteAlbumBrowseId?.takeIf(String::isNotBlank)?.let { return it }
        val wantedAlbum = song.album.trim()
        _searchResultsAlbums.value.firstOrNull {
            it.title.equals(wantedAlbum, ignoreCase = true)
        }?.let { return it.browseId }
        val region = normalizedRegion(runCatching { userPrefs.userRegionFlow.first() }.getOrDefault(""))
        val hasSpecificAlbum = wantedAlbum.isNotBlank() && !wantedAlbum.equals("YouTube Music", ignoreCase = true)
        val lookupQuery = if (hasSpecificAlbum) "$wantedAlbum ${song.artist}" else "${song.title} ${song.artist}"
        return withTimeoutOrNull(8_000L) {
            val matches = repository.searchYouTubeMusicStructured(lookupQuery, region).albums
            (if (hasSpecificAlbum) {
                matches.firstOrNull {
                    it.title.equals(wantedAlbum, ignoreCase = true) ||
                        it.title.contains(wantedAlbum, ignoreCase = true) ||
                        wantedAlbum.contains(it.title, ignoreCase = true)
                }
            } else matches.firstOrNull())?.browseId
        }
    }

    /** Resolve the primary artist page when a result did not include linked-artist metadata. */
    suspend fun resolveArtistBrowseId(song: Song): String? {
        song.artists.firstNotNullOfOrNull { it.remoteBrowseId?.takeIf(String::isNotBlank) }?.let { return it }
        val wantedNames = song.artist.split(',', '&').map(String::trim).filter(String::isNotBlank)
        _searchResultsArtists.value.firstOrNull { artist ->
            wantedNames.any { it.equals(artist.name, ignoreCase = true) }
        }?.let { return it.browseId }
        val primaryName = wantedNames.firstOrNull() ?: return null
        val region = normalizedRegion(runCatching { userPrefs.userRegionFlow.first() }.getOrDefault(""))
        return withTimeoutOrNull(8_000L) {
            repository.searchYouTubeMusicStructured(primaryName, region)
                .artists
                .firstOrNull { it.name.equals(primaryName, ignoreCase = true) }
                ?.browseId
        }
    }
}
