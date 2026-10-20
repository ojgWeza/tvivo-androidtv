using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CatalogPageCachePolicyTests
{
    private static readonly CatalogPageRevision Revision = new(1, 2, 3, 4, 5);

    [Theory]
    [InlineData("For You", "Movies", "Series", "Live", "For You")]
    [InlineData("Movies", "For You", "Live", "Series", "Movies")]
    [InlineData("Live", "Series", "Movies", "For You", "Live")]
    public void Fourth_page_evicts_least_recently_used(string first, string second, string third,
        string fourth, string evicted)
    {
        var cache = NewCache();
        foreach (var mode in new[] { first, second, third, fourth })
            Assert.True(cache.Store("a", mode, Revision, mode));
        Assert.Equal(3, cache.Count);
        Assert.False(cache.TryGet("a", evicted, Revision, out _));
        Assert.True(cache.TryGet("a", fourth, Revision, out _));
    }

    [Fact]
    public void Hit_promotes_page_and_preserves_same_instance()
    {
        var cache = NewCache();
        var page = new object();
        cache.Store("a", "For You", Revision, page);
        cache.Store("a", "Movies", Revision, new object());
        cache.Store("a", "Series", Revision, new object());
        Assert.True(cache.TryGet("a", "For You", Revision, out var hit));
        Assert.Same(page, hit);
        cache.Store("a", "Live", Revision, new object());
        Assert.False(cache.TryGet("a", "Movies", Revision, out _));
        Assert.True(cache.TryGet("a", "For You", Revision, out _));
    }

    [Theory]
    [InlineData(2, 2, 3, 4, 5)]
    [InlineData(1, 3, 3, 4, 5)]
    [InlineData(1, 2, 4, 4, 5)]
    [InlineData(1, 2, 3, 5, 5)]
    [InlineData(1, 2, 3, 4, 6)]
    public void Every_revision_component_invalidates(long catalog, long spotlight, long selection,
        long activity, long favorites)
    {
        var cache = NewCache();
        cache.Store("a", "Movies", Revision, new object());
        Assert.False(cache.TryGet("a", "Movies", new(catalog, spotlight, selection, activity, favorites), out _));
        Assert.Equal(0, cache.Count);
    }

    [Fact]
    public void Account_switch_signout_and_memory_pressure_drop_pages()
    {
        var cache = NewCache();
        cache.Store("a", "Movies", Revision, new object());
        Assert.False(cache.TryGet("b", "Movies", Revision, out _));
        cache.SetAccount("b");
        Assert.Equal(0, cache.Count);
        cache.Store("b", "Movies", Revision, new object());
        cache.Invalidate(); // memory pressure
        Assert.Equal(0, cache.Count);
        cache.Store("b", "Movies", Revision, new object());
        cache.SetAccount(null); // sign out
        Assert.Equal(0, cache.Count);
    }

    [Fact]
    public void Older_async_completion_cannot_replace_newer_page()
    {
        var cache = NewCache();
        var oldGeneration = cache.BeginRevalidation();
        var newGeneration = cache.BeginRevalidation();
        var newest = new object();
        Assert.True(cache.Store("a", "Movies", Revision, newest, newGeneration));
        Assert.False(cache.Store("a", "Movies", Revision, new object(), oldGeneration));
        Assert.True(cache.TryGet("a", "Movies", Revision, out var page));
        Assert.Same(newest, page);
    }

    [Fact]
    public void Invalidation_rejects_in_flight_completion()
    {
        var cache = NewCache();
        var generation = cache.BeginRevalidation();
        cache.Invalidate();
        Assert.False(cache.Store("a", "Movies", Revision, new object(), generation));
        cache.SetAccount("b");
        Assert.False(cache.Store("a", "Movies", Revision, new object()));
    }

    [Fact]
    public void Older_revision_cannot_displace_newer_even_without_a_generation_token()
    {
        var cache = NewCache();
        var newer = Revision with { Favorites = 6 };
        var page = new object();
        Assert.True(cache.Store("a", "Movies", newer, page));
        Assert.False(cache.Store("a", "Movies", Revision, new object()));
        cache.Invalidate();
        Assert.False(cache.Store("a", "Movies", Revision, new object()));
        Assert.True(cache.Store("a", "Movies", newer, page));
        Assert.True(cache.TryGet("a", "Movies", newer, out var hit));
        Assert.Same(page, hit);
    }

    [Fact]
    public void Repeated_access_obeys_three_entry_lru_model()
    {
        var cache = NewCache();
        var expected = new List<string>();
        var modes = new[] { "For You", "Movies", "Series", "Live" };
        var random = new Random(9127);
        for (var step = 0; step < 200; step++)
        {
            var mode = modes[random.Next(modes.Length)];
            if (!cache.TryGet("a", mode, Revision, out _))
                Assert.True(cache.Store("a", mode, Revision, new object()));
            expected.Remove(mode);
            expected.Add(mode);
            if (expected.Count > 3) expected.RemoveAt(0);
            Assert.Equal(expected.Count, cache.Count);
            foreach (var candidate in modes)
            {
                var shouldHit = expected.Contains(candidate);
                Assert.Equal(shouldHit, cache.TryGet("a", candidate, Revision, out _));
                if (shouldHit)
                {
                    expected.Remove(candidate);
                    expected.Add(candidate);
                }
            }
        }
    }

    private static CatalogPageCachePolicy<object> NewCache()
    {
        var cache = new CatalogPageCachePolicy<object>();
        cache.SetAccount("a");
        return cache;
    }
}
