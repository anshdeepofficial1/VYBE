## What's New in VYBE v0.11.8

### 🎵 Pure Audio Player (Video Features Removed)
- **Zero Video Bloat**: Completely removed video player surfaces, fullscreen video orientation switching, video buffering logic, and player header video toggles.
- **Lightweight & Focused**: VYBE is now a 100% dedicated high-fidelity audio music player with smooth album artwork animations and synchronized lyrics.

### 🎧 YouTube Music Songs Only (No Music Videos)
- **Strict Audio Filtering**: Enforced SEARCH_FILTER_SONGS so search results return actual audio songs instead of video uploads.
- **OMV Elimination**: Official Music Videos (MUSIC_VIDEO_TYPE_OMV) are completely filtered out from searches, discographies, and playback queues.
- **Direct YouTube Music Endpoints**: All streaming metadata and catalog queries strictly route through YouTube Music (music.youtube.com).

### ⚡ 1–2s Ultra-Fast Home Refresh
- **Parallel Network Requests**: Home and Daily Mix discovery (Latest Releases, Trending, and Autoplay Queue candidates) now execute concurrently via Kotlin coroutines.
- **Bounded 1.8s Timeout**: Pull-to-refresh and initial feed loading now finish in **1–2 seconds** instead of the previous 5–10 second delay.

### 📊 Accurate, Persistent Synced Library Counts
- **Reliable Local Accounting**: Synced counts for Library, Liked Tracks, Playlists, and History are now backed by persistent storage and Room database queries.
- **Zero-Count Bug Fixed**: Synced library statistics persist reliably across app restarts and never reset to 0.

### ⚙️ Streamlined Accounts Screen Layout
- **Removed Hero Card**: Eliminated the bulky top "Connected Accounts" hero section.
- **Intuitive Ordering**: The screen now features **Linked Services** right at the top, followed immediately by **Backup & Data Settings**.

### 🛠️ Search Button Crash Resolved
- **Fixed Navigation Crash**: Wrapped speech recognition in safe error handlers and cleaned Dagger/Hilt ViewModel dependencies, eliminating runtime ClassCastException crashes when tapping the search button.

---

## 📲 Mobile Downloads

| Platform | Download Link | Description |
|---|---|---|
| **Android** | [**VYBE-Android-0.11.8-arm64-v8a.apk**](https://github.com/anshdeepofficial/VYBE/releases/download/v0.11.8/VYBE-Android-0.11.8-arm64-v8a.apk) | Signed release APK (ersionCode = 11800) for Android 8.0+ |

---
**Full Changelog**: https://github.com/anshdeepofficial/VYBE/compare/v0.11.6...v0.11.8
