using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class ShelfWindowPlannerTests
{
    [Fact]
    public void Top_window_clamps_prefetch_and_loads_only_missing_shelves()
    {
        var plan = ShelfWindowPlanner.Plan(20, 0, 2, 3, 6, new[] { 0, 1, 10 });

        Assert.Equal(0, plan.FirstIndex);
        Assert.Equal(5, plan.LastIndex);
        Assert.Equal(new[] { 2, 3, 4, 5 }, plan.ToLoad);
        Assert.Equal(new[] { 10 }, plan.ToUnload);
    }

    [Fact]
    public void Bottom_window_clamps_to_last_shelf()
    {
        var plan = ShelfWindowPlanner.Plan(10, 8, 9, 3, 16, Array.Empty<int>());

        Assert.Equal(5, plan.FirstIndex);
        Assert.Equal(9, plan.LastIndex);
        Assert.Equal(Enumerable.Range(5, 5), plan.ToLoad);
    }

    [Fact]
    public void Fast_jump_loads_new_window_and_unloads_farthest_when_over_cap()
    {
        var loaded = Enumerable.Range(0, 20).ToArray();
        var plan = ShelfWindowPlanner.Plan(100, 80, 82, 3, 16, loaded);

        Assert.Equal(Enumerable.Range(77, 9), plan.ToLoad);
        Assert.Equal(Enumerable.Range(0, 13), plan.ToUnload);
        Assert.Equal(16, loaded.Length - plan.ToUnload.Count + plan.ToLoad.Count);
    }

    [Fact]
    public void Fast_jump_from_top_requests_the_destination_window()
    {
        var plan = ShelfWindowPlanner.Plan(300, 250, 252, 3, 16, Enumerable.Range(0, 9));

        Assert.Equal(247, plan.FirstIndex);
        Assert.Equal(255, plan.LastIndex);
        Assert.Equal(Enumerable.Range(247, 9), plan.ToLoad);
    }

    [Fact]
    public void New_window_still_loads_after_a_stale_window_result_was_discarded()
    {
        // The old query targeted 0..8. If its result is stale and was never applied,
        // planning from that old loaded set must still request the resumed window.
        var oldWindowLoaded = Enumerable.Range(0, 9);
        var resumed = ShelfWindowPlanner.Plan(300, 80, 82, 3, 16, oldWindowLoaded);

        Assert.Equal(77, resumed.FirstIndex);
        Assert.Equal(85, resumed.LastIndex);
        Assert.Equal(Enumerable.Range(77, 9), resumed.ToLoad);
    }

    [Fact]
    public void Shrinking_list_discards_loaded_indices_that_no_longer_exist()
    {
        var plan = ShelfWindowPlanner.Plan(5, 1, 2, 1, 12, new[] { 0, 4, 5, 12 });

        Assert.Equal(new[] { 1, 2, 3 }, plan.ToLoad);
        Assert.Equal(new[] { 5, 12 }, plan.ToUnload);
    }
}
