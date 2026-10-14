namespace Tvivo.Infrastructure;

public sealed record ShelfWindowPlan(
    int FirstIndex,
    int LastIndex,
    IReadOnlyList<int> ToLoad,
    IReadOnlyList<int> ToUnload);

/// <summary>Chooses the preview window and bounds retained loaded shelves without UI dependencies.</summary>
public static class ShelfWindowPlanner
{
    public static ShelfWindowPlan Plan(
        int shelfCount,
        int firstVisibleIndex,
        int lastVisibleIndex,
        int prefetchShelves,
        int loadedShelfCap,
        IEnumerable<int> loadedShelfIndices)
    {
        var suppliedLoaded = loadedShelfIndices.ToHashSet();
        var loaded = suppliedLoaded.Where(index => index >= 0 && index < shelfCount).ToHashSet();
        if (shelfCount <= 0 || firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex)
            return new(0, -1, Array.Empty<int>(), suppliedLoaded.Order().ToArray());

        var first = Math.Max(0, firstVisibleIndex - Math.Max(0, prefetchShelves));
        var last = Math.Min(shelfCount - 1, lastVisibleIndex + Math.Max(0, prefetchShelves));
        var window = Enumerable.Range(first, last - first + 1).ToHashSet();
        var toLoad = window.Where(index => !loaded.Contains(index)).Order().ToArray();

        var keep = new HashSet<int>(window);
        var cap = Math.Max(window.Count, loadedShelfCap);
        foreach (var index in loaded.Where(index => !window.Contains(index))
                     .OrderBy(index => DistanceToRange(index, firstVisibleIndex, lastVisibleIndex))
                     .ThenBy(index => index)
                     .Take(Math.Max(0, cap - keep.Count)))
            keep.Add(index);

        var toUnload = suppliedLoaded.Where(index => !keep.Contains(index)).Order().ToArray();
        return new(first, last, toLoad, toUnload);
    }

    private static int DistanceToRange(int index, int first, int last) =>
        index < first ? first - index : index > last ? index - last : 0;
}
