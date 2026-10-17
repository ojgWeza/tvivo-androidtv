package com.dev.Tvivo.ui.player

/** Platform-independent decisions for entering and leaving the video-only window. */
internal object PipPolicy {
    enum class FormFactor { PhoneTablet, Television, Other }
    enum class Entry { None, AutoEnter, OnUserLeaveHint }
    enum class Exit { KeepVideo, DismissPaused, StopPlayback }

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
        inPip && !playWhenReady -> Exit.DismissPaused
        else -> Exit.KeepVideo
    }

    /** Expansion resumes the Activity; dismissal leaves it stopped after exiting PiP. */
    fun shouldStopAfterExit(exitPending: Boolean, changingConfigurations: Boolean,
                            dismissingPausedPip: Boolean, stopPending: Boolean): Boolean =
        exitPending && !changingConfigurations && !dismissingPausedPip && !stopPending
}
