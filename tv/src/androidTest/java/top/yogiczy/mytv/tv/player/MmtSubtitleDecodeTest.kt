package top.yogiczy.mytv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.ttml.TtmlParser
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify that the existing Media3 renderer understands preserved TTML times and line breaks. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MmtSubtitleDecodeTest {
    private fun fixtureRoot(context: android.content.Context): java.io.File =
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mmtFixtureStorage") == "cache")
            context.cacheDir else requireNotNull(context.getExternalFilesDir(null))

    private data class CueState(
        val startUs: Long, val durationUs: Long, val text: String,
        val position: Float, val line: Float, val size: Float,
    )

    private fun parseStates(xml: String): List<CueState> {
        val data = xml.toByteArray(Charsets.UTF_8)
        val states = mutableListOf<CueState>()
        TtmlParser().parse(data, 0, data.size, SubtitleParser.OutputOptions.allCues()) { sample ->
            sample.cues.forEach { cue ->
                states += CueState(sample.startTimeUs, sample.durationUs, cue.text.toString(),
                    cue.position, cue.line, cue.size)
            }
        }
        return states
    }

    // Reconstruct only the old workaround for controlled test documents, without using prepareTtml.
    private fun legacyZeroBegins(xml: String): String =
        explicitZeroBegins(xml, "body|div|p|span")

    private fun explicitZeroBegins(xml: String, tags: String): String =
        Regex("<($tags)(\\s[^<>]*?)?>").replace(xml) { tag ->
            if (Regex("""\bbegin\s*=""").containsMatchIn(tag.value)) tag.value
            else tag.value.dropLast(1) + " begin=\"0s\">"
        }

    private fun document(body: String): String = """<tt xmlns="http://www.w3.org/ns/ttml"
        xmlns:tts="http://www.w3.org/ns/ttml#styling" tts:extent="1920px 1080px">
        <head><layout><region xml:id="bottom" tts:origin="10% 75%" tts:extent="80% 20%"/></layout></head>
        <body>$body</body></tt>"""

    private fun assertEquivalent(xml: String, startUs: Long, endUs: Long, text: String) {
        val raw = parseStates(xml)
        assertEquals("Official parser must match the old timing workaround", parseStates(legacyZeroBegins(xml)), raw)
        assertEquals(1, raw.size)
        assertEquals(startUs, raw.single().startUs)
        assertEquals(endUs - startUs, raw.single().durationUs)
        assertEquals(text, raw.single().text)
        assertEquals(0.1f, raw.single().position, 0.0001f)
        assertTrue(raw.single().line in 0f..1f)
    }

    @Test fun extractionTimeParserInheritsParentDivTimingWithoutPreprocessing() {
        assertEquivalent(document("""<div begin="2s" end="4s"><p region="bottom"><span>父级计时</span></p></div>"""),
            2_000_000, 4_000_000, "父级计时")
    }

    @Test fun extractionTimeParserInheritsThroughNestedContainers() {
        assertEquivalent(document("""<div begin="1s" end="10s"><div begin="2s" end="5s"><div>
            <p region="bottom"><span>嵌套继承</span></p></div></div></div>"""),
            3_000_000, 6_000_000, "嵌套继承")
    }

    @Test fun extractionTimeParserPreservesExplicitChildTimes() {
        assertEquivalent(document("""<div begin="2s" end="10s"><p region="bottom" begin="1s" end="3s">
            <span>显式计时</span></p></div>"""), 3_000_000, 5_000_000, "显式计时")
    }

    @Test fun endOnlyDocumentStillNeedsABodyTimeOrigin() {
        val xml = document("""<div><p region="bottom" end="2s">仅结束时间</p></div>""")
        assertTrue("Revisit body-origin fallback when Media3 handles this natively", parseStates(xml).isEmpty())
        assertEquivalent(explicitZeroBegins(xml, "body"), 0, 2_000_000, "仅结束时间")
    }

    @Test fun durationOnlyDocumentStillNeedsABodyTimeOrigin() {
        val xml = document("""<div><p region="bottom" dur="2s">仅持续时间</p></div>""")
        assertTrue("Revisit body-origin fallback when Media3 handles this natively", parseStates(xml).isEmpty())
        assertEquivalent(explicitZeroBegins(xml, "body"), 0, 2_000_000, "仅持续时间")
    }

    @Test fun capturedTtmlMatchesLegacyWorkaround() {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val directory = arguments.getString("mmtXmlFixtures")
        org.junit.Assume.assumeTrue(directory != null)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val files = java.io.File(fixtureRoot(context), directory!!).listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("No captured TTML documents supplied", files.isNotEmpty())
        files.forEach { file ->
            val raw = parseStates(file.readText())
            assertTrue("Captured TTML did not produce cues: ${file.name}", raw.isNotEmpty())
            assertEquals("Timing/text/geometry changed: ${file.name}", parseStates(legacyZeroBegins(file.readText())), raw)
        }
        println("Compared ${files.size} captured TTML documents")
    }

    @Test fun capturedStreamProducesVisibleCuesOnPlaybackTimeline() {
        val name = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mmtFixture")
        org.junit.Assume.assumeTrue(name != null)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = java.io.File(fixtureRoot(context), name!!).readBytes()
        var cursor = 0
        val input = androidx.media3.extractor.DefaultExtractorInput(androidx.media3.common.DataReader { dst, offset, length ->
            if (cursor == bytes.size) -1 else minOf(length, bytes.size - cursor).also {
                bytes.copyInto(dst, offset, cursor, cursor + it); cursor += it
            }
        }, 0, bytes.size.toLong())
        val cues = mutableListOf<CuesWithTiming>()
        val snapshot = org.json.JSONArray()
        val extractor = top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.mmt.MmtExtractor()
        extractor.init(object : androidx.media3.extractor.ExtractorOutput {
            override fun endTracks() {}
            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {}
            override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput = object : androidx.media3.extractor.TrackOutput {
                val data = java.io.ByteArrayOutputStream()
                override fun format(format: androidx.media3.common.Format) {}
                override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = input.read(ByteArray(length),0,length)
                override fun sampleData(b: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {
                    if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) data.write(b.data,b.position,length)
                    b.skipBytes(length)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?) {
                    if (type != androidx.media3.common.C.TRACK_TYPE_TEXT) return
                    val bytes = data.toByteArray()
                    val sample = androidx.media3.extractor.text.CueDecoder().decode(timeUs, bytes, 0, bytes.size)
                    cues += sample
                    snapshot.put(org.json.JSONObject().put("track", id).put("startUs", sample.startTimeUs)
                        .put("durationUs", sample.durationUs).put("cues", org.json.JSONArray().apply {
                            sample.cues.forEach { cue -> put(org.json.JSONObject().put("text", cue.text.toString())
                                .put("position", cue.position.toDouble()).put("line", cue.line.toDouble()).put("size", cue.size.toDouble())) }
                        }))
                    data.reset()
                }
            }
        })
        try {
            val position = androidx.media3.extractor.PositionHolder()
            while (extractor.read(input,position) != androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT) {}
            val nonempty = cues.filter { it.cues.isNotEmpty() }
            println("MMT subtitle samples=${cues.size}, nonempty=${nonempty.size}")
            assertTrue("No subtitle text survived actual extractor/transcoder",nonempty.isNotEmpty())
            assertTrue("Subtitle time outside captured minute",nonempty.all { it.startTimeUs in -5_000_000L..90_000_000L })
            assertTrue("Subtitle region is outside the viewport", nonempty.flatMap { it.cues }.all {
                it.position in 0f..1f && it.line in 0f..1f && it.size > 0f && it.size <= 1f
            })
            val baseline = java.io.File(fixtureRoot(context), "ttml-before-removal.json")
            when (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mmtSnapshot")) {
                "record" -> baseline.writeText(snapshot.toString())
                "compare" -> {
                    assertTrue("Record the pre-removal baseline first", baseline.isFile)
                    assertEquals("MMT subtitle tracks/text/timing/geometry changed", baseline.readText(), snapshot.toString())
                }
            }
        } finally { extractor.release() }
    }

    @Test fun decodesJapaneseTextAndKeepsCueWindow() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="2s" end="4s">日本語字幕<br/>二行目</p>
            </div></body></tt>""".toByteArray()
        val subtitle = TtmlParser().parseToLegacySubtitle(xml, 0, xml.size)
        assertTrue(subtitle.getCues(1_999_999).isEmpty())
        assertEquals("日本語字幕\n二行目", subtitle.getCues(2_000_000).single().text.toString())
        assertTrue(subtitle.getCues(4_000_000).isEmpty())
    }
}
