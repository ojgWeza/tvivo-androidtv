using System.Globalization;

namespace Tvivo.Infrastructure;

public static class CatalogRatingFormatter
{
    public static string? Format(string? rating)
    {
        if (string.IsNullOrWhiteSpace(rating) ||
            !double.TryParse(rating, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ||
            !double.IsFinite(value) || value <= 0)
            return null;

        return rating.Trim();
    }
}
