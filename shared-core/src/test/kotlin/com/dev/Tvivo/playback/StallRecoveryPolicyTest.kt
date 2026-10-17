package com.dev.Tvivo.playback

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class StallRecoveryPolicyTest {
    @Test fun real_forward_progress_arms_exactly_one_recovery() {
        val policy = StallRecoveryPolicy(10_000)
        policy.beginSession(7, false)
        assertEquals(StallDecision.None, policy.observe(7, true, true, 5_000, 0))
        assertEquals(StallDecision.None, policy.observe(7, true, true, 6_000, 1_000))
        assertEquals(StallDecision.None, policy.observe(7, true, true, 6_000, 10_999))
        assertEquals(StallDecision.Recover, policy.observe(7, true, true, 6_000, 11_000))
        assertTrue(policy.recoveryAttempted)
        assertEquals(StallDecision.GiveUp, policy.observe(7, true, true, 6_000, 21_000))
    }

    @Test fun paused_seeking_buffering_and_stale_generations_do_not_accumulate_a_stall() {
        val policy = StallRecoveryPolicy(1_000)
        policy.beginSession(4, false)
        assertEquals(StallDecision.None, policy.observe(4, true, true, 10_000, 0))
        assertEquals(StallDecision.None, policy.observe(4, true, false, 10_000, 10_000))
        assertEquals(StallDecision.None, policy.observe(4, false, true, 10_000, 11_000))
        assertEquals(StallDecision.None, policy.observe(5, true, true, 10_000, 12_000))
        assertEquals(StallDecision.None, policy.observe(4, true, true, 10_000, 13_000))
        assertFalse(policy.recoveryAttempted)
    }

    @Test fun user_session_resets_recovery_and_recovery_generation_preserves_budget() {
        val policy = StallRecoveryPolicy(1)
        policy.beginSession(1, false)
        policy.observe(1, true, true, 0, 0)
        policy.observe(1, true, true, 1, 1)
        assertEquals(StallDecision.Recover, policy.observe(1, true, true, 1, 2))
        policy.beginSession(2, true)
        policy.observe(2, true, true, 1, 3)
        assertEquals(StallDecision.GiveUp, policy.observe(2, true, true, 1, 4))
        assertEquals(StallDecision.None, policy.observe(2, true, true, 1, 5))
        policy.beginSession(3, false)
        assertFalse(policy.recoveryAttempted)
    }
}
