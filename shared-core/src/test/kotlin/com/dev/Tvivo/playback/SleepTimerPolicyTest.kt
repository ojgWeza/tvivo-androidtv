package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPolicyTest {
    @Test
    fun presets_start_duration_timers_from_the_supplied_monotonic_time() {
        for (minutes in listOf(15, 30, 45, 60, 90)) {
            val policy = SleepTimerPolicy()
            val now = 7_200_000L

            assertEquals(SleepTimerState.Armed, policy.startPreset(now, minutes).state)
            assertEquals(SleepTimerMode.Duration, policy.mode)
            assertEquals(now + minutes * 60_000L, policy.deadlineMilliseconds)
            assertNull(policy.targetItemIdentity)
        }
    }

    @Test
    fun unsupported_presets_are_rejected_without_changing_state() {
        val policy = SleepTimerPolicy()
        try {
            policy.startPreset(0, 20)
            throw AssertionError("Expected unsupported preset to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(SleepTimerState.Off, policy.state)
        }
    }

    @Test
    fun custom_duration_defaults_clamps_and_rounds_to_five_minute_steps() {
        val policy = SleepTimerPolicy()
        policy.startCustom(0)
        assertEquals(30 * 60_000L, policy.deadlineMilliseconds)

        assertEquals(5, SleepTimerPolicy.clampCustomMinutes(Int.MIN_VALUE))
        assertEquals(5, SleepTimerPolicy.clampCustomMinutes(7))
        assertEquals(10, SleepTimerPolicy.clampCustomMinutes(12))
        assertEquals(15, SleepTimerPolicy.clampCustomMinutes(13))
        assertEquals(240, SleepTimerPolicy.clampCustomMinutes(Int.MAX_VALUE))

        policy.startCustom(2 * 60_000L, 1)
        assertEquals(7 * 60_000L, policy.deadlineMilliseconds)
        policy.startCustom(2 * 60_000L, 999)
        assertEquals(242 * 60_000L, policy.deadlineMilliseconds)
    }

    @Test
    fun direct_duration_is_clamped_and_rounded() {
        val policy = SleepTimerPolicy()
        policy.startDuration(0, 242 * 60_000L)
        assertEquals(240 * 60_000L, policy.deadlineMilliseconds)
        policy.startDuration(0, 12 * 60_000L)
        assertEquals(10 * 60_000L, policy.deadlineMilliseconds)
        policy.startDuration(0, 13 * 60_000L)
        assertEquals(15 * 60_000L, policy.deadlineMilliseconds)
        policy.startDuration(0, 0)
        assertEquals(5 * 60_000L, policy.deadlineMilliseconds)
    }

    @Test
    fun warning_is_one_shot_at_the_exact_two_minute_boundary() {
        val policy = startedForTenMinutes()

        val before = policy.tick(8 * 60_000L - 1)
        assertFalse(before.showWarning)
        assertEquals(SleepTimerState.Armed, before.state)

        val at = policy.tick(8 * 60_000L)
        assertTrue(at.showWarning)
        assertEquals(SleepTimerState.Warning, at.state)

        val repeated = policy.tick(8 * 60_000L + 1_000L)
        assertFalse(repeated.showWarning)
        assertEquals(SleepTimerState.Warning, repeated.state)
    }

    @Test
    fun exact_deadline_stops_once_and_enters_ended() {
        val policy = startedForTenMinutes()

        val atDeadline = policy.tick(10 * 60_000L)
        assertTrue(atDeadline.stopPlayback)
        assertEquals(SleepTimerState.Ended, atDeadline.state)

        val duplicate = policy.tick(10 * 60_000L)
        assertFalse(duplicate.stopPlayback)
        assertFalse(duplicate.showWarning)
        assertEquals(SleepTimerState.Ended, duplicate.state)
    }

    @Test
    fun large_elapsed_time_jump_stops_immediately_without_catch_up_warning_or_fade() {
        val policy = startedForTenMinutes()

        val afterJump = policy.tick(4 * 60 * 60_000L)

        assertTrue(afterJump.stopPlayback)
        assertFalse(afterJump.showWarning)
        assertEquals(0.0, afterJump.fadeFraction, 0.0)
        assertEquals(SleepTimerState.Ended, policy.state)
    }

    @Test
    fun extension_adds_fifteen_minutes_to_existing_deadline_and_resets_warning() {
        val policy = startedForTenMinutes()
        val originalDeadline = policy.deadlineMilliseconds
        assertTrue(policy.tick(8 * 60_000L).showWarning)

        val extended = policy.extend(8 * 60_000L)

        assertEquals(SleepTimerState.Armed, extended.state)
        assertFalse(extended.showWarning)
        assertEquals(originalDeadline!! + 15 * 60_000L, policy.deadlineMilliseconds)
        assertFalse(policy.tick(8 * 60_000L + 1).showWarning)
        assertTrue(policy.tick(23 * 60_000L).showWarning)
    }

    @Test
    fun extension_at_or_after_deadline_cannot_resurrect_timer() {
        val policy = startedForTenMinutes()

        val decision = policy.extend(10 * 60_000L)

        assertTrue(decision.stopPlayback)
        assertEquals(SleepTimerState.Ended, policy.state)
        assertEquals(10 * 60_000L, policy.deadlineMilliseconds)
    }

    @Test
    fun duration_timer_is_independent_of_pause_seek_and_wall_clock_changes() {
        val policy = startedForTenMinutes()
        val deadline = policy.deadlineMilliseconds

        assertEquals(SleepTimerState.Armed, policy.tick(4 * 60_000L).state)
        assertEquals(deadline, policy.deadlineMilliseconds)
        assertFalse(policy.tick(4 * 60_000L).stopPlayback)
        assertTrue(policy.tick(10 * 60_000L).stopPlayback)
    }

    @Test
    fun duration_timer_survives_manual_and_automatic_item_changes() {
        val policy = startedForTenMinutes()
        val deadline = policy.deadlineMilliseconds

        assertEquals(SleepTimerState.Armed, policy.manualItemChange().state)
        assertEquals(SleepTimerState.Armed, policy.genuineNaturalEnd("next-item", true).state)
        assertEquals(deadline, policy.deadlineMilliseconds)
        assertEquals(SleepTimerMode.Duration, policy.mode)
    }

    @Test
    fun live_cannot_start_end_of_item_timer_and_existing_timer_is_preserved() {
        val policy = startedForTenMinutes()
        val deadline = policy.deadlineMilliseconds

        assertFalse(policy.tryStartEndOfCurrentItem("live-channel", isLive = true))
        assertEquals(SleepTimerMode.Duration, policy.mode)
        assertEquals(deadline, policy.deadlineMilliseconds)
    }

    @Test
    fun manual_item_change_cancels_end_of_item_mode() {
        val policy = SleepTimerPolicy()
        assertTrue(policy.tryStartEndOfCurrentItem("episode-A", isLive = false))

        val changed = policy.manualItemChange()

        assertEquals(SleepTimerState.Off, changed.state)
        assertEquals(SleepTimerNoticeReason.ItemChanged, changed.noticeReason)
        assertNull(policy.mode)
        assertNull(policy.targetItemIdentity)
    }

    @Test
    fun only_genuine_end_of_target_item_satisfies_end_of_item_timer() {
        val policy = SleepTimerPolicy()
        policy.tryStartEndOfCurrentItem("episode-A", isLive = false)

        assertEquals(SleepTimerState.Armed, policy.genuineNaturalEnd("episode-A", false).state) // early EOF
        assertEquals(SleepTimerState.Armed, policy.genuineNaturalEnd("episode-B", true).state)
        assertEquals(SleepTimerMode.EndOfCurrentItem, policy.mode)

        val naturalEnd = policy.genuineNaturalEnd("episode-A", true)
        assertTrue(naturalEnd.stopPlayback)
        assertEquals(SleepTimerState.Ended, naturalEnd.state)
    }

    @Test
    fun stale_generation_natural_end_callback_is_ignored() {
        val policy = SleepTimerPolicy()
        policy.tryStartEndOfCurrentItem("episode-A", isLive = false)

        val stale = policy.genuineNaturalEnd("episode-A", true, callbackGeneration = 8, currentGeneration = 9)

        assertFalse(stale.stopPlayback)
        assertEquals(SleepTimerState.Armed, stale.state)
        assertTrue(policy.genuineNaturalEnd("episode-A", true, 9, 9).stopPlayback)
    }

    @Test
    fun final_failure_cancels_timer_and_does_not_rearm_it() {
        val policy = startedForTenMinutes()

        val failure = policy.finalFailure()

        assertEquals(SleepTimerState.Off, failure.state)
        assertEquals(SleepTimerNoticeReason.FinalFailure, failure.noticeReason)
        assertNull(policy.deadlineMilliseconds)
        assertFalse(policy.finalFailure().stopPlayback)
    }

    @Test
    fun fade_progress_and_remaining_display_are_pure_duration_queries() {
        val policy = startedForTenMinutes()
        val deadline = policy.deadlineMilliseconds!!

        assertEquals(0.0, policy.getFadeFraction(deadline - 6_000L), 0.0)
        assertEquals(0.0, policy.getFadeFraction(deadline - 5_000L), 0.0)
        assertEquals(0.5, policy.getFadeFraction(deadline - 2_500L), 0.000001)
        assertEquals(1.0, policy.getFadeFraction(deadline), 0.0)
        assertEquals(SleepTimerState.Armed, policy.state)
        assertEquals(SleepTimerRemainingDisplay(2, SleepTimerRemainingUnit.Minutes),
            policy.getRemainingDisplay(deadline - 61_000L))
        assertEquals(SleepTimerRemainingDisplay(2, SleepTimerRemainingUnit.Seconds),
            policy.getRemainingDisplay(deadline - 1_500L))

        policy.tryStartEndOfCurrentItem("episode-A", isLive = false)
        assertNull(policy.getRemainingDisplay(0))
    }

    private fun startedForTenMinutes(): SleepTimerPolicy = SleepTimerPolicy().apply {
        startDuration(0, 10 * 60_000L)
    }
}
