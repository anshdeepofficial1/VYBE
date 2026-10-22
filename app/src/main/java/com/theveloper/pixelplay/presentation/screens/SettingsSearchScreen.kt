package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.theveloper.pixelplay.presentation.model.SettingsCategory
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

data class SettingSearchItem(
    val title: String,
    val description: String,
    val categoryName: String,
    val route: String,
    val keywords: List<String> = emptyList()
)

object SettingsSearchIndex {
    val allSettings = listOf(
        // Theme & Appearance
        SettingSearchItem(
            title = "Player Theme & Layout",
            description = "Customize player style, carousel peek, and background visualizer",
            categoryName = "Appearance",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.APPEARANCE.id),
            keywords = listOf("appearance", "visual", "ui", "colors", "theme", "dark", "amoled", "oled", "carousel")
        ),
        SettingSearchItem(
            title = "App Color Palette",
            description = "Dynamic Material You colors, monochrome, vibrant accents",
            categoryName = "Appearance",
            route = Screen.PaletteStyle.route,
            keywords = listOf("palette", "dynamic color", "material you", "accent")
        ),
        SettingSearchItem(
            title = "Artist Image Customization",
            description = "Manage custom artist photos and artwork sources",
            categoryName = "Appearance",
            route = Screen.ArtistSettings.route,
            keywords = listOf("artist", "cover", "avatar", "artwork")
        ),

        // Audio & Playback
        SettingSearchItem(
            title = "Equalizer & DSP",
            description = "Frequency equalizer, bass boost, virtualizer",
            categoryName = "Audio",
            route = Screen.Equalizer.route,
            keywords = listOf("equalizer", "eq", "bass", "sound", "virtualizer", "treble", "dsp")
        ),
        SettingSearchItem(
            title = "Playback Settings",
            description = "Crossfade, silence skipping, persistent shuffle, auto-play",
            categoryName = "Playback",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.PLAYBACK.id),
            keywords = listOf("crossfade", "gapless", "shuffle", "silence", "playback", "volume", "shake")
        ),
        SettingSearchItem(
            title = "Data Saver",
            description = "Reduce streaming bandwidth and adapt buffering on slower connections",
            categoryName = "Music Management",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.LIBRARY.id),
            keywords = listOf("data saver", "slow internet", "bandwidth", "buffer", "mobile data")
        ),
        SettingSearchItem(
            title = "Haptic Feedback",
            description = "Enable vibration feedback for supported controls and gestures",
            categoryName = "Behavior",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.BEHAVIOR.id),
            keywords = listOf("haptic", "vibration", "vibrate", "feedback")
        ),
        SettingSearchItem(
            title = "App Behavior & Gestures",
            description = "Touch gestures, pause on headphone disconnect, launch screen",
            categoryName = "Behavior",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.BEHAVIOR.id),
            keywords = listOf("behavior", "gestures", "headset", "bluetooth", "noisy", "launch tab")
        ),

        // Library & Storage
        SettingSearchItem(
            title = "Library & Folder Scan",
            description = "Scan local audio folders, blacklist short clips, rescan library",
            categoryName = "Library",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.LIBRARY.id),
            keywords = listOf("scan", "folders", "blacklist", "storage", "rescan", "songs", "filter")
        ),
        SettingSearchItem(
            title = "Artwork Cache",
            description = "Control the real album-art cache limit used by VYBE",
            categoryName = "Library",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.LIBRARY.id),
            keywords = listOf("cache", "artwork", "album art", "storage", "cleanup")
        ),
        SettingSearchItem(
            title = "Lyrics Sources",
            description = "Choose embedded, local, or online lyrics priority and reset imported lyrics",
            categoryName = "Lyrics",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.LIBRARY.id),
            keywords = listOf("lyrics", "synced lyrics", "embedded", "online lyrics", "lrclib")
        ),
        SettingSearchItem(
            title = "Online Playback",
            description = "Playback continuation, crossfade, silence skipping, background and Cast behavior",
            categoryName = "Playback",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.PLAYBACK.id),
            keywords = listOf("streaming", "online", "autoplay", "background", "buffering", "cast")
        ),

        // Music management
        SettingSearchItem(
            title = "Import Spotify Playlist",
            description = "Paste a Spotify playlist link to import and match tracks in VYBE",
            categoryName = "Music Management",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.LIBRARY.id),
            keywords = listOf("spotify", "import", "playlist", "spotify import", "importer", "convert playlist", "spotify playlist")
        ),
        SettingSearchItem(
            title = "AI Playlist Recommendations",
            description = "Configure the optional AI playlist generator and its recommendation inputs",
            categoryName = "AI & Spotify",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.AI_INTEGRATION.id),
            keywords = listOf("ai", "gemini", "prompt", "recommendations", "smart mix", "playlist")
        ),

        // Accounts & Streaming Services
        SettingSearchItem(
            title = "YouTube Music Account",
            description = "Sign in and sync your YouTube Music library, likes, history, and playlists",
            categoryName = "Accounts",
            route = Screen.Accounts.route,
            keywords = listOf("account", "youtube", "youtube music", "login", "sync", "playlists", "likes", "history")
        ),
        SettingSearchItem(
            title = "Spotify Playlists",
            description = "Import any public Spotify playlist from its link - no account or sign-in needed",
            categoryName = "Music Management",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.SPOTIFY.id),
            keywords = listOf("spotify", "public playlist", "import", "link", "sync")
        ),

        // About
        SettingSearchItem(
            title = "About VYBE",
            description = "Version, Maintainer Anshdeep Singh, Open source licenses, GitHub",
            categoryName = "About",
            route = Screen.About.route,
            keywords = listOf("about", "version", "maintainer", "anshdeep", "developer", "github", "license")
        ),
        SettingSearchItem(
            title = "Update Notification Hours",
            description = "Choose the overnight hours when automatic update alerts may appear",
            categoryName = "About",
            route = Screen.About.route,
            keywords = listOf("updates", "night updates", "schedule", "notification time", "remind")
        ),
        SettingSearchItem(
            title = "Developer Options",
            description = "Advanced diagnostics and technical playback controls",
            categoryName = "Developer",
            route = Screen.SettingsCategory.createRoute(SettingsCategory.DEVELOPER.id),
            keywords = listOf("developer", "advanced", "debug", "diagnostics", "logs")
        )
    )

    fun search(query: String): List<SettingSearchItem> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return emptyList()

        return allSettings.filter { item ->
            item.title.lowercase().contains(clean) ||
            item.description.lowercase().contains(clean) ||
            item.categoryName.lowercase().contains(clean) ||
            item.keywords.any { it.lowercase().contains(clean) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchScreen(
    navController: NavController,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember(searchQuery) {
        SettingsSearchIndex.search(searchQuery)
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Search Input Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search settings…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )
            }

            // Results / Empty / Default state
            if (searchQuery.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Type to search settings",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontFamily = GoogleSansRounded,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = "e.g. Spotify, Equalizer, Theme, Crossfade, Lyrics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No settings found for \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults, key = { it.title + it.route }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    navController.navigateSafely(item.route)
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GoogleSansRounded,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = "• ${item.categoryName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
