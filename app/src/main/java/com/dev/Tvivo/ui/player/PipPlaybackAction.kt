package com.dev.Tvivo.ui.player

/** The broadcast action is translated here so the PiP transport contract stays testable. */
internal object PipPlaybackAction {
    const val PLAY_PAUSE = "com.dev.Tvivo.player.PIP_PLAY_PAUSE"
    const val CLOSE = "com.dev.Tvivo.player.PIP_CLOSE"
    const val REWIND = "com.dev.Tvivo.player.PIP_REWIND"
    const val FORWARD = "com.dev.Tvivo.player.PIP_FORWARD"
    const val NEXT = "com.dev.Tvivo.player.PIP_NEXT"

    enum class Command { PlayPause, Close, Rewind, Forward, Next }

    fun command(action: String?): Command? = when (action) {
        PLAY_PAUSE -> Command.PlayPause
        CLOSE -> Command.Close
        REWIND -> Command.Rewind
        FORWARD -> Command.Forward
        NEXT -> Command.Next
        else -> null
    }
}
