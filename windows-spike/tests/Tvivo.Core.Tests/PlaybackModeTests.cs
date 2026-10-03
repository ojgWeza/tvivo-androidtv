using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackModeTests
{
    [Theory]
    [InlineData(PlaybackMode.Off, false, false, false)]
    [InlineData(PlaybackMode.Next, true, true, false)]
    [InlineData(PlaybackMode.Shuffle, true, true, true)]
    public void Mode_controls_advance_prompt_and_pick_strategy(
        PlaybackMode mode,
        bool shouldAdvance,
        bool shouldPrompt,
        bool shouldShuffle)
    {
        Assert.Equal(shouldAdvance, PlaybackModeLogic.ShouldAutoAdvance(mode));
        Assert.Equal(shouldPrompt, PlaybackModeLogic.ShouldShowNextPrompt(mode));
        Assert.Equal(shouldShuffle, PlaybackModeLogic.ShouldShuffle(mode));
    }

    [Theory]
    [InlineData(true, false, PlaybackMode.Next)]
    [InlineData(false, false, PlaybackMode.Off)]
    [InlineData(null, false, PlaybackMode.Next)]
    [InlineData(true, true, PlaybackMode.Next)]
    [InlineData(false, true, PlaybackMode.Shuffle)]
    [InlineData(null, true, PlaybackMode.Shuffle)]
    public void Legacy_autoplay_migrates_to_the_requested_kind_default(
        bool? autoplayNext,
        bool isMovie,
        PlaybackMode expected)
    {
        Assert.Equal(expected, PlaybackModeLogic.FromLegacyAutoplay(autoplayNext, isMovie));
    }

    [Fact]
    public void Legacy_settings_file_migrates_episode_and_movie_values()
    {
        var preferences = PlaybackModePreferences.FromJson("""
            { "EpisodeAutoplayNext": false, "MovieAutoplayNext": false }
            """);

        Assert.Equal(PlaybackMode.Off, preferences.EpisodeMode);
        Assert.Equal(PlaybackMode.Shuffle, preferences.MovieMode);
    }

    [Fact]
    public void Missing_legacy_values_use_the_new_per_kind_defaults()
    {
        var preferences = PlaybackModePreferences.FromJson("{}");

        Assert.Equal(PlaybackMode.Next, preferences.EpisodeMode);
        Assert.Equal(PlaybackMode.Shuffle, preferences.MovieMode);
        Assert.Equal(MinimizeToMiniMode.MiniPlayerWhilePlaying, preferences.MinimizeToMiniMode);
    }

    [Fact]
    public void Explicit_modes_round_trip_as_strings()
    {
        var preferences = new PlaybackModePreferences(PlaybackMode.Off, PlaybackMode.Shuffle);

        var json = preferences.ToJson();
        var restored = PlaybackModePreferences.FromJson(json);

        Assert.Contains("\"EpisodeMode\":\"Off\"", json, StringComparison.Ordinal);
        Assert.Equal(preferences, restored);
    }

    [Fact]
    public void Minimize_to_mini_setting_persists_when_changed()
    {
        var preferences = new PlaybackModePreferences(
            PlaybackMode.Off, PlaybackMode.Next, MinimizeToMiniMode.AlwaysMinimizeNormally);

        var restored = PlaybackModePreferences.FromJson(preferences.ToJson());

        Assert.Equal(MinimizeToMiniMode.AlwaysMinimizeNormally, restored.MinimizeToMiniMode);
    }

    [Theory]
    [InlineData(true, MinimizeToMiniMode.MiniPlayerWhilePlaying)]
    [InlineData(false, MinimizeToMiniMode.AlwaysMinimizeNormally)]
    public void Legacy_minimize_boolean_migrates_to_the_matching_mode(
        bool legacyValue, MinimizeToMiniMode expected)
    {
        var preferences = PlaybackModePreferences.FromJson(
            $$"""{ "MinimizeToMiniPlayerWhilePlaying": {{legacyValue.ToString().ToLowerInvariant()}} }""");

        Assert.Equal(expected, preferences.MinimizeToMiniMode);
        Assert.DoesNotContain("MinimizeToMiniPlayerWhilePlaying", preferences.ToJson(), StringComparison.Ordinal);
    }

    [Fact]
    public void Explicit_minimize_mode_takes_precedence_over_legacy_boolean()
    {
        var preferences = PlaybackModePreferences.FromJson("""
            { "MinimizeToMiniMode": "MiniPlayerWhilePlayingOrPaused", "MinimizeToMiniPlayerWhilePlaying": false }
            """);

        Assert.Equal(MinimizeToMiniMode.MiniPlayerWhilePlayingOrPaused, preferences.MinimizeToMiniMode);
    }
}
