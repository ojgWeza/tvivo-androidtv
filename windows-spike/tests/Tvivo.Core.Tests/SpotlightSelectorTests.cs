using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SpotlightSelectorTests
{
    private static readonly DateOnly Day = new(2026, 10, 16);
    private static SpotlightCandidate Item(CatalogItemType type, string id, string category = "a", string? year = null,
        int? ageDays = null) => new(type, id, category, SpotlightSelector.Normalize(id), year,
        ageDays is null ? null : DateTimeOffset.UtcNow.AddDays(-ageDays.Value));
    private static SpotlightSettings Settings(SpotlightSourceMode mode = SpotlightSourceMode.RecentlyAdded,
        params string[] folders) => new(new(mode, folders), new(mode, folders));

    [Theory]
    [InlineData(1)]
    [InlineData(3)]
    [InlineData(7)]
    [InlineData(10)]
    [InlineData(14)]
    public void Rotation_bucket_is_stable_until_utc_boundary(int days)
    {
        var start = SpotlightSelector.BucketStart(Day, days);
        Assert.Equal(start, SpotlightSelector.BucketStart(start.AddDays(days - 1), days));
        Assert.NotEqual(start, SpotlightSelector.BucketStart(start.AddDays(days), days));
        Assert.Equal(start, SpotlightSelector.BucketStart(
            DateOnly.FromDateTime(new DateTimeOffset(start.ToDateTime(TimeOnly.MinValue), TimeSpan.Zero)
                .AddHours(23).UtcDateTime), days));
    }

    [Fact]
    public void Rotation_period_changes_selection_but_is_deterministic()
    {
        var movies = Enumerable.Range(0, 40).Select(i => Item(CatalogItemType.Movie, $"m{i}")).ToArray();
        var day = new DateOnly(2026, 10, 20);
        var daily = Settings() with { RotationDays = 1 };
        var weekly = daily with { RotationDays = 7 };
        var a = SpotlightSelector.Select(SpotlightPageKind.Movies, "account", day, weekly, movies, []);
        var b = SpotlightSelector.Select(SpotlightPageKind.Movies, "account", day, weekly, movies, []);
        Assert.Equal(a.Items.Select(item => item.ItemId), b.Items.Select(item => item.ItemId));
        Assert.NotEqual(SpotlightSelector.BucketStart(day, daily.RotationDays),
            SpotlightSelector.BucketStart(day, weekly.RotationDays));
        Assert.NotEqual(SpotlightSelector.Select(SpotlightPageKind.Movies, "account", day, daily, movies, [])
            .Items.Select(item => item.ItemId), a.Items.Select(item => item.ItemId));
    }

    [Fact]
    public void Movies_and_series_pages_are_isolated_and_daily_order_is_stable()
    {
        var movies = Enumerable.Range(0, 24).Select(i => Item(CatalogItemType.Movie, $"m{i}")).ToArray();
        var series = Enumerable.Range(0, 24).Select(i => Item(CatalogItemType.Series, $"s{i}")).ToArray();
        var settings = Settings();
        var first = SpotlightSelector.Select(SpotlightPageKind.Movies, "opaque-a", Day, settings, movies, series);
        var sameDay = SpotlightSelector.Select(SpotlightPageKind.Movies, "opaque-a", Day, settings, movies, series);
        var nextDay = SpotlightSelector.Select(SpotlightPageKind.Movies, "opaque-a", Day.AddDays(1), settings, movies, series);
        Assert.Equal(8, first.Items.Count);
        Assert.All(first.Items, item => Assert.Equal(CatalogItemType.Movie, item.Type));
        Assert.Equal(first.Items.Select(i => i.ItemId), sameDay.Items.Select(i => i.ItemId));
        Assert.NotEqual(first.Items.Select(i => i.ItemId), nextDay.Items.Select(i => i.ItemId));
        Assert.All(SpotlightSelector.Select(SpotlightPageKind.Series, "opaque-a", Day, settings, movies, series).Items,
            item => Assert.Equal(CatalogItemType.Series, item.Type));
    }

    [Fact]
    public void For_you_alternates_four_each_and_borrows_unused_slots()
    {
        var movies = Enumerable.Range(0, 20).Select(i => Item(CatalogItemType.Movie, $"m{i}")).ToArray();
        var series = Enumerable.Range(0, 20).Select(i => Item(CatalogItemType.Series, $"s{i}")).ToArray();
        var full = SpotlightSelector.Select(SpotlightPageKind.ForYou, "opaque-a", Day, Settings(), movies, series);
        Assert.Equal(8, full.Items.Count);
        Assert.Equal(4, full.Items.Count(i => i.Type == CatalogItemType.Movie));
        Assert.Equal(4, full.Items.Count(i => i.Type == CatalogItemType.Series));
        Assert.Equal(new[] { CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Movie, CatalogItemType.Series,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Movie, CatalogItemType.Series }, full.Items.Select(i => i.Type));
        var borrowed = SpotlightSelector.Select(SpotlightPageKind.ForYou, "opaque-a", Day, Settings(), movies,
            new[] { Item(CatalogItemType.Series, "s1") });
        Assert.Equal(8, borrowed.Items.Count);
        Assert.Equal(7, borrowed.Items.Count(i => i.Type == CatalogItemType.Movie));
        Assert.Equal(1, borrowed.Items.Count(i => i.Type == CatalogItemType.Series));
    }

    [Fact]
    public void Specific_folders_round_robin_deduplicate_and_deleted_ids_do_not_fall_back()
    {
        var settings = Settings(SpotlightSourceMode.SpecificFolders, "a", "b", "deleted");
        var pool = new[] { Item(CatalogItemType.Movie, "Same", "a", "2020"),
            Item(CatalogItemType.Movie, "Same", "b", "2020"), Item(CatalogItemType.Movie, "Same", "b", "2021"),
            Item(CatalogItemType.Movie, "Other", "a") };
        var selected = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings, pool, Array.Empty<SpotlightCandidate>());
        Assert.Equal(new[] { "Same", "Other", "Same" }, selected.Items.Select(item => item.ItemId));
        var empty = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings,
            new[] { Item(CatalogItemType.Movie, "unrelated", "other") }, Array.Empty<SpotlightCandidate>());
        Assert.Empty(empty.Items);
        Assert.Equal(SpotlightEmptyReason.NoSelectedFolderCandidates, empty.EmptyReason);
    }

    [Fact]
    public void Sparse_empty_and_refresh_preserve_present_items_in_place()
    {
        var settings = Settings();
        var input = Enumerable.Range(0, 15).Select(i => Item(CatalogItemType.Movie, $"m{i}")).ToArray();
        var first = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings, input, Array.Empty<SpotlightCandidate>());
        var refreshedPool = input.Where(item => item.ItemId != first.Items[2].ItemId).Append(Item(CatalogItemType.Movie, "new"))
            .ToArray();
        var refreshed = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings, refreshedPool,
            Array.Empty<SpotlightCandidate>(), first.Items);
        Assert.Equal(first.Items[0].ItemId, refreshed.Items[0].ItemId);
        Assert.DoesNotContain(first.Items[2].ItemId, refreshed.Items.Select(i => i.ItemId));
        Assert.Equal(8, refreshed.Items.Count);
        var sparse = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings,
            input.Take(2).ToArray(), Array.Empty<SpotlightCandidate>());
        Assert.Equal(2, sparse.Items.Count);
        var empty = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, settings,
            Array.Empty<SpotlightCandidate>(), Array.Empty<SpotlightCandidate>());
        Assert.Equal(SpotlightEmptyReason.NoEligibleCandidates, empty.EmptyReason);
    }

    [Fact]
    public void Candidate_pool_and_result_are_bounded_and_live_is_never_selected()
    {
        var items = Enumerable.Range(0, 500).Select(i => Item(i % 2 == 0 ? CatalogItemType.Movie : CatalogItemType.Live, $"i{i}"))
            .ToArray();
        var result = SpotlightSelector.Select(SpotlightPageKind.Movies, "key", Day, Settings(), items,
            Array.Empty<SpotlightCandidate>());
        Assert.Equal(8, result.Items.Count);
        Assert.All(result.Items, item => Assert.Equal(CatalogItemType.Movie, item.Type));
    }

    [Fact]
    public void Folder_search_is_case_insensitive_and_keeps_complete_matching_rows()
    {
        var folders = new[]
        {
            new SpotlightFolderOption("one", "Classic Films", 12),
            new SpotlightFolderOption("two", "Drama Series", 8),
        };

        var result = SpotlightFolderList.Filter(folders, "CLASSIC");

        Assert.Equal(new[] { folders[0] }, result);
        Assert.Equal(12, result[0].ItemCount);
    }

    [Fact]
    public void Deleted_folder_detection_retains_only_saved_ids_without_a_catalog_row()
    {
        var folders = new[] { new SpotlightFolderOption("available", "Available", 1) };

        var missing = SpotlightFolderList.FindMissing(new[] { "available", "deleted", "deleted" }, folders);

        Assert.Equal(new[] { "deleted" }, missing);
    }
}
