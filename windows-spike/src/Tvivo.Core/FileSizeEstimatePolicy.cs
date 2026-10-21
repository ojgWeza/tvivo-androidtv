using System.Globalization;

namespace Tvivo.Core;

public static class FileSizeEstimatePolicy
{
    public static long? Estimate(string? bitrateKbps, string? duration, string? durationSeconds = null)
    {
        if (!long.TryParse(bitrateKbps, NumberStyles.Integer, CultureInfo.InvariantCulture, out var rate) ||
            rate is < 128 or > 80_000) return null;
        var seconds = ParseDuration(duration) ?? ParseDuration(durationSeconds);
        if (seconds is null or < 60 or > 28_800) return null;
        try
        {
            var bytes = checked((rate * 1000L * seconds.Value + 4) / 8);
            return bytes is >= FileSizeProbePolicy.MinimumBytes and <= FileSizeProbePolicy.MaximumBytes ? bytes : null;
        }
        catch (OverflowException) { return null; }
    }

    public static long? ParseDuration(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        if (long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var numeric))
            return numeric is >= 60 and <= 28_800 ? numeric : null;
        if (!TimeSpan.TryParseExact(value, @"h\:mm\:ss", CultureInfo.InvariantCulture, out var parsed) &&
            !TimeSpan.TryParseExact(value, @"hh\:mm\:ss", CultureInfo.InvariantCulture, out parsed)) return null;
        return parsed.TotalSeconds is >= 60 and <= 28_800 ? (long)parsed.TotalSeconds : null;
    }
}
