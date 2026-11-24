package top.yogiczy.mytv.tv.ui.screen.channels.components

import android.content.Context
import android.graphics.Bitmap
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import top.yogiczy.mytv.core.data.entities.channel.Channel

internal fun Channel.resolveLogo(overrideSourceLogo: Boolean, provider: String): String? =
    if (!overrideSourceLogo && !logo.isNullOrBlank()) logo else provider
        .replace("{name}", epgName)
        .replace("{name|lowercase}", epgName.lowercase())
        .replace("{name|uppercase}", epgName.uppercase())

internal fun channelLogoRequest(context: Context, logo: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo)
        // Trim at source resolution, then let the image view fit the cropped logo to its bounds.
        .size(Size.ORIGINAL)
        .allowHardware(false)
        .transformations(TrimTransparentLogo)
        .build()

internal object TrimTransparentLogo : Transformation {
    override val cacheKey = "top.yogiczy.mytv.TrimTransparentLogo.v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!input.hasAlpha()) return input

        val width = input.width
        val height = input.height
        val row = IntArray(width)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            input.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                if (row[x] ushr 24 == 0) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = y
            }
        }
        if (right < left) return input

        // Keep 5% of the shorter content edge as breathing room wherever the source allows it.
        val padding = (minOf(right - left + 1, bottom - top + 1) / 20).coerceAtLeast(1)
        val cropLeft = (left - padding).coerceAtLeast(0)
        val cropTop = (top - padding).coerceAtLeast(0)
        val cropRight = (right + padding + 1).coerceAtMost(width)
        val cropBottom = (bottom + padding + 1).coerceAtMost(height)
        if (cropLeft == 0 && cropTop == 0 && cropRight == width && cropBottom == height) return input
        return Bitmap.createBitmap(input, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
    }
}
