package top.yogiczy.mytv.tv.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavorite
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceList
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.Screens
import top.yogiczy.mytv.tv.ui.screen.main.MainScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(ExperimentalTestApi::class)
class DashboardFavoriteOrderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allFavoriteViewsUseSubscriptionGroupAndChannelOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val playableSample = File.createTempFile("favorite-order-", ".ts", Globals.fileDir).apply {
            instrumentation.context.assets.open("aac-av3a.ts").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        val first = playlist("Z 订阅", "乙组" to "乙台", "乙组" to "甲台", "甲组" to "丙台")
        val second = playlist("A 订阅", "新闻组" to "丁台", playableUrl = Uri.fromFile(playableSample).toString())
        val sources = listOf(first, second)
        val existing = ChannelFavoriteList(listOf(
            ChannelFavorite(Channel("丁台"), second.name, "新闻组"),
            ChannelFavorite(Channel("丙台"), first.name, "甲组"),
            ChannelFavorite(Channel("甲台"), first.name, "乙组"),
            ChannelFavorite(Channel("乙台"), first.name, "乙组"),
        ))
        try {
            SP.init(instrumentation.context)
            Configs.appAgreementAgreed = true
            Configs.appStartupScreen = Screens.Dashboard.name
            Configs.cloudSyncAutoPull = false
            Configs.epgEnable = false
            Configs.iptvSimilarChannelMerge = false
            Configs.iptvSourceList = IptvSourceList(sources)
            Configs.iptvSourceCurrent = second
            Configs.iptvChannelFavoriteList = existing
            Configs.iptvChannelFavoriteEnable = true
            Configs.iptvChannelFavoriteListVisible = false
            Configs.uiUseClassicPanelScreen = true
            Configs.controlKeyActions = emptyMap()

            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MyTvTheme { MainScreen() }
                }
            }
            compose.waitUntil(10_000) {
                Configs.iptvChannelFavoriteList.all { it.playlistPosition != null }
            }
            val saved = Configs.iptvChannelFavoriteList
            assertEquals(listOf(0, 2, 1, 0), saved.map { it.playlistPosition })
            assertEquals(existing.map { it.channel.name }, saved.map { it.channel.name })

            val names = listOf("乙台", "甲台", "丙台", "丁台")
            val positions = names.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
            assertEquals(positions.sorted().distinct(), positions)

            compose.onNode(hasText("我的收藏") and hasClickAction()).performTouchInput { click() }
            assertGridOrder(names)
            val sourcePositions = sources.map { compose.onNodeWithText(it.name).fetchSemanticsNode().boundsInRoot.left }
            assertEquals(sourcePositions.sorted().distinct(), sourcePositions)

            compose.onNodeWithText(first.name).performTouchInput { click() }
            assertGridOrder(names.dropLast(1))
            compose.onNodeWithText("丁台").assertDoesNotExist()

            compose.onNodeWithText(second.name).performTouchInput { click() }
            compose.onNodeWithText("丁台").performTouchInput { click() }
            compose.waitUntil(5_000) { Configs.iptvChannelLastPlay.name == "丁台" }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            compose.onNodeWithText("频道分组").assertIsDisplayed()
            compose.onNodeWithText("我的收藏").performTouchInput { click() }
            val rowPositions = names.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
            assertEquals(rowPositions.sorted().distinct(), rowPositions)
        } finally {
            compose.runOnIdle {
                compose.activity.viewModelStore.clear()
                SP.init(instrumentation.targetContext)
            }
            runBlocking { sources.forEach { IptvRepository(it).clearCache() } }
            sources.forEach { File(it.url).delete() }
            playableSample.delete()
        }
    }

    @Test fun previousFavoritesWithoutAPlaylistPositionRemainVisible() {
        val saved = Globals.json.decodeFromString<ChannelFavoriteList>("""
            {"value":[{"channel":{"name":"旧收藏"},"iptvSourceName":"旧订阅","groupName":"原分组"}]}
        """.trimIndent())
        assertNull(saved.single().playlistPosition)
        assertEquals(saved, saved.inSubscriptionOrder(emptyList()))
    }

    private fun assertGridOrder(names: List<String>) {
        val bounds = names.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEachIndexed { index, (current, next) ->
            val sameRow = current.top < next.bottom && next.top < current.bottom
            val inOrder = if (sameRow) current.left < next.left else current.top < next.top
            assertTrue("${names[index]} before ${names[index + 1]}", inOrder)
        }
    }

    private fun playlist(name: String, vararg channels: Pair<String, String>, playableUrl: String? = null): IptvSource {
        val file = File.createTempFile("favorite-order-", ".m3u", Globals.fileDir).apply {
            writeText(buildString {
                appendLine("#EXTM3U")
                channels.forEachIndexed { index, (group, channel) ->
                    appendLine("#EXTINF:-1 group-title=\"$group\",$channel")
                    appendLine(playableUrl ?: "https://example.test/$index.m3u8")
                }
            })
        }
        return IptvSource(name, file.absolutePath, isLocal = true)
    }
}
