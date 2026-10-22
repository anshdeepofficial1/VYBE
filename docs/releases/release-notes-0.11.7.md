## VYBE 0.11.7

### Pure YouTube Music audio
- Removed inline/fullscreen video playback and video-only Search/Artist sections.
- YouTube Music search now requests song results only.

### Faster and more reliable
- Home and Daily Mix discovery requests now run concurrently with bounded timeouts.
- Synced Library, Liked, Playlist, History, and total track counts persist across restarts and reconcile with stored data.
- Search album rows now use collision-safe identities to avoid duplicate-key crashes.

### Cleaner Accounts layout
- Linked Services is now the first section, followed by Settings & Data Backup.

### Device support
- This release contains a single signed `arm64-v8a` APK for modern Android devices.
