using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class StallRecoveryPolicyTests
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    [Fact]
    public void Steady_advance_never_stalls()
    {
        var policy = NewPolicy(1);
        for (var second = 0; second <= 20; second++)
            Assert.Equal(StallDecision.None, Observe(policy, 1, second, second * 1000));
    }

    [Fact]
    public void Frozen_after_first_advance_recovers_once_after_timeout()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 0));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 1, 1000));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 10, 1000));
        Assert.Equal(StallDecision.Recover, Observe(policy, 1, 11, 1000));
        Assert.True(policy.RecoveryAttempted);
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 21, 1000));
    }

    [Fact]
    public void Recovery_session_stall_gives_up_and_never_recovers_again()
    {
        var policy = NewPolicy(1);
        var recoverCount = 0;
        if (Observe(policy, 1, 0, 0) == StallDecision.Recover) recoverCount++;
        if (Observe(policy, 1, 1, 1000) == StallDecision.Recover) recoverCount++;
        if (Observe(policy, 1, 11, 1000) == StallDecision.Recover) recoverCount++;

        policy.BeginSession(2, isRecovery: true);
        Assert.Equal(StallDecision.None, Observe(policy, 2, 12, 1000));
        var decision = Observe(policy, 2, 22, 1000);
        if (decision == StallDecision.Recover) recoverCount++;

        Assert.Equal(StallDecision.GiveUp, decision);
        Assert.Equal(StallDecision.None, Observe(policy, 2, 32, 1000));
        Assert.Equal(1, recoverCount);
    }

    [Fact]
    public void User_start_resets_budget_recovery_start_does_not()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 0));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 1, 1000));
        Assert.Equal(StallDecision.Recover, Observe(policy, 1, 11, 1000));

        policy.BeginSession(2, isRecovery: true);
        Assert.True(policy.RecoveryAttempted);
        policy.BeginSession(3, isRecovery: false);
        Assert.False(policy.RecoveryAttempted);
    }

    [Fact]
    public void Frozen_before_first_advance_never_recovers()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 0));
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 10, 0));
        Assert.False(policy.RecoveryAttempted);
    }

    [Fact]
    public void Resume_or_seek_jump_is_not_first_advance()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 0));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 1, 90_000));
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 11, 90_000));
        Assert.False(policy.RecoveryAttempted);
    }

    [Fact]
    public void Backward_seek_rebaselines_without_stall()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 1000));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 1, 2000));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 2, 500));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 11, 500));
        Assert.Equal(StallDecision.Recover, Observe(policy, 1, 12, 500));
    }

    [Fact]
    public void Stale_generation_never_stalls()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 2, 0, 0));
        Assert.Equal(StallDecision.None, Observe(policy, 2, 20, 0));
        Assert.False(policy.RecoveryAttempted);
    }

    [Fact]
    public void Session_not_ready_never_stalls()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, policy.Observe(1, false, true, 0, Seconds(0)));
        Assert.Equal(StallDecision.None, policy.Observe(1, false, true, 0, Seconds(20)));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 20, 0));
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 30, 0));
    }

    [Fact]
    public void Pause_seek_drag_suspend_and_restart_clock()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 1000));
        Assert.Equal(StallDecision.None, policy.Observe(1, true, false, 1000, Seconds(20)));
        Assert.Equal(StallDecision.None, policy.Observe(1, true, false, 1000, Seconds(21)));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 21, 1000));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 30, 1000));
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 31, 1000));
    }

    [Fact]
    public void GiveUp_is_reported_once_per_session()
    {
        var policy = NewPolicy(1);
        Assert.Equal(StallDecision.None, Observe(policy, 1, 0, 0));
        Assert.Equal(StallDecision.GiveUp, Observe(policy, 1, 10, 0));
        Assert.Equal(StallDecision.None, Observe(policy, 1, 20, 0));
    }

    private static StallRecoveryPolicy NewPolicy(long generation)
    {
        var policy = new StallRecoveryPolicy(Timeout);
        policy.BeginSession(generation, isRecovery: false);
        return policy;
    }

    private static StallDecision Observe(StallRecoveryPolicy policy, long generation, int second, long positionMs) =>
        policy.Observe(
            generation,
            sessionReady: true,
            eligible: true,
            positionMs: positionMs,
            now: Seconds(second));

    private static TimeSpan Seconds(int second) => TimeSpan.FromSeconds(second);
}
