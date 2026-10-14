using System.Globalization;

namespace Tvivo.Infrastructure;

public static class CatalogRatingFormatter
{
    /// <summary>One decimal at most, no trailing ".0" (7.373 -> "7.4", 4.0 -> "4"); null when there is no real rating.</summary>
    public static string? Format(string? rating)
    {
        if (string.IsNullOrWhiteSpace(rating) ||
            !double.TryParse(rating, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ||
            !double.IsFinite(value) || value <= 0)
            return null;

        var rounded = Math.Round(value, 1, MidpointRounding.AwayFromZero);
        return rounded <= 0 ? null : rounded.ToString("0.#", CultureInfo.InvariantCulture);
    }
}
