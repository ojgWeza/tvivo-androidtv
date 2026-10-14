using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackRetryPolicyTests
{
    [Theory]
    [InlineData(PlaybackAttemptResult.NetworkFailure)]
    [InlineData(PlaybackAttemptResult.Timeout)]
    [InlineData(PlaybackAttemptResult.UnknownFailure)]
    public void Transient_failures_retry_until_the_attempt_limit(PlaybackAttemptResult result)
    {
        Assert.True(PlaybackRetryPolicy.ShouldRetry(result, 1, 5, TimeSpan.Zero));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(result, 5, 5, TimeSpan.Zero));
    }

    [Fact]
    public void Not_found_retries_with_fast_delays_within_the_attempt_budget()
    {
        Assert.True(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, 1, 5, TimeSpan.Zero));
        Assert.True(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, 4, 5, TimeSpan.Zero));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, 5, 5, TimeSpan.Zero));
        Assert.Equal(TimeSpan.FromMilliseconds(400), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 1));
        Assert.Equal(TimeSpan.FromMilliseconds(800), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 2));
        Assert.Equal(TimeSpan.FromMilliseconds(1200), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 3));
        Assert.Equal(TimeSpan.FromMilliseconds(1600), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 4));
        Assert.Equal(TimeSpan.FromMilliseconds(1600), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 8));
    }

    [Fact]
    public void Not_found_retry_time_is_capped()
    {
        Assert.False(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.NotFound,
            1,
            10,
            PlaybackRetryPolicy.MaximumNotFoundElapsed - TimeSpan.FromMilliseconds(399)));
    }

    [Fact]
    public void Not_found_attempts_share_elapsed_time_including_engine_opens_and_backoff()
    {
        var elapsed = TimeSpan.Zero;
        var attempt = 0;
        while (PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, ++attempt, 10, elapsed))
        {
            elapsed += TimeSpan.FromSeconds(1.2); // fake open duration
            elapsed += PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, attempt);
        }

        Assert.True(elapsed <= PlaybackRetryPolicy.MaximumNotFoundHardElapsed);
        Assert.False(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, 1, 10,
            PlaybackRetryPolicy.MaximumNotFoundElapsed - TimeSpan.FromMilliseconds(399)));
        Assert.False(EngineFallbackPolicy.ShouldFallback(PlaybackAttemptResult.NotFound, alreadyTried: false));
    }

    [Fact]
    public void Final_failure_stays_on_player_with_a_user_choice_and_does_not_auto_advance()
    {
        var policy = new PlaybackFailurePolicy();
        Assert.True(policy.TryHandle(11, 11, 11, ready: true));
        Assert.False(policy.ShouldAutoAdvance(11));
        Assert.False(policy.TryHandle(11, 11, 11, ready: true));
    }

    [Theory]
    [InlineData(PlaybackAttemptResult.Forbidden)]
    [InlineData(PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(PlaybackAttemptResult.DecodeFailure)]
    [InlineData(PlaybackAttemptResult.HostFailure)]
    [InlineData(PlaybackAttemptResult.Cancelled)]
    public void Forbidden_403_and_unsupported_failures_do_not_retry(PlaybackAttemptResult result) =>
        Assert.False(PlaybackRetryPolicy.ShouldRetry(result, 1, 5, TimeSpan.Zero));

    [Fact]
    public void Retry_delay_and_elapsed_time_respect_the_overall_cap()
    {
        Assert.Equal(TimeSpan.FromSeconds(1), PlaybackRetryPolicy.Delay(1));
        Assert.Equal(TimeSpan.FromSeconds(4), PlaybackRetryPolicy.Delay(9));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.Timeout, 1, 5, PlaybackRetryPolicy.MaximumElapsed - TimeSpan.FromMilliseconds(500)));
    }

    [Fact]
    public void Interruption_retries_use_the_short_recovery_backoff()
    {
        Assert.Equal(TimeSpan.FromMilliseconds(500), PlaybackRetryPolicy.InterruptionDelay(1));
        Assert.Equal(TimeSpan.FromSeconds(1), PlaybackRetryPolicy.InterruptionDelay(2));
        Assert.Equal(TimeSpan.FromSeconds(2), PlaybackRetryPolicy.InterruptionDelay(3));
        Assert.Equal(TimeSpan.FromSeconds(3), PlaybackRetryPolicy.InterruptionDelay(4));
    }

    [Theory]
    [InlineData(-2, 1)]
    [InlineData(1, 1)]
    [InlineData(5, 5)]
    [InlineData(10, 10)]
    [InlineData(11, 10)]
    public void Attempt_setting_is_clamped(int value, int expected) =>
        Assert.Equal(expected, PlaybackRetryPolicy.ClampAttempts(value));
}
