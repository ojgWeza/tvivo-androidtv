using Tvivo.Core;

namespace Tvivo.Infrastructure;

public static class CatalogHoverTextFormatter
{
    private const int CastLimit = 160;
    private const string CastPrefix = "Cast: ";

    public static string Summary(CatalogItemType type, string? plot) =>
        type == CatalogItemType.Live ? string.Empty : Normalize(plot);

    public static bool HasSummary(CatalogItemType type, string? plot, string? nowText = null, string? nextText = null) =>
        type == CatalogItemType.Live
            ? !string.IsNullOrWhiteSpace(nowText) || !string.IsNullOrWhiteSpace(nextText)
            : !string.IsNullOrEmpty(Normalize(plot));

    public static string Cast(string? cast)
    {
        var normalized = Normalize(cast);
        if (string.IsNullOrEmpty(normalized)) return string.Empty;
        var contentLimit = CastLimit - CastPrefix.Length;
        if (normalized.Length > contentLimit)
            normalized = normalized[..(contentLimit - 1)].TrimEnd() + "…";
        return CastPrefix + normalized;
    }

    public static string Normalize(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return string.Empty;
        return string.Join(' ', text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
    }
}
