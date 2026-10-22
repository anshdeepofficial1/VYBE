package com.theveloper.pixelplay.presentation.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.spotify.auth.SpotifyLoginActivity
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel

@Composable
fun SpotifySettingsSection(settingsViewModel: SettingsViewModel, context: Context) {
    val publicImport by settingsViewModel.spotifyImportState.collectAsStateWithLifecycle()
    var playlistUrl by remember { mutableStateOf("") }

    // Spotify account sign-in was removed on purpose: public playlist link import is the only
    // supported path, so there is no half-working login to confuse anyone.
    SettingsSubsection(title = "Spotify playlists") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Import any Spotify playlist from its public link. No Spotify account or sign-in is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    SettingsSubsection(title = "Public playlist URL") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Public-link import works for any Spotify playlist link without account restrictions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = playlistUrl,
                onValueChange = { playlistUrl = it },
                label = { Text("Spotify playlist URL") },
                trailingIcon = {
                    TextButton(onClick = {
                        clipboardManager.getText()?.text?.let { text ->
                            if (text.isNotBlank()) playlistUrl = text.trim()
                        }
                    }) {
                        Text("Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { settingsViewModel.importSpotifyPlaylist(playlistUrl) },
                enabled = playlistUrl.isNotBlank() && !publicImport.isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (publicImport.isImporting) "Importing..." else "Import public playlist")
            }
            if (publicImport.isImporting) {
                LinearProgressIndicator(progress = { publicImport.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${publicImport.processed} of ${publicImport.total} songs")
            }
            publicImport.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            publicImport.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
