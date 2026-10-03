namespace Tvivo.Core;

public enum PlayerPresentation
{
    Full,
    Cinema,
    Compact,
}

public enum PlayerPageKind
{
    Player,
    Catalog,
    Other,
}

public enum PlayerPresentationHost { MainWindow, WindowMini }

public enum MiniPlayerEngineKind { LibVlc, Native }

public readonly record struct WindowMiniGeometry(double Left, double Top, double Width, double Height)
{
    public static WindowMiniGeometry Default => new(0, 0, 480, 270);

    public WindowMiniGeometry Clamp(double workAreaWidth, double workAreaHeight)
    {
        workAreaWidth = Math.Max(1, double.IsFinite(workAreaWidth) ? workAreaWidth : 1);
        workAreaHeight = Math.Max(1, double.IsFinite(workAreaHeight) ? workAreaHeight : 1);
        var width = Math.Clamp(double.IsFinite(Width) && Width > 0 ? Width : 480, 1, workAreaWidth);
        var height = Math.Clamp(double.IsFinite(Height) && Height > 0 ? Height : 270, 1, workAreaHeight);
        return new WindowMiniGeometry(
            Math.Clamp(double.IsFinite(Left) ? Left : workAreaWidth - width, 0, workAreaWidth - width),
            Math.Clamp(double.IsFinite(Top) ? Top : workAreaHeight - height, 0, workAreaHeight - height),
            width,
            height);
    }

    public static WindowMiniGeometry BottomRight(double workAreaWidth, double workAreaHeight) =>
        (Default with { Left = Math.Max(0, workAreaWidth - 480), Top = Math.Max(0, workAreaHeight - 270) })
            .Clamp(workAreaWidth, workAreaHeight);
}

public readonly record struct CompactPlayerGeometry(double Left, double Top, double Width, double Height)
{
    public static CompactPlayerGeometry Default => new(0, 0, 480, 270);

    // Left and Top are absolute DIPs from the ContentSurfaceHost's top-left corner.
    // Persisted 0,0 therefore means top-left; it is not a bottom/right anchor.
    public bool TryClamp(double availableWidth, double availableHeight, out CompactPlayerGeometry clamped)
    {
        clamped = this;
        const double minimumWidth = 320;
        const double minimumHeight = 180;
        if (!double.IsFinite(availableWidth) || !double.IsFinite(availableHeight) ||
            availableWidth < minimumWidth / 0.6 || availableHeight < minimumHeight / 0.6)
            return false;

        clamped = Clamp(availableWidth, availableHeight);
        return true;
    }

    public CompactPlayerGeometry Clamp(double availableWidth, double availableHeight)
    {
        availableWidth = Math.Max(1, availableWidth);
        availableHeight = Math.Max(1, availableHeight);
        var maxWidth = availableWidth * 0.6;
        var maxHeight = availableHeight * 0.6;
        var width = Math.Clamp(double.IsFinite(Width) ? Width : 480, Math.Min(320, maxWidth), maxWidth);
        var height = Math.Clamp(double.IsFinite(Height) ? Height : 270, Math.Min(180, maxHeight), maxHeight);
        return new CompactPlayerGeometry(
            Math.Clamp(double.IsFinite(Left) ? Left : 0, 0, Math.Max(0, availableWidth - width)),
            Math.Clamp(double.IsFinite(Top) ? Top : 0, 0, Math.Max(0, availableHeight - height)),
            width,
            height);
    }

    public CompactPlayerGeometry ResizeFromEdges(
        CompactResizeEdges edges,
        double deltaX,
        double deltaY,
        double availableWidth,
        double availableHeight)
    {
        if (!double.IsFinite(availableWidth) || !double.IsFinite(availableHeight) ||
            availableWidth < 320 / 0.6 || availableHeight < 180 / 0.6)
            return this;

        deltaX = double.IsFinite(deltaX) ? deltaX : 0;
        deltaY = double.IsFinite(deltaY) ? deltaY : 0;
        var maxWidth = availableWidth * 0.6;
        var maxHeight = availableHeight * 0.6;
        var width = Math.Clamp(Width, 320, maxWidth);
        var height = Math.Clamp(Height, 180, maxHeight);
        var left = Left;
        var top = Top;

        if (edges.HasFlag(CompactResizeEdges.Left))
        {
            var right = Left + Width;
            width = Math.Clamp(Width - deltaX, 320, maxWidth);
            left = right - width;
        }
        else if (edges.HasFlag(CompactResizeEdges.Right))
        {
            width = Math.Clamp(Width + deltaX, 320, maxWidth);
        }

        if (edges.HasFlag(CompactResizeEdges.Top))
        {
            var bottom = Top + Height;
            height = Math.Clamp(Height - deltaY, 180, maxHeight);
            top = bottom - height;
        }
        else if (edges.HasFlag(CompactResizeEdges.Bottom))
        {
            height = Math.Clamp(Height + deltaY, 180, maxHeight);
        }

        return new CompactPlayerGeometry(left, top, width, height).Clamp(availableWidth, availableHeight);
    }
}

[Flags]
public enum CompactResizeEdges
{
    None = 0,
    Left = 1,
    Top = 2,
    Right = 4,
    Bottom = 8,
}

public readonly record struct PlaybackMuteState(bool IsMuted)
{
    public PlaybackMuteState Toggle() => new(!IsMuted);
}

public static class PlayerPresentationPolicy
{
    public static bool ShouldExpandBeforeWindowModeChange(bool isWindowMini) => isWindowMini;

    public static bool RequiresWindowMiniExit(
        bool maximized,
        bool fullscreen,
        bool iconic,
        double width,
        double height,
        double workAreaWidth,
        double workAreaHeight,
        double margin = 48) =>
        maximized || fullscreen || iconic ||
        width > Math.Max(0, workAreaWidth - margin) ||
        height > Math.Max(0, workAreaHeight - margin);

    public static bool UsesInWindowCompactGeometry(PlayerPresentationHost host) =>
        host != PlayerPresentationHost.WindowMini;

    public static bool CanCoexist(
        PlayerPresentation presentation,
        PlayerPageKind page,
        PlayerPresentationHost host) => host == PlayerPresentationHost.WindowMini
            ? presentation == PlayerPresentation.Compact
            : CanCoexist(presentation, page);

    public static bool ShouldMinimizeBecomeMini(
        MinimizeToMiniMode mode,
        bool isPlaying,
        bool isPaused,
        bool sessionAvailable,
        bool ended,
        StreamKind? kind,
        MiniPlayerEngineKind engine,
        bool alreadyMini,
        bool shiftHeld) =>
        !shiftHeld && !alreadyMini && !ended && sessionAvailable &&
        (kind is StreamKind.Live or StreamKind.Movie or StreamKind.Episode) &&
        engine == MiniPlayerEngineKind.LibVlc &&
        (isPlaying && mode is MinimizeToMiniMode.MiniPlayerWhilePlaying or MinimizeToMiniMode.MiniPlayerWhilePlayingOrPaused ||
         isPaused && mode == MinimizeToMiniMode.MiniPlayerWhilePlayingOrPaused);

    public static bool ShouldEnterMiniFromCinemaButton(
        bool sessionActive,
        StreamKind? kind,
        MiniPlayerEngineKind engine,
        bool alreadyMini,
        bool ended) =>
        sessionActive && !ended && (kind is StreamKind.Live or StreamKind.Movie or StreamKind.Episode) &&
        engine == MiniPlayerEngineKind.LibVlc && !alreadyMini;

    public static bool CanCoexist(PlayerPresentation presentation, PlayerPageKind page) =>
        presentation == PlayerPresentation.Compact
            ? page is PlayerPageKind.Catalog or PlayerPageKind.Other
            : page == PlayerPageKind.Player;

    public static bool ShouldStopPlaybackOnPageChange(
        PlayerPresentation presentation,
        PlayerPageKind currentPage,
        PlayerPageKind destinationPage) =>
        currentPage == PlayerPageKind.Player && destinationPage != PlayerPageKind.Player &&
        presentation != PlayerPresentation.Compact;

    public static bool ShouldSaveProgress(
        PlayerPresentation presentation,
        bool sessionReady,
        bool isResumeEligibleItem) =>
        sessionReady && isResumeEligibleItem &&
        presentation is PlayerPresentation.Full or PlayerPresentation.Cinema or PlayerPresentation.Compact;
}

public static class CompactOverlayPolicy
{
    public static bool ShouldShow(
        bool isPlaying,
        bool isBuffering,
        bool pointerOverControls,
        bool isDragging,
        bool isResizing,
        bool idleElapsed) =>
        !idleElapsed || !isPlaying || isBuffering || pointerOverControls || isDragging || isResizing;
}
