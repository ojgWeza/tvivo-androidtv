using System.Globalization;
using System.Text.RegularExpressions;

namespace Tvivo.Core;

public static class FileSizeFormatter
{
    private static readonly Regex Size = new(@"^\s*(\d+(?:\.\d+)?)\s*(B|KB|MB|GB)?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static long? ParseBytes(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var match = Size.Match(value);
        if (!match.Success || !decimal.TryParse(match.Groups[1].Value, NumberStyles.AllowDecimalPoint,
                CultureInfo.InvariantCulture, out var amount) || amount <= 0) return null;
        var multiplier = match.Groups[2].Value.ToUpperInvariant() switch
        {
            "KB" => 1_000m, "MB" => 1_000_000m, "GB" => 1_000_000_000m, _ => 1m,
        };
        if (amount > long.MaxValue / multiplier) return null;
        return (long)(amount * multiplier);
    }

    public static string? Format(long? bytes)
    {
        if (bytes is null or <= 0) return null;
        return bytes >= 1_000_000_000
            ? (bytes.Value / 1_000_000_000d).ToString("0.0", CultureInfo.InvariantCulture) + " GB"
            : Math.Max(1, (int)Math.Round(bytes.Value / 1_000_000d, MidpointRounding.AwayFromZero))
                .ToString(CultureInfo.InvariantCulture) + " MB";
    }
}
