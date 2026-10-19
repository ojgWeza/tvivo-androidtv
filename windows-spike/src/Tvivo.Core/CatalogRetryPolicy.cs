namespace Tvivo.Core;

public static class CatalogRetryPolicy
{
    public const int MaxAttempts = 3;
    public static TimeSpan Delay(int attempt, DateTimeOffset now, DateTimeOffset? retryAfter, double jitter)
    {
        var fallback = TimeSpan.FromSeconds(Math.Pow(2, attempt) + Math.Clamp(jitter, 0, 1));
        var requested = retryAfter is { } date ? date - now : fallback;
        return TimeSpan.FromMilliseconds(Math.Clamp(requested.TotalMilliseconds, 300, 20_000));
    }
}
