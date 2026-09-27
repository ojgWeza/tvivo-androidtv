using System.ComponentModel;
using System.Runtime.InteropServices;
using WinRT.Interop;

namespace Tvivo.App;

/// <summary>Owns app-window presentation independently from player cinema layout.</summary>
internal sealed class WindowStateController
{
    internal enum WindowMode { Windowed, Fullscreen }

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
    private const int HtCaption = 2;
    private const uint SwShowMaximized = 3;
    private static readonly nint HwndTop = 0;

    private readonly nint _hwnd;
    private WindowPlacement? _windowedPlacement;
    private Rect? _windowedBounds;
    private nint? _windowedStyle;
    internal WindowMode Mode { get; private set; } = WindowMode.Windowed;

    internal WindowStateController(Microsoft.UI.Xaml.Window window)
    {
        _hwnd = WindowNative.GetWindowHandle(window);
        // The XAML chrome row owns the window controls. Keep a normal resizable
        // overlapped window, but remove the duplicate native caption buttons.
        SetStyle(GetStyle() & ~WsCaption);
        SetBounds(default, SwpFrameChanged | SwpNoMove | SwpNoSize | SwpNoZOrder | SwpNoActivate);
        CenterInitialWindow();
    }

    internal void Apply(WindowMode mode)
    {
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
        LaunchDiagnostics.Write($"WINDOW-STATE utc={DateTimeOffset.UtcNow:O} apply-complete mode={Mode}");
    }

    internal void Minimize() => ShowWindow(_hwnd, SwMinimize);

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
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern nint SendMessage(nint hwnd, uint message, nint wParam, nint lParam);
}
