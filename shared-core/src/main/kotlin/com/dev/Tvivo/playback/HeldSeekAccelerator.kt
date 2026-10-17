package com.dev.Tvivo.playback

object HeldSeekAccelerator {
    private const val ACCELERATION_START_MILLISECONDS = 400L
    private const val RAMP_INTERVAL_MILLISECONDS = 1_500L
    private const val INITIAL_STEP_MILLISECONDS = 30_000L
    private const val MAXIMUM_STEP_MILLISECONDS = 300_000L

    fun stepMilliseconds(heldMilliseconds: Long): Long {
        if (heldMilliseconds < ACCELERATION_START_MILLISECONDS) return 0
        var intervals = (heldMilliseconds - ACCELERATION_START_MILLISECONDS) / RAMP_INTERVAL_MILLISECONDS
        var step = INITIAL_STEP_MILLISECONDS
        while (intervals > 0 && step < MAXIMUM_STEP_MILLISECONDS) {
            step = minOf(MAXIMUM_STEP_MILLISECONDS, step * 2)
            intervals--
        }
        return step
    }
}
