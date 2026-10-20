using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class Round2PerformanceTests
{
    [Fact]
    [Trait("Category", "Perf")]
    public void Shelf_reconciliation_and_window_planning_handle_large_catalog()
    {
        var current = Enumerable.Range(0, 100_000).Select(index => $"item-{index}").ToArray();
        var refreshed = current.Skip(1).Append("new-item").ToArray();
        ShelfReconciler.Reconcile(current.Take(8).ToArray(), refreshed.Take(8).ToArray(),
            item => item, (_, newer) => newer, true);
        var watch = Stopwatch.StartNew();
        var result = ShelfReconciler.Reconcile(current, refreshed, item => item, (_, newer) => newer, true);
        var window = ShelfWindowPlanner.Plan(10_000, 500, 510, 2, 24, Enumerable.Range(0, 1_000));
        watch.Stop();
        Assert.Equal(100_000, result.Shelves.Count);
        Assert.True(result.StructuralChangesDeferred);
        Assert.Empty(window.ToLoad);
        Assert.True(window.ToUnload.Count > 900);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"Shelf planning took {watch.Elapsed}.");
    }

    [Fact]
    [Trait("Category", "Perf")]
    public void Synthetic_100k_item_catalog_json_parses_within_budget()
    {
        var builder = new StringBuilder(5_000_000).Append('[');
        for (var index = 0; index < 100_000; index++)
        {
            if (index > 0) builder.Append(',');
            builder.Append("{\"stream_id\":").Append(index).Append(",\"name\":\"Item\"}");
        }
        var json = builder.Append(']').ToString();
        using (JsonDocument.Parse("[]")) { }
        var watch = Stopwatch.StartNew();
        using var document = JsonDocument.Parse(json);
        var count = 0;
        foreach (var item in document.RootElement.EnumerateArray())
        {
            _ = item.GetProperty("stream_id").GetInt32();
            _ = item.GetProperty("name").GetString();
            count++;
        }
        watch.Stop();
        Assert.Equal(100_000, count);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"Catalog JSON parse took {watch.Elapsed}.");
    }
}
