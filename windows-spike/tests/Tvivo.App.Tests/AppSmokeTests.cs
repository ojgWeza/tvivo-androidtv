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
}
