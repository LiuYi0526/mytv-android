package top.yogiczy.mytv.tv.ui.theme

/** Fit the design into the available window without enlarging it on ultrawide screens. */
internal fun resolveUiScaleRatio(widthDp: Float, heightDp: Float, configuredRatio: Float): Float {
    if (configuredRatio != 0f) return configuredRatio
    return minOf(widthDp / DESIGN_WIDTH, heightDp / DESIGN_HEIGHT)
}
