package com.dev.Tvivo.ui.player

/** Platform-independent decisions for entering and leaving the video-only window. */
internal object PipPolicy {
    enum class FormFactor { PhoneTablet, Television, Other }
    enum class Entry { None, AutoEnter, OnUserLeaveHint }
    enum class Exit { KeepVideo, StopPlayback }

    fun entry(
        formFactor: FormFactor,
        isPlaying: Boolean,
        featureAvailable: Boolean,
        sdkInt: Int,
        stopping: Boolean = false,
    ): Entry = when {
        formFactor != FormFactor.PhoneTablet || !isPlaying || !featureAvailable || stopping || sdkInt < 26 -> Entry.None
        sdkInt >= 31 -> Entry.AutoEnter
        else -> Entry.OnUserLeaveHint
    }

    fun exit(inPip: Boolean, playWhenReady: Boolean, explicitClose: Boolean): Exit = when {
        explicitClose -> Exit.StopPlayback
        else -> Exit.KeepVideo
    }

    /** Expansion clears the remembered PiP window in onResume; a stopped exit is dismissal. */
    fun shouldStopAfterExit(wasInPip: Boolean, inPip: Boolean,
                            changingConfigurations: Boolean, stopPending: Boolean): Boolean =
        wasInPip && !inPip && !changingConfigurations && !stopPending
}
