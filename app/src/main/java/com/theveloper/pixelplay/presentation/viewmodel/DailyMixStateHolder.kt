package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.database.AudiusFavoriteDao
import com.theveloper.pixelplay.data.database.OnlineSongCacheDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeHomeShelf
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeAccountManager
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.repository.OnlineMusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.recommendation.RecommendationProfile
import com.theveloper.pixelplay.data.recommendation.RecommendationSurface
import com.theveloper.pixelplay.data.recommendation.UnifiedRecommendationEngine
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Daily Mix and Your Mix state with an online YouTube Music ecosystem.
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val onlineMusicRepository: OnlineMusicRepository,
    private val audiusFavoriteDao: AudiusFavoriteDao,
    private val onlineSongCacheDao: OnlineSongCacheDao,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val recommendationEngine: UnifiedRecommendationEngine,
    private val youTubeAccountManager: YouTubeAccountManager,
) {
    private data class RemoteHomeData(
        val releases: List<Song>,
        val trending: List<Song>,
        val related: List<Song>,
        val shelves: List<YouTubeHomeShelf>,
    )
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null
    private var favoriteIdsSource: kotlinx.coroutines.flow.Flow<Set<String>>? = null

        private val _isRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRefreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRefreshing

    private val _dailyMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = _dailyMixSongs.asStateFlow()

    private val _yourMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val yourMixSongs: StateFlow<ImmutableList<Song>> = _yourMixSongs.asStateFlow()

    private val _latestReleaseSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val latestReleaseSongs: StateFlow<ImmutableList<Song>> = _latestReleaseSongs.asStateFlow()

    private val _quickPickSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val quickPickSongs: StateFlow<ImmutableList<Song>> = _quickPickSongs.asStateFlow()

    private val _trendingSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val trendingSongs: StateFlow<ImmutableList<Song>> = _trendingSongs.asStateFlow()

    private val _discoverySongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val discoverySongs: StateFlow<ImmutableList<Song>> = _discoverySongs.asStateFlow()

    private val _youtubeHomeShelves = MutableStateFlow<List<YouTubeHomeShelf>>(emptyList())
    val youtubeHomeShelves: StateFlow<List<YouTubeHomeShelf>> = _youtubeHomeShelves.asStateFlow()

    private val _topMoods = MutableStateFlow<ImmutableList<String>>(persistentListOf("Chill", "Happy", "Workout", "Focus", "Romantic", "Sad", "Party", "Relax"))
    val topMoods: StateFlow<ImmutableList<String>> = _topMoods.asStateFlow()

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        coroutineScope.launch(Dispatchers.IO) {
            combine(
                youTubeAccountManager.isLoggedInFlow,
                youTubeAccountManager.interestLabelsFlow,
                youTubeAccountManager.syncRevisionFlow,
            ) { loggedIn, interests, revision -> Triple(loggedIn, interests, revision) }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { (loggedIn, _, _) ->
                    // Login/logout changes the provider's Home response and account scope.
                    // Rebuild every personalized surface instead of retaining the guest feed.
                    if (loggedIn) {
                        _quickPickSongs.value = persistentListOf()
                        _youtubeHomeShelves.value = emptyList()
                    }
                    favoriteIdsSource?.let { favorites ->
                        updateDailyMix(favorites)
                    }
                }
        }
    }

    fun removeFromDailyMix(songId: String) {
        _dailyMixSongs.update { currentList ->
            currentList.filterNot { it.id == songId }.toImmutableList()
        }
    }

    /**
     * Update the daily mix and your mix with online music ecosystem.
     */
    fun updateDailyMix(
        favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>,
        showRefreshIndicator: Boolean = false,
    ) {
        favoriteIdsSource = favoriteSongIdsFlow
        updateJob?.cancel()
        updateJob = scope?.launch(Dispatchers.IO) {
            _isRefreshing.value = showRefreshIndicator
            if (userPreferencesRepository.dataSaverEnabledFlow.first()) {
                // Keep the cache-first Home snapshot already loaded by loadPersistedDailyMix().
                // Discovery, charts and release radar are nonessential while saving data.
                _isRefreshing.value = false
                return@launch
            }
            val favoriteIds = runCatching { favoriteSongIdsFlow.first() }.getOrDefault(emptySet())
            // Left as the raw preference: every repository entry point normalises it through
            // YouTubeMusicEngine.resolveRegion, which prefers YouTube's own IP-derived country
            // over the device locale when the user has not explicitly chosen one.
            val region = runCatching { userPreferencesRepository.userRegionFlow.first() }
                .getOrDefault("")

            // 1. Gather online favorites
            val onlineFavorites = runCatching {
                audiusFavoriteDao.getAllFavorites().first().map { it.toSong() }
            }.getOrDefault(emptyList())

            // The online cache also contains search/fetch results, so it must never be treated as
            // proof that the user likes every cached song.
            val syncedAccountSongs = runCatching {
                onlineSongCacheDao.observeAll().first().map { it.toSong() }
            }.getOrDefault(emptyList())

            val recentHistorySongs = runCatching {
                playbackStatsRepository.loadPlaybackHistory(limit = 250)
                    .mapNotNull { entry -> entry.track?.toSong(entry.songId) }
                    .distinctBy { it.id }
            }.getOrDefault(emptyList())
            val localFavoriteSongs = runCatching {
                musicRepository.getSongsByIds(favoriteIds.toList()).first()
            }.getOrDefault(emptyList())
            val cachedFavorites = syncedAccountSongs.filter { it.id in favoriteIds }

            // Fetch latest releases, trending, and autoplay queue candidates in parallel with bounded timeout
            // to ensure home refresh finishes in 1-2 seconds.
            val remoteHome = coroutineScope {
                val latestDeferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(1800L) {
                        runCatching { onlineMusicRepository.getLatestReleases(region) }.getOrNull()
                    }.orEmpty()
                }
                val trendingDeferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(1800L) {
                        runCatching { onlineMusicRepository.getTrendingTracks(region) }.getOrNull()
                    }.orEmpty()
                }
                val homeDeferred = async(Dispatchers.IO) {
                    // Home is cache-first. Never make the first visible frame wait for a
                    // continuation chain on a slow provider connection.
                    withTimeoutOrNull(4_500L) {
                        runCatching { onlineMusicRepository.getYouTubeMusicHome(region) }.getOrNull()
                    }.orEmpty().ifEmpty {
                        // getHomeShelves persists its authenticated first page before requesting
                        // continuations. If a slow continuation hits the outer time budget, keep
                        // that real personalised page instead of replacing Home with an error.
                        onlineMusicRepository.getCachedYouTubeMusicHome(region)
                    }
                }
                val relatedDeferred = recentHistorySongs.take(3).map { seed ->
                    async(Dispatchers.IO) {
                        // A single history item is still enough to build a useful radio.  The
                        // old 1.8 s budget routinely expired on a cold WEB_REMIX session and
                        // left Home with that same item in both Quick Picks and Listen Again.
                        withTimeoutOrNull(4_500L) {
                            runCatching { onlineMusicRepository.getAutoplayQueue(seed, region).drop(1).take(20) }.getOrNull()
                        }.orEmpty()
                    }
                }
                RemoteHomeData(
                    releases = latestDeferred.await(),
                    trending = trendingDeferred.await(),
                    related = relatedDeferred.awaitAll().flatten(),
                    shelves = homeDeferred.await(),
                )
            }
            val latestReleases = remoteHome.releases
            val trending = remoteHome.trending
            val relatedDiscovery = remoteHome.related
            _youtubeHomeShelves.value = remoteHome.shelves
            val providerQuickPicks = remoteHome.shelves
                .firstOrNull { it.title.contains("quick picks", true) }
                ?.songs.orEmpty()
            val guestFallback = remoteHome.shelves.asSequence()
                .flatMap { it.songs.asSequence() }
                .plus(trending.asSequence())
                .plus(latestReleases.asSequence())
                .distinctBy(Song::id)
                .take(20)
                .toList()
            val historyIds = recentHistorySongs.mapTo(hashSetOf(), Song::id)
            val providerAndRadioQuickPicks = (relatedDiscovery + providerQuickPicks)
                .distinctBy(Song::id)
                .filterNot { it.id in historyIds }
            val accountShelfFallback = remoteHome.shelves.asSequence()
                .filterNot { shelf -> shelf.title.contains("listen again", ignoreCase = true) }
                .flatMap { it.songs.asSequence() }
                .filterNot { it.id in historyIds }
                .distinctBy(Song::id)
                .toList()
            val visibleQuickPicks = if (youTubeAccountManager.isLoggedInFlow.value) {
                // Keep account Home provider-authored.  When the provider returns only the
                // seed card, enrich it from that seed's own watch-next radio and other account
                // shelves instead of echoing the same card from Listen Again.
                providerAndRadioQuickPicks.ifEmpty { accountShelfFallback }
            } else {
                // Once we have history, Quick Picks must be a continuation of that taste—not
                // another copy of Listen Again.  Charts are only a cold-start fallback.
                providerAndRadioQuickPicks.ifEmpty { guestFallback.filterNot { it.id in historyIds } }
            }
            // Do not retain an old guest/chart Quick Picks row after an account refresh.
            // If YouTube did not provide this shelf in the current account response, hide it
            // rather than presenting stale unrelated tracks as the user's recommendations.
            _quickPickSongs.value = visibleQuickPicks.take(20).toImmutableList()
            _trendingSongs.value = trending.distinctBy { it.id }.take(30).toImmutableList()
            val loggedOutDiscovery = (latestReleases + trending).distinctBy { it.id }

            // Account history/playlists and explicit favorites are the primary taste signals.
            val tasteCandidates = (
                recentHistorySongs + localFavoriteSongs + cachedFavorites + onlineFavorites
            ).distinctBy { it.id }
            val preferredArtists = runCatching { userPreferencesRepository.preferredArtists.first() }
                .getOrDefault(emptySet())
            val preferredGenres = runCatching { userPreferencesRepository.preferredGenres.first() }
                .getOrDefault(emptySet())
            val blockedArtists = runCatching { userPreferencesRepository.blockedArtists.first() }
                .getOrDefault(emptySet()).mapTo(mutableSetOf()) { it.trim().lowercase() }
            
            val isNotBlocked = { song: Song -> 
                song.displayArtist.trim().lowercase() !in blockedArtists 
            }

            val verifiedRecentReleases = latestReleases
                .filter(isNotBlocked)
                .filter(::isWithinReleaseRadarWindow)
            _latestReleaseSongs.value = personalizeLatestReleases(
                releases = verifiedRecentReleases,
                tasteSongs = tasteCandidates,
                preferredArtists = preferredArtists,
                preferredGenres = preferredGenres,
            ).sortedByDescending { it.releaseDateEpochMillis }.take(30).toImmutableList()

            val heardIds = recentHistorySongs.mapTo(mutableSetOf(), Song::id)
            val discoveryProfile = RecommendationProfile.fromTaste(
                songs = tasteCandidates,
                preferredArtists = preferredArtists,
                preferredGenres = preferredGenres,
                blockedArtists = blockedArtists,
            )
            val weeklySeed = java.time.LocalDate.now()
                .get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            _discoverySongs.value = recommendationEngine.rank(
                candidates = (relatedDiscovery + verifiedRecentReleases)
                    .filterNot { it.id in heardIds }
                    .distinctBy(Song::id),
                profile = discoveryProfile,
                surface = RecommendationSurface.HOME,
                limit = 40,
            ).sortedBy { song -> ("$weeklySeed:${song.id}").hashCode() }
                .take(10)
                .toImmutableList()

            // Mix shelves must recommend around the taste profile, not simply replay the
            // profile itself. Previously this used tasteCandidates (mostly recent history),
            // making Listen Again, Quick Picks, Daily Mix and Your Mix near-identical after
            // one search/play. Build the pool from provider discovery and exclude exact heard
            // tracks; the history remains a ranking signal through RecommendationProfile.
            val providerDiscoveryCandidates = (
                relatedDiscovery +
                    remoteHome.shelves
                        .filterNot { it.title.contains("listen again", ignoreCase = true) }
                        .flatMap { it.songs } +
                    verifiedRecentReleases +
                    trending
                )
                .filter(isNotBlocked)
                .filterNot { it.id in heardIds }
                .distinctBy(Song::id)
            val candidateSongs = providerDiscoveryCandidates.ifEmpty {
                loggedOutDiscovery.filter(isNotBlocked).filterNot { it.id in heardIds }
            }

            if (candidateSongs.isNotEmpty()) {
                val moodToGenres = mapOf(
                    "Chill" to listOf("lo-fi", "chill", "acoustic", "indie", "ambient"),
                    "Happy" to listOf("pop", "dance", "upbeat", "happy"),
                    "Workout" to listOf("rock", "metal", "hip hop", "electronic", "dance", "workout"),
                    "Focus" to listOf("classical", "jazz", "ambient", "instrumental", "focus"),
                    "Romantic" to listOf("r&b", "soul", "romantic", "love"),
                    "Sad" to listOf("blues", "sad", "acoustic", "emo"),
                    "Party" to listOf("pop", "dance", "electronic", "party", "club"),
                    "Relax" to listOf("ambient", "classical", "lo-fi", "relax")
                )
                
                val genreAffinity = tasteCandidates
                    .mapNotNull { it.genre?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
                    .groupingBy { it }
                    .eachCount()
                    
                val sortedMoods = moodToGenres.keys.toList().sortedByDescending { mood ->
                    moodToGenres[mood]?.sumOf { genre -> genreAffinity[genre] ?: 0 } ?: 0
                }
                _topMoods.value = sortedMoods.toImmutableList()

                val recommendationProfile = RecommendationProfile.fromTaste(
                    songs = tasteCandidates,
                    preferredArtists = preferredArtists,
                    preferredGenres = preferredGenres,
                    blockedArtists = blockedArtists,
                )
                val rankedCandidates = recommendationEngine.rank(
                    candidates = candidateSongs,
                    profile = recommendationProfile,
                    surface = RecommendationSurface.HOME,
                )
                val mix = dailyMixManager.generateDailyMix(rankedCandidates, favoriteIds)
                _dailyMixSongs.value = mix.toImmutableList()
                userPreferencesRepository.saveDailyMixSongIds(mix.map { it.id })

                val dailyIds = mix.mapTo(hashSetOf(), Song::id)
                val separateYourMixPool = rankedCandidates.filterNot { it.id in dailyIds }
                    .ifEmpty { rankedCandidates.drop((rankedCandidates.size / 3).coerceAtLeast(1)) }
                val yourMix = dailyMixManager.generateYourMix(separateYourMixPool, favoriteIds)
                _yourMixSongs.value = yourMix.toImmutableList()
                userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })

                if (_quickPickSongs.value.isEmpty()) {
                    _quickPickSongs.value = dailyMixManager
                        .getTopCandidatesForAi(rankedCandidates, favoriteIds, limit = 45)
                        .take(10)
                        .toImmutableList()
                }
            } else {
                _yourMixSongs.value = persistentListOf()
                _dailyMixSongs.value = persistentListOf()
                _quickPickSongs.value = persistentListOf()
            }
            _isRefreshing.value = false
        }
    }

    fun loadPersistedDailyMix() {
        scope?.launch {
            val dailyMixIds = userPreferencesRepository.dailyMixSongIdsFlow.first()
            if (dailyMixIds.isNotEmpty() && _dailyMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(dailyMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = dailyMixIds.mapNotNull { songMap[it] }
                    _dailyMixSongs.value = orderedSongs.toImmutableList()
                }
            }
        }

        scope?.launch {
            val yourMixIds = userPreferencesRepository.yourMixSongIdsFlow.first()
            if (yourMixIds.isNotEmpty() && _yourMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(yourMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = yourMixIds.mapNotNull { songMap[it] }
                    _yourMixSongs.value = orderedSongs.toImmutableList()
                    if (_quickPickSongs.value.isEmpty()) {
                        // Instant cache-first Home content while the daily online refresh runs
                        // silently in the background.
                        _quickPickSongs.value = orderedSongs.take(10).toImmutableList()
                    }
                }
            }
        }
    }

    fun forceUpdate(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            if (userPreferencesRepository.dataSaverEnabledFlow.first()) return@launch
            updateDailyMix(favoriteSongIdsFlow, showRefreshIndicator = true)
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
    }

    fun checkAndUpdateIfNeeded(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            if (userPreferencesRepository.dataSaverEnabledFlow.first()) return@launch
            val lastUpdate = userPreferencesRepository.lastDailyMixUpdateFlow.first()
            val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            val lastUpdateDay = Calendar.getInstance().apply {
                timeInMillis = lastUpdate
            }.get(Calendar.DAY_OF_YEAR)

            if (today != lastUpdateDay) {
                updateDailyMix(favoriteSongIdsFlow)
                userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
            }
        }
    }

    fun setDailyMixSongs(songs: List<Song>) {
        _dailyMixSongs.value = songs.toImmutableList()
        scope?.launch {
            userPreferencesRepository.saveDailyMixSongIds(songs.map { it.id })
        }
    }

    private fun personalizeLatestReleases(
        releases: List<Song>,
        tasteSongs: List<Song>,
        preferredArtists: Set<String>,
        preferredGenres: Set<String>,
    ): List<Song> {
        if (releases.isEmpty()) return emptyList()

        val artistAffinity = tasteSongs
            .groupingBy { it.displayArtist.trim().lowercase() }
            .eachCount()
        val genreAffinity = tasteSongs
            .mapNotNull { it.genre?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
        val preferredArtistKeys = preferredArtists.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val preferredGenreKeys = preferredGenres.mapTo(mutableSetOf()) { it.trim().lowercase() }

        if (artistAffinity.isEmpty() && genreAffinity.isEmpty() &&
            preferredArtistKeys.isEmpty() && preferredGenreKeys.isEmpty()
        ) {
            return releases.distinctBy { it.id }
        }

        val providerOrder = releases.withIndex().associate { it.value.id to it.index }
        return releases
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<Song> { song ->
                    val artistKey = song.displayArtist.trim().lowercase()
                    val genreKey = song.genre?.trim()?.lowercase()
                    (artistAffinity[artistKey] ?: 0) * 5 +
                        (genreAffinity[genreKey] ?: 0) * 2 +
                        (if (artistKey in preferredArtistKeys) 8 else 0) +
                        (if (genreKey != null && genreKey in preferredGenreKeys) 4 else 0)
                }.thenBy { providerOrder[it.id] ?: Int.MAX_VALUE }
            )
    }

    private fun isWithinReleaseRadarWindow(song: Song): Boolean {
        if (song.releaseDateEpochMillis <= 0L) return false
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(song.releaseDateEpochMillis).atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        return !date.isBefore(today.minusDays(29)) && !date.isAfter(today)
    }

    suspend fun getCandidatePool(
        allSongs: List<Song>,
        favoriteIds: Set<String>,
        maxSize: Int = 100
    ): List<Song> {
        return dailyMixManager.generateDailyMix(allSongs, favoriteIds, maxSize)
    }

    fun onCleared() {
        updateJob?.cancel()
        scope = null
    }
}
