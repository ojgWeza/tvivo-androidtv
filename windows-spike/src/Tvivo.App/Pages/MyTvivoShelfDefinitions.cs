using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public enum MyTvivoShelfKind
{
    ContinueWatching,
    RecentlyAdded,
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
        new MyTvivoShelfDefinition("__my_continue_movie", "Continue watching Movies", CatalogItemType.Movie, MyTvivoShelfKind.ContinueWatching),
        new MyTvivoShelfDefinition("__my_continue_series", "Continue watching Series", CatalogItemType.Series, MyTvivoShelfKind.ContinueWatching),
        new MyTvivoShelfDefinition("__my_continue_live", "Continue watching Live TV", CatalogItemType.Live, MyTvivoShelfKind.ContinueWatching),
        new MyTvivoShelfDefinition("__my_added_movie", "Recently added Movies", CatalogItemType.Movie, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_added_series", "Recently added Series", CatalogItemType.Series, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_added_live", "Recently added Live TV", CatalogItemType.Live, MyTvivoShelfKind.RecentlyAdded),
        new MyTvivoShelfDefinition("__my_favorites_movie", "Favorite Movies", CatalogItemType.Movie, MyTvivoShelfKind.Favorites),
        new MyTvivoShelfDefinition("__my_favorites_series", "Favorite Series", CatalogItemType.Series, MyTvivoShelfKind.Favorites),
        new MyTvivoShelfDefinition("__my_favorites_live", "Favorite Live TV", CatalogItemType.Live, MyTvivoShelfKind.Favorites),
    });

    /// <summary>
    /// Every shelf in the user's chosen order with its visibility. Saved entries come first;
    /// unknown ids are dropped and shelves missing from the saved list are appended, visible.
    /// </summary>
    public static IReadOnlyList<(MyTvivoShelfDefinition Definition, bool Visible)> Resolve(
        IReadOnlyList<MyTvivoShelfPreference> saved)
    {
        var byId = All.ToDictionary(definition => definition.Id, StringComparer.Ordinal);
        var result = new List<(MyTvivoShelfDefinition, bool)>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var preference in saved)
            if (byId.TryGetValue(preference.Id, out var definition) && seen.Add(preference.Id))
                result.Add((definition, preference.Visible));
        foreach (var definition in All)
            if (seen.Add(definition.Id))
                result.Add((definition, true));
        return result;
    }
}
