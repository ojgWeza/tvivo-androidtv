using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class TrackSelectionPolicyTests
{
    [Fact]
    public void Explicit_subtitle_off_wins_over_every_language_rule()
    {
        var tracks = Subtitles(("eng", "en"), ("ara", "ar"));

        Assert.Null(TrackSelectionPolicy.PickSubtitle(
            tracks,
            "en",
            explicitOff: true,
            uiCultureTwoLetterCode: "ar"));
    }

    [Fact]
    public void Preferred_language_accepts_iso_639_2_code()
    {
        var tracks = Subtitles(("arabic", "ar"), ("english", "en"));

        Assert.Equal("english", TrackSelectionPolicy.PickSubtitle(tracks, "eng", false, "ar"));
    }

    [Fact]
    public void Ui_culture_is_used_when_preferred_language_has_no_match()
    {
        var tracks = Subtitles(("english", "eng"), ("arabic", "ara"));

        Assert.Equal("arabic", TrackSelectionPolicy.PickSubtitle(tracks, "de", false, "ar"));
    }

    [Fact]
    public void First_subtitle_is_used_when_no_language_matches()
    {
        var tracks = Subtitles(("first", null), ("second", null));

        Assert.Equal("first", TrackSelectionPolicy.PickSubtitle(tracks, "de", false, "fr"));
    }

    [Fact]
    public void No_tracks_means_no_subtitle_and_no_audio_change()
    {
        var tracks = Array.Empty<PlaybackTrack>();

        Assert.Null(TrackSelectionPolicy.PickSubtitle(tracks, "en", false, "en"));
        Assert.Null(TrackSelectionPolicy.PickAudio(tracks, "en", "en"));
    }

    [Fact]
    public void Duplicate_languages_prefer_a_non_forced_track()
    {
        var tracks = Subtitles(("forced", "eng"), ("full", "en"));
        tracks[0] = tracks[0] with { DisplayName = "English (Forced)" };
        tracks[1] = tracks[1] with { DisplayName = "English (Full)" };

        Assert.Equal("full", TrackSelectionPolicy.PickSubtitle(tracks, "en", false, null));
    }

    [Fact]
    public void Duplicate_languages_with_only_secondary_labels_keep_source_order()
    {
        var tracks = Subtitles(("commentary", "en"), ("sdh", "eng"));
        tracks[0] = tracks[0] with { DisplayName = "English Commentary" };
        tracks[1] = tracks[1] with { DisplayName = "English SDH" };

        Assert.Equal("commentary", TrackSelectionPolicy.PickSubtitle(tracks, "en", false, null));
    }

    [Fact]
    public void Unmatched_audio_language_returns_null_to_keep_engine_selection()
    {
        var tracks = new[]
        {
            new PlaybackTrack(TrackKind.Audio, "english", "English", "en", true),
        };

        Assert.Null(TrackSelectionPolicy.PickAudio(tracks, "de", "fr"));
    }

    private static PlaybackTrack[] Subtitles(params (string Key, string? Language)[] tracks) =>
        tracks.Select(track => new PlaybackTrack(
            TrackKind.Subtitle,
            track.Key,
            track.Key,
            track.Language,
            false)).ToArray();
}
