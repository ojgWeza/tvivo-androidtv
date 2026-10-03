using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class NextPromptTests
{
    [Theory]
    [InlineData(3_600_000, 45_000)]
    [InlineData(600_000, 45_000)]
    [InlineData(120_000, 30_000)]
    [InlineData(60_000, 15_000)]
    [InlineData(1, 0)]
    public void EpisodeLeadIsFortyFiveSecondsCappedAtAQuarter(long duration, long expected)
        => Assert.Equal(expected, NextPrompt.LeadMilliseconds(StreamKind.Episode, duration));

    [Theory]
    [InlineData(7_200_000, 240_000)]
    [InlineData(3_600_000, 180_000)]
    [InlineData(600_000, 30_000)]
    [InlineData(300_000, 30_000)]
    [InlineData(120_000, 30_000)]
    public void MovieLeadUsesCreditsZoneWithThirtySecondFloor(long duration, long expected)
        => Assert.Equal(expected, NextPrompt.LeadMilliseconds(StreamKind.Movie, duration));

    [Theory]
    [InlineData(StreamKind.Live)]
    [InlineData(StreamKind.Series)]
    public void NonPlayableKindsHaveNoPromptLead(StreamKind kind)
        => Assert.Equal(0, NextPrompt.LeadMilliseconds(kind, 3_600_000));

    [Theory]
    [InlineData(StreamKind.Episode)]
    [InlineData(StreamKind.Movie)]
    public void UnknownOrInvalidDurationHasNoPromptLead(StreamKind kind)
    {
        Assert.Equal(0, NextPrompt.LeadMilliseconds(kind, 0));
        Assert.Equal(0, NextPrompt.LeadMilliseconds(kind, -1));
    }
}
