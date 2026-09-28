using Tvivo.Core;
using Tvivo.App.Pages;

namespace Tvivo.App;

public static class CatalogGridPaging
{
    // The current full listing renders nine columns at the standard window width.
    // Eleven rows (99 items) therefore end on a complete row.
    public const int PageSize = 99;
}

public static class ShelfVisibilityPolicy
{
    public static bool HasItems(int itemCount) => itemCount > 0;
}

public static class CatalogTransitionPolicy
{
    public static bool ShouldAnimateIncomingContent(bool hasRenderedSnapshot, bool modeChanged) =>
        modeChanged || !hasRenderedSnapshot;

    public static bool ShouldUsePlayerReturnLayoutBarrier(
        CatalogLandingPage.CatalogMode mode,
        bool currentPageIsPlayer) =>
        mode switch
        {
            CatalogLandingPage.CatalogMode.MyTvivo or
            CatalogLandingPage.CatalogMode.Movies or
            CatalogLandingPage.CatalogMode.Series or
            CatalogLandingPage.CatalogMode.LiveTv => currentPageIsPlayer,
            _ => false,
        };
}

public static class SpotlightIndicatorPolicy
{
    public const int MaximumVisibleIndicators = 7;

    public static (int StartIndex, int Count) GetVisibleWindow(int itemCount, int selectedIndex)
    {
        if (itemCount <= 0) return (0, 0);
        var count = Math.Min(itemCount, MaximumVisibleIndicators);
        var selected = Math.Clamp(selectedIndex, 0, itemCount - 1);
        var start = Math.Clamp(selected - count / 2, 0, itemCount - count);
        return (start, count);
    }
}

public static class FavoriteTargetResolver
{
    public static (CatalogItemType Type, string Id) Resolve(Channel channel) =>
        channel.Source.Kind == StreamKind.Episode && channel.Metadata.TryGetValue("seriesId", out var seriesId)
            ? (CatalogItemType.Series, seriesId)
            : (channel.Source.Kind == StreamKind.Live ? CatalogItemType.Live :
                channel.Source.Kind == StreamKind.Series ? CatalogItemType.Series : CatalogItemType.Movie, channel.Id);

    public static bool ShowsPerItemFavorite(Channel channel) =>
        channel.Source.Kind is StreamKind.Live or StreamKind.Movie;
}
