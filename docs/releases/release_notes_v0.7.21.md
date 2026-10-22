### Added
- **Search Enhancements**: YouTube Music search results are now cleanly categorized, with a dedicated section and tab just for "Videos", separating them from standard songs.
### Removed
- **UI Architecture**: Completely removed the experimental "Liquid Glass" App UI Style setting as it caused performance and layout issues. The app now strictly uses the polished Material UI.

### Fixed
- **Library Crash Fix**: Fixed a critical crash (IllegalArgumentException: Key was already used) that occurred when switching between tabs (like Folders/Albums) in the Library after playing music, which was caused by duplicate unique IDs in the listening history grid.

### Changed
- **Onboarding Improvements**: Made critical permissions (Media, Notifications, and Battery Optimization) strictly mandatory. Users can no longer skip these during the initial setup, preventing unexpected crashes later. Optional preferences (Theme, Backup, Layouts) remain skippable.
### Fixed
- **Notification Fix**: Fixed an issue where tapping the media notification in the status bar would unnecessarily trigger the player sheet to slide open. It now correctly returns you to your previous state in the app without unwanted actions.

### Changed
- **UI Enhancements**: Upgraded the Playlist and Artist detail screens by replacing standard rectangular buttons with a sleek row of three floating action buttons (Play, Shuffle, Loop) shaped with RoundedStarShape.
### Fixed
- **App Launch/Setup Crash**: Fixed a critical race condition that caused the app to crash or freeze on many devices immediately after completing the onboarding setup. Navigation to the Home screen now safely waits for DataStore commits.
- **Release Stability**: Added comprehensive ProGuard and R8 rules to prevent aggressive code stripping for Hilt, Room DAOs, ExoPlayer/Media3, and WorkManager, which caused invisible crashes on fresh installs.
- **Sync Safety**: Wrapped initial library full sync in a safety block so that localized sync failures do not block app setup.

### Added
- **Search History**: Added a Search History UI that displays previous search queries when the search bar is empty.
### Changed
- **Branding Updates**: Removed the old Beta clean-install disclaimer on startup. Replaced the Beta button in the top bar with "Smoothy Play" text.
### Fixed
- **Queue Deletion Crash**: Fixed a NumberFormatException crash when attempting to delete online songs from the queue by safely filtering non-local IDs.
- **Song Info Sheet**: Online songs no longer display the "Delete from device" option, preventing unsupported actions.

### Fixed
- **Keystore Crash Fix**: Fixed crashes on devices with broken MasterKey/EncryptedSharedPreferences by safely falling back to standard SharedPreferences.

### Fixed
- **Immersive UI Fix**: Fixed the visibility of the immersive artwork gradient behind the top action bar on album screens.
