package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import android.content.Intent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeHomeCollection
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeSettingsSyncState
import com.theveloper.pixelplay.data.network.ytmusic.isRealSongEntry
import com.theveloper.pixelplay.presentation.components.rememberStableShelf
import com.theveloper.pixelplay.data.preferences.CollagePattern
import com.theveloper.pixelplay.data.preferences.HomeSectionPreference
import com.theveloper.pixelplay.presentation.components.AlbumArtCollage
import com.theveloper.pixelplay.presentation.components.ChangelogBottomSheet
import com.theveloper.pixelplay.presentation.netease.dashboard.NeteaseDashboardViewModel
import com.theveloper.pixelplay.presentation.jellyfin.dashboard.JellyfinDashboardViewModel
import com.theveloper.pixelplay.presentation.navidrome.dashboard.NavidromeDashboardViewModel
import com.theveloper.pixelplay.presentation.qqmusic.dashboard.QqMusicDashboardViewModel
import com.theveloper.pixelplay.presentation.components.DailyMixSection
import com.theveloper.pixelplay.presentation.components.HomeGradientTopBar
import com.theveloper.pixelplay.presentation.components.HomeOptionsBottomSheet
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.RecentlyPlayedSection
import com.theveloper.pixelplay.presentation.components.RecentlyPlayedSectionMinSongsToShow
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.StatsOverviewCard
import com.theveloper.pixelplay.presentation.components.resolveMainScreenBottomGradientHeight
import com.theveloper.pixelplay.presentation.model.collectRecentlyPlayedSongIds
import com.theveloper.pixelplay.presentation.model.mapRecentlyPlayedSongs
import com.theveloper.pixelplay.presentation.components.subcomps.PlayingEqIcon
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.components.StreamingProviderSheet
import com.theveloper.pixelplay.presentation.telegram.auth.TelegramLoginActivity
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.StatsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.AccountsViewModel
import com.theveloper.pixelplay.presentation.ytmusic.auth.YouTubeLoginActivity
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import com.theveloper.pixelplay.ui.theme.LocalImmersiveGlass
import com.theveloper.pixelplay.ui.theme.glassPanel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource

private const val HomeLoadingPlaceholderMinDurationMillis = 1200L

// Modern HomeScreen with collapsible top bar and staggered grid layout
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    accountsViewModel: AccountsViewModel = hiltViewModel(),
    neteaseViewModel: NeteaseDashboardViewModel = hiltViewModel(),
    qqMusicViewModel: QqMusicDashboardViewModel = hiltViewModel(),
    navidromeViewModel: NavidromeDashboardViewModel = hiltViewModel(),
    jellyfinViewModel: JellyfinDashboardViewModel = hiltViewModel(),
    onOpenSidebar: () -> Unit
) {
    val context = LocalContext.current
    // DETECTAR MODO BENCHMARK
    val isBenchmarkMode = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("is_benchmark", false) ?: false
    }
    val statsViewModel: StatsViewModel = hiltViewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val isYouTubeLoggedIn by accountsViewModel.isYouTubeLoggedIn.collectAsStateWithLifecycle()
    val youTubeAccountAvatarUrl by accountsViewModel.youTubeAccountAvatarUrl.collectAsStateWithLifecycle()
    val settingsSyncState by accountsViewModel.youTubeSettingsSyncState.collectAsStateWithLifecycle()
    val showFirstYouTubeMusicPrompt by accountsViewModel.showFirstYouTubeMusicSignInPrompt.collectAsStateWithLifecycle()
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val curatedYourMixSongs by playerViewModel.yourMixSongs.collectAsStateWithLifecycle()
    val quickPickSongs by playerViewModel.quickPickSongs.collectAsStateWithLifecycle()
    val frequencySpeedDial by playerViewModel.speedDialSongs.collectAsStateWithLifecycle()
    val trendingSongs by playerViewModel.trendingSongs.collectAsStateWithLifecycle()
    val latestReleaseSongs by playerViewModel.latestReleaseSongs.collectAsStateWithLifecycle()
    val discoverySongs by playerViewModel.discoverySongs.collectAsStateWithLifecycle()
    val youtubeHomeShelves by playerViewModel.youtubeHomeShelves.collectAsStateWithLifecycle()
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val isDataSaverEnabled by playerViewModel.dataSaverEnabled.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeMoodFeed by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    val selectedHomeMood = activeMoodFeed?.first
    val selectedMoodSongs = activeMoodFeed?.second.orEmpty()
    val homeMoodLabels = remember { listOf("Workout", "Feel good", "Energize", "Relax", "Romance") }
    val moodFeedCache = remember {
        mutableStateMapOf<String, List<Song>>().apply {
            homeMoodLabels.forEach { mood ->
                playerViewModel.cachedMoodMix(mood).takeIf(List<Song>::isNotEmpty)?.let { put(mood, it) }
            }
        }
    }

    // Warm mood feeds without blocking or replacing the provider Home page. Once a feed is
    // available, selecting its chip is an immediate in-memory swap (the same interaction model
    // as YouTube Music) instead of a full-page spinner.
    LaunchedEffect(isYouTubeLoggedIn, youtubeHomeShelves, isDataSaverEnabled) {
        if (isDataSaverEnabled) return@LaunchedEffect
        homeMoodLabels.forEach { mood ->
            if (moodFeedCache[mood].isNullOrEmpty()) {
                val songs = playerViewModel.loadMoodMix(mood)
                if (songs.isNotEmpty()) moodFeedCache[mood] = songs.distinctBy(::recommendationIdentity)
            }
        }
    }

    if (showFirstYouTubeMusicPrompt) {
        AlertDialog(
            onDismissRequest = accountsViewModel::markFirstYouTubeMusicSignInPromptShown,
            title = { Text("Make VYBE yours") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect YouTube Music to unlock:")
                    Text("• Your playlists, liked music, and recent history")
                    Text("• Personalized Quick Picks and recommendations")
                    Text("• Optional playlist and encrypted settings sync across devices")
                    Text(
                        "VYBE still works without signing in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountsViewModel.markFirstYouTubeMusicSignInPromptShown()
                        context.startActivity(
                            Intent(context, YouTubeLoginActivity::class.java)
                                .putExtra(YouTubeLoginActivity.EXTRA_AUTO_START_SIGN_IN, true)
                        )
                    },
                ) { Text("Sign in to YouTube Music") }
            },
            dismissButton = {
                TextButton(onClick = accountsViewModel::markFirstYouTubeMusicSignInPromptShown) {
                    Text("Not now")
                }
            },
        )
    }

    val restoreState = settingsSyncState as? YouTubeSettingsSyncState.RestoreAvailable
    if (restoreState != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restore VYBE settings?") },
            text = {
                Text(
                    "A settings backup linked to this YouTube Music account was found " +
                        "(${restoreState.settingCount} settings). Restore it on this device, " +
                        "including your encrypted AI configuration, or keep this installation's defaults."
                )
            },
            confirmButton = {
                TextButton(onClick = accountsViewModel::restoreYouTubeMusicSettings) {
                    Text("Yes, restore")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = accountsViewModel::backupCurrentYouTubeMusicSettings) {
                        Text("Back up this phone")
                    }
                    TextButton(onClick = accountsViewModel::skipYouTubeMusicSettingsRestore) {
                        Text("Skip")
                    }
                }
            },
        )
    }

    val yourMixSongs = remember(
        curatedYourMixSongs,
        dailyMixSongs,
        discoverySongs,
        homeMixPreviewSongs,
    ) {
        when {
            curatedYourMixSongs.isNotEmpty() -> curatedYourMixSongs
            dailyMixSongs.isNotEmpty() -> dailyMixSongs
            discoverySongs.isNotEmpty() -> discoverySongs
            homeMixPreviewSongs.isNotEmpty() -> homeMixPreviewSongs
            else -> emptyList()
        }.toImmutableList()
    }
    var homePlaceholderRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var hasHomeLoadingMinimumElapsed by rememberSaveable(homePlaceholderRefreshGeneration) {
        mutableStateOf(false)
    }

    LaunchedEffect(homePlaceholderRefreshGeneration, yourMixSongs.isEmpty()) {
        if (yourMixSongs.isEmpty()) {
            hasHomeLoadingMinimumElapsed = false
            delay(HomeLoadingPlaceholderMinDurationMillis)
            hasHomeLoadingMinimumElapsed = true
        } else {
            hasHomeLoadingMinimumElapsed = true
        }
    }

    val shouldShowYourMixLoadingPlaceholder = yourMixSongs.isEmpty() && !hasHomeLoadingMinimumElapsed
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = 64
        )
    }
    val recentlyPlayedSourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val recentlyPlayedSourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = recentlyPlayedSourceSongsInitialValue)
    val latestRecentlyPlayedSongs = remember(playbackHistory, recentlyPlayedSourceSongs) {
        val sourceSongs = recentlyPlayedSourceSongs ?: return@remember emptyList()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = sourceSongs,
            maxItems = 64
        )
    }
    // Keep the visible Home snapshot stable and only refresh it once the screen is off-screen.
    var recentlyPlayedSongs by rememberSaveable { mutableStateOf(latestRecentlyPlayedSongs) }
    val latestRecentlyPlayedSongsState = rememberUpdatedState(latestRecentlyPlayedSongs)

    // Bumped whenever Home becomes visible again (including returning from the background) and by
    // an explicit refresh. Shelf latching is keyed on it, so rows stay still while the listener is
    // using Home but are rebuilt from the latest data on the next visit.
    var homeShelfGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(latestRecentlyPlayedSongs, lifecycleOwner) {
        val isHomeVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (recentlyPlayedSongs.isEmpty() || !isHomeVisible) {
            recentlyPlayedSongs = latestRecentlyPlayedSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recentlyPlayedSongs = latestRecentlyPlayedSongsState.value
            }
            // Deliberately no generation bump on ON_RESUME. Coming back from another app is not a
            // request for new content: it was releasing the shelf latch and re-picking the row
            // every single time, which is the "songs keep changing by themselves" complaint.
            // Only the explicit refresh buttons move a shelf now.
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val recentlyPlayedQueue = remember(recentlyPlayedSongs) {
        // A history row whose metadata was never cached degrades to a "Online Track" stub. Those
        // must never reach a shelf, so the placeholder is dropped instead of rendered.
        recentlyPlayedSongs.map { it.song }
            .filter { isRealSongEntry(it) }
            .toImmutableList()
    }
    val providerListenAgain = remember(youtubeHomeShelves) {
        youtubeHomeShelves.firstOrNull { it.title.contains("listen again", ignoreCase = true) }
            ?.songs.orEmpty()
    }
    val listenAgainSongs = remember(providerListenAgain, recentlyPlayedQueue, isYouTubeLoggedIn) {
        // The user's own history comes first, most recent first. Provider "Listen Again" only
        // fills the remaining slots, so the row always reflects what was actually played.
        (recentlyPlayedQueue + providerListenAgain)
            .distinctBy(::recommendationIdentity)
            .take(10)
    }
    // A fresh session ordering prevents Home from presenting the exact same first cards
    // on every launch while keeping the underlying personalised recommendation pool intact.
    val sessionQuickPicks = remember(
        quickPickSongs, youtubeHomeShelves, trendingSongs, latestReleaseSongs, listenAgainSongs,
        isYouTubeLoggedIn,
    ) {
        val listenAgainKeys = listenAgainSongs.mapTo(hashSetOf(), ::recommendationIdentity)
        // The song the listener just played must not come back under a different video id or as a
        // same-titled upload by another artist. Matching on the title alone is intentional: those
        // are exactly the "other song with the same name" cards that felt unrelated.
        val lastPlayedTitle = recentlyPlayedQueue.firstOrNull()?.title?.trim()?.lowercase()
        val nonHistoryShelves = youtubeHomeShelves
            .filterNot { it.title.contains("listen again", ignoreCase = true) }
            .flatMap { it.songs }
        (quickPickSongs + nonHistoryShelves + discoverySongs + latestReleaseSongs + trendingSongs)
            .distinctBy(::recommendationIdentity)
            .filterNot { recommendationIdentity(it) in listenAgainKeys }
            .filterNot { lastPlayedTitle != null && it.title.trim().lowercase() == lastPlayedTitle }
            .take(10)
    }
    // Speed Dial rotates through the listener's own pool: history first, then the provider's
    // "listen again" shelf. The refresh button re-seeds the pick so every press shows a genuinely
    // different set instead of re-rendering the same eight cards.
    //
    // The old implementation advanced a rotation offset over the pool. When the pool held exactly
    // eight songs (a fresh install is usually exactly that) the offset wrapped back to the same
    // window and the button did visibly nothing.
    var speedDialGeneration by remember { mutableIntStateOf(0) }
    // Latch each row once it has settled. Home is assembled from several asynchronous sources
    // (local history, cache, radio, provider Home), so a later response used to replace a card a
    // moment after it appeared - including the card the listener was already tapping.
    val stableQuickPicks = rememberStableShelf(sessionQuickPicks, homeShelfGeneration)
    val stableListenAgainSongs = rememberStableShelf(listenAgainSongs, homeShelfGeneration)
    val speedDialPool = remember(recentlyPlayedQueue, providerListenAgain, frequencySpeedDial) {
        // Most-played first: the engagement ranking leads, then everything else that is
        // listenable from history, so the default 8 tiles are the user's heaviest songs.
        (frequencySpeedDial + recentlyPlayedQueue + providerListenAgain)
            .distinctBy(::recommendationIdentity)
            .toImmutableList()
    }
    val stableSpeedDialPool = rememberStableShelf(speedDialPool, homeShelfGeneration)
    var speedDialSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(stableSpeedDialPool, stableQuickPicks, speedDialGeneration, homeShelfGeneration) {
        if (stableSpeedDialPool.isEmpty()) {
            speedDialSongs = emptyList()
            return@LaunchedEffect
        }
        val reservedIdentities = stableQuickPicks.mapTo(hashSetOf(), ::recommendationIdentity)
        val eligible = stableSpeedDialPool
            .filterNot { recommendationIdentity(it) in reservedIdentities }
            .ifEmpty { stableSpeedDialPool }
        if (eligible.size <= 8) {
            // Nothing to rotate - show all of them rather than a duplicate window.
            speedDialSongs = eligible
            return@LaunchedEffect
        }
        if (speedDialGeneration == 0) {
            // First paint: the listener's most-played songs, in play-count order.
            speedDialSongs = eligible.take(8)
            return@LaunchedEffect
        }
        val previous = speedDialSongs.mapTo(hashSetOf(), ::recommendationIdentity)
        var picked = emptyList<Song>()
        var attempt = 0
        while (attempt < 16) {
            val seeded = eligible.shuffled(
                kotlin.random.Random(speedDialGeneration.toLong() * 1_000_003L + attempt)
            )
            picked = seeded.take(8)
            if (picked.mapTo(hashSetOf(), ::recommendationIdentity) != previous) break
            attempt++
        }
        speedDialSongs = picked
    }
    val speedDialRandomPool = remember(stableSpeedDialPool) {
        stableSpeedDialPool
    }
    val historySeed = recentlyPlayedQueue.firstOrNull()
    val relatedSongs = remember(historySeed, quickPickSongs, dailyMixSongs) {
        val artist = historySeed?.artist.orEmpty()
        (quickPickSongs + dailyMixSongs)
            .filter { it.id != historySeed?.id && (artist.isBlank() || it.artist.contains(artist, true)) }
            .distinctBy { it.id }.take(10)
    }
    ReportDrawnWhen {
        yourMixSongs.isNotEmpty() || hasHomeLoadingMinimumElapsed || isBenchmarkMode
    }

    val yourMixSong: String = "Today's Mix for you"

    // 2) Observar sólo el currentSong (o null) para saber si mostrar padding
    val currentSong by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong }
    }.collectAsStateWithLifecycle(initialValue = null)

    // 3) Observe shuffle state for sync
    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Padding inferior si hay canción en reproducción
    val bottomPadding = if (currentSong != null) MiniPlayerHeight else 0.dp
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode)

    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    var showProfileBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val homeStatsOverview by statsViewModel.homeOverview.collectAsStateWithLifecycle()

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val density = LocalDensity.current
    val scrollThresholdPx = remember(density) { with(density) { 180.dp.toPx() } }
    val isScrolledPastThreshold = remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThresholdPx }
    }

    // Persist the scroll position across navigation away/back. The Stats card and other
    // conditional sections can shift indices while data re-emits when returning, which
    // would otherwise leave the list scrolled to the wrong place or jump to the top.
    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var needsScrollRestore by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                savedScrollIndex = listState.firstVisibleItemIndex
                savedScrollOffset = listState.firstVisibleItemScrollOffset
                needsScrollRestore = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        needsScrollRestore,
        yourMixSongs.isNotEmpty(),
        dailyMixSongs.isNotEmpty(),
        recentlyPlayedSongs.size,
        homeStatsOverview
    ) {
        if (!needsScrollRestore) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        val targetIndex = savedScrollIndex.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        listState.scrollToItem(targetIndex, savedScrollOffset)
        needsScrollRestore = false
    }

    // Drawer state for sidebar
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val isRefreshing by playerViewModel.isHomeRefreshing.collectAsStateWithLifecycle()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { playerViewModel.forceUpdateDailyMix() },
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HomeGradientTopBar(
                    profileImageUrl = youTubeAccountAvatarUrl,
                    isYouTubeMusicConnected = isYouTubeLoggedIn,
                    onMoreOptionsClick = {},
                    onTelegramClick = {
                        showProfileBottomSheet = true
                    },
                    onProfileClick = {
                        showProfileBottomSheet = true
                    },
                    onRefreshClick = {
                        playerViewModel.forceUpdateDailyMix()
                    },
                    onMenuClick = {
                        // onOpenSidebar() // Disabled
                    },
                    isScrolled = isScrolledPastThreshold.value
                )
            }
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = paddingValuesParent.calculateBottomPadding()
                            + 38.dp + bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                val enabledHomeSections = settingsUiState.homeSectionOrder
                    .filterNot { it in settingsUiState.hiddenHomeSections }
                    .let { configured ->
                        if (isYouTubeLoggedIn) configured.filter {
                            it == HomeSectionPreference.MOODS ||
                                it == HomeSectionPreference.QUICK_PICKS ||
                                it == HomeSectionPreference.SPEED_DIAL ||
                                it == HomeSectionPreference.LISTEN_AGAIN ||
                                it == HomeSectionPreference.YOUTUBE_SHELVES
                        } else configured
                    }
                enabledHomeSections.forEach { homeSection -> if (selectedHomeMood == null || homeSection == HomeSectionPreference.MOODS) when (homeSection) {
                HomeSectionPreference.MOODS -> item(key = "home_mood_shortcuts", contentType = "mood_shortcuts") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(homeMoodLabels) { mood ->
                            Surface(
                                onClick = {
                                    if (selectedHomeMood == mood) {
                                        activeMoodFeed = null
                                    } else {
                                        val cachedFeed = moodFeedCache[mood].orEmpty()
                                        if (cachedFeed.isNotEmpty()) {
                                            activeMoodFeed = mood to cachedFeed
                                        }
                                        scope.launch {
                                            val refreshed = playerViewModel.loadMoodMix(mood)
                                                .distinctBy(::recommendationIdentity)
                                            if (refreshed.isNotEmpty()) {
                                                moodFeedCache[mood] = refreshed
                                                activeMoodFeed = mood to refreshed
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.glassPanel(
                                    shape = RoundedCornerShape(18.dp),
                                    tint = if (selectedHomeMood == mood) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    alpha = 0.56f,
                                    elevation = 6.dp,
                                ),
                                color = when {
                                    LocalImmersiveGlass.current -> Color.Transparent
                                    selectedHomeMood == mood -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ) {
                                Text(
                                    mood,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    selectedHomeMood?.let { activeMood ->
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = "$activeMood for you",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "A complete $activeMood feed from YouTube Music, tuned to your listening.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // YouTube Trending — always rendered prominently on Home
                // Keep discovery immediately accessible, before the large Your Mix hero.
                HomeSectionPreference.NEW_FINDS -> if (discoverySongs.isNotEmpty() && youtubeHomeShelves.none {
                        it.title.contains("fresh", true) || it.title.contains("new find", true)
                    }) {
                    item(key = "new_finds_section", contentType = "youtube_music_row") {
                        YouTubeMusicHomeRow(
                            title = "New Finds",
                            songs = discoverySongs.take(10),
                            queueName = "New Finds",
                            playerViewModel = playerViewModel,
                            onOpenSection = { openShelfDetail(navController, "New Finds", discoverySongs) },
                        )
                    }
                }

                HomeSectionPreference.QUICK_PICKS -> if (stableQuickPicks.isNotEmpty()) item(
                    key = "quick_picks_section",
                    contentType = "youtube_music_row"
                ) {
                    YouTubeMusicHomeRow(
                        title = "Quick Picks",
                        songs = stableQuickPicks,
                        queueName = "Quick Picks",
                        playerViewModel = playerViewModel,
                            compactListStyle = true,
                            onOpenSection = { openShelfDetail(navController, "Quick Picks", stableQuickPicks) },
                    )
                }

                HomeSectionPreference.SPEED_DIAL -> if (speedDialSongs.isNotEmpty()) {
                    item(key = "speed_dial_section", contentType = "youtube_music_row") {
                        SpeedDialGrid(
                            songs = speedDialSongs,
                            randomPool = speedDialRandomPool,
                            playerViewModel = playerViewModel,
                            onRefresh = {
                                // Re-pick the visible eight from the listener's own pool and kick a
                                // real refresh so a fresh pool can replace a stale one.
                                speedDialGeneration++
                                playerViewModel.forceUpdateDailyMix()
                            },
                        )
                    }
                }

                HomeSectionPreference.LISTEN_AGAIN -> if (listenAgainSongs.isNotEmpty()) {
                    item(key = "listen_again_section", contentType = "youtube_music_row") {
                        YouTubeMusicHomeRow(
                            title = "Listen Again",
                            songs = stableListenAgainSongs,
                            queueName = "Listen Again",
                            playerViewModel = playerViewModel,
                            onOpenSection = { openShelfDetail(navController, "Listen Again", stableListenAgainSongs) },
                        )
                    }
                }

                // Render the remaining provider-authored shelves in the exact order returned by
                // YouTube Music. VYBE only applies its own visual language; it does not relabel
                // random search results as charts, mixes, community playlists or long listens.
                HomeSectionPreference.YOUTUBE_SHELVES -> youtubeHomeShelves
                    .filterNot { shelf ->
                        shelf.title.contains("quick picks", true) ||
                            shelf.title.contains("listen again", true) ||
                            shelf.title.contains("podcast", true)
                    }
                    .forEachIndexed { index, shelf ->
                        item(
                            key = "youtube_home_${index}_${shelf.title}",
                            contentType = "youtube_music_row",
                        ) {
                            if (shelf.songs.isNotEmpty()) {
                                YouTubeMusicHomeRow(
                                    title = shelf.title,
                                    songs = shelf.songs,
                                    queueName = shelf.title,
                                    playerViewModel = playerViewModel,
                                    onOpenSection = { openShelfDetail(navController, shelf.title, shelf.songs) },
                                )
                            } else {
                                YouTubeMusicCollectionRow(
                                    title = shelf.title,
                                    collections = shelf.collections,
                                    navController = navController,
                                )
                            }
                        }
                    }

                HomeSectionPreference.YOUR_MIX -> {
                if (yourMixSongs.isNotEmpty()) {
                    item(
                        key = "your_mix_header",
                        contentType = "your_mix_header"
                    ) {
                        YourMixHeader(
                            song = yourMixSong,
                            isShuffleEnabled = isShuffleEnabled,
                            onPlayShuffled = {
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = yourMixSongs,
                                    queueName = "Your Mix",
                                    startAtZero = true,
                                )
                            }
                        )
                    }
                }

                // Collage
                if (yourMixSongs.isNotEmpty()) {
                    item(
                        key = "album_art_collage",
                        contentType = "album_art_collage"
                    ) {
                        val basePattern = settingsUiState.collagePattern
                        val isAutoRotate = settingsUiState.collageAutoRotate
                        val patterns = remember { CollagePattern.entries }

                        val activePattern = if (isAutoRotate) {
                            var rotationIndex by rememberSaveable { mutableIntStateOf(-1) }
                            LaunchedEffect(Unit) { rotationIndex++ }
                            remember(rotationIndex) {
                                patterns[rotationIndex.coerceAtLeast(0) % patterns.size]
                            }
                        } else {
                            basePattern
                        }

                        AlbumArtCollage(
                            modifier = Modifier.fillMaxWidth(),
                            songs = yourMixSongs,
                            padding = 14.dp,
                            height = 400.dp,
                            pattern = activePattern,
                            onSongClick = { song ->
                                playerViewModel.showAndPlaySong(song, yourMixSongs, "Your Mix")
                            }
                        )
                    }
                }

                }

                HomeSectionPreference.RELEASES -> if (latestReleaseSongs.isNotEmpty() && youtubeHomeShelves.none {
                        it.title.contains("new release", true) || it.title.contains("release radar", true)
                    }) {
                    item(key = "release_radar", contentType = "youtube_music_row") {
                        YouTubeMusicHomeRow("New Releases & Release Radar", latestReleaseSongs.take(10), "Release Radar", playerViewModel,
                            onOpenSection = { openShelfDetail(navController, "New Releases & Release Radar", latestReleaseSongs) })
                    }
                }
                HomeSectionPreference.CHARTS -> if (trendingSongs.isNotEmpty() && youtubeHomeShelves.none {
                        it.title.contains("chart", true) || it.title.contains("trending", true)
                    }) {
                    item(key = "top_charts", contentType = "youtube_music_row") {
                        YouTubeMusicHomeRow("Top Charts & Trending", trendingSongs.take(10), "Top Charts", playerViewModel,
                            onOpenSection = { openShelfDetail(navController, "Top Charts & Trending", trendingSongs) })
                    }
                }
                HomeSectionPreference.BECAUSE_YOU_LISTENED -> if (historySeed != null && relatedSongs.isNotEmpty()) {
                    item(key = "because_you_listened", contentType = "youtube_music_row") {
                        YouTubeMusicHomeRow("Because You Listened to ${historySeed.artist}", relatedSongs, "Because You Listened", playerViewModel,
                            onOpenSection = { openShelfDetail(navController, "Because You Listened to ${historySeed.artist}", relatedSongs) })
                    }
                }
                // Daily Mix
                HomeSectionPreference.DAILY_MIX -> if (dailyMixSongs.isNotEmpty()) {
                    item(
                        key = "daily_mix_section",
                        contentType = "daily_mix_section"
                    ) {
                        DailyMixSection(
                            songs = dailyMixSongs,
                            onClickOpen = {
                                navController.navigateSafely(Screen.DailyMixScreen.route)
                            },
                            onNavigateToAlbum = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.AlbumDetail.createRoute(song.albumId),
                                    patternToPop = Screen.AlbumDetail.route
                                )
                            },
                            onNavigateToArtist = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.ArtistDetail.createRoute(song.artistId),
                                    patternToPop = Screen.ArtistDetail.route
                                )
                            },
                            onNavigateToGenre = { song ->
                                song.genre?.let {
                                    navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                                }
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }

                HomeSectionPreference.RECENTLY_PLAYED -> if (recentlyPlayedSongs.size >= RecentlyPlayedSectionMinSongsToShow) {
                    item(
                        key = "recently_played_section",
                        contentType = "recently_played_section"
                    ) {
                        RecentlyPlayedSection(
                            songs = recentlyPlayedSongs,
                            onSongClick = { song ->
                                if (recentlyPlayedQueue.isNotEmpty()) {
                                    playerViewModel.playSongs(
                                        songsToPlay = recentlyPlayedQueue,
                                        startSong = song,
                                        queueName = "Recently Played"
                                    )
                                }
                            },
                            onOpenAllClick = {
                                navController.navigateSafely(Screen.RecentlyPlayed.route)
                            },
                            themeStateHolder = playerViewModel.themeStateHolder,
                            currentSongId = currentSong?.id,
                            contentPadding = PaddingValues(start = 8.dp, end = 24.dp)
                        )
                    }
                }
                else -> Unit
                } }
                // Never replace Home with a blocking loading page. A first uncached request keeps
                // the current provider shelves visible; the mood feed swaps in only when ready.
                if (selectedHomeMood != null && selectedMoodSongs.isNotEmpty()) {
                    item(key = "mood_quick_picks") {
                        YouTubeMusicHomeRow(
                            title = "${selectedHomeMood} Quick Picks",
                            songs = selectedMoodSongs.take(12),
                            queueName = "${selectedHomeMood} Quick Picks",
                            playerViewModel = playerViewModel,
                            compactListStyle = true,
                            onOpenSection = { openShelfDetail(navController, "${selectedHomeMood} Quick Picks", selectedMoodSongs) },
                        )
                    }
                    // Only show a second row when the provider actually returned more than the
                    // first twelve songs. The previous fallback repeated a shuffled copy of the
                    // same twelve, which read as unrelated filler.
                    val moreMoodSongs = selectedMoodSongs.drop(12).take(12)
                    if (moreMoodSongs.isNotEmpty()) {
                        item(key = "mood_more_for_you") {
                            YouTubeMusicHomeRow(
                                title = "More ${selectedHomeMood} music",
                                songs = moreMoodSongs,
                                queueName = "More ${selectedHomeMood} music",
                                playerViewModel = playerViewModel,
                                onOpenSection = { openShelfDetail(navController, "More ${selectedHomeMood} music", selectedMoodSongs) },
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        ) {

        }
    }
    if (showOptionsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = sheetState
        ) {
            HomeOptionsBottomSheet(
                onNavigateToMashup = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            showOptionsBottomSheet = false
                            navController.navigateSafely(Screen.DJSpace.route)
                        }
                    }
                }
            )
        }
    }
    if (showProfileBottomSheet) {
        com.theveloper.pixelplay.presentation.components.ProfileBottomSheet(
            navController = navController,
            onDismiss = { showProfileBottomSheet = false },
            statsViewModel = statsViewModel
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun YourMixLoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(256.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun YourMixEmptyPlaceholder(
    onRefresh: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 256.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 28.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBR = 28.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = 28.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusTR = 28.dp,
                    smoothnessAsPercentBL = 60,
                ),
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_empty_placeholder_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.home_empty_placeholder_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FilledTonalButton(
                onClick = onRefresh,
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 22.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBR = 22.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = 22.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusTR = 22.dp,
                    smoothnessAsPercentBL = 60,
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.home_empty_placeholder_refresh))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YourMixHeader(
    song: String,
    isShuffleEnabled: Boolean = false,
    onPlayShuffled: () -> Unit
) {
    val buttonCorners = 68.dp
    val colors = MaterialTheme.colorScheme

    val titleStyle = rememberYourMixTitleStyle()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(256.dp)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp, start = 12.dp)
        ) {
            // Your Mix Title
            Text(
                text = stringResource(R.string.home_your_mix_title),
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
            )

            // Artist/Song subtitle
            Text(
                text = song,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        // Play Button - color changes based on shuffle state
        LargeExtendedFloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp),
            onClick = onPlayShuffled,
            containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
            contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusTL = buttonCorners,
                smoothnessAsPercentTR = 60,
                cornerRadiusBR = buttonCorners,
                smoothnessAsPercentTL = 60,
                cornerRadiusBL = buttonCorners,
                smoothnessAsPercentBR = 60,
                cornerRadiusTR = buttonCorners,
                smoothnessAsPercentBL = 60,
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_shuffle_24),
                contentDescription = stringResource(R.string.common_shuffle_play),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}


// SongListItem (modificado para aceptar parámetros individuales)
@Composable
fun SongListItemFavs(
    modifier: Modifier = Modifier,
    cardCorners: Dp = 12.dp,
    title: String,
    artist: String,
    albumArtUrl: String?,
    isPlaying: Boolean,
    isCurrentSong: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalImmersiveGlass.current
    val containerColor = when {
        isCurrentSong -> colors.primaryContainer.copy(alpha = if (glass) 0.38f else 0.46f)
        glass -> Color.Transparent
        else -> colors.surfaceContainer
    }
    val contentColor = if (isCurrentSong) colors.primary else colors.onSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(
                shape = RoundedCornerShape(cardCorners),
                tint = if (isCurrentSong) colors.primaryContainer else colors.surfaceContainer,
                alpha = if (isCurrentSong) 0.42f else 0.58f,
                elevation = 8.dp,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cardCorners),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = albumArtUrl,
                    contentDescription = stringResource(R.string.common_album_art_for_title, title),
                    contentScale = ContentScale.Crop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist, style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (isCurrentSong) {
                PlayingEqIcon(
                    modifier = Modifier
                        .weight(0.1f)
                        .padding(start = 8.dp)
                        .size(width = 18.dp, height = 16.dp), // similar al tamaño del ícono
                    color = colors.primary,
                    isPlaying = isPlaying  // o conectalo a tu estado real de reproducción
                )
            }
        }
    }
}

// Wrapper Composable for SongListItemFavs to isolate state observation
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SongListItemFavsWrapper(
    song: Song,
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Collect the stablePlayerState once
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    // Derive isThisSongPlaying using remember
    val isThisSongPlaying = remember(song.id, stablePlayerState.currentSong?.id, stablePlayerState.isPlaying) {
        song.id == stablePlayerState.currentSong?.id
    }

    // Call the presentational composable
    SongListItemFavs(
        modifier = modifier,
        cardCorners = 0.dp,
        title = song.title,
        artist = song.displayArtist,
        albumArtUrl = song.albumArtUriString,
        isPlaying = stablePlayerState.isPlaying,
        isCurrentSong = song.id == stablePlayerState.currentSong?.id,
        onClick = onClick
    )
}


@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberYourMixTitleStyle(): TextStyle {
    return remember {
        TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(636),
                        FontVariation.width(152f),
                        FontVariation.Setting("ROND", 50f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(760),
            fontSize = 64.sp,
            lineHeight = 62.sp
        )
    }
}

private fun recommendationIdentity(song: Song): String = buildString {
    append(song.title.trim().lowercase().replace(Regex("\\s+"), " "))
    append('|')
    append(song.artist.trim().lowercase().replace(Regex("\\s+"), " "))
}

/**
 * Shows YouTube Music trending tracks on the Home screen as a horizontal card row.
 * Always renders even when no local library exists, giving the user something to listen to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeMusicHomeRow(
    title: String,
    songs: List<Song>,
    queueName: String,
    playerViewModel: PlayerViewModel,
    compactListStyle: Boolean = false,
    onOpenSection: (() -> Unit)? = null,
) {
    // Context menu
    val contextMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contextMenuSong by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )
            if (songs.isNotEmpty()) {
                TextButton(onClick = { playerViewModel.playSongs(songs, songs.first(), queueName) }) {
                    Text("Play all")
                }
                onOpenSection?.let { open ->
                    Surface(onClick = open, shape = RoundedCornerShape(50)) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = "Open all $title",
                            modifier = Modifier.padding(8.dp).size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Music is temporarily unavailable. Pull down to retry.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        val visibleSongs = songs.take(10)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val rowGroups = if (compactListStyle) visibleSongs.chunked(4) else visibleSongs.map(::listOf)
            items(rowGroups, key = { group -> group.joinToString("|") { it.id } }) { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { song ->
                Card(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(260),
                            fadeOutSpec = androidx.compose.animation.core.tween(180),
                            placementSpec = androidx.compose.animation.core.spring(
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            ),
                        )
                        .width(if (compactListStyle) 330.dp else 164.dp)
                        .glassPanel(
                            shape = RoundedCornerShape(16.dp),
                            tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                            alpha = 0.56f,
                            elevation = 12.dp,
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = {
                                playerViewModel.playBrowseSong(song, songs, queueName)
                            },
                            onDoubleClick = {
                                playerViewModel.toggleFavoriteSpecificSong(song)
                            },
                            onLongClick = {
                                contextMenuSong = song
                            }
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (LocalImmersiveGlass.current) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    if (compactListStyle) Row(
                        modifier = Modifier.height(72.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = song.albumArtUriString,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(song.title, maxLines = 1, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
                            Text(song.displayArtist, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis)
                        }
                    } else Column {
                        AsyncImage(
                            model = song.albumArtUriString,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = song.title,
                                maxLines = 1,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = song.displayArtist,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (queueName == "Release Radar" && song.releaseDateEpochMillis > 0L) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = java.time.Instant.ofEpochMilli(song.releaseDateEpochMillis)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDate()
                                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
        }
    }

    var homeSongForPlaylist by remember { mutableStateOf<Song?>(null) }

    // Context bottom sheet
    contextMenuSong?.let { song ->
        val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
        val isFav = favoriteSongIds.contains(song.id)
        com.theveloper.pixelplay.presentation.components.SongContextBottomSheet(
            song = song,
            sheetState = contextMenuSheetState,
            onDismiss = { contextMenuSong = null },
            onPlayNext = { playerViewModel.addSongNextToQueue(song) },
            onAddToQueue = { playerViewModel.addSongToQueue(song) },
            onAddToPlaylist = {
                homeSongForPlaylist = song
            },
            onDownload = {
                com.theveloper.pixelplay.data.network.ytmusic.YouTubeDownloadManager
                    .fromContext(context)
                    .enqueueDownload(song)
            },
            isFavorite = isFav,
            onToggleFavorite = {
                playerViewModel.toggleFavoriteSpecificSong(song, removing = isFav)
            },
            onAlbum = if (
                !song.remoteAlbumBrowseId.isNullOrBlank() || song.albumId > 0L || song.id.startsWith("yt_")
            ) ({ playerViewModel.triggerAlbumNavigationFromPlayer(song) }) else null,
            onArtist = song.artists.firstOrNull()?.remoteBrowseId?.takeIf(String::isNotBlank)?.let { browseId ->
                { playerViewModel.triggerRemoteArtistNavigationFromPlayer(browseId) }
            } ?: song.artistId.takeIf { it > 0L }?.let { artistId ->
                { playerViewModel.triggerArtistNavigationFromPlayer(artistId) }
            },
        )
    }

    homeSongForPlaylist?.let { song ->
        com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet(
            songs = listOf(song),
            onDismiss = { homeSongForPlaylist = null },
            bottomBarHeight = 0.dp,
            playerViewModel = playerViewModel
        )
    }
}

@Composable
private fun YouTubeMusicCollectionRow(
    title: String,
    collections: List<YouTubeHomeCollection>,
    navController: NavController,
) {
    if (collections.isEmpty()) return
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                            items(collections.take(20), key = { it.browseId }) { collection ->
                Card(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(260),
                            fadeOutSpec = androidx.compose.animation.core.tween(180),
                            placementSpec = androidx.compose.animation.core.spring(
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            ),
                        )
                        .width(164.dp)
                        .glassPanel(
                            shape = RoundedCornerShape(16.dp),
                            tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                            alpha = 0.56f,
                            elevation = 12.dp,
                        )
                        .clickable {
                        val route = if (collection.pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                            Screen.ArtistDetail.createRoute(collection.browseId)
                        } else {
                            Screen.AlbumDetail.createRoute(collection.browseId)
                        }
                        navController.navigateSafely(route)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (LocalImmersiveGlass.current) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    AsyncImage(
                        model = collection.thumbnailUrl,
                        contentDescription = collection.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            collection.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (collection.subtitle.isNotBlank()) {
                            Text(
                                collection.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedDialGrid(
    songs: List<Song>,
    randomPool: List<Song>,
    playerViewModel: PlayerViewModel,
    onRefresh: () -> Unit = {},
) {
    val visibleSongs = songs.take(8)
    if (visibleSongs.isEmpty()) return
    val context = LocalContext.current
    val contextMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contextMenuSong by remember { mutableStateOf<Song?>(null) }
    var speedDialSongForPlaylist by remember { mutableStateOf<Song?>(null) }
    var randomDialTurns by remember { mutableIntStateOf(0) }
    val randomDialRotation by animateFloatAsState(
        targetValue = randomDialTurns * 720f,
        animationSpec = tween(durationMillis = 720),
        label = "speedDialRandomSpin",
    )

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed dial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp))
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh speed dial",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val gap = 8.dp
            val tileSize = (maxWidth - gap * 2) / 3
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                (0 until 3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        (0 until 3).forEach { column ->
                            val index = row * 3 + column
                            if (index < visibleSongs.size) {
                                val song = visibleSongs[index]
                                Box(
                                    modifier = Modifier
                                        .size(tileSize)
                                        .clip(RoundedCornerShape(14.dp))
                                        .combinedClickable(
                                            onClick = { playerViewModel.playBrowseSong(song, visibleSongs, "Speed dial") },
                                            onDoubleClick = { playerViewModel.toggleFavoriteSpecificSong(song) },
                                            onLongClick = { contextMenuSong = song },
                                        ),
                                ) {
                                    AsyncImage(
                                        model = song.albumArtUriString,
                                        contentDescription = song.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                                                ),
                                            ),
                                    )
                                    Text(
                                        text = song.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .padding(horizontal = 9.dp, vertical = 8.dp),
                                    )
                                }
                            } else if (index == 8) {
                                Box(
                                    modifier = Modifier
                                        .size(tileSize)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer,
                                                    MaterialTheme.colorScheme.tertiaryContainer,
                                                ),
                                            ),
                                        )
                                        .clickable {
                                            randomDialTurns += 1
                                            val pool = randomPool.ifEmpty { visibleSongs }
                                            pool.randomOrNull()?.let { randomSong ->
                                                playerViewModel.playBrowseSong(randomSong, pool, "Your random pick")
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier.size(92.dp).rotate(randomDialRotation),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.align(Alignment.TopCenter).size(22.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.primary))
                                        Box(Modifier.align(Alignment.BottomStart).size(18.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.tertiary))
                                        Box(Modifier.align(Alignment.BottomEnd).size(26.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.secondary))
                                        Surface(
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                            shadowElevation = 5.dp,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Shuffle,
                                                contentDescription = "Play a random song from your listening history",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(13.dp).size(26.dp),
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.size(tileSize))
                            }
                        }
                    }
                }
            }
        }
    }

    contextMenuSong?.let { song ->
        val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
        val isFavorite = song.id in favoriteSongIds
        com.theveloper.pixelplay.presentation.components.SongContextBottomSheet(
            song = song,
            sheetState = contextMenuSheetState,
            onDismiss = { contextMenuSong = null },
            onPlayNext = { playerViewModel.addSongNextToQueue(song) },
            onAddToQueue = { playerViewModel.addSongToQueue(song) },
            onAddToPlaylist = { speedDialSongForPlaylist = song },
            onDownload = {
                com.theveloper.pixelplay.data.network.ytmusic.YouTubeDownloadManager
                    .fromContext(context)
                    .enqueueDownload(song)
            },
            isFavorite = isFavorite,
            onToggleFavorite = {
                playerViewModel.toggleFavoriteSpecificSong(song, removing = isFavorite)
            },
            onAlbum = if (!song.remoteAlbumBrowseId.isNullOrBlank() || song.albumId > 0L) {
                { playerViewModel.triggerAlbumNavigationFromPlayer(song) }
            } else null,
            onArtist = song.artists.firstOrNull()?.remoteBrowseId?.takeIf(String::isNotBlank)?.let { browseId ->
                { playerViewModel.triggerRemoteArtistNavigationFromPlayer(browseId) }
            } ?: song.artistId.takeIf { it > 0L }?.let { artistId ->
                { playerViewModel.triggerArtistNavigationFromPlayer(artistId) }
            },
        )
    }

    speedDialSongForPlaylist?.let { song ->
        com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet(
            songs = listOf(song),
            onDismiss = { speedDialSongForPlaylist = null },
            bottomBarHeight = 0.dp,
            playerViewModel = playerViewModel,
        )
    }
}

@Composable
fun ReleaseRadarSection(
    songs: List<Song>,
    playerViewModel: PlayerViewModel,
) {
    val now = remember { System.currentTimeMillis() }
    val thirtyDaysAgo = remember(now) { now - 30L * 24 * 60 * 60 * 1000L }

    val radarSongs = remember(songs) {
        songs
            .filter { song ->
                val releaseTime = when {
                    song.dateAdded in thirtyDaysAgo..now -> song.dateAdded
                    song.dateModified in thirtyDaysAgo..now -> song.dateModified
                    else -> song.dateAdded.takeIf { it > thirtyDaysAgo } ?: now
                }
                releaseTime >= thirtyDaysAgo
            }
            .sortedByDescending { it.dateAdded.takeIf { d -> d > 0L } ?: it.dateModified }
            .take(20)
    }

    if (radarSongs.isEmpty()) return

    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "🚀 Release Radar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Fresh music released in the last 30 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(radarSongs, key = { "radar_${it.id}" }) { song ->
                val dateLabel = remember(song.dateAdded, song.dateModified) {
                    val timestamp = if (song.dateAdded > 0L) song.dateAdded else song.dateModified
                    if (timestamp <= 0L) "Recent"
                    else {
                        val diffDays = ((now - timestamp) / (1000 * 60 * 60 * 24)).toInt()
                        when (diffDays) {
                            0 -> "Today"
                            1 -> "Yesterday"
                            else -> {
                                val sdf = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                                sdf.format(java.util.Date(timestamp))
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .width(150.dp)
                        .clickable {
                            playerViewModel.showAndPlaySong(song, radarSongs, "Release Radar")
                        },
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            SmartImage(
                                model = song.albumArtUriString,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp),
                            ) {
                                Text(
                                    text = dateLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
