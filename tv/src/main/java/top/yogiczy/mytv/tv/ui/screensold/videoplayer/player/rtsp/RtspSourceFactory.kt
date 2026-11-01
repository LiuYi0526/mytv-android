package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.rtsp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.av3a.Av3aPayloadReaderFactory

/** Keep RTSP independent of HTTP data-source options and retain TS/AV3A readers. */
@OptIn(UnstableApi::class)
object RtspSourceFactory {
    fun create(uri: Uri, userAgent: String, timeoutMs: Long, forceTcp: Boolean): RtspMediaSource =
        RtspMediaSource.Factory()
            .setUserAgent(userAgent)
            .setTimeoutMs(timeoutMs.coerceAtLeast(1))
            .setForceUseRtpTcp(forceTcp)
            .setTsPayloadReaderFactory(Av3aPayloadReaderFactory())
            .createMediaSource(MediaItem.fromUri(uri))
}
