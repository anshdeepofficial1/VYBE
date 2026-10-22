package com.theveloper.pixelplay.data.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class Song(
    val id: String,
    val title: String,
    /**
     * Legacy artist display string.
     * - With multi-artist parsing enabled by default, this typically contains only the primary artist for backward compatibility.
     * For accurate display of all artists, use the [artists] list and [displayArtist] property.
     */
    val artist: String,
    val artistId: Long, // Primary artist ID for backward compatibility
    val artists: List<ArtistRef> = emptyList(), // All artists for multi-artist support
    val album: String,
    val albumId: Long,
    val albumArtist: String? = null, // Album artist from metadata
    val path: String, // Added for direct file system access
    val contentUriString: String,
    val albumArtUriString: String?,
    val duration: Long,
    val genre: String? = null,
    val lyrics: String? = null,
    val isFavorite: Boolean = false,
    val trackNumber: Int = 0,
    val discNumber: Int? = null,
    val year: Int = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val mimeType: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val telegramFileId: Int? = null, // ID of the file in Telegram
    val telegramChatId: Long? = null, // ID of the chat where the file is located
    val neteaseId: Long? = null, // Netease Cloud Music song ID
    val gdriveFileId: String? = null, // Google Drive file ID
    val qqMusicMid: String? = null, // QQ Music song MID
    val navidromeId: String? = null, // Navidrome song ID
    val jellyfinId: String? = null, // Jellyfin item ID
    /** YouTube Music album browse ID, retained while an online song is in the player/queue. */
    val remoteAlbumBrowseId: String? = null,
    /** Exact catalog release date in epoch milliseconds; zero when the provider did not supply one. */
    val releaseDateEpochMillis: Long = 0L,
    /** True only for an official YouTube video result that can open in VYBE's video surface. */
    val isMusicVideo: Boolean = false,
) : Parcelable {
    /**
     * Returns the display string for artists.
     * If multiple artists exist (populated during sync), joins them with ", ".
     * Falls back to the raw artist field (splitting is done at sync time, not display time).
     */
    val displayArtist: String
        get() {
            val raw = if (artists.isNotEmpty()) {
                artists.sortedByDescending { it.isPrimary }.joinToString(", ") { it.name }
            } else {
                artist
            }
            // Auto-generated YouTube Music artist channels are named "<Artist> - Topic". The suffix
            // is a catalogue implementation detail, not part of anybody's name, so it never reaches
            // the UI. A plain endsWith keeps this free of per-frame regex allocation.
            return if (raw.endsWith(" - Topic", ignoreCase = true)) {
                raw.dropLast(" - Topic".length).trimEnd().ifEmpty { raw }
            } else {
                raw
            }
        }

    /**
     * Returns the primary artist from the artists list,
     * or creates one from the legacy artist field.
     */
    val primaryArtist: ArtistRef
        get() = artists.find { it.isPrimary }
            ?: artists.firstOrNull()
            ?: ArtistRef(id = artistId, name = artist, isPrimary = true)

    companion object {
        fun emptySong(): Song {
            return Song(
                id = "-1",
                title = "",
                artist = "",
                artistId = -1L,
                artists = emptyList(),
                album = "",
                albumId = -1L,
                albumArtist = null,
                path = "",
                contentUriString = "",
                albumArtUriString = null,
                duration = 0L,
                genre = null,
                lyrics = null,
                isFavorite = false,
                trackNumber = 0,
                discNumber = null,
                year = 0,
                dateAdded = 0,
                dateModified = 0,
                mimeType = "-",
                bitrate = 0,
                sampleRate = 0,
                telegramFileId = null,
                telegramChatId = null,
                neteaseId = null,
                gdriveFileId = null,
                qqMusicMid = null,
                navidromeId = null,
                jellyfinId = null
            )
        }
    }
}
