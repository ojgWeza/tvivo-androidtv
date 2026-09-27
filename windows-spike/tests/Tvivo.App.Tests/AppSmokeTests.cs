using Tvivo.Core;
using Tvivo.App.Pages;
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
}
