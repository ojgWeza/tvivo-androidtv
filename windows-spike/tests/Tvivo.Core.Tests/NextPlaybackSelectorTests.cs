using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class NextPlaybackSelectorTests
{
    [Fact]
    public void Sequential_next_returns_the_following_item_and_none_at_the_end()
    {
        var selector = new NextPlaybackSelector();
        var playlist = new[] { "one", "two", "three" };

        Assert.Equal("two", selector.SelectNext(playlist, "one"));
        Assert.Null(selector.SelectNext(playlist, "three"));
    }

    [Fact]
    public void Shuffle_never_repeats_within_a_round_and_never_picks_current()
    {
        var selector = new NextPlaybackSelector(new Random(17));
        var playlist = new[] { "one", "two", "three", "four" };
        var seen = new HashSet<string>(StringComparer.Ordinal) { "one" };
        var current = "one";

        for (var index = 0; index < playlist.Length - 1; index++)
        {
            var next = selector.SelectNext(playlist, current, shuffle: true);
            Assert.NotNull(next);
            Assert.NotEqual(current, next);
            Assert.True(seen.Add(next!));
            current = next!;
        }

        Assert.Equal(playlist.Length, seen.Count);
    }

    [Fact]
    public void Shuffle_prefers_unfinished_and_starts_a_new_round_after_exhaustion()
    {
        var selector = new NextPlaybackSelector(new Random(3));
        var playlist = new[] { "current", "finished", "unwatched" };
        var finished = new HashSet<string>(StringComparer.Ordinal) { "current", "finished" };

        Assert.Equal("unwatched", selector.SelectNext(playlist, "current", finished, shuffle: true));
        Assert.Equal("finished", selector.SelectNext(playlist, "unwatched", finished, shuffle: true));
        Assert.Equal("unwatched", selector.SelectNext(playlist, "finished", finished, shuffle: true));
    }

    [Fact]
    public void Shuffle_handles_empty_and_single_item_playlists()
    {
        var selector = new NextPlaybackSelector(new Random(1));

        Assert.Null(selector.SelectNext(Array.Empty<string>(), "missing", shuffle: true));
        Assert.Null(selector.SelectNext(new[] { "only" }, "only", shuffle: true));
    }

    [Fact]
    public void Shuffle_is_deterministic_for_the_same_seed()
    {
        static string[] Run(int seed)
        {
            var selector = new NextPlaybackSelector(new Random(seed));
            var playlist = new[] { "one", "two", "three", "four", "five" };
            var result = new List<string>();
            var current = "one";
            for (var index = 0; index < playlist.Length - 1; index++)
            {
                current = selector.SelectNext(playlist, current, shuffle: true)!;
                result.Add(current);
            }
            return result.ToArray();
        }

        Assert.Equal(Run(42), Run(42));
    }
}
