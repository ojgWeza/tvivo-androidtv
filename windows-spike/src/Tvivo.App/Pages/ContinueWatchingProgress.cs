using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

internal sealed record ContinueWatchingProgress(bool HasMeasuredProgress, double Percent, int? MinutesLeft)
{
    public string TimeLeft => MinutesLeft is { } minutes ? $"{FormatDuration(minutes)} left" : string.Empty;

    internal static string FormatDuration(int minutes) => minutes switch
    {
        < 60 => $"{minutes} min",
        _ when minutes % 60 == 0 => $"{minutes / 60}h",
        _ => $"{minutes / 60}h {minutes % 60} min",
    };

    public static ContinueWatchingProgress For(Channel channel, PlaybackProgress? progress)
    {
        var durationMs = progress?.DurationMs;
        var hasMeasuredProgress = (channel.Source.Kind is StreamKind.Movie or StreamKind.Series) && durationMs is > 0;
        var percent = hasMeasuredProgress ? Math.Clamp(progress!.ResumeMs * 100d / durationMs!.Value, 0, 100) : 0;
        var minutesLeft = hasMeasuredProgress && durationMs!.Value > progress!.ResumeMs
            ? (int?)Math.Ceiling((durationMs.Value - progress.ResumeMs) / 60_000d)
            : null;
        return new(hasMeasuredProgress, percent, minutesLeft);
    }
}
