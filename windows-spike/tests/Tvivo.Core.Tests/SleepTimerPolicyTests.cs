using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SleepTimerPolicyTests
{
    [Theory]
    [InlineData(15)]
    [InlineData(30)]
    [InlineData(45)]
    [InlineData(60)]
    [InlineData(90)]
    [InlineData(120)]
    public void Presets_start_a_duration_timer(int minutes)
    {
        var policy = new SleepTimerPolicy();
        var now = TimeSpan.FromHours(2);

        var decision = policy.StartPreset(now, minutes);

        Assert.Equal(SleepTimerState.Armed, decision.State);
        Assert.Equal(SleepTimerMode.Duration, policy.Mode);
        Assert.Equal(now + TimeSpan.FromMinutes(minutes), policy.Deadline);
        Assert.Null(policy.TargetItemIdentity);
    }

    [Fact]
    public void Unsupported_preset_is_rejected()
    {
        var policy = new SleepTimerPolicy();

        Assert.Throws<ArgumentOutOfRangeException>(() => policy.StartPreset(TimeSpan.Zero, 20));
        Assert.Equal(SleepTimerState.Off, policy.State);
    }

    [Fact]
    public void Custom_duration_defaults_to_thirty_minutes_and_clamps_and_rounds_to_five_minute_steps()
    {
        var policy = new SleepTimerPolicy();
        policy.StartCustom(TimeSpan.Zero);
        Assert.Equal(TimeSpan.FromMinutes(30), policy.Deadline);

        Assert.Equal(5, SleepTimerPolicy.ClampCustomMinutes(int.MinValue));
        Assert.Equal(5, SleepTimerPolicy.ClampCustomMinutes(7));
        Assert.Equal(10, SleepTimerPolicy.ClampCustomMinutes(12));
        Assert.Equal(15, SleepTimerPolicy.ClampCustomMinutes(13));
        Assert.Equal(240, SleepTimerPolicy.ClampCustomMinutes(int.MaxValue));

        policy.StartCustom(TimeSpan.FromMinutes(2), 1);
        Assert.Equal(TimeSpan.FromMinutes(7), policy.Deadline);
        policy.StartCustom(TimeSpan.FromMinutes(2), 999);
        Assert.Equal(TimeSpan.FromMinutes(242), policy.Deadline);
    }

    [Fact]
    public void Direct_duration_input_is_clamped_and_rounded_to_the_custom_range_and_step()
    {
        var policy = new SleepTimerPolicy();

        policy.StartDuration(TimeSpan.Zero, TimeSpan.FromMinutes(242));
        Assert.Equal(TimeSpan.FromMinutes(240), policy.Deadline);
        policy.StartDuration(TimeSpan.Zero, TimeSpan.FromMinutes(12));
        Assert.Equal(TimeSpan.FromMinutes(10), policy.Deadline);
        policy.StartDuration(TimeSpan.Zero, TimeSpan.FromMinutes(13));
        Assert.Equal(TimeSpan.FromMinutes(15), policy.Deadline);
        policy.StartDuration(TimeSpan.Zero, TimeSpan.Zero);
        Assert.Equal(TimeSpan.FromMinutes(5), policy.Deadline);
    }

    [Fact]
    public void Hint_is_shown_once_at_the_exact_five_minute_boundary()
    {
        var policy = StartedForTenMinutes();

        var beforeBoundary = policy.Tick(TimeSpan.FromMinutes(5) - TimeSpan.FromTicks(1));
        Assert.False(beforeBoundary.ShowHint);
        Assert.Equal(SleepTimerState.Armed, beforeBoundary.State);

        var atBoundary = policy.Tick(TimeSpan.FromMinutes(5));
        Assert.True(atBoundary.ShowHint);
        Assert.Equal(SleepTimerState.Armed, atBoundary.State);

        var repeated = policy.Tick(TimeSpan.FromMinutes(5) + TimeSpan.FromSeconds(1));
        Assert.False(repeated.ShowHint);
        Assert.Equal(SleepTimerState.Armed, repeated.State);
    }

    [Fact]
    public void Exact_deadline_stops_playback_and_repeated_ticks_do_not_repeat_the_stop_decision()
    {
        var policy = StartedForTenMinutes();

        var atDeadline = policy.Tick(TimeSpan.FromMinutes(10));
        Assert.True(atDeadline.StopPlayback);
        Assert.Equal(SleepTimerState.Ended, atDeadline.State);

        var duplicate = policy.Tick(TimeSpan.FromMinutes(10));
        Assert.False(duplicate.StopPlayback);
        Assert.False(duplicate.ShowHint);
        Assert.Equal(SleepTimerState.Ended, duplicate.State);
    }

    [Fact]
    public void Large_resume_time_jump_past_deadline_stops_immediately_without_hint()
    {
        var policy = StartedForTenMinutes();

        var afterResume = policy.Tick(TimeSpan.FromHours(4));

        Assert.True(afterResume.StopPlayback);
        Assert.False(afterResume.ShowHint);
        Assert.Equal(SleepTimerState.Ended, policy.State);
    }

    [Fact]
    public void Extension_adds_fifteen_minutes_to_the_existing_deadline_and_dismisses_hint()
    {
        var policy = StartedForTenMinutes();
        var oldDeadline = policy.Deadline;
        Assert.True(policy.Tick(TimeSpan.FromMinutes(5)).ShowHint);

        var extended = policy.Extend(TimeSpan.FromMinutes(5));

        Assert.Equal(SleepTimerState.Armed, extended.State);
        Assert.False(extended.ShowHint);
        Assert.Equal(oldDeadline + TimeSpan.FromMinutes(15), policy.Deadline);
        Assert.False(policy.Tick(TimeSpan.FromMinutes(5) + TimeSpan.FromTicks(1)).ShowHint);
        Assert.True(policy.Tick(TimeSpan.FromMinutes(20)).ShowHint);
    }

    [Fact]
    public void Extension_cannot_resurrect_a_timer_after_its_deadline()
    {
        var policy = StartedForTenMinutes();
        var decision = policy.Extend(TimeSpan.FromMinutes(10));

        Assert.True(decision.StopPlayback);
        Assert.Equal(SleepTimerState.Ended, policy.State);
        Assert.Equal(TimeSpan.FromMinutes(10), policy.Deadline);
    }

    [Fact]
    public void Cancel_turns_off_timer_and_clears_its_deadline_and_target()
    {
        var policy = StartedForTenMinutes();
        var decision = policy.Cancel();

        Assert.Equal(SleepTimerState.Off, decision.State);
        Assert.Equal(SleepTimerNoticeReason.Cancelled, decision.NoticeReason);
        Assert.Null(policy.Mode);
        Assert.Null(policy.Deadline);
        Assert.Null(policy.TargetItemIdentity);
        Assert.Equal(SleepTimerState.Off, policy.Cancel().State);
    }

    [Fact]
    public void Pauses_seeks_and_wall_clock_changes_do_not_affect_supplied_monotonic_deadline()
    {
        var policy = StartedForTenMinutes();
        var deadline = policy.Deadline;

        // Pause, seek, and wall-clock changes are intentionally not policy inputs.
        var stillArmed = policy.Tick(TimeSpan.FromMinutes(4));
        Assert.Equal(SleepTimerState.Armed, stillArmed.State);
        Assert.Equal(deadline, policy.Deadline);
        Assert.False(stillArmed.StopPlayback);
        Assert.True(policy.Tick(TimeSpan.FromMinutes(10)).StopPlayback);
    }

    [Fact]
    public void Duration_timer_survives_manual_and_natural_automatic_item_changes()
    {
        var policy = StartedForTenMinutes();
        var deadline = policy.Deadline;

        Assert.Equal(SleepTimerState.Armed, policy.ManualItemChange().State);
        Assert.Equal(SleepTimerState.Armed, policy.GenuineNaturalEnd("next-item", isGenuineEnd: true).State);
        Assert.Equal(deadline, policy.Deadline);
        Assert.Equal(SleepTimerMode.Duration, policy.Mode);
        Assert.True(policy.Tick(TimeSpan.FromMinutes(10)).StopPlayback);
    }

    [Fact]
    public void End_of_item_timer_rejects_live_and_preserves_existing_timer()
    {
        var policy = StartedForTenMinutes();
        var deadline = policy.Deadline;

        Assert.False(policy.TryStartEndOfCurrentItem("live-channel", isLive: true));
        Assert.Equal(SleepTimerMode.Duration, policy.Mode);
        Assert.Equal(deadline, policy.Deadline);

        Assert.True(policy.TryStartEndOfCurrentItem("movie-opaque-id", isLive: false));
        Assert.Equal(SleepTimerMode.EndOfCurrentItem, policy.Mode);
        Assert.Equal("movie-opaque-id", policy.TargetItemIdentity);
        Assert.Null(policy.Deadline);
    }

    [Fact]
    public void End_of_item_stops_only_for_a_genuine_end_of_the_target_item()
    {
        var policy = new SleepTimerPolicy();
        policy.TryStartEndOfCurrentItem("episode-A", isLive: false);

        Assert.Equal(SleepTimerState.Armed, policy.GenuineNaturalEnd("episode-A", isGenuineEnd: false).State);
        Assert.Equal(SleepTimerState.Armed, policy.GenuineNaturalEnd("episode-B", isGenuineEnd: true).State);
        Assert.Equal(SleepTimerMode.EndOfCurrentItem, policy.Mode);

        var genuineEnd = policy.GenuineNaturalEnd("episode-A", isGenuineEnd: true);
        Assert.True(genuineEnd.StopPlayback);
        Assert.Equal(SleepTimerState.Ended, genuineEnd.State);
    }

    [Fact]
    public void Stale_generation_natural_end_callback_is_ignored()
    {
        var policy = new SleepTimerPolicy();
        policy.TryStartEndOfCurrentItem("episode-A", isLive: false);

        var stale = policy.GenuineNaturalEnd("episode-A", true, callbackGeneration: 8, currentGeneration: 9);

        Assert.False(stale.StopPlayback);
        Assert.Equal(SleepTimerState.Armed, stale.State);
        Assert.True(policy.GenuineNaturalEnd("episode-A", true, 9, 9).StopPlayback);
    }

    [Fact]
    public void Manual_item_change_cancels_end_of_item_timer_with_notice()
    {
        var policy = new SleepTimerPolicy();
        policy.TryStartEndOfCurrentItem("episode-A", isLive: false);

        var decision = policy.ManualItemChange();

        Assert.Equal(SleepTimerState.Off, decision.State);
        Assert.Equal(SleepTimerNoticeReason.ItemChanged, decision.NoticeReason);
        Assert.Null(policy.Mode);
        Assert.Null(policy.TargetItemIdentity);
    }

    [Fact]
    public void Final_failure_turns_timer_off_and_does_not_rearm_it()
    {
        var policy = StartedForTenMinutes();

        var failure = policy.FinalFailure();

        Assert.Equal(SleepTimerState.Off, failure.State);
        Assert.Equal(SleepTimerNoticeReason.FinalFailure, failure.NoticeReason);
        Assert.Null(policy.Deadline);
        Assert.False(policy.FinalFailure().StopPlayback);
    }

    [Theory]
    [InlineData(3, 1)]
    [InlineData(5, 1)]
    [InlineData(9, 1)]
    [InlineData(10, 5)]
    [InlineData(15, 5)]
    [InlineData(180, 5)]
    public void Hint_window_depends_on_initial_duration(int durationMinutes, int leadMinutes)
    {
        var duration = TimeSpan.FromMinutes(durationMinutes);
        var lead = TimeSpan.FromMinutes(leadMinutes);
        Assert.False(SleepTimerPolicy.ShouldShowHint(duration, lead + TimeSpan.FromTicks(1)));
        Assert.True(SleepTimerPolicy.ShouldShowHint(duration, lead));
        Assert.True(SleepTimerPolicy.ShouldShowHint(duration, TimeSpan.FromTicks(1)));
        Assert.False(SleepTimerPolicy.ShouldShowHint(duration, TimeSpan.Zero));
    }

    [Fact]
    public void Five_minute_timer_hints_only_in_its_last_minute_and_manual_replay_resets_end()
    {
        var policy = new SleepTimerPolicy();
        policy.StartCustom(TimeSpan.Zero, 5);
        Assert.False(policy.Tick(TimeSpan.FromMinutes(3)).ShowHint);
        Assert.False(policy.Tick(TimeSpan.FromMinutes(4) - TimeSpan.FromTicks(1)).ShowHint);
        Assert.True(policy.Tick(TimeSpan.FromMinutes(4)).ShowHint);
        Assert.False(policy.Tick(TimeSpan.FromMinutes(4) + TimeSpan.FromSeconds(1)).ShowHint);
        Assert.True(policy.Tick(TimeSpan.FromMinutes(5)).StopPlayback);
        policy.ResetAfterManualReplay();
        Assert.Equal(SleepTimerState.Off, policy.State);
        Assert.False(policy.Tick(TimeSpan.FromMinutes(6)).StopPlayback);
    }

    [Theory]
    [InlineData(60, 1, SleepTimerRemainingUnit.Minutes)]
    [InlineData(59, 59, SleepTimerRemainingUnit.Seconds)]
    [InlineData(1, 1, SleepTimerRemainingUnit.Seconds)]
    [InlineData(0, 0, SleepTimerRemainingUnit.Seconds)]
    public void Remaining_display_uses_minutes_at_one_minute_and_seconds_below_it(
        int secondsBeforeDeadline,
        int expectedValue,
        SleepTimerRemainingUnit expectedUnit)
    {
        var policy = StartedForTenMinutes();
        var now = policy.Deadline!.Value - TimeSpan.FromSeconds(secondsBeforeDeadline);

        Assert.Equal(new SleepTimerRemainingDisplay(expectedValue, expectedUnit), policy.GetRemainingDisplay(now));
    }

    [Fact]
    public void Remaining_display_rounds_up_partial_units_and_is_absent_for_end_of_item()
    {
        var policy = StartedForTenMinutes();
        Assert.Equal(new SleepTimerRemainingDisplay(2, SleepTimerRemainingUnit.Minutes),
            policy.GetRemainingDisplay(policy.Deadline!.Value - TimeSpan.FromSeconds(61)));
        Assert.Equal(new SleepTimerRemainingDisplay(2, SleepTimerRemainingUnit.Seconds),
            policy.GetRemainingDisplay(policy.Deadline.Value - TimeSpan.FromMilliseconds(1500)));

        policy.TryStartEndOfCurrentItem("episode-A", isLive: false);
        Assert.Null(policy.GetRemainingDisplay(TimeSpan.Zero));
    }

    private static SleepTimerPolicy StartedForTenMinutes()
    {
        var policy = new SleepTimerPolicy();
        policy.StartDuration(TimeSpan.Zero, TimeSpan.FromMinutes(10));
        return policy;
    }
}
