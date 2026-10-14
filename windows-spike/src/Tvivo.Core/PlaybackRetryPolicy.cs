namespace Tvivo.Core;

public static class PlaybackRetryPolicy
{
    public const int DefaultAttempts = 5;
    public const int MinimumAttempts = 1;
    public const int MaximumAttempts = 10;
    public static readonly TimeSpan MaximumElapsed = TimeSpan.FromSeconds(30);

    public static int ClampAttempts(int attempts) => Math.Clamp(attempts, MinimumAttempts, MaximumAttempts);

    public static bool ShouldRetry(PlaybackAttemptResult result, int attemptIndex, int maxAttempts, TimeSpan elapsed) =>
        attemptIndex >= 1 &&
        attemptIndex < ClampAttempts(maxAttempts) &&
        elapsed + Delay(attemptIndex) <= MaximumElapsed &&
        result is PlaybackAttemptResult.NetworkFailure
            or PlaybackAttemptResult.Timeout
            or PlaybackAttemptResult.UnknownFailure;

    public static TimeSpan Delay(int attemptIndex) =>
        TimeSpan.FromSeconds(Math.Clamp(attemptIndex, 1, 4));
}
