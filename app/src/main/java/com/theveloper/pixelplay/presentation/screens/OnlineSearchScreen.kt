package com.theveloper.pixelplay.presentation.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.theveloper.pixelplay.presentation.viewmodel.OnlineSearchViewModel
import com.theveloper.pixelplay.presentation.viewmodel.OnlineSearchFilter
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.AmbientRecognitionSheet
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SongContextBottomSheet
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeAlbum
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeArtist
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    paddingValuesParent: PaddingValues,
    viewModel: OnlineSearchViewModel = hiltViewModel()
) {
    val trendingTracks by viewModel.trendingTracks.collectAsStateWithLifecycle()
    val latestReleaseTracks by viewModel.latestReleaseTracks.collectAsStateWithLifecycle()
    val aiRecommendations by viewModel.aiRecommendations.collectAsStateWithLifecycle()
    val searchResultsSongs by viewModel.searchResultsSongs.collectAsStateWithLifecycle()
    val searchResultsAlbums by viewModel.searchResultsAlbums.collectAsStateWithLifecycle()
    val searchResultsArtists by viewModel.searchResultsArtists.collectAsStateWithLifecycle()
val searchTopResult by viewModel.searchTopResult.collectAsStateWithLifecycle()
    val searchFilter by viewModel.searchFilter.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val isYouTubeMusicConnected by viewModel.isYouTubeMusicConnected.collectAsStateWithLifecycle()
    val accountInterestLabels by viewModel.accountInterestLabels.collectAsStateWithLifecycle()
    val discoveryTitle by viewModel.discoveryTitle.collectAsStateWithLifecycle()
    val discoveryArtists by viewModel.discoveryArtists.collectAsStateWithLifecycle()
    val querySuggestions by viewModel.querySuggestions.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    // This is intentionally not saveable: returning to Explore must start with the
    // provider discovery page. Recent searches are revealed only after the user taps
    // the search field during the current screen visit.
    var searchActivated by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // A previous mood selection must not leak into the next Explore visit and appear
        // under the generic "Top songs" heading.
        viewModel.selectDiscovery("Best for You")
    }

    // Context menu state
    val contextMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contextMenuSong by remember { mutableStateOf<Song?>(null) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var isVoiceListening by remember { mutableStateOf(false) }
    var showAmbientRecognition by rememberSaveable { mutableStateOf(false) }
    val speechRecognizer = remember(context) {
        runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        }.getOrNull()
    }
    DisposableEffect(speechRecognizer) {
        runCatching {
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isVoiceListening = true }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { isVoiceListening = false }
                override fun onError(error: Int) {
                    isVoiceListening = false
                    Toast.makeText(context, "Voice search could not hear a clear title or artist.", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isVoiceListening = false
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { spoken ->
                        query = spoken
                        selectedGenre = null
                        viewModel.submitSearch(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }
    val beginVoiceListening: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song title or artist")
        }
        speechRecognizer?.startListening(intent)
            ?: Toast.makeText(context, "Voice recognition is unavailable on this device.", Toast.LENGTH_SHORT).show()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoiceListening() else Toast.makeText(context, "Microphone permission is required for voice search.", Toast.LENGTH_SHORT).show()
    }
    val startVoiceSearch: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginVoiceListening()
        } else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val searchInputFocusRequester = remember { FocusRequester() }
    val bottomPadding = paddingValuesParent.calculateBottomPadding() + MiniPlayerHeight + 16.dp

    val isSearching = query.isNotBlank()
    val discoveryChips = remember {
        listOf(
            "Best for You", "Latest Releases", "Charts", "Workout", "Feel good",
            "Energize", "Relax", "Romance", "Focus", "Party", "Punjabi", "Indian",
        )
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        // Cached discovery remains visible during refresh; the centered cold-start
        // indicator is the single loading affordance on this screen.
        isRefreshing = false,
        onRefresh = {
            selectedGenre = null
            viewModel.fetchTrending()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(paddingValuesParent.calculateTopPadding() + 16.dp))

        // ── Search Bar ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                DockedSearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            modifier = Modifier
                                .focusRequester(searchInputFocusRequester)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { searchActivated = true })
                                },
                            query = query,
                            onQueryChange = {
                                query = it
                                selectedGenre = null
                                if (it.isNotBlank()) viewModel.search(it)
                                else viewModel.clearSearch()
                            },
                            onSearch = { if (query.isNotBlank()) viewModel.submitSearch(query) },
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Search songs, albums, artists") },
                            trailingIcon = {
                                Row {
                                IconButton(onClick = { showAmbientRecognition = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = "Recognize a song playing nearby",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = startVoiceSearch) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = if (isVoiceListening) "Listening" else "Voice search",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (query.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            query = ""
                                            viewModel.clearSearch()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear search",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                }
                            },
                            colors = SearchBarDefaults.inputFieldColors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = SearchBarDefaults.colors(
                        containerColor = Color.Transparent,
                        dividerColor = Color.Transparent
                    ),
                    content = {}
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (query.isNotBlank() && querySuggestions.isNotEmpty()) {
            // YouTube Music lists completions vertically so every suggestion is reachable.
            // A horizontal chip row only ever exposed the first couple of them.
            Column(Modifier.fillMaxWidth()) {
                querySuggestions.forEach { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                query = suggestion
                                selectedGenre = null
                                viewModel.submitSearch(suggestion)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (searchActivated && query.isBlank() && recentSearches.isNotEmpty()) {
            Text(
                text = "Recent searches",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recentSearches, key = { "recent_${it.id}_${it.query}" }) { history ->
                    InputChip(
                        selected = false,
                        onClick = {
                            query = history.query
                            selectedGenre = null
                            viewModel.submitSearch(history.query)
                        },
                        label = { Text(history.query, maxLines = 1) },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.deleteRecentSearch(history.query) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove ${history.query} from search history",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Chips Bar ───────────────────────────────────────────────────
        if (isSearching) {
            // Category filter chips: All, Songs, Albums, Artists
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OnlineSearchFilter.values().forEach { filter ->
                    FilterChip(
                        selected = searchFilter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.name.lowercase().capitalize()) },
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Content ─────────────────────────────────────────────────────
        val hasVisibleSearchResults = searchResultsSongs.isNotEmpty() ||
            searchResultsAlbums.isNotEmpty() || searchResultsArtists.isNotEmpty()
        val hasVisibleDiscovery = trendingTracks.isNotEmpty() || aiRecommendations.isNotEmpty() || discoveryArtists.isNotEmpty()
        if (isLoading && isSearching && !hasVisibleSearchResults) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (isSearching) {
            // ── Structured Search Results View ──────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (searchFilter == OnlineSearchFilter.ALL &&
                    (searchTopResult != null || searchResultsSongs.isNotEmpty())
                ) {
                    item(key = "top_result_header") {
                        Text(
                            text = "Top result",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    val hero = searchTopResult
                    if (hero != null) {
                        item(key = "top_result_${hero.browseId}_${hero.title}") {
                            val seedSong = hero.seedSong
                            ElevatedCard(
                                onClick = {
                                    keyboardController?.hide()
                                    viewModel.rememberSearch(query)
                                    when {
                                        hero.representsArtist &&
                                            hero.browseId.isNotBlank() ->
                                            navController.navigateSafely(
                                                Screen.ArtistDetail.createRoute("remote_artist|${hero.browseId}")
                                            )

                                        hero.representsAlbum &&
                                            hero.browseId.isNotBlank() ->
                                            navController.navigateSafely(
                                                Screen.AlbumDetail.createRoute("remote_album|${hero.browseId}")
                                            )

                                        seedSong != null ->
                                            playerViewModel.playOnlineSeed(seedSong, "VYBE Radio")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Column {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    AsyncImage(
                                        model = hero.thumbnailUrl ?: seedSong?.albumArtUriString,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(76.dp).clip(CircleShape),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = hero.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = hero.subtitle.ifBlank { hero.typeLabel },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (seedSong != null) {
                                        FilledIconButton(onClick = {
                                            keyboardController?.hide()
                                            viewModel.rememberSearch(query)
                                            playerViewModel.playOnlineSeed(seedSong, "VYBE Radio")
                                        }) {
                                            Icon(
                                                Icons.Rounded.PlayArrow,
                                                contentDescription = "Play ${hero.title}",
                                            )
                                        }
                                    }
                                }
                                // YouTube Music shows the tracks it pulled for the query inside the
                                // hero card and leaves them out of the list below. Rendering them here
                                // keeps that same layout and stops them from disappearing entirely.
                                val inlineSeeds = hero.seedSongs
                                    .dropWhile { it.title.equals(hero.title, ignoreCase = true) }
                                    .take(3)
                                if (inlineSeeds.isNotEmpty()) {
                                    inlineSeeds.forEach { seed ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    keyboardController?.hide()
                                                    viewModel.rememberSearch(query)
                                                    playerViewModel.playOnlineSeed(seed, "VYBE Radio")
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            AsyncImage(
                                                model = seed.albumArtUriString,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = seed.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = seed.artist,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    } else {
                    item(key = "top_result_${searchResultsSongs.first().id}") {
                        val topResult = searchResultsSongs.first()
                        ElevatedCard(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.rememberSearch(query)
                                playerViewModel.playOnlineSeed(topResult, "VYBE Radio")
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AsyncImage(
                                    model = topResult.albumArtUriString,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = topResult.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = topResult.artist,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FilledIconButton(onClick = {
                                    keyboardController?.hide()
                                    viewModel.rememberSearch(query)
                                    playerViewModel.playOnlineSeed(topResult, "VYBE Radio")
                                }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${topResult.title}")
                                }
                            }
                        }
                    }
                    }
                }

                // 1. Songs Section
                if (searchFilter == OnlineSearchFilter.ALL || searchFilter == OnlineSearchFilter.SONGS) {
                    if (searchResultsSongs.isNotEmpty()) {
                        val heroSeedIds = searchTopResult?.seedSongIds?.toHashSet() ?: hashSetOf()
                        val visibleSongs = if (searchFilter == OnlineSearchFilter.ALL) {
                            val listed = if (heroSeedIds.isNotEmpty()) {
                                // YouTube Music renders the card's tracks inside the card, so they
                                // are excluded from the list underneath to avoid showing them twice.
                                searchResultsSongs.filterNot { it.id in heroSeedIds }
                            } else {
                                searchResultsSongs.drop(1)
                            }
                            listed.take(7)
                        } else {
                            searchResultsSongs
                        }
                        item(key = "songs_header") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    keyboardController?.hide()
                                    playerViewModel.playSongs(searchResultsSongs, searchResultsSongs.first(), "Search: $query")
                                }) {
                                    Text("Play all")
                                }
                                IconButton(onClick = { viewModel.setFilter(OnlineSearchFilter.SONGS) }) {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open all songs")
                                }
                            }
                        }
                        items(
                            count = visibleSongs.size,
                            key = { "song_${visibleSongs[it].id}" }
                        ) { index ->
                            val song = visibleSongs[index]
                            Box(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = androidx.compose.animation.core.tween(240),
                                        fadeOutSpec = androidx.compose.animation.core.tween(160),
                                        placementSpec = androidx.compose.animation.core.spring(
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                        ),
                                    )
                                    .padding(horizontal = 16.dp),
                            ) {
                                EnhancedSongListItem(
                                    song = song.copy(artist = "${song.artist} • ${song.album}"),
                                    isPlaying = false,
                                    isCurrentSong = false,
                                    showMoreOptionsButton = true,
                                    onLongPress = { contextMenuSong = song },
                                    onMoreOptionsClick = { contextMenuSong = song },
                                    onClick = {
                                        keyboardController?.hide()
                                        viewModel.rememberSearch(query)
                                        playerViewModel.playOnlineSeed(song, "VYBE Radio")
                                    }
                                )
                            }
                        }
                    }
                }



                // 2. Albums Section
                if (searchFilter == OnlineSearchFilter.ALL || searchFilter == OnlineSearchFilter.ALBUMS) {
                    if (searchResultsAlbums.isNotEmpty()) {
                        item(key = "albums_header") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Albums, Movies & Soundtracks", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.setFilter(OnlineSearchFilter.ALBUMS) }) {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open all albums")
                                }
                            }
                        }
                        itemsIndexed(
                            items = searchResultsAlbums,
                            key = { index, album -> "album_${album.browseId}_$index" },
                        ) { _, album ->
                            AlbumSearchCard(
                                album = album,
                                onClick = {
                                    keyboardController?.hide()
                                    navController.navigateSafely(
                                        Screen.AlbumDetail.createRoute("remote_album|${album.browseId}")
                                    )
                                }
                            )
                        }
                    }
                }

                // 3. Artists Section
                if (searchFilter == OnlineSearchFilter.ALL || searchFilter == OnlineSearchFilter.ARTISTS) {
                    if (searchResultsArtists.isNotEmpty()) {
                        item(key = "artists_header") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Artists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.setFilter(OnlineSearchFilter.ARTISTS) }) {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open all artists")
                                }
                            }
                        }
                        item(key = "artists_grid") {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                searchResultsArtists.chunked(3).forEach { rowArtists ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        rowArtists.forEach { artist ->
                                    ArtistSearchCard(
                                        artist = artist,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            keyboardController?.hide()
                                            navController.navigateSafely(
                                                Screen.ArtistDetail.createRoute("remote_artist|${artist.browseId}")
                                            )
                                        }
                                    )
                                        }
                                        repeat(3 - rowArtists.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (searchResultsSongs.isEmpty() && searchResultsAlbums.isEmpty() && searchResultsArtists.isEmpty()) {
                    item(key = "search_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = searchError ?: "No results found for \"$query\"",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                if (searchError != null) {
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.submitSearch(query) }) {
                                        Text("Try again")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ── Idle / Trending View ─────────────────────────────────────────
            val defaultExploreTracks = remember(aiRecommendations, trendingTracks) {
                // A one-item local history snapshot is an instant placeholder, not a complete
                // discovery shelf. Prefer the expanded account/radio pool once it is available.
                val source = if (aiRecommendations.size > trendingTracks.size) aiRecommendations else trendingTracks
                // The same recording is frequently published under several video ids (album,
                // single, topic channel). Collapse those to a single card so the grid never shows
                // what looks like a random repeat of a song the user just saw.
                source.distinctBy { song ->
                    "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                item(key = "explore_title") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Explore", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (isYouTubeMusicConnected) "New music and charts, personalized from your account"
                            else "Discover what is new and popular on YouTube Music",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "explore_destinations") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        listOf(
                            listOf("New releases", "Charts"),
                            listOf("Moods and genres"),
                        ).forEach { destinationRow ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                destinationRow.forEach { title ->
                            val target = when (title) {
                                "New releases" -> "Latest Releases"
                                "Moods and genres" -> "Moods & genres"
                                else -> title
                            }
                            ElevatedCard(
                                onClick = {
                                    if (selectedGenre == target) {
                                        selectedGenre = null
                                        viewModel.selectDiscovery("Best for You")
                                    } else {
                                        selectedGenre = target
                                        if (target != "Moods & genres") viewModel.selectDiscovery(target)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(74.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (selectedGenre == target) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open $title")
                                }
                            }
                                }
                            }
                        }
                    }
                }

                if (latestReleaseTracks.isNotEmpty() && (selectedGenre == null || selectedGenre == "Latest Releases")) {
                    item(key = "explore_release_shelf") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("New releases", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                            DiscoveryTrackGrid(
                                songs = latestReleaseTracks.take(8),
                                onClick = { song -> playerViewModel.playSongs(latestReleaseTracks, song, "New releases") },
                                onLongClick = { contextMenuSong = it },
                            )
                        }
                    }
                }

                if (selectedGenre == "Latest Releases" && latestReleaseTracks.isEmpty()) {
                    item(key = "explore_release_status") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                                Text(
                                    "Loading current releases…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = searchError ?: "No current releases were returned for your region.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = { viewModel.selectDiscovery("Latest Releases") }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                // A selected mood used to fetch data successfully and then render nothing.
                // Render both the default Top songs shelf and every focused mood/genre result.
                val primaryTracks = if (selectedGenre == null) defaultExploreTracks else trendingTracks
                if (primaryTracks.isNotEmpty() &&
                    selectedGenre != "Latest Releases" &&
                    selectedGenre != "Moods & genres" &&
                    selectedGenre != "Charts") {
                    item(key = "explore_primary_${selectedGenre ?: "default"}") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (selectedGenre == null) "Recommended for you" else discoveryTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            DiscoveryTrackGrid(
                                songs = primaryTracks.take(8),
                                onClick = { song -> playerViewModel.playSongs(primaryTracks, song, if (selectedGenre == null) "Recommended for you" else discoveryTitle) },
                                onLongClick = { contextMenuSong = it },
                            )
                        }
                    }
                }

                if (selectedGenre != null &&
                    selectedGenre != "Latest Releases" &&
                    selectedGenre != "Moods & genres" &&
                    primaryTracks.isEmpty()) {
                    item(key = "explore_selected_status_$selectedGenre") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                                Text(
                                    "Loading $selectedGenre…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = searchError ?: "No $selectedGenre music was returned for your region.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = { viewModel.selectDiscovery(selectedGenre!!) }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                // Mood/genre destinations remain visible on the complete Explore feed and
                // while a mood is focused, so a failed/slow request can never leave a blank page.
                if (selectedGenre == null || selectedGenre == "Moods & genres" ||
                    selectedGenre in setOf(
                        "Workout", "Feel good", "Energize", "Relax", "Romance",
                        "Focus", "Party", "Punjabi", "Indian", "Hip-hop",
                    )) {
                    item(key = "instant_explore_header") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "Moods & genres",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Music for every moment",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "instant_explore_tiles") {
                        val instantCategories = remember {
                            listOf("Workout", "Feel good", "Energize", "Relax", "Romance", "Focus", "Party", "Punjabi", "Indian", "Hip-hop")
                        }
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            instantCategories.chunked(2).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowItems.forEach { category ->
                                        ElevatedCard(
                                            onClick = {
                                                selectedGenre = category
                                                viewModel.selectDiscovery(category)
                                            },
                                            modifier = Modifier.weight(1f).height(76.dp),
                                            shape = RoundedCornerShape(18.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            ),
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                                contentAlignment = Alignment.BottomStart,
                                            ) {
                                                Text(
                                                    category,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (trendingTracks.isNotEmpty() && selectedGenre == "Charts") {
                    item(key = "explore_chart_shelf") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = discoveryTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            DiscoveryTrackGrid(
                                songs = trendingTracks.take(10),
                                onClick = { song -> playerViewModel.playSongs(trendingTracks, song, discoveryTitle) },
                                onLongClick = { contextMenuSong = it },
                            )
                        }
                    }
                }

                if (selectedGenre == null && !isLoading && !hasVisibleDiscovery && latestReleaseTracks.isEmpty()) {
                    item(key = "explore_empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = searchError ?: "Explore could not load right now",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = { viewModel.fetchTrending() }) { Text("Retry") }
                        }
                    }
                } else if (selectedGenre == null && isLoading && !hasVisibleDiscovery && latestReleaseTracks.isEmpty()) {
                    item(key = "explore_loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
    }

    if (showAmbientRecognition) {
        AmbientRecognitionSheet(
            onDismiss = { showAmbientRecognition = false },
            playerViewModel = playerViewModel,
        )
    }

    // ── Song Context Bottom Sheet ───────────────────────────────────────
    contextMenuSong?.let { song ->
        SongContextBottomSheet(
            song = song,
            sheetState = contextMenuSheetState,
            onDismiss = { contextMenuSong = null },
            onPlayNext = {
                playerViewModel.addSongNextToQueue(song)
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(song)
            },
            onAddToPlaylist = {
                songForPlaylist = song
            },
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = {
                playerViewModel.toggleFavoriteSpecificSong(song)
            },
            onAlbum = if (
                !song.remoteAlbumBrowseId.isNullOrBlank() ||
                song.albumId > 0L ||
                song.id.startsWith("yt_") ||
                (song.album.isNotBlank() && !song.album.equals("YouTube Music", ignoreCase = true))
            ) {
                {
                    scope.launch {
                        val albumBrowseId = viewModel.resolveAlbumBrowseId(song)
                        when {
                            !albumBrowseId.isNullOrBlank() -> navController.navigateSafely(
                                Screen.AlbumDetail.createRoute(albumBrowseId)
                            )
                            song.albumId > 0L -> navController.navigateSafely(
                                Screen.AlbumDetail.createRoute(song.albumId)
                            )
                            else -> Toast.makeText(context, "Album page is unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else null,
            onArtist = {
                scope.launch {
                    val artistBrowseId = viewModel.resolveArtistBrowseId(song)
                    when {
                        !artistBrowseId.isNullOrBlank() -> navController.navigateSafely(
                            Screen.ArtistDetail.createRoute(artistBrowseId)
                        )
                        song.artistId > 0L -> navController.navigateSafely(
                            Screen.ArtistDetail.createRoute(song.artistId)
                        )
                        else -> Toast.makeText(context, "Artist page is unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // ── Playlist Selection Bottom Sheet ─────────────────────────────────
    songForPlaylist?.let { song ->
        PlaylistBottomSheet(
            songs = listOf(song),
            onDismiss = { songForPlaylist = null },
            bottomBarHeight = 0.dp,
            playerViewModel = playerViewModel
        )
    }
}

@Composable
private fun AlbumSearchCard(
    album: YouTubeAlbum,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubcomposeAsyncImage(
                model = album.thumbnailUrl,
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(12.dp)),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.padding(20.dp))
                },
                success = { SubcomposeAsyncImageContent() },
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(
                    text = album.title,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = album.artist,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(album.type, album.year?.toString()).joinToString(" • "),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtistSearchCard(
    artist: YouTubeArtist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            SubcomposeAsyncImage(
                model = artist.thumbnailUrl,
                contentDescription = artist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                },
                error = {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(42.dp)
                    )
                },
                success = { SubcomposeAsyncImageContent() },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            overflow = TextOverflow.Ellipsis
        )
        artist.subscriberCount?.takeIf { it.isNotBlank() }?.let { subscribers ->
            Text(
                text = subscribers,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiscoveryTrackGrid(
    songs: List<Song>,
    onClick: (Song) -> Unit,
    onLongClick: (Song) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        songs.chunked(2).forEachIndexed { rowIndex, rowSongs ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowSongs.forEach { song ->
                    DiscoveryTrackCard(
                        song = song,
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(song) },
                        onLongClick = { onLongClick(song) },
                    )
                }
                if (rowSongs.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiscoveryTrackCard(
    song: Song,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.pointerInput(song.id) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongClick() },
            )
        },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = song.albumArtUriString,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(13.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = song.artist,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column {
            AsyncImage(
                model = song.albumArtUriString,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
