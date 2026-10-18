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
}

public static class CatalogReloadPolicy
{
    public static bool ShouldReuseHome(bool hasSuccessfulLoad, bool sameAccount, bool dataVersionMatches) =>
        hasSuccessfulLoad && sameAccount && dataVersionMatches;
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
