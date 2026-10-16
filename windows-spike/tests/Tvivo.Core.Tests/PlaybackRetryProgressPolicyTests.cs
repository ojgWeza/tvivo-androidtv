using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackRetryProgressPolicyTests
{
    [Fact]
    public void Progress_is_monotonic_and_clamped_to_the_policy_schedule()
    {
        var attempts = PlaybackRetryPolicy.ClampAttempts(5);
        var timeout = PlaybackRetryPolicy.AttemptTimeout(StreamKind.Movie);
        var backoffs = PlaybackRetryPolicy.BackoffDelays(attempts);
        var elapsedValues = new[]
        {
            TimeSpan.Zero,
            TimeSpan.FromSeconds(20),
            TimeSpan.FromSeconds(50),
            TimeSpan.FromSeconds(100),
            TimeSpan.FromHours(1),
        };

        var progress = elapsedValues.Select(elapsed => PlaybackRetryProgressPolicy.GetProgress(
            3, attempts, timeout, backoffs, elapsed, StreamKind.Movie).Fraction).ToArray();

        Assert.All(progress, value => Assert.InRange(value, 0d, 1d));
        Assert.Equal(progress.OrderBy(value => value), progress);
        Assert.Equal(1d, progress[^1]);
    }

    [Fact]
    public void Status_advances_with_attempt_and_elapsed_stages()
    {
        var timeout = TimeSpan.FromSeconds(10);
        var backoffs = PlaybackRetryPolicy.BackoffDelays(5);

        Assert.Equal("Opening your movie...", GetStatus(1, TimeSpan.Zero));
        Assert.Equal("Opening your movie...", GetStatus(1, TimeSpan.FromMilliseconds(3999)));
        Assert.Equal("Still connecting, this can take a moment...", GetStatus(1, TimeSpan.FromSeconds(4)));
        Assert.Equal("Trying again...", GetStatus(2, TimeSpan.FromSeconds(10)));
        Assert.Equal("Almost there, checking again...", GetStatus(4, TimeSpan.FromSeconds(35)));
        Assert.Equal("Last try...", GetStatus(5, TimeSpan.FromSeconds(50)));
        Assert.All(new[]
        {
            GetStatus(1, TimeSpan.Zero), GetStatus(1, TimeSpan.FromSeconds(4)),
            GetStatus(2, TimeSpan.FromSeconds(10)), GetStatus(4, TimeSpan.FromSeconds(35)),
            GetStatus(5, TimeSpan.FromSeconds(50)),
        }, status => Assert.InRange(status.Length, 1, 60));

        string GetStatus(int attempt, TimeSpan elapsed) => PlaybackRetryProgressPolicy.GetProgress(
            attempt, 5, timeout, backoffs, elapsed, StreamKind.Movie).Status;
    }

    [Fact]
    public void Live_uses_tuning_wording()
    {
        var backoffs = PlaybackRetryPolicy.BackoffDelays(5);
        Assert.Equal("Tuning in...", PlaybackRetryProgressPolicy.GetProgress(
            1, 5, TimeSpan.FromSeconds(10), backoffs, TimeSpan.Zero, StreamKind.Live).Status);
        Assert.Equal("Still tuning in, this can take a moment...", PlaybackRetryProgressPolicy.GetProgress(
            1, 5, TimeSpan.FromSeconds(10), backoffs, TimeSpan.FromSeconds(4), StreamKind.Live).Status);
    }

    [Theory]
    [InlineData(1499, false)]
    [InlineData(1500, true)]
    [InlineData(2000, true)]
    public void Progress_bar_waits_until_the_first_attempt_has_taken_a_moment(int elapsedMilliseconds, bool visible)
    {
        var progress = PlaybackRetryProgressPolicy.GetProgress(
            1,
            5,
            PlaybackRetryPolicy.AttemptTimeout(StreamKind.Movie),
            PlaybackRetryPolicy.BackoffDelays(5),
            TimeSpan.FromMilliseconds(elapsedMilliseconds),
            StreamKind.Movie);

        Assert.Equal(visible, progress.IsVisible);
    }
}
