package com.theveloper.pixelplay.data.network.ytmusic

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeRemotePlaylist(
    val id: String,
    val name: String,
    val coverUrl: String?,
)

data class YouTubeAccountSnapshot(
    val accountName: String,
    val accountAvatarUrl: String?,
    val accountIdentity: String,
    val playlists: List<Pair<YouTubeRemotePlaylist, List<YouTubeTrack>>>,
    val likedSongs: List<YouTubeTrack>,
    val recentHistory: List<YouTubeTrack>,
)

@Singleton
class YouTubeMusicAccountApi @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /**
     * Records a play using the authenticated account. YouTube Music normally exposes an
     * add-to-history feedback token in the `next` response. Some account/client variants
     * expose only playback tracking URLs, so the tracking ping is a compatible fallback.
     */
    suspend fun recordPlayback(
        cookie: String,
        videoId: String,
        listenedMs: Long,
        durationMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val session = createSession(cookie)
        val next = runCatching {
            post(
                session,
                "next",
                JSONObject()
                    .put("videoId", videoId)
                    .put("playlistId", "RDAMVM$videoId")
                    .put("isAudioOnly", true),
            )
        }.getOrNull()
        val feedbackToken = next?.let { findFirstString(it, "addToHistoryFeedbackToken") }
        if (!feedbackToken.isNullOrBlank()) {
            val accepted = runCatching {
                post(
                    session,
                    "feedback",
                    JSONObject().put("feedbackTokens", JSONArray().put(feedbackToken)),
                )
                true
            }.getOrDefault(false)
            if (accepted) return@withContext true
        }

        val player = runCatching {
            post(session, "player", JSONObject().put("videoId", videoId).put("contentCheckOk", true))
        }.getOrNull() ?: return@withContext false
        val tracking = player.optJSONObject("playbackTracking") ?: return@withContext false
        val baseUrl = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")
            ?.takeIf(String::isNotBlank)
            ?: tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")
                ?.takeIf(String::isNotBlank)
            ?: return@withContext false
        val listenedSeconds = (listenedMs / 1000.0).coerceAtLeast(1.0)
        val durationSeconds = (durationMs / 1000.0).takeIf { it > 0.0 } ?: listenedSeconds
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("st", "0")
            ?.setQueryParameter("et", "${listenedSeconds.coerceAtMost(durationSeconds)}")
            ?.setQueryParameter("cmt", "$listenedSeconds")
            ?.build()
            ?: return@withContext false
        runCatching {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("Cookie", session.cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$ORIGIN/")
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun loadSnapshot(cookie: String): YouTubeAccountSnapshot = withContext(Dispatchers.IO) {
        val session = createSession(cookie)
        val accountMenu = post(session, "account/account_menu", JSONObject())
        val accountName = parseAccountName(accountMenu)
            ?: error("Could not verify the YouTube Music account")
        val accountAvatarUrl = parseAccountAvatarUrl(accountMenu)
        val accountIdentity = parseAccountIdentity(accountMenu)
            ?: sha1("$accountName|${accountAvatarUrl?.substringBefore('=')}")

        val playlistIndex = fetchAllBrowsePages(session, "FEmusic_liked_playlists")
        val remotePlaylists = parsePlaylists(playlistIndex)
            .filterNot { isSettingsBackupPlaylist(it.name) }
        val playlists = remotePlaylists.map { playlist ->
            playlist to runCatching {
                fetchAllBrowsePages(session, "VL${playlist.id}").let(::parseTracks)
            }.getOrDefault(emptyList())
        }
        val likedSongs = runCatching {
            parseTracks(fetchAllBrowsePages(session, "VLLM"))
        }.getOrDefault(emptyList())
        val history = runCatching {
            parseTracks(fetchAllBrowsePages(session, "FEmusic_history"))
        }.getOrDefault(emptyList())

        YouTubeAccountSnapshot(
            accountName = accountName,
            accountAvatarUrl = accountAvatarUrl,
            accountIdentity = accountIdentity,
            playlists = playlists,
            likedSongs = likedSongs,
            recentHistory = history,
        )
    }

    /** Adds tracks to an existing user playlist without removing or reordering remote items. */
    suspend fun addVideosToPlaylist(
        cookie: String,
        playlistId: String,
        videoIds: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        val uniqueIds = videoIds.filter { it.isNotBlank() }.distinct()
        if (uniqueIds.isEmpty()) return@withContext 0
        val session = createSession(cookie)
        uniqueIds.chunked(50).forEach { chunk ->
            val actions = JSONArray().apply {
                chunk.forEach { videoId ->
                    put(JSONObject().apply {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", videoId)
                    })
                }
            }
            post(
                session = session,
                endpoint = "browse/edit_playlist",
                body = JSONObject()
                    .put("playlistId", playlistId)
                    .put("actions", actions),
            )
        }
        uniqueIds.size
    }

    suspend fun createPlaylist(
        cookie: String,
        title: String,
        videoIds: List<String>,
        description: String = "",
    ): String = withContext(Dispatchers.IO) {
        val response = post(
            session = createSession(cookie),
            endpoint = "playlist/create",
            body = JSONObject()
                .put("title", title.trim().ifBlank { "VYBE Playlist" })
                .put("description", description)
                .put("privacyStatus", "PRIVATE")
                .put("videoIds", JSONArray(videoIds.filter { it.isNotBlank() }.distinct())),
        )
        findFirstString(response, "playlistId")
            ?: error("YouTube Music did not return the created playlist ID")
    }

    suspend fun loadSettingsBackup(cookie: String): String? = withContext(Dispatchers.IO) {
        val session = createSession(cookie)
        val index = fetchAllBrowsePages(session, "FEmusic_liked_playlists")
        val parts = parsePlaylists(index)
            .filter { isSettingsBackupPlaylist(it.name) }
            .sortedBy { backupPartNumber(it.name) }
            .mapNotNull { playlist ->
                runCatching {
                    parsePlaylistDescription(fetchAllBrowsePages(session, "VL${playlist.id}"))
                }.getOrNull()
            }
        parts.takeIf { it.isNotEmpty() }?.joinToString(separator = "")
    }

    suspend fun saveSettingsBackup(cookie: String, payload: String) = withContext(Dispatchers.IO) {
        val session = createSession(cookie)
        val existing = parsePlaylists(fetchAllBrowsePages(session, "FEmusic_liked_playlists"))
            .filter { isSettingsBackupPlaylist(it.name) }
            .sortedBy { backupPartNumber(it.name) }
        val chunks = payload.chunked(SETTINGS_BACKUP_CHUNK_SIZE).ifEmpty { listOf("") }
        chunks.forEachIndexed { index, chunk ->
            val title = if (chunks.size == 1) SETTINGS_BACKUP_TITLE else "$SETTINGS_BACKUP_TITLE ${index + 1}"
            val remote = existing.getOrNull(index)
            if (remote == null) {
                createPlaylist(cookie, title, emptyList(), description = chunk)
            } else {
                if (remote.name != title) renamePlaylist(cookie, remote.id, title)
                setPlaylistDescription(session, remote.id, chunk)
            }
        }
        existing.drop(chunks.size).forEach { deletePlaylist(cookie, it.id) }
    }

    private fun setPlaylistDescription(session: SessionConfig, playlistId: String, description: String) {
        val actions = JSONArray().put(
            JSONObject()
                .put("action", "ACTION_SET_PLAYLIST_DESCRIPTION")
                .put("playlistDescription", description)
        )
        post(session, "browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", actions))
    }

    suspend fun deletePlaylist(cookie: String, playlistId: String) = withContext(Dispatchers.IO) {
        post(
            session = createSession(cookie),
            endpoint = "playlist/delete",
            body = JSONObject().put("playlistId", playlistId),
        )
        Unit
    }

    suspend fun renamePlaylist(cookie: String, playlistId: String, title: String) =
        withContext(Dispatchers.IO) {
            val actions = JSONArray().put(
                JSONObject()
                    .put("action", "ACTION_SET_PLAYLIST_NAME")
                    .put("playlistName", title.trim().ifBlank { "VYBE Playlist" })
            )
            post(
                session = createSession(cookie),
                endpoint = "browse/edit_playlist",
                body = JSONObject().put("playlistId", playlistId).put("actions", actions),
            )
            Unit
        }

    /**
     * Replaces a playlist exactly. Removing by setVideoId and then adding in local order is
     * deterministic and also handles duplicate videos correctly.
     */
    suspend fun replacePlaylistContents(
        cookie: String,
        playlistId: String,
        currentTracks: List<YouTubeTrack>,
        desiredVideoIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        val session = createSession(cookie)
        currentTracks.mapNotNull { it.setVideoId }.chunked(50).forEach { setIds ->
            val actions = JSONArray().apply {
                setIds.forEach { setId ->
                    put(JSONObject().put("action", "ACTION_REMOVE_VIDEO").put("setVideoId", setId))
                }
            }
            post(session, "browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", actions))
        }
        desiredVideoIds.filter { it.isNotBlank() }.chunked(50).forEach { videoIds ->
            val actions = JSONArray().apply {
                videoIds.forEach { videoId ->
                    put(JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId))
                }
            }
            post(session, "browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", actions))
        }
    }

    private fun createSession(cookie: String): SessionConfig {
        val page = httpClient.newCall(
            Request.Builder()
                .url(ORIGIN)
                .header("Cookie", cookie)
                .header("User-Agent", USER_AGENT)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("YouTube Music login session is unavailable")
            response.body?.string().orEmpty()
        }
        val apiKey = API_KEY_REGEX.find(page)?.groupValues?.getOrNull(1)
            ?: DEFAULT_API_KEY
        val clientVersion = CLIENT_VERSION_REGEX.find(page)?.groupValues?.getOrNull(1)
            ?: DEFAULT_CLIENT_VERSION
        return SessionConfig(cookie, apiKey, clientVersion, findSapisid(cookie))
    }

    private fun fetchAllBrowsePages(session: SessionConfig, browseId: String): JSONObject {
        val pages = JSONArray()
        var response = post(session, "browse", JSONObject().put("browseId", browseId))
        val visited = mutableSetOf<String>()
        var pageCount = 0
        while (pageCount < MAX_PAGES) {
            pages.put(response)
            pageCount++
            val token = findContinuation(response)
                ?.takeIf { it.isNotBlank() && visited.add(it) }
                ?: break
            response = post(session, "browse", JSONObject().put("continuation", token))
        }
        return JSONObject().put("pages", pages)
    }

    private fun post(session: SessionConfig, endpoint: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", session.clientVersion)
            put("hl", "en")
        }))
        val timestamp = System.currentTimeMillis() / 1000
        val auth = "SAPISIDHASH ${timestamp}_${sha1("$timestamp ${session.sapisid} $ORIGIN")}" 
        val request = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?key=${session.apiKey}&prettyPrint=false")
            .header("Cookie", session.cookie)
            .header("Authorization", auth)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("YouTube Music request failed (${response.code})")
            JSONObject(payload)
        }
    }

    private fun parseAccountName(root: JSONObject): String? {
        findObjects(root, "activeAccountHeaderRenderer").forEach { renderer ->
            renderer.optJSONObject("accountName")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private fun parseAccountAvatarUrl(root: JSONObject): String? {
        findObjects(root, "activeAccountHeaderRenderer").forEach { renderer ->
            renderer.optJSONObject("accountPhoto")
                ?.let(::findLargestThumbnail)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private fun parseAccountIdentity(root: JSONObject): String? {
        findObjects(root, "activeAccountHeaderRenderer").forEach { renderer ->
            findFirstString(renderer, "channelHandle")?.let { return it }
            findFirstString(renderer, "browseId")?.takeIf { it.startsWith("UC") }?.let { return it }
        }
        return null
    }

    private fun parsePlaylistDescription(root: JSONObject): String? {
        val headers = findObjects(root, "musicEditablePlaylistDetailHeaderRenderer") +
            findObjects(root, "musicDetailHeaderRenderer")
        headers.forEach { header ->
            val description = header.optJSONObject("description") ?: return@forEach
            val text = collectRunTexts(description).joinToString("").trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun isSettingsBackupPlaylist(title: String): Boolean =
        SETTINGS_BACKUP_TITLES.any { prefix -> title.startsWith(prefix) }

    private fun backupPartNumber(name: String): Int = name
        .removePrefix(SETTINGS_BACKUP_TITLE)
        .removePrefix(LEGACY_SETTINGS_BACKUP_TITLE)
        .trim()
        .toIntOrNull() ?: 1

    private fun parsePlaylists(root: JSONObject): List<YouTubeRemotePlaylist> {
        val renderers = findObjects(root, "musicTwoRowItemRenderer") +
            findObjects(root, "musicResponsiveListItemRenderer")
        return renderers.mapNotNull { renderer ->
            val browseId = findFirstString(renderer, "browseId") ?: return@mapNotNull null
            val playlistId = when {
                browseId.startsWith("VL") -> browseId.removePrefix("VL")
                browseId.startsWith("PL") -> browseId
                else -> return@mapNotNull null
            }
            val title = firstRunText(renderer.optJSONObject("title"))
                ?: firstFlexColumnText(renderer)
                ?: return@mapNotNull null
            YouTubeRemotePlaylist(playlistId, title, findLargestThumbnail(renderer))
        }.distinctBy { it.id }
    }

    private fun parseTracks(root: JSONObject): List<YouTubeTrack> {
        val renderers = findObjects(root, "musicResponsiveListItemRenderer") +
            findObjects(root, "playlistPanelVideoRenderer")
        return renderers.mapNotNull { renderer ->
            val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
                ?: findFirstString(renderer, "videoId")
                ?: return@mapNotNull null
            val title = firstFlexColumnText(renderer)
                ?: firstRunText(renderer.optJSONObject("title"))
                ?: return@mapNotNull null
            val subtitle = secondFlexColumnRuns(renderer)
            val artist = subtitle.firstOrNull { run ->
                run.second?.startsWith("UC") == true || run.second?.startsWith("MPLA") == true
            }?.first ?: subtitle.firstOrNull()?.first.orEmpty()
            val album = subtitle.firstOrNull { it.second?.startsWith("MPRE") == true }?.first
                ?: "YouTube Music"
            val durationText = collectRunTexts(renderer).lastOrNull { DURATION_REGEX.matches(it) }
            YouTubeTrack(
                videoId = videoId,
                title = title,
                artist = artist.ifBlank { "YouTube Music" },
                album = album,
                durationSeconds = parseDuration(durationText),
                thumbnailUrl = findLargestThumbnail(renderer),
                setVideoId = findFirstString(renderer, "setVideoId"),
            )
        }.distinctBy { it.videoId }
    }

    private fun firstFlexColumnText(renderer: JSONObject): String? = renderer
        .optJSONArray("flexColumns")
        ?.optJSONObject(0)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?.optJSONObject("text")
        ?.let(::firstRunText)

    private fun secondFlexColumnRuns(renderer: JSONObject): List<Pair<String, String?>> {
        val text = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text") ?: return emptyList()
        val runs = text.optJSONArray("runs") ?: return emptyList()
        return buildList {
            for (index in 0 until runs.length()) {
                val run = runs.optJSONObject(index) ?: continue
                val value = run.optString("text").trim()
                if (value == "\u2022") continue
                if (value.isBlank() || value == "•") continue
                add(value to findFirstString(run, "browseId"))
            }
        }
    }

    private fun firstRunText(text: JSONObject?): String? = text
        ?.optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun collectRunTexts(root: Any?): List<String> {
        val results = mutableListOf<String>()
        walk(root) { key, value ->
            if (key == "text" && value is String && value.isNotBlank()) results += value
        }
        return results
    }

    private fun findLargestThumbnail(root: Any?): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        walk(root) { key, value ->
            if (key == "thumbnails" && value is JSONArray) {
                for (index in 0 until value.length()) {
                    val item = value.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    val width = item.optInt("width", index)
                    if (url.isNotBlank() && width >= bestWidth) {
                        bestWidth = width
                        bestUrl = url
                    }
                }
            }
        }
        return bestUrl
    }

    private fun findContinuation(root: Any?): String? {
        fun search(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("continuationCommand")
                        ?.optString("token")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    value.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    value.keys().forEach { key -> search(value.opt(key))?.let { return it } }
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    search(value.opt(index))?.let { return it }
                }
            }
            return null
        }
        return search(root)
    }

    private fun findFirstString(root: Any?, targetKey: String): String? {
        var result: String? = null
        walk(root) { key, value ->
            if (result == null && key == targetKey && value is String && value.isNotBlank()) result = value
        }
        return result
    }

    private fun findObjects(root: Any?, targetKey: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        walk(root) { key, value -> if (key == targetKey && value is JSONObject) results += value }
        return results
    }

    private fun walk(root: Any?, visit: (String, Any?) -> Unit) {
        fun traverse(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    visit(key, child)
                    traverse(child)
                }
                is JSONArray -> for (index in 0 until value.length()) traverse(value.opt(index))
            }
        }
        traverse(root)
    }

    private fun parseDuration(value: String?): Long {
        val parts = value?.split(':')?.mapNotNull(String::toLongOrNull) ?: return 0L
        return parts.fold(0L) { total, part -> total * 60 + part }
    }

    private fun findSapisid(cookie: String): String {
        val values = cookie.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        return values["SAPISID"]
            ?: values["__Secure-3PAPISID"]
            ?: error("The YouTube Music login cookie is incomplete")
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private data class SessionConfig(
        val cookie: String,
        val apiKey: String,
        val clientVersion: String,
        val sapisid: String,
    )

    private companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        const val DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val DEFAULT_CLIENT_VERSION = "1.20240801.01.00"
        const val MAX_PAGES = 50
        const val SETTINGS_BACKUP_TITLE = "VYBE Settings Backup"
        const val LEGACY_SETTINGS_BACKUP_TITLE = "Pixel Player Settings Backup"
        val SETTINGS_BACKUP_TITLES = listOf(SETTINGS_BACKUP_TITLE, LEGACY_SETTINGS_BACKUP_TITLE)
        const val SETTINGS_BACKUP_CHUNK_SIZE = 3_500
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val API_KEY_REGEX = Regex("""\"INNERTUBE_API_KEY\":\"([^\"]+)\"""")
        val CLIENT_VERSION_REGEX = Regex("""\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"""")
        val DURATION_REGEX = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
    }
}
