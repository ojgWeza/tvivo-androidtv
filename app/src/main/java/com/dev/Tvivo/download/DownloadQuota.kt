package com.dev.Tvivo.download

/** Pure byte accounting shared by queue admission and settings. */
object DownloadQuota {
    const val BYTES_PER_GB = 1_000_000_000L
    const val DEFAULT_CAP_GB = 20
    const val MIN_CAP_GB = 1
    const val MAX_CAP_GB = 500

    fun clampCapGb(value: Int): Int = value.coerceIn(MIN_CAP_GB, MAX_CAP_GB)
    fun capBytes(capGb: Int): Long = clampCapGb(capGb).toLong() * BYTES_PER_GB

    sealed interface Evaluation {
        data class Fits(val projected: Long, val cap: Long) : Evaluation
        data class ExceedsCap(val projected: Long, val cap: Long, val overBy: Long) : Evaluation
        data object SizeUnknown : Evaluation
    }

    data class ItemSize(val bytes: Long?, val exact: Boolean)

    fun evaluate(items: List<ItemSize>, cap: Long, used: Long, reserved: Long): Evaluation {
        if (items.any { it.bytes == null || it.bytes < 0 }) return Evaluation.SizeUnknown
        val requested = items.sumOf { item ->
            val bytes = requireNotNull(item.bytes)
            if (item.exact) bytes else conservativeEstimate(bytes)
        }
        val projected = safeAdd(safeAdd(used.coerceAtLeast(0), reserved.coerceAtLeast(0)), requested)
        return if (projected <= cap) Evaluation.Fits(projected, cap)
        else Evaluation.ExceedsCap(projected, cap, projected - cap)
    }

    fun conservativeEstimate(bytes: Long): Long =
        if (bytes > (Long.MAX_VALUE - 1) / 3) Long.MAX_VALUE else (bytes * 3 + 1) / 2

    private fun safeAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
