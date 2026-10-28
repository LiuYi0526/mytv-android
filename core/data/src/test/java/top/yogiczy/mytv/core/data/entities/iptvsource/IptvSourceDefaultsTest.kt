package top.yogiczy.mytv.core.data.entities.iptvsource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.core.data.utils.Constants

class IptvSourceDefaultsTest {
    @Test fun freshInstallHasNoSubscriptionOrDownloadUrl() {
        assertTrue(Constants.IPTV_SOURCE_LIST.isEmpty())
        assertEquals(IptvSource(), IptvSourceDefaults.current())
    }

    @Test fun persistedLegacyPlaceholderBecomesEmpty() {
        val legacy = IptvSource(name = "无效占位订阅源", url = "http://1.2.3.4/live.m3u")
        assertEquals(IptvSource(), IptvSourceDefaults.current(legacy))
    }

    @Test fun realAndCustomizedSubscriptionsArePreserved() {
        val sources = listOf(
            IptvSource(name = "我的订阅", url = "https://example.com/live.m3u"),
            IptvSource(name = "我的订阅", url = "http://1.2.3.4/live.m3u"),
            IptvSource(name = "无效占位订阅源", url = "https://example.com/live.m3u"),
            IptvSource(name = "无效占位订阅源", url = "http://1.2.3.4/live.m3u", transformJs = "custom"),
        )
        sources.forEach { assertSame(it, IptvSourceDefaults.current(it)) }
    }
}
