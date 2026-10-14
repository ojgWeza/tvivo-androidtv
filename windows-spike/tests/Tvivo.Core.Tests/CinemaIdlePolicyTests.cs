using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CinemaIdlePolicyTests
{
    [Theory]
    [InlineData(2999, false)]
    [InlineData(3000, true)]
    [InlineData(5000, true)]
    public void Hides_at_the_shared_three_second_deadline(int idleMilliseconds, bool expected) =>
        Assert.Equal(expected, CinemaIdlePolicy.ShouldHideControls(
            TimeSpan.FromMilliseconds(idleMilliseconds), isPlaying: true,
            flyoutOpen: false, dragging: false, keyboardInteractionActive: false));

    [Theory]
    [InlineData(false, false, false)]
    [InlineData(false, true, false)]
    [InlineData(false, false, true)]
    public void Keeps_controls_visible_when_playback_or_an_interaction_requires_them(
        bool isPlaying, bool flyoutOpen, bool dragging) =>
        Assert.False(CinemaIdlePolicy.ShouldHideControls(
            TimeSpan.FromSeconds(10), isPlaying, flyoutOpen, dragging, keyboardInteractionActive: false));

    [Fact]
    public void Keeps_controls_visible_during_keyboard_interaction() =>
        Assert.False(CinemaIdlePolicy.ShouldHideControls(
            TimeSpan.FromSeconds(10), isPlaying: true, flyoutOpen: false,
            dragging: false, keyboardInteractionActive: true));
}
