package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private data class ChangelogRelease(
    val version: String,
    val date: String,
    val changes: List<String>,
)

private val VybeChangelog = listOf(
    ChangelogRelease(
        version = "0.14.0",
        date = "18 October 2026",
        changes = listOf(
            "Instant playback: Tapping any song now starts audio in about one second, even from a cold start, using a concurrent stream-resolver race with a shared single-flight cache.",
            "No more 403 recoveries: The stream liveness probe now mirrors Media3's exact request (no Range header, YouTube Music Origin and Referer, Chrome user agent), so dead mirrors lose the race instead of winning it and then failing.",
            "Recommendation accuracy: Provider ordering is now authoritative and results are never re-ranked by title text, so a search for a Punjabi track can no longer surface an unrelated song with a similar name.",
            "Immersive artwork: The current cover is blurred across every screen, bar and sheet from one app-wide layer, with a new Full App artwork toggle in Settings.",
            "Region-correct Home: The storefront country is derived from the connection unless you pick one, so shelves, charts and moods match the region you are actually in.",
            "Release build: VYBE now ships as a signed, R8-minified and resource-shrunk release APK with verified ad-free playback."
        )
    ),
    ChangelogRelease(
        version = "0.13.0",
        date = "8 September 2026",
        changes = listOf(
            "Major discovery update: Guest Search now opens instantly with useful Explore categories while live YouTube Music recommendations load and signed-in preferences replace them.",
            "Home reliability: Provider pagination is bounded so valid first-page Quick Picks and shelves are no longer discarded by a cold-start timeout.",
            "Home content: Added Speed dial and retained provider-authored shelves, collections, charts, releases, videos, artists, community playlists, moods and long-listen sections whenever supplied for the region/account.",
            "Empty state cleanup: Removed the misleading No data to show Your Mix card when a guest profile has not produced a personal mix yet.",
            "Customization: Speed dial joins the persistent Home section visibility and ordering controls."
        )
    ),
    ChangelogRelease(
        version = "0.12.3",
        date = "7 September 2026",
        changes = listOf(
            "YouTube Music Home: Real personalised provider shelves now appear in provider order, including mixes, Speed Dial, community playlists, moods, albums, videos, artists, long listens, live music and fresh releases when available for the account and region.",
            "Fast Home: The last successful authentic feed is cached for instant relaunch and offline fallback; random search results are never labelled as official shelves.",
            "Search: Songs, Videos, Albums and Artists are separated, official music videos are retained, and exact remote browse IDs open the correct result.",
            "Song / Video player: Verified music videos switch inside the artwork area on the same Media3 session and exact timestamp, with landscape fullscreen and 360p/720p/1080p quality selection.",
            "Home interactions: Every song shelf supports Play all, tap to play, double-tap to like and long-press actions in VYBE's visual language.",
            "Music-only policy: Podcast and promotional shelves remain excluded from VYBE."
        )
    ),
    ChangelogRelease(
        version = "0.12.2",
        date = "7 September 2026",
        changes = listOf(
            "Clean Reinstall: Android Auto Backup, cloud restore, device-transfer restore, and retained uninstall data are disabled.",
            "Fresh installs no longer inherit Listening Again, listening stats, cache metadata, recommendations, or queues from a previous installation.",
            "Manual account backup remains available and restores data only after explicit user sign-in and confirmation."
        )
    ),
    ChangelogRelease(
        version = "0.12.1",
        date = "7 September 2026",
        changes = listOf(
            "Playback Recovery: Online retries preserve the autoplay queue and no longer show a misleading recoverable source error.",
            "Continuous Radio: Artist-related fallback tracks are added and repeat-one cannot block queue progression.",
            "Instant Search: Discovery restores immediately from disk with one clean loading indicator.",
            "Library: Internal playback cache is a strict unique last 10 and the visible Cache folder is removed.",
            "Albums & Metadata: Exact provider IDs and normalized title/artist fallback resolve albums; placeholder artist labels are removed.",
            "Home: New Finds appears before Quick Picks, Listen Again, and Your Mix.",
            "Backup & Account: Last backup time, daily 8:00 AM backup, and the YouTube Music profile photo are now included."
        )
    ),
    ChangelogRelease(
        version = "0.12.0",
        date = "5 September 2026",
        changes = listOf(
            "In-App Updater Size Verification Fix: Resolved 'Downloaded update size does not match' error by making size verification resilient and ensuring accurate package manifest checks.",
            "Silent Update Download: Download progress notification is now completely silent without repetitive alert sounds on percentage changes, with a single completion alert once ready to install.",
            "Permanent Lyrics Caching: Fetched and translated lyrics for local and online tracks are safely cached to disk and persist indefinitely across app restarts.",
            "Reset Imported Lyrics Flow: Resetting lyrics immediately cleans the song's stored lyrics and opens the Fetch Lyrics picker dialog directly inside the player sheet."
        )
    ),
    ChangelogRelease(
        version = "0.11.9",
        date = "5 September 2026",
        changes = listOf(
            "Search Crash Fix: Resolved ClassCastException on Search screen by safely managing in-memory discovery caching and eliminating raw disk deserialization.",
            "In-App Release Notes: Redesigned and updated Release Notes screen with full changelog history and proper Material 3 styling.",
            "Stability: Hardened background discovery retrieval against corrupted cache states."
        )
    ),
    ChangelogRelease(
        version = "0.11.8",
        date = "5 September 2026",
        changes = listOf(
            "Pure Audio Player: Completely removed video playback, video surfaces, buffering indicators, and player header video toggles.",
            "YouTube Music Songs Only: Strict filtering excludes official music videos (OMVs) and video uploads. Search and discovery query exclusively through YouTube Music songs endpoints.",
            "Ultra-Fast 1–2s Refresh: Parallelized discovery requests with bounded 1.8s timeout; home refresh now completes in 1–2 seconds.",
            "Persistent Synced Library: Synced counts for Library, Liked Tracks, Playlists, and History persist accurately across launches.",
            "Accounts Layout: Streamlined hierarchy placing Linked Services first, followed directly by Backup & Data Settings."
        )
    ),
    ChangelogRelease(
        version = "0.11.6",
        date = "4 September 2026",
        changes = listOf(
            "Player Video Button: Restricted video icon strictly to verified music videos.",
            "Theme Tinting: Dynamic app logo tinting now properly respects system Light/Dark appearance.",
            "In-App Updater: Fixed unknown download size display with real-time MB and percentage tracking."
        )
    ),
    ChangelogRelease(
        version = "0.11.5",
        date = "4 September 2026",
        changes = listOf(
            "Streaming Engine: Optimized YouTube Music and JioSaavn audio stream resolution with rapid fallbacks.",
            "Cache: Enhanced audio chunk caching for gapless and instant track skipping."
        )
    ),
    ChangelogRelease(
        version = "0.10.7",
        date = "2 September 2026",
        changes = listOf(
            "Onboarding: Update-hour selection is shown only on a true first install and never after an app update.",
            "Spotify: Secure PKCE browser sign-in replaces the blank WebView and synchronizes accessible private, collaborative, followed, and owned playlists.",
            "Video: Artwork-area video uses the same Media3 session as audio, preserves position, supports fullscreen and no longer double-plays.",
            "Library: Exact artist and album browse IDs open the selected profile or release; Follow and Block stay synchronized with recommendations.",
            "Discovery: Official YouTube Music chart order is preserved and cached; weekly discovery excludes heard and blocked tracks.",
            "Audio: Added optional genre-aware Smart EQ and safer transient audio-focus resume while respecting Data Saver.",
            "Privacy: Added optional deduplicated install and active-device counting with hashed App Set ID and consent revocation.",
            "Release: Android builds and CI now produce only the modern ARM64 APK."
        )
    ),
    ChangelogRelease(
        version = "0.10.6",
        date = "1 September 2026",
        changes = listOf(
            "Branding: Made the notification and media status-bar VYBE mark consistently white without changing in-app logos.",
            "Playback: Bound YouTube Music and JioSaavn audio to exact provider IDs instead of stale URLs or same-title matches.",
            "Library: Fixed visible album and artist cards opening as unavailable by separating local, remote, and metadata lookup identities."
        )
    ),
    ChangelogRelease(
        version = "0.10.5",
        date = "1 September 2026",
        changes = listOf(
            "Spotify: Improved in-app WebView rendering, blank-page recovery, and post-login playlist synchronization verification.",
            "Video: Added artwork-area video playback with position handoff, controls, and fullscreen support.",
            "Sharing: Exact YouTube IDs now recover the original title, artist, and high-resolution artwork.",
            "Discovery: Quick Picks refresh per session while Trending keeps the authoritative YouTube Music chart order.",
            "Updates: Added exact overnight time selection in onboarding, About, and a one-time upgrade prompt.",
            "Recognition: Added longer normalized capture and multiple fingerprint windows for quieter ambient recordings.",
            "Settings: Improved search coverage for Spotify, Data Saver, Haptics, and update hours.",
            "Branding: Enlarged the visible VYBE notification silhouette without exceeding Android's status icon slot."
        )
    ),
    ChangelogRelease(
        version = "0.10.3",
        date = "31 August 2026",
        changes = listOf(
            "Spotify: Rebuilt sign-in as a complete in-app browser experience with visible loading and synchronization states.",
            "Spotify: Added Samsung WebView rendering compatibility, timeout detection, renderer recovery, and retry handling.",
            "Spotify: Added secure HTTP and SSL failure handling while preserving OAuth PKCE and exact callback validation.",
            "Spotify: Account playlists now refresh immediately after a successful in-app connection.",
            "AI: Fixed provider-specific key saving, stale cooldowns, lyric translation and English-script line handling.",
            "Sharing: VYBE and YouTube Music links now retain the exact song ID and play that track inside VYBE.",
            "Recognition: Added the GPL-compatible Echo Music-style Shazam signature flow.",
            "Home: Kept New Releases & Release Radar available with recent-release filtering."
        )
    ),
    ChangelogRelease(
        version = "0.10.2",
        date = "30 August 2026",
        changes = listOf(
            "Updates: Replaced release-note parsing with a machine-readable update manifest.",
            "Updates: Adopted monotonic Android version codes so same-version and downgrade collisions cannot recur.",
            "Updates: Added SHA-256 verification before an APK can reach Android's installer.",
            "Updates: Prevented cached or same-version APK assets from being offered as updates."
        )
    ),
    ChangelogRelease(
        version = "0.10.1",
        date = "30 August 2026",
        changes = listOf(
            "Updates: Skip now suppresses the selected version's popup and notification until a newer release or a manual check.",
            "Updates: Made Android versionCode authoritative so an installed build can never be offered as its own update.",
            "Quick Picks: Recommendations now use actual listening history and explicit likes instead of unrelated cached searches.",
            "Release Radar: Added authoritative provider-date validation and a second strict 30-day filter.",
            "Moods: Added richer visual elements and contextual labels to the mood cards."
        )
    ),
    ChangelogRelease(
        version = "0.10.0",
        date = "30 August 2026",
        changes = listOf(
            "Updates: Fixed same-version GitHub releases being offered as newer updates.",
            "Updates: Version checks now use installed Android package metadata and normalized semantic tags.",
            "Updates: Downloaded APKs are accepted only when their Android version code is newer.",
            "Release: Consolidated the latest player artwork, AI, recognition, and discovery fixes."
        )
    ),
    ChangelogRelease(
        version = "0.9.7",
        date = "30 August 2026",
        changes = listOf(
            "Player: Fixed the edge-to-edge artwork strip and improved Now Playing contrast.",
            "Artwork: Added premium full-cover Home collages and higher-resolution notification art.",
            "AI: Fixed saved provider keys being misreported as missing during lyrics translation and romanization.",
            "AI settings: Added a clear provider status and feature-usage guide.",
            "Recognition: Added cancellation and self-contained native runtime support.",
            "Release Radar: Restricted releases to the latest 30 days with newest-first dates."
        )
    ),
    ChangelogRelease(
        version = "0.9.5",
        date = "30 August 2026",
        changes = listOf(
            "Spotify: Added PKCE account sign-in for private, collaborative, followed, and public playlist imports.",
            "Spotify: Added playlist selection, import progress, secure token refresh, and optional YouTube Music sync.",
            "Recognition: Results now return to VYBE search and play inside the app instead of opening Google results.",
            "Recommendations: Autoplay now prioritizes the current language, artist, and music style.",
            "Discovery: Uses YouTube Music charts and current-year release discovery.",
            "Equalizer: Connected outputs now open VYBE EQ with separate saved profiles per device.",
            "Metadata: Added a Re-fetch metadata action to song menus."
        )
    ),
    ChangelogRelease(
        version = "0.9.4",
        date = "30 August 2026",
        changes = listOf(
            "Library: Fixed exact album and artist navigation, multi-artist selection, and persistent tab order/visibility.",
            "Home: Restored square Quick Picks and Listen Again artwork cards.",
            "Recognition: Prevented automatic launch and added a user-controlled navigation setting.",
            "Devices: Added focused Bluetooth audio controls and removed the unrelated Wi-Fi shortcut.",
            "Updates: Added custom reminder timing and clear up-to-date confirmation.",
            "Support: Added optional GitHub Sponsors and Buy Me a Coffee links."
        )
    ),
    ChangelogRelease(
        version = "0.9.3",
        date = "30 August 2026",
        changes = listOf(
            "Home: Added Release Radar, Top Charts, Listen Again, related recommendations and Fresh Finds.",
            "Recognition: Added a fifth customizable navigation destination with no user API key.",
            "Playback: Added animated artwork, data saver, high-refresh controls and promoted Now Playing support.",
            "Updates: Added APK size, downloaded size, progress and selectable reminder times.",
            "AI: Fixed provider-name matching so configured DeepSeek and Gemini keys reach Daily Mix.",
            "Notifications: Standardized VYBE branding across notification types."
        )
    ),
    ChangelogRelease(
        version = "0.9.2",
        date = "29 August 2026",
        changes = listOf(
            "Sharing: Added verified VYBE links that preserve the exact YouTube Music song ID.",
            "Search: Exact song and artist matches now rank ahead of loosely related results.",
            "Performance: Exact and enriched YouTube Music searches now run concurrently.",
            "Library: Refined multi-selection, albums, artists and navigation behavior.",
            "Personalization: Improved daily recommendations and YouTube Music sync details."
        )
    ),
    ChangelogRelease(
        version = "0.9.1",
        date = "29 August 2026",
        changes = listOf(
            "Quick Picks: Added complete long-press song actions.",
            "Branding: Refined About and Home logo sizing and alignment.",
            "Logo modes: Added Restart now and Restart later after changing appearance.",
            "Splash: Corrected light and dark logo contrast.",
            "Profile: Removed unused empty sheet space.",
            "Accounts: Added clearer YouTube Music sync coverage and a dedicated sync action."
        )
    ),
    ChangelogRelease(
        version = "0.8.6",
        date = "28 August 2026",
        changes = listOf(
            "Downloads: Added resilient download state, progress controls, retry and pause/resume support.",
            "Playback: Improved queue state, end-of-track handling and sleep timer controls.",
            "Library: Improved album, playlist and online-song metadata and artwork handling.",
            "Search: Refined YouTube Music result resolution and matching.",
            "Stability: Verified the complete release source with a clean Kotlin compilation."
        )
    ),
    ChangelogRelease(
        version = "0.8.0",
        date = "24 August 2026",
        changes = listOf(
            "Spotify: Added Spotify account integration in Settings.",
            "Spotify: You can now connect your Spotify account and import playlists directly.",
            "Privacy: Removed personal developer domains from share links.",
            "Fix: Resolved Online Track metadata being overwritten during hydration.",
            "Fix: Fixed 400 Bad Request error when searching lyrics with empty queries."
        )
    ),
    ChangelogRelease(
        version = "0.7.22",
        date = "24 August 2026",
        changes = listOf(
            "Artwork: Added loading indicators when fetching album artwork.",
            "Playback: Fixed an issue where original metadata was overwritten.",
            "Lyrics: Fixed a network crash (400 Bad Request) when Lyrics Suggestions were turned off.",
            "Sleep Timer: Extracted Sleep Timer into its own dedicated menu button in the main player.",
            "Recommendations: Moods are now dynamically curated based on your listening habits.",
            "Recommendations: Blocked Artists now correctly filter out of generated daily mixes.",
            "Search: Improved typo-tolerance search ranking to heavily prioritize exact matches.",
            "Settings: Refined category icon colors for a richer, premium look."
        )
    ),
    ChangelogRelease(
        version = "0.7.21",
        date = "23 August 2026",
        changes = listOf(
            "Search Enhancements: YouTube Music search results are now cleanly categorized, with a dedicated section and tab just for Videos, separating them from standard songs.",
            "UI Architecture: Completely removed the experimental Liquid Glass App UI Style setting as it caused performance and layout issues. The app now strictly uses the polished Material UI."
        )
    ),
    ChangelogRelease(
        version = "0.7.20",
        date = "23 August 2026",
        changes = listOf(
            "Library Crash Fix: Fixed a critical crash that occurred when switching between tabs in the Library after playing music, which was caused by duplicate unique IDs in the listening history grid."
        )
    ),
    ChangelogRelease(
        version = "0.7.19",
        date = "23 August 2026",
        changes = listOf(
            "Onboarding Improvements: Made critical permissions (Media, Notifications, and Battery Optimization) strictly mandatory. Users can no longer skip these during the initial setup.",
            "Notification Fix: Fixed an issue where tapping the media notification in the status bar would unnecessarily trigger the player sheet to slide open."
        )
    ),
    ChangelogRelease(
        version = "0.7.18",
        date = "23 August 2026",
        changes = listOf(
            "UI Enhancements: Upgraded the Playlist and Artist detail screens by replacing standard rectangular buttons with a sleek row of three floating action buttons (Play, Shuffle, Loop) shaped with RoundedStarShape.",
            "App Launch/Setup Crash: Fixed a critical race condition that caused the app to crash or freeze on many devices immediately after completing the onboarding setup.",
            "Release Stability: Added comprehensive ProGuard and R8 rules to prevent aggressive code stripping for Hilt, Room DAOs, ExoPlayer/Media3, and WorkManager.",
            "Sync Safety: Wrapped initial library full sync in a safety block so that localized sync failures do not block app setup."
        )
    ),
    ChangelogRelease(
        version = "0.7.17",
        date = "23 August 2026",
        changes = listOf(
            "Search History: Added a Search History UI that displays previous search queries when the search bar is empty.",
            "Branding Updates: Removed the old Beta clean-install disclaimer on startup. Replaced the Beta button in the top bar with Smoothy Play text.",
            "Queue Deletion Crash: Fixed a NumberFormatException crash when attempting to delete online songs from the queue by safely filtering non-local IDs.",
            "Song Info Sheet: Online songs no longer display the Delete from device option, preventing unsupported actions."
        )
    ),
    ChangelogRelease(
        version = "0.7.16",
        date = "23 August 2026",
        changes = listOf(
            "Keystore Crash Fix: Fixed crashes on devices with broken MasterKey/EncryptedSharedPreferences by safely falling back to standard SharedPreferences."
        )
    ),
    ChangelogRelease(
        version = "0.7.15",
        date = "23 August 2026",
        changes = listOf(
            "Completely abandoned broken hardware Keystore on specific devices, eliminating startup crashes.",
            "Added Play, Shuffle, and Loop playback buttons directly to Album, Playlist, and Artist profile headers.",
            "Fixed Immersive Artwork layout where title/artist text was invisible over bright covers.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.14",
        date = "23 August 2026",
        changes = listOf(
            "Attempted to fix AEADBadTagException startup crash on some Android 13/14 devices.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.13",
        date = "23 August 2026",
        changes = listOf(
            "Fixed a search bug where the UI had to be fully cleared with backspace before searching again.",
            "Prepared settings skeleton for Screenshot Privacy toggle.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.12",
        date = "23 August 2026",
        changes = listOf(
            "Fixed duplicate artist-history keys that could crash Home.",
            "Preserved square artwork and centered landscape thumbnails over a blurred fill.",
            "Restored album titles, artists, artwork, and track thumbnails from YouTube Music.",
            "Added automatic GitHub update notifications with reminder and skip controls.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.11",
        date = "23 August 2026",
        changes = listOf(
            "Made artist pages render immediately and progressively load original profiles, artwork, and complete catalogues.",
            "Added a Storage & cache dashboard for artwork cache, offline downloads, other app data, and total usage.",
            "Added VYBE song links that open and play shared tracks in VYBE, with a download fallback for recipients.",
            "Fixed missing online durations and hid private online and in-app download paths from Song Info.",
            "Hardened database and transient-cache recovery during updates from older VYBE versions.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.10",
        date = "22 August 2026",
        changes = listOf(
            "Prevented legacy or damaged local databases from trapping VYBE in a startup crash loop.",
            "Added automatic recovery for corrupted app settings while preserving healthy user data.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.8",
        date = "22 August 2026",
        changes = listOf(
            "Enabled Go to Album and Go to Artist for online search and Home song menus.",
            "Added on-demand YouTube Music album lookup when a result lacks its album browse ID.",
            "Kept music audio results while filtering podcast and episode content from search.",
            "Made real download-progress and completion notifications silent.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.7",
        date = "22 August 2026",
        changes = listOf(
            "Added live download progress in the app and Android notifications, including failed-download retry feedback.",
            "Added list/grid switching to Downloads, Songs, Albums, Artists, Playlists, and Liked.",
            "Filled empty Library categories from listening history while keeping device folders unchanged.",
            "Filtered podcast episodes from music search and added artist cards plus expanded artist catalogues.",
            "Added album and artist navigation from Now Playing, including a picker for collaborations.",
            "Improved landscape YouTube Music artwork with a square fitted image and blurred background fill.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.6",
        date = "22 August 2026",
        changes = listOf(
            "Added in-app GitHub release update checks, APK downloads, progress, and installer handoff.",
            "Added volume-up resume after VYBE automatically pauses at zero media volume.",
            "Fixed online artist navigation by preserving YouTube Music browse IDs.",
            "Added a visible and playable Top Songs section to online artist profiles.",
            "Improved synchronized lyric scrolling so it no longer visually lags by one or two seconds.",
            "Kept Spotify public-URL import, removed account sign-in, improved parsing, and added large-playlist progress.",
            "Changed the scrollbar default to off; it can still be enabled in Settings.",
        ),
    ),
    ChangelogRelease(
        version = "0.7.5",
        date = "13 June 2026",
        changes = listOf(
            "VYBE playback, search, library, downloads, lyrics, accounts, and personalization baseline.",
            "Added YouTube Music integration, AI tools, Wear OS support, and extensive performance improvements.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Release Notes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(VybeChangelog, key = { it.version }) { release ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(24.dp, 60),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "VYBE ${release.version}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = release.date,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        release.changes.forEach { change ->
                            Text(
                                text = "• $change",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
