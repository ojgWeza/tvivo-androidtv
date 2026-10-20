using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CatalogEntryPolicyTests
{
    [Theory]
    [InlineData(CatalogEntryIntent.StartAtTop, false)]
    [InlineData(CatalogEntryIntent.Restore, true)]
    [InlineData(CatalogEntryIntent.TabSwitch, true)]
    [InlineData(CatalogEntryIntent.ReturnFromPlayer, true)]
    [InlineData(CatalogEntryIntent.Refresh, true)]
    public void Scroll_entry_intent_selects_restore_or_reset(CatalogEntryIntent intent, bool restores) =>
        Assert.Equal(restores, CatalogScrollEntryPolicy.ShouldRestore(intent));

    [Fact]
    public void Home_tab_switch_back_and_refresh_have_expected_scroll_behavior()
    {
        Assert.True(CatalogScrollEntryPolicy.ShouldResetToTop(CatalogEntryIntent.StartAtTop));
        Assert.True(CatalogScrollEntryPolicy.ShouldRestore(CatalogEntryIntent.ReturnFromPlayer));
        Assert.True(CatalogScrollEntryPolicy.ShouldRestore(CatalogEntryIntent.Refresh));
    }

    [Fact]
    public void Series_artwork_resolves_safe_absolute_relative_fallbacks_and_episode_inheritance()
    {
        var provider = new Uri("https://provider.example/base/");
        Assert.Equal("https://cdn.example/cover.jpg", SeriesArtworkPolicy.Resolve("https://cdn.example/cover.jpg", provider));
        Assert.Equal("https://provider.example/base/images/cover.jpg", SeriesArtworkPolicy.Resolve("images/cover.jpg", provider));
        Assert.Null(SeriesArtworkPolicy.Resolve("javascript:alert(1)", provider));
        Assert.Null(SeriesArtworkPolicy.Resolve("//elsewhere.example/a.jpg", provider));
        Assert.Equal("backdrop.jpg", SeriesArtworkPolicy.SelectArray(new string?[] { "", "backdrop.jpg", "icon.jpg" }));
        Assert.Null(SeriesArtworkPolicy.Select("", " "));
        Assert.Equal(new Uri("https://cdn.example/series.jpg"), SeriesArtworkPolicy.InheritEpisode(null, new Uri("https://cdn.example/series.jpg")));
    }
}
