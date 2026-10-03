namespace Tvivo.Core;

/// <summary>
/// When an episode counts as watched. A percentage of the length would mark a 20-minute episode
/// finished with two minutes still to go, so completion is "the stream ended" or "only the last few
/// seconds are left" (some streams stop just short of reporting an end), never a share of the length.
/// </summary>
public static class EpisodeCompletion
{
    public const long TailMilliseconds = 15_000;
    private const long MinimumDurationForTailMilliseconds = 60_000;

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        ended || durationMilliseconds is { } duration && duration >= MinimumDurationForTailMilliseconds &&
        duration - Math.Clamp(positionMilliseconds, 0, duration) <= TailMilliseconds;
}

/// <summary>
/// A film is finished when the stream ends or only the closing credits are left: the last four minutes,
/// but never more than 5% of the runtime so a short film cannot be marked finished with its ending unseen.
/// </summary>
public static class MovieCompletion
{
    public const long CreditsMilliseconds = 4 * 60_000;
    private const long MinimumDurationMilliseconds = 5 * 60_000;
    private const double MaximumTailShare = 0.05;

    public static long CreditsTailMilliseconds(long durationMilliseconds) => durationMilliseconds <= 0
        ? 0
        : Math.Min(CreditsMilliseconds, (long)(durationMilliseconds * MaximumTailShare));

    public static bool IsFinished(long positionMilliseconds, long? durationMilliseconds, bool ended)
    {
        if (ended) return true;
        if (durationMilliseconds is not { } duration || duration < MinimumDurationMilliseconds) return false;
        var tail = CreditsTailMilliseconds(duration);
        return duration - Math.Clamp(positionMilliseconds, 0, duration) <= tail;
    }
}
