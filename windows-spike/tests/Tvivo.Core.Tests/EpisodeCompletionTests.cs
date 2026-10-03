using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class EpisodeCompletionTests
{
    private const long Twenty = 20 * 60_000;

    [Fact]
    public void Ninety_percent_of_a_twenty_minute_episode_is_not_finished()
        => Assert.False(EpisodeCompletion.IsFinished(18 * 60_000, Twenty, ended: false));

    [Fact]
    public void Two_minutes_left_is_not_finished_but_the_last_fifteen_seconds_are()
    {
        Assert.False(EpisodeCompletion.IsFinished(Twenty - 120_000, Twenty, false));
        Assert.False(EpisodeCompletion.IsFinished(Twenty - 15_001, Twenty, false));
        Assert.True(EpisodeCompletion.IsFinished(Twenty - 15_000, Twenty, false));
    }

    [Fact]
    public void An_ended_stream_is_finished_even_without_a_known_length()
        => Assert.True(EpisodeCompletion.IsFinished(0, null, ended: true));

    [Fact]
    public void Without_a_length_or_an_end_it_is_never_finished()
        => Assert.False(EpisodeCompletion.IsFinished(5_000_000, null, ended: false));

    [Fact]
    public void Very_short_clips_do_not_count_as_finished_at_the_start()
        => Assert.False(EpisodeCompletion.IsFinished(0, 30_000, ended: false));

    private const long Film = 100 * 60_000;

    [Fact]
    public void A_film_is_finished_only_in_its_last_four_minutes()
    {
        Assert.False(MovieCompletion.IsFinished(Film - 5 * 60_000, Film, false));
        Assert.True(MovieCompletion.IsFinished(Film - 4 * 60_000, Film, false));
        Assert.True(MovieCompletion.IsFinished(Film - 60_000, Film, false));
    }

    [Fact]
    public void The_film_credits_window_never_exceeds_five_percent_of_a_short_runtime()
    {
        const long Short = 40 * 60_000; // 5% = 2 minutes
        Assert.False(MovieCompletion.IsFinished(Short - 3 * 60_000, Short, false));
        Assert.True(MovieCompletion.IsFinished(Short - 2 * 60_000, Short, false));
    }

    [Fact]
    public void A_film_without_a_length_finishes_only_when_it_ends()
    {
        Assert.False(MovieCompletion.IsFinished(Film, null, false));
        Assert.True(MovieCompletion.IsFinished(0, null, true));
    }
}
