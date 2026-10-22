## What's New in VYBE v0.12.0

### 🔄 In-App Updater Size Check Fix
- **Size Verification Fix**: Resolved the issue where updating inside the app failed with `"Downloaded update size does not match"`.
- **Resilient Verification**: The updater now validates against the server's HTTP content length and package integrity checks, eliminating failures caused by manifest size discrepancies.
- **Silent Download Progress**: Update download notifications are now completely silent (`IMPORTANCE_LOW` with sounds disabled). No more notification chime on every percentage increment! A single notification alerts you once the APK is downloaded and ready to install.

### 📝 Permanent Lyrics Caching Across Sessions
- **Disk Cache for All Tracks**: Lyrics fetched or translated for local and online tracks (including YouTube Music streams) are permanently stored in safe disk cache files.
- **No Redundant Re-fetching**: Once lyrics are fetched for a track, they stay cached. Re-opening the app or playing the song weeks or months later will load lyrics instantly from cache without fetching again.
- **Safe Keying**: Lyrics cache handles sanitized IDs and artist-title fallback pairs to ensure rock-solid persistence.

### 🎛️ Seamless "Reset Imported Lyrics" Experience
- **Sheet Stays Open**: Resetting imported lyrics no longer abruptly closes the player lyrics sheet.
- **Instant Reset & Re-fetch**: Immediately clears existing stored lyrics and launches the search options dialog so you can effortlessly pick new or alternative lyrics on the spot.

---

## 📲 Mobile Downloads

| Platform | Download Link | Description |
|---|---|---|
| **Android** | [**VYBE-Android-0.12.0-arm64-v8a.apk**](https://github.com/anshdeepofficial/VYBE/releases/download/v0.12.0/VYBE-Android-0.12.0-arm64-v8a.apk) | Signed release APK (versionCode = 12000) for Android 8.0+ |

---
**Full Changelog**: https://github.com/anshdeepofficial/VYBE/compare/v0.11.9...v0.12.0
