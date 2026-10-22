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

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(7)]
    [InlineData(8)]
    [InlineData(70)]
    public void Viewport_state_handles_all_page_sizes(int count)
    {
        var ids = Enumerable.Range(0, count).Select(i => $"item-{i}").ToArray();
        var state = SpotlightViewportState.Create(ids, 1200);
        Assert.Equal(count == 0 ? -1 : 0, state.SelectedIndex);
        Assert.Null(state.OpenIndex);
        Assert.Equal((1200 - 7 * 8) / 7.3, state.Layout.CollapsedWidth, 6);
        Assert.Equal(ids, state.ItemIds);
        Assert.True(state.Layout.ItemWidths.Count <= 10);
        if (count > 0) Assert.Equal(ids[0], state.Focus(0).SelectedItemId);
    }

    [Fact]
    public void Interior_open_has_exact_peeks_gap_sum_and_virtual_window()
    {
        var state = SpotlightViewportState.Create(Enumerable.Range(0, 70).Select(i => $"id-{i}").ToArray(), 1200)
            .Focus(35);
        var layout = state.Layout;
        var c = layout.CollapsedWidth;
        Assert.Equal(0.15 * c, layout.PredecessorPeekWidth, 6);
        Assert.Equal(0.15 * c, layout.NextPeekWidth, 6);
        Assert.Equal(3, layout.FollowingSliverCount);
        Assert.Equal(1200, layout.PredecessorPeekWidth + layout.OpenWidth +
            3 * c + layout.NextPeekWidth + 5 * 8, 6);
        Assert.Equal(35 * (c + 8) - layout.PredecessorPeekWidth - 8, layout.ScrollTarget, 6);
        Assert.Contains(layout.ItemWidths, item => item.Index == 35 && item.Width == layout.OpenWidth);
        Assert.True(layout.ItemWidths.Count <= 8);
    }

    [Fact]
    public void Boundaries_release_missing_peeks_into_open_card()
    {
        var state = SpotlightViewportState.Create(Enumerable.Range(0, 70).Select(i => $"id-{i}").ToArray(), 1200);
        var first = state.Focus(0).Layout;
        var last = state.Focus(69).Layout;
        Assert.Equal(0, first.PredecessorPeekWidth);
        Assert.Equal(0, first.ScrollTarget);
        Assert.Equal(1200, first.OpenWidth + 3 * first.CollapsedWidth + first.NextPeekWidth + 4 * 8, 6);
        Assert.Equal(0, last.NextPeekWidth);
        Assert.Equal(0, last.FollowingSliverCount);
        Assert.Equal(1200, last.PredecessorPeekWidth + last.OpenWidth + 8, 6);
    }

    [Fact]
    public void Focus_move_activation_restore_and_resize_keep_ids_stable()
    {
        var ids = Enumerable.Range(0, 70).Select(i => $"id-{i}").ToArray();
        var idle = SpotlightViewportState.Create(ids, 1200);
        Assert.Null(idle.HoverAfterDwell(20, TimeSpan.FromMilliseconds(349)).OpenIndex);
        var focused = idle.HoverAfterDwell(20, SpotlightStripState.DwellDuration).Focus(20);
        var moved = focused.Move(1).Activate();
        Assert.Equal("id-21", moved.ActivatedItemId);
        Assert.Equal(ids, moved.ItemIds);
        var returned = moved.ReturnFromPlayer("id-21", moved.Layout.ScrollTarget + 12);
        Assert.Equal(moved.Layout.ScrollTarget + 12, returned.Layout.ScrollTarget, 6);
        Assert.Equal(SpotlightStripInputMode.Returned, returned.InputMode);
        Assert.Equal(returned.Layout.ScrollTarget, returned.Focus(21).Move(0).Layout.ScrollTarget, 6);
        Assert.Equal("id-22", returned.Move(1).SelectedItemId);
        Assert.NotEqual(returned.Layout.ScrollTarget, returned.Move(1).Layout.ScrollTarget);
        Assert.Same(returned, returned.ReturnFromPlayer("id-20", 0));
        var resized = returned.Resize(500, reducedMotion: true);
        Assert.Equal("id-21", resized.SelectedItemId);
        Assert.Equal(TimeSpan.Zero, resized.Layout.SlideDuration);
        Assert.True(resized.Layout.CollapsedWidth >= 44);
        Assert.True(resized.Layout.OpenWidth >= 300);
        Assert.True(resized.Layout.FollowingSliverCount < 3);
    }
}
