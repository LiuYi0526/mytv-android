package top.yogiczy.mytv.core.data.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import java.util.TimeZone
import top.yogiczy.mytv.core.data.media.PlaybackRange

class ChannelPlaybackTest {
    private val start = 1767225600000L // 2026-01-01T00:00:00Z
    private val end = start + 1800000L
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun url(line: ChannelLine) = ChannelPlayback.playbackUrl(line, start, end, end + 1000, zone)

    @Test fun reloadRegeneratesPlayseekFromLiveLineWithoutLosingMetadataOrProgrammeRange() {
        val line = ChannelLine("http://example.test/PLTV/1.m3u8?token=a%2Bb==\$备用",
            httpUserAgent = "custom-UA", manifestType = "hls", licenseType = "clearkey", licenseKey = "key")
        val now = end + 600_000
        val request = requireNotNull(ChannelPlayback.reloadRequest(line, PlaybackRange(start, end), start + 600_000, now, zone))
        assertEquals("http://example.test/TVOD/1.m3u8?token=a%2Bb==&playseek=20260101081000-20260101084000", request.line.url)
        assertEquals(line.copy(url = request.line.url), request.line)
        assertEquals(start, request.range.startAt)
        assertEquals(start + 600_000, request.range.sourceStartAt)
        assertEquals(end, request.range.endAt)
        assertEquals(end - start, request.range.durationMs)
        val again = requireNotNull(ChannelPlayback.reloadRequest(line, request.range, start + 300_000, now + 60_000, zone))
        assertEquals(1, Regex("playseek=").findAll(again.line.url).count())
        assertTrue(again.line.url.contains("playseek=20260101080500-20260101084100"))
    }

    @Test fun reloadUsesExplicitTemplateAndRejectsUnavailableHistory() {
        val line = ChannelLine("https://example.test/live", catchup = "append", catchupSource = "?b={b:10}&e={e:10}", pltv2tvod = false)
        val range = PlaybackRange(start, end)
        assertTrue(ChannelPlayback.reloadRequest(line, range, start + 60_000, end, zone)!!.line.url.contains("b=1767225660"))
        assertNull(ChannelPlayback.reloadRequest(line.copy(catchup = "none"), range, start + 60_000, end, zone))
        assertNull(ChannelPlayback.reloadRequest(line, range, end, end, zone))
        assertNull(ChannelPlayback.reloadRequest(line, range, start, start - 1, zone))
        assertNull(ChannelPlayback.reloadRequest(line, range, start + 120_000, start + 60_000, zone))
    }

    @Test fun reloadHonoursM3uFullUrlAndAppendTemplatesWithCurrentRequestEnd() {
        val now = end + 600_000
        val range = PlaybackRange(start, end)
        val target = start + 600_000
        val full = ChannelLine("http://example.test/PLTV/live", catchup = "default", pltv2tvod = false,
            catchupSource = "https://archive.test/{b:10}/{e:10}?duration={duration}&fixed=keep")
        val request = requireNotNull(ChannelPlayback.reloadRequest(full, range, target, now, zone))
        assertEquals("https://archive.test/1767226200/1767228000?duration=1800&fixed=keep", request.line.url)
        assertEquals(end, request.range.endAt)
        val append = full.copy(catchup = "append", catchupSource = "?startTime={b:10}&stopTime={e:10}&fixed=keep")
        assertEquals("http://example.test/PLTV/live?startTime=1767226200&stopTime=1767228000&fixed=keep",
            ChannelPlayback.reloadRequest(append, range, target, now, zone)!!.line.url)
        assertFalse(request.line.url.contains("playseek"))
    }

    @Test fun continuousRequestsCanStartAfterTheOriginalProgrammeAndUseTheSameM3uTemplate() {
        val line = ChannelLine("https://example.test/live", catchup = "append", catchupSource = "?start={b:10}&end={e:10}")
        val now = end + 600_000
        val request = requireNotNull(ChannelPlayback.reloadRequest(line, PlaybackRange(start, end), end + 60_000, now, zone, continuous = true))
        assertTrue(request.range.continuous)
        assertEquals(end + 60_000, request.range.sourceStartAt)
        assertEquals("https://example.test/live?start=1767227460&end=1767228000", request.line.url)
    }

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
        assertEquals("http://example.test/TVOD/1.m3u8?token=one&playseek=20260101080000-20260101083000", url(line))
        assertTrue(ChannelPlayback.isSupported(line.copy(url = "http://example.test/TVOD/1")))
        assertTrue(url(line.copy(pltv2tvod = false))!!.contains("/PLTV/"))
    }

    @Test fun pltvReplacementUsesUppercaseAndKeepsExistingTvodSpelling() {
        for (path in listOf("PLTV", "pltv", "PlTv")) {
            val line = ChannelLine("http://example.test/$path/1")
            assertTrue(url(line)!!.startsWith("http://example.test/TVOD/1?"))
            assertTrue(url(line.copy(pltv2tvod = false))!!.startsWith("http://example.test/$path/1?"))
        }
        for (path in listOf("TVOD", "tvod")) {
            assertTrue(url(ChannelLine("http://example.test/$path/1"))!!
                .startsWith("http://example.test/$path/1?"))
        }
    }

    @Test fun explicitRuleTakesPriorityOverPltvFallback() {
        val line = ChannelLine("https://example.test/PLTV/1", catchup = "default",
            catchupSource = "https://archive.test/PLTV/1/{b:10}")
        assertEquals("https://archive.test/TVOD/1/1767225600", url(line))
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
