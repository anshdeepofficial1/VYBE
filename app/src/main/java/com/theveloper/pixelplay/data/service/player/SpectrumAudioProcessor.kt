package com.theveloper.pixelplay.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pure pass-through that listens to what the sink is about to play and feeds [SpectrumBus].
 *
 * Being a pass-through is the whole point: the analyser sits in the audio processor chain without
 * changing one byte of audio, so enabling the visualiser can never alter what the listener hears,
 * change the sample rate, or interfere with the equaliser, replay gain or the crossfade engine.
 * [queueInput] copies the buffer through untouched and only reads a duplicate of it.
 *
 * Analysis cost is bounded on purpose: a 1024 point FFT every ~33 ms (thirty frames a second), with
 * nothing at all when the visualiser is switched off in Settings.
 */
@UnstableApi
class SpectrumAudioProcessor(
    private val bus: SpectrumBus,
) : AudioProcessor {

    private val fft = Fft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE)
    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val magnitudes = FloatArray(FFT_SIZE / 2)
    private val hann = FloatArray(FFT_SIZE)
    private val levels = FloatArray(SpectrumBus.BAND_COUNT)
    private val ring = FloatArray(FFT_SIZE)

    private var format: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var channelCount = 0
    private var sampleRateHz = 0
    private var ringPosition = 0
    private var ringCount = 0
    private var lastAnalysisAt = 0L
    /** Band [i] collects FFT bins in `binRanges[i] until binRanges[i + 1]`. */
    private var binRanges = IntArray(SpectrumBus.BAND_COUNT + 1)

    init {
        for (index in 0 until FFT_SIZE) {
            // Hann window: keeps a note's energy inside its own bins so the bars do not smear.
            hann[index] = (0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))).toFloat()
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        format = inputAudioFormat
        channelCount = inputAudioFormat.channelCount
        sampleRateHz = inputAudioFormat.sampleRate
        computeBandBins()
        // Unchanged format in, unchanged format out: this processor owns no audio of its own.
        return inputAudioFormat
    }

    /**
     * Always active. An inactive processor is bypassed by the sink, and this one must stay in the
     * path so its output buffer can mirror the input exactly.
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        if (bus.enabled && format != AudioFormat.NOT_SET) {
            collect(inputBuffer)
        }

        outputBuffer = ensureOutputBuffer(size)
        // Consumes the input buffer, which is exactly what the sink expects after queueInput.
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()

        if (bus.enabled && readyToAnalyse()) {
            lastAnalysisAt = SystemClock.elapsedRealtime()
            analyse()
        }
    }

    /**
     * Copies the newest PCM into the rolling window without moving the caller's position, so the
     * same bytes are still available for the pass-through copy above.
     */
    private fun collect(inputBuffer: ByteBuffer) {
        if (channelCount <= 0) return
        val duplicate = inputBuffer.duplicate().order(NATIVE_ORDER)
        val bytesPerSample = when (format.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return // Integer 8/24/32-bit input: analysis skipped, playback untouched.
        }
        val bytesPerFrame = bytesPerSample * channelCount
        val frames = duplicate.remaining() / bytesPerFrame
        if (frames == 0) return

        var offset = duplicate.position()
        repeat(frames) {
            var acc = 0f
            repeat(channelCount) { channel ->
                val index = offset + channel * bytesPerSample
                acc += if (format.encoding == C.ENCODING_PCM_16BIT) {
                    readShort(duplicate, index) / 32768f
                } else {
                    readFloat(duplicate, index)
                }
            }
            pushSample(acc / channelCount)
            offset += bytesPerFrame
        }
    }

    private fun pushSample(sample: Float) {
        ring[ringPosition] = sample
        ringPosition++
        if (ringPosition == FFT_SIZE) ringPosition = 0
        if (ringCount < FFT_SIZE) ringCount++
    }

    private fun readyToAnalyse(): Boolean {
        if (channelCount <= 0 || sampleRateHz <= 0) return false
        if (ringCount < FFT_SIZE / 2) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return false
        return true
    }

    private fun analyse() {
        // Oldest sample first, silence-padded at the front until the window has filled once.
        if (ringCount < FFT_SIZE) {
            java.util.Arrays.fill(window, 0, FFT_SIZE - ringCount, 0f)
            System.arraycopy(ring, 0, window, FFT_SIZE - ringCount, ringCount)
        } else {
            System.arraycopy(ring, ringPosition, window, 0, FFT_SIZE - ringPosition)
            System.arraycopy(ring, 0, window, FFT_SIZE - ringPosition, ringPosition)
        }

        for (index in 0 until FFT_SIZE) {
            val value = window[index] * hann[index]
            real[index] = value
            imag[index] = 0f
        }
        fft.transform(real, imag)

        for (index in 0 until magnitudes.size) {
            val re = real[index]
            val im = imag[index]
            magnitudes[index] = sqrt(re * re + im * im)
        }

        // A full scale sine lands on |X| ≈ FFT_SIZE / 4 once Hann's 0.5 coherent gain is undone,
        // so this normalisation puts peak loudness at 1.0 for every sample rate.
        val norm = 4f / FFT_SIZE
        var anyEnergy = false
        for (band in 0 until SpectrumBus.BAND_COUNT) {
            val start = binRanges[band]
            val end = binRanges[band + 1].coerceAtLeast(start + 1)
            var sum = 0f
            for (bin in start until end) sum += magnitudes[bin]
            val amplitude = (sum / (end - start)) * norm
            // Square root lifts quiet detail into a visible range without clipping loud passages.
            val level = sqrt(amplitude.coerceIn(0f, 1f))
            levels[band] = level
            if (level > 0.01f) anyEnergy = true
        }
        if (anyEnergy) bus.publish(levels)
    }

    /** Logarithmic band edges, from a low bass shelf up to the top of a music signal. */
    private fun computeBandBins() {
        val nyquist = sampleRateHz / 2f
        if (nyquist <= 0f) {
            java.util.Arrays.fill(binRanges, 0)
            return
        }
        val lowest = 45f
        val highest = 16_000f.coerceAtMost(nyquist * 0.98f)
        val bandCount = SpectrumBus.BAND_COUNT
        val ratio = (highest / lowest).toDouble().pow(1.0 / bandCount).toFloat()
        var frequency = lowest.toDouble()
        for (band in 0 until bandCount) {
            binRanges[band] = binFor(frequency.toFloat(), nyquist)
            frequency *= ratio
        }
        binRanges[bandCount] = binFor(highest, nyquist) + 1
        // Keep every band populated, even on very low rate sources.
        for (band in 0 until bandCount) {
            if (binRanges[band + 1] <= binRanges[band]) binRanges[band + 1] = binRanges[band] + 1
        }
        binRanges[bandCount] = binRanges[bandCount].coerceAtMost(FFT_SIZE / 2)
        for (band in 0 until bandCount) {
            if (binRanges[band] >= FFT_SIZE / 2) binRanges[band] = FFT_SIZE / 2 - 1
        }
    }

    private fun binFor(frequency: Float, nyquist: Float): Int {
        val bin = (frequency * FFT_SIZE / (nyquist * 2f)).toInt()
        return bin.coerceIn(0, FFT_SIZE / 2 - 1)
    }

    private fun ensureOutputBuffer(size: Int): ByteBuffer {
        return if (outputBuffer.capacity() < size) {
            ByteBuffer.allocateDirect(size).order(NATIVE_ORDER).also { outputBuffer = it }
        } else {
            outputBuffer.clear()
            outputBuffer
        }
    }

    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    @Deprecated("Media3 AudioProcessor now prefers flush(StreamMetadata); kept for interface compatibility")
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        ringPosition = 0
        ringCount = 0
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        format = AudioFormat.NOT_SET
        channelCount = 0
        sampleRateHz = 0
        ringPosition = 0
        ringCount = 0
        lastAnalysisAt = 0L
    }

    private companion object {
        const val FFT_SIZE = 1024
        const val ANALYSIS_INTERVAL_MS = 33L
        val NATIVE_ORDER: ByteOrder = ByteOrder.nativeOrder()
        private val NATIVE_BIG_ENDIAN = NATIVE_ORDER == ByteOrder.BIG_ENDIAN

        private fun readShort(buffer: ByteBuffer, index: Int): Float {
            val low = buffer.get(index).toInt() and 0xFF
            val high = buffer.get(index + 1).toInt() and 0xFF
            val bits = if (NATIVE_BIG_ENDIAN) {
                (low shl 8) or high
            } else {
                (high shl 8) or low
            }
            return bits.toShort().toFloat()
        }

        private fun readFloat(buffer: ByteBuffer, index: Int): Float {
            val b0 = buffer.get(index).toInt() and 0xFF
            val b1 = buffer.get(index + 1).toInt() and 0xFF
            val b2 = buffer.get(index + 2).toInt() and 0xFF
            val b3 = buffer.get(index + 3).toInt() and 0xFF
            val bits = if (NATIVE_BIG_ENDIAN) {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            } else {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            }
            return Float.fromBits(bits)
        }
    }
}

/**
 * In-place iterative radix-2 Cooley-Tukey transform over [FFT_SIZE] points.
 *
 * A real FFT library would pull in a native dependency for something that only ever needs to turn
 * 1024 samples into 512 magnitudes thirty times a second; this is a few hundred flops a frame.
 */
private class Fft(private val size: Int) {
    private val cos = FloatArray(size / 2)
    private val sin = FloatArray(size / 2)
    private val reversed = IntArray(size)

    init {
        for (index in 0 until size / 2) {
            val angle = 2.0 * PI * index / size
            cos[index] = cos(angle).toFloat()
            sin[index] = sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (index in 0 until size) {
            var source = index
            var target = 0
            repeat(bits) {
                target = (target shl 1) or (source and 1)
                source = source shr 1
            }
            reversed[index] = target
        }
    }

    fun transform(real: FloatArray, imag: FloatArray) {
        for (index in 0 until size) {
            val swap = reversed[index]
            if (swap <= index) continue
            val realValue = real[index]
            real[index] = real[swap]
            real[swap] = realValue
            val imagValue = imag[index]
            imag[index] = imag[swap]
            imag[swap] = imagValue
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                var twiddle = 0
                var index = start
                while (index < start + half) {
                    val pair = index + half
                    val cosValue = cos[twiddle]
                    val sinValue = sin[twiddle]
                    val tempReal = cosValue * real[pair] + sinValue * imag[pair]
                    val tempImag = cosValue * imag[pair] - sinValue * real[pair]
                    real[pair] = real[index] - tempReal
                    imag[pair] = imag[index] - tempImag
                    real[index] += tempReal
                    imag[index] += tempImag
                    index++
                    twiddle += step
                }
                start += length
            }
            length = length shl 1
        }
    }
}
