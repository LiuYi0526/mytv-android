package top.yogiczy.mytv.core.data.utils

import okio.ByteString.Companion.decodeBase64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** YYKM embeds the sync JSON itself in base64://, rather than an encoded download URL. */
object Base64SyncLink {
    private const val PREFIX = "base64://"

    fun decode(value: String): String? {
        val link = value.trim()
        if (!link.startsWith(PREFIX, ignoreCase = true)) return null

        // Do not URL-decode: '+' and '/' are part of the standard Base64 alphabet.
        val payload = link.substring(PREFIX.length)
        val bytes = payload.decodeBase64()
            ?: throw IllegalArgumentException("同步链接的 Base64 编码无效")
        require(bytes.size > 0) { "同步链接的数据为空" }

        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("同步链接的数据不是有效的 UTF-8 文本")
        }
    }
}
