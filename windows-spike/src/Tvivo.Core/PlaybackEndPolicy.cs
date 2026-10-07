namespace Tvivo.Core;

/// <summary>
/// Separates a verified end-of-item signal from an early EOF or an end with no known duration.
/// </summary>
public static class PlaybackEndPolicy
{
    public const long EndToleranceMilliseconds = 30_000;

    public static bool IsGenuineEnd(long positionMilliseconds, long? durationMilliseconds, bool ended) =>
        ended && durationMilliseconds is { } duration && duration > 0 &&
        duration - Math.Clamp(positionMilliseconds, 0, duration) <= EndToleranceMilliseconds;
}
