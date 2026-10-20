using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class Round2PolicyTests
{
    [Fact]
    public void Cooldown_schedule_and_recent_window_cover_boundaries()
    {
        foreach (var factor in new[] { -1d, 0.9d, 1d, 1.1d, 2d })
        {
            var schedule = RecentlyLeftCooldownPolicy.BuildSchedule(() => factor);
            Assert.Equal(6, schedule.Count);
            Assert.True(schedule.Zip(schedule.Skip(1), (a, b) => b > a).All(value => value));
            Assert.InRange(schedule[^1], TimeSpan.FromSeconds(58.5), TimeSpan.FromSeconds(65));
        }
        foreach (var age in new[] { 0, 1, 69, 70, 71, 100 })
        {
            var now = TimeSpan.Zero;
            var policy = new RecentlyLeftCooldownPolicy(() => now);
            policy.BeginOpen("a", StreamKind.Movie);
            now = TimeSpan.FromTicks(1);
            policy.BeginOpen("b", StreamKind.Movie);
            now = TimeSpan.FromSeconds(age);
            Assert.Equal(age <= 70 ? RecentlyLeftCooldownResult.WaitForProvider : RecentlyLeftCooldownResult.NotEligible,
                policy.BeginOpen("a", StreamKind.Movie));
        }
    }

    [Fact]
    public void Cooldown_history_is_bounded_without_losing_recent_item()
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("keep", StreamKind.Movie);
        for (var index = 0; index < RecentlyLeftCooldownPolicy.HistoryCapacity * 2; index++)
        {
            now += TimeSpan.FromMilliseconds(1);
            policy.BeginOpen($"other-{index}", StreamKind.Movie);
        }
        Assert.Equal(RecentlyLeftCooldownResult.NotEligible, policy.BeginOpen("keep", StreamKind.Movie));
    }

    [Fact]
    public void Retry_progress_is_bounded_and_reveals_at_exact_delay_for_each_kind()
    {
        foreach (var kind in new[] { StreamKind.Movie, StreamKind.Episode, StreamKind.Live })
        {
            var timeout = PlaybackRetryPolicy.AttemptTimeout(kind);
            var backoff = PlaybackRetryPolicy.BackoffDelays(5);
            var previous = 0d;
            for (var milliseconds = 0; milliseconds <= 200_000; milliseconds += 250)
            {
                var progress = PlaybackRetryProgressPolicy.GetProgress(3, 5, timeout, backoff,
                    TimeSpan.FromMilliseconds(milliseconds), kind);
                Assert.InRange(progress.Fraction, previous, 1d);
                Assert.Equal(milliseconds >= 1500, progress.IsVisible);
                previous = progress.Fraction;
            }
        }
    }

    [Fact]
    public void Sleep_timer_uses_supplied_monotonic_time_and_ignores_old_end_callbacks()
    {
        var timer = new SleepTimerPolicy();
        timer.StartDuration(TimeSpan.FromHours(4), TimeSpan.FromMinutes(5));
        Assert.False(timer.Tick(TimeSpan.FromHours(4) + TimeSpan.FromMinutes(4)).StopPlayback);
        Assert.True(timer.Tick(TimeSpan.FromHours(4) + TimeSpan.FromMinutes(5)).StopPlayback);
        Assert.False(timer.Tick(TimeSpan.FromHours(4) + TimeSpan.FromMinutes(6)).StopPlayback);

        timer.StartDuration(TimeSpan.Zero, TimeSpan.FromMinutes(5));
        Assert.True(timer.Tick(TimeSpan.FromMinutes(3)).ShowWarning);
        timer.Extend(TimeSpan.FromMinutes(3));
        Assert.Equal(TimeSpan.FromMinutes(20), timer.Deadline);
        Assert.False(timer.Tick(TimeSpan.FromMinutes(3)).ShowWarning);
        Assert.True(timer.Tick(TimeSpan.FromMinutes(18)).ShowWarning);

        Assert.False(timer.TryStartEndOfCurrentItem("live", isLive: true));
        Assert.Equal(SleepTimerMode.Duration, timer.Mode);
        Assert.True(timer.TryStartEndOfCurrentItem("episode", isLive: false));
        Assert.False(timer.GenuineNaturalEnd("episode", true, 1, 2).StopPlayback);
        Assert.Equal(SleepTimerNoticeReason.ItemChanged, timer.ManualItemChange().NoticeReason);
        Assert.False(timer.GenuineNaturalEnd("episode", true).StopPlayback);
    }

    [Fact]
    public void Spotlight_touch_needs_its_own_first_tap_after_hover_or_keyboard_open()
    {
        var state = SpotlightStripState.Create(3).Open(2);
        Assert.False(state.TouchTapActivates(2, -1));
        Assert.False(state.TouchTapActivates(1, 2));
        Assert.True(state.TouchTapActivates(2, 2));
        Assert.Equal(2, state.Move(50).OpenIndex);
        Assert.Equal(0, state.Move(-50).OpenIndex);
        Assert.False(SpotlightStripState.ShouldOpenAfterDwell(0, 1, TimeSpan.FromMilliseconds(349)));
        Assert.True(SpotlightStripState.ShouldOpenAfterDwell(0, 1, TimeSpan.FromMilliseconds(350)));
    }

    [Fact]
    public void Spotlight_metadata_rejects_reused_card_after_render_or_channel_change()
    {
        Assert.True(SpotlightMetadataOwnership.CanApply(8, 8, true, true, false));
        Assert.False(SpotlightMetadataOwnership.CanApply(8, 9, true, true, false));
        Assert.False(SpotlightMetadataOwnership.CanApply(8, 8, false, true, false));
        Assert.False(SpotlightMetadataOwnership.CanApply(8, 8, true, false, false));
        Assert.False(SpotlightMetadataOwnership.CanApply(8, 8, true, true, true));
    }

    [Fact]
    public void Search_close_pointer_and_session_guards_cover_all_boolean_combinations()
    {
        foreach (var mask in Enumerable.Range(0, 128))
        {
            var flags = Enumerable.Range(0, 7).Select(bit => (mask & (1 << bit)) != 0).ToArray();
            Assert.Equal(flags[0] && flags[1] && !flags[2] && !flags[3] && !flags[4] && !flags[5] && flags[6],
                CompactPointerHidePolicy.ShouldHide(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6]));
        }
        Assert.Equal("alt-f4", WindowCloseRequestPolicy.ClassifyNative(true, true));
        Assert.Equal("taskbar", WindowCloseRequestPolicy.ClassifyNative(true, false));
        Assert.Equal("unknown", WindowCloseRequestPolicy.ClassifyNative(false, true));
        Assert.False(SearchActivationPolicy.Allows(true, "pointer", "none", false, 1));
        Assert.False(SearchActivationPolicy.Allows(true, "pointer", "none", true, 2001));
        Assert.True(SearchActivationPolicy.Allows(true, "keyboard", "Enter", true, 2000));
        Assert.False(PlaybackSessionOwnership.CanStopProgress(2, 3));
        Assert.False(PlaybackSessionOwnership.CanCompleteSleepTimerStop(2, 3, true));
    }
}
