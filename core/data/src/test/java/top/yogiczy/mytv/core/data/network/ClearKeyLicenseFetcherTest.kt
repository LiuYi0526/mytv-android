package top.yogiczy.mytv.core.data.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.media.ClearKeyLicense
import java.io.IOException

class ClearKeyLicenseFetcherTest {
    private val challenge = """{"kids":["ABEiM0RVZneImaq7zN3u_w"],"type":"temporary"}""".toByteArray()
    private val jwk = """{"keys":[{"kty":"oct","kid":"ABEiM0RVZneImaq7zN3u_w","k":"_-7dzLuqmYh3ZlVEMyIRAA"},{"kty":"oct","kid":"_-7dzLuqmYh3ZlVEMyIRAA","k":"ABEiM0RVZneImaq7zN3u_w"}],"type":"temporary"}"""

    @Test fun httpUrlsAreDistinguishedFromInlineKeys() {
        assertEquals("http://example.test/license?type=base64&token=a=b==",
            ClearKeyLicense.remoteUrl(" \"http://example.test/license?type=base64&token=a=b==\" "))
        assertNotNull(ClearKeyLicense.remoteUrl("https://example.test/license"))
        listOf(null, "", "file:///license", "001122:334455").forEach {
            assertNull(ClearKeyLicense.remoteUrl(it))
        }
    }

    @Test fun postsChallengeAndPreservesAllKeysQueryAndHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(jwk))
            val result = ClearKeyLicenseFetcher(OkHttpClient(), mapOf("User-Agent" to "TestPlayer/1"))
                .load(server.url("/5002?type=base64&token=a=b==").toString(), challenge)
            assertEquals(2, Json.parseToJsonElement(String(result)).jsonObject.getValue("keys").jsonArray.size)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/5002?type=base64&token=a=b==", request.path)
            assertEquals("TestPlayer/1", request.getHeader("User-Agent"))
            assertEquals(String(challenge), request.body.readUtf8())
            assertEquals("application/json", request.getHeader("Content-Type"))
        }
    }

    @Test fun followsRedirectsAndDoesNotReuseExpiredLicenseResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody(jwk))
            server.enqueue(MockResponse().setResponseCode(403))
            val fetcher = ClearKeyLicenseFetcher(OkHttpClient())
            assertTrue(fetcher.load(server.url("/license").toString(), challenge).isNotEmpty())
            assertTrue(runCatching { fetcher.load(server.url("/license").toString(), challenge) }.exceptionOrNull() is IOException)
            assertEquals(3, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals(String(challenge), request.body.readUtf8())
            }
        }
    }

    @Test fun rejectsErrorsMalformedAndOversizedResponsesWithoutLeakingContents() {
        MockWebServer().use { server ->
            val fetcher = ClearKeyLicenseFetcher(OkHttpClient())
            listOf("private-secret", """{"keys":[]}""", """{"keys":[{"kty":"oct","kid":"x","k":"private-secret"}]}""", "x".repeat(65537)).forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                val error = runCatching { fetcher.load(server.url("/license?token=secret").toString(), challenge) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertFalse(error!!.message.orEmpty().contains("private-secret"))
                assertFalse(error.message.orEmpty().contains("token="))
            }
        }
    }

    @Test fun standardBase64IsNormalizedForAllKeys() {
        val result = ClearKeyLicense.remoteResponse(jwk.replace('_', '/').replace('-', '+'))
        val keys = Json.parseToJsonElement(String(result)).jsonObject.getValue("keys").jsonArray
        assertEquals(2, keys.size)
        assertEquals("_-7dzLuqmYh3ZlVEMyIRAA", keys[0].jsonObject.getValue("k").jsonPrimitive.content)
    }

    @Test fun hexadecimalMapFromPostEndpointIsConvertedWithoutDroppingKeys() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "00112233445566778899aabbccddeeff":"ffeeddccbbaa99887766554433221100",
                "ffeeddccbbaa99887766554433221100":"00112233445566778899aabbccddeeff"
            }"""))
            val result = ClearKeyLicenseFetcher(OkHttpClient()).load(server.url("/license").toString(), challenge)
            assertEquals(Json.parseToJsonElement(jwk), Json.parseToJsonElement(String(result)))
        }
    }
}
