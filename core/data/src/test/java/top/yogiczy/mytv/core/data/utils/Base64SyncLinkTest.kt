package top.yogiczy.mytv.core.data.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class Base64SyncLinkTest {
    private val json = """{"description":"中文同步 🚀","configs":{"videoPlayerUserAgent":"测试 + / = %"}}"""
    private fun link(text: String) = "base64://" + text.encodeUtf8().base64()

    @Test
    fun decodesUtf8SyncJson() {
        val decoded = Base64SyncLink.decode(link(json))!!
        assertEquals(json, decoded)
        assertEquals("中文同步 🚀", Json.parseToJsonElement(decoded).jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun preservesStandardBase64PlusSlashAndPadding() {
        val text = """{"a":"࠾࠿"}"""
        val encoded = link(text)
        assertTrue(encoded.substringAfter("://").contains('+'))
        assertTrue(encoded.substringAfter("://").contains('/'))
        assertEquals(text, Base64SyncLink.decode(encoded))
        assertEquals("{}", Base64SyncLink.decode("base64://e30="))
    }

    @Test
    fun acceptsUrlSafeBase64() {
        val text = """{"a":"࠾࠿"}"""
        assertEquals(text, Base64SyncLink.decode("base64://" + text.encodeUtf8().base64Url()))
    }

    @Test
    fun acceptsMissingPadding() {
        assertEquals("{}", Base64SyncLink.decode("base64://e30"))
    }

    @Test
    fun acceptsWrappedPayloadAndSurroundingWhitespace() {
        val wrapped = json.encodeUtf8().base64().chunked(20).joinToString("\r\n\t ")
        assertEquals(json, Base64SyncLink.decode(" \nbase64://$wrapped\r\n "))
    }

    @Test
    fun decodesOnlyOnce() {
        val encodedJson = json.encodeUtf8().base64()
        assertEquals(encodedJson, Base64SyncLink.decode(link(encodedJson)))
    }

    @Test
    fun leavesNetworkUrlsToTheNetworkRepository() {
        for (value in listOf("https://example.test/sync?token=abc+/=", "http://example.test/base64://e30=", "", "file:///sync.json")) {
            assertNull(Base64SyncLink.decode(value))
        }
    }

    @Test
    fun rejectsInvalidBase64WithoutIncludingPayloadInError() {
        for (payload in listOf("not!base64", "a", "%2Be30%3D")) {
            val exception = assertThrows(IllegalArgumentException::class.java) { Base64SyncLink.decode("base64://$payload") }
            assertEquals("同步链接的 Base64 编码无效", exception.message)
        }
    }

    @Test
    fun rejectsEmptyDataAndInvalidUtf8() {
        for (payload in listOf("", " \r\n\t", "/w==")) {
            assertThrows(IllegalArgumentException::class.java) { Base64SyncLink.decode("base64://$payload") }
        }
    }
}
