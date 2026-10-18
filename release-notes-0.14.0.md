## VYBE 0.14.0 — Instant Playback and Immersive Artwork

### Playback
- Tapping a song starts audio in about one second, cold start included, by racing the preferred stream resolver against its fallbacks concurrently instead of waiting for each one in turn.
- All resolvers share a single-flight resolve cache, so a track is never resolved twice and the tapped song always keeps a free connection.
- The stream liveness probe now sends exactly what the player sends (no `Range` header, YouTube Music `Origin`/`Referer`, Chrome user agent). Mirrors that return a valid-looking URL but 403 without a `Range` request now lose the race instead of winning it and failing a moment later.
- Repeated HTTP 403 recovery loops that stalled playback after the first buffering pass are gone.
- Prefetch is sequential and limited to two tracks so it can never contend with the song you actually tapped.

### Recommendations and search
- Provider ordering is authoritative and results are no longer re-ranked by title text, so searching a Punjabi track can no longer surface an unrelated song with a similar name.
- Wrong-language and wrong-genre recommendations from local score reordering are removed.
- The real Top result (`musicCardShelfRenderer`) is parsed and shown instead of faking it from the first song row.

### Look and feel
- Full App artwork: a single app-wide blurred cover layer sits behind every screen, bar and sheet, with the artwork bleeding through headers, cards, the navigation bar and the player sheet.
- New **Full App artwork** toggle in Settings.
- The old per-screen artwork wash was removed so it can no longer fight the immersive layer.

### Region and storefront
- The storefront country is derived from the connection when no explicit preference is set, with precedence: explicit preference, then connection country, then device region, then India as a last resort.
- Region, account scope and request semantics are part of the Home cache key, so a stale guest-looking response can no longer mask a fixed signed-in path.

### Package
- Signed, R8-minified and resource-shrunk release APK for Android 8.0+ (universal: ARM64, ARMv7, x86, x86_64).
