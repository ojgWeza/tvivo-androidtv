using Tvivo.Core;

namespace Tvivo.Infrastructure;

public static class CatalogHoverTextFormatter
{
    private const int CastLimit = 160;
    private const string CastPrefix = "Cast: ";

    public static string Summary(CatalogItemType type, string? plot, bool loading, bool failed,
        string? nowText = null, string? nextText = null)
    {
        if (type == CatalogItemType.Live)
            return string.IsNullOrWhiteSpace(nowText) && string.IsNullOrWhiteSpace(nextText)
                ? "No programme summary available"
                : string.Empty;

        var normalizedPlot = Normalize(plot);
        if (!string.IsNullOrEmpty(normalizedPlot)) return normalizedPlot;
        if (loading) return "Loading summary…";
        if (failed) return "Summary unavailable";
        return "No summary available";
    }

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
