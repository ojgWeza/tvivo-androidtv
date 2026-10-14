namespace Tvivo.Infrastructure;

public sealed record ShelfReconciliation<T>(
    IReadOnlyList<T> Shelves,
    bool StructuralChangesDeferred,
    int AddedCount,
    int RemovedCount,
    int ReorderedCount);

/// <summary>Updates shelf data without changing row identity or order during an active refresh.</summary>
public static class ShelfReconciler
{
    public static ShelfReconciliation<T> Reconcile<T>(
        IReadOnlyList<T> current,
        IReadOnlyList<T> refreshed,
        Func<T, string> id,
        Func<T, T, T> updateMatching,
        bool deferStructuralChanges)
    {
        var uniqueCurrent = KeepFirst(current, id, out _);
        var uniqueRefreshed = KeepFirst(refreshed, id, out var duplicateRefreshed);
        if (duplicateRefreshed)
            System.Diagnostics.Trace.WriteLine("event=perf.shelfrows duplicateIds=true source=reconciler");

        var currentIds = uniqueCurrent.Select(id).ToArray();
        var refreshedIds = uniqueRefreshed.Select(id).ToArray();
        var currentSet = currentIds.ToHashSet(StringComparer.Ordinal);
        var refreshedSet = refreshedIds.ToHashSet(StringComparer.Ordinal);
        var added = refreshedIds.Count(value => !currentSet.Contains(value));
        var removed = currentIds.Count(value => !refreshedSet.Contains(value));
        var reordered = currentIds.Where(refreshedSet.Contains)
            .SequenceEqual(refreshedIds.Where(currentSet.Contains), StringComparer.Ordinal) ? 0 : 1;

        if (!deferStructuralChanges || current.Count == 0)
            return new(uniqueRefreshed.ToArray(), false, added, removed, reordered);

        var updates = uniqueRefreshed.ToDictionary(id, StringComparer.Ordinal);
        var stable = uniqueCurrent.Select(existing => updates.TryGetValue(id(existing), out var fresh)
            ? updateMatching(existing, fresh)
            : existing).ToArray();
        return new(stable, added + removed + reordered > 0, added, removed, reordered);
    }

    private static IReadOnlyList<T> KeepFirst<T>(IReadOnlyList<T> source, Func<T, string> id, out bool hadDuplicates)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var result = new List<T>(source.Count);
        hadDuplicates = false;
        foreach (var item in source)
        {
            if (seen.Add(id(item))) result.Add(item);
            else hadDuplicates = true;
        }
        return result;
    }
}
