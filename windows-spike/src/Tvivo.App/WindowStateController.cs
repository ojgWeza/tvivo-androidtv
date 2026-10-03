using System.ComponentModel;
using System.Runtime.InteropServices;
using WinRT.Interop;
using Microsoft.UI.Windowing;
using Microsoft.UI.Dispatching;
using Tvivo.Core;

namespace Tvivo.App;

/// <summary>Owns app-window presentation independently from player cinema layout.</summary>
internal sealed class WindowStateController : IDisposable
{
    internal enum WindowMode { Windowed, Fullscreen }
    internal enum WindowMiniModeRequest { Expand, Windowed, Fullscreen, Maximize, Restore }

    private const int GwlStyle = -16;
    private static readonly nint WsPopup = unchecked((nint)0x80000000);
    private static readonly nint WsOverlappedWindow = 0x00CF0000;
    private static readonly nint WsCaption = 0x00C00000;
    private const uint MonitorDefaultToNearest = 2;
    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpFrameChanged = 0x0020;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoSize = 0x0001;
    private const int SwMinimize = 6;
    private const uint WmNcLButtonDown = 0x00A1;
    private const uint WmNcLButtonDblClk = 0x00A3;
    private const uint WmGetMinMaxInfo = 0x0024;
    private const uint WmSize = 0x0005;
    private const uint WmWindowPosChanged = 0x0047;
    private const uint WmEnterSizeMove = 0x0231;
    private const uint WmExitSizeMove = 0x0232;

    // True while the OS modal move/size loop runs (title-strip drag, frame resize). Per-message
    // safety/topmost/geometry work is deferred to WM_EXITSIZEMOVE so dragging stays smooth.
    private bool _inMoveSizeLoop;
    private const int HtCaption = 2;
    private const uint SwShowMaximized = 3;
    private const int SwMaximize = 3;
    private const int SwRestore = 9;
    private const int SizeRestored = 0;
    private const int VkShift = 0x10;
    private const short KeyDownMask = unchecked((short)0x8000);
    private static readonly nint HwndTop = 0;

    private readonly nint _hwnd;
    private readonly Microsoft.UI.Xaml.Window _window;
    private WindowPlacement? _windowedPlacement;
    private Rect? _windowedBounds;
    private nint? _windowedStyle;
    internal WindowMode Mode { get; private set; } = WindowMode.Windowed;
    internal bool IsWindowMini { get; private set; }
    internal bool IsShiftDown => (GetKeyState(VkShift) & KeyDownMask) != 0;
    internal event Action<WindowMiniGeometry>? WindowMiniGeometryChanged;
    private WindowMode _miniPreviousMode;
    private WindowPlacement _miniPreviousPlacement;
    private Rect _miniPreviousBounds;
    private DispatcherQueue? _dispatcher;
    private Func<bool>? _shouldEnterMini;
    private Action? _enterMini;
    private Action<WindowMiniModeRequest>? _requestMiniModeChange;
    private Action<string>? _forceExitMini;
    private bool _miniModeRequestQueued;
    private bool _minimizedFromMini;
    private SubclassProcedure? _subclassProcedure;
    private static readonly nuint SubclassId = 0x54564956;

    internal WindowStateController(Microsoft.UI.Xaml.Window window)
    {
        _window = window;
        _hwnd = WindowNative.GetWindowHandle(window);
        // The XAML chrome row owns the window controls. Keep a normal resizable
        // overlapped window, but remove the duplicate native caption buttons.
        SetStyle(GetStyle() & ~WsCaption);
        SetBounds(default, SwpFrameChanged | SwpNoMove | SwpNoSize | SwpNoZOrder | SwpNoActivate);
        CenterInitialWindow();
    }

    internal WindowMode EffectiveMode => IsWindowMini ? _miniPreviousMode : Mode;

    internal void InstallMinimizeInterceptor(
        DispatcherQueue dispatcher,
        Func<bool> shouldEnterMini,
        Action enterMini,
        Action<WindowMiniModeRequest> requestMiniModeChange,
        Action<string> forceExitMini)
    {
        _dispatcher = dispatcher;
        _shouldEnterMini = shouldEnterMini;
        _enterMini = enterMini;
        _requestMiniModeChange = requestMiniModeChange;
        _forceExitMini = forceExitMini;
        _subclassProcedure = WindowSubclassProc;
        if (!SetWindowSubclass(_hwnd, _subclassProcedure, SubclassId, 0))
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    internal WindowMiniGeometry GetDefaultWindowMiniGeometry()
    {
        var work = GetMonitorInfoForWindow().Work;
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
        var width = (work.Right - work.Left) * 96d / dpi;
        var height = (work.Bottom - work.Top) * 96d / dpi;
        return WindowMiniGeometry.BottomRight(width, height);
    }

    internal WindowMiniGeometry ClampWindowMiniGeometry(WindowMiniGeometry geometry)
    {
        var work = GetMonitorInfoForWindow().Work;
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
        return geometry.Clamp((work.Right - work.Left) * 96d / dpi, (work.Bottom - work.Top) * 96d / dpi);
    }

    internal WindowMiniGeometry GetCurrentWindowMiniGeometry() => ReadWindowMiniGeometry();

    internal WindowMiniGeometry EnterWindowMini(WindowMiniGeometry geometry)
    {
        if (IsWindowMini) return ClampWindowMiniGeometry(geometry);
        _miniPreviousMode = Mode;
        if (Mode == WindowMode.Fullscreen)
            Apply(WindowMode.Windowed);
        _miniPreviousPlacement = NewWindowPlacement();
        if (!GetWindowPlacement(_hwnd, ref _miniPreviousPlacement) || !GetWindowRect(_hwnd, out _miniPreviousBounds))
            throw new Win32Exception(Marshal.GetLastWin32Error());

        var work = GetMonitorInfoForWindow().Work;
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
        geometry = ClampWindowMiniGeometry(geometry);
        var width = (int)Math.Round(geometry.Width * dpi / 96d);
        var height = (int)Math.Round(geometry.Height * dpi / 96d);
        var x = work.Left + (int)Math.Round(geometry.Left * dpi / 96d);
        var y = work.Top + (int)Math.Round(geometry.Top * dpi / 96d);
        if (_window.AppWindow.Presenter is OverlappedPresenter presenter)
            presenter.IsAlwaysOnTop = true;
        SetBounds(new Rect { Left = x, Top = y, Right = x + width, Bottom = y + height }, SwpFrameChanged | SwpNoActivate);
        IsWindowMini = true;
        return geometry;
    }

    internal void ExitWindowMini()
    {
        if (!IsWindowMini) return;
        // Clear mini state before native placement changes so their notifications
        // cannot re-assert topmost after an intentional exit.
        IsWindowMini = false;
        _miniModeRequestQueued = false;
        if (_window.AppWindow.Presenter is OverlappedPresenter presenter)
            presenter.IsAlwaysOnTop = false;
        var placement = _miniPreviousPlacement;
        if (!SetWindowPlacement(_hwnd, ref placement))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        if (_miniPreviousPlacement.ShowCmd != SwShowMaximized)
            SetBounds(_miniPreviousBounds, SwpFrameChanged | SwpNoActivate | SwpNoZOrder);
        else
            SetWindowPos(_hwnd, HwndTop, 0, 0, 0, 0,
                SwpFrameChanged | SwpNoActivate | SwpNoZOrder | SwpNoSize | SwpNoMove);
        if (_miniPreviousMode == WindowMode.Fullscreen)
            Apply(WindowMode.Fullscreen);
    }

    private nint WindowSubclassProc(nint hwnd, uint message, nint wParam, nint lParam, nuint idSubclass, nuint refData)
    {
        const uint wmSysCommand = 0x0112;
        const int scMinimize = 0xF020;
        const int scMaximize = 0xF030;
        const int scRestore = 0xF120;
        if (IsWindowMini && message == wmSysCommand)
        {
            var command = (int)wParam & 0xFFF0;
            if (command == scMinimize)
                _minimizedFromMini = true;
            if (command == scRestore && _minimizedFromMini)
            {
                var restoreResult = DefSubclassProc(hwnd, message, wParam, lParam);
                _dispatcher?.TryEnqueue(RestoreWindowMiniAfterTaskbarRestore);
                return restoreResult;
            }
            if (command == scMaximize)
            {
                QueueMiniModeChange(WindowMiniModeRequest.Maximize);
                return 0;
            }
            if (command == scRestore)
            {
                QueueMiniModeChange(WindowMiniModeRequest.Restore);
                return 0;
            }
        }
        if (IsWindowMini && message == WmNcLButtonDblClk && (int)wParam == HtCaption)
        {
            QueueMiniModeChange(WindowMiniModeRequest.Maximize);
            return 0;
        }
        if (message == wmSysCommand && ((int)wParam & 0xFFF0) == scMinimize &&
            !IsShiftDown && _shouldEnterMini?.Invoke() == true)
        {
            _dispatcher?.TryEnqueue(() => _enterMini?.Invoke());
            return 0;
        }
        var result = DefSubclassProc(hwnd, message, wParam, lParam);
        if (IsWindowMini && _minimizedFromMini && message == WmSize && (int)wParam == SizeRestored)
            _dispatcher?.TryEnqueue(RestoreWindowMiniAfterTaskbarRestore);
        if (message == WmEnterSizeMove)
            _inMoveSizeLoop = true;
        else if (message == WmExitSizeMove)
        {
            _inMoveSizeLoop = false;
            if (IsWindowMini)
            {
                CheckWindowMiniSafety("native-size-state");
                EnsureWindowMiniTopmost();
                _dispatcher?.TryEnqueue(() => WindowMiniGeometryChanged?.Invoke(ReadWindowMiniGeometry()));
            }
        }
        else if (!_inMoveSizeLoop && IsWindowMini && !(_minimizedFromMini && IsIconic(_hwnd)) &&
            (message == WmGetMinMaxInfo || message == WmSize || message == WmWindowPosChanged))
        {
            CheckWindowMiniSafety(message == WmGetMinMaxInfo ? "minmax-info" : "native-size-state");
            if (message == WmWindowPosChanged)
            {
                EnsureWindowMiniTopmost();
                _dispatcher?.TryEnqueue(() => WindowMiniGeometryChanged?.Invoke(ReadWindowMiniGeometry()));
            }
        }
        return result;
    }

    private void QueueMiniModeChange(WindowMiniModeRequest request)
    {
        if (_miniModeRequestQueued || _dispatcher is not { } dispatcher) return;
        _miniModeRequestQueued = true;
        dispatcher.TryEnqueue(() => _requestMiniModeChange?.Invoke(request));
    }

    private void CheckWindowMiniSafety(string reason)
    {
        if (_minimizedFromMini && IsIconic(_hwnd)) return;
        var invalidState = GetWindowMiniInvalidStateReason();
        if (invalidState is null) return;
        _dispatcher?.TryEnqueue(() => _forceExitMini?.Invoke($"{reason}:{invalidState}"));
    }

    internal string? GetWindowMiniInvalidStateReason()
    {
        var placement = NewWindowPlacement();
        if (!GetWindowPlacement(_hwnd, ref placement) || !GetWindowRect(_hwnd, out var bounds)) return null;
        MonitorInfo monitor;
        try { monitor = GetMonitorInfoForWindow(); }
        catch (Win32Exception) { return null; }
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0) return null;
        var scale = dpi / 96d;
        var width = bounds.Right - bounds.Left;
        var height = bounds.Bottom - bounds.Top;
        var workWidth = monitor.Work.Right - monitor.Work.Left;
        var workHeight = monitor.Work.Bottom - monitor.Work.Top;
        var fullscreen = Math.Abs(bounds.Left - monitor.Monitor.Left) <= 2 &&
            Math.Abs(bounds.Top - monitor.Monitor.Top) <= 2 &&
            Math.Abs(bounds.Right - monitor.Monitor.Right) <= 2 &&
            Math.Abs(bounds.Bottom - monitor.Monitor.Bottom) <= 2;
        if (IsIconic(_hwnd)) return "iconic";
        if (IsZoomed(_hwnd) || placement.ShowCmd == SwShowMaximized) return "maximized";
        if (fullscreen) return "fullscreen";
        return PlayerPresentationPolicy.RequiresWindowMiniExit(
            false, false, false, width, height, workWidth, workHeight, 48 * scale)
            ? "oversized"
            : null;
    }

    internal void EnsureWindowMiniTopmost()
    {
        if (!IsWindowMini || _window.AppWindow.Presenter is not OverlappedPresenter presenter || presenter.IsAlwaysOnTop)
            return;
        try { presenter.IsAlwaysOnTop = true; }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"event=window-mini.topmost-reassert-failed error={exception.GetType().Name}");
        }
    }

    private void RestoreWindowMiniAfterTaskbarRestore()
    {
        if (!IsWindowMini || !_minimizedFromMini || IsIconic(_hwnd)) return;
        _minimizedFromMini = false;
        EnsureWindowMiniTopmost();
        CheckWindowMiniSafety("taskbar-restore");
    }

    internal void RequestWindowMode(WindowMode mode)
    {
        if (PlayerPresentationPolicy.ShouldExpandBeforeWindowModeChange(IsWindowMini))
        {
            QueueMiniModeChange(mode == WindowMode.Fullscreen
                ? WindowMiniModeRequest.Fullscreen
                : WindowMiniModeRequest.Windowed);
            return;
        }
        Apply(mode);
    }

    private WindowMiniGeometry ReadWindowMiniGeometry()
    {
        if (!GetWindowRect(_hwnd, out var bounds)) return GetDefaultWindowMiniGeometry();
        var work = GetMonitorInfoForWindow().Work;
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0) return GetDefaultWindowMiniGeometry();
        return ClampWindowMiniGeometry(new WindowMiniGeometry(
            (bounds.Left - work.Left) * 96d / dpi,
            (bounds.Top - work.Top) * 96d / dpi,
            (bounds.Right - bounds.Left) * 96d / dpi,
            (bounds.Bottom - bounds.Top) * 96d / dpi));
    }

    public void Dispose()
    {
        if (_subclassProcedure is not null)
            RemoveWindowSubclass(_hwnd, _subclassProcedure, SubclassId);
        _subclassProcedure = null;
    }

    internal void Apply(WindowMode mode)
    {
        if (PlayerPresentationPolicy.ShouldExpandBeforeWindowModeChange(IsWindowMini))
        {
            QueueMiniModeChange(mode == WindowMode.Fullscreen
                ? WindowMiniModeRequest.Fullscreen
                : WindowMiniModeRequest.Windowed);
            return;
        }
        if (Mode == mode)
            return;

        LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} apply-begin from={Mode} to={mode}");

        if (mode == WindowMode.Fullscreen)
        {
            var placement = NewWindowPlacement();
            if (!GetWindowPlacement(_hwnd, ref placement) || !GetWindowRect(_hwnd, out var bounds))
                throw new Win32Exception(Marshal.GetLastWin32Error());

            var style = GetStyle();
            var monitor = GetMonitorInfoForWindow();
            var fullscreenStyle = (style & ~WsOverlappedWindow) | WsPopup;
            SetStyle(fullscreenStyle);
            try
            {
                SetBounds(monitor.Monitor, SwpFrameChanged | SwpNoActivate);
            }
            catch
            {
                SetStyle(style);
                SetWindowPlacement(_hwnd, ref placement);
                SetBounds(bounds, SwpFrameChanged | SwpNoActivate | SwpNoZOrder);
                throw;
            }

            _windowedStyle = style;
            _windowedPlacement = placement;
            _windowedBounds = bounds;
        }
        else
        {
            if (_windowedStyle is not { } style || _windowedPlacement is not { } savedPlacement ||
                _windowedBounds is not { } savedBounds)
                throw new InvalidOperationException("Windowed state was not captured before fullscreen.");

            SetStyle(style);
            var placement = savedPlacement;
            LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPlacement-begin showCmd={placement.ShowCmd}");
            if (!SetWindowPlacement(_hwnd, ref placement))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPlacement-complete showCmd={placement.ShowCmd}");
            if (placement.ShowCmd != SwShowMaximized)
                SetBounds(savedBounds, SwpFrameChanged | SwpNoActivate | SwpNoZOrder);
            else
            {
                var flags = SwpFrameChanged | SwpNoActivate | SwpNoZOrder | SwpNoSize | SwpNoMove;
                LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPos-maximized-frame-begin flags=0x{flags:X}");
                if (!SetWindowPos(_hwnd, HwndTop, 0, 0, 0, 0, flags))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPos-maximized-frame-complete");
            }
        }

        Mode = mode;
        MarkFullscreen(mode == WindowMode.Fullscreen);
        LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} apply-complete mode={Mode}");
    }

    // Tells the shell this window is (no longer) fullscreen so the taskbar is not left hidden after we leave or exit.
    internal void ClearFullscreenHint() => MarkFullscreen(false);

    internal void MaximizeWindow() => ShowWindow(_hwnd, SwMaximize);

    internal void RestoreWindow() => ShowWindow(_hwnd, SwRestore);

    private ITaskbarList2? _taskbarList;

    private void MarkFullscreen(bool fullscreen)
    {
        try
        {
            if (_taskbarList is null)
            {
                var list = (ITaskbarList2)new TaskbarListCom();
                list.HrInit();
                _taskbarList = list;
            }
            _taskbarList.MarkFullscreenWindow(_hwnd, fullscreen);
            LaunchDiagnostics.Write($"WINDOW-STATE taskbar-hint fullscreen={fullscreen}");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"WINDOW-STATE taskbar-hint failed fullscreen={fullscreen} {exception.GetType().Name}");
        }
    }

    [ComImport, Guid("56FDF344-FD6D-11d0-958A-006097C9A090")]
    private class TaskbarListCom { }

    [ComImport, Guid("602D4995-B13A-429b-A66E-1935E44F4317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList2
    {
        void HrInit();
        void AddTab(nint hwnd);
        void DeleteTab(nint hwnd);
        void ActivateTab(nint hwnd);
        void SetActiveAlt(nint hwnd);
        void MarkFullscreenWindow(nint hwnd, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
    }

    internal void Minimize()
    {
        if (IsWindowMini) _minimizedFromMini = true;
        ShowWindow(_hwnd, SwMinimize);
    }

    internal void BeginWindowDrag()
    {
        if (Mode == WindowMode.Windowed)
        {
            ReleaseCapture();
            SendMessage(_hwnd, WmNcLButtonDown, HtCaption, 0);
        }
    }

    private void CenterInitialWindow()
    {
        var work = GetMonitorInfoForWindow().Work;
        var dpi = GetDpiForWindow(_hwnd);
        if (dpi == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        var width = Math.Min(work.Right - work.Left, (int)(1280L * dpi / 96));
        var height = Math.Min(work.Bottom - work.Top, (int)(800L * dpi / 96));
        var left = work.Left + (work.Right - work.Left - width) / 2;
        var top = work.Top + (work.Bottom - work.Top - height) / 2;
        SetBounds(new Rect { Left = left, Top = top, Right = left + width, Bottom = top + height },
            SwpNoZOrder | SwpNoActivate);
    }

    private nint GetStyle()
    {
        var style = IntPtr.Size == 8 ? GetWindowLongPtr64(_hwnd, GwlStyle) : GetWindowLong32(_hwnd, GwlStyle);
        if (style == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return style;
    }

    private void SetStyle(nint style)
    {
        var previous = IntPtr.Size == 8
            ? SetWindowLongPtr64(_hwnd, GwlStyle, style)
            : SetWindowLong32(_hwnd, GwlStyle, style);
        if (previous == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    private MonitorInfo GetMonitorInfoForWindow()
    {
        var monitor = MonitorFromWindow(_hwnd, MonitorDefaultToNearest);
        if (monitor == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        var info = new MonitorInfo { Size = (uint)Marshal.SizeOf<MonitorInfo>() };
        if (!GetMonitorInfo(monitor, ref info))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return info;
    }

    private void SetBounds(Rect bounds, uint flags)
    {
        LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPos-begin rect=({bounds.Left},{bounds.Top},{bounds.Right},{bounds.Bottom}) flags=0x{flags:X}");
        if (!SetWindowPos(_hwnd, HwndTop, bounds.Left, bounds.Top,
                bounds.Right - bounds.Left, bounds.Bottom - bounds.Top, flags))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} SetWindowPos-complete");
    }

    private static WindowPlacement NewWindowPlacement() => new() { Length = (uint)Marshal.SizeOf<WindowPlacement>() };

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct WindowPlacement
    {
        public uint Length, Flags, ShowCmd;
        public Point MinPosition, MaxPosition;
        public Rect NormalPosition;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo
    {
        public uint Size;
        public Rect Monitor, Work;
        public uint Flags;
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern nint GetWindowLongPtr64(nint hwnd, int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW", SetLastError = true)]
    private static extern nint GetWindowLong32(nint hwnd, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern nint SetWindowLongPtr64(nint hwnd, int index, nint value);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongW", SetLastError = true)]
    private static extern nint SetWindowLong32(nint hwnd, int index, nint value);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowPlacement(nint hwnd, ref WindowPlacement placement);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPlacement(nint hwnd, ref WindowPlacement placement);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(nint hwnd, out Rect bounds);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern nint MonitorFromWindow(nint hwnd, uint flags);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetDpiForWindow(nint hwnd);

    [DllImport("user32.dll", EntryPoint = "GetMonitorInfoW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(nint monitor, ref MonitorInfo info);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(nint hwnd, nint insertAfter, int x, int y, int width, int height, uint flags);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(nint hwnd, int command);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(nint hwnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(nint hwnd);

    [DllImport("user32.dll")]
    private static extern short GetKeyState(int key);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern nint SendMessage(nint hwnd, uint message, nint wParam, nint lParam);

    private delegate nint SubclassProcedure(nint hwnd, uint message, nint wParam, nint lParam, nuint idSubclass, nuint refData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(nint hwnd, SubclassProcedure callback, nuint idSubclass, nuint refData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProcedure callback, nuint idSubclass);

    [DllImport("comctl32.dll")]
    private static extern nint DefSubclassProc(nint hwnd, uint message, nint wParam, nint lParam);
}
