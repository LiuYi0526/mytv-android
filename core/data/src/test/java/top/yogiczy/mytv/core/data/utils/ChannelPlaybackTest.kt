package top.yogiczy.mytv.core.data.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import java.util.TimeZone

class ChannelPlaybackTest {
    private val start = 1767225600000L // 2026-01-01T00:00:00Z
    private val end = start + 1800000L
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun url(line: ChannelLine) = ChannelPlayback.playbackUrl(line, start, end, end + 1000, zone)

    @Test fun explicitRulesSupportOrdinaryLiveUrls() {
        val line = ChannelLine("https://example.test/live.mpd", catchup = "default",
            catchupSource = "https://example.test/archive?b=\${(b)10}&e={e:timestampseconds}&d={duration}")
        assertTrue(ChannelPlayback.isSupported(line))
        assertEquals("https://example.test/archive?b=1767225600&e=1767227400&d=1800", url(line))
        assertFalse(ChannelPlayback.isSupported(ChannelLine(line.url)))
        assertNull(url(ChannelLine(line.url)))
    }

    @Test fun appendKeepsSignedParametersAndFragmentAndEncodesOnce() {
        val line = ChannelLine("https://example.test/live?token=a%2Bb==#keep", catchup = "append",
            catchupSource = "?seek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}&name=A%20B&eq=a=b==")
        assertEquals("https://example.test/live?token=a%2Bb==&seek=20260101080000-20260101083000&name=A%20B&eq=a%3Db%3D%3D#keep", url(line))
        assertEquals("https://example.test/live?seek=1767225600000",
            url(line.copy(url = "https://example.test/live", catchupSource = "seek={b}")))
    }

    @Test fun automaticPltvReplayKeepsLineNameOutOfUrl() {
        val line = ChannelLine("http://example.test/PLTV/1.m3u8?token=one\$线路1")
        assertTrue(ChannelPlayback.isSupported(line))
        assertEquals("http://example.test/tvod/1.m3u8?token=one&playseek=20260101080000-20260101083000", url(line))
        assertTrue(ChannelPlayback.isSupported(line.copy(url = "http://example.test/TVOD/1")))
        assertTrue(url(line.copy(pltv2tvod = false))!!.contains("/PLTV/"))
    }

    @Test fun explicitRuleTakesPriorityOverPltvFallback() {
        val line = ChannelLine("https://example.test/PLTV/1", catchup = "default",
            catchupSource = "https://archive.test/PLTV/1/{b:10}")
        assertEquals("https://archive.test/tvod/1/1767225600", url(line))
        assertEquals("https://archive.test/PLTV/1/1767225600", url(line.copy(pltv2tvod = false)))
        assertFalse(ChannelPlayback.isSupported(line.copy(catchup = "none")))
        assertFalse(ChannelPlayback.isSupported(line.copy(catchup = "shift")))
        assertFalse(ChannelPlayback.isSupported(line.copy(url = "https://example.test/live", catchupSource = "")))
    }

    @Test fun yykmTimeAliasesFormatsAndZones() {
        val line = ChannelLine("http://example.test/live", catchup = "default", catchupSource =
            "http://example.test/{b}/{e}/{start:YmdHMS|UTC}/{end:yyyyMMddHHmmss|Asia/Shanghai}/{now:13}/{lutc:10}")
        assertEquals("http://example.test/1767225600000/1767227400000/20260101000000/20260101083000/1767227401000/1767227401", url(line))
        assertEquals("http://example.test/2026-01-01T00:00:00.000Z/2026-01-01T00:30:00.000Z",
            url(line.copy(catchupSource = "http://example.test/{utc}/{utcend}")))
    }

    @Test fun invalidTemplateFailsWithoutChangingLiveLine() {
        val line = ChannelLine("http://example.test/live", catchup = "append", catchupSource = "?start={unknown}")
        assertNull(url(line))
        assertNull(url(line.copy(catchupSource = "?start={b:yyyyMMdd|Invalid/Zone}")))
        assertNull(url(line.copy(catchup = "default", catchupSource = "not an absolute URL")))
        assertNull(ChannelPlayback.playbackUrl(line, end, start))
        assertEquals("http://example.test/live", line.playableUrl)
    }

    @Test fun metadataSurvivesSerializationAndPlaybackCopy() {
        val old = Json.decodeFromString<ChannelLine>("""{"url":"http://example.test/PLTV/1"}""")
        assertTrue(ChannelPlayback.isSupported(old))
        val line = old.copy(httpUserAgent = "player", manifestType = "mpd", licenseType = "com.widevine.alpha",
            licenseKey = "https://license.test", catchup = "append", catchupSource = "?b={b}", pltv2tvod = false)
        val restored = Json.decodeFromString<ChannelLine>(Json.encodeToString(line))
        assertEquals(line, restored)
        val playback = restored.copy(url = url(restored)!!)
        assertEquals(line.httpUserAgent, playback.httpUserAgent)
        assertEquals(line.manifestType, playback.manifestType)
        assertEquals(line.licenseType, playback.licenseType)
        assertEquals(line.licenseKey, playback.licenseKey)
    }
}
