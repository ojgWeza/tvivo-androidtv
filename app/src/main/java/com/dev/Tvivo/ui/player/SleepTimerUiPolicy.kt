package com.dev.Tvivo.ui.player

/** UI-facing timer state and command mapping kept free of Android/Compose types for JVM tests. */
data class SleepTimerUiState(
    val state: State = State.Off,
    val mode: Mode? = null,
    val remainingMs: Long = -1L,
    val endedTitle: String? = null,
    val endedItemId: String? = null,
) {
    enum class State { Off, Armed, Warning, Ended }
    enum class Mode { Duration, EndOfCurrentItem }

    val isActive: Boolean get() = state == State.Armed || state == State.Warning
    val isWarning: Boolean get() = state == State.Warning
    val isEnded: Boolean get() = state == State.Ended

    companion object {
        fun fromPayload(
            state: String?, mode: String?, remainingMs: Long,
            endedTitle: String?, endedItemId: String?
        ): SleepTimerUiState {
            val mappedState = State.values().firstOrNull { it.name == state } ?: State.Off
            val mappedMode = Mode.values().firstOrNull { it.name == mode }
            return SleepTimerUiState(mappedState, mappedMode, remainingMs, endedTitle, endedItemId)
        }
    }
}

data class SleepTimerUiCommand(val action: String, val minutes: Int? = null)

object SleepTimerUiPolicy {
    val presets = listOf(15, 30, 45, 60, 90)
    private const val PREFIX = "com.dev.Tvivo.playback."

    fun preset(minutes: Int) = SleepTimerUiCommand("TIMER_START_PRESET", minutes)
    fun custom(minutes: Int) = SleepTimerUiCommand("TIMER_START_CUSTOM", minutes.coerceIn(5, 240).let { ((it + 2) / 5) * 5 })
    fun endOfItem() = SleepTimerUiCommand("TIMER_START_END_OF_ITEM")
    fun extend() = SleepTimerUiCommand("TIMER_EXTEND_15")
    fun cancel() = SleepTimerUiCommand("TIMER_CANCEL")

    fun actionFor(command: SleepTimerUiCommand): String? = when (command.action) {
        "TIMER_START_PRESET" -> PREFIX + "TIMER_START_PRESET"
        "TIMER_START_CUSTOM" -> PREFIX + "TIMER_START_CUSTOM"
        "TIMER_START_END_OF_ITEM" -> PREFIX + "TIMER_START_END_OF_ITEM"
        "TIMER_EXTEND_15" -> PREFIX + "TIMER_EXTEND_15"
        "TIMER_CANCEL" -> PREFIX + "TIMER_CANCEL"
        else -> null
    }

    fun remainingLabel(remainingMs: Long): RemainingLabel? {
        if (remainingMs < 0L) return null
        return if (remainingMs >= 60_000L) {
            RemainingLabel(((remainingMs + 59_999L) / 60_000L).toInt(), false)
        } else {
            RemainingLabel(((remainingMs + 999L) / 1_000L).toInt(), true)
        }
    }

    data class RemainingLabel(val value: Int, val seconds: Boolean)
}

internal data class EndedItemSnapshot(val requestId: String, val title: String, val itemId: String)

internal sealed interface PlayerSessionExitState {
    data class EndedView(val item: EndedItemSnapshot) : PlayerSessionExitState
    data object Stopped : PlayerSessionExitState
}

internal data class ReplayItem(val url: String, val title: String, val itemId: String?)
internal data class ReplayRequest(val item: ReplayItem, val requestId: String, val positionMs: Long)

/** The service may already be gone when the Activity learns that playback ended. */
internal class PlayerSessionExitController(
    private val endedSnapshot: () -> EndedItemSnapshot?,
    private val requestPlay: (ReplayRequest) -> Unit,
) {
    fun disconnected(expectedRequestId: String?): PlayerSessionExitState {
        val ended = endedSnapshot()?.takeIf { expectedRequestId != null && it.requestId == expectedRequestId }
        return if (ended != null) PlayerSessionExitState.EndedView(ended) else PlayerSessionExitState.Stopped
    }

    fun watchAgain(item: ReplayItem, newRequestId: String) {
        requestPlay(ReplayRequest(item, newRequestId, 0L))
    }
}
