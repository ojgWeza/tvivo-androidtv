using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlayerCategoryAccordionStateTests
{
    [Fact]
    public void Toggle_keeps_one_category_open_and_returns_visible_focus_header()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Movie);
        Assert.Equal("first", state.Toggle("first").HeaderToFocus);
        state.SelectItem("movie");
        var oldGeneration = state.PageGeneration;
        Assert.Equal("second", state.Toggle("second").HeaderToFocus);
        Assert.Equal("second", state.ExpandedCategoryId);
        Assert.Null(state.SelectedItemId);
        Assert.True(state.PageGeneration > oldGeneration);
        Assert.Equal("second", state.Toggle("second").HeaderToFocus);
        Assert.Null(state.ExpandedCategoryId);
    }

    [Fact]
    public void Stale_pages_are_rejected_after_switch_collapse_account_and_type_reset()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Movie);
        state.Toggle("first");
        var generation = state.PageGeneration;
        state.Toggle("second");
        Assert.False(state.TryAcceptPage(generation, "first", 0, Array.Empty<Channel>()));
        Assert.False(state.TryAcceptPage(state.PageGeneration, "first", 0, Array.Empty<Channel>()));
        Assert.True(state.TryAcceptPage(state.PageGeneration, "second", 0, Array.Empty<Channel>()));
        state.SetContext("b", CatalogItemType.Movie);
        Assert.Equal(0, state.CachedPageCount);
        Assert.False(state.TryAcceptPage(generation, "second", 0, Array.Empty<Channel>()));
        state.Toggle("third");
        var typeGeneration = state.PageGeneration;
        state.SetContext("b", CatalogItemType.Series);
        Assert.Null(state.ExpandedCategoryId);
        Assert.Null(state.SelectedItemId);
        Assert.False(state.TryAcceptPage(typeGeneration, "third", 0, Array.Empty<Channel>()));
    }

    [Fact]
    public void Page_cache_retains_only_three_recent_pages_of_open_category()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Movie);
        state.Toggle("folder");
        var generation = state.PageGeneration;
        foreach (var offset in new[] { 0, 50, 100 })
            Assert.True(state.TryAcceptPage(generation, "folder", offset, Array.Empty<Channel>()));
        Assert.True(state.TryGetPage("folder", 0, out _));
        Assert.True(state.TryAcceptPage(generation, "folder", 150, Array.Empty<Channel>()));
        Assert.Equal(3, state.CachedPageCount);
        Assert.False(state.TryGetPage("folder", 50, out _));
        Assert.True(state.TryGetPage("folder", 0, out _));
        state.Toggle("folder");
        Assert.Equal(3, state.CachedPageCount);
        Assert.False(state.TryAcceptPage(generation, "folder", 200, Array.Empty<Channel>()));
        state.Toggle("folder");
        Assert.True(state.TryGetPage("folder", 0, out _));
    }

    [Fact]
    public void Playing_folder_opens_first_and_repeated_selection_keeps_generation_and_focus()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Movie);
        Assert.Equal("middle", state.OpenForPlaying("middle", "film").HeaderToFocus);
        Assert.Equal(new[] { "middle", "first", "last" },
            state.OrderedCategoryIds(new[] { "first", "middle", "last" }));
        var generation = state.PageGeneration;
        Assert.Equal("middle", state.OpenForPlaying("middle", "film").HeaderToFocus);
        Assert.Equal(generation, state.PageGeneration);
        Assert.Equal("middle", state.SelectCategory("middle").HeaderToFocus);
        Assert.Equal(generation, state.PageGeneration);
        Assert.Equal("film", state.SelectedItemId);
        Assert.Equal("middle", state.Toggle("middle").HeaderToFocus);
        Assert.Equal(new[] { "first", "middle", "last" },
            state.OrderedCategoryIds(new[] { "first", "middle", "last" }));
    }

    [Fact]
    public void Unknown_playing_folder_does_not_open_any_folder()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Series);
        Assert.Null(state.OpenForPlaying(null, "episode").HeaderToFocus);
        Assert.Null(state.ExpandedCategoryId);
    }

    [Fact]
    public void Series_drill_in_preserves_selection_and_invalidates_pending_pages()
    {
        var state = new PlayerCategoryAccordionState("a", CatalogItemType.Series);
        state.Toggle("folder");
        state.SelectItem("series");
        var generation = state.PageGeneration;
        state.EnterSeriesEpisodes();
        Assert.Equal(PlayerCategoryViewMode.SeriesEpisodes, state.ViewMode);
        Assert.False(state.TryAcceptPage(generation, "folder", 0, Array.Empty<Channel>()));
        Assert.Equal("folder", state.ReturnToCategories().HeaderToFocus);
        Assert.Equal("series", state.SelectedItemId);
        Assert.Equal(PlayerCategoryViewMode.Categories, state.ViewMode);
    }
}
