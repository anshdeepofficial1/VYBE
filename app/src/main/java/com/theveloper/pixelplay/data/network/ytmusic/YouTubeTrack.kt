package com.theveloper.pixelplay.data.network.ytmusic

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.SongMetadataCleaner
import kotlinx.parcelize.Parcelize

enum class YouTubeMusicEntityType { SONG, MUSIC_VIDEO }

@Immutable
@Parcelize
data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "YouTube Music",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    /** Playlist-scoped identifier required by YouTube Music for removal/reordering. */
    val setVideoId: String? = null,
    val resultType: YouTubeMusicEntityType = YouTubeMusicEntityType.SONG,
    val isOfficial: Boolean = false,
    val linkedArtists: List<YouTubeArtist> = emptyList(),
    val albumBrowseId: String? = null,
    val releaseDateEpochMillis: Long = 0L,
) : Parcelable {
    fun toSong(streamUrl: String? = null): Song {
        val reliableArtist = linkedArtists.joinToString(", ") { it.name }
            .takeIf(String::isNotBlank)
            ?: artist.takeUnless { it.isBlank() || it.equals("YouTube Music", ignoreCase = true) }
            ?: "Unknown Artist"
        val cleaned = SongMetadataCleaner.clean(title, reliableArtist)
        val finalStream = streamUrl ?: "yt_$videoId"
        return Song(
            id = "yt_$videoId",
            title = cleaned.title,
            artist = cleaned.artist,
            artistId = 0L,
            artists = linkedArtists.mapIndexed { index, linked ->
                ArtistRef(
                    id = linked.browseId.hashCode().toLong(),
                    name = linked.name,
                    isPrimary = index == 0,
                    remoteBrowseId = linked.browseId,
                )
            },
            album = album.ifBlank { "YouTube Music" },
            albumId = 0L,
            path = finalStream,
            contentUriString = "yt://$videoId",
            albumArtUriString = thumbnailUrl,
            duration = if (durationSeconds > 0) durationSeconds * 1000L else 0L,
            mimeType = "audio/mp4",
            bitrate = 256,
            sampleRate = 44100,
            remoteAlbumBrowseId = albumBrowseId,
            releaseDateEpochMillis = releaseDateEpochMillis,
            isMusicVideo = resultType == YouTubeMusicEntityType.MUSIC_VIDEO,
        )
    }
}

@Immutable
@Parcelize
data class YouTubeAlbum(
    val browseId: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val trackCount: Int = 0,
    val type: String = "Album", // Album, Single, EP
    val thumbnailUrl: String? = null
) : Parcelable {
    fun toAlbum(): Album {
        return Album(
            id = browseId.hashCode().toLong(),
            title = title,
            artist = artist,
            year = year ?: 0,
            dateAdded = System.currentTimeMillis(),
            albumArtUriString = thumbnailUrl,
            songCount = trackCount,
            albumArtist = artist,
            remoteBrowseId = browseId,
        )
    }
}

@Immutable
@Parcelize
data class YouTubeArtist(
    val browseId: String,
    val name: String,
    val subscriberCount: String? = null,
    val thumbnailUrl: String? = null
) : Parcelable {
    fun toArtist(): Artist {
        return Artist(
            id = browseId.hashCode().toLong(),
            name = name,
            songCount = 0,
            imageUrl = thumbnailUrl,
            customImageUri = null,
            remoteBrowseId = browseId,
        )
    }
}

@Immutable
data class YouTubeSearchResult(
    val songs: List<Song> = emptyList(),
    val albums: List<YouTubeAlbum> = emptyList(),
    val artists: List<YouTubeArtist> = emptyList(),
    val videos: List<Song> = emptyList(),
    /**
     * YouTube Music's `musicCardShelfRenderer`, the large hero card above the result list.
     * It is usually the exact entity the query names (the artist, album or song), which is why
     * showing "the first song instead" drifted from the website.
     */
    val topResult: YouTubeTopResult? = null
)

/**
 * The hero card YouTube Music places above search results.
 *
 * @param typeLabel provider's own type word, e.g. "Artist", "Song", "Album", "Playlist".
 * @param subtitle provider's descriptor, e.g. "Artist • 167M monthly audience".
 * @param seedSongs the tracks the card renders inline (up to three). YouTube Music shows these
 * inside the hero card instead of repeating them in the list underneath, so the UI has to know
 * which ones they are or a song ends up displayed twice.
 */
@Immutable
data class YouTubeTopResult(
    val typeLabel: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val browseId: String = "",
    val pageType: String = "",
    val seedSongs: List<Song> = emptyList(),
) {
    val seedSong: Song? get() = seedSongs.firstOrNull()

    val seedSongIds: List<String> get() = seedSongs.mapNotNull { it.id.takeIf(String::isNotBlank) }

    /** True when the card points at a channel/artist page rather than an album. */
    val representsArtist: Boolean
        get() = pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC")

    /** True when the card points at an album page. */
    val representsAlbum: Boolean
        get() = pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE")
}

@Immutable
data class YouTubeArtistProfile(
    val browseId: String,
    val name: String,
    val bannerUrl: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val topSongs: List<Song> = emptyList(),
    val videos: List<Song> = emptyList(),
    val albums: List<YouTubeAlbum> = emptyList(),
    val singles: List<YouTubeAlbum> = emptyList(),
    val relatedArtists: List<YouTubeArtist> = emptyList()
)

@Immutable
data class YouTubeAlbumDetails(
    val browseId: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val trackCount: Int = 0,
    val coverUrl: String? = null,
    val albumType: String = "Album",
    val description: String? = null,
    val tracks: List<Song> = emptyList(),
    val releaseDateEpochMillis: Long = 0L,
)

/** A provider-authored YouTube Music Home shelf, kept in provider order. */
@Immutable
data class YouTubeHomeShelf(
    val title: String,
    val songs: List<Song>,
    val collections: List<YouTubeHomeCollection> = emptyList(),
)

@Immutable
data class YouTubeHomeCollection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val pageType: String = "",
)
