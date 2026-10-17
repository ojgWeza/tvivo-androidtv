package com.dev.Tvivo.playback

/** Small decisions kept independent of Android so routing and privacy rules stay testable. */
internal object PlaybackSessionPolicy {
    enum class Action { Stop, Ignore }

    fun routeAction(action: String, mayControlSession: Boolean): Action =
        if (mayControlSession && action == PlaybackCommands.STOP) Action.Stop else Action.Ignore

    fun mayControlSession(controllerUid: Int, appUid: Int, trusted: Boolean): Boolean =
        controllerUid == appUid || trusted

    fun mayRunPrivilegedCommands(controllerUid: Int, appUid: Int): Boolean = controllerUid == appUid

    fun displayTitle(title: String): String {
        val cleaned = title.trim().replace(Regex("[\\r\\n\\t]+"), " ")
        return if (cleaned.isBlank() || Regex("(?i)(https?|rtmp|file)://|\\b\\S+@\\S+\\b").containsMatchIn(cleaned)) {
            "Now playing"
        } else cleaned
    }

    fun positionToSave(currentPositionMs: Long, lastGoodPositionMs: Long, failed: Boolean): Long =
        (if (failed) lastGoodPositionMs else currentPositionMs).coerceAtLeast(0L)

    interface AudioTarget {
        fun handleAudioFocus(enabled: Boolean)
        fun pauseWhenBecomingNoisy(enabled: Boolean)
    }

    fun configureAudio(target: AudioTarget) {
        target.handleAudioFocus(true)
        target.pauseWhenBecomingNoisy(true)
    }
}
