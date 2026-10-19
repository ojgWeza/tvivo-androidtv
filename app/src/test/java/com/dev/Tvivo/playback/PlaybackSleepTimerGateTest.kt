package com.dev.Tvivo.playback

import kotlinx.coroutines.test.runTest
import com.dev.Tvivo.ui.player.SleepTimerUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSleepTimerGateTest {
    private class FakePort : PlaybackHandoff.Port {
        var connections = 0
        var peakConnections = 0
        val saves = mutableListOf<Pair<Boolean, Boolean>>()
        override suspend fun save(item: PlaybackHandoff.Item, ended: Boolean, allowCompletion: Boolean) {
            saves += ended to allowCompletion
        }
        override fun close() { connections = 0 }
        override fun open(item: PlaybackHandoff.Item, generation: Long) {
            connections++
            peakConnections = maxOf(peakConnections, connections)
        }
    }

    private fun item(id: String) = PlaybackHandoff.Item(id, "https://example.invalid/$id.mp4",
        id, "account", "vod", id, false, 0L)

    @Test fun expiryWinsOverNextRetryReconnectAndLateEof() = runTest {
        for (lateAction in listOf("next", "retry", "reconnect", "eof")) {
            val port = FakePort()
            val handoff = PlaybackHandoff(port)
            val timer = SleepTimerPolicy()
            handoff.play(item("first"))
            timer.startCustom(0, 5)
            val expiry = timer.tick(300_000L)
            assertTrue(expiry.stopPlayback)
            handoff.stop(allowCompletion = false)
            if (lateAction == "eof")
                assertFalse(timer.genuineNaturalEnd("first", true).stopPlayback)

            if (PlaybackSleepTimerGate.permitsTransition(timer.tick(300_001L), false)) {
                when (lateAction) {
                    "next" -> handoff.play(item("second"), previousEnded = true)
                    "retry", "reconnect" -> handoff.reopenCurrent()
                    "eof" -> handoff.play(item("second"), previousEnded = true)
                }
            }
            assertFalse(PlaybackSleepTimerGate.permitsTransition(timer.tick(300_001L), false))
            assertEquals(lateAction, 0, port.connections)
            assertEquals(lateAction, 1, port.peakConnections)
            assertEquals(lateAction, listOf(false to false), port.saves)
        }
    }

    @Test fun warningActionsAreAvailableWithoutTheActivity() {
        val timer = SleepTimerPolicy()
        timer.startCustom(0, 5)
        assertEquals(listOf(PlaybackCommands.TIMER_EXTEND_15, PlaybackCommands.TIMER_CANCEL,
            PlaybackCommands.STOP),
            PlaybackSleepTimerGate.notificationActions(timer.state))
        assertTrue(timer.tick(180_000L).showWarning)
        assertEquals(listOf(PlaybackCommands.TIMER_EXTEND_15, PlaybackCommands.TIMER_CANCEL,
            PlaybackCommands.STOP), PlaybackSleepTimerGate.notificationActions(timer.state))
        assertEquals(PlaybackSleepTimerGate.NotificationAction.Extend,
            PlaybackSleepTimerGate.notificationAction(PlaybackCommands.TIMER_EXTEND_15))
        assertEquals(PlaybackSleepTimerGate.NotificationAction.Cancel,
            PlaybackSleepTimerGate.notificationAction(PlaybackCommands.TIMER_CANCEL))
        timer.extend(180_000L)
        assertEquals(listOf(PlaybackCommands.TIMER_EXTEND_15, PlaybackCommands.TIMER_CANCEL,
            PlaybackCommands.STOP),
            PlaybackSleepTimerGate.notificationActions(timer.state))
        timer.cancel()
        assertEquals(listOf(PlaybackCommands.STOP),
            PlaybackSleepTimerGate.notificationActions(timer.state))
    }

    @Test fun notificationExtensionReportsRemainingTimeWhileArmed() {
        val timer = SleepTimerPolicy()
        timer.startCustom(0, 5)
        timer.tick(180_000L)
        timer.extend(180_000L)
        assertEquals(SleepTimerState.Armed, timer.state)
        val remaining = PlaybackSleepTimerGate.remainingMilliseconds(timer, 180_000L)
        assertEquals(17 * 60_000L, remaining)
        assertEquals(SleepTimerUiPolicy.RemainingLabel(17, false),
            SleepTimerUiPolicy.remainingLabel(remaining))
        timer.cancel()
        assertEquals(-1L, PlaybackSleepTimerGate.remainingMilliseconds(timer, 180_000L))
    }
}
