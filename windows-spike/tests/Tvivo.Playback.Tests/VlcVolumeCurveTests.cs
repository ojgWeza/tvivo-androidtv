using Tvivo.Playback;
using Xunit;

namespace Tvivo.Playback.Tests;

public sealed class VlcVolumeCurveTests
{
    [Theory]
    [InlineData(0, 0)]
    [InlineData(100, 100)]
    public void ToPlayerVolume_PreservesEndpoints(int sliderPercent, int expectedPlayerVolume)
    {
        Assert.Equal(expectedPlayerVolume, VlcVolumeCurve.ToPlayerVolume(sliderPercent));
    }

    [Fact]
    public void ToPlayerVolume_CompensatesVlcCubicAtHalfAndQuarterVolume()
    {
        var halfSessionScalar = Math.Pow(VlcVolumeCurve.ToPlayerVolume(50) / 100d, 3d);
        var quarterSessionScalar = Math.Pow(VlcVolumeCurve.ToPlayerVolume(25) / 100d, 3d);

        Assert.InRange(halfSessionScalar, 0.4d, 0.6d);
        Assert.InRange(quarterSessionScalar, 0.2d, 0.3d);
    }

    [Fact]
    public void ToPlayerVolume_IsMonotonic()
    {
        var mapped = Enumerable.Range(0, 101)
            .Select(VlcVolumeCurve.ToPlayerVolume)
            .ToArray();

        Assert.Equal(mapped.Order(), mapped);
    }

    [Fact]
    public void ToSliderPercent_RoundTripsEverySliderPositionWithinOnePercent()
    {
        foreach (var sliderPercent in Enumerable.Range(0, 101))
        {
            var playerVolume = VlcVolumeCurve.ToPlayerVolume(sliderPercent);
            var roundTrippedPercent = VlcVolumeCurve.ToSliderPercent(playerVolume);

            Assert.InRange(Math.Abs(roundTrippedPercent - sliderPercent), 0, 1);
        }
    }

    [Theory]
    [InlineData(-1, 0)]
    [InlineData(101, 100)]
    public void ToPlayerVolume_ClampsOutsideSliderRange(int sliderPercent, int expectedPlayerVolume)
    {
        Assert.Equal(expectedPlayerVolume, VlcVolumeCurve.ToPlayerVolume(sliderPercent));
    }
}
