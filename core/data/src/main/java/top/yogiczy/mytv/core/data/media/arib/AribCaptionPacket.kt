package top.yogiczy.mytv.core.data.media.arib

/** ARIB PES payload carried by an ID3 PRIV frame, as emitted by tsreadex/aribb24.js. */
data class AribCaptionPacket(val type: Int, val bytes: ByteArray, val languages: List<Language>?, val statementLanguageId: Int?) {
    data class Language(val id: Int, val code: String)

    companion object {
        const val OWNER = "aribb24.js"
        const val MIME_TYPE = "application/x-arib-caption"
        const val MAX_PACKET_SIZE = 1024 * 1024

        fun parse(owner: String, data: ByteArray): AribCaptionPacket? {
            if (owner != OWNER || data.size !in 10..MAX_PACKET_SIZE) return null
            fun u(index: Int) = data[index].toInt() and 255
            val type = u(0)
            if (type !in 0x80..0x81 || u(1) != 0xff || u(2) and 0xf0 != 0xf0) return null
            val group = 3 + (u(2) and 15)
            if (group + 7 > data.size) return null
            val size = (u(group + 3) shl 8) or u(group + 4)
            if (size < 1 || size > data.size - group - 7 || u(group + 1) > u(group + 2)) return null
            val groupId = (u(group) ushr 2) and 0x3f
            var languages: List<Language>? = null
            if (groupId and 0x1f == 0 && u(group + 1) == 0 && u(group + 2) == 0) {
                val end = group + 5 + size
                var at = group + 6
                if (u(group + 5) ushr 6 == 2) at += 5
                if (at >= end) return null
                val count = u(at++)
                if (count !in 1..8) return null
                val found = mutableListOf<Language>()
                repeat(count) {
                    if (at >= end) return null
                    val header = u(at++)
                    if (header and 15 in 12..14) at++
                    if (at + 4 > end) return null
                    val code = data.copyOfRange(at, at + 3).toString(Charsets.US_ASCII)
                    if (!code.all { it in 'a'..'z' }) return null
                    val id = (header ushr 5) + 1
                    // libaribcaption implements the two languages allowed by the broadcast profile.
                    if (id <= 2) found += Language(id, code)
                    at += 4
                }
                if (at + 3 > end) return null
                languages = found.distinctBy { it.id }
            }
            return AribCaptionPacket(type, data.copyOf(), languages, (groupId and 0x1f).takeIf { it in 1..2 })
        }
    }
}
