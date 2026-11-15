using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CompactPointerHidePolicyTests
{
    [Fact]
    public void Hides_after_idle_only_while_playing_and_unobstructed() =>
        Assert.True(CompactPointerHidePolicy.ShouldHide(
            idleElapsed: true, isPlaying: true, isBuffering: false,
            pointerOverChrome: false, gestureActive: false, flyoutOpen: false));

    [Theory]
    [InlineData(false, true, false, false, false, false)]
    [InlineData(true, false, false, false, false, false)]
    [InlineData(true, true, true, false, false, false)]
    [InlineData(true, true, false, true, false, false)]
    [InlineData(true, true, false, false, true, false)]
    [InlineData(true, true, false, false, false, true)]
    public void Keeps_pointer_visible_when_any_hide_condition_is_missing(
        bool idleElapsed, bool isPlaying, bool isBuffering, bool pointerOverChrome,
        bool gestureActive, bool flyoutOpen) =>
        Assert.False(CompactPointerHidePolicy.ShouldHide(
            idleElapsed, isPlaying, isBuffering, pointerOverChrome,
            gestureActive, flyoutOpen));
}
