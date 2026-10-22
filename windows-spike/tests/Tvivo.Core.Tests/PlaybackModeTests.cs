using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackModeTests
{
    [Theory]
    [InlineData(PlaybackMode.Next, PlaybackMode.Shuffle)]
    [InlineData(PlaybackMode.Shuffle, PlaybackMode.Next)]
    public void Clicking_the_other_mode_then_itself_turns_the_mode_off(
        PlaybackMode current,
        PlaybackMode clicked)
    {
        var switched = PlaybackModeTransitions.Next(current, clicked);

        Assert.Equal(clicked, switched);
        Assert.Equal(PlaybackMode.Off, PlaybackModeTransitions.Next(switched, clicked));
    }

    [Theory]
    [InlineData(PlaybackMode.Next)]
    [InlineData(PlaybackMode.Shuffle)]
    public void Clicking_the_active_mode_turns_playback_mode_off(PlaybackMode mode)
    {
        Assert.Equal(PlaybackMode.Off, PlaybackModeTransitions.Next(mode, mode));
    }

    [Theory]
    [InlineData(PlaybackMode.Next)]
    [InlineData(PlaybackMode.Shuffle)]
    public void Clicking_a_mode_while_off_activates_it(PlaybackMode clicked)
    {
        Assert.Equal(clicked, PlaybackModeTransitions.Next(PlaybackMode.Off, clicked));
    }

    [Theory]
    [InlineData(PlaybackMode.Off)]
    [InlineData((PlaybackMode)999)]
    public void Off_stays_off_when_off_or_an_unknown_mode_is_clicked(PlaybackMode clicked)
    {
        Assert.Equal(PlaybackMode.Off, PlaybackModeTransitions.Next(PlaybackMode.Off, clicked));
    }

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
        Assert.Equal(PlaybackRetryPolicy.DefaultAttempts, preferences.PlaybackAttempts);
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
    [InlineData(0, 1)]
    [InlineData(4, 4)]
    [InlineData(12, 10)]
    public void Playback_attempt_setting_is_clamped_when_loaded(int configured, int expected)
    {
        var preferences = PlaybackModePreferences.FromJson($$"""{ "PlaybackAttempts": {{configured}} }""");

        Assert.Equal(expected, preferences.PlaybackAttempts);
    }

    [Theory]
    [InlineData("\"five\"")]
    [InlineData("null")]
    public void Invalid_playback_attempt_setting_uses_default(string configured)
    {
        var preferences = PlaybackModePreferences.FromJson($$"""{ "PlaybackAttempts": {{configured}} }""");

        Assert.Equal(PlaybackRetryPolicy.DefaultAttempts, preferences.PlaybackAttempts);
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

    [Fact]
    public void New_player_settings_default_and_round_trip()
    {
        Assert.Equal(CinemaEscapeSetting.NormalPlayer, PlaybackModePreferences.FromJson("{}").CinemaEscape);
        Assert.Equal(SleepTimerItemChangeSetting.Clear, PlaybackModePreferences.FromJson("{}").SleepTimerOnItemChange);
        var configured = new PlaybackModePreferences(PlaybackMode.Next, PlaybackMode.Shuffle,
            CinemaEscape: CinemaEscapeSetting.MiniPlayer, SleepTimerOnItemChange: SleepTimerItemChangeSetting.Keep);
        Assert.Equal(configured, PlaybackModePreferences.FromJson(configured.ToJson()));
    }
}
