using Tvivo.Core;
using Tvivo.App.Pages;
using Tvivo.App;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class AppSmokeTests
{
    [Fact]
    public void Connection_error_copy_does_not_display_exception_details()
    {
        var sensitive = "https://private.example/account C:\\Users\\Someone\\secret.txt";
        var exception = new InvalidOperationException(sensitive);
        var messages = new[]
        {
            ConnectionErrorText.SavedConnection(exception),
            ConnectionErrorText.Authentication(exception),
            ConnectionErrorText.SaveConnection(exception),
            ConnectionErrorText.SignOut(exception),
        };

        Assert.Equal(new[]
        {
            "Could not load your saved connection. Try signing in again.",
            "Couldn't connect. Check the server address and your account details, then try again.",
            "Couldn't save your connection. Please try again.",
            "Couldn't sign out cleanly. Try again, or restart the app.",
        }, messages);
        Assert.All(messages, message =>
        {
            Assert.DoesNotContain("private.example", message, StringComparison.Ordinal);
            Assert.DoesNotContain("secret.txt", message, StringComparison.Ordinal);
        });
    }

    [Fact]
    public void AppTestProjectCanUseCoreContracts()
    {
        var token = new PlaybackSessionToken(1, Guid.NewGuid());
        Assert.Equal(1, token.Generation);
    }

    [Fact]
    public void Artwork_reuse_requires_the_same_item_url_and_current_source()
    {
        Assert.True(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/item-1", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-2", "https://art/item-1", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/new", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/item-1", false));
    }

    [Fact]
    public void Artwork_budget_counts_decoded_pixels_and_evicts_only_when_over_limit()
    {
        const long limit = 96L * 1024 * 1024;
        Assert.Equal(760_000, ArtworkMemoryBudget.EstimateDecodedBytes(380, 500));
        Assert.False(ArtworkMemoryBudget.ShouldEvict(limit, limit, 2, 128));
        Assert.True(ArtworkMemoryBudget.ShouldEvict(limit + 1, limit, 2, 128));
        Assert.True(ArtworkMemoryBudget.ShouldEvict(limit, limit, 129, 128));
        Assert.False(ArtworkMemoryBudget.ShouldEvict(limit + 1, limit, 1, 128));
    }

    [Fact]
    public void My_Tvivo_shelves_are_type_specific_and_ordered_by_concept_then_type()
    {
        Assert.Equal(new[]
        {
            "Recently added Movies",
            "Recently added Series",
            "Recently added Live TV",
            "Recently played Movies",
            "Recently played Series",
            "Recently played Live TV",
            "Favorite Movies",
            "Favorite Series",
            "Favorite Live TV",
        }, MyTvivoShelfDefinitions.All.Select(shelf => shelf.Title));
        Assert.Equal(new[]
        {
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
        }, MyTvivoShelfDefinitions.All.Select(shelf => shelf.Type));
    }

    [Fact]
    public void Empty_shelves_are_hidden_and_folder_pages_end_on_a_complete_nine_column_row()
    {
        Assert.False(ShelfVisibilityPolicy.HasItems(0));
        Assert.True(ShelfVisibilityPolicy.HasItems(1));
        Assert.Equal(99, CatalogGridPaging.PageSize);
        Assert.Equal(0, CatalogGridPaging.PageSize % 9);
    }

    [Fact]
    public void Catalog_snapshot_transition_reveals_first_content_and_fades_mode_changes()
    {
        Assert.True(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: false, modeChanged: false));
        Assert.True(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: true, modeChanged: true));
        Assert.False(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: true, modeChanged: false));
    }

    [Fact]
    public void Player_return_layout_barrier_uses_current_page_for_every_catalog_mode()
    {
        foreach (var mode in Enum.GetValues<CatalogLandingPage.CatalogMode>())
        {
            Assert.True(CatalogTransitionPolicy.ShouldUsePlayerReturnLayoutBarrier(mode, currentPageIsPlayer: true));
            Assert.False(CatalogTransitionPolicy.ShouldUsePlayerReturnLayoutBarrier(mode, currentPageIsPlayer: false));
        }
    }

    [Fact]
    public void Spotlight_indicator_window_stays_small_and_keeps_the_selected_item_visible()
    {
        var (start, count) = SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 40, selectedIndex: 23);

        Assert.Equal(7, count);
        Assert.InRange(23, start, start + count - 1);
        Assert.Equal((0, 0), SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 0, selectedIndex: 0));
        Assert.Equal((33, 7), SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 40, selectedIndex: 39));
    }

    [Fact]
    public void Series_episode_favorite_routes_to_the_parent_and_has_no_per_episode_star()
    {
        var episode = new Channel("account", "episode-1", "series-1", "Episode", "Episode", null, null, null,
            new StreamSource("episode-1", StreamKind.Episode),
            new Dictionary<string, string> { ["seriesId"] = "series-1" });
        var movie = new Channel("account", "movie-1", null, "Movie", "Movie", null, null, null,
            new StreamSource("movie-1", StreamKind.Movie), new Dictionary<string, string>());
        var live = new Channel("account", "live-1", null, "Live", "Live", null, null, null,
            new StreamSource("live-1", StreamKind.Live), new Dictionary<string, string>());

        Assert.Equal((CatalogItemType.Series, "series-1"), FavoriteTargetResolver.Resolve(episode));
        Assert.False(FavoriteTargetResolver.ShowsPerItemFavorite(episode));
        Assert.True(FavoriteTargetResolver.ShowsPerItemFavorite(movie));
        Assert.True(FavoriteTargetResolver.ShowsPerItemFavorite(live));
    }
}
