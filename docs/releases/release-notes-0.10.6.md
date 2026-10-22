## VYBE 0.10.6

### Fixed
- The status-bar and notification VYBE mark is now an always-white monochrome asset in both light and dark system modes; other app logos are unchanged.
- YouTube Music and JioSaavn playback stays bound to the exact provider track ID, preventing stale URLs or same-title matches from producing the wrong audio.
- Library album and artist cards now open through explicit local, remote, or metadata lookup identities instead of incorrectly reporting that visible content was unavailable.

### Package
- Modern Android devices only (`arm64-v8a`).
- Android version code: `10600`.
