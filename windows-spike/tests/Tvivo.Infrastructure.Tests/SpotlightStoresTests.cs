using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class SpotlightStoresTests
{
    [Fact]
    public void Settings_store_round_trips_atomically_and_scopes_files_to_account_key()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        try
        {
            var store = new SpotlightSettingsStore(directory);
            var settings = new SpotlightSettings(new(SpotlightSourceMode.SpecificFolders, new[] { "movie-folder" }),
                new(SpotlightSourceMode.UnexploredFolders, Array.Empty<string>()), 7,
                new(SpotlightSourceMode.RecentlyAdded), new(SpotlightSourceMode.SpecificFolders, new[] { "series-page" }),
                new(SpotlightSourceMode.SpecificFolders, new[] { "for-you-movie" }),
                new(SpotlightSourceMode.UnexploredFolders, new[] { "for-you-series" }));
            store.Write("opaque-account-key", settings);
            var restored = store.Read("opaque-account-key");
            Assert.Equal(settings.Movies.SourceMode, restored.Movies.SourceMode);
            Assert.Equal(settings.Movies.SelectedCategoryIds, restored.Movies.SelectedCategoryIds);
            Assert.Equal(settings.Series.SourceMode, restored.Series.SourceMode);
            Assert.Equal("series-page", Assert.Single(restored.SeriesPage!.SelectedCategoryIds!));
            Assert.Equal("for-you-movie", Assert.Single(restored.ForYouMovies!.SelectedCategoryIds!));
            Assert.Equal("for-you-series", Assert.Single(restored.ForYouSeries!.SelectedCategoryIds!));
            Assert.Equal(7, restored.RotationDays);
            Assert.Equal(SpotlightSettings.Default, store.Read("another-key"));
            Assert.Single(Directory.GetFiles(directory, "*.json"));
            Assert.Empty(Directory.GetFiles(directory, "*.tmp"));
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Fact]
    public void Legacy_settings_json_seeds_all_page_sources_from_movies_and_series()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        try
        {
            var store = new SpotlightSettingsStore(directory);
            store.Write("key", SpotlightSettings.Default with
            {
                Movies = new(SpotlightSourceMode.SpecificFolders, new[] { "old-movie" }),
                Series = new(SpotlightSourceMode.UnexploredFolders, new[] { "old-series" }),
            });
            var path = Directory.GetFiles(directory, "*.json").Single();
            var json = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            json.Remove("moviesPage"); json.Remove("seriesPage"); json.Remove("forYouMovies"); json.Remove("forYouSeries");
            File.WriteAllText(path, json.ToJsonString());

            var restored = store.Read("key");

            Assert.Equal("old-movie", Assert.Single(restored.ForPage(SpotlightPageKind.Movies, CatalogItemType.Movie).SelectedCategoryIds!));
            Assert.Equal("old-movie", Assert.Single(restored.ForPage(SpotlightPageKind.ForYou, CatalogItemType.Movie).SelectedCategoryIds!));
            Assert.Equal("old-series", Assert.Single(restored.ForPage(SpotlightPageKind.Series, CatalogItemType.Series).SelectedCategoryIds!));
            Assert.Equal(SpotlightSourceMode.UnexploredFolders,
                restored.ForPage(SpotlightPageKind.ForYou, CatalogItemType.Series).SourceMode);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Theory]
    [InlineData(null, 1)]
    [InlineData("14", 14)]
    [InlineData("2", 1)]
    [InlineData("99", 1)]
    [InlineData("\"invalid\"", 1)]
    public void Rotation_settings_read_old_json_and_normalize_unknown_values(string? rotation, int expected)
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        try
        {
            var store = new SpotlightSettingsStore(directory);
            store.Write("key", SpotlightSettings.Default with
            {
                Movies = new SpotlightContentSettings(SpotlightSourceMode.SpecificFolders, new[] { "folder" })
            });
            var path = Directory.GetFiles(directory, "*.json").Single();
            var json = File.ReadAllText(path);
            json = System.Text.RegularExpressions.Regex.Replace(json, ",\"rotationDays\":1", "");
            if (rotation is not null) json = json[..^1] + $",\"rotationDays\":{rotation}" + "}";
            File.WriteAllText(path, json);
            Assert.Equal(expected, store.Read("key").RotationDays);
            Assert.Equal(SpotlightSourceMode.SpecificFolders, store.Read("key").Movies.SourceMode);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Theory]
    [InlineData("{ malformed")]
    [InlineData("{\"version\":99,\"movies\":{},\"series\":{}}")]
    [InlineData("{\"version\":1,\"movies\":{\"sourceMode\":\"FutureMode\",\"selectedCategoryIds\":[]},\"series\":{\"sourceMode\":\"RecentlyAdded\",\"selectedCategoryIds\":[]}}")]
    [InlineData("{\"version\":1,\"movies\":{\"sourceMode\":\"SpecificFolders\",\"selectedCategoryIds\":[\"a\",\"a\"]},\"series\":{\"sourceMode\":\"RecentlyAdded\",\"selectedCategoryIds\":[]}}")]
    public void Malformed_unknown_or_duplicate_settings_fall_back_to_default(string json)
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var store = new SpotlightSettingsStore(directory);
            store.Write("key", SpotlightSettings.Default);
            File.WriteAllText(Directory.GetFiles(directory, "*.json").Single(), json);
            Assert.Equal(SpotlightSettings.Default, store.Read("key"));
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public void Daily_store_round_trips_only_ids_and_trims_old_days()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        try
        {
            var store = new SpotlightDailySelectionStore(directory);
            var today = new DateOnly(2026, 10, 16);
            var selection = new SpotlightDailySelection("opaque-account-key", SpotlightPageKind.Movies, today,
                SpotlightSourceMode.RecentlyAdded, "folder-fingerprint",
                new[] { new SpotlightDailyItem(CatalogItemType.Movie, "opaque-id") });
            for (var offset = 34; offset >= 1; offset--)
                store.Write(selection with { UtcDate = today.AddDays(-offset) });
            store.Write(selection);
            var restored = store.Read(selection.AccountKey, selection.Page, today, selection.SourceMode, selection.FolderFingerprint);
            Assert.Equal(selection.AccountKey, restored?.AccountKey);
            Assert.Equal(selection.Items, restored?.Items);
            Assert.Null(store.Read("other-key", selection.Page, today, selection.SourceMode, selection.FolderFingerprint));
            Assert.Null(store.Read(selection.AccountKey, selection.Page, today.AddDays(-31), selection.SourceMode, selection.FolderFingerprint));
            Assert.Empty(Directory.GetFiles(directory, "*.tmp"));
            var json = File.ReadAllText(Directory.GetFiles(directory, "*.json").Single());
            Assert.DoesNotContain("artwork", json, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("http", json, StringComparison.OrdinalIgnoreCase);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Fact]
    public void Daily_store_invalidate_removes_cached_selection_for_account()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-tests", Guid.NewGuid().ToString("N"));
        try
        {
            var store = new SpotlightDailySelectionStore(directory);
            var day = new DateOnly(2026, 10, 16);
            var selection = new SpotlightDailySelection("account", SpotlightPageKind.ForYou, day,
                SpotlightSourceMode.RecentlyAdded, "fingerprint",
                new[] { new SpotlightDailyItem(CatalogItemType.Movie, "item") });
            store.Write(selection);

            store.Invalidate("account");

            Assert.Null(store.Read("account", selection.Page, day, selection.SourceMode, selection.FolderFingerprint));
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }
}
