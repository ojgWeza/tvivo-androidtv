using Tvivo.Core;
using Tvivo.App.Pages;
using Tvivo.App;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class AppSmokeTests
{
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
