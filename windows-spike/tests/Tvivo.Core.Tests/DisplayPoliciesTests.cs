using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class DisplayPoliciesTests
{
    [Theory]
    [InlineData(StreamKind.Movie, null, true)]
    [InlineData(StreamKind.Episode, null, true)]
    [InlineData(StreamKind.Movie, 1L, true)]
    [InlineData(StreamKind.Movie, 1_400_000_000L, false)]
    [InlineData(StreamKind.Live, null, false)]
    public void Open_probe_ignores_background_recheck_interval(StreamKind kind, long? exact, bool expected) =>
        Assert.Equal(expected, FileSizeProbePolicy.ShouldProbeOnOpen(kind, exact));

    [Fact]
    public void Exact_size_precedes_approximation()
    {
        Assert.Equal("~1.4 GB", FileSizeFormatter.FormatPreferred(null, 1_400_000_000));
        Assert.Equal("650 MB", FileSizeFormatter.FormatPreferred(650_000_000, 1_400_000_000));
        Assert.Equal("Approximate file size: 1.4 GB", FileSizeFormatter.AutomationSize(null, 1_400_000_000));
    }

    [Theory]
    [InlineData("8000", "00:30:00", 1_800_000_000L)]
    [InlineData("8000", "1800", 1_800_000_000L)]
    [InlineData("127", "1800", null)]
    [InlineData("80001", "1800", null)]
    [InlineData("8000", "59", null)]
    [InlineData("8000", "08:00:01", null)]
    [InlineData("8000 kbps", "1800", null)]
    public void Estimate_requires_credible_total_bitrate_and_duration(string rate, string duration, long? expected) =>
        Assert.Equal(expected, FileSizeEstimatePolicy.Estimate(rate, duration));

    [Theory]
    [InlineData(StreamKind.Movie, null, false, true)]
    [InlineData(StreamKind.Episode, null, false, true)]
    [InlineData(StreamKind.Series, null, false, false)]
    [InlineData(StreamKind.Live, null, false, false)]
    [InlineData(StreamKind.Movie, 1400000000L, false, false)]
    [InlineData(StreamKind.Episode, null, true, false)]
    public void File_size_probe_policy_limits_kind_and_rechecks_missing(StreamKind kind, long? sizeBytes,
        bool checkedRecently, bool expected)
    {
        var now = DateTimeOffset.UtcNow;
        var checkedAt = checkedRecently ? now.AddDays(-6) : (DateTimeOffset?)null;
        Assert.Equal(expected, FileSizeProbePolicy.ShouldProbe(kind, sizeBytes, checkedAt, now));
    }

    [Theory]
    [InlineData(StreamKind.Movie, true)]
    [InlineData(StreamKind.Episode, true)]
    [InlineData(StreamKind.Series, false)]
    [InlineData(StreamKind.Live, false)]
    public void File_size_is_displayed_only_for_movies_and_episodes(StreamKind kind, bool expected) =>
        Assert.Equal(expected, FileSizeDisplayPolicy.ShouldShow(kind));

    [Theory]
    [InlineData(false, true, true, false)]
    [InlineData(true, false, true, false)]
    [InlineData(true, true, false, false)]
    [InlineData(true, true, true, true)]
    public void Spotlight_ring_requires_active_page_recent_key_and_keyboard_focus(
        bool active, bool keyPressed, bool keyboardFocus, bool expected) =>
        Assert.Equal(expected, SpotlightFocusPolicy.ShowRing(active, keyPressed, keyboardFocus));

    [Theory]
    [InlineData("Series", "s01e01 Series s01e01 Episode 1", 0, "1. Episode 1")]
    [InlineData("Series", "Series - S02E03 Pilot", 1, "2. Pilot")]
    [InlineData("Series", "S01E01", 0, "1. Episode 1")]
    [InlineData("Series", "The original title", 2, "3. The original title")]
    [InlineData("Series", "1", 0, "1. Episode 1")]
    [InlineData("Series", "", 0, "1. Episode 1")]
    [InlineData("Series", "1. Pilot", 0, "1. Pilot")]
    [InlineData("Series", "E01 - Pilot", 0, "1. Pilot")]
    [InlineData("Series", "Episode 1", 0, "1. Episode 1")]
    public void Episode_names_keep_provider_words_without_repeated_prefixes(
        string series, string provider, int index, string expected) =>
        Assert.Equal(expected, EpisodeDisplayNamePolicy.Format(series, 1, 1, provider, index));

    [Theory]
    [InlineData("1400000000", "1.4 GB")]
    [InlineData("650000000", "650 MB")]
    [InlineData("1.4 GB", "1.4 GB")]
    [InlineData("650 MB", "650 MB")]
    public void File_sizes_parse_and_format(string raw, string expected) =>
        Assert.Equal(expected, FileSizeFormatter.Format(FileSizeFormatter.ParseBytes(raw)));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("unknown")]
    [InlineData("-1")]
    public void Unknown_sizes_stay_hidden(string? raw) =>
        Assert.Null(FileSizeFormatter.Format(FileSizeFormatter.ParseBytes(raw)));

    [Fact]
    public void File_size_availability_rechecks_missing_values_after_seven_days_only()
    {
        var now = DateTimeOffset.Parse("2026-10-21T12:00:00Z");
        Assert.Equal(FileSizeAvailability.NeverChecked, FileSizeAvailabilityPolicy.Classify(null, null));
        Assert.True(FileSizeAvailabilityPolicy.ShouldCheck(null, null, now));
        Assert.Equal(FileSizeAvailability.CheckedAbsent,
            FileSizeAvailabilityPolicy.Classify(null, now.AddDays(-6)));
        Assert.False(FileSizeAvailabilityPolicy.ShouldCheck(null, now.AddDays(-6), now));
        Assert.True(FileSizeAvailabilityPolicy.ShouldCheck(null, now.AddDays(-7), now));
        Assert.Equal(FileSizeAvailability.Known,
            FileSizeAvailabilityPolicy.Classify(1_400_000_000, now.AddDays(-30)));
        Assert.False(FileSizeAvailabilityPolicy.ShouldCheck(1_400_000_000, now.AddDays(-30), now));
    }
}
