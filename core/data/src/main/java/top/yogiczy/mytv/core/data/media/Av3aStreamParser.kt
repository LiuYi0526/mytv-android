package top.yogiczy.mytv.core.data.media

/** Bounded frame assembly across TS/PES boundaries. No Android or Media3 dependency. */
class Av3aStreamParser(private val onFrame: (Av3aHeader, ByteArray, Long?) -> Unit) {
    private val headerBytes = ByteArray(Av3aHeader.HEADER_SIZE)
    private var count = 0
    private var header: Av3aHeader? = null
    private var frame: ByteArray? = null
    private var pendingTimeUs: Long? = null
    private var nextTimeUs: Long? = null
    private var frameTimeUs: Long? = null
    private var timeRemainder = 0L

    fun packetStarted(timeUs: Long?) {
        if (timeUs != null) pendingTimeUs = timeUs
    }

    fun seek() {
        count = 0
        header = null
        frame = null
        pendingTimeUs = null
        nextTimeUs = null
        frameTimeUs = null
        timeRemainder = 0
    }

    fun consume(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val currentFrame = frame
            if (currentFrame != null) {
                val copied = minOf(end - cursor, currentFrame.size - count)
                data.copyInto(currentFrame, count, cursor, cursor + copied)
                count += copied
                cursor += copied
                if (count == currentFrame.size) {
                    val currentHeader = checkNotNull(header)
                    onFrame(currentHeader, currentFrame, frameTimeUs)
                    val duration = Av3aHeader.SAMPLES_PER_FRAME * 1_000_000L + timeRemainder
                    nextTimeUs = frameTimeUs?.plus(duration / currentHeader.sampleRate)
                    timeRemainder = duration % currentHeader.sampleRate
                    frame = null
                    header = null
                    count = 0
                }
                continue
            }
            val byte = data[cursor++]
            if (count == 0) {
                if (byte != 0xff.toByte()) continue
                frameTimeUs = pendingTimeUs ?: nextTimeUs
                if (pendingTimeUs != null) timeRemainder = 0
                pendingTimeUs = null
            }
            headerBytes[count++] = byte
            if (count == 2 && byte != 0xf2.toByte()) {
                count = if (byte == 0xff.toByte()) 1 else 0
                nextTimeUs = frameTimeUs
                continue
            }
            if (count != headerBytes.size) continue
            val parsed = Av3aHeader.parse(headerBytes)
            if (parsed == null) {
                // Resynchronize inside an invalid header without moving the input backwards.
                val next = (1 until count).firstOrNull { headerBytes[it] == 0xff.toByte() }
                count = if (next == null) 0 else (count - next).also {
                    headerBytes.copyInto(headerBytes, 0, next, headerBytes.size)
                }
                nextTimeUs = frameTimeUs
                continue
            }
            header = parsed
            frame = headerBytes.copyOf(parsed.frameSize)
        }
    }
}
