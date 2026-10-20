package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.DefaultEpgFetcher
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgFetcherTest {
    private val xml = """<?xml version="1.0"?><tv><channel id="one"/></tv>"""
    private fun gzip() = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(xml.toByteArray()) }
    }.toByteArray()

    @Test
    fun acceptsXmlEndpointsWithQueryStringsAndNoExtension() = runBlocking {
        for (url in listOf("https://example.test/guide.XML?token=test", "https://example.test/api/epg?format=xml")) {
            val fetcher = EpgFetcher.instances.first { it.isSupport(url) }
            assertEquals(xml, fetcher.fetch(xml.toResponseBody()).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test
    fun detectsGzipEvenWithoutAGzipExtension() = runBlocking {
        for (url in listOf("https://example.test/guide.xml.gz?token=test", "https://example.test/api/epg", "https://example.test/guide.xml")) {
            val fetcher = EpgFetcher.instances.first { it.isSupport(url) }
            assertEquals(xml, fetcher.fetch(gzip().toResponseBody()).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test
    fun gzipExtensionDoesNotDoubleDecompressAnAlreadyDecodedHttpBody() = runBlocking {
        val fetcher = EpgFetcher.instances.first { it.isSupport("https://example.test/guide.gz") }
        assertEquals(xml, fetcher.fetch(xml.toResponseBody()).use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun shortBodiesArePassedThroughToXmlValidation() = runBlocking {
        assertTrue(DefaultEpgFetcher().fetch(byteArrayOf().toResponseBody()).use { it.readBytes().isEmpty() })
        assertArrayEquals(byteArrayOf(0x1f), DefaultEpgFetcher().fetch(byteArrayOf(0x1f).toResponseBody()).use { it.readBytes() })
    }
}
