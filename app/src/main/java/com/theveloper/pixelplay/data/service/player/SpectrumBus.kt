package com.theveloper.pixelplay.data.service.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared home for the live audio spectrum, so both engine players and every drawing surface agree
 * on one set of numbers.
 *
 * The analysing processor runs on the audio sink thread, which must never block on UI work, so it
 * only hands over raw band levels and this class does the shaping:
 *
 *  - attack/decay smoothing, which is what makes bars feel tied to the kick instead of flickering
 *  - an idle decay, so a paused track lets the bars fall back to silence instead of freezing at a
 *    random height (the audio thread simply stops feeding buffers while paused)
 *  - conflation through [StateFlow], so a slow frame drops intermediate values rather than queueing
 *    them up behind the playback thread
 *
 * Both engine players publish into the same bus. During a crossfade the two spectra interleave,
 * which is exactly what the mix sounds like.
 */
@Singleton
class SpectrumBus @Inject constructor() {

    private val _spectrum = MutableStateFlow(FloatArray(BAND_COUNT))
    /** 0..1 per band, low frequency first. A fresh array per emission, so it always changes identity. */
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    /**
     * Read by the audio processors to decide whether analysis is worth the CPU at all. Nothing
     * outside the player draws bars when this is off, so the FFT is skipped entirely.
     */
    @Volatile
    var enabled: Boolean = true
        private set

    private val lock = Any()
    private val smoothed = FloatArray(BAND_COUNT)
    private var lastPublishAt = 0L
    private var publishedSilence = true

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            while (isActive) {
                delay(DECAY_TICK_MS)
                decayWhenIdle()
            }
        }
    }

    /** Turns the whole analyser off, keeping the audio path untouched. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            synchronized(lock) {
                java.util.Arrays.fill(smoothed, 0f)
                publishedSilence = true
                _spectrum.value = FloatArray(BAND_COUNT)
            }
        }
    }

    /** Called from the audio thread with fresh, already-decoded band levels in 0..1. */
    fun publish(raw: FloatArray) {
        if (!enabled) return
        synchronized(lock) {
            val count = minOf(raw.size, smoothed.size)
            for (index in 0 until count) {
                val target = raw[index].coerceIn(0f, 1f)
                val current = smoothed[index]
                // Fast rise, slower fall: a transient should jump on the frame it happens and bleed
                // away over a few frames rather than disappear in one.
                smoothed[index] = if (target >= current) {
                    current + (target - current) * ATTACK
                } else {
                    current + (target - current) * DECAY
                }
            }
            lastPublishAt = SystemClock.elapsedRealtime()
            publishedSilence = false
            _spectrum.value = smoothed.copyOf()
        }
    }

    /**
     * Lets the bars fall when nothing is being played. Without this a paused track leaves the last
     * measured spectrum painted on screen forever, which reads as a frozen graphic rather than a
     * visualiser that stopped.
     */
    private fun decayWhenIdle() {
        synchronized(lock) {
            if (SystemClock.elapsedRealtime() - lastPublishAt < IDLE_THRESHOLD_MS) return

            var peak = 0f
            for (index in smoothed.indices) {
                val value = smoothed[index]
                if (value <= 0f) continue
                val decayed = value * IDLE_DECAY_FACTOR
                smoothed[index] = if (decayed < MIN_LEVEL) 0f else decayed
                if (smoothed[index] > peak) peak = smoothed[index]
            }

            if (peak <= 0f) {
                // Emit the zeroed array exactly once so the drawing side settles, then stay quiet
                // instead of pushing a new (identical) array every tick forever.
                if (publishedSilence) return
                publishedSilence = true
            } else {
                publishedSilence = false
            }
            _spectrum.value = smoothed.copyOf()
        }
    }

    companion object {
        /** Bands drawn by the visualiser, lowest frequency first. */
        const val BAND_COUNT = 28

        private const val ATTACK = 0.62f
        private const val DECAY = 0.24f
        private const val DECAY_TICK_MS = 45L
        private const val IDLE_THRESHOLD_MS = 110L
        private const val IDLE_DECAY_FACTOR = 0.72f
        private const val MIN_LEVEL = 0.004f
    }
}
