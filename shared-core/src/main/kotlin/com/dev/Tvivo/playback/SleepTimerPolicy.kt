package com.dev.Tvivo.playback

/** Timer lifecycle. Time is supplied by the caller using a monotonic millisecond clock. */
enum class SleepTimerState { Off, Armed, Warning, Ended }

enum class SleepTimerMode { Duration, EndOfCurrentItem }

enum class SleepTimerNoticeReason { None, Cancelled, ItemChanged, FinalFailure }

enum class SleepTimerRemainingUnit { Minutes, Seconds }

data class SleepTimerDecision(
    val state: SleepTimerState,
    val showWarning: Boolean = false,
    val stopPlayback: Boolean = false,
    val fadeFraction: Double = 0.0,
    val noticeReason: SleepTimerNoticeReason = SleepTimerNoticeReason.None,
)

data class SleepTimerRemainingDisplay(val value: Int, val unit: SleepTimerRemainingUnit)

/**
 * Pure sleep-timer state machine. Pass elapsed-realtime milliseconds from the caller;
 * pause, seek, and wall-clock changes are intentionally not policy inputs.
 */
class SleepTimerPolicy {
    companion object {
        val presetMinutes: List<Int> = listOf(15, 30, 45, 60, 90)
        const val minimumDurationMinutes = 5
        const val maximumDurationMinutes = 240
        const val durationStepMinutes = 5
        const val defaultDurationMinutes = 30
        const val warningLeadMilliseconds = 2 * 60 * 1_000L
        const val fadeDurationMilliseconds = 5 * 1_000L
        const val extensionDurationMilliseconds = 15 * 60 * 1_000L

        /** Clamp and round a custom minute value to the nearest five-minute step. */
        fun clampCustomMinutes(minutes: Int): Int {
            val clamped = minutes.coerceIn(minimumDurationMinutes, maximumDurationMinutes)
            return kotlin.math.floor(clamped / durationStepMinutes.toDouble() + 0.5).toInt() * durationStepMinutes
        }
    }

    private var warningShown = false

    var state: SleepTimerState = SleepTimerState.Off
        private set
    var mode: SleepTimerMode? = null
        private set
    var deadlineMilliseconds: Long? = null
        private set
    var targetItemIdentity: String? = null
        private set

    fun startPreset(nowMilliseconds: Long, minutes: Int): SleepTimerDecision {
        require(minutes in presetMinutes) { "Unsupported sleep-timer preset: $minutes" }
        return startDuration(nowMilliseconds, minutes * 60_000L)
    }

    fun startCustom(nowMilliseconds: Long, minutes: Int = defaultDurationMinutes): SleepTimerDecision =
        startDuration(nowMilliseconds, clampCustomMinutes(minutes) * 60_000L)

    /** Duration is clamped to 5..240 minutes and rounded to the nearest 5-minute step. */
    fun startDuration(nowMilliseconds: Long, durationMilliseconds: Long): SleepTimerDecision {
        val minutes = durationMilliseconds / 60_000.0
        val clampedMinutes = minutes.coerceIn(minimumDurationMinutes.toDouble(), maximumDurationMinutes.toDouble())
        val steppedMinutes = kotlin.math.floor(clampedMinutes / durationStepMinutes + 0.5).toLong() * durationStepMinutes
        setTimer(SleepTimerMode.Duration, nowMilliseconds + steppedMinutes * 60_000L, null)
        return decision()
    }

    /** Returns false for Live without changing the timer already in force. */
    fun tryStartEndOfCurrentItem(itemIdentity: String, isLive: Boolean): Boolean {
        require(itemIdentity.isNotEmpty()) { "Item identity must not be empty" }
        if (isLive) return false
        setTimer(SleepTimerMode.EndOfCurrentItem, null, itemIdentity)
        return true
    }

    fun tick(nowMilliseconds: Long): SleepTimerDecision {
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended ||
            mode != SleepTimerMode.Duration || deadlineMilliseconds == null
        ) return decision()

        val remaining = deadlineMilliseconds!! - nowMilliseconds
        if (remaining <= 0L) {
            state = SleepTimerState.Ended
            return decision(stopPlayback = true)
        }

        val showWarning = !warningShown && remaining <= warningLeadMilliseconds
        if (showWarning) warningShown = true
        state = if (warningShown) SleepTimerState.Warning else SleepTimerState.Armed
        return decision(showWarning = showWarning, fadeFraction = getFadeFraction(nowMilliseconds))
    }

    /** Adds fifteen minutes to the existing deadline; it never resets the timer from now. */
    fun extend(nowMilliseconds: Long): SleepTimerDecision {
        val deadline = deadlineMilliseconds
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended ||
            mode != SleepTimerMode.Duration || deadline == null
        ) return decision()

        if (nowMilliseconds >= deadline) return tick(nowMilliseconds)
        deadlineMilliseconds = deadline + extensionDurationMilliseconds
        warningShown = false
        state = SleepTimerState.Armed
        return decision()
    }

    fun cancel(): SleepTimerDecision {
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended) return decision()
        turnOff()
        return decision(noticeReason = SleepTimerNoticeReason.Cancelled)
    }

    /** Manual changes cancel an end-of-item timer but leave a duration timer running. */
    fun manualItemChange(): SleepTimerDecision {
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended ||
            mode != SleepTimerMode.EndOfCurrentItem
        ) return decision()
        turnOff()
        return decision(noticeReason = SleepTimerNoticeReason.ItemChanged)
    }

    /** Only a verified natural end of the targeted item ends an end-of-item timer. */
    fun genuineNaturalEnd(itemIdentity: String, isGenuineEnd: Boolean): SleepTimerDecision {
        require(itemIdentity.isNotEmpty()) { "Item identity must not be empty" }
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended ||
            mode != SleepTimerMode.EndOfCurrentItem || !isGenuineEnd ||
            targetItemIdentity != itemIdentity
        ) return decision()
        state = SleepTimerState.Ended
        return decision(stopPlayback = true)
    }

    /** Stale player callbacks cannot satisfy a timer targeting the current generation. */
    fun genuineNaturalEnd(
        itemIdentity: String,
        isGenuineEnd: Boolean,
        callbackGeneration: Long,
        currentGeneration: Long,
    ): SleepTimerDecision {
        if (callbackGeneration != currentGeneration) return decision()
        return genuineNaturalEnd(itemIdentity, isGenuineEnd)
    }

    fun finalFailure(): SleepTimerDecision {
        if (state == SleepTimerState.Off || state == SleepTimerState.Ended) return decision()
        turnOff()
        return decision(noticeReason = SleepTimerNoticeReason.FinalFailure)
    }

    /** Returns fade progress in the final five seconds without changing timer state. */
    fun getFadeFraction(nowMilliseconds: Long): Double {
        val deadline = deadlineMilliseconds ?: return 0.0
        if (mode != SleepTimerMode.Duration) return 0.0
        val remaining = deadline - nowMilliseconds
        if (remaining >= fadeDurationMilliseconds) return 0.0
        if (remaining <= 0L) return 1.0
        return (1.0 - remaining.toDouble() / fadeDurationMilliseconds).coerceIn(0.0, 1.0)
    }

    fun getRemainingDisplay(nowMilliseconds: Long): SleepTimerRemainingDisplay? {
        val deadline = deadlineMilliseconds ?: return null
        if (mode != SleepTimerMode.Duration) return null
        val remaining = deadline - nowMilliseconds
        return if (remaining < 60_000L) {
            SleepTimerRemainingDisplay(ceilUnits(remaining, 1_000L), SleepTimerRemainingUnit.Seconds)
        } else {
            SleepTimerRemainingDisplay(ceilUnits(remaining, 60_000L), SleepTimerRemainingUnit.Minutes)
        }
    }

    private fun setTimer(mode: SleepTimerMode, deadline: Long?, targetItemIdentity: String?) {
        this.mode = mode
        deadlineMilliseconds = deadline
        this.targetItemIdentity = targetItemIdentity
        warningShown = false
        state = SleepTimerState.Armed
    }

    private fun turnOff() {
        state = SleepTimerState.Off
        mode = null
        deadlineMilliseconds = null
        targetItemIdentity = null
        warningShown = false
    }

    private fun decision(
        showWarning: Boolean = false,
        stopPlayback: Boolean = false,
        fadeFraction: Double = 0.0,
        noticeReason: SleepTimerNoticeReason = SleepTimerNoticeReason.None,
    ) = SleepTimerDecision(state, showWarning, stopPlayback, fadeFraction, noticeReason)

    private fun ceilUnits(value: Long, unit: Long): Int {
        if (value <= 0L) return 0
        val units = value / unit + if (value % unit == 0L) 0 else 1
        return units.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

}
