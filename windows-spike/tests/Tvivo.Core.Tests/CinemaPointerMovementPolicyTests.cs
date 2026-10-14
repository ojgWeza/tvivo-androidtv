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

    [Fact]
    public void LeaveNeverWakesTheCinemaPointer()
    {
        Assert.False(CinemaPointerMovementPolicy.ShouldWakeForLeave());
    }

    [Fact]
    public void CreationGracePeriodSuppressesMovementAndInputWakeups()
    {
        Assert.True(CinemaPointerMovementPolicy.IsWithinCreationGracePeriod(0));
        Assert.True(CinemaPointerMovementPolicy.IsWithinCreationGracePeriod(399));
        Assert.False(CinemaPointerMovementPolicy.IsWithinCreationGracePeriod(400));
        Assert.False(CinemaPointerMovementPolicy.ShouldWakeForPointerMove(399, 10, 10, 20, 20));
        Assert.False(CinemaPointerMovementPolicy.ShouldWakeForPointerPoll(399, true, 10, 10, 20, 20));
        Assert.Null(CinemaPointerMovementPolicy.GetInputWakeReason(399, isWheel: false));
        Assert.Equal("click", CinemaPointerMovementPolicy.GetInputWakeReason(400, isWheel: false));
    }

    [Fact]
    public void MovementWakeRequiresTheTwoPixelDisplacementThreshold()
    {
        Assert.False(CinemaPointerMovementPolicy.ShouldWakeForPointerMove(400, 40, 50, 41, 50));
        Assert.True(CinemaPointerMovementPolicy.ShouldWakeForPointerMove(400, 40, 50, 42, 50));
        Assert.False(CinemaPointerMovementPolicy.ShouldWakeForPointerPoll(400, false, 40, 50, 41, 50));
        Assert.True(CinemaPointerMovementPolicy.ShouldWakeForPointerPoll(400, true, 40, 50, 40, 50));
    }

    [Fact]
    public void ButtonAndWheelWakeReasonsAreDistinct()
    {
        Assert.Equal("click", CinemaPointerMovementPolicy.GetInputWakeReason(500, isWheel: false));
        Assert.Equal("wheel", CinemaPointerMovementPolicy.GetInputWakeReason(500, isWheel: true));
    }
}
