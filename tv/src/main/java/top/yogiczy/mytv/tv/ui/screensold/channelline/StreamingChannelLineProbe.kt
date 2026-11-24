package top.yogiczy.mytv.tv.ui.screensold.channelline

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.antmedia.rtmp_client.RtmpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.rtsp.RtspSourceFactory
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Measures a stream's initial response without switching the current player. */
@OptIn(UnstableApi::class)
internal object StreamingChannelLineProbe {
    private const val TIMEOUT_MS = 5_000
    private val rtspPermits = Semaphore(2)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val rtspHandler by lazy {
        Handler(HandlerThread("Channel RTSP probe").apply { start() }.looper)
    }
    private val rtspDispatcher by lazy { rtspHandler.asCoroutineDispatcher() }

    /** Returns null for a scheme that has no probe. */
    suspend fun measure(context: Context, uri: Uri, userAgent: String, forceRtspTcp: Boolean): Long? {
        val probe: suspend () -> Long = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "rtsp" -> suspend { measureRtsp(context.applicationContext, uri, userAgent, forceRtspTcp) }
            "rtmp" -> suspend { measureBlocking { startedAt -> readRtmp(uri, startedAt) } }
            "udp", "rtp" -> suspend { measureBlocking { startedAt -> readDatagram(uri, startedAt) } }
            else -> return null
        }
        return withTimeoutOrNull(TIMEOUT_MS.toLong()) { probe() }
            ?: throw SocketTimeoutException("Stream probe timed out")
    }

    private suspend fun measureRtsp(context: Context, uri: Uri, userAgent: String, forceTcp: Boolean): Long =
        rtspPermits.withPermit {
            withContext(rtspDispatcher) {
                val player = ExoPlayer.Builder(context)
                    .setLooper(rtspHandler.looper)
                    .setReleaseTimeoutMs(500)
                    .build()
                try {
                    val startedAt = System.nanoTime()
                    suspendCancellableCoroutine { result ->
                        player.addListener(object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                if (!tracks.isEmpty && result.isActive) result.resume(elapsedMs(startedAt))
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                if (result.isActive) result.resumeWithException(error)
                            }
                        })
                        player.setMediaSource(
                            RtspSourceFactory.create(uri, userAgent, TIMEOUT_MS.toLong(), forceTcp),
                        )
                        player.prepare()
                    }
                } finally {
                    player.release()
                }
            }
        }

    private suspend fun measureBlocking(read: (Long) -> Long): Long = suspendCancellableCoroutine { result ->
        // Native RTMP I/O cannot safely be closed from another thread. Limit blocked workers and
        // let the UI deadline cancel the wait independently of socket cleanup.
        ioDispatcher.dispatch(result.context, Runnable {
            if (!result.isActive) return@Runnable
            try {
                result.resume(read(System.nanoTime()))
            } catch (error: Exception) {
                result.resumeWithException(error)
            } catch (error: LinkageError) {
                result.resumeWithException(IOException("Streaming probe unavailable", error))
            }
        })
    }

    private fun readRtmp(uri: Uri, startedAt: Long): Long {
        val client = RtmpClient()
        client.setSendTimeout(TIMEOUT_MS)
        client.setReceiveTimeout(TIMEOUT_MS)
        client.open(uri.toString(), false)
        try {
            if (client.read(ByteArray(1), 0, 1) <= 0) throw EOFException("Empty RTMP stream")
            return elapsedMs(startedAt)
        } finally {
            client.close()
        }
    }

    private fun readDatagram(uri: Uri, startedAt: Long): Long {
        val source = UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, TIMEOUT_MS)
        try {
            source.open(DataSpec(uri))
            if (source.read(ByteArray(1), 0, 1) <= 0) throw EOFException("Empty datagram")
            return elapsedMs(startedAt)
        } finally {
            source.close()
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1)
}
