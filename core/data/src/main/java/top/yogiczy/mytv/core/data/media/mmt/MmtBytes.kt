package top.yogiczy.mytv.core.data.media.mmt

/** All lengths from the stream are checked before advancing or allocating. */
internal class MmtBytes(val data: ByteArray, var position: Int = 0, val limit: Int = data.size) {
    val remaining get() = limit - position
    fun need(count: Int) { if (count < 0 || count > remaining) throw MmtMalformed() }
    fun u8(): Int { need(1); return data[position++].toInt() and 255 }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun skip(count: Int) { need(count); position += count }
    fun part(count: Int): MmtBytes { need(count); return MmtBytes(data, position, position + count).also { position += count } }
    fun bytes(count: Int = remaining): ByteArray { need(count); return data.copyOfRange(position, position + count).also { position += count } }
}

internal class MmtMalformed : RuntimeException()

internal fun <K, V> MutableMap<K, V>.putBounded(key: K, value: V, max: Int) {
    if (key !in this && size >= max) remove(keys.first())
    put(key, value)
}

internal fun ntpTimeUs(seconds: Long, fraction: Long): Long =
    (seconds - 2_208_988_800L) * 1_000_000L + ((fraction * 1_000_000L) ushr 32)

/** Reassembles one contiguous MMTP fragment chain. Gaps/duplicates never join different samples. */
internal class MmtFragments(private val maxBytes: Int) {
    private var buffer = ByteArray(0)
    private var count = 0
    private var active = false
    private var sequence: Long? = null
    fun reset() { count = 0; active = false; sequence = null }
    fun acceptSequence(next: Long): Boolean {
        val previous = sequence
        if (previous != null && next != ((previous + 1) and 0xffffffffL)) {
            val delta = (next - previous) and 0xffffffffL
            if (delta == 0L || delta >= 0x80000000L) return false
            count = 0
            active = false
        }
        sequence = next
        return true
    }
    fun append(indicator: Int, data: ByteArray): ByteArray? {
        if (indicator == 0) { count = 0; active = false; return data.takeIf { it.size <= maxBytes } }
        if (indicator == 1) { count = 0; active = true }
        if (!active) return null
        if (data.size > maxBytes - count) { count = 0; active = false; return null }
        if (count + data.size > buffer.size) buffer = buffer.copyOf(minOf(maxBytes, maxOf(count + data.size, buffer.size * 2, 1024)))
        data.copyInto(buffer, count)
        count += data.size
        if (indicator != 3) return null
        active = false
        return buffer.copyOf(count).also { count = 0 }
    }
}
