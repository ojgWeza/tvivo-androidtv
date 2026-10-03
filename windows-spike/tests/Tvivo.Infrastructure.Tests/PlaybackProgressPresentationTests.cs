using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class PlaybackProgressPresentationTests
{
    [Fact]
    public void Finished_progress_is_a_full_visible_watched_bar()
    {
        var result = PlaybackProgressPresentation.For(
            new PlaybackProgress("movie", PlaybackProgressState.Finished, 0, null));

        Assert.True(result.IsVisible);
        Assert.Equal(100, result.Value);
        Assert.Equal("Watched", result.AutomationName);
    }

    [Fact]
    public void In_progress_with_duration_shows_percentage_and_accessible_text()
    {
        var result = PlaybackProgressPresentation.For(
            new PlaybackProgress("movie", PlaybackProgressState.InProgress, 42_000, 100_000));

        Assert.True(result.IsVisible);
        Assert.Equal(42, result.Value);
        Assert.Equal("Watched 42%", result.AutomationName);
    }

    [Theory]
    [InlineData(null)]
    [InlineData(0L)]
    public void Missing_or_unknown_duration_hides_the_bar(long? duration)
    {
        var result = PlaybackProgressPresentation.For(
            new PlaybackProgress("movie", PlaybackProgressState.InProgress, 42_000, duration));

        Assert.False(result.IsVisible);
        Assert.Equal(0, result.Value);
        Assert.Equal(string.Empty, result.AutomationName);
    }

    [Fact]
    public void Unwatched_progress_is_hidden()
    {
        var result = PlaybackProgressPresentation.For(
            new PlaybackProgress("movie", PlaybackProgressState.Unwatched, 0, 100_000));

        Assert.False(result.IsVisible);
        Assert.Equal(0, result.Value);
        Assert.Equal(string.Empty, result.AutomationName);
    }
}
