using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class RecentlyLeftCooldownPolicyTests
{
    [Theory]
    [InlineData(69, RecentlyLeftCooldownResult.WaitForProvider)]
    [InlineData(71, RecentlyLeftCooldownResult.NotEligible)]
    public void Recent_prior_open_and_a_different_item_open_determine_cooldown(int seconds, RecentlyLeftCooldownResult expected)
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("movie-a", StreamKind.Movie);
        now = TimeSpan.FromSeconds(1);
        policy.BeginOpen("movie-b", StreamKind.Movie);
        now = TimeSpan.FromSeconds(seconds);

        Assert.Equal(expected, policy.BeginOpen("movie-a", StreamKind.Movie));
    }

    [Fact]
    public void Reopening_the_same_item_without_an_intervening_item_is_not_a_cooldown()
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("movie-a", StreamKind.Movie);
        now = TimeSpan.FromSeconds(5);

        Assert.Equal(RecentlyLeftCooldownResult.NotEligible, policy.BeginOpen("movie-a", StreamKind.Movie));
    }

    [Fact]
    public void Recently_left_episode_is_eligible()
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("episode-a", StreamKind.Episode);
        now = TimeSpan.FromSeconds(1);
        policy.BeginOpen("episode-b", StreamKind.Episode);
        now = TimeSpan.FromSeconds(10);

        Assert.Equal(RecentlyLeftCooldownResult.WaitForProvider, policy.BeginOpen("episode-a", StreamKind.Episode));
    }

    [Theory]
    [InlineData(StreamKind.Live)]
    [InlineData(StreamKind.Series)]
    public void Non_playable_item_kinds_are_excluded(StreamKind kind)
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("same", kind);
        now = TimeSpan.FromSeconds(1);
        policy.BeginOpen("other", kind);
        now = TimeSpan.FromSeconds(2);

        Assert.Equal(RecentlyLeftCooldownResult.NotEligible, policy.BeginOpen("same", kind));
    }

    [Fact]
    public void A_different_item_must_have_opened_after_the_same_item()
    {
        var now = TimeSpan.Zero;
        var policy = new RecentlyLeftCooldownPolicy(() => now);
        policy.BeginOpen("movie-a", StreamKind.Movie);
        now = TimeSpan.FromSeconds(71);
        policy.BeginOpen("movie-b", StreamKind.Movie);
        now = TimeSpan.FromSeconds(72);

        Assert.Equal(RecentlyLeftCooldownResult.NotEligible, policy.BeginOpen("movie-a", StreamKind.Movie));
    }

    [Theory]
    [InlineData(0.9)]
    [InlineData(1.0)]
    [InlineData(1.1)]
    public void Schedule_is_monotonic_and_stays_within_jitter_bounds_and_total_window(double factor)
    {
        var schedule = RecentlyLeftCooldownPolicy.BuildSchedule(() => factor);
        var offsets = new[] { 3d, 10d, 25d, 45d, 58d, 65d };
        Assert.Equal(6, schedule.Count);
        Assert.True(schedule.Zip(schedule.Skip(1), (left, right) => right > left).All(value => value));
        for (var index = 0; index < schedule.Count; index++)
            Assert.InRange(schedule[index].TotalSeconds, offsets[index] * 0.9, Math.Min(offsets[index] * 1.1, 65));
        Assert.InRange(schedule[^1].TotalSeconds, 58.5, 65);
    }

    [Fact]
    public void Jitter_values_are_clamped_to_ten_percent()
    {
        Assert.Equal(2.7, RecentlyLeftCooldownPolicy.BuildSchedule(() => 0.2)[0].TotalSeconds, 6);
        Assert.Equal(3.3, RecentlyLeftCooldownPolicy.BuildSchedule(() => 3)[0].TotalSeconds, 6);
    }

    [Theory]
    [InlineData(0, "Opening your video...")]
    [InlineData(5, "The provider is getting it ready...")]
    [InlineData(15, "Still connecting, this can take a moment...")]
    [InlineData(30, "Almost there, checking again...")]
    [InlineData(45, "Nearly ready...")]
    [InlineData(58, "Last check...")]
    [InlineData(65, "Last check...")]
    public void Status_message_tracks_each_time_slice(int seconds, string expected) =>
        Assert.Equal(expected, RecentlyLeftCooldownPolicy.StatusAt(TimeSpan.FromSeconds(seconds)));

    [Theory]
    [InlineData(-1, 0)]
    [InlineData(0, 0)]
    [InlineData(32.5, 0.5)]
    [InlineData(65, 1)]
    [InlineData(90, 1)]
    public void Progress_fraction_is_clamped(double seconds, double expected) =>
        Assert.Equal(expected, RecentlyLeftCooldownPolicy.ProgressFraction(TimeSpan.FromSeconds(seconds)), 6);

    [Fact]
    public void Cancellation_and_new_generations_stop_the_wait()
    {
        Assert.False(RecentlyLeftCooldownPolicy.IsCurrentSession(true, 8, 8));
        Assert.False(RecentlyLeftCooldownPolicy.IsCurrentSession(false, 8, 9));
        Assert.True(RecentlyLeftCooldownPolicy.IsCurrentSession(false, 8, 8));
    }
}
