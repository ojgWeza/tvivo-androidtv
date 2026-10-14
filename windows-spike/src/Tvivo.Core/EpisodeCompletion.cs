namespace Tvivo.Core;

internal static class CompletionThresholds
{
    public const long CreditsMilliseconds = 4 * 60_000;
    public const long MinimumCreditsDurationMilliseconds = 5 * 60_000;
    public const long ShortTailMilliseconds = 15_000;
    public const long MinimumShortTailDurationMilliseconds = 60_000;
    private const double MaximumCreditsTailShare = 0.05;

    public static long CreditsTailMilliseconds(long durationMilliseconds) => durationMilliseconds <= 0
        ? 0
        : Math.Min(CreditsMilliseconds, (long)(durationMilliseconds * MaximumCreditsTailShare));

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended, bool allowShortTail)
    {
        if (ended) return PlaybackEndPolicy.IsGenuineEnd(positionMilliseconds, durationMilliseconds, ended);
        if (durationMilliseconds is not { } duration) return false;

        if (duration >= MinimumCreditsDurationMilliseconds)
            return duration - Math.Clamp(positionMilliseconds, 0, duration) <= CreditsTailMilliseconds(duration);

        return allowShortTail && duration >= MinimumShortTailDurationMilliseconds &&
               duration - Math.Clamp(positionMilliseconds, 0, duration) <= ShortTailMilliseconds;
    }
}

/// <summary>Episodes use the movie credits window, with a short tail for very short episodes.</summary>
public static class EpisodeCompletion
{
    public const long TailMilliseconds = CompletionThresholds.ShortTailMilliseconds;

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        CompletionThresholds.IsFinished(positionMilliseconds, durationMilliseconds, ended, allowShortTail: true);
}

/// <summary>
/// A film is finished when the stream ends or only the closing credits are left: the last four minutes,
/// but never more than 5% of the runtime so a short film cannot be marked finished with its ending unseen.
/// </summary>
public static class MovieCompletion
{
    public const long CreditsMilliseconds = CompletionThresholds.CreditsMilliseconds;

    public static long CreditsTailMilliseconds(long durationMilliseconds) =>
        CompletionThresholds.CreditsTailMilliseconds(durationMilliseconds);

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        CompletionThresholds.IsFinished(positionMilliseconds, durationMilliseconds, ended, allowShortTail: false);
}
