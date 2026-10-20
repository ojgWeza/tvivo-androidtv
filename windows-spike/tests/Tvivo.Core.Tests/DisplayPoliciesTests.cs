using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class DisplayPoliciesTests
{
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
}
