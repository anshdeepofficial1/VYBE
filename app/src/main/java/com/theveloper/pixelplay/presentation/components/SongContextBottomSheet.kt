package com.theveloper.pixelplay.presentation.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.sharing.VybeSongShareLink

sealed interface ActionIcon {
    data class Vector(val vector: ImageVector) : ActionIcon
    data class DrawableRes(val resId: Int) : ActionIcon
}

data class SongContextAction(
    val icon: ActionIcon,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongContextBottomSheet(
    song: Song,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /** null = song is streaming-only, button is grayed out */
    onDownload: (() -> Unit)? = null,
    onArtist: (() -> Unit)? = null,
    onAlbum: (() -> Unit)? = null,
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val favoriteAction = onToggleFavorite?.let { toggleFavorite ->
        SongContextAction(
            icon = ActionIcon.Vector(
                if (isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder
            ),
            label = if (isFavorite == true) "Remove from Liked Songs" else "Add to Liked Songs",
            onClick = {
                toggleFavorite()
                onDismiss()
            }
        )
    }
    val actions = listOfNotNull(
        favoriteAction,
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_download_24),
            label = if (song.path.startsWith("http") || song.contentUriString.startsWith("http") || song.id.startsWith("yt_") || song.id.startsWith("audius_")) "Download" else "Downloaded",
            enabled = true,
            onClick = {
                if (song.path.startsWith("http") || song.contentUriString.startsWith("http") || song.id.startsWith("yt_") || song.id.startsWith("audius_")) {
                    com.theveloper.pixelplay.data.network.ytmusic.YouTubeDownloadManager
                        .fromContext(context)
                        .enqueueDownload(song)
                } else {
                    Toast.makeText(context, "Song is already on this device", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }
        ),
        SongContextAction(
            icon = ActionIcon.Vector(Icons.Default.Share),
            label = "Share",
            onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "${song.title} on VYBE")
                    putExtra(Intent.EXTRA_TEXT, VybeSongShareLink.shareText(song))
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                onDismiss()
            }
        ),
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_playlist_play_24),
            label = "Play Next",
            onClick = { onPlayNext(); onDismiss() }
        ),
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_queue_music_24),
            label = "Add to Queue",
            onClick = { onAddToQueue(); onDismiss() }
        ),
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_playlist_add_24),
            label = "Add to Playlist",
            onClick = { onAddToPlaylist(); onDismiss() }
        ),
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_artist_24),
            label = "Go to Artist",
            enabled = onArtist != null,
            onClick = {
                if (onArtist != null) {
                    onArtist()
                    onDismiss()
                }
            }
        ),
        SongContextAction(
            icon = ActionIcon.DrawableRes(R.drawable.rounded_album_24),
            label = if (onAlbum != null && song.album.isNotBlank() && !song.album.equals("YouTube Music", ignoreCase = true) && !song.album.equals("Online Track", ignoreCase = true)) "Album: ${song.album}" else "Go to Album",
            // The player resolves the album even when the song only carries a placeholder
            // name (it searches the online catalogue and, when nothing matches, shows the
            // "album not found" notice), so the entry stays tappable whenever a target
            // handler exists at all.
            enabled = onAlbum != null,
            onClick = {
                if (onAlbum != null) {
                    onAlbum()
                    onDismiss()
                }
            }
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Song header
            ListItem(
                headlineContent = {
                    Text(
                        text = song.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    AsyncImage(
                        model = song.albumArtUriString,
                        contentDescription = null,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(4.dp))

            // Action rows
            actions.forEach { action ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (action.enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    leadingContent = {
                        when (val icon = action.icon) {
                            is ActionIcon.Vector -> Icon(
                                imageVector = icon.vector,
                                contentDescription = action.label,
                                tint = if (action.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            is ActionIcon.DrawableRes -> Icon(
                                painter = painterResource(icon.resId),
                                contentDescription = action.label,
                                tint = if (action.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    },
                    modifier = Modifier.clickable(enabled = action.enabled) { action.onClick() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
