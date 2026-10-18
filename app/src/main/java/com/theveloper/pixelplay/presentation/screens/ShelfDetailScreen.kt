package com.theveloper.pixelplay.presentation.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ShelfDetailSnapshot(val title: String, val songs: List<Song>)

object ShelfDetailStore {
    private val snapshots = ConcurrentHashMap<String, ShelfDetailSnapshot>()

    fun register(title: String, songs: List<Song>): String {
        val key = UUID.randomUUID().toString()
        snapshots[key] = ShelfDetailSnapshot(title, songs.distinctBy { it.id })
        return key
    }

    fun get(key: String): ShelfDetailSnapshot? = snapshots[key]
}

fun openShelfDetail(
    navController: NavController,
    title: String,
    songs: List<Song>,
) {
    val key = ShelfDetailStore.register(title, songs)
    navController.navigate("shelf_detail/${Uri.encode(key)}")
}

@Composable
fun ShelfDetailScreen(
    navController: NavHostController,
    shelfKey: String,
    playerViewModel: PlayerViewModel,
) {
    val snapshot = ShelfDetailStore.get(shelfKey)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(snapshot?.title ?: "Music") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (snapshot == null || snapshot.songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("This section is no longer available. Return Home and open it again.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${snapshot.songs.size} songs", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            playerViewModel.playSongs(snapshot.songs, snapshot.songs.first(), snapshot.title)
                        }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("Play all", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                items(snapshot.songs, key = { it.id }) { song ->
                    EnhancedSongListItem(
                        song = song,
                        isPlaying = false,
                        isCurrentSong = false,
                        showMoreOptionsButton = false,
                        onMoreOptionsClick = {},
                        onClick = { playerViewModel.playSongs(snapshot.songs, song, snapshot.title) },
                        onLongPress = {},
                    )
                }
            }
        }
    }
}
