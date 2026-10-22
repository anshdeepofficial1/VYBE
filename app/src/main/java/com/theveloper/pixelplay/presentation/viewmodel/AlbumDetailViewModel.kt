package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository // Importar MusicRepository
import com.theveloper.pixelplay.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val onlineMusicRepository: com.theveloper.pixelplay.data.repository.OnlineMusicRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        val rawAlbumId: String? = savedStateHandle.get("albumId")
        val albumIdString: String? = rawAlbumId?.let { android.net.Uri.decode(it) }?.trim()
        if (!albumIdString.isNullOrBlank()) {
            when {
                albumIdString.startsWith(REMOTE_ALBUM_PREFIX) ->
                    loadOnlineAlbumData(albumIdString.removePrefix(REMOTE_ALBUM_PREFIX))
                albumIdString.startsWith(LOOKUP_ALBUM_PREFIX) -> {
                    val parts = albumIdString.removePrefix(LOOKUP_ALBUM_PREFIX).split('|', limit = 2)
                    loadOnlineAlbumByMetadata(parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty())
                }
                albumIdString.startsWith(COMPOSITE_ALBUM_PREFIX) -> {
                    val parts = albumIdString.removePrefix(COMPOSITE_ALBUM_PREFIX).split('|')
                    val id = parts.getOrNull(0)?.toLongOrNull()
                    val title = parts.getOrNull(1).orEmpty()
                    val artist = parts.getOrNull(2).orEmpty()
                    if (id != null) {
                        loadAlbumData(id, fallbackTitle = title, fallbackArtist = artist)
                    } else {
                        loadOnlineAlbumByMetadata(title, artist)
                    }
                }
                albumIdString.toLongOrNull() != null -> loadAlbumData(albumIdString.toLong())
                else -> loadOnlineAlbumData(albumIdString)
            }
        } else {
            _uiState.update { it.copy(error = context.getString(R.string.album_detail_id_not_found), isLoading = false) }
        }
    }

    private fun loadOnlineAlbumByMetadata(title: String, artist: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val match = runCatching {
                val wantedTitle = normalize(title)
                val wantedArtist = normalize(artist)
                val candidates = (
                    onlineMusicRepository.searchMusicStructured("$title $artist").albums +
                        onlineMusicRepository.searchMusicStructured(title).albums
                    ).distinctBy { it.browseId }
                candidates.maxByOrNull { album ->
                    val candidateTitle = normalize(album.title)
                    val candidateArtist = normalize(album.artist)
                    (if (candidateTitle == wantedTitle) 100 else if (candidateTitle.contains(wantedTitle)) 40 else 0) +
                        (if (wantedArtist.isNotBlank() &&
                            (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist))) 60 else 0)
                }?.takeIf { album ->
                    val candidateTitle = normalize(album.title)
                    candidateTitle == wantedTitle || candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle)
                }
            }.getOrNull()
            if (match != null) {
                loadOnlineAlbumData(match.browseId)
            } else {
                _uiState.update { it.copy(error = context.getString(R.string.album_detail_not_found), isLoading = false) }
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun loadOnlineAlbumData(browseId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val directDetails = runCatching { onlineMusicRepository.getAlbumDetails(browseId) }.getOrNull()
                val isRawId = browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_")
                val matchedAlbum = if (directDetails == null && !isRawId) {
                    onlineMusicRepository.searchMusicStructured(browseId).albums
                        .firstOrNull { it.title.equals(browseId, ignoreCase = true) }
                } else null
                val resolvedBrowseId = matchedAlbum?.browseId ?: browseId
                val details = directDetails
                    ?: matchedAlbum?.let { onlineMusicRepository.getAlbumDetails(it.browseId) }
                if (details != null) {
                    val resolvedCover = details.coverUrl
                        ?: details.tracks.firstNotNullOfOrNull { it.albumArtUriString?.takeIf(String::isNotBlank) }
                    val resolvedTitle = details.title.takeUnless { it.equals("Album", ignoreCase = true) }
                        ?: details.tracks.firstNotNullOfOrNull { track ->
                            track.album.takeUnless { it.isBlank() || it.equals("YouTube Music", ignoreCase = true) }
                        }
                        ?: details.title
                    val resolvedArtist = details.artist.takeUnless { it.equals("Various Artists", ignoreCase = true) }
                        ?: details.tracks.firstOrNull()?.artist
                        ?: details.artist
                    val resolvedTracks = details.tracks.map { track ->
                        track.copy(
                            album = resolvedTitle,
                            albumArtUriString = track.albumArtUriString?.takeIf(String::isNotBlank) ?: resolvedCover,
                        )
                    }
                    val album = Album(
                        id = resolvedBrowseId.hashCode().toLong(),
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        year = details.year ?: 0,
                        dateAdded = System.currentTimeMillis(),
                        albumArtUriString = resolvedCover,
                        songCount = resolvedTracks.size,
                        albumArtist = resolvedArtist,
                        remoteBrowseId = resolvedBrowseId,
                    )
                    _uiState.value = AlbumDetailUiState(
                        album = album,
                        songs = resolvedTracks,
                        isLoading = false
                    )
                } else {
                    _uiState.update { it.copy(error = context.getString(R.string.album_detail_not_found), isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Failed to load album", isLoading = false) }
            }
        }
    }

    private fun loadAlbumData(id: Long, fallbackTitle: String = "", fallbackArtist: String = "") {
        // Mutable so a local album row that carries no tracks can still be resolved online by its
        // own title/artist. Without this, a stale albums row renders an album page with zero songs.
        var onlineFallbackTitle = fallbackTitle
        var onlineFallbackArtist = fallbackArtist
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val albumDetailsFlow = musicRepository.getAlbumById(id)
                val albumSongsFlow = musicRepository.getSongsForAlbum(id)

                combine(albumDetailsFlow, albumSongsFlow) { album, songs ->
                    val resolvedAlbum = album ?: songs.firstOrNull()?.let { firstSong ->
                        val title = firstSong.album.takeUnless {
                            it.isBlank() || it.equals("YouTube Music", ignoreCase = true)
                        } ?: firstSong.title
                        Album(
                            id = id,
                            title = title,
                            artist = firstSong.albumArtist?.takeIf(String::isNotBlank) ?: firstSong.artist,
                            year = firstSong.year,
                            dateAdded = songs.maxOfOrNull { it.dateAdded } ?: firstSong.dateAdded,
                            albumArtUriString = songs.firstNotNullOfOrNull {
                                it.albumArtUriString?.takeIf(String::isNotBlank)
                            },
                            songCount = songs.size,
                            albumArtist = firstSong.albumArtist?.takeIf(String::isNotBlank) ?: firstSong.artist,
                            remoteBrowseId = firstSong.remoteAlbumBrowseId,
                        )
                    }
                    if (onlineFallbackTitle.isBlank() && resolvedAlbum != null) {
                        onlineFallbackTitle = resolvedAlbum.title
                        onlineFallbackArtist = resolvedAlbum.artist
                    }
                    if (resolvedAlbum != null && songs.isNotEmpty()) {
                        AlbumDetailUiState(
                            album = resolvedAlbum,
                            songs = songs.sortedWith(
                                compareBy<Song> { it.discNumber ?: 1 }
                                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                                    .thenBy { it.title.lowercase() }
                            ),
                            isLoading = false
                        )
                    } else if (onlineFallbackTitle.isNotBlank()) {
                        // No local tracks for this album: resolve the real release online rather
                        // than showing an album page with an empty track list.
                        null
                    } else {
                        AlbumDetailUiState(
                            error = context.getString(R.string.album_detail_not_found),
                            isLoading = false
                        )
                    }
                }
                    .catch { e ->
                        emit(
                            AlbumDetailUiState(
                                error = context.getString(R.string.album_detail_error_loading_album, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        )
                    }
                    .collect { newState ->
                        if (newState == null && onlineFallbackTitle.isNotBlank()) {
                            loadOnlineAlbumByMetadata(onlineFallbackTitle, onlineFallbackArtist)
                        } else if (newState != null) {
                            _uiState.value = newState
                        }
                    }

            } catch (e: Exception) {
                if (onlineFallbackTitle.isNotBlank()) {
                    loadOnlineAlbumByMetadata(onlineFallbackTitle, onlineFallbackArtist)
                } else {
                    _uiState.update {
                        it.copy(
                            error = context.getString(R.string.album_detail_error_loading_album, e.localizedMessage ?: ""),
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    fun update(songs: List<Song>) {
        _uiState.update {
            it.copy(
                isLoading = false,
                songs = songs
            )
        }
    }

    private companion object {
        const val REMOTE_ALBUM_PREFIX = "remote_album|"
        const val LOOKUP_ALBUM_PREFIX = "lookup_album|"
        const val COMPOSITE_ALBUM_PREFIX = "album_meta|"
    }
}
