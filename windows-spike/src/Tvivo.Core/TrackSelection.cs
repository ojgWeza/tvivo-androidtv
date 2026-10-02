using System.Collections.ObjectModel;
using System.Globalization;

namespace Tvivo.Core;

public enum TrackKind
{
    Audio,
    Subtitle,
}

public sealed record PlaybackTrack(
    TrackKind Kind,
    string Key,
    string DisplayName,
    string? LanguageCode,
    bool IsSelected);

public sealed record PlaybackTrackSnapshot(
    IReadOnlyList<PlaybackTrack> Audio,
    IReadOnlyList<PlaybackTrack> Subtitles,
    bool IsResolved)
{
    public static PlaybackTrackSnapshot Unresolved { get; } = new(
        Array.Empty<PlaybackTrack>(),
        Array.Empty<PlaybackTrack>(),
        false);
}

public static class TrackSelectionPolicy
{
    private static readonly IReadOnlyDictionary<string, string> LanguageAliases = BuildLanguageAliases();

    public static string? PickSubtitle(
        IReadOnlyList<PlaybackTrack> tracks,
        string? preferredLanguage,
        bool explicitOff,
        string? uiCultureTwoLetterCode)
    {
        if (explicitOff || tracks.Count == 0)
            return null;

        return FindLanguageMatch(tracks, preferredLanguage)?.Key
            ?? FindLanguageMatch(tracks, uiCultureTwoLetterCode)?.Key
            ?? tracks[0].Key;
    }

    public static string? PickAudio(
        IReadOnlyList<PlaybackTrack> tracks,
        string? preferredLanguage,
        string? uiCultureTwoLetterCode)
    {
        if (tracks.Count == 0)
            return null;

        return FindLanguageMatch(tracks, preferredLanguage)?.Key
            ?? FindLanguageMatch(tracks, uiCultureTwoLetterCode)?.Key;
    }

    public static string GetDisplayName(
        TrackKind kind,
        string? sourceLabel,
        string? languageCode,
        int ordinal)
    {
        var label = sourceLabel?.Trim();
        if (!string.IsNullOrEmpty(label) && !LooksLikeAnOpaqueLanguageOrOrdinal(label, languageCode, kind, ordinal))
            return label;

        if (TryGetLanguageName(languageCode, out var languageName))
            return languageName;

        return $"{(kind == TrackKind.Audio ? "Audio" : "Subtitle")} track {ordinal}";
    }

    public static string? NormalizeLanguageCode(string? languageCode)
    {
        if (string.IsNullOrWhiteSpace(languageCode))
            return null;

        var primaryCode = languageCode.Trim().Split('-', '_')[0];
        if (primaryCode.Length == 0)
            return null;

        return LanguageAliases.TryGetValue(primaryCode, out var twoLetterCode)
            ? twoLetterCode
            : primaryCode.ToLowerInvariant();
    }

    private static PlaybackTrack? FindLanguageMatch(
        IReadOnlyList<PlaybackTrack> tracks,
        string? requestedLanguage)
    {
        var requestedCode = NormalizeLanguageCode(requestedLanguage);
        if (requestedCode is null)
            return null;

        var matches = tracks
            .Where(track => string.Equals(NormalizeLanguageCode(track.LanguageCode), requestedCode, StringComparison.Ordinal))
            .ToArray();
        if (matches.Length == 0)
            return null;

        return matches.FirstOrDefault(track => !HasClearlySecondaryLabel(track.DisplayName)) ?? matches[0];
    }

    private static bool TryGetLanguageName(string? languageCode, out string languageName)
    {
        var normalized = NormalizeLanguageCode(languageCode);
        if (normalized is null)
        {
            languageName = string.Empty;
            return false;
        }

        try
        {
            languageName = CultureInfo.GetCultureInfo(normalized).EnglishName;
            return !string.IsNullOrWhiteSpace(languageName);
        }
        catch (CultureNotFoundException)
        {
            languageName = string.Empty;
            return false;
        }
    }

    private static bool LooksLikeAnOpaqueLanguageOrOrdinal(
        string label,
        string? languageCode,
        TrackKind kind,
        int ordinal)
    {
        if (string.Equals(NormalizeLanguageCode(label), NormalizeLanguageCode(languageCode), StringComparison.Ordinal) &&
            NormalizeLanguageCode(label) is not null)
            return true;

        return string.Equals(label, ordinal.ToString(CultureInfo.InvariantCulture), StringComparison.Ordinal) ||
               string.Equals(label, $"Track {ordinal}", StringComparison.OrdinalIgnoreCase) ||
               string.Equals(label, kind == TrackKind.Audio ? "Audio" : "Subtitle", StringComparison.OrdinalIgnoreCase);
    }

    private static bool HasClearlySecondaryLabel(string displayName)
    {
        var words = displayName
            .Split(new[] { ' ', '-', '_', '(', ')', '[', ']', '/', '\\', '.', ':' }, StringSplitOptions.RemoveEmptyEntries);
        return words.Any(word => string.Equals(word, "forced", StringComparison.OrdinalIgnoreCase) ||
                                string.Equals(word, "commentary", StringComparison.OrdinalIgnoreCase) ||
                                string.Equals(word, "sdh", StringComparison.OrdinalIgnoreCase));
    }

    private static IReadOnlyDictionary<string, string> BuildLanguageAliases()
    {
        var aliases = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var culture in CultureInfo.GetCultures(CultureTypes.AllCultures))
        {
            AddAlias(aliases, culture.TwoLetterISOLanguageName, culture.TwoLetterISOLanguageName);
            AddAlias(aliases, culture.ThreeLetterISOLanguageName, culture.TwoLetterISOLanguageName);
            AddAlias(aliases, culture.ThreeLetterWindowsLanguageName, culture.TwoLetterISOLanguageName);
        }

        return new ReadOnlyDictionary<string, string>(aliases);
    }

    private static void AddAlias(IDictionary<string, string> aliases, string alias, string twoLetterCode)
    {
        if (!string.IsNullOrWhiteSpace(alias) &&
            !string.Equals(alias, "iv", StringComparison.OrdinalIgnoreCase))
            aliases.TryAdd(alias, twoLetterCode.ToLowerInvariant());
    }
}
