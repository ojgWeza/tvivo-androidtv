using Tvivo.Core;

namespace Tvivo.App.Pages;

public enum MyTvivoShelfKind
{
    RecentlyAdded,
    RecentlyPlayed,
    Favorites,
}

public sealed record MyTvivoShelfDefinition(
    string Id,
    string Title,
    CatalogItemType Type,
    MyTvivoShelfKind Kind);

public static class MyTvivoShelfDefinitions
{
    public static IReadOnlyList<MyTvivoShelfDefinition> All { get; } = Array.AsReadOnly(new[]
    {
        new MyTvivoShelfDefinition("__my_added_movie", "Recently added Movies", CatalogItemType.Movie, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_added_series", "Recently added Series", CatalogItemType.Series, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_added_live", "Recently added Live TV", CatalogItemType.Live, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_played_movie", "Recently played Movies", CatalogItemType.Movie, MyTvivoShelfKind.RecentlyPlayed),
        new MyTvivoShelfDefinition("__my_played_series", "Recently played Series", CatalogItemType.Series, MyTvivoShelfKind.RecentlyPlayed),
        new MyTvivoShelfDefinition("__my_played_live", "Recently played Live TV", CatalogItemType.Live, MyTvivoShelfKind.RecentlyPlayed),
        new MyTvivoShelfDefinition("__my_favorites_movie", "Favorite Movies", CatalogItemType.Movie, MyTvivoShelfKind.Favorites),
        new MyTvivoShelfDefinition("__my_favorites_series", "Favorite Series", CatalogItemType.Series, MyTvivoShelfKind.Favorites),
        new MyTvivoShelfDefinition("__my_favorites_live", "Favorite Live TV", CatalogItemType.Live, MyTvivoShelfKind.Favorites),
    });
}
