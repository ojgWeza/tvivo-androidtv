namespace Tvivo.Core;

internal static class CompletionThresholds
{
    public const long MinimumTailDurationMilliseconds = 5 * 60_000;
    public const long ShortTailMilliseconds = 15_000;
    public const long MinimumShortTailDurationMilliseconds = 60_000;
    public static long TailMilliseconds(long durationMilliseconds, long maximumTailMilliseconds, double tailShare) => durationMilliseconds <= 0
        ? 0
        : Math.Min(maximumTailMilliseconds, (long)(durationMilliseconds * tailShare));

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended, long maximumTailMilliseconds, double tailShare, bool allowShortTail)
    {
        if (ended) return PlaybackEndPolicy.IsGenuineEnd(positionMilliseconds, durationMilliseconds, ended);
        if (durationMilliseconds is not { } duration) return false;

        if (duration >= MinimumTailDurationMilliseconds)
            return duration - Math.Clamp(positionMilliseconds, 0, duration) <= TailMilliseconds(duration, maximumTailMilliseconds, tailShare);

        return allowShortTail && duration >= MinimumShortTailDurationMilliseconds &&
               duration - Math.Clamp(positionMilliseconds, 0, duration) <= ShortTailMilliseconds;
    }
}

/// <summary>Episodes use a four-minute or eight-percent tail, with a short tail for very short episodes.</summary>
public static class EpisodeCompletion
{
    public const long TailMilliseconds = CompletionThresholds.ShortTailMilliseconds;
    public const long MaximumTailMilliseconds = 4 * 60_000;
    public const double TailShare = 0.08;

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        CompletionThresholds.IsFinished(positionMilliseconds, durationMilliseconds, ended, MaximumTailMilliseconds, TailShare, allowShortTail: true);
}

/// <summary>
/// A film is finished when the stream ends or only the closing credits are left: the last ten minutes,
/// but never more than 10% of the runtime.
/// </summary>
public static class MovieCompletion
{
    public const long CreditsMilliseconds = MaximumTailMilliseconds;
    public const long MaximumTailMilliseconds = 10 * 60_000;
    public const double TailShare = 0.10;
    public const long MinimumTailDurationMilliseconds = 5 * 60_000;

    public static long CreditsTailMilliseconds(long durationMilliseconds) =>
        CompletionThresholds.TailMilliseconds(durationMilliseconds, MaximumTailMilliseconds, TailShare);

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        CompletionThresholds.IsFinished(positionMilliseconds, durationMilliseconds, ended, MaximumTailMilliseconds, TailShare, allowShortTail: false);
}
