package top.yogiczy.mytv.core.data.media

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.decodeBase64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.*

/** Converts the inline Kodi KID:key form to a ClearKey JWK response. */
object ClearKeyLicense {
    fun keyIds(response: ByteArray): List<java.util.UUID> =
        Json.parseToJsonElement(response.toString(Charsets.UTF_8)).jsonObject.getValue("keys").jsonArray.map {
            val bytes = it.jsonObject.getValue("kid").jsonPrimitive.content.decodeBase64()
            require(bytes?.size == 16) { "Invalid ClearKey KID" }
            val buffer = java.nio.ByteBuffer.wrap(bytes!!.toByteArray())
            java.util.UUID(buffer.long, buffer.long)
        }.distinct()

    fun remoteUrl(value: String?): String? =
        value?.trim()?.removeSurrounding("\"")?.toHttpUrlOrNull()?.toString()

    /** Validate the entire JWK set, keeping separate audio/video keys. */
    fun remoteResponse(body: String): ByteArray {
        val root = Json.parseToJsonElement(body).jsonObject
        if ("keys" !in root) {
            // Some playlist proxies return a hexadecimal KID -> KEY map for POST requests.
            require(root.isNotEmpty()) { "ClearKey response contains no keys" }
            val keys = root.map { (kid, key) ->
                inlineKey("$kid:${key.jsonPrimitive.content}")
            }
            return buildJsonObject {
                put("keys", JsonArray(keys))
                put("type", "temporary")
            }.toString().toByteArray(Charsets.UTF_8)
        }
        val keys = root.getValue("keys").jsonArray
        require(keys.isNotEmpty()) { "ClearKey response contains no keys" }
        val normalized = keys.map { element ->
            val key = element.jsonObject
            require(key.getValue("kty").jsonPrimitive.content == "oct") { "Unsupported ClearKey key type" }
            fun decode(name: String): String {
                val bytes = key.getValue(name).jsonPrimitive.content.decodeBase64()
                require(bytes?.size == 16) { "Invalid ClearKey key size" }
                return bytes!!.base64Url().trimEnd('=')
            }
            buildJsonObject {
                put("kty", "oct")
                put("kid", decode("kid"))
                put("k", decode("k"))
            }
        }
        return buildJsonObject {
            put("keys", JsonArray(normalized))
            put("type", "temporary")
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun isSupported(type: String?): Boolean =
        type?.trim()?.removeSurrounding("\"")?.lowercase() in setOf("clearkey", "org.w3.clearkey")

    fun response(value: String?): ByteArray {
        val text = value?.trim().orEmpty()
        if (text.startsWith("{")) {
            // Accept standard JWK / HEX maps as well as legacy {"KID:KEY","KID:KEY"} lists.
            val objectValue = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (objectValue != null) return remoteResponse(text)
        }
        val keys = text.removeSurrounding("{", "}").split(',').map { inlineKey(it) }
        val unique = linkedMapOf<String, JsonObject>()
        keys.forEach { key ->
            val kid = key.getValue("kid").jsonPrimitive.content
            val previous = unique.put(kid, key)
            require(previous == null || previous == key) { "Conflicting ClearKey entries for one KID" }
        }
        return buildJsonObject {
            put("keys", JsonArray(unique.values.toList()))
            put("type", "temporary")
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun inlineKey(value: String): JsonObject {
        val parts = value.trim().removeSurrounding("\"").split(':')
        require(parts.size == 2) { "ClearKey requires an inline KID:key pair" }
        val kid = parts[0].trim().replace("-", "")
        val key = parts[1].trim()
        val hex128 = Regex("[0-9a-fA-F]{32}")
        require(hex128.matches(kid) && hex128.matches(key)) { "ClearKey KID and key must each contain 16 bytes" }
        val encodedKid = kid.decodeHex().base64Url().trimEnd('=')
        val encodedKey = key.decodeHex().base64Url().trimEnd('=')
        return buildJsonObject {
            put("kty", "oct")
            put("k", encodedKey)
            put("kid", encodedKid)
        }
    }
}
