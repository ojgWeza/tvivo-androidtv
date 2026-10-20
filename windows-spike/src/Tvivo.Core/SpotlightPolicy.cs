using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Tvivo.Core;

public enum SpotlightSourceMode { RecentlyAdded, SpecificFolders, UnexploredFolders }
public enum SpotlightPageKind { Movies, Series, ForYou }

public sealed record SpotlightContentSettings(SpotlightSourceMode SourceMode,
    IReadOnlyList<string>? SelectedCategoryIds = null);

public sealed record SpotlightSettings(SpotlightContentSettings Movies, SpotlightContentSettings Series)
{
    public static SpotlightSettings Default { get; } = new(
        new(SpotlightSourceMode.RecentlyAdded), new(SpotlightSourceMode.RecentlyAdded));
}

public sealed record SpotlightFolderOption(string Id, string Name, int ItemCount);

public static class SpotlightFolderList
{
    public static IReadOnlyList<SpotlightFolderOption> Filter(IEnumerable<SpotlightFolderOption> folders, string? query) =>
        string.IsNullOrWhiteSpace(query)
            ? folders.ToArray()
            : folders.Where(folder => folder.Name.Contains(query.Trim(), StringComparison.CurrentCultureIgnoreCase)).ToArray();

    public static IReadOnlyList<string> FindMissing(IEnumerable<string>? selectedIds, IEnumerable<SpotlightFolderOption> folders)
    {
        var available = folders.Select(folder => folder.Id).ToHashSet(StringComparer.Ordinal);
        return (selectedIds ?? Array.Empty<string>()).Where(id => !available.Contains(id))
            .Distinct(StringComparer.Ordinal).ToArray();
    }
}

public sealed record SpotlightCandidate(CatalogItemType Type, string ItemId, string CategoryId,
    string NormalizedTitle, string? Year, DateTimeOffset? AddedAt)
{
    public static SpotlightCandidate FromChannel(Channel channel) => new(
        channel.Source.Kind switch
        {
            StreamKind.Movie => CatalogItemType.Movie,
            StreamKind.Series => CatalogItemType.Series,
            _ => CatalogItemType.Live,
        },
        channel.Id, channel.GroupId ?? string.Empty, SpotlightSelector.Normalize(channel.DisplayName),
        channel.Metadata.TryGetValue("year", out var year) ? year : null, channel.AddedAt);
}

public enum SpotlightEmptyReason { NoEligibleCandidates, NoSelectedFolderCandidates }
public sealed record SpotlightSelectionResult(IReadOnlyList<SpotlightCandidate> Items,
    SpotlightEmptyReason? EmptyReason = null);

/// <summary>Deterministic, bounded daily Spotlight selection. Candidate inputs must already be bounded source pools.</summary>
public static class SpotlightSelector
{
    public const int SelectionSize = 12;
    public const int CandidatePoolLimit = 200;

    public static SpotlightSelectionResult Select(SpotlightPageKind page, string opaqueAccountKey,
        DateOnly utcDate, SpotlightSettings settings,
        IReadOnlyList<SpotlightCandidate> movieCandidates,
        IReadOnlyList<SpotlightCandidate> seriesCandidates,
        IReadOnlyList<SpotlightCandidate>? previousSelection = null)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(movieCandidates);
        ArgumentNullException.ThrowIfNull(seriesCandidates);
        var movieConfig = settings.Movies ?? SpotlightSettings.Default.Movies;
        var seriesConfig = settings.Series ?? SpotlightSettings.Default.Series;
        if (page == SpotlightPageKind.Movies)
            return SelectSingle(CatalogItemType.Movie, movieConfig, movieCandidates, opaqueAccountKey, page, utcDate, previousSelection);
        if (page == SpotlightPageKind.Series)
            return SelectSingle(CatalogItemType.Series, seriesConfig, seriesCandidates, opaqueAccountKey, page, utcDate, previousSelection);

        var movie = Prepare(movieCandidates, CatalogItemType.Movie, movieConfig, CandidatePoolLimit);
        var series = Prepare(seriesCandidates, CatalogItemType.Series, seriesConfig, CandidatePoolLimit);
        var target = Math.Min(SelectionSize, movie.Count + series.Count);
        var movieSlots = Math.Min(SelectionSize / 2, movie.Count);
        var seriesSlots = Math.Min(SelectionSize / 2, series.Count);
        var remaining = target - movieSlots - seriesSlots;
        if (remaining > 0)
        {
            var movieExtra = Math.Min(remaining, movie.Count - movieSlots);
            movieSlots += movieExtra;
            remaining -= movieExtra;
            seriesSlots += Math.Min(remaining, series.Count - seriesSlots);
        }
        movie = Preserve(Rank(movie, opaqueAccountKey, page, utcDate, movieConfig, Math.Min(movie.Count, SelectionSize)),
            previousSelection, CatalogItemType.Movie, movieSlots, movie);
        series = Preserve(Rank(series, opaqueAccountKey, page, utcDate, seriesConfig, Math.Min(series.Count, SelectionSize)),
            previousSelection, CatalogItemType.Series, seriesSlots, series);
        var mixed = new List<SpotlightCandidate>(target);
        for (var index = 0; mixed.Count < target; index++)
        {
            if (index < movie.Count) mixed.Add(movie[index]);
            if (mixed.Count < target && index < series.Count) mixed.Add(series[index]);
        }
        return new(mixed, mixed.Count == 0 ? EmptyReason(movieConfig, seriesConfig) : null);
    }

    private static SpotlightSelectionResult SelectSingle(CatalogItemType type, SpotlightContentSettings config,
        IReadOnlyList<SpotlightCandidate> input, string account, SpotlightPageKind page, DateOnly date,
        IReadOnlyList<SpotlightCandidate>? previous)
    {
        var prepared = Prepare(input, type, config, CandidatePoolLimit);
        if (prepared.Count == 0) return new(Array.Empty<SpotlightCandidate>(), EmptyReason(config));
        var ranked = Rank(prepared, account, page, date, config, Math.Min(prepared.Count, SelectionSize));
        var stable = Preserve(ranked, previous, type, SelectionSize, prepared);
        return new(stable, null);
    }

    private static List<SpotlightCandidate> Prepare(IEnumerable<SpotlightCandidate> input, CatalogItemType type,
        SpotlightContentSettings config, int limit)
    {
        var selected = (config.SelectedCategoryIds ?? Array.Empty<string>()).Where(id => !string.IsNullOrWhiteSpace(id))
            .ToHashSet(StringComparer.Ordinal);
        var source = input.Where(item => item.Type == type && !string.IsNullOrWhiteSpace(item.ItemId) &&
            !string.IsNullOrWhiteSpace(item.NormalizedTitle));
        if (config.SourceMode == SpotlightSourceMode.SpecificFolders)
        {
            if (selected.Count == 0) return new();
            var byFolder = selected.ToDictionary(id => id, _ => new List<SpotlightCandidate>(), StringComparer.Ordinal);
            foreach (var item in source)
                if (byFolder.TryGetValue(item.CategoryId, out var folder) && folder.Count < CandidatePoolLimit)
                    folder.Add(item);
            var folders = byFolder.OrderBy(pair => pair.Key, StringComparer.Ordinal)
                .Select(pair => pair.Value).ToArray();
            var output = new List<SpotlightCandidate>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            for (var index = 0; output.Count < limit; index++)
            {
                var added = false;
                foreach (var folder in folders)
                    if (index < folder.Count && output.Count < limit)
                    {
                        var candidate = folder[index];
                        if (seen.Add(DuplicateKey(candidate))) output.Add(candidate);
                        added = true;
                    }
                if (!added) break;
            }
            return output;
        }
        return Deduplicate(source.Take(CandidatePoolLimit)).Take(limit).ToList();
    }

    private static List<SpotlightCandidate> Deduplicate(IEnumerable<SpotlightCandidate> input) => input
        .GroupBy(item => DuplicateKey(item), StringComparer.Ordinal).Select(group => group.First()).ToList();

    private static string DuplicateKey(SpotlightCandidate item) =>
        Normalize(item.NormalizedTitle) + "|" + (string.IsNullOrWhiteSpace(item.Year) ? "?" : item.Year.Trim());

    public static string Normalize(string title) => string.Join(' ', title.Trim().Normalize(NormalizationForm.FormKC)
        .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)).ToUpperInvariant();

    private static List<SpotlightCandidate> Rank(IReadOnlyList<SpotlightCandidate> candidates, string account,
        SpotlightPageKind page, DateOnly date, SpotlightContentSettings config, int count)
    {
        if (candidates.Count <= count) return candidates.ToList();
        var seed = Seed(account, page, date, config);
        var remaining = candidates.ToList();
        var result = new List<SpotlightCandidate>(count);
        var random = new DeterministicRandom(seed);
        var newest = remaining.Where(item => item.AddedAt.HasValue).Select(item => item.AddedAt!.Value).DefaultIfEmpty(DateTimeOffset.MinValue).Max();
        while (result.Count < count && remaining.Count > 0)
        {
            var weights = remaining.Select(item => item.AddedAt is null || newest == DateTimeOffset.MinValue
                ? 0.25 : 1.0 / (1.0 + Math.Max(0, (newest - item.AddedAt.Value).TotalDays) / 30.0)).ToArray();
            var total = weights.Sum();
            var pick = random.NextDouble() * total;
            var index = 0;
            for (; index < weights.Length - 1 && pick >= weights[index]; index++) pick -= weights[index];
            result.Add(remaining[index]);
            remaining.RemoveAt(index);
        }
        return result;
    }

    private static List<SpotlightCandidate> Preserve(IReadOnlyList<SpotlightCandidate> ranked,
        IReadOnlyList<SpotlightCandidate>? previous, CatalogItemType type, int limit,
        IReadOnlyList<SpotlightCandidate> availableCandidates)
    {
        if (previous is null || previous.Count == 0) return ranked.Take(limit).ToList();
        var available = availableCandidates.Where(item => item.Type == type).GroupBy(item => item.ItemId, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        var output = new List<SpotlightCandidate>(limit);
        foreach (var old in previous.Where(item => item.Type == type))
            if (output.Count < limit && available.Remove(old.ItemId, out var stillPresent)) output.Add(stillPresent);
        foreach (var item in ranked)
            if (output.Count < limit && available.Remove(item.ItemId, out var added)) output.Add(added);
        return output;
    }

    private static SpotlightEmptyReason EmptyReason(SpotlightContentSettings config) =>
        config.SourceMode == SpotlightSourceMode.SpecificFolders
            ? SpotlightEmptyReason.NoSelectedFolderCandidates : SpotlightEmptyReason.NoEligibleCandidates;
    private static SpotlightEmptyReason EmptyReason(SpotlightContentSettings a, SpotlightContentSettings b) =>
        a.SourceMode == SpotlightSourceMode.SpecificFolders || b.SourceMode == SpotlightSourceMode.SpecificFolders
            ? SpotlightEmptyReason.NoSelectedFolderCandidates : SpotlightEmptyReason.NoEligibleCandidates;

    public static string SelectedFolderFingerprint(SpotlightSettings settings) => Hash(string.Join("\n",
        "M:" + Fingerprint(settings.Movies), "S:" + Fingerprint(settings.Series)));

    private static string Fingerprint(SpotlightContentSettings settings) =>
        settings.SourceMode + ":" + string.Join(",", (settings.SelectedCategoryIds ?? Array.Empty<string>())
            .Distinct(StringComparer.Ordinal).OrderBy(id => id, StringComparer.Ordinal));
    private static ulong Seed(string account, SpotlightPageKind page, DateOnly date, SpotlightContentSettings settings) =>
        Convert.ToUInt64(Hash(account + "\n" + page + "\n" + date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) +
            "\n" + Fingerprint(settings)).Substring(0, 16), 16);
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    private struct DeterministicRandom(ulong state)
    {
        private ulong _state = state;
        public double NextDouble()
        {
            _state += 0x9E3779B97F4A7C15UL;
            var value = _state;
            value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9UL;
            value = (value ^ (value >> 27)) * 0x94D049BB133111EBUL;
            value ^= value >> 31;
            return (value >> 11) * (1.0 / (1UL << 53));
        }
    }
}
