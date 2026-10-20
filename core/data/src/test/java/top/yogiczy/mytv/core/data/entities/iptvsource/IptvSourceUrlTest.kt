package top.yogiczy.mytv.core.data.entities.iptvsource

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList

class IptvSourceUrlTest {
    @Test
    fun parsesYykmSubscriptionExample() {
        val parsed = IptvSourceUrl.parse("http://example.com/tv.m3u#sp;sha1=time-interval://6h;vp-ua=okHttp/Mod-2")
        assertEquals("http://example.com/tv.m3u", parsed.requestUrl)
        assertEquals("okHttp/Mod-2", parsed.playerUserAgent)
    }

    @Test
    fun preservesDownloadQueryAndCredentialsExactly() {
        val url = "https://user:p%40ss@example.com/list?token=a+b%2Fc%3D&x=1&x=2"
        assertEquals(url, IptvSourceUrl.parse("$url#sp;vp-ua=Player/1").requestUrl)
    }

    @Test
    fun supportsBrowserUserAgentsAndFollowingParameters() {
        val ua = "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36"
        assertEquals(ua, IptvSourceUrl.parse("https://example.com#sp;vp-ua=$ua;other=x").playerUserAgent)
    }

    @Test
    fun decodesPercentEncodingOnceAndPreservesPlusAndEquals() {
        assertEquals("Player/1.0 (+test; key=value) %2F", IptvSourceUrl.parse(
            "https://example.com#sp;vp-ua=Player%2F1.0%20(+test%3B%20key%3Dvalue)%20%252F"
        ).playerUserAgent)
    }

    @Test
    fun leavesOrdinaryLinksAndFragmentsUnchanged() {
        for (url in listOf("https://example.com?vp-ua=server-parameter", "https://example.com#chapter", "file:///storage/tv.m3u")) {
            assertEquals(IptvSourceUrl(url), IptvSourceUrl.parse(url))
        }
    }

    @Test
    fun ignoresBlankInvalidOrUnsafeUserAgent() {
        for (ua in listOf("", " ", "%ZZ", "Player%0D%0AX-Test:yes", "Player%00", "Player%09")) {
            val parsed = IptvSourceUrl.parse("https://example.com#sp;vp-ua=$ua")
            assertEquals("https://example.com", parsed.requestUrl)
            assertNull(parsed.playerUserAgent)
        }
    }

    @Test
    fun missingUaDoesNotAlterChannels() {
        val groups = groups()
        assertSame(groups, IptvSourceUrl.parse("https://example.com#sp;sha1=hash").applyTo(groups))
    }

    @Test
    fun subscriptionUaIsFallbackForAllLinesAndSurvivesCacheAndFavorites() {
        val source = IptvSourceUrl.parse("https://example.com#sp;vp-ua=Source/1")
        val cached = Json.decodeFromString<ChannelGroupList>(Json.encodeToString(groups()))
        val result = source.applyTo(cached)
        assertEquals(listOf("Source/1", "Line/1"), result.first().channelList.first().lineList.map { it.httpUserAgent })
        val favorite = Json.decodeFromString<Channel>(Json.encodeToString(result.first().channelList.first()))
        assertEquals("Source/1", favorite.lineList.first().httpUserAgent)
        assertNull(cached.first().channelList.first().lineList.first().httpUserAgent)
        assertEquals(result, source.applyTo(cached))
    }

    @Test
    fun switchingSourcesDoesNotReusePreviousUa() {
        val cached = groups()
        val first = IptvSourceUrl.parse("https://example.com#sp;vp-ua=First/1").applyTo(cached)
        val second = IptvSourceUrl.parse("https://example.com#sp;vp-ua=Second/1").applyTo(cached)
        assertEquals("First/1", first.first().channelList.first().lineList.first().httpUserAgent)
        assertEquals("Second/1", second.first().channelList.first().lineList.first().httpUserAgent)
    }

    private fun groups() = ChannelGroupList(listOf(ChannelGroup(
        name = "测试", channelList = ChannelList(listOf(Channel(name = "频道", lineList = ChannelLineList(listOf(
            ChannelLine(url = "https://example.com/live"),
            ChannelLine(url = "https://example.com/override", httpUserAgent = "Line/1"),
        ))))),
    )))
}
