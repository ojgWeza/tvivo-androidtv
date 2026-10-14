using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CinemaPointerMovementPolicyTests
{
    [Fact]
    public void HasMoved_UsesTheHidePositionAsItsBaseline()
    {
        Assert.False(CinemaPointerMovementPolicy.HasMoved(400, 300, 401, 300));
        Assert.True(CinemaPointerMovementPolicy.HasMoved(400, 300, 402, 300));
        Assert.True(CinemaPointerMovementPolicy.HasMoved(400, 300, 401, 302));
    }

    [Fact]
    public void HasMoved_UsesSquaredDistanceAndSupportsNegativeMonitorCoordinates()
    {
        Assert.False(CinemaPointerMovementPolicy.HasMoved(-1920, -200, -1919, -199));
        Assert.True(CinemaPointerMovementPolicy.HasMoved(-1920, -200, -1919, -198));
    }

    [Fact]
    public void HasMoved_UsesLongArithmeticForExtremeCoordinates()
    {
        Assert.True(CinemaPointerMovementPolicy.HasMoved(int.MinValue, 0, int.MaxValue, 0));
    }
}
