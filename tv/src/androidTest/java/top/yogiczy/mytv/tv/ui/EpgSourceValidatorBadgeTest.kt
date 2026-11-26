package top.yogiczy.mytv.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceList
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.tv.ui.screen.settings.subcategories.SettingsEpgSourceScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme

class EpgSourceValidatorBadgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val testSources = mutableListOf<EpgSource>()
    private val modificationDate = "Wed, 25 Nov 2026 04:00:00 GMT"

    @After fun removeGuideFixtures() = runBlocking {
        testSources.forEach { EpgRepository(it).clearCache() }
    }

    @Test fun etagTakesPriorityAndAppearsNextToSourceName() {
        val source = seedGuide(
            "新闻节目单",
            etag = "\"fixture-tag\"",
            lastModified = modificationDate,
        )
        showSources(source)
        val name = compose.onNodeWithText(source.name, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithText("ETag", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(tag.left >= name.right && tag.center.y in name.top..name.bottom)
        compose.onNodeWithText("Last-Modified", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun lastModifiedAppearsWhenItIsTheOnlyHttpValidator() {
        val source = seedGuide("地区节目单", lastModified = modificationDate)
        showSources(source)
        compose.onNodeWithText("Last-Modified", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("ETag", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sourcesWithoutSavedHttpValidatorsHaveNoBadge() {
        val legacy = seedGuide("旧版节目单")
        val gzip = seedGuide("压缩节目单", gzipMtime = 1000)
        showSources(legacy, gzip)
        compose.onNodeWithText("ETag", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Last-Modified", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun showSources(vararg sources: EpgSource) {
        val list = EpgSourceList(sources.toList())
        compose.setContent {
            MyTvTheme { SettingsEpgSourceScreen(epgSourceListProvider = { list }) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("频道：1", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size == sources.size
        }
    }

    private fun seedGuide(
        name: String, etag: String? = null, lastModified: String? = null, gzipMtime: Long? = null,
    ): EpgSource {
        val source = EpgSource(name, "https://example.test/${UUID.randomUUID()}/guide.xml.gz")
        testSources.add(source)
        val parsed = File(Globals.cacheDir, source.cacheFileName("v3.json"))
        parsed.parentFile?.mkdirs()
        parsed.writeText("""
            {"value":[{"channelId":"test","channelList":["test"],"programmeList":{"value":[
                {"startAt":10,"endAt":20,"title":"Guide programme"}
            ]}}]}
        """.trimIndent())
        if (etag != null || lastModified != null || gzipMtime != null) {
            val validators = buildJsonObject {
                put("resource", "fixture")
                etag?.let { put("etag", it) }
                lastModified?.let { put("lastModified", it) }
                gzipMtime?.let { put("gzipMtime", it) }
            }
            val modified = parsed.lastModified()
            File(parsed.path + ".validation").writeText(
                """{"stamp":{"modified":$modified,"bytes":${parsed.length()}},"checkedAt":$modified,"validators":$validators}""",
            )
        }
        return source
    }
}
