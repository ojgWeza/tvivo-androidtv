package com.dev.Tvivo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipPlaybackActionTest {
    @Test fun actionsMapToTransportCommands() {
        assertEquals(PipPlaybackAction.Command.PlayPause, PipPlaybackAction.command(PipPlaybackAction.PLAY_PAUSE))
        assertEquals(PipPlaybackAction.Command.Rewind, PipPlaybackAction.command(PipPlaybackAction.REWIND))
        assertEquals(PipPlaybackAction.Command.Forward, PipPlaybackAction.command(PipPlaybackAction.FORWARD))
        assertEquals(PipPlaybackAction.Command.Next, PipPlaybackAction.command(PipPlaybackAction.NEXT))
        assertEquals(PipPlaybackAction.Command.Close, PipPlaybackAction.command(PipPlaybackAction.CLOSE))
        assertNull(PipPlaybackAction.command("unknown"))
    }
}
