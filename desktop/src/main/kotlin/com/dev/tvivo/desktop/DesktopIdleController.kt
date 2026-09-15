package com.dev.tvivo.desktop

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class IdleState { Active, Idle }

/** Keeps the idle contract separate from Compose event handlers and screen navigation. */
internal class DesktopIdleController(private val scope: CoroutineScope) {
    val state = mutableStateOf(IdleState.Active)
    private var timer: Job? = null
    private var visible = true
    private var focused = true
    private var pausedForPlayer = false
    private var restoreTarget: DesktopRoute = DesktopRoute.Home

    fun onRouteChanged(route: DesktopRoute) {
        pausedForPlayer = route is DesktopRoute.Player
        if (route !is DesktopRoute.Player) restoreTarget = route
        if (pausedForPlayer) pause("player") else restart("navigation")
    }

    fun onInput(reason: String): Boolean {
        val wasIdle = state.value == IdleState.Idle
        if (wasIdle) {
            state.value = IdleState.Active
            idleTrace("idle rotation stopped(reason=$reason)")
            idleTrace("idle restored(route=${restoreTarget.idleName()})")
        }
        restart(reason)
        return wasIdle
    }

    fun onWindowFocusChanged(hasFocus: Boolean) { focused = hasFocus; if (hasFocus) restart("window-focus") else pause("window-hidden") }
    fun onVisibilityChanged(isVisible: Boolean) { visible = isVisible; if (isVisible) restart("window-visible") else pause("window-hidden") }
    fun onDialogVisibilityChanged(open: Boolean) { if (open) pause("dialog") else restart("dialog-dismissed") }
    fun dispose() = timer?.cancel()

    private fun restart(reason: String) {
        timer?.cancel()
        if (!visible || !focused || pausedForPlayer || state.value == IdleState.Idle) return
        idleTrace("idle timer restart(reason=$reason)")
        timer = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (visible && focused && !pausedForPlayer && state.value == IdleState.Active) {
                state.value = IdleState.Idle
                idleTrace("idle entered")
            }
        }
    }

    private fun pause(reason: String) { timer?.cancel(); timer = null; idleTrace("idle timer paused(reason=$reason)") }
    private companion object { const val IDLE_TIMEOUT_MS = 5 * 60 * 1_000L }
}

private fun DesktopRoute.idleName(): String = when (this) {
    DesktopRoute.Home -> "home"
    is DesktopRoute.Browse -> "browse-${type.name.lowercase()}"
    is DesktopRoute.Detail -> "detail"
    is DesktopRoute.Episodes -> "episodes"
    is DesktopRoute.Player -> "player"
    DesktopRoute.Account -> "account"
}

internal fun idleTrace(message: String) = println("[$message]")
