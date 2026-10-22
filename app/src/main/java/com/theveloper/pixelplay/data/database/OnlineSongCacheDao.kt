package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** Metadata for playable online tracks referenced by persisted local playlists. */
@Entity(tableName = "online_song_cache")
data class OnlineSongCacheEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val duration: Long,
    val path: String,
    val contentUri: String,
    val mimeType: String?,
    /**
     * JSON of the linked [[name, remoteBrowseId]] pairs. Restored tracks keep the credits
     * the provider attached, so an artist tap opens the real YouTube Music profile instead
     * of second-guessing the name through a search (which can land on a namesake artist).
     */
    val artistsJson: String? = null,
    /** Provider browse id of the release, so restored tracks still open their album page. */
    val remoteAlbumBrowseId: String? = null,
)

private const val ARTIST_JSON_NAME = "n"
private const val ARTIST_JSON_BROWSE = "b"

fun parseCachedArtists(artistsJson: String?): List<ArtistRef> {
    if (artistsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(artistsJson)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val name = obj.optString(ARTIST_JSON_NAME)
                if (name.isBlank()) continue
                add(
                    ArtistRef(
                        id = obj.optString(ARTIST_JSON_BROWSE).ifBlank { name }.lowercase().hashCode().toLong(),
                        name = name,
                        isPrimary = index == 0,
                        remoteBrowseId = obj.optString(ARTIST_JSON_BROWSE).takeIf(String::isNotBlank),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

fun serializeCachedArtists(artists: List<ArtistRef>): String? {
    if (artists.isEmpty()) return null
    return runCatching {
        JSONArray().apply {
            artists.forEach { artist ->
                put(
                    JSONObject().apply {
                        put(ARTIST_JSON_NAME, artist.name)
                        put(ARTIST_JSON_BROWSE, artist.remoteBrowseId ?: "")
                    }
                )
            }
        }
    }.getOrNull()?.toString()
}

@Dao
interface OnlineSongCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<OnlineSongCacheEntity>)

    @Query("SELECT * FROM online_song_cache")
    fun observeAll(): Flow<List<OnlineSongCacheEntity>>

    @Query("SELECT COUNT(*) FROM online_song_cache")
    suspend fun getCount(): Int

    /** Drops every cached online row so stale metadata cannot outlive a catalogue-rule change. */
    @Query("DELETE FROM online_song_cache")
    suspend fun clear()
}

fun OnlineSongCacheEntity.toSong(): Song {
    val restoredArtists = parseCachedArtists(artistsJson)
    return Song(
        id = id,
        title = title,
        artist = artist,
        artistId = 0L,
        artists = restoredArtists,
        album = album,
        albumId = 0L,
        path = path,
        contentUriString = contentUri,
        albumArtUriString = albumArtUrl,
        duration = duration,
        mimeType = mimeType,
        bitrate = null,
        sampleRate = null,
        remoteAlbumBrowseId = remoteAlbumBrowseId,
    )
}

fun Song.toOnlineSongCacheEntity(): OnlineSongCacheEntity = OnlineSongCacheEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtUrl = albumArtUriString,
    duration = duration,
    path = path,
    contentUri = contentUriString,
    mimeType = mimeType,
    artistsJson = serializeCachedArtists(artists),
    remoteAlbumBrowseId = remoteAlbumBrowseId,
)
