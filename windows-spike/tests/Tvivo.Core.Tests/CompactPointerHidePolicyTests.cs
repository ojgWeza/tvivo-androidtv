using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CompactPointerHidePolicyTests
{
    [Fact]
    public void Hides_after_idle_only_while_playing_and_unobstructed() =>
        Assert.True(CompactPointerHidePolicy.ShouldHide(
            idleElapsed: true, isPlaying: true, isBuffering: false,
            pointerOverChrome: false, gestureActive: false, flyoutOpen: false, windowActive: true));

    [Theory]
    [InlineData(false, true, false, false, false, false, true)]
    [InlineData(true, false, false, false, false, false, true)]
    [InlineData(true, true, true, false, false, false, true)]
    [InlineData(true, true, false, true, false, false, true)]
    [InlineData(true, true, false, false, true, false, true)]
    [InlineData(true, true, false, false, false, true, true)]
    [InlineData(true, true, false, false, false, false, false)]
    public void Keeps_pointer_visible_when_any_hide_condition_is_missing(
        bool idleElapsed, bool isPlaying, bool isBuffering, bool pointerOverChrome,
        bool gestureActive, bool flyoutOpen, bool windowActive) =>
        Assert.False(CompactPointerHidePolicy.ShouldHide(
            idleElapsed, isPlaying, isBuffering, pointerOverChrome,
            gestureActive, flyoutOpen, windowActive));
}
