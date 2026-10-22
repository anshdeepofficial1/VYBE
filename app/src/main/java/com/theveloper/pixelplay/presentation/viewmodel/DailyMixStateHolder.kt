package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.database.AudiusFavoriteDao
import com.theveloper.pixelplay.data.database.OnlineSongCacheDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeHomeShelf
import com.theveloper.pixelplay.data.network.ytmusic.isRealSongEntry
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val engagementDao: com.theveloper.pixelplay.data.database.EngagementDao,
) {
    private data class RemoteHomeData(
        val releases: List<Song>,
        val trending: List<Song>,
        val related: List<List<Song>>,
        val shelves: List<YouTubeHomeShelf>,
    )

    /**
     * Merges per-seed radio lists round-robin instead of stacking them back-to-back.
     *
     * Concatenating made the first shelf slots a wall of one artist whenever several history
     * seeds belonged to the same favourite, which read as "Quick Picks only knows two songs".
     * Alternating the streams keeps the shelf varied while all of it still traces back to the
     * listener's own taste.
     */
    private fun interleaveSeedRadios(radios: List<List<Song>>): List<Song> {
        if (radios.isEmpty()) return emptyList()
        val cursors = IntArray(radios.size) { 1 } // position 0 is the seed itself, saved for Listen Again
        val out = mutableListOf<Song>()
        val seen = hashSetOf<String>()
        while (out.size < 60) {
            var advanced = false
            for (listIndex in radios.indices) {
                val radio = radios[listIndex]
                while (cursors[listIndex] < radio.size) {
                    val song = radio[cursors[listIndex]++]
                    if (seen.add(song.id)) {
                        out += song
                        advanced = true
                        break
                    }
                }
            }
            if (!advanced) break
        }
        return out
    }

    /**
     * Publishes a shelf only when the new pass actually produced something.
     *
     * Home is refreshed on every return to the foreground, on every pull, and whenever the
     * periodic mix rebuild fires. Each of those passes fans out into several requests, and any one
     * of them can come back empty - a slow link hitting the timeout, a provider hiccup, a single
     * failed call inside the fan-out. Publishing that emptiness used to blank whole rows, which
     * then only came back on a later refresh. A row the listener has already seen stays on screen
     * until a pass has real content to replace it with.
     */
    private fun <T> MutableStateFlow<ImmutableList<T>>.publishSticky(items: List<T>) {
        if (items.isEmpty() && value.isNotEmpty()) return
        value = items.toImmutableList()
    }

    private fun MutableStateFlow<List<YouTubeHomeShelf>>.publishStickyShelves(
        items: List<YouTubeHomeShelf>,
    ) {
        if (items.isEmpty() && value.isNotEmpty()) return
        value = items
    }

    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null
    private var favoriteIdsSource: kotlinx.coroutines.flow.Flow<Set<String>>? = null
    private val catalogueMigrationMutex = Mutex()
    private var catalogueMigrationChecked = false

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

    /**
     * Speed Dial seeds ordered by how much the listener actually plays them - play count
     * first, most recent listen as the tie-breaker. Filled from the song_engagements table
     * so the shelf reflects the user's habits instead of a random slice of history.
     */
    private val _speedDialSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val speedDialSongs: StateFlow<ImmutableList<Song>> = _speedDialSongs.asStateFlow()

    private val _trendingSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val trendingSongs: StateFlow<ImmutableList<Song>> = _trendingSongs.asStateFlow()

    private val _discoverySongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val discoverySongs: StateFlow<ImmutableList<Song>> = _discoverySongs.asStateFlow()

    private val _youtubeHomeShelves = MutableStateFlow<List<YouTubeHomeShelf>>(emptyList())
    val youtubeHomeShelves: StateFlow<List<YouTubeHomeShelf>> = _youtubeHomeShelves.asStateFlow()

    private val _topMoods = MutableStateFlow<ImmutableList<String>>(persistentListOf("Chill", "Happy", "Workout", "Focus", "Romantic", "Sad", "Party", "Relax"))
    val topMoods: StateFlow<ImmutableList<String>> = _topMoods.asStateFlow()

    /**
     * Pulls the connected YouTube Music library when a sync is due.
     *
     * Opening Library -> Playlists / Artists calls this so the account's playlists and the mixes
     * YouTube generated for the user show up immediately instead of waiting for the next scheduled
     * background sync.
     */
    fun syncYouTubeLibraryIfDue(force: Boolean = false) {
        youTubeAccountManager.syncIfDue(force)
    }

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
                    withTimeoutOrNull(4_000L) {
                        runCatching { onlineMusicRepository.getLatestReleases(region) }.getOrNull()
                    }.orEmpty()
                }
                val trendingDeferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(4_000L) {
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
                    related = relatedDeferred.awaitAll(),
                    shelves = homeDeferred.await(),
                )
            }
            val latestReleases = remoteHome.releases
            val trending = remoteHome.trending
            val relatedDiscovery = interleaveSeedRadios(remoteHome.related)
            _youtubeHomeShelves.publishStickyShelves(
                remoteHome.shelves
                    .mapNotNull { shelf ->
                        val clean = shelf.songs.filter { isRealSongEntry(it) }
                        if (clean.isEmpty() && shelf.collections.isEmpty()) null
                        else shelf.copy(songs = clean)
                    }
            )
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

            // Speed Dial is rebuilt from the listener's real play counts every refresh.
            refreshEngagementSpeedDial()

            // An artist cap swaps chart/discovery filler in when one favourite floods the
            // shelf, so Quick Picks keeps its YT Music taste-blend instead of turning into a
            // single-artist block whenever several liked songs share the same performer.
            fun quickPicksArtistKey(song: Song) = song.displayArtist.trim().lowercase().ifBlank { song.id }
            val fillerPool = (guestFallback + trending + latestReleases)
                .distinctBy { it.id }
                .filterNot { it.id in historyIds }
            val providerAndRadioQuickPicks = run {
                val perArtistCap = 3
                val counts = mutableMapOf<String, Int>()
                var fillerIndex = 0
                val out = mutableListOf<Song>()
                val outIds = mutableSetOf<String>()
                fun takeFiller(): Song? {
                    while (fillerIndex < fillerPool.size) {
                        val candidate = fillerPool[fillerIndex++]
                        val candidateKey = quickPicksArtistKey(candidate)
                        if (counts.getOrDefault(candidateKey, 0) >= perArtistCap) continue
                        return candidate
                    }
                    return null
                }
                val primary = (relatedDiscovery + providerQuickPicks)
                    .distinctBy(Song::id)
                    .filterNot { it.id in historyIds }
                for (song in primary) {
                    val key = quickPicksArtistKey(song)
                    if (counts.getOrDefault(key, 0) >= perArtistCap) {
                        takeFiller()?.let { replacement ->
                            if (outIds.add(replacement.id)) {
                                out += replacement
                                counts[quickPicksArtistKey(replacement)] =
                                    counts.getOrDefault(quickPicksArtistKey(replacement), 0) + 1
                            }
                        }
                        continue
                    }
                    if (outIds.add(song.id)) {
                        out += song
                        counts[key] = counts.getOrDefault(key, 0) + 1
                    }
                }
                out
            }
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
            _quickPickSongs.publishSticky(
                visibleQuickPicks
                    .filter { isRealSongEntry(it) }
                    .take(20)
            )
            _trendingSongs.publishSticky(
                trending
                    .filter { isRealSongEntry(it) }
                    .distinctBy { it.id }
                    .take(30)
            )
            val loggedOutDiscovery = (latestReleases + trending).distinctBy { it.id }

            // Account history/playlists and explicit favorites are the primary taste signals.
            val tasteCandidates = (
                recentHistorySongs + localFavoriteSongs + cachedFavorites + onlineFavorites
            ).filter { isRealSongEntry(it) }.distinctBy { it.id }
            val preferredArtists = runCatching { userPreferencesRepository.preferredArtists.first() }
                .getOrDefault(emptySet())
            val preferredGenres = runCatching { userPreferencesRepository.preferredGenres.first() }
                .getOrDefault(emptySet())
            val blockedArtists = runCatching { userPreferencesRepository.blockedArtists.first() }
                .getOrDefault(emptySet()).mapTo(mutableSetOf()) { it.trim().lowercase() }
            
            val isNotBlocked = { song: Song -> 
                song.displayArtist.trim().lowercase() !in blockedArtists && isRealSongEntry(song)
            }

            val verifiedRecentReleases = latestReleases
                .filter(isNotBlocked)
                .filter(::isWithinReleaseRadarWindow)
            _latestReleaseSongs.publishSticky(
                personalizeLatestReleases(
                    releases = verifiedRecentReleases,
                    tasteSongs = tasteCandidates,
                    preferredArtists = preferredArtists,
                    preferredGenres = preferredGenres,
                ).sortedByDescending { it.releaseDateEpochMillis }.take(30)
            )

            val heardIds = recentHistorySongs.mapTo(mutableSetOf(), Song::id)
            val discoveryProfile = RecommendationProfile.fromTaste(
                songs = tasteCandidates,
                preferredArtists = preferredArtists,
                preferredGenres = preferredGenres,
                blockedArtists = blockedArtists,
            )
            // Keep the engine's own ranking. Shuffling the ranked pool with a weekly hash used to
            // push weak matches ahead of the strongest ones just to vary the row.
            _discoverySongs.publishSticky(
                recommendationEngine.rank(
                    candidates = (relatedDiscovery + verifiedRecentReleases)
                        .filter(isNotBlocked)
                        .filterNot { it.id in heardIds }
                        .distinctBy(Song::id),
                    profile = discoveryProfile,
                    surface = RecommendationSurface.HOME,
                    limit = 10,
                )
            )

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
                _dailyMixSongs.publishSticky(mix)
                userPreferencesRepository.saveDailyMixSongIds(mix.map { it.id })

                val dailyIds = mix.mapTo(hashSetOf(), Song::id)
                val separateYourMixPool = rankedCandidates.filterNot { it.id in dailyIds }
                    .ifEmpty { rankedCandidates.drop((rankedCandidates.size / 3).coerceAtLeast(1)) }
                val yourMix = dailyMixManager.generateYourMix(separateYourMixPool, favoriteIds)
                _yourMixSongs.publishSticky(yourMix)
                userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })

                if (_quickPickSongs.value.isEmpty()) {
                    _quickPickSongs.value = dailyMixManager
                        .getTopCandidatesForAi(rankedCandidates, favoriteIds, limit = 45)
                        .take(10)
                        .toImmutableList()
                }
            } else {
                // Keep the last mix that rendered. A pass with no candidates is a slow or empty
                // provider answer, not a reason to wipe shelves the listener is looking at.
            }
            _isRefreshing.value = false
        }
    }

    /**
     * Rebuilds the Speed Dial shelf from [com.theveloper.pixelplay.data.database.SongEngagementEntity].
     *
     * The shelf is the user's most-played songs, ordered by play count with the most recent
     * listen as the tie-breaker, resolved through the same id resolver the mixes use so
     * online, local and Audius tracks all flow in. Still-refreshing rows must not vanish,
     * so the publish keeps the last shelf when nothing is resolved.
     */
    private suspend fun refreshEngagementSpeedDial() {
        val stats = runCatching {
            engagementDao.getTopPlayedSongs(40)
                .asSequence()
                .filter { it.playCount > 0 }
                .sortedWith(
                    compareByDescending<com.theveloper.pixelplay.data.database.SongEngagementEntity> { it.playCount }
                        .thenByDescending { it.lastPlayedTimestamp }
                )
                .take(24)
                .map { it.songId }
                .toList()
        }.getOrDefault(emptyList())
        if (stats.isEmpty()) return
        val resolved = runCatching {
            withContext(Dispatchers.IO) { musicRepository.getSongsByIds(stats).first() }
        }.getOrDefault(emptyList())
        val resolvedMap = resolved.asSequence().associateBy { it.id }
        val ordered = stats.asSequence()
            .mapNotNull { resolvedMap[it] }
            .filter { isRealSongEntry(it) }
            .distinctBy { it.id }
            .take(16)
            .toList()
        if (ordered.isNotEmpty()) _speedDialSongs.publishSticky(ordered)
    }

    /**
     * Clears online metadata written before the "YouTube Music catalogue only" rules existed.
     *
     * An upgraded install restores its mix by id from the online cache, so without this it keeps
     * showing the video uploads and 16:9 stills that the previous version had stored. Version 3
     * additionally rebuilds rows whose artist was saved as the placeholder "YouTube Music" (or as
     * "Unknown Artist") so the improved credit parsing refills the cache with real artists.
     * Runs once per [ONLINE_CATALOGUE_VERSION]; the cleared mix is rebuilt by the normal refresh.
     */
    private suspend fun ensureCatalogueIsCurrent(): Boolean = catalogueMigrationMutex.withLock {
        if (catalogueMigrationChecked) return true
        catalogueMigrationChecked = true
        val version = runCatching { userPreferencesRepository.onlineContentVersionFlow.first() }
            .getOrDefault(0)
        if (version >= ONLINE_CATALOGUE_VERSION) return true
        runCatching {
            onlineSongCacheDao.clear()
            userPreferencesRepository.clearSavedMixes()
            userPreferencesRepository.setOnlineContentVersion(ONLINE_CATALOGUE_VERSION)
        }
        false
    }
    fun loadPersistedDailyMix() {
        scope?.launch {
            if (!ensureCatalogueIsCurrent()) return@launch
            val dailyMixIds = userPreferencesRepository.dailyMixSongIdsFlow.first()
            if (dailyMixIds.isNotEmpty() && _dailyMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(dailyMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = dailyMixIds.mapNotNull { songMap[it] }
                        // getSongsByIds fabricates an "Online Track" stub for ids it cannot resolve.
                        // Those are placeholders, not songs, so they never reach the shelf.
                        .filter { isRealSongEntry(it) }
                    _dailyMixSongs.value = orderedSongs.toImmutableList()
                }
            }
        }

        scope?.launch {
            if (!ensureCatalogueIsCurrent()) return@launch
            val yourMixIds = userPreferencesRepository.yourMixSongIdsFlow.first()
            if (yourMixIds.isNotEmpty() && _yourMixSongs.value.isEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(yourMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    val orderedSongs = yourMixIds.mapNotNull { songMap[it] }
                        .filter { isRealSongEntry(it) }
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

    private companion object {
        /** Bump when the online catalogue rules change enough to invalidate cached metadata. */
        const val ONLINE_CATALOGUE_VERSION = 3
    }
}
