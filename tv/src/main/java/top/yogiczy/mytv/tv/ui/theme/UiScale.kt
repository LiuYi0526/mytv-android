package top.yogiczy.mytv.tv.ui.theme

import kotlin.math.roundToInt

/** Fit the design into the available window without enlarging it on ultrawide screens. */
internal fun resolveUiScaleRatio(widthDp: Float, heightDp: Float, configuredRatio: Float): Float {
    if (configuredRatio != 0f) return configuredRatio
    return minOf(widthDp / DESIGN_WIDTH, heightDp / DESIGN_HEIGHT)
}

/** Same 2.4-column channel card rhythm as the dashboard, fitted to the grid's actual viewport. */
internal fun resolveChannelGridColumns(contentWidthDp: Float): Int {
    val layoutColumns = (contentWidthDp.coerceAtLeast(0f) / (LAYOUT_GRID_WIDTH + LAYOUT_GRID_SPACING)).roundToInt()
    return (layoutColumns / 2.4f).roundToInt().coerceAtLeast(1)
}
