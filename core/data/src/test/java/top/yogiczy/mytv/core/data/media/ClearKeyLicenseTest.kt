package top.yogiczy.mytv.core.data.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ClearKeyLicenseTest {
    private val pair = "94472526cc92471493d46543faefadda:36658f2faa1f09f206a2f448ac653e2d"

    @Test fun kodiAliasesAreAcceptedButOtherDrmTypesAreNot() {
        listOf("clearkey", "org.w3.clearkey", " ORG.W3.CLEARKEY ", "\"clearkey\"").forEach {
            assertTrue(ClearKeyLicense.isSupported(it))
        }
        listOf(null, "", "com.widevine.alpha", "not-clearkey").forEach {
            assertFalse(ClearKeyLicense.isSupported(it))
        }
    }

    @Test fun suppliedPairProducesUnpaddedBase64UrlJwk() {
        val response = Json.parseToJsonElement(String(ClearKeyLicense.response(pair))).jsonObject
        val key = response.getValue("keys").jsonArray.single().jsonObject
        assertEquals("lEclJsySRxST1GVD-u-t2g", key.getValue("kid").jsonPrimitive.content)
        assertEquals("NmWPL6ofCfIGovRIrGU-LQ", key.getValue("k").jsonPrimitive.content)
        assertEquals("oct", key.getValue("kty").jsonPrimitive.content)
        assertEquals("temporary", response.getValue("type").jsonPrimitive.content)
    }

    @Test fun uuidKidAndQuotedWhitespaceAreAccepted() {
        assertArrayEquals(ClearKeyLicense.response(pair), ClearKeyLicense.response(
            " \"94472526-cc92-4714-93d4-6543faefadda : 36658F2FAA1F09F206A2F448AC653E2D\" ",
        ))
    }

    @Test fun missingMalformedAndWrongSizedKeysFailWithoutExposingKey() {
        listOf(null, "", "1234:5678", "$pair:extra", pair.replace('9', 'z'), pair.dropLast(1)).forEach {
            val error = runCatching { ClearKeyLicense.response(it) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains(pair))
        }
    }

    @Test fun braceWrappedMultiplePairsPreserveBothKeysAndKids() {
        val first = "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
        val second = "ffeeddccbbaa99887766554433221100:00112233445566778899aabbccddeeff"
        val body = ClearKeyLicense.response("{\"$first\", \"$second\"}")
        val keys = Json.parseToJsonElement(String(body)).jsonObject.getValue("keys").jsonArray
        assertEquals(2, keys.size)
        assertEquals("ABEiM0RVZneImaq7zN3u_w", keys[0].jsonObject.getValue("kid").jsonPrimitive.content)
        assertEquals("_-7dzLuqmYh3ZlVEMyIRAA", keys[0].jsonObject.getValue("k").jsonPrimitive.content)
        assertEquals("_-7dzLuqmYh3ZlVEMyIRAA", keys[1].jsonObject.getValue("kid").jsonPrimitive.content)
        assertEquals(2, ClearKeyLicense.keyIds(body).size)
        assertArrayEquals(body, ClearKeyLicense.response("$first,$second"))
    }

    @Test fun standardHexMapAndJwkAreAcceptedInline() {
        val map = """{"00112233445566778899aabbccddeeff":"ffeeddccbbaa99887766554433221100"}"""
        val expected = Json.parseToJsonElement(String(ClearKeyLicense.response(
            "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100")))
        assertEquals(expected, Json.parseToJsonElement(String(ClearKeyLicense.response(map))))
        assertEquals(expected, Json.parseToJsonElement(String(ClearKeyLicense.response(expected.toString()))))
    }

    @Test fun invalidSecondKeyAndConflictingDuplicatesAreRejected() {
        listOf("{\"$pair\",\"bad:bad\"}", "{\"$pair\",}", "{}", "{\"$pair\"", "$pair,${pair.substringBefore(':')}:00000000000000000000000000000000").forEach {
            assertTrue(runCatching { ClearKeyLicense.response(it) }.isFailure)
        }
        val body = ClearKeyLicense.response("$pair,$pair")
        assertEquals(1, Json.parseToJsonElement(String(body)).jsonObject.getValue("keys").jsonArray.size)
    }
}
