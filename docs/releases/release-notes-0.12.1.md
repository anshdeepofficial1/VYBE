## VYBE 0.12.1

### Playback and queue
- Online source retries refresh only the failed item and preserve the complete autoplay timeline.
- Recoverable source refreshes no longer show a misleading playback-error popup.
- VYBE Radio adds artist-related fallback tracks and prevents repeat-one from blocking continuation.

### Faster discovery
- Search opens from its last valid discovery snapshot immediately and refreshes in the background.
- Removed the duplicate Search refresh spinner.
- Added **New Finds** before **Quick Picks**, **Listen Again**, and **Your Mix**.

### Library and metadata
- Playback cache retains exactly the latest 10 unique tracks internally; its Library folder is removed.
- Albums resolve by exact remote ID with normalized title-and-artist fallback for migrated entries.
- Placeholder `YouTube Music` artist labels are removed.

### Account and backup
- Accounts displays the signed-in YouTube Music profile photo.
- Last successful backup time is shown.
- Automatic settings backup runs daily at 8:00 AM; manual backup remains available.
