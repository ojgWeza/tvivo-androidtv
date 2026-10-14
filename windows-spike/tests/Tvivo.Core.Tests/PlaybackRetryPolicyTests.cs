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

    [Theory]
    [InlineData(PlaybackAttemptResult.NotFound)]
    [InlineData(PlaybackAttemptResult.Forbidden)]
    [InlineData(PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(PlaybackAttemptResult.DecodeFailure)]
    [InlineData(PlaybackAttemptResult.HostFailure)]
    [InlineData(PlaybackAttemptResult.Cancelled)]
    public void Non_transient_failures_do_not_retry(PlaybackAttemptResult result) =>
        Assert.False(PlaybackRetryPolicy.ShouldRetry(result, 1, 5, TimeSpan.Zero));

    [Fact]
    public void Retry_delay_and_elapsed_time_respect_the_overall_cap()
    {
        Assert.Equal(TimeSpan.FromSeconds(1), PlaybackRetryPolicy.Delay(1));
        Assert.Equal(TimeSpan.FromSeconds(4), PlaybackRetryPolicy.Delay(9));
        Assert.False(PlaybackRetryPolicy.ShouldRetry(
            PlaybackAttemptResult.Timeout, 1, 5, PlaybackRetryPolicy.MaximumElapsed - TimeSpan.FromMilliseconds(500)));
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
