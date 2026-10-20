using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SpotlightStripStateTests
{
    [Fact]
    public void EntryOpensFirstItemAndMovesClampWithoutWrapping()
    {
        var state = SpotlightStripState.Create(12);
        Assert.Equal(0, state.OpenIndex);
        Assert.Equal(0, state.Move(-1).OpenIndex);
        Assert.Equal(11, state.Move(12).OpenIndex);
        Assert.Equal(11, state.Move(11).Move(1).OpenIndex);
    }

    [Fact]
    public void DwellRequiresDifferentTargetAndFullDelay()
    {
        Assert.False(SpotlightStripState.ShouldOpenAfterDwell(0, 1, TimeSpan.FromMilliseconds(349)));
        Assert.True(SpotlightStripState.ShouldOpenAfterDwell(0, 1, TimeSpan.FromMilliseconds(350)));
        Assert.False(SpotlightStripState.ShouldOpenAfterDwell(1, 1, TimeSpan.FromSeconds(1)));
    }

    [Theory]
    [InlineData(800)]
    [InlineData(1280)]
    [InlineData(1920)]
    [InlineData(3840)]
    public void EightSpotlightItemsFillAvailableWidth(double viewport)
    {
        const int itemCount = 8;
        const double gap = 8;
        const double collapsedWidth = 64;
        var openWidth = SpotlightStripState.OpenWidth(viewport, itemCount, collapsedWidth, gap);
        var totalWidth = openWidth + (itemCount - 1) * collapsedWidth + (itemCount - 1) * gap;

        Assert.InRange(Math.Abs(totalWidth - viewport), 0, 1);
        Assert.True(openWidth >= collapsedWidth);
    }

    [Fact]
    public void ScrollTargetMovesOnlyWhenItemIsOutsideViewport()
    {
        Assert.Equal(100, SpotlightStripState.ScrollIntoViewTarget(100, 64, 100, 500));
        Assert.Equal(40, SpotlightStripState.ScrollIntoViewTarget(40, 64, 100, 500));
        Assert.Equal(164, SpotlightStripState.ScrollIntoViewTarget(600, 64, 100, 500));
    }

    [Fact]
    public void AccessibilityTextAndTouchSemanticsAreStable()
    {
        Assert.Equal("Film, expanded, 4 of 12", SpotlightStripState.Announcement("Film", 3, 12));
        Assert.Equal("Film, Movie, 4 of 12, collapsed", SpotlightStripState.AutomationName("Film", true, 3, 12, false));
        var state = SpotlightStripState.Create(2);
        Assert.False(state.TouchTapActivates(1));
        Assert.True(state.Open(1).TouchTapActivates(1));
    }
}
