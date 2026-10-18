package com.theveloper.pixelplay.data.network.ytmusic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.theveloper.pixelplay.data.database.OnlineSongCacheDao
import com.theveloper.pixelplay.data.database.OnlineSongCacheEntity
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.repository.OnlineMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

data class YouTubeLibraryStats(
    val library: Int = 0,
    val liked: Int = 0,
    val playlists: Int = 0,
    val history: Int = 0,
)

enum class YouTubeSyncState {
    IDLE,
    SYNCING,
    SYNCED,
    ERROR
}

@Singleton
class YouTubeAccountManager @Inject constructor(
    @ApplicationContext context: Context,
    private val accountApi: YouTubeMusicAccountApi,
    private val onlineSongCacheDao: OnlineSongCacheDao,
    private val playlistRepository: PlaylistPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val onlineMusicRepository: OnlineMusicRepository,
) {
    private val prefs: SharedPreferences = YouTubeAuthPreferences.create(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isLoggedInFlow = MutableStateFlow(prefs.getBoolean(KEY_IS_LOGGED_IN, false))
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedInFlow.asStateFlow()

    private val _accountNameFlow = MutableStateFlow(
        prefs.getString(KEY_ACCOUNT_NAME, "YouTube Music User") ?: "YouTube Music User"
    )
    val accountNameFlow: StateFlow<String> = _accountNameFlow.asStateFlow()

    private val _accountAvatarUrlFlow = MutableStateFlow(
        prefs.getString(KEY_ACCOUNT_AVATAR_URL, null)
    )
    val accountAvatarUrlFlow: StateFlow<String?> = _accountAvatarUrlFlow.asStateFlow()

    private val _accountIdentityFlow = MutableStateFlow(prefs.getString(KEY_ACCOUNT_IDENTITY, null).orEmpty())
    val accountIdentityFlow: StateFlow<String> = _accountIdentityFlow.asStateFlow()

    private val _interestLabelsFlow = MutableStateFlow(readPersistedInterestLabels())
    val interestLabelsFlow: StateFlow<List<String>> = _interestLabelsFlow.asStateFlow()

    private val _syncStateFlow = MutableStateFlow(YouTubeSyncState.IDLE)
    val syncStateFlow: StateFlow<YouTubeSyncState> = _syncStateFlow.asStateFlow()

    private val _syncRevisionFlow = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC_TIME, 0L))
    val syncRevisionFlow: StateFlow<Long> = _syncRevisionFlow.asStateFlow()

    private val _syncedCountFlow = MutableStateFlow(prefs.getInt(KEY_SYNCED_COUNT, 0))
    val syncedCountFlow: StateFlow<Int> = _syncedCountFlow.asStateFlow()
    private val _libraryStatsFlow = MutableStateFlow(
        YouTubeLibraryStats(
            library = prefs.getInt(KEY_STAT_LIBRARY, 0),
            liked = prefs.getInt(KEY_STAT_LIKED, 0),
            playlists = prefs.getInt(KEY_STAT_PLAYLISTS, 0),
            history = prefs.getInt(KEY_STAT_HISTORY, 0),
        )
    )
    val libraryStatsFlow: StateFlow<YouTubeLibraryStats> = _libraryStatsFlow.asStateFlow()

    private val _syncEnabledPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val syncEnabledPlaylistIds: StateFlow<Set<String>> = _syncEnabledPlaylistIds.asStateFlow()

    private val syncMutex = Mutex()
    private var scheduledSync: Job? = null
    @Volatile private var syncRequestedWhileRunning = false

    init {
        scope.launch {
            playlistRepository.userPlaylistsFlow.collect { playlists ->
                refreshEnabledPlaylistIds(playlists)
                if (_isLoggedInFlow.value) {
                    recalculateStoredStats(playlists)
                }
            }
        }
    }

    private suspend fun recalculateStoredStats(playlists: List<Playlist>) {
        val cacheCount = runCatching { onlineSongCacheDao.getCount() }.getOrDefault(0)
        val ytPlaylists = playlists.filter {
            it.source.startsWith(YOUTUBE_PLAYLIST_SOURCE_PREFIX) &&
                !it.source.endsWith(":LM") &&
                !it.source.endsWith(":HISTORY")
        }
        val likedPlaylist = playlists.firstOrNull { it.id == "yt_sync_liked_songs" || it.source == "YOUTUBE_MUSIC:LM" }
        val historyPlaylist = playlists.firstOrNull { it.id == "yt_sync_recent_history" || it.source == "YOUTUBE_MUSIC:HISTORY" }
        val likedCount = likedPlaylist?.songIds?.size ?: prefs.getInt(KEY_STAT_LIKED, 0)
        val historyCount = historyPlaylist?.songIds?.size ?: prefs.getInt(KEY_STAT_HISTORY, 0)
        val playlistsCount = if (ytPlaylists.isNotEmpty()) ytPlaylists.size else prefs.getInt(KEY_STAT_PLAYLISTS, 0)
        val libraryCount = if (cacheCount > 0) cacheCount else prefs.getInt(KEY_STAT_LIBRARY, 0)
        val totalSynced = if (cacheCount > 0) cacheCount else prefs.getInt(KEY_SYNCED_COUNT, libraryCount)

        _syncedCountFlow.value = totalSynced
        _libraryStatsFlow.value = YouTubeLibraryStats(
            library = libraryCount,
            liked = likedCount,
            playlists = playlistsCount,
            history = historyCount,
        )
        prefs.edit()
            .putInt(KEY_SYNCED_COUNT, totalSynced)
            .putInt(KEY_STAT_LIBRARY, libraryCount)
            .putInt(KEY_STAT_LIKED, likedCount)
            .putInt(KEY_STAT_PLAYLISTS, playlistsCount)
            .putInt(KEY_STAT_HISTORY, historyCount)
            .apply()
    }

    fun loginWithAuth(cookie: String) {
        if (cookie.isBlank() || _syncStateFlow.value == YouTubeSyncState.SYNCING) return
        sync(cookie, persistLoginOnSuccess = true)
    }

    fun logout() {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_AUTH_COOKIE)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_AVATAR_URL)
            .remove(KEY_ACCOUNT_IDENTITY)
            .remove(KEY_INTEREST_LABELS)
            .remove(KEY_SYNCED_COUNT)
            .remove(KEY_STAT_LIBRARY)
            .remove(KEY_STAT_LIKED)
            .remove(KEY_STAT_PLAYLISTS)
            .remove(KEY_STAT_HISTORY)
            .apply()
        _isLoggedInFlow.value = false
        _accountNameFlow.value = "Disconnected"
        _accountAvatarUrlFlow.value = null
        _accountIdentityFlow.value = ""
        _interestLabelsFlow.value = emptyList()
        _syncStateFlow.value = YouTubeSyncState.IDLE
        _syncedCountFlow.value = 0
        _libraryStatsFlow.value = YouTubeLibraryStats()
    }

    fun syncLibrary() {
        val cookie = prefs.getString(KEY_AUTH_COOKIE, null)
        if (cookie.isNullOrBlank()) {
            _syncStateFlow.value = YouTubeSyncState.ERROR
            return
        }
        sync(cookie, persistLoginOnSuccess = false)
    }

    /** Refreshes the connected account automatically without making the user press Sync. */
    fun syncIfDue(force: Boolean = false) {
        if (!_isLoggedInFlow.value || _syncStateFlow.value == YouTubeSyncState.SYNCING) return
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        if (!force && System.currentTimeMillis() - lastSync < AUTO_SYNC_INTERVAL_MS) return
        syncLibrary()
    }

    /** Sends a completed VYBE listening session to the connected YouTube Music history. */
    suspend fun recordPlayback(songId: String, listenedMs: Long, durationMs: Long): Boolean {
        if (!_isLoggedInFlow.value || listenedMs < MIN_REMOTE_HISTORY_MS) return false
        val cookie = prefs.getString(KEY_AUTH_COOKIE, null)?.takeIf(String::isNotBlank) ?: return false
        val videoId = songId
            .removePrefix("yt_video_")
            .removePrefix("yt_")
            .takeIf { YOUTUBE_VIDEO_ID.matches(it) }
            ?: return false
        return runCatching {
            accountApi.recordPlayback(cookie, videoId, listenedMs, durationMs)
        }.onFailure { error ->
            Log.w(TAG, "Could not write playback to YouTube Music history", error)
        }.getOrDefault(false)
    }

    fun setPlaylistSyncEnabled(playlistId: String, enabled: Boolean) {
        val overrides = readSyncOverrides().toMutableMap()
        overrides[playlistId] = enabled
        writeSyncOverrides(overrides)
        scope.launch {
            refreshEnabledPlaylistIds(playlistRepository.getPlaylistsOnce())
            if (enabled && _isLoggedInFlow.value) syncLibrary()
        }
    }

    fun requestPlaylistSync(playlistId: String) {
        if (!_isLoggedInFlow.value || playlistId !in _syncEnabledPlaylistIds.value) return
        scheduledSync?.cancel()
        scheduledSync = scope.launch {
            delay(1_200)
            syncLibrary()
        }
    }

    suspend fun loadRemoteSettingsBackup(): Pair<String, String?> {
        val cookie = prefs.getString(KEY_AUTH_COOKIE, null)
            ?: error("Connect YouTube Music before restoring settings.")
        val identity = _accountIdentityFlow.value.ifBlank {
            accountApi.loadSnapshot(cookie).accountIdentity.also { resolved ->
                prefs.edit().putString(KEY_ACCOUNT_IDENTITY, resolved).apply()
                _accountIdentityFlow.value = resolved
            }
        }
        return identity to accountApi.loadSettingsBackup(cookie)
    }

    suspend fun saveRemoteSettingsBackup(payload: String) {
        val cookie = prefs.getString(KEY_AUTH_COOKIE, null)
            ?: error("Connect YouTube Music before backing up settings.")
        accountApi.saveSettingsBackup(cookie, payload)
    }

    /** Records a remote tombstone before the local row disappears, then retries on next sync. */
    suspend fun onLocalPlaylistDeleting(playlist: Playlist) {
        if (!isPlaylistSyncEnabled(playlist)) return
        val remoteId = remoteIdOf(playlist) ?: return
        addPendingRemoteDelete(remoteId)
        val cookie = prefs.getString(KEY_AUTH_COOKIE, null) ?: return
        runCatching { accountApi.deletePlaylist(cookie, remoteId) }
            .onSuccess { removePendingRemoteDelete(remoteId) }
    }

    private fun sync(cookie: String, persistLoginOnSuccess: Boolean) {
        scope.launch {
            if (!syncMutex.tryLock()) {
                syncRequestedWhileRunning = true
                return@launch
            }
            try {
                _syncStateFlow.value = YouTubeSyncState.SYNCING
                runCatching { reconcile(cookie).also { persistSnapshot(it) } }
                    .onSuccess { snapshot ->
                    if (persistLoginOnSuccess) {
                        prefs.edit()
                            .putBoolean(KEY_IS_LOGGED_IN, true)
                            .putString(KEY_AUTH_COOKIE, cookie)
                            .putString(KEY_ACCOUNT_NAME, snapshot.accountName)
                            .putString(KEY_ACCOUNT_AVATAR_URL, snapshot.accountAvatarUrl)
                            .putString(KEY_ACCOUNT_IDENTITY, snapshot.accountIdentity)
                            .apply()
                    }
                    _isLoggedInFlow.value = true
                    _accountNameFlow.value = snapshot.accountName
                    _accountAvatarUrlFlow.value = snapshot.accountAvatarUrl
                    _accountIdentityFlow.value = snapshot.accountIdentity
                    _syncStateFlow.value = YouTubeSyncState.SYNCED
                    val syncedAt = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_LAST_SYNC_TIME, syncedAt).apply()
                    _syncRevisionFlow.value = syncedAt
                    }
                    .onFailure { error ->
                    Log.e(TAG, "Authenticated YouTube Music sync failed", error)
                    _syncStateFlow.value = YouTubeSyncState.ERROR
                    if (persistLoginOnSuccess) _isLoggedInFlow.value = false
                    }
            } finally {
                syncMutex.unlock()
                if (syncRequestedWhileRunning) {
                    syncRequestedWhileRunning = false
                    sync(cookie, persistLoginOnSuccess = false)
                }
            }
        }
    }

    private suspend fun reconcile(cookie: String): YouTubeAccountSnapshot {
        processPendingRemoteDeletes(cookie)
        var snapshot = accountApi.loadSnapshot(cookie)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        val remoteById = snapshot.playlists.associate { it.first.id to it }
        var remoteChanged = false

        playlistRepository.getPlaylistsOnce().forEach { local ->
            if (!isPlaylistSyncEnabled(local)) return@forEach
            val remoteId = remoteIdOf(local)
            if (remoteId == null) {
                val createdId = accountApi.createPlaylist(cookie, local.name, resolveVideoIds(local))
                playlistRepository.updatePlaylist(
                    local.copy(source = "$YOUTUBE_PLAYLIST_SOURCE_PREFIX$createdId")
                )
                remoteChanged = true
                return@forEach
            }

            val remoteEntry = remoteById[remoteId]
            if (remoteEntry == null) {
                if (lastSync > 0L && local.lastModified <= lastSync) {
                    playlistRepository.deletePlaylist(local.id)
                } else {
                    val createdId = accountApi.createPlaylist(cookie, local.name, resolveVideoIds(local))
                    playlistRepository.updatePlaylist(
                        local.copy(source = "$YOUTUBE_PLAYLIST_SOURCE_PREFIX$createdId")
                    )
                    remoteChanged = true
                }
                return@forEach
            }

            if (lastSync > 0L && local.lastModified > lastSync) {
                val (remote, remoteTracks) = remoteEntry
                val desired = resolveVideoIds(local)
                if (remote.name != local.name) {
                    accountApi.renamePlaylist(cookie, remoteId, local.name)
                    remoteChanged = true
                }
                if (remoteTracks.map { it.videoId } != desired) {
                    accountApi.replacePlaylistContents(cookie, remoteId, remoteTracks, desired)
                    remoteChanged = true
                }
            }
        }

        if (remoteChanged) snapshot = accountApi.loadSnapshot(cookie)
        return snapshot
    }

    private suspend fun resolveVideoIds(playlist: Playlist): List<String> {
        val directBySongId = playlist.songIds.associateWith { id ->
            id.removePrefix(YOUTUBE_SONG_ID_PREFIX)
                .takeIf { id.startsWith(YOUTUBE_SONG_ID_PREFIX) && it.isNotBlank() }
        }
        if (directBySongId.values.all { it != null }) return directBySongId.values.filterNotNull()

        val songs = musicRepository.getSongsByIds(playlist.songIds).first().associateBy { it.id }
        return playlist.songIds.mapNotNull { songId ->
            directBySongId[songId] ?: songs[songId]?.let { song ->
                runCatching {
                    onlineMusicRepository.searchSongs("${song.title} ${song.displayArtist}")
                        .map { candidate ->
                            candidate to matchScore(
                                song.title,
                                song.displayArtist,
                                candidate.title,
                                candidate.displayArtist,
                            )
                        }
                        .maxByOrNull { it.second }
                        ?.takeIf { it.second >= MIN_LOCAL_MATCH_SCORE }
                        ?.first
                        ?.id
                        ?.removePrefix(YOUTUBE_SONG_ID_PREFIX)
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
        }.distinct()
    }

    private fun matchScore(
        wantedTitle: String,
        wantedArtist: String,
        title: String,
        artist: String,
    ): Int {
        val wt = normalize(wantedTitle)
        val wa = normalize(wantedArtist)
        val ct = normalize(title)
        val ca = normalize(artist)
        return (if (wt == ct) 100 else if (ct.contains(wt) || wt.contains(ct)) 60 else 0) +
            (if (wa == ca) 40 else if (ca.contains(wa) || wa.contains(ca)) 20 else 0)
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private suspend fun persistSnapshot(snapshot: YouTubeAccountSnapshot) {
        val allTracks = buildList {
            snapshot.playlists.forEach { addAll(it.second) }
            addAll(snapshot.likedSongs)
            addAll(snapshot.recentHistory)
        }.distinctBy { it.videoId }

        if (allTracks.isNotEmpty()) {
            onlineSongCacheDao.upsertAll(allTracks.map { track ->
                val song = track.toSong()
                OnlineSongCacheEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumArtUrl = song.albumArtUriString,
                    duration = song.duration,
                    path = song.path,
                    contentUri = song.contentUriString,
                    mimeType = song.mimeType,
                )
            })
        }

        val existingPlaylists = playlistRepository.getPlaylistsOnce()
        snapshot.playlists.forEach { (remote, tracks) ->
            val existing = existingPlaylists.firstOrNull { remoteIdOf(it) == remote.id }
            if (existing != null && !isPlaylistSyncEnabled(existing)) return@forEach
            val songIds = tracks.map { "yt_${it.videoId}" }.distinct()
            if (existing != null) {
                playlistRepository.updatePlaylist(
                    existing.copy(
                        name = remote.name,
                        songIds = songIds,
                        coverImageUri = remote.coverUrl ?: existing.coverImageUri,
                        source = "$YOUTUBE_PLAYLIST_SOURCE_PREFIX${remote.id}",
                    )
                )
            } else {
                playlistRepository.createPlaylist(
                    name = remote.name,
                    songIds = songIds,
                    coverImageUri = remote.coverUrl,
                    customId = "yt_sync_${remote.id}",
                    source = "$YOUTUBE_PLAYLIST_SOURCE_PREFIX${remote.id}",
                )
            }
        }
        if (snapshot.likedSongs.isNotEmpty()) {
            playlistRepository.createPlaylist(
                name = "YouTube Music — Liked Songs",
                songIds = snapshot.likedSongs.map { "yt_${it.videoId}" }.distinct(),
                coverImageUri = snapshot.likedSongs.firstOrNull()?.thumbnailUrl,
                customId = "yt_sync_liked_songs",
                source = "YOUTUBE_MUSIC:LM",
            )
        }
        if (snapshot.recentHistory.isNotEmpty()) {
            playlistRepository.createPlaylist(
                name = "YouTube Music — Recent History",
                songIds = snapshot.recentHistory.map { "yt_${it.videoId}" }.distinct(),
                coverImageUri = snapshot.recentHistory.firstOrNull()?.thumbnailUrl,
                customId = "yt_sync_recent_history",
                source = "YOUTUBE_MUSIC:HISTORY",
            )
        }
        val stats = YouTubeLibraryStats(
            library = allTracks.size,
            liked = snapshot.likedSongs.distinctBy { it.videoId }.size,
            playlists = snapshot.playlists.size,
            history = snapshot.recentHistory.distinctBy { it.videoId }.size,
        )
        _syncedCountFlow.value = allTracks.size
        _libraryStatsFlow.value = stats
        prefs.edit()
            .putInt(KEY_SYNCED_COUNT, allTracks.size)
            .putInt(KEY_STAT_LIBRARY, stats.library)
            .putInt(KEY_STAT_LIKED, stats.liked)
            .putInt(KEY_STAT_PLAYLISTS, stats.playlists)
            .putInt(KEY_STAT_HISTORY, stats.history)
            .apply()
        updateInterestLabels(snapshot)
        refreshEnabledPlaylistIds(playlistRepository.getPlaylistsOnce())
    }

    private fun updateInterestLabels(snapshot: YouTubeAccountSnapshot) {
        val scores = linkedMapOf<String, Int>()
        fun addArtist(rawArtist: String, weight: Int) {
            rawArtist
                .split(Regex("\\s*(?:,|&|•|/|\\bx\\b|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*", RegexOption.IGNORE_CASE))
                .map(String::trim)
                .filter { it.length in 2..50 && !it.equals("Various Artists", ignoreCase = true) }
                .forEach { artist ->
                    val existing = scores.keys.firstOrNull { it.equals(artist, ignoreCase = true) }
                    val label = existing ?: artist
                    scores[label] = (scores[label] ?: 0) + weight
                }
        }

        snapshot.recentHistory.forEach { addArtist(it.artist, 4) }
        snapshot.likedSongs.forEach { addArtist(it.artist, 3) }
        snapshot.playlists.forEach { (_, tracks) -> tracks.forEach { addArtist(it.artist, 1) } }

        val labels = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
            .take(MAX_INTEREST_LABELS)
        prefs.edit().putString(KEY_INTEREST_LABELS, labels.joinToString(INTEREST_SEPARATOR)).apply()
        _interestLabelsFlow.value = labels
    }

    private fun readPersistedInterestLabels(): List<String> =
        prefs.getString(KEY_INTEREST_LABELS, null)
            ?.split(INTEREST_SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinctBy { it.lowercase() }
            ?.take(MAX_INTEREST_LABELS)
            .orEmpty()

    private fun remoteIdOf(playlist: Playlist): String? = playlist.source
        .takeIf { it.startsWith(YOUTUBE_PLAYLIST_SOURCE_PREFIX) }
        ?.removePrefix(YOUTUBE_PLAYLIST_SOURCE_PREFIX)
        ?.takeIf { it.isNotBlank() && it != "LM" && it != "HISTORY" }

    private fun isPlaylistSyncEnabled(playlist: Playlist): Boolean =
        readSyncOverrides()[playlist.id] ?: (remoteIdOf(playlist) != null)

    private fun refreshEnabledPlaylistIds(playlists: List<Playlist>) {
        _syncEnabledPlaylistIds.value = playlists
            .filter(::isPlaylistSyncEnabled)
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun readSyncOverrides(): Map<String, Boolean> {
        val raw = prefs.getString(KEY_PLAYLIST_SYNC_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { key -> put(key, json.optBoolean(key)) } }
        }.getOrDefault(emptyMap())
    }

    private fun writeSyncOverrides(values: Map<String, Boolean>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_PLAYLIST_SYNC_OVERRIDES, json.toString()).apply()
    }

    private fun addPendingRemoteDelete(remoteId: String) {
        prefs.edit().putStringSet(
            KEY_PENDING_REMOTE_DELETES,
            prefs.getStringSet(KEY_PENDING_REMOTE_DELETES, emptySet()).orEmpty() + remoteId,
        ).apply()
    }

    private fun removePendingRemoteDelete(remoteId: String) {
        prefs.edit().putStringSet(
            KEY_PENDING_REMOTE_DELETES,
            prefs.getStringSet(KEY_PENDING_REMOTE_DELETES, emptySet()).orEmpty() - remoteId,
        ).apply()
    }

    private suspend fun processPendingRemoteDeletes(cookie: String) {
        prefs.getStringSet(KEY_PENDING_REMOTE_DELETES, emptySet()).orEmpty().toList().forEach { remoteId ->
            runCatching { accountApi.deletePlaylist(cookie, remoteId) }
                .onSuccess { removePendingRemoteDelete(remoteId) }
        }
    }

    private companion object {
        const val TAG = "YouTubeAccountManager"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_ACCOUNT_AVATAR_URL = "account_avatar_url"
        const val KEY_ACCOUNT_IDENTITY = "account_identity"
        const val KEY_INTEREST_LABELS = "interest_labels"
        const val KEY_AUTH_COOKIE = "auth_cookie"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
        const val KEY_PLAYLIST_SYNC_OVERRIDES = "playlist_sync_overrides"
        const val KEY_PENDING_REMOTE_DELETES = "pending_remote_playlist_deletes"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_STAT_LIBRARY = "stat_library"
        const val KEY_STAT_LIKED = "stat_liked"
        const val KEY_STAT_PLAYLISTS = "stat_playlists"
        const val KEY_STAT_HISTORY = "stat_history"
        const val YOUTUBE_PLAYLIST_SOURCE_PREFIX = "YOUTUBE_MUSIC:"
        const val YOUTUBE_SONG_ID_PREFIX = "yt_"
        const val MIN_LOCAL_MATCH_SCORE = 80
        const val MAX_INTEREST_LABELS = 8
        const val MIN_REMOTE_HISTORY_MS = 5_000L
        const val AUTO_SYNC_INTERVAL_MS = 30L * 60L * 1000L
        const val INTEREST_SEPARATOR = "\u001F"
        val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
