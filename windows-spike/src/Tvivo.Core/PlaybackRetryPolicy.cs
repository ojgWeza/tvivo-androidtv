namespace Tvivo.Core;

public static class PlaybackRetryPolicy
{
    public const int DefaultAttempts = 5;
    public const int MinimumAttempts = 1;
    public const int MaximumAttempts = 10;
    public static readonly TimeSpan MaximumNotFoundElapsed = TimeSpan.FromSeconds(8);
    public static readonly TimeSpan MaximumNotFoundHardElapsed = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan MinimumNotFoundOpenWindow = TimeSpan.FromSeconds(1);

    public static TimeSpan AttemptTimeout(StreamKind kind) => kind is StreamKind.Movie or StreamKind.Episode
        ? TimeSpan.FromSeconds(25)
        : TimeSpan.FromSeconds(10);

    public static IReadOnlyList<TimeSpan> BackoffDelays(int maxAttempts)
    {
        var attempts = ClampAttempts(maxAttempts);
        return Enumerable.Range(1, attempts - 1).Select(Delay).ToArray();
    }

    public static int ClampAttempts(int attempts) => Math.Clamp(attempts, MinimumAttempts, MaximumAttempts);

    public static bool ShouldRetry(PlaybackAttemptResult result, int attemptIndex, int maxAttempts, TimeSpan elapsed)
    {
        if (attemptIndex < 1 || attemptIndex >= ClampAttempts(maxAttempts))
            return false;

        var retryable = result is PlaybackAttemptResult.NotFound
            or PlaybackAttemptResult.NetworkFailure
            or PlaybackAttemptResult.Timeout
            or PlaybackAttemptResult.HostNotReady
            or PlaybackAttemptResult.UnknownFailure;
        if (!retryable)
            return false;

        if (result != PlaybackAttemptResult.NotFound)
            return true;

        var delay = Delay(result, attemptIndex);
        return elapsed + delay + MinimumNotFoundOpenWindow <= MaximumNotFoundElapsed;
    }

    public static TimeSpan Delay(PlaybackAttemptResult result, int attemptIndex) =>
        Delay(attemptIndex);

    public static TimeSpan Delay(int attemptIndex) =>
        InterruptionDelay(attemptIndex);

    public static TimeSpan InterruptionDelay(int attemptIndex) => attemptIndex switch
    {
        <= 1 => TimeSpan.FromMilliseconds(500),
        2 => TimeSpan.FromSeconds(1),
        3 => TimeSpan.FromSeconds(2),
        _ => TimeSpan.FromSeconds(3),
    };
}
