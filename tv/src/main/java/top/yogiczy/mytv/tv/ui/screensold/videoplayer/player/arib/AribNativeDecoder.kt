package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.arib

import android.graphics.Bitmap
import androidx.annotation.Keep
import androidx.media3.common.text.Cue
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owned and closed on the caption worker; no native objects outlive the player. */
@Keep
internal class AribNativeDecoder(superimpose: Boolean, language: Int) : Closeable {
    private var handle: Long
    init {
        System.loadLibrary("liuguang_arib")
        handle = nativeCreate(superimpose, language)
        check(handle != 0L) { "ARIB decoder initialization failed" }
    }
    fun decode(data: ByteArray, positionMs: Long) = handle != 0L && nativeDecode(handle, data, positionMs)
    fun render(positionMs: Long): AribCaptionFrame? =
        if (handle == 0L) null else nativeRender(handle, positionMs)?.let(AribCaptionFrame::parse)
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }
    private external fun nativeCreate(superimpose: Boolean, language: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeDecode(handle: Long, bytes: ByteArray, positionMs: Long): Boolean
    private external fun nativeRender(handle: Long, positionMs: Long): ByteArray?
}

internal data class AribCaptionFrame(val startMs: Long, val durationMs: Long, val text: String, val cues: List<Cue>) {
    companion object {
        fun parse(data: ByteArray): AribCaptionFrame? = runCatching {
            require(data.size in 36..16 * 1024 * 1024)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            require(buffer.int == 0x41423234)
            val width = buffer.int
            val height = buffer.int
            require(width in 1..1920 && height in 1..1080)
            val start = buffer.long
            val duration = buffer.long
            require(duration >= -1)
            val textSize = buffer.int
            require(textSize in 0..minOf(buffer.remaining() - 4, 1024 * 1024))
            val text = ByteArray(textSize).also(buffer::get).toString(Charsets.UTF_8)
            val count = buffer.int
            require(count in 0..32)
            val cues = buildList {
              repeat(count) {
                val x = buffer.int
                val y = buffer.int
                val w = buffer.int
                val h = buffer.int
                require(w in 1..1920 && h in 1..1080 && x in -1920..1920 && y in -1080..1080)
                val bytes = w * h * 4
                require(bytes <= buffer.remaining())
                val source = buffer.position()
                buffer.position(source + bytes)
                // Glyph strokes/ruby may cross the caption plane edge. Clip pixels, not the entire cue.
                val left = maxOf(0, x)
                val top = maxOf(0, y)
                val right = minOf(width, x + w)
                val bottom = minOf(height, y + h)
                if (right <= left || bottom <= top) return@repeat
                val clippedWidth = right - left
                val clippedHeight = bottom - top
                val bitmap = Bitmap.createBitmap(clippedWidth, clippedHeight, Bitmap.Config.ARGB_8888)
                if (clippedWidth == w && clippedHeight == h) {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data, source, bytes))
                } else {
                    val pixels = ByteArray(clippedWidth * clippedHeight * 4)
                    repeat(clippedHeight) { row ->
                        val start = source + ((top - y + row) * w + left - x) * 4
                        data.copyInto(pixels, row * clippedWidth * 4, start, start + clippedWidth * 4)
                    }
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                }
                add(Cue.Builder().setBitmap(bitmap)
                    .setPosition(left.toFloat() / width).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(top.toFloat() / height, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(clippedWidth.toFloat() / width).setBitmapHeight(clippedHeight.toFloat() / height).build())
              }
            }
            require(!buffer.hasRemaining())
            AribCaptionFrame(start, duration, text, cues)
        }.getOrNull()
    }
}
