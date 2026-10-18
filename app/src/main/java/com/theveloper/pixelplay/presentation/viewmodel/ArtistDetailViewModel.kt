package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the full UI state for ArtistDetailScreen.
 *
 * [effectiveImageUrl] is the resolved image to display (custom takes priority over Deezer).
 * It is updated after artist data loads and again whenever the user changes the custom image.
 */
data class ArtistDetailUiState(
    val artist: Artist? = null,
    val songs: List<Song> = emptyList(),
    val videos: List<Song> = emptyList(),
    val albumSections: List<ArtistAlbumSection> = emptyList(),
    val effectiveImageUrl: String? = null,
    val subscriberCount: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@Immutable
data class ArtistAlbumSection(
    val albumId: Long,
    val remoteBrowseId: String? = null,
    val title: String,
    val year: Int?,
    val albumArtUriString: String?,
    val songs: List<Song>
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val onlineMusicRepository: com.theveloper.pixelplay.data.repository.OnlineMusicRepository,
    private val artistImageRepository: ArtistImageRepository,
    private val userPreferencesRepository: com.theveloper.pixelplay.data.preferences.UserPreferencesRepository,
    val themeStateHolder: ThemeStateHolder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val preferredArtists = userPreferencesRepository.preferredArtists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val blockedArtists = userPreferencesRepository.blockedArtists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFollowArtist(name: String) = viewModelScope.launch {
        val key = name.trim()
        val preferred = userPreferencesRepository.preferredArtists.first().toMutableSet()
        val existing = preferred.firstOrNull { it.equals(key, true) }
        if (existing != null) preferred.remove(existing) else preferred.add(key)
        userPreferencesRepository.updatePreferredArtists(preferred)
        if (existing == null) {
            userPreferencesRepository.updateBlockedArtists(
                userPreferencesRepository.blockedArtists.first().filterNot { it.equals(key, true) }.toSet()
            )
        }
    }

    fun toggleBlockArtist(name: String) = viewModelScope.launch {
        val key = name.trim()
        val blocked = userPreferencesRepository.blockedArtists.first().toMutableSet()
        val existing = blocked.firstOrNull { it.equals(key, true) }
        if (existing != null) blocked.remove(existing) else blocked.add(key)
        userPreferencesRepository.updateBlockedArtists(blocked)
        if (existing == null) {
            userPreferencesRepository.updatePreferredArtists(
                userPreferencesRepository.preferredArtists.first().filterNot { it.equals(key, true) }.toSet()
            )
        }
    }

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    /**
     * Pre-warmed color scheme for the current artist image.
     * This is populated synchronously (from the processor's LRU/DB cache) before [uiState]
     * marks [ArtistDetailUiState.isLoading] = false, so the screen has the correct palette
     * on its very first composition — no flash from system colors.
     *
     * Consumers should read this directly instead of calling [ThemeStateHolder.getAlbumColorSchemeFlow]
     * in order to avoid the initial-null-emission that causes the flash.
     */
    private val _artistColorScheme = MutableStateFlow<ColorSchemePair?>(null)
    val artistColorScheme: StateFlow<ColorSchemePair?> = _artistColorScheme.asStateFlow()

    init {
        savedStateHandle.getStateFlow<String?>("artistId", null)
            .onEach { idString ->
                if (idString != null) {
                    when {
                        idString.startsWith(REMOTE_ARTIST_PREFIX) ->
                            loadOnlineArtistData(idString.removePrefix(REMOTE_ARTIST_PREFIX))
                        idString.startsWith(LOOKUP_ARTIST_PREFIX) ->
                            loadOnlineArtistByName(idString.removePrefix(LOOKUP_ARTIST_PREFIX))
                        idString.startsWith(COMPOSITE_ARTIST_PREFIX) -> {
                            val parts = idString.removePrefix(COMPOSITE_ARTIST_PREFIX).split('|', limit = 2)
                            val localId = parts.firstOrNull()?.toLongOrNull()
                            val name = parts.getOrNull(1).orEmpty()
                            if (localId != null) loadArtistData(localId, name) else loadOnlineArtistByName(name)
                        }
                        idString.toLongOrNull() != null -> loadArtistData(idString.toLong())
                        else -> loadOnlineArtistData(idString)
                    }
                } else {
                    _uiState.update { it.copy(error = context.getString(R.string.artist_detail_id_not_found), isLoading = false) }
                }
            }
            .launchIn(viewModelScope)
    }

    private var currentLoadJob: Job? = null
    private var currentEnrichmentJob: Job? = null

    private fun loadOnlineArtistByName(name: String) {
        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val match = withTimeoutOrNull(ARTIST_PROFILE_TIMEOUT_MS) {
                onlineMusicRepository.searchMusicStructured(name).artists
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: onlineMusicRepository.searchMusicStructured(name).artists.firstOrNull()
            }
            if (match != null) {
                loadOnlineArtistData(match.browseId)
            } else {
                _uiState.update {
                    it.copy(error = context.getString(R.string.artist_detail_not_found), isLoading = false)
                }
            }
        }
    }

    private fun loadOnlineArtistData(browseId: String) {
        currentLoadJob?.cancel()
        currentEnrichmentJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val directProfile = withTimeoutOrNull(ARTIST_PROFILE_TIMEOUT_MS) {
                    onlineMusicRepository.getArtistProfile(browseId)
                }
                val matchedArtist = if (directProfile == null) {
                    withTimeoutOrNull(ARTIST_PROFILE_TIMEOUT_MS) {
                        onlineMusicRepository.searchMusicStructured(browseId).artists
                            .firstOrNull { it.name.equals(browseId, ignoreCase = true) }
                    }
                } else null
                val resolvedBrowseId = matchedArtist?.browseId ?: browseId
                val profile = directProfile ?: withTimeoutOrNull(ARTIST_PROFILE_TIMEOUT_MS) {
                    matchedArtist?.let { onlineMusicRepository.getArtistProfile(it.browseId) }
                }
                if (profile != null) {
                    val artist = Artist(
                        id = resolvedBrowseId.hashCode().toLong(),
                        name = profile.name,
                        songCount = profile.topSongs.size,
                        imageUrl = profile.avatarUrl ?: profile.bannerUrl,
                        customImageUri = null,
                        remoteBrowseId = resolvedBrowseId,
                    )
                    val effectiveUrl = profile.bannerUrl ?: profile.avatarUrl
                    _uiState.value = ArtistDetailUiState(
                        artist = artist,
                        songs = profile.topSongs,
                        videos = profile.videos,
                        albumSections = emptyList(),
                        effectiveImageUrl = effectiveUrl,
                        subscriberCount = profile.subscribers,
                        isLoading = false,
                    )
                    warmArtistPalette(effectiveUrl)
                    currentEnrichmentJob = launch {
                        loadOnlineDiscography(resolvedBrowseId, profile, emptyList())
                    }
                } else {
                    _uiState.value = ArtistDetailUiState(
                        artist = Artist(
                            id = browseId.hashCode().toLong(),
                            name = "Artist",
                            songCount = 0,
                            remoteBrowseId = browseId,
                        ),
                        isLoading = false,
                        error = "Artist profile is temporarily unavailable. Please try again.",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to load artist", isLoading = false) }
            }
        }
    }

    private fun loadArtistData(id: Long, fallbackName: String = "") {
        currentLoadJob?.cancel()
        currentEnrichmentJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            Log.d("ArtistDebug", "loadArtistData: id=$id")
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val artistDetailsFlow = musicRepository.getArtistById(id)
                val artistSongsFlow = musicRepository.getSongsForArtist(id)

                combine(artistDetailsFlow, artistSongsFlow) { artist, songs ->
                    Log.d("ArtistDebug", "loadArtistData: id=$id found=${artist != null} songs=${songs.size}")
                    artist to songs
                }
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                error = context.getString(R.string.artist_error_loading_artist, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        }
                    }
                    .collect { (artist, songs) ->
                        if (artist == null) {
                            if (fallbackName.isNotBlank()) loadOnlineArtistByName(fallbackName)
                            else _uiState.update {
                                it.copy(error = context.getString(R.string.artist_detail_not_found), isLoading = false)
                            }
                            return@collect
                        }

                        val albumSections = buildAlbumSections(songs)
                        val orderedSongs = albumSections.flatMap { it.songs }

                        // 1) Resolve effective image URL (custom > Deezer, may fetch from API)
                        val effectiveUrl = artist.effectiveImageUrl

                        // 2) Pre-warm the color scheme BEFORE emitting isLoading = false.
                        //    getOrGenerateColorScheme checks the in-memory LRU first (≈0 ms if cached),
                        //    then the DB cache (fast), and only generates from scratch ~on first visit.
                        //    Either way, the scheme is ready before the screen first renders.
                        _artistColorScheme.value = null

                        // 3) Atomically publish state + pre-warmed color scheme.
                        //    Both flows update before the Compose frame runs, so no intermediate null frame.
                        _uiState.value = ArtistDetailUiState(
                            artist = artist,
                            songs = orderedSongs,
                            albumSections = albumSections,
                            effectiveImageUrl = effectiveUrl,
                            isLoading = false
                        )
                        currentEnrichmentJob?.cancel()
                        currentEnrichmentJob = launch { enrichLocalArtist(artist, orderedSongs) }
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.artist_error_loading_artist, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    private suspend fun enrichLocalArtist(artist: Artist, localSongs: List<Song>) = supervisorScope {
        val imageDeferred = async {
            withTimeoutOrNull(IMAGE_LOOKUP_TIMEOUT_MS) {
                artistImageRepository.getEffectiveArtistImageUrl(artist.id, artist.name)
            }
        }
        val profileDeferred = async {
            val remoteId = artist.remoteBrowseId?.takeIf(String::isNotBlank)
                ?: withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    onlineMusicRepository.searchMusicStructured(artist.name).artists
                        .firstOrNull { it.name.equals(artist.name, ignoreCase = true) }
                        ?.browseId
                }
            remoteId?.let { browseId ->
                withTimeoutOrNull(ARTIST_PROFILE_TIMEOUT_MS) {
                    onlineMusicRepository.getArtistProfile(browseId)
                }?.let { browseId to it }
            }
        }

        val resolvedImage = imageDeferred.await()
        if (!resolvedImage.isNullOrBlank()) {
            _uiState.update { state ->
                state.copy(
                    effectiveImageUrl = resolvedImage,
                    artist = state.artist?.copy(imageUrl = resolvedImage),
                )
            }
            warmArtistPalette(resolvedImage)
        }

        profileDeferred.await()?.let { (browseId, profile) ->
            val profileImage = profile.bannerUrl ?: profile.avatarUrl ?: resolvedImage
            val baseSongs = (localSongs + profile.topSongs)
                .filterNot { it.title.contains("jukebox", ignoreCase = true) || it.title.contains("mashup", ignoreCase = true) || it.duration > 900_000L }
                .distinctBy { it.id }
            _uiState.update { state ->
                state.copy(
                    artist = state.artist?.copy(
                        name = profile.name.takeUnless { it == "Artist" } ?: artist.name,
                        songCount = baseSongs.size,
                        imageUrl = profile.avatarUrl ?: resolvedImage,
                        remoteBrowseId = browseId,
                    ),
                    songs = baseSongs,
                    albumSections = buildAlbumSections(localSongs).filter { it.songs.size > 1 },
                    effectiveImageUrl = profileImage,
                    error = null,
                )
            }
            warmArtistPalette(profileImage)
            loadOnlineDiscography(browseId, profile, localSongs)
        }
    }

    private suspend fun loadOnlineDiscography(
        browseId: String,
        profile: com.theveloper.pixelplay.data.network.ytmusic.YouTubeArtistProfile,
        localSongs: List<Song>,
    ) {
        // Album tab represents real multi-track albums only. Singles remain in Songs and must
        // not be fabricated into one-track album cards.
        val releases = profile.albums.distinctBy { it.browseId }
        val resolvedAlbums = mutableListOf<com.theveloper.pixelplay.data.network.ytmusic.YouTubeAlbumDetails>()
        releases.chunked(RELEASE_BATCH_SIZE).forEach { batch ->
            resolvedAlbums += supervisorScope {
                batch.map { release ->
                    async {
                        withTimeoutOrNull(ALBUM_DETAILS_TIMEOUT_MS) {
                            onlineMusicRepository.getAlbumDetails(release.browseId)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val remoteSongs = (profile.topSongs + resolvedAlbums.flatMap { it.tracks }).distinctBy { it.id }
            val completeSongs = (localSongs + remoteSongs).distinctBy { it.id }
            val releaseSections = resolvedAlbums
                .sortedWith(
                    compareByDescending<com.theveloper.pixelplay.data.network.ytmusic.YouTubeAlbumDetails> { it.year ?: 0 }
                        .thenBy { it.title.lowercase() }
                )
                .filter { it.tracks.size > 1 }
                .map { album ->
                    val songsWithArtwork = album.tracks.map { track ->
                        if (track.albumArtUriString.isNullOrBlank()) {
                            track.copy(albumArtUriString = album.coverUrl)
                        } else track
                    }
                    ArtistAlbumSection(
                        albumId = album.browseId.hashCode().toLong(),
                        remoteBrowseId = album.browseId,
                        title = album.title,
                        year = album.year,
                        albumArtUriString = album.coverUrl,
                        songs = songsWithArtwork,
                    )
                }
            _uiState.update { state ->
                state.copy(
                    artist = state.artist?.copy(songCount = completeSongs.size, remoteBrowseId = browseId),
                    songs = completeSongs,
                    videos = profile.videos,
                    albumSections = mergeSections(
                        buildAlbumSections(localSongs).filter { it.songs.size > 1 },
                        releaseSections,
                    ),
                )
            }
        }
    }

    private suspend fun warmArtistPalette(url: String?) {
        if (url.isNullOrBlank()) return
        _artistColorScheme.value = runCatching {
            withTimeoutOrNull(IMAGE_LOOKUP_TIMEOUT_MS) {
                themeStateHolder.getOrGenerateColorScheme(url)
            }
        }.getOrNull()
    }

    /**
     * Called from the UI when the user selects a custom image from the system photo picker.
     * Copies the image to internal storage, persists the path to DB, and triggers palette regeneration.
     */
    fun setCustomImage(sourceUri: Uri) {
        val artistId = _uiState.value.artist?.id ?: return
        viewModelScope.launch {
            try {
                val internalPath = artistImageRepository.setCustomArtistImage(context, artistId, sourceUri)
                if (!internalPath.isNullOrBlank()) {
                    val oldEffectiveUrl = _uiState.value.effectiveImageUrl

                    // Regenerate palette from the new image url — invalidate old and warm-up new
                    if (!oldEffectiveUrl.isNullOrBlank() && oldEffectiveUrl != internalPath) {
                        themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                    }
                    val newScheme = try {
                        themeStateHolder.forceRegenerateColorScheme(internalPath)
                        themeStateHolder.getOrGenerateColorScheme(internalPath)
                    } catch (e: Exception) {
                        Log.w("ArtistDebug", "Failed to regenerate color scheme for custom image: ${e.message}")
                        null
                    }

                    _artistColorScheme.value = newScheme
                    _uiState.update { state ->
                        // Cache-busting: add timestamp to internalPath to force Coil to reload
                        val effectiveUrlWithBust = "$internalPath?t=${System.currentTimeMillis()}"
                        state.copy(
                            effectiveImageUrl = effectiveUrlWithBust,
                            artist = state.artist?.copy(customImageUri = internalPath)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ArtistDebug", "Failed to set custom image: ${e.message}")
            }
        }
    }

    /**
     * Called when the user wants to revert to the Deezer-sourced image.
     */
    fun clearCustomImage() {
        val artist = _uiState.value.artist ?: return
        viewModelScope.launch {
            try {
                val oldEffectiveUrl = _uiState.value.effectiveImageUrl
                artistImageRepository.clearCustomArtistImage(context, artist.id)

                // Fall back to Deezer URL
                val deezerUrl = artistImageRepository.getArtistImageUrl(artist.name, artist.id)
                val newEffectiveUrl = deezerUrl.takeIf { !it.isNullOrBlank() }

                // Invalidate old custom image palette
                if (!oldEffectiveUrl.isNullOrBlank()) {
                    themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                }

                val newScheme = if (!newEffectiveUrl.isNullOrBlank()) {
                    try {
                        themeStateHolder.getOrGenerateColorScheme(newEffectiveUrl)
                    } catch (e: Exception) {
                        Log.w("ArtistDebug", "Failed to regenerate palette after clear: ${e.message}")
                        null
                    }
                } else null

                _artistColorScheme.value = newScheme
                _uiState.update { state ->
                    state.copy(
                        effectiveImageUrl = newEffectiveUrl,
                        artist = state.artist?.copy(customImageUri = null, imageUrl = deezerUrl)
                    )
                }

            } catch (e: Exception) {
                Log.e("ArtistDebug", "Failed to clear custom image: ${e.message}")
            }
        }
    }

    fun removeSongFromAlbumSection(songId: String) {
        _uiState.update { currentState ->
            val updatedAlbumSections = currentState.albumSections.map { section ->
                val updatedSongs = section.songs.filterNot { it.id == songId }
                section.copy(songs = updatedSongs)
            }.filter { it.songs.isNotEmpty() }

            currentState.copy(
                albumSections = updatedAlbumSections,
                songs = currentState.songs.filterNot { it.id == songId }
            )
        }
    }

    fun retry() {
        val artist = _uiState.value.artist ?: return
        artist.remoteBrowseId?.takeIf(String::isNotBlank)?.let(::loadOnlineArtistData)
            ?: loadArtistData(artist.id)
    }
}

private val songDisplayComparator = compareBy<Song> { it.discNumber ?: 1 }
    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
    .thenBy { it.title.lowercase() }

private fun buildAlbumSections(songs: List<Song>): List<ArtistAlbumSection> {
    if (songs.isEmpty()) return emptyList()

    val sections = songs
        .groupBy { it.albumId to it.album }
        .map { (key, albumSongs) ->
            val sortedSongs = albumSongs.sortedWith(songDisplayComparator)
            val albumYear = albumSongs.mapNotNull { song -> song.year.takeIf { it > 0 } }.maxOrNull()
            val albumArtUri = albumSongs.firstNotNullOfOrNull { it.albumArtUriString }
            ArtistAlbumSection(
                albumId = key.first,
                remoteBrowseId = albumSongs.firstNotNullOfOrNull { it.remoteAlbumBrowseId },
                title = (key.second.takeIf { it.isNotBlank() } ?: "Unknown Album"),
                year = albumYear,
                albumArtUriString = albumArtUri,
                songs = sortedSongs
            )
        }

    val (withYear, withoutYear) = sections.partition { it.year != null }
    val withYearSorted = withYear.sortedWith(
        compareByDescending<ArtistAlbumSection> { it.year ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase() }
    )
    val withoutYearSorted = withoutYear.sortedBy { it.title.lowercase() }

    return withYearSorted + withoutYearSorted
}

private fun mergeSections(vararg groups: List<ArtistAlbumSection>): List<ArtistAlbumSection> =
    groups.asSequence().flatten()
        .filter { it.songs.isNotEmpty() }
        .distinctBy { section ->
            section.title.trim().lowercase() to section.songs.map { it.id }.sorted()
        }
        .toList()

private const val SEARCH_TIMEOUT_MS = 8_000L
private const val IMAGE_LOOKUP_TIMEOUT_MS = 6_000L
private const val ARTIST_PROFILE_TIMEOUT_MS = 20_000L
private const val ALBUM_DETAILS_TIMEOUT_MS = 15_000L
private const val RELEASE_BATCH_SIZE = 4
private const val REMOTE_ARTIST_PREFIX = "remote_artist|"
private const val LOOKUP_ARTIST_PREFIX = "lookup_artist|"
private const val COMPOSITE_ARTIST_PREFIX = "artist_meta|"
