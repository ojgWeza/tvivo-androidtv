namespace Tvivo.Core;

/// <summary>How early to offer the next playlist item for a measured stream duration.</summary>
public static class NextPrompt
{
    public const long EpisodeLeadMilliseconds = 45_000;
    public const long EpisodeMaximumDurationShare = 4;
    public const long MinimumMovieLeadMilliseconds = 30_000;

    public static long LeadMilliseconds(StreamKind kind, long durationMilliseconds)
    {
        if (durationMilliseconds <= 0) return 0;

        return kind switch
        {
            StreamKind.Episode => Math.Min(EpisodeLeadMilliseconds, durationMilliseconds / EpisodeMaximumDurationShare),
            StreamKind.Movie => Math.Max(MinimumMovieLeadMilliseconds, MovieCompletion.CreditsTailMilliseconds(durationMilliseconds)),
            _ => 0,
        };
    }
}
