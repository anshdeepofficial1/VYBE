## What's New in VYBE v0.11.9

### 🛠️ Search Button Crash Resolved
- **Fixed ClassCastException**: Completely resolved the LinkedTreeMap cannot be cast to Song error occurring when tapping the Search button.
- **Pure In-Memory Discovery Cache**: Removed unsafe Gson reflection on disk-cached track lists in SearchDiscoveryCache. Discovery results are now held natively in memory, preventing type erasure and deserialization mismatches under R8 minification.
- **Automatic Legacy Cache Cleanup**: Automatically purges any legacy corrupted disk cache from previous sessions on first launch, ensuring a clean state for all users.
- **Defensive Error Handling**: Wrapped initial discovery cache loading in OnlineSearchViewModel with safety fallbacks so search opens instantaneously without crashing.

### 📋 In-App "Release Notes" Screen
- **Modern Release Notes Screen**: Replaced the legacy Changelog with a dedicated **Release Notes** screen accessible directly from Settings & About.
- **Material 3 Formatting**: Redesigned layout with version badges, release dates, structured bullet points, and full recent changelog history (including 0.11.9, 0.11.8, and 0.11.6).

### 🎵 Pure Audio & YouTube Music Experience (v0.11.8 Recap)
- Pure audio playback with all video players and toggles removed.
- Strict YouTube Music songs-only querying (no OMVs or non-music video uploads).
- Ultra-fast 1–2s Home & Daily Mix refresh with parallelized coroutine requests.
- Persistent library sync statistics for Library, Liked, Playlists, and History.
- Cleaner Accounts layout with Linked Services positioned at the top followed by Backup.

---

## 📲 Mobile Downloads

| Platform | Download Link | Description |
|---|---|---|
| **Android** | [**VYBE-Android-0.11.9-arm64-v8a.apk**](https://github.com/anshdeepofficial/VYBE/releases/download/v0.11.9/VYBE-Android-0.11.9-arm64-v8a.apk) | Signed release APK (ersionCode = 11900) for Android 8.0+ |

---
**Full Changelog**: https://github.com/anshdeepofficial/VYBE/compare/v0.11.8...v0.11.9
