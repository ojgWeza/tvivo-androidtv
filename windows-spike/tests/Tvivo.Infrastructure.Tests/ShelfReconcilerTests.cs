using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class ShelfReconcilerTests
{
    [Fact]
    public void Preview_preload_and_unload_keep_row_identity_title_and_badge()
    {
        var unloaded = new[]
        {
            new ShelfRow("a", "Action", false, false),
            new ShelfRow("b", "Drama", true, false),
        };

        var loaded = ShelfReconciler.Reconcile(unloaded,
            new[] { unloaded[0] with { HasCards = true }, unloaded[1] with { HasCards = true } },
            row => row.Id, (_, fresh) => fresh, deferStructuralChanges: true);
        var unloadedAgain = ShelfReconciler.Reconcile(loaded.Shelves,
            loaded.Shelves.Select(row => row with { HasCards = false }).ToArray(),
            row => row.Id, (_, fresh) => fresh, deferStructuralChanges: true);

        Assert.Equal(new[] { (0, "a", "Action", false), (1, "b", "Drama", true) }, Tuples(unloadedAgain.Shelves));
    }

    [Fact]
    public void Refresh_defers_structure_and_keeps_visible_shelf_at_its_index()
    {
        var current = new[]
        {
            new ShelfRow("a", "A", false, true),
            new ShelfRow("b", "B", false, true),
            new ShelfRow("visible", "Old title", false, true),
            new ShelfRow("d", "D", false, true),
        };
        var refreshed = new[]
        {
            new ShelfRow("new", "New", false, true),
            new ShelfRow("d", "D updated", false, true),
            new ShelfRow("visible", "Updated title", true, false),
            new ShelfRow("a", "A", false, true),
        };

        var result = ShelfReconciler.Reconcile(current, refreshed,
            row => row.Id, (existing, fresh) => fresh with { HasCards = existing.HasCards },
            deferStructuralChanges: true);

        Assert.True(result.StructuralChangesDeferred);
        Assert.Equal(1, result.AddedCount);
        Assert.Equal(1, result.RemovedCount);
        Assert.Equal("visible", result.Shelves[2].Id);
        Assert.Equal("Updated title", result.Shelves[2].Title);
        Assert.True(result.Shelves[2].HasNewItems);
        Assert.True(result.Shelves[2].HasCards);
        Assert.Equal(new[] { "a", "b", "visible", "d" }, result.Shelves.Select(row => row.Id));
    }

    [Fact]
    public void Duplicate_refreshed_ids_keep_the_first_shelf()
    {
        var result = ShelfReconciler.Reconcile(
            Array.Empty<ShelfRow>(),
            new[]
            {
                new ShelfRow("duplicate", "First", false, true),
                new ShelfRow("duplicate", "Second", true, false),
            },
            row => row.Id, (_, fresh) => fresh, deferStructuralChanges: false);

        var shelf = Assert.Single(result.Shelves);
        Assert.Equal("First", shelf.Title);
        Assert.False(shelf.HasNewItems);
    }

    [Fact]
    public void Deferred_structure_commits_when_deferral_ends()
    {
        var current = new[] { new ShelfRow("a", "A", false, true) };
        var refreshed = new[]
        {
            new ShelfRow("b", "B", false, true),
            new ShelfRow("a", "Updated A", true, true),
        };

        var deferred = ShelfReconciler.Reconcile(current, refreshed,
            row => row.Id, (_, fresh) => fresh, deferStructuralChanges: true);
        var committed = ShelfReconciler.Reconcile(deferred.Shelves, refreshed,
            row => row.Id, (_, fresh) => fresh, deferStructuralChanges: false);

        Assert.True(deferred.StructuralChangesDeferred);
        Assert.False(committed.StructuralChangesDeferred);
        Assert.Equal(new[] { "b", "a" }, committed.Shelves.Select(row => row.Id));
        Assert.Equal("Updated A", committed.Shelves[1].Title);
    }

    private static (int Index, string Id, string Title, bool Badge)[] Tuples(IReadOnlyList<ShelfRow> shelves) =>
        shelves.Select((row, index) => (index, row.Id, row.Title, row.HasNewItems)).ToArray();

    private sealed record ShelfRow(string Id, string Title, bool HasNewItems, bool HasCards);
}
