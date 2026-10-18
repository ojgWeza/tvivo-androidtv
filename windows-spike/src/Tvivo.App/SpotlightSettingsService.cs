using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App;

/// <summary>App-facing access to account-scoped Spotlight preferences and local previews.</summary>
public sealed class SpotlightSettingsService
{
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

    public static void LogSaved(SpotlightSettings settings) => LaunchDiagnostics.Write(
        $"event=spotlight.settings.saved moviesMode={settings.Movies.SourceMode} moviesFolders={settings.Movies.SelectedCategoryIds?.Count ?? 0} " +
        $"seriesMode={settings.Series.SourceMode} seriesFolders={settings.Series.SelectedCategoryIds?.Count ?? 0}");

    public void Save(string opaqueAccountKey, SpotlightSettings settings)
    {
        _settingsStore.Write(opaqueAccountKey, settings);
        _dailyStore.Invalidate(opaqueAccountKey);
    }

    public IReadOnlyList<string> PreviewTitles(ProviderAccount account, CatalogItemType type, SpotlightSettings settings)
    {
        var channels = GetChannels(account, type, settings);
        var candidates = channels.Select(SpotlightCandidate.FromChannel).ToArray();
        IReadOnlyList<SpotlightCandidate> movies = type == CatalogItemType.Movie ? candidates : Array.Empty<SpotlightCandidate>();
        IReadOnlyList<SpotlightCandidate> series = type == CatalogItemType.Series ? candidates : Array.Empty<SpotlightCandidate>();
        var page = type == CatalogItemType.Movie ? SpotlightPageKind.Movies : SpotlightPageKind.Series;
        var displayTitles = channels.GroupBy(channel => channel.Id, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First().DisplayName, StringComparer.Ordinal);
        return SpotlightSelector.Select(page, account.AccountId, DateOnly.FromDateTime(DateTime.UtcNow), settings,
                movies, series)
            .Items.Take(3).Select(item => displayTitles.GetValueOrDefault(item.ItemId, item.NormalizedTitle)).ToArray();
    }

    private IReadOnlyList<Channel> GetChannels(ProviderAccount account, CatalogItemType type, SpotlightSettings settings)
    {
        var content = type == CatalogItemType.Movie ? settings.Movies : settings.Series;
        return content.SourceMode switch
        {
            SpotlightSourceMode.SpecificFolders => (content.SelectedCategoryIds ?? Array.Empty<string>())
                .SelectMany(id => _catalog.GetSpotlightCategoryCandidates(account, type, id)).ToArray(),
            SpotlightSourceMode.UnexploredFolders => _catalog.GetSpotlightUnexploredCandidates(account, type, null),
            _ => _catalog.GetSpotlightRecentlyAddedCandidates(account, type),
        };
    }
}
