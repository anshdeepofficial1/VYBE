package com.theveloper.pixelplay.data.service.player

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.flac.FlacExtractor
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnostics
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransitionSettings
import com.theveloper.pixelplay.data.navidrome.NavidromeStreamProxy
import com.theveloper.pixelplay.data.netease.NeteaseStreamProxy
import com.theveloper.pixelplay.data.qqmusic.QqMusicStreamProxy
import com.theveloper.pixelplay.data.telegram.TelegramRepository
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.envelope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class ActiveDecoderInfo(
    val name: String,
    val isHardware: Boolean
)

internal fun shouldResumeAfterTransientAudioFocusLoss(
    masterPlayWhenReady: Boolean,
    masterIsPlaying: Boolean,
    transitionRunning: Boolean,
    auxiliaryPlayWhenReady: Boolean,
    auxiliaryIsPlaying: Boolean
): Boolean {
    return masterPlayWhenReady ||
        masterIsPlaying ||
        (transitionRunning && (auxiliaryPlayWhenReady || auxiliaryIsPlaying))
}

internal fun shouldDisableAudioOffloadByDefaultForDevice(
    manufacturer: String,
    brand: String,
    model: String,
    hardware: String,
    sdkInt: Int
): Boolean {
    val manufacturerName = manufacturer.trim().lowercase()
    val brandName = brand.trim().lowercase()
    val modelName = model.trim().lowercase()
    val hardwareName = hardware.trim().lowercase()

    val isXiaomiFamilyDevice = manufacturerName == "xiaomi" ||
        brandName == "xiaomi" ||
        brandName == "redmi" ||
        brandName == "poco"
    if (isXiaomiFamilyDevice && sdkInt >= 36) return true

    // Google Pixel devices on SDK 37+ (Android 16 QPR / 17 preview) exhibit an audio
    // offload HAL bug where the Opus position counter jumps ~49 seconds at a time,
    // causing audible skips and incorrect position restoration on player rebuild.
    val isGooglePixelDevice = manufacturerName == "google" || brandName == "google"
    if (isGooglePixelDevice && sdkInt >= 37) return true

    val isLavaDevice =
        manufacturerName == "lava" ||
            brandName == "lava"
    val looksLikeMtkHardware =
        hardwareName.startsWith("mt") ||
            hardwareName.contains("mediatek") ||
            hardwareName.contains("mtk")
    val isReportedLxxFamily = modelName.startsWith("lxx") && isLavaDevice
    val isMtkLavaVariant = isLavaDevice && looksLikeMtkHardware

    return sdkInt >= 35 && (isReportedLxxFamily || isMtkLavaVariant)
}

internal fun shouldTriggerAudioOffloadStallFallback(
    audioOffloadEnabled: Boolean,
    transitionRunning: Boolean,
    isCurrentMasterPlayer: Boolean,
    mediaIdMatches: Boolean,
    playbackState: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    playbackSuppressionReason: Int
): Boolean {
    return audioOffloadEnabled &&
        !transitionRunning &&
        isCurrentMasterPlayer &&
        mediaIdMatches &&
        playWhenReady &&
        !isPlaying &&
        playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED
}

/**
 * Decides whether an early STATE_BUFFERING (within ~500ms of audio playing) should be read
 * as a HAL offload reset and trigger disabling offload for the session.
 *
 * The buffering is NOT treated as a HAL reset when it is explained by a recent user seek
 * ([isPostSeekBuffering]) or by a just-finished crossfade ([isPostTransitionBuffering]) —
 * in those cases the buffering is expected, and disabling offload would needlessly drop the
 * battery saving and rebuild the player (an audible glitch).
 */
internal fun shouldDisableAudioOffloadOnEarlyBuffering(
    audioOffloadEnabled: Boolean,
    transitionRunning: Boolean,
    lastPlayingAtMs: Long,
    timeSincePlayingMs: Long,
    isPostSeekBuffering: Boolean,
    isPostTransitionBuffering: Boolean,
    isPostMediaItemTransition: Boolean
): Boolean {
    return audioOffloadEnabled &&
        !transitionRunning &&
        lastPlayingAtMs > 0L &&
        timeSincePlayingMs < 500L &&
        !isPostSeekBuffering &&
        !isPostTransitionBuffering &&
        !isPostMediaItemTransition
}

/** ExoPlayer [DefaultLoadControl] buffer durations (ms) for a build of the player. */
internal data class LoadControlBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

/**
 * Picks the buffer profile for the player. On memory-constrained devices the maximum
 * prefetch depth is reduced to cap peak RAM — with time-based buffering the buffered RAM is
 * bitrate × seconds, so a 60 s window on a hi-res lossless track (plus a second buffered
 * player during a crossfade) can be tens of MB. Shrinking the *time* window (not switching
 * to a byte threshold) keeps start latency and cross-format uniformity identical to the
 * normal profile; only how far ahead we prefetch changes, which is free for local files and
 * still ample for remote streams. Normal-RAM devices are unchanged.
 */
internal fun loadControlBufferProfileFor(isLowRamDevice: Boolean): LoadControlBufferProfile {
    return if (isLowRamDevice) {
        LoadControlBufferProfile(
            minBufferMs = 15_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 5_000
        )
    } else {
        LoadControlBufferProfile(
            minBufferMs = 30_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 5_000
        )
    }
}

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless transitions.
 *
 * Player A is the designated "master" player. During a crossfade the MediaSession can
 * expose Player B early for UI continuity, while Player A remains alive to fade out.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, Player A adopts the state of Player B, ensuring continuity.
 */
@OptIn(UnstableApi::class)
@Singleton
class DualPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val telegramRepository: TelegramRepository,
    private val telegramStreamProxy: com.theveloper.pixelplay.data.telegram.TelegramStreamProxy,
    private val neteaseStreamProxy: NeteaseStreamProxy,
    private val qqMusicStreamProxy: QqMusicStreamProxy,
    private val navidromeStreamProxy: NavidromeStreamProxy,
    private val jellyfinStreamProxy: com.theveloper.pixelplay.data.jellyfin.JellyfinStreamProxy,
    private val gdriveStreamProxy: com.theveloper.pixelplay.data.gdrive.GDriveStreamProxy,
    private val telegramCacheManager: com.theveloper.pixelplay.data.telegram.TelegramCacheManager,
    private val onlineMusicRepository: com.theveloper.pixelplay.data.repository.OnlineMusicRepository,
    private val connectivityStateHolder: com.theveloper.pixelplay.presentation.viewmodel.ConnectivityStateHolder,
    private val playbackRecentCacheManager: com.theveloper.pixelplay.data.cache.PlaybackRecentCacheManager,
    private val spectrumBus: SpectrumBus
) {
    private companion object {
        private const val AUDIO_OFFLOAD_STALL_FALLBACK_MS = 4_000L
        // Grace window after a crossfade/transition during which the STATE_BUFFERING
        // "HAL offload reset" heuristic is suppressed. Right after the player swap the new
        // master (the former auxiliary) has just started, so a brief buffering blip there
        // must NOT be mistaken for a HAL underflow — doing so would disable audio offload
        // for the whole session (losing the battery saving) and rebuild the player (an
        // audible glitch right after the fade). This keeps offload enabled across crossfades.
        private const val POST_TRANSITION_OFFLOAD_GUARD_MS = 2_000L
        private const val STREAM_URL_SAFETY_MARGIN_MS = 2L * 60L * 1000L
        private const val MAX_AUXILIARY_TIMELINE_ITEMS = 200
        private val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
        private val REMOTE_MEDIA_SCHEMES = setOf("http", "https", "telegram", "netease", "qqmusic", "navidrome", "jellyfin", "gdrive", "yt", "saavn")
        // Subset of REMOTE_MEDIA_SCHEMES: schemes that need proxy resolution.
        // http/https resolve directly and must NOT enter the resolvedUriCache lookup path.
        private val CLOUD_PROXY_SCHEMES = setOf("telegram", "netease", "qqmusic", "navidrome", "jellyfin", "gdrive", "yt", "saavn")
    }

    data class TransitionTarget(
        val mediaItem: MediaItem,
        val absoluteIndex: Int,
        val queueSize: Int
    )

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var hiFiModeEnabled: Boolean = false
        private set
    var skipSilenceEnabled: Boolean = false
        private set
    private var audioOffloadEnabled = !shouldDisableAudioOffloadByDefault()
    private var transitionJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var transitionRunning = false
    private var preResolutionJob: Job? = null
    private var queueSnapshot: List<MediaItem> = emptyList()
    private var activeWindowStartIndex = 0
    private var activePlayerUsesWindowedQueue = false
    private var preparedWindowStartIndex = 0
    private var preparedPlayerUsesWindowedQueue = false

    private lateinit var playerA: ExoPlayer
    private var playerB: ExoPlayer? = null

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionDisplayPlayerListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionFinishedListeners = mutableListOf<() -> Unit>()

    private var onPlayerAboutToBeReleasedListener: ((Player) -> Unit)? = null

    fun setOnPlayerAboutToBeReleasedListener(listener: (Player) -> Unit) {
        onPlayerAboutToBeReleasedListener = listener
    }
    
    // Active Audio Session ID Flow
    private val _activeAudioSessionId = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    private val _activeDecoderInfo = MutableStateFlow<ActiveDecoderInfo?>(null)
    val activeDecoderInfo: StateFlow<ActiveDecoderInfo?> = _activeDecoderInfo.asStateFlow()

    // Audio Focus Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false
    private var isApplyingFocusPause = false
    private var focusRecoveryJob: Job? = null

    private fun pausePlayersForAudioFocus() {
        // Media3 reports a direct playWhenReady=false assignment as USER_REQUEST. Mark the
        // assignment so the listener does not mistake a system focus pause for a real user
        // pause and erase the auto-resume intent.
        isApplyingFocusPause = true
        try {
            playerA.playWhenReady = false
            playerB?.playWhenReady = false
        } finally {
            isApplyingFocusPause = false
        }
    }
    private var lastPlayWhenReadyAtMs: Long = 0L
    private var lastPlayingAtMs: Long = 0L
    // Used to distinguish a STATE_BUFFERING caused by a user seek from a real HAL offload
    // reset (where audio underflows mid-playback). Without this, seeking shortly after
    // playback starts re-enters BUFFERING within the HAL-reset window and triggers a full
    // player rebuild, which leaves the MediaSession briefly pointing at the released player
    // and silently drops any subsequent seeks.
    private var lastSeekAtMs: Long = 0L
    // Used to distinguish a STATE_BUFFERING caused by a song transition from a real HAL offload reset.
    private var lastMediaItemTransitionAtMs: Long = 0L
    // Diagnostics: timestamp when the master player entered STATE_BUFFERING, used to
    // measure buffering->ready (playback prepare) durations for the performance report.
    private var bufferingStartedAtMs: Long = 0L
    // Diagnostics: timestamp when the most recent crossfade/transition started.
    private var transitionStartedAtMs: Long = 0L
    // Timestamp when the most recent crossfade/transition finished. Used to give the new
    // master a grace window before the HAL-offload-reset heuristic can fire, so a crossfade
    // can never spuriously disable audio offload (battery) or trigger a player rebuild.
    private var lastTransitionFinishedAtMs: Long = 0L

    /**
     * Whether ExoPlayer audio offload is currently enabled for this session. Exposed
     * read-only for the diagnostic performance report. Offload is disabled at runtime
     * when a HAL stall/reset is detected (see [disableAudioOffloadForSession]).
     */
    val isAudioOffloadEnabled: Boolean
        get() = audioOffloadEnabled

    /** Lightweight, allocation-cheap snapshot of the live audio format, for diagnostics. */
    data class AudioFormatSnapshot(
        val sampleMimeType: String?,
        val sampleRate: Int,
        val channelCount: Int,
        val pcmEncoding: Int,
        val bitrate: Int
    )

    /** Returns the current master-player audio format, or null when nothing is decoding. */
    fun currentAudioFormatSnapshot(): AudioFormatSnapshot? {
        if (!::playerA.isInitialized) return null
        val format = playerA.audioFormat ?: return null
        fun Int.orZero() = if (this == Format.NO_VALUE) 0 else this
        val bitrate = when {
            format.averageBitrate != Format.NO_VALUE -> format.averageBitrate
            format.peakBitrate != Format.NO_VALUE -> format.peakBitrate
            else -> 0
        }
        return AudioFormatSnapshot(
            sampleMimeType = format.sampleMimeType,
            sampleRate = format.sampleRate.orZero(),
            channelCount = format.channelCount.orZero(),
            pcmEncoding = format.pcmEncoding.orZero(),
            bitrate = bitrate
        )
    }

    /**
     * Set by MusicService once ReplayGain for the incoming track is known.
     * The crossfade loop reads this at the end instead of hard-coding 1f,
     * so the incoming track reaches its correct RG volume without a jump.
     * Reset to null after each transition.
     */
    var incomingTrackReplayGainVolume: Float? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS. Pausing.")
                val auxiliaryPlayer = playerB
                isFocusLossPause = shouldResumeAfterTransientAudioFocusLoss(
                    masterPlayWhenReady = playerA.playWhenReady,
                    masterIsPlaying = playerA.isPlaying,
                    transitionRunning = transitionRunning,
                    auxiliaryPlayWhenReady = auxiliaryPlayer?.playWhenReady == true,
                    auxiliaryIsPlaying = auxiliaryPlayer?.isPlaying == true,
                )
                pausePlayersForAudioFocus()
                abandonAudioFocus()
                // Calls and many short-form video apps report a full LOSS rather than
                // LOSS_TRANSIENT. Queue a delayed focus request; Android grants it only after
                // the call/video releases focus, then the GAIN branch resumes the same item.
                if (isFocusLossPause) {
                    focusRecoveryJob?.cancel()
                    focusRecoveryJob = scope.launch {
                        delay(750)
                        if (isFocusLossPause) requestAudioFocus()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT. Pausing.")
                val auxiliaryPlayer = playerB
                isFocusLossPause = shouldResumeAfterTransientAudioFocusLoss(
                    masterPlayWhenReady = playerA.playWhenReady,
                    masterIsPlaying = playerA.isPlaying,
                    transitionRunning = transitionRunning,
                    auxiliaryPlayWhenReady = auxiliaryPlayer?.playWhenReady == true,
                    auxiliaryIsPlaying = auxiliaryPlayer?.isPlaying == true
                )
                pausePlayersForAudioFocus()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag("TransitionDebug").d("AudioFocus GAIN. Resuming if paused by loss.")
                focusRecoveryJob?.cancel()
                focusRecoveryJob = null
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB?.playWhenReady = true
                }
            }
        }
    }

    // Listener to attach to the active master player (playerA)
    private val masterPlayerListener = object : Player.Listener, AnalyticsListener, ExoPlayer.AudioOffloadListener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                !isApplyingFocusPause
            ) {
                Timber.tag("TransitionDebug").d("User manually paused playback. Clearing isFocusLossPause.")
                isFocusLossPause = false
                focusRecoveryJob?.cancel()
                focusRecoveryJob = null
            }
            if (playWhenReady) {
                lastPlayWhenReadyAtMs = SystemClock.elapsedRealtime()
                requestAudioFocus()
                scheduleAudioOffloadFallbackIfNeeded(playerA)
            } else {
                cancelAudioOffloadFallback()
                // Keep focus across user pauses so a quick resume doesn't have to re-acquire it.
                // Focus is abandoned explicitly on AUDIOFOCUS_LOSS and on release(); anything in
                // between (user pause/play) keeps the request alive to avoid contention races
                // that occasionally caused press-play to auto-pause after a short wait.
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastPlayingAtMs = SystemClock.elapsedRealtime()
                cancelAudioOffloadFallback()
            }
        }

        /**
         * Fires when ExoPlayer believes the audio HAL is producing output via
         * offload and the renderer thread can stop polling — at that point the
         * CPU genuinely doesn't need a wake lock to keep playing audio. When
         * [sleepingForOffload] flips back to false (track change, format
         * mismatch, fallback path), restore [C.WAKE_MODE_LOCAL] so the
         * non-offload PCM path keeps the CPU awake correctly.
         *
         * Battery: this is what actually lets the SoC race-to-sleep during
         * music playback. The static [C.WAKE_MODE_LOCAL] we set at build time
         * is the safe default; this callback is the dynamic optimisation.
         */
        @Suppress("UnsafeOptInUsageError")
        override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
            if (!::playerA.isInitialized) return
            // Only override the wake mode for local media. Remote schemes need
            // C.WAKE_MODE_NETWORK to keep the wifi lock; we never want to drop
            // that to NONE.
            val baseMode = wakeModeFor(playerA.currentMediaItem)
            val desiredMode = if (sleepingForOffload && baseMode == C.WAKE_MODE_LOCAL) {
                C.WAKE_MODE_NONE
            } else {
                baseMode
            }
            if (currentWakeMode == desiredMode) return

            try {
                playerA.setWakeMode(desiredMode)
                playerB?.setWakeMode(desiredMode)
                currentWakeMode = desiredMode
                Timber.tag("DualPlayerEngine").d(
                    "Wake mode -> %d (sleepingForOffload=%b)",
                    desiredMode,
                    sleepingForOffload
                )
            } catch (e: Exception) {
                Timber.tag("DualPlayerEngine").w(e, "Failed to apply offload-aware wake mode")
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val isHardware = AudioDecoderPolicy.isLikelyHardwareDecoder(decoderName)
            _activeDecoderInfo.value = ActiveDecoderInfo(decoderName, isHardware)
            PerformanceMetrics.recordTiming(
                PerformanceMetrics.Timings.AUDIO_DECODER_INIT,
                initializationDurationMs
            )
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                name = "audio_decoder_initialized"
            ) {
                mapOf(
                    "decoderName" to decoderName,
                    "isHardware" to isHardware.toString(),
                    "initializationDurationMs" to initializationDurationMs.toString()
                )
            }
            Timber.tag("DualPlayerEngine").d("Audio decoder initialized: %s (Hardware: %b)", decoderName, isHardware)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            // Record the live format (channels, sample rate, bit depth) as the report's
            // source of multichannel / bit-depth data — these aren't stored in the library DB.
            PerformanceMetrics.recordPlaybackFormat(
                channelCount = if (format.channelCount == Format.NO_VALUE) 0 else format.channelCount,
                sampleRate = if (format.sampleRate == Format.NO_VALUE) 0 else format.sampleRate,
                pcmEncoding = if (format.pcmEncoding == Format.NO_VALUE) 0 else format.pcmEncoding
            )
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                name = "audio_format_changed"
            ) {
                mapOf(
                    "mime" to (format.sampleMimeType ?: "unknown"),
                    "sampleRate" to format.sampleRate.toString(),
                    "channels" to format.channelCount.toString(),
                    "pcmEncoding" to format.pcmEncoding.toString(),
                    "bitrate" to format.bitrate.toString()
                )
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != 0 && _activeAudioSessionId.value != audioSessionId) {
                _activeAudioSessionId.value = audioSessionId
                AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                    type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                    name = "audio_session_changed"
                ) {
                    mapOf("audioSessionId" to audioSessionId.toString())
                }
                Timber.tag("TransitionDebug").d("Master audio session changed: %d", audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastMediaItemTransitionAtMs = SystemClock.elapsedRealtime()
            cancelAudioOffloadFallback()
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                name = "media_item_transition",
                elapsedRealtimeMs = lastMediaItemTransitionAtMs
            ) {
                mapOf(
                    "reason" to reason.toString(),
                    "scheme" to (mediaItem?.localConfiguration?.uri?.scheme ?: "unknown")
                )
            }
            
            // If the transition was not automatic (e.g. user skip or playlist change),
            // immediately cancel any background crossfade logic to ensure responsiveness.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                cancelNext()
            }

            val uri = mediaItem?.localConfiguration?.uri
            if (uri?.scheme == "telegram") {
                scope.launch {
                    val result = telegramRepository.resolveTelegramUri(uri.toString())
                    val fileId = result?.first
                    telegramCacheManager.setActivePlayback(fileId)
                    Timber.tag("DualPlayerEngine").d("Telegram playback active: fileId=$fileId")
                }
            } else {
                telegramCacheManager.setActivePlayback(null)
            }
            applyWakeModeForCurrentItem()

            // --- Pre-Resolve Next/Prev Tracks with Debounce to prevent flooding ---
            preResolutionJob?.cancel()
            preResolutionJob = scope.launch {
                delay(600) // Wait for user to stop skipping/navigating
                try {
                    val currentIndex = playerA.currentMediaItemIndex
                    if (currentIndex != C.INDEX_UNSET) {
                        // Resolve each neighbour directly — no intermediate list allocation.
                        if (currentIndex + 1 < playerA.mediaItemCount) {
                            playerA.getMediaItemAt(currentIndex + 1).localConfiguration?.uri
                                ?.takeIf { it.scheme in CLOUD_PROXY_SCHEMES }
                                ?.let { resolveCloudUri(it) }
                        }
                        if (currentIndex - 1 >= 0) {
                            playerA.getMediaItemAt(currentIndex - 1).localConfiguration?.uri
                                ?.takeIf { it.scheme in CLOUD_PROXY_SCHEMES }
                                ?.let { resolveCloudUri(it) }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("DualPlayerEngine").w(e, "Pre-resolution error")
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (transitionRunning) return
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || queueSnapshot.isEmpty()) {
                refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    val now = SystemClock.elapsedRealtime()
                    if (bufferingStartedAtMs == 0L) bufferingStartedAtMs = now
                    AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                        type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                        name = "playback_buffering",
                        elapsedRealtimeMs = now
                    )
                    val timeSincePlayingMs = now - lastPlayingAtMs
                    val timeSinceSeekMs = now - lastSeekAtMs
                    val timeSinceTransitionMs = now - lastTransitionFinishedAtMs
                    val timeSinceMediaItemTransitionMs = now - lastMediaItemTransitionAtMs
                    val isPostSeekBuffering = lastSeekAtMs > 0L && timeSinceSeekMs < 1_500L
                    val isPostTransitionBuffering = lastTransitionFinishedAtMs > 0L &&
                        timeSinceTransitionMs < POST_TRANSITION_OFFLOAD_GUARD_MS
                    val isPostMediaItemTransition = lastMediaItemTransitionAtMs > 0L &&
                        timeSinceMediaItemTransitionMs < 2_000L
                    if (shouldDisableAudioOffloadOnEarlyBuffering(
                            audioOffloadEnabled = audioOffloadEnabled,
                            transitionRunning = transitionRunning,
                            lastPlayingAtMs = lastPlayingAtMs,
                            timeSincePlayingMs = timeSincePlayingMs,
                            isPostSeekBuffering = isPostSeekBuffering,
                            isPostTransitionBuffering = isPostTransitionBuffering,
                            isPostMediaItemTransition = isPostMediaItemTransition
                        )
                    ) {
                        disableAudioOffloadForSession(
                            reason = "HAL offload reset detected: STATE_BUFFERING after ${timeSincePlayingMs}ms of playback"
                        )
                    } else {
                        scheduleAudioOffloadFallbackIfNeeded(playerA)
                    }
                }
                Player.STATE_READY -> {
                    if (bufferingStartedAtMs > 0L) {
                        val prepareDurationMs = SystemClock.elapsedRealtime() - bufferingStartedAtMs
                        PerformanceMetrics.recordTiming(
                            PerformanceMetrics.Timings.PLAYBACK_PREPARE,
                            prepareDurationMs
                        )
                        AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                            type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                            name = "playback_ready_after_buffering"
                        ) {
                            mapOf("prepareDurationMs" to prepareDurationMs.toString())
                        }
                        bufferingStartedAtMs = 0L
                    }
                    scheduleAudioOffloadFallbackIfNeeded(playerA)
                }
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    bufferingStartedAtMs = 0L
                    cancelAudioOffloadFallback()
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Timber.tag("DualPlayerEngine").e(error, "Master player error: ${error.errorCodeName}")
            val currentItem = if (::playerA.isInitialized) playerA.currentMediaItem else null
            val uri = currentItem?.localConfiguration?.uri
            val uriString = uri?.toString().orEmpty()
            val mediaId = currentItem?.mediaId.orEmpty()
            if (mediaId.startsWith("yt_") || uriString.startsWith("yt") || uriString.contains("googlevideo.com") ||
                mediaId.startsWith("saavn_") || uriString.startsWith("saavn") || uriString.contains("saavn.dev") || uriString.contains("jiosaavn")) {
                Timber.tag("DualPlayerEngine").w("Online playback error detected for $mediaId. Invalidating cache and retrying.")
                val isSaavn = mediaId.startsWith("saavn_") || uriString.startsWith("saavn")
                val cleanId = when {
                    mediaId.startsWith("yt_") -> mediaId.removePrefix("yt_")
                    uriString.startsWith("yt://") -> uriString.removePrefix("yt://")
                    mediaId.startsWith("saavn_") -> mediaId.removePrefix("saavn_")
                    uriString.startsWith("saavn://") -> uriString.removePrefix("saavn://")
                    else -> mediaId
                }
                onlineMusicRepository.invalidateStreamUrl(cleanId)
                resolvedUriCache.remove(uriString)
                resolvedUriCache.remove(mediaId)
                resolvedUriCache.remove("yt://$cleanId")
                resolvedUriCache.remove("yt_$cleanId")
                resolvedUriCache.remove("saavn://$cleanId")
                resolvedUriCache.remove("saavn_$cleanId")
                scope.launch {
                    val freshUrl = onlineMusicRepository.resolvePlaybackUrl(
                        Song(
                            id = if (isSaavn) "saavn_$cleanId" else "yt_$cleanId",
                            title = currentItem?.mediaMetadata?.title?.toString().orEmpty(),
                            artist = currentItem?.mediaMetadata?.artist?.toString().orEmpty(),
                            artistId = 0L,
                            album = currentItem?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                            albumId = 0L,
                            path = if (isSaavn) "saavn://$cleanId" else "yt://$cleanId",
                            contentUriString = if (isSaavn) "saavn://$cleanId" else "yt://$cleanId",
                            albumArtUriString = currentItem?.mediaMetadata?.artworkUri?.toString(),
                            duration = 0L,
                            mimeType = "audio/mp4",
                            bitrate = 256,
                            sampleRate = 44100
                        )
                    )
                    if (!freshUrl.isNullOrBlank() && ::playerA.isInitialized && currentItem != null) {
                        val freshUri = Uri.parse(freshUrl)
                        if (isSaavn) {
                            cacheResolvedUri("saavn://$cleanId", freshUri)
                            cacheResolvedUri("saavn_$cleanId", freshUri)
                        } else {
                            cacheResolvedUri("yt://$cleanId", freshUri)
                            cacheResolvedUri("yt_$cleanId", freshUri)
                        }
                        val newItem = currentItem.buildUpon().setUri(freshUri).build()
                        val pos = playerA.currentPosition.coerceAtLeast(0L)
                        val currentIndex = playerA.currentMediaItemIndex
                        // Keep the radio/playlist timeline intact while refreshing an expired
                        // stream URL. setMediaItem() used to replace the entire queue here,
                        // leaving the recovered track alone and repeating forever.
                        if (currentIndex != C.INDEX_UNSET && currentIndex < playerA.mediaItemCount) {
                            playerA.replaceMediaItem(currentIndex, newItem)
                            playerA.seekTo(currentIndex, pos)
                        } else {
                            playerA.setMediaItem(newItem, pos)
                        }
                        playerA.prepare()
                        playerA.play()
                    }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                lastSeekAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun addMasterPlayerListeners(player: ExoPlayer) {
        player.addListener(masterPlayerListener)
        player.addAnalyticsListener(masterPlayerListener)
        player.addAudioOffloadListener(masterPlayerListener)
    }

    private fun removeMasterPlayerListeners(player: ExoPlayer) {
        player.removeListener(masterPlayerListener)
        player.removeAnalyticsListener(masterPlayerListener)
        player.removeAudioOffloadListener(masterPlayerListener)
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }

    fun addTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.add(listener)
    }

    fun removeTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.remove(listener)
    }

    fun addTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.add(listener)
    }

    /**
     * Notifies the engine that an external caller (UI seek, etc.) is about to issue a
     * seek through the MediaController. Used to mark the upcoming STATE_BUFFERING as
     * seek-driven so the HAL-reset heuristic does not trigger a player rebuild that
     * would race with the in-flight seek command.
     *
     * Setting this here (synchronously, before the seek dispatches) is more reliable
     * than waiting for onPositionDiscontinuity, which is delivered on the next event
     * batch and can race with onPlaybackStateChanged on some Media3 versions.
     */
    fun notifyExternalSeekInitiated() {
        lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    fun removeTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.remove(listener)
    }

    val masterPlayer: Player
        get() {
            initialize()
            return playerA
        }

    fun isTransitionRunning(): Boolean = transitionRunning

    fun isUsingWindowedQueue(): Boolean = activePlayerUsesWindowedQueue

    fun getFullQueue(): List<MediaItem> = ensureQueueSnapshot()

    fun getCurrentAbsoluteIndex(): Int {
        if (!::playerA.isInitialized) return 0
        val mediaItem = playerA.currentMediaItem ?: return playerA.currentMediaItemIndex.coerceAtLeast(0)
        val snapshot = ensureQueueSnapshot()
        val index = resolveCurrentAbsoluteIndex(mediaItem, snapshot)
        return if (index == C.INDEX_UNSET) {
            if (activePlayerUsesWindowedQueue) {
                (activeWindowStartIndex + playerA.currentMediaItemIndex).coerceIn(0, (snapshot.size - 1).coerceAtLeast(0))
            } else {
                playerA.currentMediaItemIndex.coerceAtLeast(0)
            }
        } else {
            index
        }
    }

    fun triggerAdjacentPreResolution() {
        if (!::playerA.isInitialized) return
        preResolutionJob?.cancel()
        val currentIndex = playerA.currentMediaItemIndex
        if (currentIndex != C.INDEX_UNSET) {
            val adjacentCloudUris = mutableListOf<Uri>()
            if (currentIndex + 1 < playerA.mediaItemCount) {
                playerA.getMediaItemAt(currentIndex + 1).localConfiguration?.uri?.let { uri ->
                    if (uri.scheme in REMOTE_MEDIA_SCHEMES) adjacentCloudUris.add(uri)
                }
            }
            if (currentIndex - 1 >= 0) {
                playerA.getMediaItemAt(currentIndex - 1).localConfiguration?.uri?.let { uri ->
                    if (uri.scheme in REMOTE_MEDIA_SCHEMES) adjacentCloudUris.add(uri)
                }
            }

            if (adjacentCloudUris.isNotEmpty()) {
                preResolutionJob = scope.launch {
                    delay(600) // Wait for user to stop skipping/navigating
                    try {
                        for (uriToResolve in adjacentCloudUris) {
                            resolveCloudUri(uriToResolve)
                        }
                    } catch (e: Exception) {
                        Timber.tag("DualPlayerEngine").w(e, "Error during pre-resolution triggered manually")
                    }
                }
            }
        }
    }

    fun getAudioSessionId(): Int = if (::playerA.isInitialized) playerA.audioSessionId else 0

    private var isReleased = false

    /** A resolved playback URI together with the moment its upstream URL stops working. */
    private data class CachedResolvedUri(val uri: Uri, val expiresAtMs: Long)

    private val resolvedUriCache = LruCache<String, CachedResolvedUri>(100)

    /**
     * googlevideo URLs embed their own `expire=<epoch seconds>` parameter and are bound to the IP
     * that requested them. The cache used to hold a resolution forever (until LRU eviction), so a
     * long queue could hand Media3 a URL that had already died, which the user heard as a mid-song
     * glitch before the error-recovery path re-resolved it. URLs that advertise no expiry (local
     * proxy routes for the cloud providers) keep their previous effectively-permanent lifetime.
     */
    private fun cacheResolvedUri(key: String, uri: Uri) {
        resolvedUriCache.put(key, CachedResolvedUri(uri, resolvedUriExpiryMs(uri)))
    }

    private fun cachedResolvedUri(key: String): Uri? {
        val entry = resolvedUriCache.get(key) ?: return null
        if (entry.expiresAtMs > System.currentTimeMillis()) return entry.uri
        resolvedUriCache.remove(key)
        return null
    }

    private fun resolvedUriExpiryMs(uri: Uri): Long {
        val advertised = uri.getQueryParameter("expire")?.toLongOrNull() ?: return Long.MAX_VALUE
        return advertised * 1000L - STREAM_URL_SAFETY_MARGIN_MS
    }

    // Whether the OS classifies this as a low-RAM device. Used to cap the player's max
    // prefetch depth so hi-res/lossless buffering (and the second player during a crossfade)
    // can't balloon peak memory on constrained hardware. Cached: it never changes at runtime.
    private val isLowRamDevice: Boolean by lazy {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }

    fun initialize() {
        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        if (::playerA.isInitialized) {
            removeMasterPlayerListeners(playerA)
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            try { playerA.release() } catch (e: Exception) { /* Ignore */ }
        }
        playerB?.let { try { it.release() } catch (e: Exception) { /* Ignore */ } }
        playerB = null

        playerA = buildPlayer()

        addMasterPlayerListeners(playerA)

        _activeAudioSessionId.value = playerA.audioSessionId
        isReleased = false
        queueSnapshot = emptyList()
        activeWindowStartIndex = 0
        activePlayerUsesWindowedQueue = false
        resetPreparedWindowState()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return

        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            // Let the system queue our request behind a transient holder instead of failing.
            // Pairs with the AUDIOFOCUS_GAIN handler below: on DELAYED we pause and mark the
            // pause as focus-driven so the eventual GAIN callback resumes playback.
            .setAcceptsDelayedFocusGain(true)
            .build()

        val result = audioManager.requestAudioFocus(request)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusRequest = request
                // A fresh request made after AUDIOFOCUS_LOSS may be granted synchronously.
                // Android does not send a separate GAIN callback in that case, so resume here
                // as well. Without this, calls on Samsung devices (and the emulator telephony
                // stack) paused correctly but remained paused forever after the call ended.
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    focusRecoveryJob?.cancel()
                    focusRecoveryJob = null
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB?.playWhenReady = true
                }
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                audioFocusRequest = request
                isFocusLossPause = true
                pausePlayersForAudioFocus()
            }
            else -> {
                Timber.tag("TransitionDebug").w("AudioFocus Request Failed: $result")
                pausePlayersForAudioFocus()
                // A full focus loss is commonly used by calls and some short-form video apps.
                // Keep probing while that transient owner is active; stop immediately after a
                // user pause because onPlayWhenReadyChanged clears isFocusLossPause.
                if (isFocusLossPause) {
                    focusRecoveryJob?.cancel()
                    focusRecoveryJob = scope.launch {
                        delay(1_000)
                        if (isFocusLossPause) requestAudioFocus()
                    }
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun scheduleAudioOffloadFallbackIfNeeded(player: ExoPlayer) {
        cancelAudioOffloadFallback()
        if (!audioOffloadEnabled || transitionRunning || !player.playWhenReady || player.isPlaying) return
        if (!isLikelyLocalMedia(player.currentMediaItem)) return

        val watchedMediaId = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        bufferingFallbackJob = scope.launch {
            delay(AUDIO_OFFLOAD_STALL_FALLBACK_MS)

            val currentMediaId = player.currentMediaItem?.mediaId
            val shouldFallback = shouldTriggerAudioOffloadStallFallback(
                audioOffloadEnabled = audioOffloadEnabled,
                transitionRunning = transitionRunning,
                isCurrentMasterPlayer = player === playerA,
                mediaIdMatches = currentMediaId == watchedMediaId,
                playbackState = player.playbackState,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                playbackSuppressionReason = player.playbackSuppressionReason
            )
            if (!shouldFallback) return@launch

            disableAudioOffloadForSession(
                reason = "Local media did not produce audio for " +
                    "${AUDIO_OFFLOAD_STALL_FALLBACK_MS}ms (state=${player.playbackState})"
            )
        }
    }

    private fun cancelAudioOffloadFallback() {
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = null
    }

    private fun isLikelyLocalMedia(mediaItem: MediaItem?): Boolean {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return scheme == null || scheme in LOCAL_MEDIA_SCHEMES
    }

    private fun wakeModeFor(mediaItem: MediaItem?): Int {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme != null && scheme in REMOTE_MEDIA_SCHEMES) {
            C.WAKE_MODE_NETWORK
        } else {
            C.WAKE_MODE_LOCAL
        }
    }

    private var currentWakeMode: Int = C.WAKE_MODE_LOCAL

    private fun applyWakeModeForCurrentItem() {
        if (!::playerA.isInitialized) return
        val mode = wakeModeFor(playerA.currentMediaItem)
        if (currentWakeMode == mode) return
        
        try {
            playerA.setWakeMode(mode)
            playerB?.setWakeMode(mode)
            currentWakeMode = mode
            Timber.tag("DualPlayerEngine").d("Wake mode updated to %d", mode)
        } catch (e: Exception) {
            Timber.tag("DualPlayerEngine").w(e, "Failed to update wake mode")
        }
    }

    private fun shouldDisableAudioOffloadByDefault(): Boolean {
        return shouldDisableAudioOffloadByDefaultForDevice(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            hardware = Build.HARDWARE,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    private fun disableAudioOffloadForSession(reason: String) {
        if (!audioOffloadEnabled) return
        if (transitionRunning) {
            Timber.tag("DualPlayerEngine").w("Skipping offload fallback during active transition. %s", reason)
            return
        }

        audioOffloadEnabled = false
        PerformanceMetrics.recordOffloadFallback(reason, SystemClock.elapsedRealtime())
        rebuildPlayersPreservingMasterState(
            logMessage = "Audio offload disabled for current session. $reason"
        )
    }

    private fun rebuildPlayersPreservingMasterState(logMessage: String) {
        cancelAudioOffloadFallback()
        AdvancedPerformanceDiagnostics.recordEventIfEnabled(
            type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
            name = "player_rebuild_start"
        ) {
            mapOf("reason" to logMessage)
        }

        val desiredPlayWhenReady = playerA.playWhenReady
        // Guard against snapshotting a position that landed during a bad early-startup seek
        // (e.g. an offload stall rebuild firing while the player is at a spurious offset).
        // Positions under 5s on first playback are more likely noise than intent.
        val positionMs = if (playerA.currentPosition > 5_000L) playerA.currentPosition else 0L
        val currentIndex = playerA.currentMediaItemIndex.coerceAtLeast(0)
        // Pre-sized ArrayList avoids the IntRange object and the extra copy produced by .map.
        val mediaItemCount = playerA.mediaItemCount
        val mediaItems = ArrayList<MediaItem>(mediaItemCount)
        for (i in 0 until mediaItemCount) mediaItems.add(playerA.getMediaItemAt(i))
        val repeatMode = playerA.repeatMode
        val shuffleMode = playerA.shuffleModeEnabled
        val volume = playerA.volume
        val pauseAtEnd = playerA.pauseAtEndOfMediaItems
        val playbackParameters: PlaybackParameters = playerA.playbackParameters

        removeMasterPlayerListeners(playerA)
        onPlayerAboutToBeReleasedListener?.invoke(playerA)
        playerA.release()
        playerB?.release()
        playerB = null

        playerA = buildPlayer()

        addMasterPlayerListeners(playerA)
        playerA.volume = volume
        playerA.pauseAtEndOfMediaItems = pauseAtEnd
        playerA.playbackParameters = playbackParameters

        if (mediaItems.isNotEmpty()) {
            playerA.setMediaItems(mediaItems, currentIndex, positionMs)
            playerA.repeatMode = repeatMode
            playerA.shuffleModeEnabled = shuffleMode
            playerA.prepare()
            playerA.playWhenReady = desiredPlayWhenReady
            applyWakeModeForCurrentItem()
        }

        _activeAudioSessionId.value = playerA.audioSessionId
        onPlayerSwappedListeners.forEach { it(playerA) }

        AdvancedPerformanceDiagnostics.recordEventIfEnabled(
            type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
            name = "player_rebuild_end"
        ) {
            mapOf("audioSessionId" to playerA.audioSessionId.toString())
        }
        Timber.tag("DualPlayerEngine").d(logMessage)
    }

    /**
     * Returns a [DefaultLoadControl] tuned to the device's RAM tier.
     *
     * Low-RAM devices ([ActivityManager.isLowRamDevice]) receive halved buffer ceilings
     * to prevent memory pressure when both players co-exist during a crossfade.
     * [bufferForPlaybackMs] is set to ExoPlayer's documented default of 2 500 ms on both
     * tiers — the previous value of 5 000 ms doubled first-audio latency with no benefit.
     */
    private fun buildAdaptiveLoadControl(): DefaultLoadControl {
        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .isLowRamDevice
        // setPrioritizeTimeOverSizeThresholds(true): instructs ExoPlayer to use buffered
        // *duration* (not buffered *bytes*) as the criterion for deciding when to start
        // playback and when to stop buffering. This is required for correct behaviour with
        // high-bitrate and lossless formats (FLAC, hi-res ALAC, WAV) where a short byte
        // window would be exhausted almost immediately, causing repeated rebuffering.
        // Without this flag ExoPlayer falls back to a default byte threshold that was
        // designed for typical compressed audio (~128–320 kbps) and will underperform on
        // files with bitrates above ~1 Mbps.
        return if (isLowRam) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs                      */ 15_000,
                    /* maxBufferMs                      */ 30_000,
                    /* bufferForPlaybackMs              */    500,
                    /* bufferForPlaybackAfterRebufferMs */  1_500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs                      */ 30_000,
                    /* maxBufferMs                      */ 60_000,
                    /* bufferForPlaybackMs              */    500,
                    /* bufferForPlaybackAfterRebufferMs */  1_500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )

            AudioDecoderPolicy.selectPlatformDecoders(mimeType, decoderInfos)
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(hiFiModeEnabled)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            HiResSampleRateCapAudioProcessor(),
                            SurroundDownmixProcessor(),
                            SilenceSkippingAudioProcessor().apply {
                                setEnabled(skipSilenceEnabled)
                            },
                            // Last in the chain so the visualiser sees exactly what is being sent
                            // to the device. Pure pass-through: it mirrors the buffer through and
                            // only reads a copy of it, so it cannot alter playback.
                            SpectrumAudioProcessor(spectrumBus),
                        )
                    )
                    .build()
            }

            override fun buildTextRenderers(
                context: Context,
                eventListener: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip text renderers.
            }

            override fun buildCameraMotionRenderers(
                context: Context,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip camera motion renderers.
            }
        }.setEnableAudioFloatOutput(hiFiModeEnabled)
         .setMediaCodecSelector(mediaCodecSelector)
         .setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val audioAttributes = Media3AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
            
        val resolver = object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val uri = dataSpec.uri
                val scheme = uri.scheme
                val uriString = uri.toString()
                if (scheme in CLOUD_PROXY_SCHEMES || uriString.startsWith("yt_") || uriString.startsWith("yt://")) {
                    cachedResolvedUri(uriString)?.let { return dataSpec.buildUpon().setUri(it).build() }
                    if (scheme == "yt" || scheme == "saavn" || uriString.startsWith("yt_") || uriString.startsWith("yt://") || uriString.startsWith("saavn_") || uriString.startsWith("saavn://")) {
                        val cleanId = when {
                            uriString.startsWith("yt://") -> uriString.removePrefix("yt://")
                            uriString.startsWith("yt_") -> uriString.removePrefix("yt_")
                            uriString.startsWith("saavn://") -> uriString.removePrefix("saavn://")
                            uriString.startsWith("saavn_") -> uriString.removePrefix("saavn_")
                            else -> uri.lastPathSegment ?: uriString
                        }

                        // Check instant offline playback cache first
                        val localAudioCache = playbackRecentCacheManager.getCachedAudioFile(cleanId)
                        if (localAudioCache != null && localAudioCache.exists() && localAudioCache.length() > 10_000L) {
                            Timber.tag("DualPlayerEngine").d("resolveDataSpec: Serving from local recent cache for %s", cleanId)
                            val localUri = Uri.fromFile(localAudioCache)
                            resolvedUriCache.put(uriString, CachedResolvedUri(localUri, Long.MAX_VALUE))
                            return dataSpec.buildUpon().setUri(localUri).build()
                        }
                        val snapshot = queueSnapshot
                        val isSaavn = uriString.startsWith("saavn")
                        val expectedMediaId = if (isSaavn) "saavn_$cleanId" else "yt_$cleanId"
                        var matchedItem = snapshot.firstOrNull { it.mediaId == expectedMediaId }
                        if (matchedItem == null && ::playerA.isInitialized) {
                            val currentItem = playerA.currentMediaItem
                            if (currentItem != null && currentItem.mediaId == expectedMediaId) {
                                matchedItem = currentItem
                            }
                        }
                        val title = matchedItem?.mediaMetadata?.title?.toString().orEmpty()
                        val artist = matchedItem?.mediaMetadata?.artist?.toString().orEmpty()
                        val directUrl = onlineMusicRepository.resolvePlaybackUrlSync(cleanId, title, artist, isSaavn)
                        if (!directUrl.isNullOrBlank()) {
                            val resolvedUri = Uri.parse(directUrl)
                            cacheResolvedUri(uriString, resolvedUri)
                            if (isSaavn) {
                                cacheResolvedUri("saavn_$cleanId", resolvedUri)
                                cacheResolvedUri("saavn://$cleanId", resolvedUri)
                            } else {
                                cacheResolvedUri("yt_$cleanId", resolvedUri)
                                cacheResolvedUri("yt://$cleanId", resolvedUri)
                            }
                            Timber.tag("DualPlayerEngine").d("resolveDataSpec: Resolved online stream dynamically for %s", cleanId)
                            return dataSpec.buildUpon().setUri(resolvedUri).build()
                        }
                    }
                    Timber.tag("DualPlayerEngine").d("resolveDataSpec: Cache MISS for %s — using original URI", scheme)
                }
                return dataSpec
            }
        }
        
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/"
            ))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val resolvingFactory = ResolvingDataSource.Factory(dataSourceFactory, resolver)
        val extractorsFactory = DefaultExtractorsFactory()
            // FLAG_WORKAROUND_IGNORE_EDIT_LISTS intentionally removed: it breaks Opus files
            // by discarding the edit list that encodes the pre-skip (encoder delay), causing
            // ExoPlayer to seek ~44-52s into the track on first playback.
            // FLAG_ENABLE_CONSTANT_BITRATE_SEEKING (not _ALWAYS): fallback-only CBR seeking
            // so VBR MP3s with proper Xing/VBRI headers still use their seek table and land
            // on the exact frame instead of jumping ±30 s on a VBR file.
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setFlacExtractorFlags(FlacExtractor.FLAG_DISABLE_ID3_METADATA)

        val loadControl = buildAdaptiveLoadControl()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .build().apply {
            setAudioAttributes(audioAttributes, false)
            val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (audioOffloadEnabled) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    }
                )
                .build()
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            playWhenReady = false
        }
    }

    private fun getOrCreateAuxiliaryPlayer(): ExoPlayer {
        playerB?.let { return it }
        return buildPlayer().also { player ->
            player.setWakeMode(currentWakeMode)
            playerB = player
        }
    }

    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        if (::playerA.isInitialized) {
            playerA.pauseAtEndOfMediaItems = shouldPause
        }
    }

    fun getNextTransitionTarget(currentMediaItem: MediaItem, repeatMode: Int): TransitionTarget? {
        val snapshot = ensureQueueSnapshot()
        if (snapshot.isEmpty()) return null

        val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(currentMediaItem, snapshot)
        if (currentAbsoluteIndex == C.INDEX_UNSET) return null

        val targetIndex = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> currentAbsoluteIndex
            else -> currentAbsoluteIndex + 1
        }

        val targetItem = snapshot.getOrNull(targetIndex) ?: return null
        return TransitionTarget(
            mediaItem = targetItem,
            absoluteIndex = targetIndex,
            queueSize = snapshot.size
        )
    }

    fun setHiFiMode(enabled: Boolean) {
        if (hiFiModeEnabled == enabled) return
        hiFiModeEnabled = enabled
        rebuildPlayersPreservingMasterState("Hi-Fi mode set to $enabled")
    }

    fun setSkipSilence(enabled: Boolean) {
        if (skipSilenceEnabled == enabled) return
        skipSilenceEnabled = enabled
        rebuildPlayersPreservingMasterState("Skip silence set to $enabled")
    }

    /**
     * Stable audio mode: force the non-offload audio pipeline. See
     * [com.theveloper.pixelplay.data.preferences.UserPreferencesRepository.disableAudioOffloadFlow].
     */
    fun setStableAudioMode(enabled: Boolean) {
        val desiredOffload = !enabled
        if (audioOffloadEnabled == desiredOffload) return
        if (!desiredOffload) {
            disableAudioOffloadForSession("Stable audio mode enabled")
        } else {
            audioOffloadEnabled = true
            rebuildPlayersPreservingMasterState("Stable audio mode disabled")
        }
    }

    suspend fun resolveCloudUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        cachedResolvedUri(uriString)?.let { return@withContext it }

        val resolved: Uri? = when (uri.scheme) {
            "telegram" -> resolveTelegramUriAsync(uri, uriString)
            "netease" -> resolveNeteaseUriAsync(uriString)
            "qqmusic" -> resolveQqMusicUriAsync(uriString)
            "navidrome" -> resolveNavidromeUriAsync(uriString)
            "jellyfin" -> resolveJellyfinUriAsync(uriString)
            "gdrive" -> resolveGDriveUriAsync(uriString)
            "yt", "saavn" -> resolveYouTubeUriAsync(uriString)
            else -> if (uriString.startsWith("yt_") || uriString.startsWith("saavn_") || uriString.startsWith("audius_")) {
                resolveYouTubeUriAsync(uriString)
            } else null
        }

        if (resolved != null) {
            cacheResolvedUri(uriString, resolved)
            return@withContext resolved
        }
        uri
    }

    /**
     * Resolves an online music item without discarding the metadata needed by query fallbacks.
     */
    suspend fun resolveOnlineSongUri(song: Song): Uri = withContext(Dispatchers.IO) {
        val originalUri = MediaItemBuilder.playbackUri(song)
        val uriString = originalUri.toString()
        cachedResolvedUri(uriString)?.let { return@withContext it }

        val streamUrl = onlineMusicRepository.resolvePlaybackUrl(song)
        if (streamUrl.isNullOrBlank()) return@withContext originalUri

        val resolvedUri = Uri.parse(streamUrl)
        val cleanId = song.id
            .removePrefix("yt_")
            .removePrefix("saavn_")
        cacheResolvedUri(uriString, resolvedUri)
        cacheResolvedUri(song.id, resolvedUri)
        if (song.id.startsWith("saavn_") || song.contentUriString.startsWith("saavn://")) {
            cacheResolvedUri("saavn_$cleanId", resolvedUri)
            cacheResolvedUri("saavn://$cleanId", resolvedUri)
        } else {
            cacheResolvedUri("yt_$cleanId", resolvedUri)
            cacheResolvedUri("yt://$cleanId", resolvedUri)
        }
        resolvedUri
    }

    private suspend fun resolveYouTubeUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        val cleanId = when {
            uriString.startsWith("yt://") -> uriString.removePrefix("yt://")
            uriString.startsWith("yt_") -> uriString.removePrefix("yt_")
            uriString.startsWith("saavn://") -> uriString.removePrefix("saavn://")
            uriString.startsWith("saavn_") -> uriString.removePrefix("saavn_")
            else -> uriString
        }
        val isSaavn = uriString.startsWith("saavn")
        
        val snapshot = queueSnapshot
        val expectedMediaId = if (isSaavn) "saavn_$cleanId" else "yt_$cleanId"
        var matchedItem = snapshot.firstOrNull { it.mediaId == expectedMediaId }
        if (matchedItem == null && ::playerA.isInitialized) {
            val currentItem = playerA.currentMediaItem
            if (currentItem != null && currentItem.mediaId == expectedMediaId) {
                matchedItem = currentItem
            }
        }
        val title = matchedItem?.mediaMetadata?.title?.toString().orEmpty()
        val artist = matchedItem?.mediaMetadata?.artist?.toString().orEmpty()

        val streamUrl = onlineMusicRepository.resolvePlaybackUrl(
            Song(
                id = if (isSaavn) "saavn_$cleanId" else "yt_$cleanId",
                title = title,
                artist = artist,
                artistId = 0L,
                album = "",
                albumId = 0L,
                path = if (isSaavn) "saavn://$cleanId" else "yt://$cleanId",
                contentUriString = if (isSaavn) "saavn://$cleanId" else "yt://$cleanId",
                albumArtUriString = null,
                duration = 0L,
                mimeType = "audio/mp4",
                bitrate = 256,
                sampleRate = 44100
            )
        )
        streamUrl?.let {
            val resolvedUri = Uri.parse(it)
            cacheResolvedUri(uriString, resolvedUri)
            if (isSaavn) {
                cacheResolvedUri("saavn_$cleanId", resolvedUri)
                cacheResolvedUri("saavn://$cleanId", resolvedUri)
            } else {
                cacheResolvedUri("yt_$cleanId", resolvedUri)
                cacheResolvedUri("yt://$cleanId", resolvedUri)
            }
            resolvedUri
        }
    }

    private suspend fun resolveTelegramUriAsync(uri: Uri, uriString: String): Uri? = withContext(Dispatchers.IO) {
        val pathSegments = uri.pathSegments
        val fileId = if (pathSegments.isNotEmpty()) {
            telegramRepository.resolveTelegramUri(uriString)?.first
        } else {
            uri.host?.toIntOrNull()
        } ?: return@withContext null

        val fileInfo = telegramRepository.getFile(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            return@withContext Uri.fromFile(File(fileInfo.local.path))
        }

        if (!connectivityStateHolder.isOnline.value) {
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return@withContext null
        }

        if (!telegramStreamProxy.ensureReady(5_000L)) return@withContext null
        val proxyUrl = telegramStreamProxy.getProxyUrl(fileId, 0L)
        if (proxyUrl.isNotEmpty()) Uri.parse(proxyUrl) else null
    }

    private suspend fun resolveNeteaseUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!neteaseStreamProxy.ensureReady(5_000L)) return@withContext null
        neteaseStreamProxy.resolveNeteaseUri(uriString)?.let { Uri.parse(it) }
    }

    private suspend fun resolveQqMusicUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!qqMusicStreamProxy.ensureReady(5_000L)) return@withContext null
        qqMusicStreamProxy.warmUpStreamUrl(uriString)
        qqMusicStreamProxy.resolveQqMusicUri(uriString)?.let { Uri.parse(it) }
    }

    private suspend fun resolveNavidromeUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!navidromeStreamProxy.ensureReady(5_000L)) return@withContext null
        navidromeStreamProxy.warmUpStreamUrl(uriString)
        navidromeStreamProxy.resolveNavidromeUri(uriString)?.toUri()
    }

    private suspend fun resolveJellyfinUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!jellyfinStreamProxy.ensureReady(5_000L)) return@withContext null
        jellyfinStreamProxy.warmUpStreamUrl(uriString)
        jellyfinStreamProxy.resolveJellyfinUri(uriString)?.toUri()
    }

    private suspend fun resolveGDriveUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!connectivityStateHolder.isOnline.value) {
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return@withContext null
        }
        if (!gdriveStreamProxy.ensureReady(5_000L)) return@withContext null
        gdriveStreamProxy.resolveGDriveUri(uriString)?.toUri()
    }

    suspend fun resolveMediaItem(mediaItem: MediaItem): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = uri.scheme
        if (scheme !in CLOUD_PROXY_SCHEMES) return mediaItem
        val resolvedUri = resolveCloudUri(uri)
        return if (resolvedUri == uri) mediaItem else mediaItem.buildUpon().setUri(resolvedUri).build()
    }

    suspend fun prepareNext(target: TransitionTarget, startPositionMs: Long = 0L) {
        prepareNext(target.mediaItem, target.absoluteIndex, startPositionMs)
    }

    suspend fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        val preferredIndex = findMediaItemIndex(
            items = ensureQueueSnapshot(),
            mediaId = mediaItem.mediaId,
            preferAfterExclusive = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, queueSnapshot)
        )
        prepareNext(mediaItem, preferredIndex, startPositionMs)
    }

    private suspend fun prepareNext(mediaItem: MediaItem, preferredAbsoluteIndex: Int, startPositionMs: Long = 0L) {
        try {
            val snapshot = ensureQueueSnapshot()
            val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, snapshot)
            val targetIndex = when {
                preferredAbsoluteIndex in snapshot.indices &&
                    snapshot[preferredAbsoluteIndex].mediaId == mediaItem.mediaId -> preferredAbsoluteIndex
                else -> findMediaItemIndex(snapshot, mediaItem.mediaId, currentAbsoluteIndex)
            }
            val resolvedItem = resolveMediaItem(mediaItem)
            val auxiliaryPlayer = getOrCreateAuxiliaryPlayer()

            auxiliaryPlayer.stop()
            auxiliaryPlayer.clearMediaItems()

            if (targetIndex != C.INDEX_UNSET && snapshot.isNotEmpty()) {
                val count = snapshot.size
                val (start, end) = auxiliaryWindowBounds(targetIndex, count)
                val windowItems = ArrayList<MediaItem>(end - start)
                for (i in start until end) {
                    val item = snapshot[i]
                    windowItems.add(if (i == targetIndex) resolvedItem else item)
                }
                preparedWindowStartIndex = start
                preparedPlayerUsesWindowedQueue = count > MAX_AUXILIARY_TIMELINE_ITEMS
                auxiliaryPlayer.setMediaItems(windowItems, targetIndex - start, startPositionMs)
            } else {
                // Fallback: preserve existing timeline items instead of truncating to single item!
                val fullTimeline = if (snapshot.isNotEmpty()) snapshot else playerA.run {
                    (0 until mediaItemCount).map { getMediaItemAt(it) }
                }
                if (fullTimeline.isNotEmpty()) {
                    val updatedItems = fullTimeline.toMutableList()
                    val idx = updatedItems.indexOfFirst { it.mediaId == mediaItem.mediaId }
                    val finalIndex = if (idx >= 0) {
                        updatedItems[idx] = resolvedItem
                        idx
                    } else {
                        updatedItems.add(resolvedItem)
                        updatedItems.lastIndex
                    }
                    resetPreparedWindowState()
                    auxiliaryPlayer.setMediaItems(updatedItems, finalIndex, startPositionMs)
                } else {
                    resetPreparedWindowState()
                    auxiliaryPlayer.setMediaItem(resolvedItem)
                    auxiliaryPlayer.seekTo(startPositionMs)
                }
            }

            auxiliaryPlayer.prepare()
            auxiliaryPlayer.volume = 0f
            auxiliaryPlayer.pause()
        } catch (e: Exception) {
            resetPreparedWindowState()
            Timber.tag("TransitionDebug").e(e, "Failed to prepare next player")
        }
    }

    fun cancelNext() {
        val shouldPublishMasterPlayer = transitionRunning
        transitionJob?.cancel()
        transitionRunning = false
        resetPreparedWindowState()
        playerB?.takeIf { it.mediaItemCount > 0 }?.let { auxiliaryPlayer ->
            try {
                auxiliaryPlayer.stop()
                auxiliaryPlayer.clearMediaItems()
            } catch (e: Exception) { /* Ignore */ }
        }
        if (::playerA.isInitialized) {
            playerA.volume = 1f
            if (shouldPublishMasterPlayer) {
                onPlayerSwappedListeners.forEach { it(playerA) }
            }
        }
        incomingTrackReplayGainVolume = null
        setPauseAtEndOfMediaItems(false)
    }

    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionStartedAtMs = SystemClock.elapsedRealtime()
        transitionJob = scope.launch {
            try {
                performOverlapTransition(settings)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.tag("TransitionDebug").e(e, "Error performing transition")
                }
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                playerB?.stop()
            } finally {
                transitionRunning = false
                lastTransitionFinishedAtMs = SystemClock.elapsedRealtime()
                if (transitionStartedAtMs > 0L) {
                    PerformanceMetrics.recordTiming(
                        PerformanceMetrics.Timings.TRANSITION,
                        SystemClock.elapsedRealtime() - transitionStartedAtMs
                    )
                    transitionStartedAtMs = 0L
                }
                onTransitionFinishedListeners.forEach { it() }
            }
        }
    }

    private suspend fun performOverlapTransition(settings: TransitionSettings) {
        val auxiliaryPlayer = playerB
        if (auxiliaryPlayer == null || auxiliaryPlayer.mediaItemCount == 0) {
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        if (auxiliaryPlayer.playbackState == Player.STATE_IDLE) auxiliaryPlayer.prepare()
        if (auxiliaryPlayer.playbackState == Player.STATE_BUFFERING) {
            if (!awaitPlayerReady(auxiliaryPlayer, 3000L)) {
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                return
            }
        }

        val outgoingStartVolume = playerA.volume.coerceIn(0f, 1f)
        auxiliaryPlayer.volume = 0f
        if (!playerA.isPlaying && playerA.playbackState == Player.STATE_READY) playerA.play()
        auxiliaryPlayer.playWhenReady = true
        auxiliaryPlayer.play()

        val outgoingPlayer = playerA
        val incomingPlayer = auxiliaryPlayer

        incomingPlayer.repeatMode = outgoingPlayer.repeatMode
        incomingPlayer.shuffleModeEnabled = outgoingPlayer.shuffleModeEnabled
        outgoingPlayer.pauseAtEndOfMediaItems = true
        incomingPlayer.pauseAtEndOfMediaItems = false
        onTransitionDisplayPlayerListeners.forEach { it(incomingPlayer) }

        val duration = settings.durationMs.toLong().coerceAtLeast(500L)
        val stepMs = 32L
        val startedAtMs = SystemClock.elapsedRealtime()

        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtMost(duration)
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val volIn = envelope(progress, settings.curveIn)
            val volOut = 1f - envelope(progress, settings.curveOut)
            val incomingTarget = incomingTrackReplayGainVolume ?: 1f
            incomingPlayer.volume = (volIn * incomingTarget).coerceIn(0f, 1f)
            outgoingPlayer.volume = (volOut * outgoingStartVolume).coerceIn(0f, 1f)

            if (elapsed >= duration) break
            delay(stepMs)
        }

        outgoingPlayer.volume = 0f
        incomingPlayer.volume = incomingTrackReplayGainVolume ?: 1f
        incomingTrackReplayGainVolume = null

        removeMasterPlayerListeners(outgoingPlayer)

        playerA = incomingPlayer
        playerB = outgoingPlayer
        activeWindowStartIndex = preparedWindowStartIndex
        activePlayerUsesWindowedQueue = preparedPlayerUsesWindowedQueue
        resetPreparedWindowState()

        playerA.pauseAtEndOfMediaItems = false
        playerB?.pauseAtEndOfMediaItems = false
        addMasterPlayerListeners(playerA)
        if (playerA.playWhenReady) requestAudioFocus()

        onPlayerSwappedListeners.forEach { it(playerA) }
        _activeAudioSessionId.value = playerA.audioSessionId

        playerB?.pause()
        playerB?.stop()
        playerB?.clearMediaItems()

        setPauseAtEndOfMediaItems(false)
    }

    private fun ensureQueueSnapshot(): List<MediaItem> {
        // Single guard: isEmpty() short-circuits the windowed-queue size check, so
        // refreshQueueSnapshotFromMaster() is called at most once per invocation.
        if (queueSnapshot.isEmpty() ||
            (!activePlayerUsesWindowedQueue && queueSnapshot.size != playerA.mediaItemCount)
        ) {
            refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        }
        return queueSnapshot
    }

    private fun refreshQueueSnapshotFromMaster(windowStartIndex: Int, usesWindowedQueue: Boolean) {
        if (!::playerA.isInitialized) return

        val count = playerA.mediaItemCount
        if (count <= 0) {
            queueSnapshot = emptyList()
            activeWindowStartIndex = 0
            activePlayerUsesWindowedQueue = false
            return
        }

        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            items.add(playerA.getMediaItemAt(i))
        }

        queueSnapshot = items
        activeWindowStartIndex = windowStartIndex
        activePlayerUsesWindowedQueue = usesWindowedQueue
    }

    private fun resolveCurrentAbsoluteIndex(mediaItem: MediaItem, snapshot: List<MediaItem>): Int {
        if (snapshot.isEmpty()) return C.INDEX_UNSET

        val playerIndex = playerA.currentMediaItemIndex
        if (activePlayerUsesWindowedQueue) {
            val absoluteIndex = activeWindowStartIndex + playerIndex
            if (absoluteIndex in snapshot.indices &&
                snapshot[absoluteIndex].mediaId == mediaItem.mediaId
            ) {
                return absoluteIndex
            }
        } else if (playerIndex in snapshot.indices &&
            snapshot[playerIndex].mediaId == mediaItem.mediaId
        ) {
            return playerIndex
        }

        return findMediaItemIndex(snapshot, mediaItem.mediaId, preferAfterExclusive = C.INDEX_UNSET)
    }

    private fun findMediaItemIndex(
        items: List<MediaItem>,
        mediaId: String,
        preferAfterExclusive: Int
    ): Int {
        var fallback = C.INDEX_UNSET
        for (i in items.indices) {
            if (items[i].mediaId == mediaId) {
                if (preferAfterExclusive != C.INDEX_UNSET && i > preferAfterExclusive) return i
                if (fallback == C.INDEX_UNSET) fallback = i
            }
        }
        return fallback
    }

    private fun auxiliaryWindowBounds(targetIndex: Int, count: Int): Pair<Int, Int> {
        if (count <= MAX_AUXILIARY_TIMELINE_ITEMS) return 0 to count

        val halfWindow = MAX_AUXILIARY_TIMELINE_ITEMS / 2
        var start = (targetIndex - halfWindow).coerceAtLeast(0)
        var end = (start + MAX_AUXILIARY_TIMELINE_ITEMS).coerceAtMost(count)
        start = (end - MAX_AUXILIARY_TIMELINE_ITEMS).coerceAtLeast(0)
        return start to end
    }

    private fun resetPreparedWindowState() {
        preparedWindowStartIndex = 0
        preparedPlayerUsesWindowedQueue = false
    }

    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.playbackState == Player.STATE_READY) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_READY)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        } ?: false
    }


    fun release() {
        transitionJob?.cancel()
        preResolutionJob?.cancel()
        cancelAudioOffloadFallback()
        scope.coroutineContext[Job]?.cancel()
        abandonAudioFocus()
        if (::playerA.isInitialized) {
            removeMasterPlayerListeners(playerA)
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            playerA.release()
        }
        playerB?.release()
        playerB = null
        isReleased = true
    }
}
