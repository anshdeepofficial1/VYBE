# VYBE 0.14.1

## Music & metadata
- Artist, album and song credits are now parsed from YouTube Music properly everywhere — no more "YouTube Music" or "Unknown Artist" placeholders in Home, Search, Library, playlists or the player.
- Album and artist pages label their tracks with the real album/artist credit instead of blank or placeholder names.
- Album navigation: "Go to Album" is only offered when an actual album exists; otherwise a clear localized toast tells you there is no album for that song.
- Artist navigation: YouTube namesakes no longer collide — searching an artist now opens the exact-name profile (e.g. the rapper Morell, not the lowercase electronic "morell").
- Player artist rows open the remote YouTube Music profile instead of an empty local page.

## Home shelves
- Quick Picks: history-seed radios are interleaved round-robin and capped per artist, with chart/discovery filler — the shelf no longer turns into a single-artist block.
- Listen Again: your most recently played song first.
- Speed Dial: built from real play counts (song_engagements), so your most-played songs get the tiles; the refresh button still reshuffles for variety.

## Playback & radio
- Tapping any song — Speed Dial, Quick Picks, search results, Library — plays exactly that song and queues a same-artist/similar radio after it instead of the next row.

## UI
- Library > Playlists empty state now shows a proper "No playlist has been created" popup with guidance.
- Accounts: connecting YouTube Music is a single step — the Connect button goes straight to the Google sign-in page.

## Under the hood
- Room upgrade to v46: cached online songs now persist real artist references (name + browse id) and remote album ids, so restored songs keep correct credits and valid navigation.
- Catalogue v3 one-time migration rebuilds cached rows that stored the old placeholder credits.
