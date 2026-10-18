## [0.14.0] - 2026-10-18
### Added
- Full App artwork: one app-wide blurred cover layer behind every screen, bar and sheet, with a toggle in Settings.
- Region-aware Home storefront derived from the connection when no explicit country is chosen.

### Changed
- Stream resolution now races the preferred resolver against fallbacks concurrently, with a shared single-flight cache, so a tap starts audio in about one second even on a cold start.
- The stream liveness probe mirrors Media3's real request (no Range header, YouTube Music Origin/Referer, Chrome user agent) so dead mirrors lose the race instead of winning and then failing.
- Provider ordering is authoritative; search results are no longer re-ranked by title text, which removes unrelated same-name tracks from Punjabi and other searches.
- Release builds are signed, R8-minified and resource-shrunk.

### Fixed
- Fixed repeated HTTP 403 recoveries that stalled playback after the first buffering pass.
- Fixed wrong-song playback and wrong-language recommendations caused by local title-based re-ranking.
- Removed the per-screen artwork wash that conflicted with the app-wide immersive layer.

## [0.13.0] - 2026-09-08
### Fixed
- Prevented valid first-page YouTube Music Home and Quick Picks data from being discarded when continuation requests exceeded the cold-start timeout.
- Search no longer opens as a blank page on a fresh guest installation; Explore categories remain available while remote discovery loads.
- Removed the misleading empty Your Mix placeholder for guest profiles without a generated personal mix.

### Added
- Added Speed dial backed by the current provider/recommendation pool.
- Added persistent visibility and ordering control for Speed dial.
- Added focused tests for Home shelf parsing, continuation tokens, and multi-page album tracks.

## [0.12.0] - 2026-09-05
### Fixed
- Fixed in-app updater download size mismatch ("Downloaded update size does not match") by making size checks resilient and matching package manifest sizes.
- Fixed noisy notification sound triggering on every download percentage change; updater now uses a silent progress channel with a single completion alert.
- Fixed lyrics re-fetching issue: fetched and translated lyrics are now permanently stored in disk cache across app restarts.
- Fixed "Reset Imported Lyrics" closing the lyrics sheet; now clears existing stored lyrics and immediately presents the search options picker dialog without closing the sheet.

## [0.11.9] - 2026-09-05
### Fixed
- Fixed critical `ClassCastException` (`LinkedTreeMap cannot be cast to Song`) when opening the Search screen by removing unsafe Gson disk deserialization and switching to a robust in-memory discovery cache.
- Hardened `OnlineSearchViewModel` initialization against corrupted cache reads with comprehensive safety fallbacks.

### Changed
- Replaced the in-app "Changelog" button with a modern "Release Notes" screen featuring full release history and updated Material 3 formatting.

## [0.11.8] - 2026-09-05
### Changed
- Converted VYBE to a pure YouTube Music audio experience by removing inline and fullscreen video playback.
- Restricted YouTube Music searches strictly to songs (`SEARCH_FILTER_SONGS`), excluding OMVs and videos.
- Reordered Accounts to show Linked Services first, followed by Backup.

### Improved
- Home and Daily Mix refreshes now complete in 1–2s via parallelized requests with bounded 1.8s timeout.
- Fully persisted YouTube Music synced tracks count and statistics across app launches.

### Fixed
- Fixed crash when tapping Search button caused by speech recognizer availability and Dagger dependency mapping.

## [0.11.7] - 2026-09-04
### Changed
- Converted VYBE to a pure YouTube Music audio experience by removing inline/fullscreen video playback and Videos tabs from Search and Artist profiles.
- Restricted YouTube Music searches to the songs filter so music-video results no longer enter audio result shelves.
- Reordered Accounts to show Linked Services first, followed by Settings & Data Backup.

### Improved
- Parallelized release, trending, and autoplay discovery requests with bounded timeouts for substantially faster Home and Daily Mix refreshes.
- Persisted YouTube Music Library, Liked, Playlist, History, and total synced counts across launches and reconciled them against local synced data.

### Fixed
- Hardened Search list identity for albums with duplicate or missing provider IDs, preventing Compose list-key crashes when Search is opened or refreshed.

## [0.11.6] - 2026-09-04
### Fixed
- **Player Video Button Visibility**:
  - Restricted the video toggle button strictly to genuine music videos (`song.isMusicVideo` or video streams). Standard audio songs no longer display the video icon.
- **Video Buffering & Loading Feedback**:
  - Added an active player buffering listener and loading indicators. While resolving video streams or buffering, a blurred artwork background with a progress spinner and "Loading video..." text is displayed instead of a blank black screen.
- **Fullscreen Video Orientation Glitch**:
  - Eliminated sensor landscape activity flips. The screen remains strictly in portrait/vertical orientation on an edge-to-edge black background with a clean back navigation handler.
- **Dynamic App Logo Light Mode Tinting**:
  - Fixed logo color filtering in `GradientTopBar.kt` and `AboutScreen.kt` to faithfully reflect system Light/Dark theme and appearance preferences.
- **In-App Updater Accurate MB Tracking**:
  - Resolved the "Downloading Unknown from Unknown" issue by providing exact byte counts and automated remote `Content-Length` resolution. Progress displays exact downloaded MBs and total size (e.g. `12.4 MB of 55.0 MB`).
  - Monotonic `versionCode = 11600` ensures seamless in-app and sideload upgrades over version 11500.

### Added
- **Complete 1:1 Parity iOS App (`iosApp`)**:
  - Fully rewritten native SwiftUI iOS companion app matching Android VYBE feature-for-feature: online music streaming, "Best for You" and "Release Radar" home feeds, real-time debounced online search with genre badges, offline cache, and native lock screen media controls.

## [0.11.5] - 2026-09-04
### Fixed
- **In-App Updater Version Code & Monotonic Identity Fix**:
  - Aligned Android release `versionCode` to monotonic identity `11500` across CI and build configurations, resolving the *"Download update is not newer than installed vibe version"* error when installing updates.
  - Hardened updater verification in `GitHubUpdateService` to allow version upgrade when version names are newer even if version codes match.
  - Linked official release signing keys in CI secrets to ensure 100% signature consistency between in-app updates and existing installs.
- **Recommendation Contamination Filter**: Strictly excluded videos and non-original fan remixes (dhol remixes, DJ edits, slowed+reverb, bass boosted, mashups, compilations) from "Recommended for You ✨".
- **Renamed "Trending Now" to "Best for You"**: Tailored discovery tracks to user listening habits with strictly clean audio songs.

### Added
- **Instant Search Area Discovery (0ms Zero-Latency Pre-warm)**: Pre-populates discovery content, "Best for You" tracks, and AI recommendations immediately from disk/memory snapshot upon opening the app, eliminating the 5–10s search screen delay.
- **Library Recent 10 Songs Offline Cache**: Dedicated "Cached (10)" tab in Library that stores the last 10 played songs on disk for instant offline replay with zero network overhead and no buffering spinner.
- **iPhone Companion App (`iosApp`) Updates**:
  - Added "Cached" tab in Library mirroring the 10-song offline cache.
  - Added "Best for You" discovery section in Search view.
- **Dual-Target GitHub Release Automation (Android APK + iPhone IPA)**:
  - Updated CI/CD workflow to compile both Android release APKs and iPhone IPAs directly on GitHub Actions.
  - Generates signed Android release APKs and universal sideloadable iPhone IPAs (ready for AltStore, SideStore, TrollStore, Scarlet, Sideloadly).
  - Automatically uploads all mobile assets to the GitHub Release.

## [0.11.2] - 2026-09-03
### Added
- **Multi-Source Dynamic Recommendation Engine (OuterTune + Meld + EchoMusic Combo)**:
  - **OuterTune & InnerTune Automix Radio Seeds**: Automatically selects 1–2 random seeds from user playback history on each launch/refresh to explore fresh musical branches via YouTube Music's automix AI (`RDAMVM<seedId>`).
  - **EchoMusic Anti-History Deduplication**: Strictly excludes songs already played in recent listening history so recommendations always deliver brand-new discoveries rather than repetitive past tracks.
  - **Meld Spotify Hybridization**: Automatically blends top tracks and artist seeds from connected Spotify accounts into the discovery pool.
  - **Dynamic On-Refresh Shuffle**: Pull-to-refresh continuously re-rolls seed tracks for an ever-fresh, non-repeating discovery feed.
- **Account Backup Card with Live Size & Manual Trigger**: Displays total backup storage size badge (MB/KB) and a one-tap "Back Up Now" manual trigger in the User Accounts screen with live progress.
- **Artist Follow / Following & Block Controls**: Dedicated Follow and Block action buttons on artist profile headers with real-time state updates.
- **Automatic Spotify In-App Login & Token Capture**: Direct in-app Spotify WebView authentication that automatically captures tokens without manual copy-pasting.

### Fixed
- **Playlist Queue Disappearing & Infinite Single-Song Loop Bug**: Resolved issue where playing a playlist would suddenly drop all upcoming tracks and endlessly loop a single song in "listen again" mode. Ensured all playlist media items load synchronously into the playback engine and preserved timeline snapshots during dual-player transitions.
- **Artist Profile Clutter & Jukebox Elimination**: Cleaned artist profile content to strictly show original songs, official music videos, and verified albums by the target artist. Filtered out 1–2 hour jukebox compilations, non-stop mashups, full albums, and unrelated third-party uploads.
- **Online Search Screen Empty State**: Fixed blank search discovery by displaying pre-warmed "Trending" and "Latest Releases" filter chips.
- **Fullscreen Video Landscape Lock**: Ensured tapping the fullscreen button in video player locks the container in true horizontal landscape orientation without vertical letterboxing or orientation jitter.
- **Ultra-Low Playback Latency**: Optimized initial playback start buffer down to 500ms for instant audio playback start.

## [0.11.1] - 2026-09-03
### Added
- **One-Tap Bulk Playlist Download**: Added a dedicated Download button to the top action bar of playlist screens, allowing users to queue and download all songs in a playlist in a single tap with live toast confirmation.
- **Curated Spotify Playlists & Top Songs ("Best Songs")**: Unlocked Spotify-curated playlists (e.g., "Best Songs of 2023/2024", "Discover Weekly", "Daily Mix") and automatically fetched user top tracks ("Your Top Songs (All-Time)" and "Your Top Songs (Recent / 2024-2025)") directly into the importable playlist list.
- **Direct Spotify Access Token & `sp_dc` Session Input**: Added a direct token/cookie input option in Spotify Settings allowing manual login using OAuth Access Tokens or Web Player `sp_dc` cookies.

### Fixed
- **Video Player Fullscreen Button Overlap**: Moved the in-artwork Fullscreen button to the bottom-right corner (`BottomEnd`), completely separating it from the top-bar video toggle button and eliminating misclicks.
- **Video Toggle Loading Spinner**: Live visual loading spinner on the full player video toggle button remains clearly visible throughout stream resolution and buffering.
- **Zero-Glitch Horizontal Video Fullscreen**: Replaced Activity-level orientation flipping with a stable full-screen landscape container, completely eliminating app twist, screen jitter, and portrait flip-back loops. Includes dedicated playback controls (play/pause, previous, next, quality picker, exit fullscreen).
- **Library Album "Not Found" & Wrong Album Resolution**: Resolved Navigation Compose path argument URL encoding issue for album IDs. Added composite metadata fallback (`album_meta|id|title|artist`) ensuring albums open their exact tracks reliably without failing or fetching unrelated albums.

## [0.11.0] - 2026-09-02
### Added
- **Constrained Square Artwork Video Player**: Inline video playback is strictly constrained within the square album cover boundaries without expanding vertically or obscuring bottom playback controls.
- **Video Toggle Loading Spinner**: Live visual loading spinner directly on the player video button while streams buffer or switch.
- **Clean In-Player Video Surface & Explicit Quality Selector**: In-line square video removes all cluttered playback icons and displays only auto-hiding (5s) Quality selector (4K 2160p, 1080p Full HD, 720p HD, 480p SD, 360p) and Fullscreen controls.
- **Landscape / Horizontal Fullscreen Mode**: Fullscreen video opens in true horizontal landscape orientation with full playback controls, scrub bar, and quality picker.
- **Home Release Radar (Last 30 Days)**: Replaced Moods with a dedicated Release Radar section featuring fresh releases from the last 30 days, sorted descending with exact release date badges ("Today", "Yesterday", "1 Sep", "30 Aug").
- **Instant Trending Search**: Pre-warmed search discovery displaying trending playlists and trending songs instantly without blank delay or cluttered artist bubbles.
- **Enhanced Song Recognition**: Added Cancel button while listening, instant playback handoff with sheet auto-dismiss, and Listen Again / Skip controls.
- **Default Downloads Library Tab & Resilient Album Loading**: Library opens to Downloads tab by default and guarantees album metadata and song resolution without "Album not found" errors.
- **Spotify Web Player Token & `sp_dc` Cookie Authentication**: Seamless in-app WebView session capture and direct `sp_dc` cookie authentication bypassing Spotify 403 / 25-user Development Mode quotas.

## [0.10.9] - 2026-09-02
### Added
- **Social Reel & Video Audio Recognition**: Share Instagram Reels and YouTube Shorts directly to VYBE to automatically analyze the background audio and recognize the exact song and matched timestamp offset (e.g., "Matched at 1:10").
- **Timestamp Offset Playback ("Play from Matched Time")**: Choose between "Play from beginning (0:00)" or "Play from matched timestamp (e.g. 1:10)" to jump straight to the viral reel chorus.
- **Ambient Recognition Auto-Dismiss & Player Handoff**: Tapping "Play in VYBE" on ambiently recognized songs or reel audio immediately dismisses the recognition sheet and opens the full player.

## [0.10.8] - 2026-09-02
### Added
- **Playlist Radio & Auto-Continuation**: When a playlist finishes, seamless recommended continuation songs matching the playlist's artists, genre, and style start playing without altering the saved playlist.
- **Account-Linked Settings Backup & Restore**: Automatic encrypted backup of settings, EQ configurations, and preferences linked to the user account with prompt for "Restore Backup", "Back up this phone", or "Skip".
- **Artwork-Container Video Player & Player Video Button**: Embedded video playback strictly within square artwork container with instant video toggle button in full player.
- **Artist Follow & Block System**: Follow and block actions on Artist Detail screens with full state persistence and exclusion of blocked artists from autoplay/moods.
- **Smart AI Equalizer**: Auto-adapts EQ presets based on music genre with Data Saver awareness.
- **Quick Picks Long-Press Sheet**: Context bottom sheet for download, playlist, queue, artist, album, and sharing.
- **Installation Telemetry & Deduped Active Device Counter**: Privacy-safe App Set ID hashing and heartbeat tracking.

### Fixed
- **Moods Playlists Loading**: Resolved empty state issue for all moods (Chill, Happy, Workout, Focus, Romance, Sad, Party, Relax, Sleep) with fallback queries.
- **Exact Search & Artist Verification**: Prioritized official audio and official music videos in search results (e.g. "295 Sidhu Moose Wala"), showing verified artist profile and official video.
- **Spotify 403 & Session Handling**: Clear user error guidance and graceful degradation for Spotify Developer Mode limitations and followed playlists.
- **Audio Focus & Call Auto-Resume**: Smooth auto-resume on call disconnect and media abandonment (Instagram Reels, VLC).
- **Home UI & Splash Theme-Awareness**: Left-aligned VYBE logo and clean light/dark splash rendering.

## [0.10.5] - 2026-09-01
### Added
- Exact minute-level overnight update notification hours in onboarding, About, and a one-time upgrade prompt.
- Embedded in-player YouTube video mode with current-position handoff, artwork backdrop, controls, and fullscreen support.
- Pull-to-refresh discovery plus searchable Spotify, data-saver, haptics, and update-schedule settings.

### Changed
- Quick Picks and recommendations refresh their presentation each session while YouTube Music chart order remains authoritative.
- Ambient recognition now captures a longer normalized sample and attempts multiple fingerprint windows for quieter recordings.
- The VYBE status-bar notification mark uses a tightly fitted source asset for better legibility in Android's fixed icon slot.

### Fixed
- Spotify sign-in uses the device WebView identity, detects blank pages, and verifies playlist synchronization before reporting success.
- Exact shared YouTube IDs now recover real title, artist, and high-resolution artwork even when Music metadata is unavailable.
- Night update alerts no longer offer an unrestricted Anytime mode; manual checks still bypass the schedule.
- Settings search results now expose and open more relevant destinations reliably.

## [0.10.4] - 2026-08-31
### Added
- First-run update schedule choice with a recommended 8:00 PM–6:00 AM window or anytime checks; manual checks remain immediate.
- Dedicated Songs and Videos sections in online search.
- In-app video playback for official video results, starting at the current audio position.

### Added in the modern-device release
- Added ambient song recognition with microphone capture, exact VYBE matching, cancellation, and clear processing/result states.

### Changed
- Release APKs now target modern 64-bit Android devices only (`arm64-v8a`); the legacy 32-bit APK is no longer produced.

### Fixed
- Linked the native recognition engine with an embedded static C++ runtime so it loads reliably without a separate shared runtime library.
- Preserved existing voice search while keeping ambient recognition lifecycle-safe and independent.

## [0.10.3] - 2026-08-31
### Changed
- Reworked the recognition stack and removed its previous native fingerprint runtime.

### Fixed
- Rebuilt Spotify authentication as a complete in-app login experience with progress, callback, and synchronization states.
- Fixed black Spotify login screens on affected Samsung Android System WebView renderers.
- Added login timeout detection, WebView renderer recovery, HTTP/SSL error handling, and in-app retry.
- Preserved secure OAuth Authorization Code with PKCE and exact HTTPS callback validation.
- Fixed AI keys being saved against a stale provider and cleared stale provider cooldowns when a key changes.
- Made lyric translation and English-script conversion tolerant of provider line formatting while preserving every displayed lyric line.
- Exact VYBE share links now include the provider song ID and metadata; YouTube Music links resolve and play by exact video ID inside VYBE.
- Kept New Releases & Release Radar visible with its strict recent-release filtering.

## [0.10.2] - 2026-08-30
### Fixed
- Permanently fixed false update offers and the “Downloaded update is not newer” loop.
- Switched update discovery to a machine-readable manifest with Android `versionCode` as the authoritative comparison.
- Adopted monotonic release code `10200`, safely above all previous VYBE builds.
- Added APK SHA-256, package-name, version-code, and signing-certificate verification before installation.
- Added cache-busting update checks so replaced or stale GitHub assets are never selected.

## [0.9.2] - 2026-08-29
### Added
- Verified VYBE App Links at `vybetune.vercel.app/watch?v=...` for exact-song sharing and direct in-app playback.
- Added the website fallback and Android Digital Asset Links declaration for users who do not yet have VYBE installed.

### Fixed
- YouTube Music search now requests the dedicated Songs filter and ranks exact title/artist matches before loose recommendations.
- Search requests run concurrently for faster exact and enriched results.
- Fixed album/artist navigation, library multi-selection, daily recommendations, branded theme presentation, and account sync details.

## [0.9.0] - 2026-08-28
### Fixed
- **Splash Screen Logo**: Fixed the app launch splash logo being cropped/zoomed in on open, now displayed with proper safe margins and centering.
- **Home Top Bar Brand Logo**: Restored VYBE header logo to its proper prominent, crisp size on the Home screen.
- **Crash Fixes**: Fixed crash when opening Appearance Settings and About Screen caused by XML bitmap resource resolution in Compose.
- **Batch Downloads & Selection**: Fixed and polished multi-selection downloading and album actions.

## [0.8.6] - 2026-08-28
### Added
- Download pause, resume, retry and cancel handling with persistent progress.
- Expanded queue, album, playlist and sleep-timer actions.

### Fixed
- Improved online-song metadata, artwork, search matching and end-of-track state handling.
- Improved release stability across Android devices.

## [0.8.5] - 2026-08-25
### Fixed
- Fixed the 'update is not newer than installed version' error which happens when a small hotfix is deployed without a major version bump.
- Fixed a crash in Settings category screen.

## [0.8.4] - 2026-08-25
### Added
- Added 'Report Bug or Suggest Feature' in About screen which links directly to GitHub Issues.
### Changed
- Moved 'Preferred Artists' and 'Don't Suggest Artists' from Music Settings to Artist Settings.
- You can now properly manage, search, add, and remove your preferred and blocked artists to boost or suppress their recommendations in the app.

## [0.8.3] - 2026-08-25
### Fixed
- Fixed Audio Focus auto-resume bug (app will no longer auto-resume if you manually pause it before a phone call/notification).
- Updated VYBE Share Link format to use `https://music.vybe.app/watch?v=ID` layout with backwards compatibility.

## [0.8.2]
### Added
- **Library Pull-to-Refresh Metadata Fix**: Refreshing library tabs like Liked Songs and Playlists now actively resolves and restores "Online Track" placeholder metadata permanently.
- **Advanced Timer Controls**: Reinstated full timer controls with dedicated buttons for Custom Time, Cancel, and Apply, giving users maximum flexibility.

### Fixed
- **Download Notifications**: Both song downloads and app updates now clearly display in the status bar notifications area.
- **Playback Processing Notification**: Eliminated the persistent "Processing playback action" notification that could get stuck when playback paused.
- **Digital Sleep Timer Clock**: Fixed an issue where the live digital countdown clock was missing from the player controls.
- **Immersive Artwork Refinement**: The Immersive Artwork feature has been refined to keep the original album art centered and perfectly visible, while extending a beautifully blurred background with proper top and bottom shading for text readability.

## [0.8.1]
### Added
- **Sleep Timer Countdown**: The sleep timer now displays a live digital countdown on the player screen.
- **Spotify Importer UI**: Replaced the Spotify account login with a simpler Playlist Importer interface.

### Fixed
- **Sleep Timer Options**: Replaced the complex Play Count feature with a straightforward Apply Timer button in the timer options.
- **Spotify Login Error**: Removed the `SPOTIFY_CLIENT_ID` requirement that was causing login errors.

## [0.8.0] - 2026-08-24

### Added
- **Spotify Integration**: Connect your Spotify account to import playlists directly into VYBE.
- Added dedicated Spotify Account dashboard in Settings.

### Fixed
- **Privacy**: Removed personal developer domains from share links; reverted to reliable app links fallback.
- **Metadata**: Resolved issue where Online Track placeholder data was being overwritten over legitimate local metadata during caching.
- **Lyrics**: Fixed a 400 Bad Request network crash when searching lyrics with empty queries (Suggestions OFF).

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.22] - 2026-08-24
### Added
- **Artwork**: Added loading indicators when fetching album artwork.
### Fixed
- **Playback**: Fixed an issue where original metadata was overwritten.
- **Lyrics**: Fixed a network crash (400 Bad Request) when Lyrics Suggestions were turned off.
- **Sleep Timer**: Extracted Sleep Timer into its own dedicated menu button in the main player.
- **Recommendations**: Moods are now dynamically curated based on your listening habits.
- **Recommendations**: Blocked Artists now correctly filter out of generated daily mixes.
- **Search**: Improved typo-tolerance search ranking to heavily prioritize exact matches.
- **Settings**: Refined category icon colors for a richer, premium look.

## [0.7.21] - 2026-08-23
### Added
- **Search Enhancements**: YouTube Music search results are now cleanly categorized, with a dedicated section and tab just for "Videos", separating them from standard songs.
### Removed
- **UI Architecture**: Completely removed the experimental "Liquid Glass" App UI Style setting as it caused performance and layout issues. The app now strictly uses the polished Material UI.

## [0.7.20] - 2026-08-23
### Fixed
- **Library Crash Fix**: Fixed a critical crash (`IllegalArgumentException: Key was already used`) that occurred when switching between tabs (like Folders/Albums) in the Library after playing music, which was caused by duplicate unique IDs in the listening history grid.

## [0.7.19] - 2026-08-23
### Changed
- **Onboarding Improvements**: Made critical permissions (Media, Notifications, and Battery Optimization) strictly mandatory. Users can no longer skip these during the initial setup, preventing unexpected crashes later. Optional preferences (Theme, Backup, Layouts) remain skippable.
### Fixed
- **Notification Fix**: Fixed an issue where tapping the media notification in the status bar would unnecessarily trigger the player sheet to slide open. It now correctly returns you to your previous state in the app without unwanted actions.

## [0.7.18] - 2026-08-23
### Changed
- **UI Enhancements**: Upgraded the Playlist and Artist detail screens by replacing standard rectangular buttons with a sleek row of three floating action buttons (Play, Shuffle, Loop) shaped with `RoundedStarShape`.
### Fixed
- **App Launch/Setup Crash**: Fixed a critical race condition that caused the app to crash or freeze on many devices immediately after completing the onboarding setup. Navigation to the Home screen now safely waits for DataStore commits.
- **Release Stability**: Added comprehensive ProGuard and R8 rules to prevent aggressive code stripping for Hilt, Room DAOs, ExoPlayer/Media3, and WorkManager, which caused invisible crashes on fresh installs.
- **Sync Safety**: Wrapped initial library full sync in a safety block so that localized sync failures do not block app setup.

## [0.7.17] - 2026-08-23
### Added
- **Search History**: Added a Search History UI that displays previous search queries when the search bar is empty.
### Changed
- **Branding Updates**: Removed the old Beta clean-install disclaimer on startup. Replaced the Beta button in the top bar with "Smoothy Play" text.
### Fixed
- **Queue Deletion Crash**: Fixed a `NumberFormatException` crash when attempting to delete online songs from the queue by safely filtering non-local IDs.
- **Song Info Sheet**: Online songs no longer display the "Delete from device" option, preventing unsupported actions.

## [0.7.16] - 2026-08-23
### Fixed
- **Keystore Crash Fix**: Fixed crashes on devices with broken MasterKey/EncryptedSharedPreferences by safely falling back to standard SharedPreferences.

## [0.7.15] - 2026-08-23
### Fixed
- **Immersive UI Fix**: Fixed the visibility of the immersive artwork gradient behind the top action bar on album screens.

## [0.7.10] - 2026-08-22

### Fixed
- Added release-safe recovery for legacy, incompatible, or corrupted local databases that could trap the app in an immediate startup crash loop.
- Added automatic recovery for a corrupted settings DataStore instead of crashing before the first screen appears.
- Release upgrades now rebuild only when local persistence cannot be opened safely; healthy databases and settings remain untouched.

## [0.7.9] - 2026-08-22

### Fixed
- Existing installations now migrate safely from database version 42 instead of crashing during startup.
- Added automated migration coverage through the current database version to prevent release-only upgrade failures.

## [0.7.8] - 2026-08-22

### Fixed
- Search and Home song menus now open online album and artist pages instead of disabling Go to Album.
- Missing YouTube Music album browse IDs are resolved on demand from the song metadata.
- Podcast and episode results are excluded while legitimate music audio results remain available.

### Changed
- Download notifications are silent and appear only after a real audio transfer starts, followed by a completion notification after the file is saved.

## [0.7.7] - 2026-08-22

### Added
- Live in-app and notification download progress, with retry feedback for failures.
- List/grid switching across Downloads, Songs, Albums, Artists, Playlists, and Liked.
- Listening-history fallbacks for Songs, Albums, and Artists when the device library is empty.

### Changed
- Search now filters podcast episodes, shows artist profiles, preserves relevant related songs, and expands complete artist catalogues.
- YouTube Music landscape artwork is presented in a square frame with a blurred background fill.
- Player title and artist labels now navigate to their album and artist pages, including an artist picker for collaborations.

## [0.7.6] - 2026-08-22

### Added
- In-app GitHub Releases update checks, APK download progress, and Android installer handoff.
- Volume-up resume after playback was automatically paused at zero media volume.
- A dedicated in-app Changelog screen under About.

### Changed
- Scrollbars now default to off while remaining user-configurable.
- Synchronized lyric scrolling now reacts quickly instead of visually lagging behind the audio.
- Spotify public-playlist imports show live processed/total progress and support paginated large playlists.

### Fixed
- Online artist search results retain their real YouTube Music browse IDs.
- Artist profiles now display and play their Top Songs instead of showing only a song count.
- Spotify public-link parsing no longer exposes the raw `No value for state` error.
- Spotify account sign-in was removed while public URL import remains available.

## [0.7.5-beta] - 2026-06-13

### Added
- **Google Drive:** Added Google Drive support and improved player lifecycle management.
- **AI Lyrics:** Integrated AI lyrics translation logic in `LyricsStateHolder` and user preferences.
- **Gemma:** Deleted old Gemini model IDs and integrated Gemma model support.
- **Wear OS:** Added wear lyrics translation/romanization preferences and album art background.
- **Diagnostics:** Added a lag diagnostic tool.
- **Search:** Added multi-selection support to the Search screen.
- **UI:** Added outlined button style for AOD screen.
- **Connectivity:** Added support for HTTP URLs on local-network Navidrome and Jellyfin hosts.
- **Localization:** Added Arabic and Turkish language support, and unrecognized languages.

### Changed
- **Battery Optimization:** Drastically reduced battery consumption via audio offload and adaptive UI polling.
- **Queue System:** Refactored shuffle, queue reordering, and playback orchestration to `QueueStateHolder` using explicit queue indices.
- **Transitions & Animations:** Implemented Material 3 Expressive motion curves for player, queue sheet, and screen transitions.
- **Architecture:** Decomposed `MusicService` and modularized `PlayerViewModel` state listeners.
- **Library Sync:** Optimized library sync with throttled scans and faster artwork loading.
- **Database:** Migrated database to version 42 and updated Navidrome schema.
- **Equalizer:** Added "Save New" action and improved layout.
- **Localization:** Refactored app localization, resource cleanup, and UI text wrapping.
- **Dependencies:** Bumped dependencies including `kotlinx-collections-immutable`, `okhttp`, and Gradle plugins.

### Fixed
- **Playback:** Resolved buffering issues, song skipping lags, and unnecessary recompositions during playback.
- **Media Store Sync:** Improved MediaStore URI resolution, external song deletion, and Android 11+ storage volume resolution.
- **Lyrics & Metadata:** Fixed Chinese lyrics detection, pinyin tone suffixes, and batch metadata/artwork editing consistency.
- **Wear OS:** Resolved memory issues and state persistence.
- **UI:** Fixed marquee text fade glitches, navigation bar corner behavior, blur issues, scrollbar bugs, and layout/padding insets.
- **Other:** Fixed backup playlist update issues and startup AI provider errors.

### New Contributors
- @YtMechnij made their first contribution in https://github.com/theovilardo/PixelPlayer/pull/2106
- @juinc made their first contribution in https://github.com/theovilardo/PixelPlayer/pull/2109
- @ZL114514 made their first contribution in https://github.com/theovilardo/PixelPlayer/pull/2159
- @aliabbasov99 made their first contribution in https://github.com/theovilardo/PixelPlayer/pull/2262
- @Hisham-Alzamzami made their first contribution in https://github.com/theovilardo/PixelPlayer/pull/2335


## [0.7.0-beta] - 2026-05-25

### Added
- **Wear OS:** Music transfer, local playback, queue synchronization, and remote control from the watch.
- **AI:** Groq AI and OpenRouter (experimental) with token optimization and AI-powered playlist generation.
- **Cloud & Streaming:** Jellyfin support.
- Direct song synchronization from server albums in Navidrome.
- Standardized branding for NetEase Music.
- **Lyrics:** Synchronized translation with a dedicated toggle and Kugou LRC format support.
- Text alignment customization and improvements to TTML parsing.
- Advanced romanization for Japanese characters.
- **UI/UX:** Redesigned queue sheet and "Recently Played" pills with a dynamic palette.
- Marquee support for long titles and a compact mode for the navigation bar.
- New horizontal timeline for monthly statistics and multi-artist support.
- **Telegram:** Native support for topics, playlist display, and reactive updates.

### Changed
- **Audio Engine:** Complete overhaul with support for MIDI, improvements to ALAC/M4A/Opus, and decoder optimization (including Samsung-specific decoders).
- **Energy Efficiency:** Drastically reduced battery consumption and thermal optimization through UI task gates.
- **Database and Cache:** Massive optimizations to queries, cover art cache controller v3, and support for Scoped Storage.
- **Startup:** Improved load times through optimized generation of Baseline Profiles.
- Project license changed from MIT to Proprietary License.

### Fixed
- **Playback:** Fixed stuttering in Opus/MP3, errors in ReplayGain during crossfades, and flickering during album art changes.
- **Navigation:** Fixed navigation loops in Telegram and improved screen entry/exit animations.
- **Stability:** Eliminated crashes on Android 12+, fixed memory leaks (ANRs), and improved exception handling in background services.
- **Security:** CI hardening, encryption of cloud storage credentials, and media server access control.

### Localization
- 🇪🇸 **Spanish** | 🇫🇷 **French** | 🇷🇺 **Russian**
- 🇨🇳 **Simplified Chinese** | 🇮🇩 **Indonesian** | 🇮🇹 **Italian** | 🇩🇪 **German**

## [0.6.0-beta] - 2026-03-05

### Added
- Added Android Auto support through Media3 `MediaLibraryService`.
- Added Wear OS companion support, including watch transfer and playback controls.
- Added cloud provider expansions: Telegram playlist management, NetEase sync improvements, QQ Music integration, Subsonic/Navidrome, and Google Drive streaming (WIP).
- Added a modernized backup/restore system (v3), account management, and persistent queue restoration.
- Added smarter lyrics workflows (manual fallback search + storage refactor), Recently Played, and new multi-selection flows (songs/albums/playlists).
- Added home and UI customization features: collage patterns, quick settings tiles, expressive scrollbar refinements, and new widget styles.

### Changed
- Reworked player architecture and interaction model (unified player sheet refactors, predictive back handling, gesture tuning).
- Redesigned key surfaces including Lyrics, Cast, Artist, Genre, and Daily Mix experiences.
- Refined library/search/navigation behavior with safer navigation APIs and better state restoration.
- Improved audio compatibility and metadata handling (JAudioTagger fallback, URI handling, surround/noisy behavior).
- Expanded integration UX across Telegram/NetEase/QQ login and sync flows.

### Fixed
- Fixed multiple queue/shuffle edge cases (anchored shuffle, start-at-zero shuffle, queue synchronization).
- Fixed playback interruption behavior when headphones disconnect and resolved foreground service start restrictions.
- Fixed Cast-related crash cases and improved cast reliability.
- Fixed Sleep Timer UI issues, files tab navigation, album artist crash, and state-sync regressions in settings/reorder flows.
- Fixed release build stability (`R8`) and numerous UI polish issues across bottom sheets and controls.

### Performance
- Reduced recompositions and state overhead across Player, Library, Queue, and detail screens.
- Improved startup behavior (eliminated blank flash and deferred heavy Telegram native loading off main thread).
- Optimized folder/genre/artist loading, bottom navigation responsiveness, and gesture fluidity.
- Reduced CPU/main-thread pressure and improved service/widget runtime efficiency.
- Reduced APK size using ABI splits, downloadable fonts, and SDK cleanup.

### New Contributors
- @ThatOneCalculator
- @ryan7zoom
- @LarveyOfficial
- @Dv1101
- @Sincere-Bhattarai

## [0.5.0-beta] - 2026-01-14

### Added
- Implemented 10-band Equalizer and effects suite (feat: @theovilardo)
- Added M3U playlist import/export support (feat/fix: @lostf1sh, @theovilardo)
- Integrated Deezer API for artist images (feat: @lostf1sh)
- Added Gemini AI model selection, system prompt settings, and AI playlist entry point (feat: @lostf1sh, @theovilardo)
- Added sync offset support for lyrics and multi-strategy remote search (feat/fix: @lostf1sh, @theovilardo)
- Added Baseline Profiles for improved performance (feat/fix: @theovilardo, @google-labs-julesbot)
- Added support for custom playlist covers

### Changed
- **Material 3 Expressive UI**: Modernized Settings, Stats, Player, Bottom Sheets, and dialogs (refactor: @theovilardo, @lostf1sh)
- **Library Sync**: Rebuilt initial sync flow with phase-based progress reporting and linear indicators (feat: @lostf1sh)
- **Settings Architecture**: Introduced category sub-screens and improved navigation handling (refactor/fix: @theovilardo)
- **Queue & Player**: Decoupled queue updates from scroll animations, added animated queue scrolling (feat/fix: @lostf1sh, @theovilardo)
- Improved widget previews and case-insensitive sorting logic (feat/fix: @lostf1sh, @google-labs-julesbot)

### Fixed
- Fixed casting stability, queue transitions, and reduced latency (fix: @theovilardo)
- Fixed delayed content rendering and unwanted collapses in Player Sheet (fix/refactor: @theovilardo)
- Fixed reordering issues in queue
- General crash fixes and minor UX improvements (fix: @lostf1sh, @theovilardo)

## [0.4.0-beta] - 2025-12-15

### Added
- Major navigation redesign
- New file explorer for choosing source directories
- Landscape mode (thanks to "leave this blank for now")
- New Connectivity and casting functionalities
- Seamless continuity between remote devices
- Gapless transition between songs
- Crossfade
- New Custom Transitions feature (only for playlists)
- Keep playing after closed the app
- UI Optimizations
- Improved stats feature
- Redesigned Queue control with more features
- Improved different filetypes support for playing and metadata editing
- Improved permission controller
- Minor bug fixes

## [0.3.0-beta] - 2025-10-28

### What's new
- Introduced a richer listening stats hub with deeper insights into your sessions.
- Launched a floating quick player to instantly open and preview local files.
- Added a folders tab with a tree-style navigator and playlist-ready view.

### Improvements
- Refined the overall Material 3 UI for a cleaner and more cohesive experience.
- Smoothed out animations and transitions across the app for more fluid navigation.
- Enhanced the artist screen layout with richer details and polish.
- Upgraded DailyMix and YourMix generation with smarter, more diverse selections.
- Strengthened the AI assistant to deliver more relevant playback suggestions.
- Improved search relevance and presentation for faster discovery.
- Expanded support for a broader range of audio file formats.

### Fixes
- Resolved metadata quirks so song details stay accurate everywhere.
- Restored notification shortcuts so they reliably jump back into playback.

## [0.2.0-beta] - 2024-09-15

### Added
- Chromecast support for casting audio from your device (temporarily disabled).
- In-app changelog to keep you updated on the latest features.
- Improved lyrics search
- Support for .LRC files, both embedded and external.
- Offline lyrics support.
- Synchronized lyrics (synced with the song).
- New screen to view the full queue.
- Reorder and remove songs from the queue.
- Mini-player gestures (swipe down to close).
- Added more material animations.
- New settings to customize the look and feel.
- New settings to clear the cache.

### Changed
- Complete redesign of the user interface.
- Complete redesign of the player.
- Performance improvements in the library.
- Improved application startup speed.
- The AI now provides better results.

### Fixed
- Fixed various bugs in the tag editor.
- Fixed a bug where the playback notification was not clearing.
- Fixed several bugs that caused the app to crash.

## [0.1.0-beta] - 2024-08-30

### Added
- Initial beta release of PixelPlayer Music Player.
- Local music scanning and playback (MP3, FLAC, AAC).
- Background playback using a foreground service and Media3.
- Modern UI with Jetpack Compose, Material 3, and Dynamic Color support.
- Music library organization by songs, albums, and artists.
- Home screen widget for music control.
- Real-time audio waveform visualization.
- Built-in tag editor for song metadata.
- AI-powered features using Gemini.
- Smooth in-app permission handling.
# VYBE 0.7.12 — 23 August 2026

- Fixed the duplicate artist-history key crash.
- Preserved square artwork and centered landscape thumbnails over a blurred fill.
- Restored original album title, artist, header artwork, and track thumbnails.
- Added automatic GitHub update alerts with 1-hour, tomorrow, and skip-version controls.
## [0.12.1] - 2026-09-07
### Fixed
- Preserved the complete autoplay queue when an expired online stream URL is refreshed, and suppressed the misleading recoverable source-error popup.
- Ensured online radio never inherits repeat-one and added an artist-related continuation fallback when the provider returns only the seed track.
- Made Search discovery appear instantly from a persistent snapshot and removed its duplicate refresh/loading indicator.
- Hardened remote album lookup using exact provider IDs plus normalized title-and-artist fallback matching.
- Removed `YouTube Music` placeholder artist metadata when a provider artist is available.
- Enforced a canonical, deduplicated last-10 internal playback cache while removing the Cache folder from Library navigation.

### Added
- Added New Finds before Quick Picks, Listen Again, and Your Mix on Home.
- Added a persisted last-successful-backup time and an automatic account settings backup at 8:00 AM daily.
- Added the signed-in YouTube Music profile photo to Accounts.
## [0.12.2] - 2026-09-07
### Fixed
- Disabled Android Auto Backup, automatic cloud restore, OEM device-transfer restore, and retained-data uninstall behavior for VYBE.
- A true uninstall followed by reinstall now starts with empty listening history, stats, recommendations, discovery snapshots, queues, and caches unless the user explicitly signs in and chooses an in-app restore.
- Manual and account-based VYBE backup remain available and user-controlled.
