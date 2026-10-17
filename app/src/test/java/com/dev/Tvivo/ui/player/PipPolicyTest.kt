package com.dev.Tvivo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipPolicyTest {
    @Test fun playingPhoneAndTabletEnterOnSupportedDevices() {
        assertEquals(PipPolicy.Entry.OnUserLeaveHint,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 26))
        assertEquals(PipPolicy.Entry.OnUserLeaveHint,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 30))
        assertEquals(PipPolicy.Entry.AutoEnter,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 31))
        assertEquals(PipPolicy.Entry.AutoEnter,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 34))
    }

    @Test fun noEntryForPausedTvUnsupportedOrStopping() {
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, false, true, 34))
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.Television, true, true, 34))
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.Other, true, true, 34))
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, false, 34))
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 25))
        assertEquals(PipPolicy.Entry.None,
            PipPolicy.entry(PipPolicy.FormFactor.PhoneTablet, true, true, 34, stopping = true))
    }

    @Test fun pauseDismissesOnlyPipWhileCloseStopsPlayback() {
        assertEquals(PipPolicy.Exit.KeepVideo, PipPolicy.exit(true, true, false))
        assertEquals(PipPolicy.Exit.KeepVideo, PipPolicy.exit(false, false, false))
        assertEquals(PipPolicy.Exit.DismissPaused, PipPolicy.exit(true, false, false))
        assertEquals(PipPolicy.Exit.StopPlayback, PipPolicy.exit(true, true, true))
        assertEquals(PipPolicy.Exit.StopPlayback, PipPolicy.exit(true, false, true))
    }

    @Test fun stoppedPipExitIsDismissalButResumedExpansionIsNot() {
        assertEquals(true, PipPolicy.shouldStopAfterExit(true, false, false, false))
        assertEquals(false, PipPolicy.shouldStopAfterExit(false, false, false, false))
        assertEquals(false, PipPolicy.shouldStopAfterExit(true, true, false, false))
        assertEquals(false, PipPolicy.shouldStopAfterExit(true, false, true, false))
        assertEquals(false, PipPolicy.shouldStopAfterExit(true, false, false, true))
    }
}
