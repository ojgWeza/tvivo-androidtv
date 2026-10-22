using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App;

/// <summary>App-facing access to account-scoped Spotlight preferences and local previews.</summary>
public sealed class SpotlightSettingsService
{
    private long _revision;
    public long Revision => Interlocked.Read(ref _revision);
    private readonly SpotlightSettingsStore _settingsStore;
    private readonly SpotlightDailySelectionStore _dailyStore;
    private readonly SqliteCatalogRepository _catalog;

    public SpotlightSettingsService(SqliteCatalogRepository catalog)
    {
        _catalog = catalog;
        _settingsStore = new SpotlightSettingsStore();
        _dailyStore = new SpotlightDailySelectionStore();
    }

    public SpotlightSettings ReadEffectiveSettings(string opaqueAccountKey) => _settingsStore.Read(opaqueAccountKey);

    public SpotlightSelectionResult Select(ProviderAccount account, SpotlightPageKind page,
        DateOnly utcDate, IReadOnlyList<Channel>? moviePool = null,
        IReadOnlyList<Channel>? seriesPool = null)
    {
        var settings = ReadEffectiveSettings(account.AccountId);
        var movieChannels = page == SpotlightPageKind.Series ? Array.Empty<Channel>() : moviePool ?? GetChannels(account, page, CatalogItemType.Movie, settings);
        var seriesChannels = page == SpotlightPageKind.Movies ? Array.Empty<Channel>() : seriesPool ?? GetChannels(account, page, CatalogItemType.Series, settings);
        var candidates = movieChannels.Concat(seriesChannels).Select(SpotlightCandidate.FromChannel).ToArray();
        var pageSource = page switch
        {
            SpotlightPageKind.Movies => settings.ForPage(page, CatalogItemType.Movie).SourceMode,
            SpotlightPageKind.Series => settings.ForPage(page, CatalogItemType.Series).SourceMode,
            _ => settings.ForPage(page, CatalogItemType.Movie).SourceMode,
        };
        var sourceLabel = page == SpotlightPageKind.ForYou
            ? $"Movies:{settings.ForPage(page, CatalogItemType.Movie).SourceMode},Series:{settings.ForPage(page, CatalogItemType.Series).SourceMode}"
            : pageSource.ToString();
        var fingerprint = SpotlightSelector.SelectedFolderFingerprint(settings);
        var bucketDate = SpotlightSelector.BucketStart(utcDate, settings.RotationDays);
        var previous = _dailyStore.Read(account.AccountId, page, bucketDate, pageSource, fingerprint)?.Items
            .Select(item => candidates.FirstOrDefault(candidate => candidate.Type == item.Type && candidate.ItemId == item.ItemId))
            .Where(candidate => candidate is not null).Cast<SpotlightCandidate>().ToArray();
        var selected = SpotlightSelector.Select(page, account.AccountId, utcDate, settings,
            movieChannels.Select(SpotlightCandidate.FromChannel).ToArray(), seriesChannels.Select(SpotlightCandidate.FromChannel).ToArray(), previous);
        _dailyStore.Write(new SpotlightDailySelection(account.AccountId, page, bucketDate, pageSource, fingerprint,
            selected.Items.Select(item => new SpotlightDailyItem(item.Type, item.ItemId)).ToArray()));
        var dayHash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(utcDate.ToString("yyyy-MM-dd"))))[..12];
        LaunchDiagnostics.Write($"event=spotlight.selection page={page} source={sourceLabel} count={selected.Items.Count} dayHash={dayHash}");
        return selected;
    }

    public static void LogSaved(SpotlightSettings settings) => LaunchDiagnostics.Write(
        $"event=spotlight.settings.saved moviesMode={settings.Movies.SourceMode} moviesFolders={settings.Movies.SelectedCategoryIds?.Count ?? 0} " +
        $"seriesMode={settings.Series.SourceMode} seriesFolders={settings.Series.SelectedCategoryIds?.Count ?? 0}");

    public void Save(string opaqueAccountKey, SpotlightSettings settings)
    {
        _settingsStore.Write(opaqueAccountKey, settings);
        _dailyStore.Invalidate(opaqueAccountKey);
        Interlocked.Increment(ref _revision);
    }

    public IReadOnlyList<string> PreviewTitles(ProviderAccount account, CatalogItemType type, SpotlightSettings settings)
        => PreviewTitles(account, type == CatalogItemType.Movie ? SpotlightPageKind.Movies : SpotlightPageKind.Series, type, settings);

    public IReadOnlyList<string> PreviewTitles(ProviderAccount account, SpotlightPageKind page, CatalogItemType type, SpotlightSettings settings)
    {
        var channels = GetChannels(account, page, type, settings);
        var candidates = channels.Select(SpotlightCandidate.FromChannel).ToArray();
        IReadOnlyList<SpotlightCandidate> movies = type == CatalogItemType.Movie ? candidates : Array.Empty<SpotlightCandidate>();
        IReadOnlyList<SpotlightCandidate> series = type == CatalogItemType.Series ? candidates : Array.Empty<SpotlightCandidate>();
        var displayTitles = channels.GroupBy(channel => channel.Id, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First().DisplayName, StringComparer.Ordinal);
        return SpotlightSelector.Select(page, account.AccountId, DateOnly.FromDateTime(DateTime.UtcNow), settings,
                movies, series)
            .Items.Take(3).Select(item => displayTitles.GetValueOrDefault(item.ItemId, item.NormalizedTitle)).ToArray();
    }

    public IReadOnlyList<Channel> GetChannelsForSelection(ProviderAccount account, SpotlightPageKind page, CatalogItemType type, SpotlightSettings settings) =>
        GetChannels(account, page, type, settings);

    private IReadOnlyList<Channel> GetChannels(ProviderAccount account, SpotlightPageKind page, CatalogItemType type, SpotlightSettings settings)
    {
        var content = settings.ForPage(page, type);
        return content.SourceMode switch
        {
            SpotlightSourceMode.SpecificFolders => (content.SelectedCategoryIds ?? Array.Empty<string>())
                .SelectMany(id => _catalog.GetSpotlightCategoryCandidates(account, type, id)).ToArray(),
            SpotlightSourceMode.UnexploredFolders => _catalog.GetSpotlightUnexploredCandidates(account, type, null,
                categoryLimit: 12, itemsPerCategory: 6),
            _ => _catalog.GetSpotlightRecentlyAddedCandidates(account, type),
        };
    }
}
