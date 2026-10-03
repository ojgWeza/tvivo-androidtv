using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlayerPresentationPolicyTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(true, true)]
    public void Window_mode_change_expands_first_only_while_window_mini(bool isWindowMini, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.ShouldExpandBeforeWindowModeChange(isWindowMini));

    [Theory]
    [InlineData(false, false, false, 480, 270, false)]
    [InlineData(true, false, false, 480, 270, true)]
    [InlineData(false, true, false, 480, 270, true)]
    [InlineData(false, false, true, 480, 270, true)]
    [InlineData(false, false, false, 1900, 270, true)]
    [InlineData(false, false, false, 480, 1050, true)]
    public void Window_mini_exit_safety_detects_invalid_native_states(
        bool maximized, bool fullscreen, bool iconic, double width, double height, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.RequiresWindowMiniExit(
            maximized, fullscreen, iconic, width, height, 1920, 1080));

    [Theory]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, true)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, false, true, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlayingOrPaused, false, true, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, true)]
    [InlineData(MinimizeToMiniMode.AlwaysMinimizeNormally, true, false, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, true, true, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, false, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, true, false, StreamKind.Movie, MiniPlayerEngineKind.Native, false, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlayingOrPaused, true, false, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, true, false, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, true, false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, true, false)]
    [InlineData(MinimizeToMiniMode.MiniPlayerWhilePlaying, true, false, true, false, StreamKind.Series, MiniPlayerEngineKind.LibVlc, false, false, false)]
    public void Minimize_enters_window_mini_only_when_all_gates_pass(
        MinimizeToMiniMode mode, bool playing, bool paused, bool sessionAvailable, bool ended,
        StreamKind kind, MiniPlayerEngineKind engine, bool alreadyMini, bool shiftHeld, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.ShouldMinimizeBecomeMini(
            mode, playing, paused, sessionAvailable, ended, kind, engine, alreadyMini, shiftHeld));

    [Theory]
    [InlineData(true, StreamKind.Live, MiniPlayerEngineKind.LibVlc, false, false, true)]
    [InlineData(true, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, true)]
    [InlineData(true, StreamKind.Episode, MiniPlayerEngineKind.LibVlc, false, false, true)]
    [InlineData(false, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, false, false)]
    [InlineData(true, StreamKind.Movie, MiniPlayerEngineKind.Native, false, false, false)]
    [InlineData(true, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, true, false, false)]
    [InlineData(true, StreamKind.Movie, MiniPlayerEngineKind.LibVlc, false, true, false)]
    [InlineData(true, StreamKind.Series, MiniPlayerEngineKind.LibVlc, false, false, false)]
    public void Cinema_button_enters_window_mini_only_for_an_active_supported_session(
        bool active, StreamKind kind, MiniPlayerEngineKind engine, bool alreadyMini, bool ended, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.ShouldEnterMiniFromCinemaButton(
            active, kind, engine, alreadyMini, ended));

    [Fact]
    public void Window_mini_geometry_defaults_to_bottom_right_and_clamps_to_work_area()
    {
        Assert.Equal(new WindowMiniGeometry(1440, 810, 480, 270), WindowMiniGeometry.BottomRight(1920, 1080));
        Assert.Equal(new WindowMiniGeometry(0, 0, 300, 160), new WindowMiniGeometry(900, 700, 480, 270).Clamp(300, 160));
    }

    [Theory]
    [InlineData(true, false, false, false, false, false, true)]
    [InlineData(true, false, false, false, false, true, false)]
    [InlineData(false, false, false, false, false, true, true)]
    [InlineData(true, true, false, false, false, true, true)]
    [InlineData(true, false, true, false, false, true, true)]
    [InlineData(true, false, false, true, false, true, true)]
    [InlineData(true, false, false, false, true, true, true)]
    public void Compact_overlay_stays_visible_until_idle_playback_has_no_active_use(
        bool isPlaying, bool isBuffering, bool pointerOverControls, bool isDragging, bool isResizing,
        bool idleElapsed, bool expected) =>
        Assert.Equal(expected, CompactOverlayPolicy.ShouldShow(
            isPlaying, isBuffering, pointerOverControls, isDragging, isResizing, idleElapsed));

    [Theory]
    [InlineData(PlayerPresentation.Full, PlayerPageKind.Player, true)]
    [InlineData(PlayerPresentation.Full, PlayerPageKind.Catalog, false)]
    [InlineData(PlayerPresentation.Cinema, PlayerPageKind.Player, true)]
    [InlineData(PlayerPresentation.Cinema, PlayerPageKind.Other, false)]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Catalog, true)]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Other, true)]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Player, false)]
    public void Coexistence_is_limited_to_the_intended_page(
        PlayerPresentation presentation, PlayerPageKind page, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.CanCoexist(presentation, page));

    [Theory]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Player, PlayerPresentationHost.WindowMini, true)]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Catalog, PlayerPresentationHost.WindowMini, true)]
    [InlineData(PlayerPresentation.Full, PlayerPageKind.Player, PlayerPresentationHost.WindowMini, false)]
    [InlineData(PlayerPresentation.Compact, PlayerPageKind.Catalog, PlayerPresentationHost.MainWindow, true)]
    public void Window_mini_host_coexists_only_with_compact_presentation(
        PlayerPresentation presentation, PlayerPageKind page, PlayerPresentationHost host, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.CanCoexist(presentation, page, host));

    [Theory]
    [InlineData(PlayerPresentationHost.MainWindow, true)]
    [InlineData(PlayerPresentationHost.WindowMini, false)]
    public void Window_mini_layout_ignores_in_window_card_geometry(PlayerPresentationHost host, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.UsesInWindowCompactGeometry(host));

    [Theory]
    [InlineData(PlayerPresentation.Full, true)]
    [InlineData(PlayerPresentation.Cinema, true)]
    [InlineData(PlayerPresentation.Compact, false)]
    public void Leaving_player_stops_only_outside_compact(
        PlayerPresentation presentation, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.ShouldStopPlaybackOnPageChange(
            presentation, PlayerPageKind.Player, PlayerPageKind.Catalog));

    [Theory]
    [InlineData(PlayerPresentation.Full, true, true, true)]
    [InlineData(PlayerPresentation.Cinema, true, true, true)]
    [InlineData(PlayerPresentation.Compact, true, true, true)]
    [InlineData(PlayerPresentation.Compact, false, true, false)]
    [InlineData(PlayerPresentation.Compact, true, false, false)]
    public void Progress_requires_a_ready_resume_eligible_session(
        PlayerPresentation presentation, bool sessionReady, bool eligible, bool expected) =>
        Assert.Equal(expected, PlayerPresentationPolicy.ShouldSaveProgress(presentation, sessionReady, eligible));

    [Fact]
    public void Geometry_is_clamped_to_size_limits_and_available_bounds()
    {
        var geometry = new CompactPlayerGeometry(900, 700, 1000, 700).Clamp(1000, 800);

        Assert.Equal(new CompactPlayerGeometry(400, 320, 600, 480), geometry);
    }

    [Fact]
    public void Geometry_uses_available_bounds_when_window_is_smaller_than_minimum()
    {
        var geometry = new CompactPlayerGeometry(-5, -10, 480, 270).Clamp(300, 160);

        Assert.Equal(new CompactPlayerGeometry(0, 0, 180, 96), geometry);
    }

    [Fact]
    public void Geometry_replaces_non_finite_values_with_safe_defaults()
    {
        var geometry = new CompactPlayerGeometry(double.NaN, double.PositiveInfinity, double.NaN, double.NaN)
            .Clamp(1200, 900);

        Assert.Equal(new CompactPlayerGeometry(0, 0, 480, 270), geometry);
    }

    [Theory]
    [InlineData(0, 0)]
    [InlineData(double.NaN, 900)]
    [InlineData(500, 300)]
    [InlineData(1200, 250)]
    public void Geometry_rejects_unmeasured_or_too_small_display_bounds(double availableWidth, double availableHeight)
    {
        var logical = new CompactPlayerGeometry(700, 400, 480, 270);

        Assert.False(logical.TryClamp(availableWidth, availableHeight, out var displayed));
        Assert.Equal(logical, displayed);
        Assert.Equal(new CompactPlayerGeometry(700, 400, 480, 270), logical);
    }

    [Fact]
    public void Geometry_display_clamp_does_not_replace_persisted_logical_geometry()
    {
        var logical = new CompactPlayerGeometry(900, 700, 1000, 700);

        Assert.True(logical.TryClamp(1000, 800, out var displayed));

        Assert.Equal(new CompactPlayerGeometry(400, 320, 600, 480), displayed);
        Assert.Equal(new CompactPlayerGeometry(900, 700, 1000, 700), logical);
    }

    [Fact]
    public void Resize_from_left_and_top_keeps_the_opposite_corner_anchored()
    {
        var resized = new CompactPlayerGeometry(200, 150, 480, 270)
            .ResizeFromEdges(CompactResizeEdges.Left | CompactResizeEdges.Top, -80, -40, 1200, 900);

        Assert.Equal(new CompactPlayerGeometry(120, 110, 560, 310), resized);
        Assert.Equal(680, resized.Left + resized.Width);
        Assert.Equal(420, resized.Top + resized.Height);
    }

    [Fact]
    public void Resize_edges_clamp_to_minimum_and_available_bounds()
    {
        var resized = new CompactPlayerGeometry(600, 500, 480, 270)
            .ResizeFromEdges(CompactResizeEdges.Left | CompactResizeEdges.Top, 500, 400, 1200, 900);

        Assert.Equal(320, resized.Width);
        Assert.Equal(180, resized.Height);
        Assert.Equal(1080, resized.Left + resized.Width);
        Assert.Equal(770, resized.Top + resized.Height);
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, false)]
    public void Mute_state_toggles_without_changing_volume(bool startMuted, bool expectedMuted) =>
        Assert.Equal(expectedMuted, new PlaybackMuteState(startMuted).Toggle().IsMuted);
}
