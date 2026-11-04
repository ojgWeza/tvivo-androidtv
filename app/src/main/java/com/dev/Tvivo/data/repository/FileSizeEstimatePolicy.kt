package com.dev.Tvivo.data.repository

/** Conservative plausibility bounds for Xtream bitrate and runtime metadata. */
object FileSizeEstimatePolicy {
    private const val MIN_BITRATE_KBPS = 128
    private const val MAX_BITRATE_KBPS = 80_000
    private const val MIN_DURATION_SECONDS = 60
    private const val MAX_DURATION_SECONDS = 28_800
    private const val MIN_BYTES = 1_000_000L
    private const val MAX_BYTES = 200_000_000_000L

    fun estimateBytes(bitrateKbps: Int?, durationSeconds: Int?): Long? {
        val bitrate = bitrateKbps?.takeIf { it in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS } ?: return null
        val duration = durationSeconds?.takeIf { it in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS } ?: return null
        // kbit/s * seconds / 8 gives kilobytes; convert decimal KB to bytes.
        val bytes = bitrate.toLong() * duration.toLong() * 1_000L / 8L
        return bytes.takeIf { it in MIN_BYTES..MAX_BYTES }
    }
}
