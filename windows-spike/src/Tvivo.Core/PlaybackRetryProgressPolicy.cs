namespace Tvivo.Core;

public readonly record struct PlaybackRetryProgress(double Fraction, string Status, bool IsVisible);

/// <summary>Describes the quiet progress shown while normal playback attempts are retried.</summary>
public static class PlaybackRetryProgressPolicy
{
    public static readonly TimeSpan RevealDelay = TimeSpan.FromMilliseconds(1500);

    public static PlaybackRetryProgress GetProgress(
        int attemptNumber,
        int maxAttempts,
        TimeSpan attemptTimeout,
        IReadOnlyList<TimeSpan> backoffDelays,
        TimeSpan elapsed,
        StreamKind kind)
    {
        ArgumentNullException.ThrowIfNull(backoffDelays);
        var attempts = PlaybackRetryPolicy.ClampAttempts(maxAttempts);
        var attempt = Math.Clamp(attemptNumber, 1, attempts);
        var timeout = attemptTimeout > TimeSpan.Zero ? attemptTimeout : TimeSpan.Zero;
        var totalTicks = timeout.Ticks * (double)attempts + backoffDelays.Sum(delay => Math.Max(0, delay.Ticks));
        var clampedElapsed = Math.Max(0, elapsed.Ticks);
        var fraction = totalTicks <= 0 ? 1d : Math.Clamp(clampedElapsed / totalTicks, 0d, 1d);
        var live = kind == StreamKind.Live;
        var status = attempt switch
        {
            1 when clampedElapsed < timeout.Ticks * 0.4d => live
                ? "Tuning in..."
                : kind == StreamKind.Episode ? "Opening your episode..." : "Opening your movie...",
            1 => live ? "Still tuning in, this can take a moment..." : "Still connecting, this can take a moment...",
            _ when attempt == attempts => "Last try...",
            _ when attempt == attempts - 1 => "Almost there, checking again...",
            _ => "Trying again...",
        };

        return new PlaybackRetryProgress(fraction, status, elapsed >= RevealDelay);
    }
}
