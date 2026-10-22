## v0.7.20
- **UI Architecture**: Added support for a new "Liquid Glass" Apple-like UI style utilizing the `Haze` library for real-time background glassmorphism. You can toggle this feature via Settings -> Appearance -> App UI Style.
- **Library Crash Fix**: Fixed a critical crash (`IllegalArgumentException: Key was already used`) that occurred when switching between tabs (like Folders/Albums) in the Library after playing music, which was caused by duplicate unique IDs in the listening history grid.
