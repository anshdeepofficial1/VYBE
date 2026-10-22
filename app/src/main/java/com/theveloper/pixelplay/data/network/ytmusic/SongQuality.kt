package com.theveloper.pixelplay.data.network.ytmusic

import com.theveloper.pixelplay.data.model.Song

/**
 * Terms for uploads that are somebody else's recording with a filter, watermark or lyric sheet
 * pasted on top. YouTube Music happily returns these for a plain song search, and they are never
 * the recording a listener asked for.
 */
private val NOVELTY_TERMS = listOf(
    "slowed", "reverb", "8d audio", "8d version", "8d music", "8d song", "8d beat",
    "8d sound", "3d audio", "3d version", "nightcore", "chipmunk", "sped up", "speed up",
    "speedup", "bass boosted", "bass boost", "mashup", "mega mix", "megamix",
    "lo-fi", "lofi", "remix", "dhol", "club mix", "trap mix", "dhol mix",
    "instrumental", "karaoke", "ringtone", "tiktok", "tik tok", "whatsapp status",
    "status video", "lyrics video", "lyric video", "official video", "music video",
    "full song", "jukebox", "non stop", "nonstop", "8-bit", "8bit", "jatt vibe",
)

/** Spoken-word and non-music catalogue entries. */
private val NON_MUSIC_TERMS = listOf(
    "podcast", "episode", "episodes", "audiobook", "audio book",
    "interview", "talk show", "press conference", "keynote", "news",
)

/**
 * Titles the catalogue invents when a track's metadata was never cached. They are placeholders, not
 * song names, and a shelf that shows them looks broken.
 */
private val PLACEHOLDER_TITLES = setOf(
    "online track", "unknown", "unknown track", "unknown title",
    "untitled", "null", "none", "no title",
)

/**
 * Devotional / discourse content. It lives in the regional YouTube Music Home shelves but is not
 * something a listener asked for, so it must never reach a recommendation shelf or a chart.
 */
private val DEVOTIONAL_TERMS = listOf(
    "aarti", "arti ", "aarti ", "bhajan", "bhajans", "chalisa", "kirtan",
    "devotional", "pravachan", "katha", "mantra", "shloka", "stotram",
    "puja ", "pooja ", "havan", "satsang", "dhun", "vandana",
)

/**
 * True when [song] is a real recording rather than a video, a fan re-upload, a spoken-word entry or
 * devotional discourse.
 *
 * [query] is the listener's own text. A novelty term only marks a row as junk when the listener did
 * not ask for it, so searching "instrumental" or "lofi" still works while searching a song name
 * cannot surface a slowed/reverb/8D copy of someone else's upload.
 */
internal fun isRealSongEntry(song: Song, query: String = ""): Boolean {
    if (song.title.isBlank() || song.artist.isBlank()) return false

    // Placeholder metadata invented by the cache when the real title was never stored.
    if (song.title.trim().lowercase() in PLACEHOLDER_TITLES) return false

    // Never let a video take the place of a song.
    if (song.isMusicVideo ||
        song.contentUriString.startsWith("yt_video:") ||
        song.id.startsWith("yt_video_")
    ) {
        return false
    }

    val metadata = "${song.title} ${song.artist} ${song.album}".lowercase()
    val normalizedQuery = query.trim().lowercase()

    if (NON_MUSIC_TERMS.any(metadata::contains)) return false
    if (DEVOTIONAL_TERMS.any(metadata::contains)) return false

    return NOVELTY_TERMS.none { term -> metadata.contains(term) && !normalizedQuery.contains(term) }
}
