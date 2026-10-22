package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.cache.PlaybackRecentCacheManager
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.repository.OnlineMusicRepository
import com.theveloper.pixelplay.utils.QueueUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles the ViewModel-owned collaborators that the shuffle entry points need so this
 * holder can resolve a song source and dispatch shuffled playback without depending on
 * [PlayerViewModel]. Mirrors the lambda-callback pattern used by [MetadataEditCallbacks].
 *
 * - [scope]            the ViewModel's coroutine scope.
 * - [currentStorageFilter] reads the active storage filter for favorites resolution.
 * - [albums]/[artists] snapshot the in-memory library categories for the "random" pickers.
 * - [playShuffled]     hands the resolved list to the ViewModel's shuffled-playback dispatch.
 */
class ShufflePlaybackCallbacks(
    val scope: CoroutineScope,
    val currentStorageFilter: () -> StorageFilter,
    val albums: () -> List<Album>,
    val artists: () -> List<Artist>,
    val playShuffled: (songs: List<Song>, queueName: String) -> Unit,
)

/**
 * Bundles the ViewModel collaborators that the album/artist play entry points need to
 * dispatch sequential playback and reveal the player sheet.
 */
class PlaybackSourceCallbacks(
    val scope: CoroutineScope,
    val playSongs: (songs: List<Song>, startSong: Song, queueName: String, playlistId: String?) -> Unit,
    val showSheet: () -> Unit,
    /**
     * Appends to the queue that is already playing without restarting the current track.
     * [expectedCurrentId] is the seed that the caller believes is playing; when the listener
     * has moved on since the call started, the append is dropped instead of splicing the new
     * tracks into somebody else's queue.
     */
    val extendQueue: (songs: List<Song>, expectedCurrentId: String?) -> Unit = { _, _ -> },
)

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val onlineMusicRepository: OnlineMusicRepository,
    private val playbackRecentCacheManager: PlaybackRecentCacheManager,
) {

    companion object {
        private const val SHUFFLE_SAMPLE_LIMIT = 500
        private const val ALL_SONGS_SHUFFLED_QUEUE = "All Songs (Shuffled)"
        private const val FAVORITES_SHUFFLED_QUEUE = "Liked Songs (Shuffled)"
    }

    // Original queue order before shuffle (for restoring when unshuffling)
    private var _originalQueueOrder: List<Song> = emptyList()
    val originalQueueOrder: List<Song> get() = _originalQueueOrder
    
    // Original queue name before shuffle
    private var _originalQueueName: String = "None"
    val originalQueueName: String get() = _originalQueueName
    
    /**
     * Store the original queue state before shuffling.
     */
    fun saveOriginalQueueState(queue: List<Song>, queueName: String) {
        _originalQueueOrder = queue.toList()
        _originalQueueName = queueName
    }
    
    /**
     * Set original queue order (for updates during playback).
     */
    fun setOriginalQueueOrder(queue: List<Song>) {
        _originalQueueOrder = queue.toList()
    }
    
    /**
     * Check if original queue is empty.
     */
    fun hasOriginalQueue(): Boolean = _originalQueueOrder.isNotEmpty()
    
    /**
     * Get the original queue for restoring after unshuffle.
     */
    fun getOriginalQueueForRestore(): List<Song> = _originalQueueOrder.toList()
    
    /**
     * Clear the original queue state (e.g., when queue is cleared).
     */
    fun clearOriginalQueue() {
        _originalQueueOrder = emptyList()
        _originalQueueName = "None"
    }

    /**
     * Keeps the pre-shuffle restore snapshot in step with mutations committed to Media3.
     * Without this, disabling shuffle restores an old snapshot and resurrects removed songs.
     */
    fun onQueueItemRemoved(index: Int, songId: String) {
        if (_originalQueueOrder.isEmpty()) return
        val matchingIndex = index
            .takeIf { it in _originalQueueOrder.indices && _originalQueueOrder[it].id == songId }
            ?: _originalQueueOrder.indexOfFirst { it.id == songId }.takeIf { it >= 0 }
            ?: return
        _originalQueueOrder = _originalQueueOrder.toMutableList().apply { removeAt(matchingIndex) }
    }

    fun onQueueItemAdded(song: Song, index: Int? = null) {
        if (_originalQueueOrder.isEmpty()) return
        val insertionIndex = (index ?: _originalQueueOrder.size).coerceIn(0, _originalQueueOrder.size)
        _originalQueueOrder = _originalQueueOrder.toMutableList().apply { add(insertionIndex, song) }
    }

    fun onQueueItemMoved(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _originalQueueOrder.indices || toIndex !in _originalQueueOrder.indices) return
        _originalQueueOrder = _originalQueueOrder.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
    }
    
    /**
     * Create a shuffled version of a queue, keeping the current song at the start.
     * Uses Fisher-Yates via [QueueUtils] for uniform randomness.
     */
    fun createShuffledQueue(
        currentQueue: List<Song>,
        currentSongId: String?
    ): List<Song> {
        if (currentQueue.isEmpty()) return emptyList()

        val currentIndex = currentQueue.indexOfFirst { it.id == currentSongId }
        return if (currentIndex >= 0) {
            QueueUtils.buildAnchoredShuffleQueue(currentQueue, currentIndex)
        } else {
            QueueUtils.fisherYatesCopy(currentQueue)
        }
    }

    /**
     * Prepares a list for shuffled playback.
     * 1. Saves original queue.
     * 2. Picks a random start song.
     * 3. Creates a shuffled list starting with that song.
     */
    fun prepareShuffledQueue(songs: List<Song>, queueName: String): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        saveOriginalQueueState(songs, queueName)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = QueueUtils.buildAnchoredShuffleQueue(songs, startIndex)

        return Pair(shuffledQueue, startSong)
    }

    /**
     * Prepares a list for shuffled playback with a specific start song.
     */
    fun prepareShuffledQueueWithStart(songs: List<Song>, startSong: Song, queueName: String): List<Song> {
        saveOriginalQueueState(songs, queueName)
        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        return QueueUtils.buildAnchoredShuffleQueue(songs, startIndex)
    }

    /**
     * Suspendable variant for large queues.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>, 
        queueName: String, 
        startAtZero: Boolean = false
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        saveOriginalQueueState(songs, queueName)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }

    /* -------------------------------------------------------------------------- */
    /*                       Shuffle / play orchestration                         */
    /* -------------------------------------------------------------------------- */

    /**
     * Shuffles a bounded random sample of the whole library. Loads songs straight from the
     * repository instead of materializing the entire library in memory.
     */
    fun shuffleAll(
        queueName: String = ALL_SONGS_SHUFFLED_QUEUE,
        callbacks: ShufflePlaybackCallbacks
    ) {
        callbacks.scope.launch {
            val randomSongs = musicRepository.getRandomSongs(limit = SHUFFLE_SAMPLE_LIMIT, storageFilter = callbacks.currentStorageFilter())
            if (randomSongs.isNotEmpty()) {
                callbacks.playShuffled(randomSongs, queueName)
            }
        }
    }

    /**
     * Picks and plays a random song by shuffling a bounded random sample of the library.
     */
    fun playRandom(callbacks: ShufflePlaybackCallbacks) =
        shuffleAll(ALL_SONGS_SHUFFLED_QUEUE, callbacks)

    /**
     * Shuffles the user's favorite songs. Favorites are loaded on demand rather than
     * held in memory.
     */
    fun shuffleFavorites(callbacks: ShufflePlaybackCallbacks) {
        callbacks.scope.launch {
            val favSongs = musicRepository.getFavoriteSongsOnce(callbacks.currentStorageFilter())
            if (favSongs.isNotEmpty()) {
                callbacks.playShuffled(favSongs, FAVORITES_SHUFFLED_QUEUE)
            }
        }
    }

    /**
     * Picks a random album and plays it shuffled.
     */
    fun shuffleRandomAlbum(callbacks: ShufflePlaybackCallbacks) {
        callbacks.scope.launch {
            val allAlbums = callbacks.albums().ifEmpty {
                musicRepository.getAllAlbumsOnce(StorageFilter.ALL, minTracks = 2)
            }
            if (allAlbums.isEmpty()) return@launch
            val randomAlbum = allAlbums.random()
            val albumSongs = musicRepository.getSongsForAlbum(randomAlbum.id).first()
            if (albumSongs.isNotEmpty()) {
                callbacks.playShuffled(albumSongs, randomAlbum.title)
            }
        }
    }

    /**
     * Picks a random artist and plays their catalogue shuffled.
     */
    fun shuffleRandomArtist(callbacks: ShufflePlaybackCallbacks) {
        callbacks.scope.launch {
            val allArtists = callbacks.artists().ifEmpty {
                musicRepository.getAllArtistsOnce()
            }
            if (allArtists.isEmpty()) return@launch
            val randomArtist = allArtists.random()
            val artistSongs = musicRepository.getSongsForArtist(randomArtist.id).first()
            if (artistSongs.isNotEmpty()) {
                callbacks.playShuffled(artistSongs, randomArtist.name)
            }
        }
    }

    /**
     * Loads an album's songs, orders them by disc/track, and dispatches sequential playback.
     */
    fun playAlbum(album: Album, callbacks: PlaybackSourceCallbacks) {
        callbacks.scope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    musicRepository.getSongsForAlbum(album.id).first()
                }

                if (songsList.isNotEmpty()) {
                    val sortedSongs = songsList.sortedWith(
                        compareBy<Song> { it.discNumber ?: 1 }
                            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() }
                    )

                    callbacks.playSongs(sortedSongs, sortedSongs.first(), album.title, null)
                    callbacks.showSheet()
                } else {
                    Timber.w("Album '%s' has no playable songs.", album.title)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error playing album %s", album.title)
            }
        }
    }

    /**
     * Loads an artist's songs and dispatches sequential playback.
     */
    fun playArtist(artist: Artist, callbacks: PlaybackSourceCallbacks) {
        callbacks.scope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    artist.remoteBrowseId?.let { browseId ->
                        onlineMusicRepository.getArtistProfile(browseId)?.topSongs
                    } ?: musicRepository.getSongsForArtist(artist.id).first()
                }

                if (songsList.isNotEmpty()) {
                    callbacks.playSongs(songsList, songsList.first(), artist.name, null)
                    callbacks.showSheet()
                } else {
                    Timber.w("Artist '%s' has no playable songs.", artist.name)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error playing artist %s", artist.name)
            }
        }
    }

    /**
     * Starts the tapped track first and builds the radio queue around it afterwards.
     *
     * Building that queue is two Innertube round trips (`POST /next`, then usually a
     * `POST /search` when `next` yields no watch-next items) measured at 2.2s + 1.1s on a
     * normal link. Awaiting both before dispatching playback meant waiting on metadata for
     * *other* songs while the track that was actually tapped sat ready on disk: 4.4s from tap
     * to ExoPlayer start, even when the audio was already in the last-10 cache and would have
     * been served in 42ms. Playback therefore starts with just the seed and the rest of the
     * queue is spliced in by [PlaybackSourceCallbacks.extendQueue] while it plays.
     */
    fun playOnlineSeed(
        seed: Song,
        sourceName: String,
        callbacks: PlaybackSourceCallbacks,
        fallbackQueue: List<Song> = emptyList(),
    ) {
        callbacks.scope.launch {
            // A seed already in the last-10 cache plays straight from disk, so there is nothing
            // to warm up - resolving it would only burn a network round trip before the tap
            // could produce any sound. The kick-off below is likewise skipped for those.
            //
            // Give a fast resolver a head start so ExoPlayer can borrow its memoised URL, but
            // never let a slow one hold the first note hostage. ExoPlayer resolves inline
            // anyway if the wait expires, exactly as it did before this warm-up existed.
            //
            // Deliberately not awaited here: [PlaybackDispatchStateHolder.playSongs] already
            // performs its own bounded wait on the very same single-flight resolve for this seed.
            // Waiting twice stacked two full seconds of deadline in front of the first note - the
            // worst case being a link that never resolves the track in time, where playback paid
            // the entire 4s before ExoPlayer was even allowed to start.
            callbacks.scope.launch(Dispatchers.IO) {
                if (playbackRecentCacheManager.getCachedAudioFile(seed.id) == null) {
                    runCatching { onlineMusicRepository.resolvePlaybackUrl(seed) }
                }
            }
            callbacks.playSongs(listOf(seed), seed, sourceName, null)
            callbacks.showSheet()

            val initialQueue = runCatching {
                withContext(Dispatchers.IO) { onlineMusicRepository.getAutoplayQueue(seed) }
            }.onFailure { Timber.w(it, "Could not build autoplay queue for %s", seed.title) }
                .getOrDefault(listOf(seed))
                .ifEmpty { listOf(seed) }
            val queue = if (initialQueue.size > 1) initialQueue else {
                val related = runCatching {
                    withContext(Dispatchers.IO) {
                        onlineMusicRepository.searchMusicStructured(seed.artist).songs
                    }
                }.getOrDefault(emptyList())
                    .filterNot { it.id == seed.id }
                    .distinctBy { it.id }
                    .take(20)
                (listOf(seed) + related).distinctBy { it.id }
            }
            // A browse surface can hand in the shelf it was tapped from. It is only used when the
            // provider has no radio for this seed, so the queue is never just the seed alone.
            val resolvedQueue = queue.takeIf { it.size > 1 }
                ?: fallbackQueue.filter { it.id != seed.id }
                    .ifEmpty { queue }
                    .let { (listOf(seed) + it).distinctBy(Song::id) }
            // Warm the next couple of tracks so auto-advance does not stall when the link is
            // poor. Never awaited, so a slow network cannot delay anything.
            callbacks.scope.launch(Dispatchers.IO) {
                runCatching { onlineMusicRepository.prefetchStreamUrls(resolvedQueue.drop(1), limit = 2) }
            }
            callbacks.extendQueue(resolvedQueue, seed.id)
        }
    }
}
