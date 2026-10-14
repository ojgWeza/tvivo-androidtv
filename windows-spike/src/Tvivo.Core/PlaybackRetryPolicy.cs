namespace Tvivo.Core;

public static class PlaybackRetryPolicy
{
    public const int DefaultAttempts = 5;
    public const int MinimumAttempts = 1;
    public const int MaximumAttempts = 10;
    public static readonly TimeSpan MaximumElapsed = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan MaximumNotFoundElapsed = TimeSpan.FromSeconds(8);

    public static int ClampAttempts(int attempts) => Math.Clamp(attempts, MinimumAttempts, MaximumAttempts);

    public static bool ShouldRetry(PlaybackAttemptResult result, int attemptIndex, int maxAttempts, TimeSpan elapsed)
    {
        if (attemptIndex < 1 || attemptIndex >= ClampAttempts(maxAttempts))
            return false;

        var delay = Delay(result, attemptIndex);
        var maximumElapsed = result == PlaybackAttemptResult.NotFound
            ? MaximumNotFoundElapsed
            : MaximumElapsed;
        return elapsed + delay <= maximumElapsed &&
            result is PlaybackAttemptResult.NotFound
                or PlaybackAttemptResult.NetworkFailure
                or PlaybackAttemptResult.Timeout
                or PlaybackAttemptResult.UnknownFailure;
    }

    public static TimeSpan Delay(PlaybackAttemptResult result, int attemptIndex) =>
        result == PlaybackAttemptResult.NotFound
            ? TimeSpan.FromMilliseconds(Math.Clamp(attemptIndex, 1, 4) * 400)
            : Delay(attemptIndex);

    public static TimeSpan Delay(int attemptIndex) =>
        TimeSpan.FromSeconds(Math.Clamp(attemptIndex, 1, 4));

    public static TimeSpan InterruptionDelay(int attemptIndex) => attemptIndex switch
    {
        <= 1 => TimeSpan.FromMilliseconds(500),
        2 => TimeSpan.FromSeconds(1),
        3 => TimeSpan.FromSeconds(2),
        _ => TimeSpan.FromSeconds(3),
    };
}
