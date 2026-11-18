using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackSpeedPolicyTests
{
    [Fact]
    public void Speeds_ContainsSupportedChoicesInOrder()
    {
        Assert.Equal(new[] { 0.5, 0.75, 1d, 1.25, 1.5, 1.75, 2d }, PlaybackSpeedPolicy.Speeds);
    }

    [Theory]
    [InlineData(0.5, true)]
    [InlineData(1, true)]
    [InlineData(2, true)]
    [InlineData(0.25, false)]
    [InlineData(3, false)]
    public void IsSupported_ValidatesChoices(double speed, bool expected) =>
        Assert.Equal(expected, PlaybackSpeedPolicy.IsSupported(speed));

    [Fact]
    public void Label_FormatsAndRejectsUnsupportedSpeeds()
    {
        Assert.Equal("0.75x", PlaybackSpeedPolicy.Label(0.75));
        Assert.Equal("1x", PlaybackSpeedPolicy.Label(1));
        Assert.Throws<ArgumentOutOfRangeException>(() => PlaybackSpeedPolicy.Label(1.1));
    }
}
