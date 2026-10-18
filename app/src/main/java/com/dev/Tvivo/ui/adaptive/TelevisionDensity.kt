package com.dev.Tvivo.ui.adaptive

/** Density tokens derived from the available TV window in dp, independent of panel diagonal. */
data class TelevisionDensity(
    val posterWidth: Int,
    val posterHeight: Int,
    val channelWidth: Int,
    val channelHeight: Int,
    val sideGutter: Int,
    val sectionGap: Int,
    val cardGap: Int,
    val shellHeight: Int,
    val headerHeight: Int,
    val titleHeight: Int
)

object TelevisionDensityPolicy {
    fun calculate(widthDp: Int, heightDp: Int, fontScale: Float = 1f): TelevisionDensity {
        val safeWidth = widthDp.coerceAtLeast(320)
        val safeHeight = heightDp.coerceAtLeast(320)
        val windowScale = minOf(safeWidth / 960f, safeHeight / 540f)
        val scale = ((1f + (windowScale - 1f) * 0.2f) *
            (1f / fontScale.coerceAtLeast(1f).coerceAtMost(1.5f))).coerceIn(0.88f, 1.6f)
        val posterWidth = (104f * scale).toInt().coerceIn(96, 160)
        val shellHeight = (72f * scale).toInt().coerceIn(64, 88)
        return TelevisionDensity(
            posterWidth = posterWidth,
            posterHeight = (posterWidth * 1.5f).toInt(),
            channelWidth = posterWidth,
            channelHeight = (posterWidth * 124f / 220f).toInt(),
            sideGutter = (32f * scale).toInt().coerceIn(24, 40),
            sectionGap = (8f * scale).toInt().coerceIn(6, 12),
            cardGap = (8f * scale).toInt().coerceIn(6, 12),
            shellHeight = shellHeight,
            headerHeight = (48f * scale).toInt().coerceIn(44, 60),
            titleHeight = (20f * fontScale.coerceIn(1f, 1.3f)).toInt().coerceIn(20, 26)
        )
    }
}
