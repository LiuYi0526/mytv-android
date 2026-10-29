package top.yogiczy.mytv.tv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import java.util.TimeZone

/** Exercise Android's ICU regex engine, which is stricter than the desktop JDK. */
@RunWith(AndroidJUnit4::class)
class ChannelPlaybackAndroidTest {
    private val start = 1767225600000L
    private val end = start + 1800000L

    @Test fun checkingReplayAvailabilityDoesNotCrashOnAndroid() {
        assertFalse(ChannelPlayback.isSupported(ChannelLine("https://example.test/live")))
        assertTrue(ChannelPlayback.isSupported(ChannelLine("https://example.test/PLTV/1")))
    }

    @Test fun defaultAndAppendTemplatesExpandOnAndroid() {
        val line = ChannelLine("https://example.test/PLTV/1?token=a%2Bb==", catchup = "default",
            catchupSource = "https://archive.test/{b:10}?end=\${(e)10}")
        assertEquals("https://archive.test/1767225600?end=1767227400",
            ChannelPlayback.playbackUrl(line, start, end))
        assertEquals("https://example.test/tvod/1?token=a%2Bb==&playseek=20260101080000-20260101083000",
            ChannelPlayback.playbackUrl(line.copy(catchup = null, catchupSource = null), start, end,
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals("https://example.test/tvod/1?token=a%2Bb==&b=1767225600&e=1767227400",
            ChannelPlayback.playbackUrl(line.copy(catchup = "append", catchupSource = "?b={b:10}&e=\${(e)10}"), start, end))
    }
}
