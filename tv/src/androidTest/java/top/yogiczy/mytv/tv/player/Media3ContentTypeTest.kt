package top.yogiczy.mytv.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.Media3ContentType
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.repositories.iptv.parser.M3uIptvParser
import top.yogiczy.mytv.core.data.repositories.iptv.parser.IptvParser.ChannelItem.Companion.toChannelGroupList
import top.yogiczy.mytv.core.data.utils.Globals

@OptIn(UnstableApi::class)
class Media3ContentTypeTest {
    @Test fun parsedChannelsKeepHintsThroughActualGroupCacheAndSourceSelection() = runBlocking {
        Globals.resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (hint in listOf("hls", "m3u8", " HlS ", " M3U8 ")) {
            val groups = M3uIptvParser().parse("""
                #EXTINF:-1 group-title="A;B",Channel
                #KODIPROP:inputstream.adaptive.manifest_type="$hint"
                #EXTVLCOPT:http-user-agent=CacheTest/1
                https://example.test/entry
            """.trimIndent()).toChannelGroupList()
            val cached = Globals.json.decodeFromString<ChannelGroupList>(Globals.json.encodeToString(groups))
            assertEquals(2, cached.size)
            cached.forEach { group ->
                val line = group.channelList.single().lineList.single()
                assertEquals(hint, line.manifestType)
                assertEquals("CacheTest/1", line.httpUserAgent)
                assertEquals(C.CONTENT_TYPE_HLS, Media3ContentType.resolve(Uri.parse(line.playableUrl), line.manifestType))
            }
        }
    }

    @Test fun hlsAliasesIgnoreCaseWhitespaceAndQuotes() {
        for (hint in listOf("hls", "m3u8", " HlS ", "\" M3U8 \"")) {
            assertEquals(C.CONTENT_TYPE_HLS,
                Media3ContentType.resolve(Uri.parse("https://example.test/entry"), hint))
        }
    }

    @Test fun explicitHintWinsOverSuffixAndFallback() {
        assertEquals(C.CONTENT_TYPE_HLS, Media3ContentType.resolve(
            Uri.parse("https://example.test/entry.mpd"), "hls", C.CONTENT_TYPE_OTHER))
        assertEquals(C.CONTENT_TYPE_DASH, Media3ContentType.resolve(
            Uri.parse("https://example.test/entry.m3u8"), "mpd", C.CONTENT_TYPE_HLS))
        for (hint in listOf("mmt", " CUSTOM ")) {
            assertEquals(C.CONTENT_TYPE_OTHER, Media3ContentType.resolve(
                Uri.parse("https://example.test/entry.m3u8"), hint))
        }
    }

    @Test fun missingAndUnknownHintsKeepUrlDetectionAndFallback() {
        for (hint in listOf(null, "", " future-format ")) {
            assertNull(Media3ContentType.explicit(hint))
            assertEquals(C.CONTENT_TYPE_HLS,
                Media3ContentType.resolve(Uri.parse("https://example.test/live.m3u8?token=a"), hint))
            assertEquals(C.CONTENT_TYPE_DASH,
                Media3ContentType.resolve(Uri.parse("https://example.test/live.mpd"), hint))
            assertEquals(C.CONTENT_TYPE_OTHER,
                Media3ContentType.resolve(Uri.parse("https://example.test/live.ts"), hint))
            assertEquals(C.CONTENT_TYPE_OTHER,
                Media3ContentType.resolve(Uri.parse("https://example.test/entry"), hint))
            assertEquals(C.CONTENT_TYPE_HLS,
                Media3ContentType.resolve(Uri.parse("https://example.test/entry"), hint, C.CONTENT_TYPE_HLS))
        }
    }

    @Test fun rtspAndRtpRetainTheirTransportSelection() {
        for (url in listOf("rtsp://example.test/stream", "rtp://239.0.0.1:1234")) {
            assertEquals(C.CONTENT_TYPE_RTSP,
                Media3ContentType.resolve(Uri.parse(url), null, C.CONTENT_TYPE_HLS))
        }
    }
}
