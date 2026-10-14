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
        Assert.Equal(TimeSpan.FromMilliseconds(500), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 1));
        Assert.Equal(TimeSpan.FromSeconds(1), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 2));
        Assert.Equal(TimeSpan.FromSeconds(2), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 3));
        Assert.Equal(TimeSpan.FromSeconds(3), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 4));
        Assert.Equal(TimeSpan.FromSeconds(3), PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, 8));
    }

    [Fact]
    public void Not_found_retry_time_is_capped()
    {
        Assert.False(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.NotFound,
            1,
            10,
            PlaybackRetryPolicy.MaximumNotFoundElapsed - TimeSpan.FromMilliseconds(499)));
    }

    [Fact]
    public void Not_found_attempts_share_elapsed_time_including_engine_opens_and_backoff()
    {
        var elapsed = TimeSpan.Zero;
        var attempt = 0;
        while (PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, ++attempt, 10, elapsed))
        {
            elapsed += TimeSpan.FromMilliseconds(100); // fake open duration
            elapsed += PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, attempt);
        }

        Assert.True(elapsed <= PlaybackRetryPolicy.MaximumNotFoundHardElapsed);
        Assert.False(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, 1, 10,
            PlaybackRetryPolicy.MaximumNotFoundElapsed - TimeSpan.FromMilliseconds(499)));
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
    public void Timeout_attempts_are_not_cut_off_by_an_elapsed_time_cap()
    {
        Assert.Equal(TimeSpan.FromMilliseconds(500), PlaybackRetryPolicy.Delay(1));
        Assert.Equal(TimeSpan.FromSeconds(3), PlaybackRetryPolicy.Delay(9));
        Assert.True(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.Timeout, 4, 5, TimeSpan.FromMinutes(10)));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.Timeout, 5, 5, TimeSpan.Zero));
    }

    [Fact]
    public void Five_timeout_attempts_run_before_fallback_and_fallback_has_a_fresh_budget()
    {
        var configuredAttempts = 5;
        var elapsed = TimeSpan.Zero;
        var primaryAttempts = 1;
        while (PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.Timeout, primaryAttempts, configuredAttempts, elapsed))
        {
            elapsed += PlaybackRetryPolicy.Delay(PlaybackAttemptResult.Timeout, primaryAttempts);
            primaryAttempts++;
        }

        Assert.Equal(5, primaryAttempts);
        Assert.Equal(TimeSpan.FromSeconds(6.5), elapsed);

        var fallbackAttempts = 1;
        Assert.True(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.Timeout, fallbackAttempts, configuredAttempts, TimeSpan.Zero));
        Assert.Equal(5, PlaybackRetryPolicy.ClampAttempts(configuredAttempts));
    }

    [Fact]
    public void Five_not_found_attempts_fit_the_separate_not_found_budget_before_fallback()
    {
        var elapsed = TimeSpan.Zero;
        var attempts = 1;
        while (PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.NotFound, attempts, 5, elapsed))
        {
            elapsed += TimeSpan.FromMilliseconds(100);
            elapsed += PlaybackRetryPolicy.Delay(PlaybackAttemptResult.NotFound, attempts);
            attempts++;
        }

        Assert.Equal(5, attempts);
        Assert.True(elapsed < PlaybackRetryPolicy.MaximumNotFoundElapsed);
    }

    [Fact]
    public void Host_not_ready_is_retried_as_a_distinct_pre_open_failure()
    {
        Assert.True(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.HostNotReady, 1, 5, TimeSpan.Zero));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(PlaybackAttemptResult.HostNotReady, 5, 5, TimeSpan.Zero));
        Assert.True(EngineFallbackPolicy.ShouldFallback(PlaybackAttemptResult.HostNotReady, alreadyTried: false));
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
