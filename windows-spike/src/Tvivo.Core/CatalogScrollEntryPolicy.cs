namespace Tvivo.Core;

public enum CatalogEntryIntent { Restore, TabSwitch, StartAtTop, ReturnFromPlayer, Refresh }

public static class CatalogScrollEntryPolicy
{
    public static bool ShouldRestore(CatalogEntryIntent intent) =>
        intent is CatalogEntryIntent.Restore or CatalogEntryIntent.TabSwitch or CatalogEntryIntent.ReturnFromPlayer or CatalogEntryIntent.Refresh;

    public static bool ShouldResetToTop(CatalogEntryIntent intent) => intent == CatalogEntryIntent.StartAtTop;
}

public static class SeriesArtworkPolicy
{
    public static string? Select(params string?[] candidates) =>
        candidates.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))?.Trim();

    public static string? SelectArray(IEnumerable<string?> candidates) => Select(candidates.ToArray());

    public static string? Resolve(string? value, Uri providerBase)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var candidate = value.Trim();
        if (Uri.TryCreate(candidate, UriKind.Absolute, out var absolute))
            return absolute.Scheme is "http" or "https" ? absolute.ToString() : null;
        if (candidate.StartsWith("//", StringComparison.Ordinal) || candidate.StartsWith('\\')) return null;
        if (!Uri.TryCreate(providerBase, candidate, out var resolved) || resolved.Scheme is not ("http" or "https")) return null;
        return resolved.ToString();
    }

    public static Uri? InheritEpisode(Uri? episodeArtwork, Uri? seriesArtwork) => episodeArtwork ?? seriesArtwork;
}
