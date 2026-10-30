package top.yogiczy.mytv.core.data.repositories.epg

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.DefaultEpgFetcher

class EpgUpdateProgressTest {
    @Test fun knownLengthReportsActualBytesAndTotal() {
        val bytes = ByteArray(30_000) { 42 }
        val events = mutableListOf<EpgUpdateProgress>()
        EpgProgressResponseBody(bytes.toResponseBody(), events::add).use {
            assertArrayEquals(bytes, it.bytes())
        }
        assertEquals(0L, events.first().bytesRead)
        assertEquals(bytes.size.toLong(), events.last().bytesRead)
        assertEquals(bytes.size.toLong(), events.last().totalBytes)
    }

    @Test fun unknownLengthNeverInventsTotalSize() {
        val events = mutableListOf<EpgUpdateProgress>()
        val delegate = object : ResponseBody() {
            val data = Buffer().writeUtf8("guide")
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = data
        }
        EpgProgressResponseBody(delegate, events::add).use { assertEquals("guide", it.string()) }
        assertEquals(5L, events.last().bytesRead)
        assertTrue(events.all { it.totalBytes == null })
    }

    @Test fun gzipProgressCountsDownloadedPayloadNotDecodedXmlOrSniffingReads() = runBlocking {
        val xml = "<tv>" + "guide".repeat(5000) + "</tv>"
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        val events = mutableListOf<EpgUpdateProgress>()
        val body = EpgProgressResponseBody(compressed.toResponseBody(), events::add)
        DefaultEpgFetcher().fetch(body).use { assertEquals(xml, it.readBytes().toString(Charsets.UTF_8)) }
        assertEquals(compressed.size.toLong(), events.last().bytesRead)
        assertTrue(events.all { it.bytesRead <= compressed.size })
    }

    @Test fun failedReadDoesNotReportFullDownload() {
        val events = mutableListOf<EpgUpdateProgress>()
        val delegate = object : ResponseBody() {
            val data = object : ForwardingSource(Buffer().writeUtf8("guide")) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("disconnected")
            }.buffer()
            override fun contentType() = null
            override fun contentLength() = 100L
            override fun source(): BufferedSource = data
        }
        try {
            EpgProgressResponseBody(delegate, events::add).use { it.bytes() }
            fail("Expected read failure")
        } catch (_: IOException) {
            assertTrue(events.all { it.bytesRead < 100L })
        }
    }
}
