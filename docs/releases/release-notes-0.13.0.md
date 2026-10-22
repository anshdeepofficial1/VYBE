## VYBE 0.13.0 — Major Discovery Update

### Home and Search
- Guest Search opens immediately with useful Explore categories while live YouTube Music results load.
- Signed-in recommendations automatically replace guest discovery when available.
- Fixed cold-start Home requests losing valid Quick Picks when continuation paging exceeded the timeout.
- Added Speed dial with persistent Home show/hide and reorder controls.
- Removed the misleading empty Your Mix card for new guest profiles.
- Provider shelves, collections, charts, releases, videos, artists, community playlists, moods, and long listens stay in provider order when supplied.

### Player and catalog
- Search keeps Songs, Videos, Albums, and Artists separated and preserves exact remote IDs.
- Verified music videos switch in the artwork area using the same playback session and timestamp.
- Multi-page album and Home continuation parsing is covered by focused regression tests.

### Package
- One universal signed APK for Android 8.0+ with ARM64, ARMv7, x86, and x86_64 support.
