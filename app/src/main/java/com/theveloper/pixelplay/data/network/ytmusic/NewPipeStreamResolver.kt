package com.theveloper.pixelplay.data.network.ytmusic

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a YouTube video id to a current, signed stream URL containing playable audio.
 *
 * The hand-written Innertube clients remain as fallbacks in [YouTubeMusicEngine], but they
 * cannot decipher every signature variant and are deliberately not the primary playback path.
 */
@Singleton
class NewPipeStreamResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val initialized = AtomicBoolean(false)

    fun resolve(
        videoId: String,
        preferLowBitrate: Boolean = false,
        targetBitrate: Int? = null,
    ): String? {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isBlank()) return null

        return runCatching {
            ensureInitialized()
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$cleanId"
            )
            selectBestAudio(info.audioStreams, preferLowBitrate, targetBitrate)?.content
                // Some current YouTube responses expose only one muxed 360p stream. The app's
                // audio-only renderer can still extract and play its audio track.
                ?: selectPlayableMuxedStream(info.videoStreams)?.content
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "NewPipe stream extraction failed for %s", cleanId)
        }.getOrNull()
    }

    /**
     * Resolves a muxed (audio + video) stream for the inline player. Keeping this beside the
     * audio resolver means the UI never has to embed a YouTube web page or own extractor state.
     */
    suspend fun resolveVideo(
        videoId: String,
        preferLowData: Boolean = false,
        targetHeight: Int? = null,
    ): String? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isBlank()) return@withContext null

        runCatching {
            ensureInitialized()
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$cleanId"
            )
            if (targetHeight != null) {
                selectMuxedStreamByQuality(info.videoStreams, targetHeight)?.content
                    ?: selectPlayableMuxedStream(info.videoStreams, preferLowData)?.content
            } else {
                selectPlayableMuxedStream(info.videoStreams, preferLowData)?.content
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "NewPipe video extraction failed for %s", cleanId)
        }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(OkHttpNewPipeDownloader(okHttpClient))
        }
    }

    internal fun selectBestAudio(
        streams: List<AudioStream>,
        preferLowBitrate: Boolean = false,
        targetBitrate: Int? = null,
    ): AudioStream? = streams
        .asSequence()
        // YouTube audio-only URLs are commonly labelled DASH even though each AudioStream
        // contains a direct signed URL that Media3 can load on its own.
        .filter { it.isUrl && it.content.startsWith("http") }
        // Prefer a broadly supported M4A/AAC stream, then choose the highest bitrate.
        .sortedWith(if (targetBitrate != null) {
            compareBy<AudioStream> {
                kotlin.math.abs(
                    (it.averageBitrate.takeIf { bitrate -> bitrate > 0 } ?: targetBitrate) - targetBitrate
                )
            }.thenByDescending { it.format?.name?.contains("M4A", ignoreCase = true) == true }
        } else if (preferLowBitrate) {
            compareBy<AudioStream> { it.averageBitrate.takeIf { bitrate -> bitrate > 0 } ?: Int.MAX_VALUE }
                .thenByDescending { it.format?.name?.contains("M4A", ignoreCase = true) == true }
        } else {
            compareByDescending<AudioStream> { stream ->
                stream.format?.name?.contains("M4A", ignoreCase = true) == true
            }.thenByDescending { it.averageBitrate }
        })
        .firstOrNull()

    internal fun selectPlayableMuxedStream(
        streams: List<VideoStream>,
        preferLowData: Boolean = false,
    ): VideoStream? = streams
        .asSequence()
        .filter { it.isUrl && it.content.startsWith("http") }
        .sortedWith(
            if (preferLowData) {
                compareBy<VideoStream> { it.height.takeIf { height -> height > 0 } ?: Int.MAX_VALUE }
            } else {
                compareByDescending<VideoStream> { it.height }
            }
        )
        .firstOrNull()

    internal fun selectMuxedStreamByQuality(
        streams: List<VideoStream>,
        targetHeight: Int,
    ): VideoStream? = streams
        .asSequence()
        .filter { it.isUrl && it.content.startsWith("http") }
        .minByOrNull { kotlin.math.abs((it.height.takeIf { h -> h > 0 } ?: 360) - targetHeight) }

    private class OkHttpNewPipeDownloader(
        private val client: OkHttpClient
    ) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(null)
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }

            client.newCall(builder.build()).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body.string(),
                    response.request.url.toString()
                )
            }
        }
    }

    private companion object {
        const val TAG = "NewPipeStreamResolver"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
