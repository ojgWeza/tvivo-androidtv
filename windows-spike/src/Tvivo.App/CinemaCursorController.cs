using System.Runtime.InteropServices;
using Tvivo.Core;

namespace Tvivo.App;

/// <summary>Hides the idle cinema or compact pointer with one tiny, owned hit-target window.</summary>
internal sealed class CinemaCursorController : IDisposable
{
    private const uint WmSetCursor = 0x0020;
    private const uint WmMouseMove = 0x0200;
    private const uint WmMouseLeave = 0x02A3;
    private const uint WmLButtonDown = 0x0201;
    private const uint WmRButtonDown = 0x0204;
    private const uint WmMouseWheel = 0x020A;
    private const uint WmXButtonDown = 0x020B;
    private const uint WmNcDestroy = 0x0082;
    private const uint WmClose = 0x0010;
    private const uint WmMouseActivate = 0x0021;
    private const int MaNoActivate = 3;
    private const int HtClient = 1;
    private const uint WsPopup = 0x80000000;
    private const uint WsExLayered = 0x00080000;
    private const uint WsExToolWindow = 0x00000080;
    private const uint WsExNoActivate = 0x08000000;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpShowWindow = 0x0040;
    private const int SwShownNoActivate = 4;
    private const uint LwaAlpha = 0x00000002;
    private const uint ErrorClassAlreadyExists = 1410;
    private const int PopupSize = 2;
    private const string WindowClassName = "Tvivo.CinemaCursorHitTarget";

    private static readonly WndProc PopupWindowProcedure = PopupWndProc;
    private static readonly Dictionary<nint, CinemaCursorController> PopupOwners = new();
    private static bool _windowClassRegistered;

    private readonly nint _rootHwnd;
    private readonly Action<string> _showControls;
    private volatile bool _hidden;
    private bool _loggedPopupProbe;
    private bool _disposed;
    private string _presentation = "cinema";
    private Timer? _postHideProbeTimer;
    private long _hideGeneration;
    private long _popupCreatedAtTick;
    private NativePoint _hideBaseline;
    private nint _popupHwnd;

    internal CinemaCursorController(nint rootHwnd, Action<string> showControls)
    {
        _rootHwnd = rootHwnd;
        _showControls = showControls;
    }

    internal void SetHidden(bool hidden, string presentation = "cinema")
    {
        if (_disposed) return;
        _hidden = hidden;
        _presentation = presentation;
        _loggedPopupProbe = false;
        LaunchDiagnostics.Write($"event={presentation}.cursor-set hidden={hidden}");

        if (!hidden)
        {
            DestroyPopup();
            RestoreCursorAtPointer();
            return;
        }

        DestroyPopup();
        if (GetCursorPos(out _hideBaseline))
            CreatePopupAt(_hideBaseline);
        SchedulePostHideProbe(presentation);
    }

    internal bool TryWakeFromPoll(int thresholdPixels = 2)
    {
        if (!_hidden || _popupHwnd == 0 || !GetCursorPos(out var current)) return false;
        var elapsedMilliseconds = Environment.TickCount64 - _popupCreatedAtTick;
        if (!_loggedPopupProbe && elapsedMilliseconds >= 300)
        {
            _loggedPopupProbe = true;
            WritePopupDiagnostics();
        }

        var rect = new NativeRect();
        if (!GetWindowRect(_popupHwnd, out rect)) return false;
        var outsidePopup = current.X < rect.Left || current.X >= rect.Right ||
                           current.Y < rect.Top || current.Y >= rect.Bottom;
        if (!CinemaPointerMovementPolicy.ShouldWakeForPointerPoll(
            elapsedMilliseconds,
            outsidePopup,
            _hideBaseline.X, _hideBaseline.Y, current.X, current.Y, thresholdPixels)) return false;

        RequestShow("pointer-poll");
        return true;
    }

    private void CreatePopupAt(NativePoint point)
    {
        // Keep the Win32 screen-pixel coordinates from GetCursorPos intact across monitors;
        // XAML-scaled coordinates would place the hit target away from the actual hotspot.
        var hitTarget = WindowFromPoint(point);
        if (hitTarget == 0 || (hitTarget != _rootHwnd && !IsChild(_rootHwnd, hitTarget)))
            return;

        if (!RegisterPopupWindowClass())
        {
            LaunchDiagnostics.Write($"event=cinema.cursor-popup-failed stage=register error={Marshal.GetLastWin32Error()}");
            return;
        }

        var module = GetModuleHandle(null);
        var hwnd = CreateWindowEx(
            WsExNoActivate | WsExToolWindow | WsExLayered,
            WindowClassName,
            string.Empty,
            WsPopup,
            point.X,
            point.Y,
            PopupSize,
            PopupSize,
            _rootHwnd,
            0,
            module,
            0);
        if (hwnd == 0)
        {
            LaunchDiagnostics.Write($"event=cinema.cursor-popup-failed stage=create error={Marshal.GetLastWin32Error()}");
            return;
        }

        SetCursor(0);
        _popupHwnd = hwnd;
        PopupOwners[hwnd] = this;
        _popupCreatedAtTick = Environment.TickCount64;
        if (!SetLayeredWindowAttributes(hwnd, 0, 1, LwaAlpha) ||
            !SetWindowPos(hwnd, 0, point.X, point.Y, PopupSize, PopupSize, SwpNoActivate | SwpShowWindow))
        {
            var error = Marshal.GetLastWin32Error();
            DestroyPopup();
            LaunchDiagnostics.Write($"event=cinema.cursor-popup-failed stage=show error={error}");
            return;
        }

        ShowWindow(hwnd, SwShownNoActivate);
        SetCursor(0);
        WritePopupDiagnostics();
    }

    private void SchedulePostHideProbe(string presentation)
    {
        var generation = Interlocked.Increment(ref _hideGeneration);
        _postHideProbeTimer?.Dispose();
        _postHideProbeTimer = new Timer(_ =>
        {
            if (Volatile.Read(ref _hideGeneration) == generation)
                WritePostHideProbe(presentation);
        }, null, 800, Timeout.Infinite);
    }

    private void WritePostHideProbe(string presentation)
    {
        var cursorInfo = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
        var gotCursorInfo = GetCursorInfo(ref cursorInfo);
        var cursorVisible = gotCursorInfo && (cursorInfo.Flags & 0x00000001) != 0;
        var className = "unknown";
        if (GetCursorPos(out var point))
        {
            var hwnd = WindowFromPoint(point);
            var buffer = new System.Text.StringBuilder(256);
            if (hwnd != 0 && GetClassName(hwnd, buffer, buffer.Capacity) > 0)
                className = buffer.ToString();
        }
        LaunchDiagnostics.Write(
            $"event={presentation}.cursor-probe cursorVisible={cursorVisible.ToString().ToLowerInvariant()} " +
            $"class={className} getCursorInfo={(gotCursorInfo ? "true" : "false")} flags=0x{cursorInfo.Flags:X}");
    }

    private static bool RegisterPopupWindowClass()
    {
        if (_windowClassRegistered) return true;

        var windowClass = new WindowClassEx
        {
            Size = (uint)Marshal.SizeOf<WindowClassEx>(),
            WindowProcedure = PopupWindowProcedure,
            Instance = GetModuleHandle(null),
            Cursor = 0,
            ClassName = WindowClassName
        };
        if (RegisterClassEx(ref windowClass) != 0)
        {
            _windowClassRegistered = true;
            return true;
        }

        if (Marshal.GetLastWin32Error() == ErrorClassAlreadyExists)
        {
            _windowClassRegistered = true;
            return true;
        }
        return false;
    }

    private void RequestShow(string reason)
    {
        DestroyPopup(reason);
        _showControls(reason);
    }

    private void DestroyPopup(string? reason = null)
    {
        var hwnd = _popupHwnd;
        if (hwnd == 0) return;

        _popupHwnd = 0;
        PopupOwners.Remove(hwnd);
        if (IsWindow(hwnd)) DestroyWindow(hwnd);
        if (reason is not null)
            LaunchDiagnostics.Write($"event={_presentation}.cursor-popup-destroyed reason={reason}");
    }

    private void RestoreCursorAtPointer()
    {
        if (!GetCursorPos(out var point)) return;
        var hwnd = WindowFromPoint(point);
        if (hwnd == 0 || (hwnd != _rootHwnd && !IsChild(_rootHwnd, hwnd))) return;
        SendMessage(hwnd, WmSetCursor, hwnd, (nint)((WmMouseMove << 16) | HtClient));
    }

    private void WritePopupDiagnostics()
    {
        if (_popupHwnd == 0 || !GetCursorPos(out var point)) return;
        var windowAtCursor = WindowFromPoint(point);
        var cursorInfo = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
        GetCursorInfo(ref cursorInfo);
        var popupThread = GetWindowThreadProcessId(_popupHwnd, out _);
        LaunchDiagnostics.Write(
            $"event={_presentation}.cursor-popup hwnd=0x{_popupHwnd:X} windowFromPoint=0x{windowAtCursor:X} " +
            $"isPopup={(windowAtCursor == _popupHwnd ? "true" : "false")} uiThread={GetCurrentThreadId()} " +
            $"popupThread={popupThread} getCursor=0x{GetCursor():X} cursorInfoFlags=0x{cursorInfo.Flags:X} " +
            $"cursorInfoHandle=0x{cursorInfo.Cursor:X} cursorVisible={((cursorInfo.Flags & 0x00000001) != 0).ToString().ToLowerInvariant()}");
    }

    private static nint PopupWndProc(nint hwnd, uint message, nint wParam, nint lParam)
    {
        if (PopupOwners.TryGetValue(hwnd, out var controller))
        {
            if (message == WmSetCursor)
            {
                SetCursor(0);
                return 1;
            }

            if (message == WmMouseMove)
            {
                if (GetCursorPos(out var point) &&
                    CinemaPointerMovementPolicy.ShouldWakeForPointerMove(
                        Environment.TickCount64 - controller._popupCreatedAtTick,
                        controller._hideBaseline.X, controller._hideBaseline.Y,
                        point.X, point.Y))
                    controller.RequestShow("pointer-move");
                return 0;
            }

            if (message == WmMouseLeave) // Popup transitions can synthesize leave immediately after creation.
                return 0;

            if (message == WmMouseActivate)
                return MaNoActivate;

            if (message is WmLButtonDown or WmRButtonDown or WmMouseWheel or WmXButtonDown)
            {
                var reason = CinemaPointerMovementPolicy.GetInputWakeReason(
                    Environment.TickCount64 - controller._popupCreatedAtTick,
                    message == WmMouseWheel);
                if (reason is not null) controller.RequestShow(reason);
                return 0;
            }

            if (message == WmClose)
            {
                controller.DestroyPopup();
                return 0;
            }
        }

        if (message == WmNcDestroy && PopupOwners.Remove(hwnd, out var owner) && owner._popupHwnd == hwnd)
            owner._popupHwnd = 0;
        return DefWindowProc(hwnd, message, wParam, lParam);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _hidden = false;
        Interlocked.Increment(ref _hideGeneration);
        _postHideProbeTimer?.Dispose();
        _postHideProbeTimer = null;
        DestroyPopup();
        _disposed = true;
    }

    private delegate nint WndProc(nint hwnd, uint message, nint wParam, nint lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WindowClassEx
    {
        internal uint Size;
        internal uint Style;
        [MarshalAs(UnmanagedType.FunctionPtr)] internal WndProc WindowProcedure;
        internal int ClassExtra;
        internal int WindowExtra;
        internal nint Instance;
        internal nint Icon;
        internal nint Cursor;
        internal nint Background;
        internal nint MenuName;
        [MarshalAs(UnmanagedType.LPWStr)] internal string ClassName;
        internal nint SmallIcon;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint
    {
        internal int X;
        internal int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CursorInfo
    {
        internal int Size;
        internal uint Flags;
        internal nint Cursor;
        internal NativePoint ScreenPosition;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        internal int Left;
        internal int Top;
        internal int Right;
        internal int Bottom;
    }

    [DllImport("user32.dll", EntryPoint = "RegisterClassExW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassEx(ref WindowClassEx windowClass);

    [DllImport("user32.dll", EntryPoint = "CreateWindowExW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint CreateWindowEx(uint extendedStyle, string className, string windowName, uint style,
        int x, int y, int width, int height, nint parent, nint menu, nint instance, nint parameter);

    [DllImport("user32.dll", EntryPoint = "SetLayeredWindowAttributes", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetLayeredWindowAttributes(nint hwnd, uint colorKey, byte alpha, uint flags);

    [DllImport("user32.dll", EntryPoint = "SetWindowPos", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(nint hwnd, nint insertAfter, int x, int y, int width, int height, uint flags);

    [DllImport("user32.dll", EntryPoint = "ShowWindow")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(nint hwnd, int command);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(nint hwnd);

    [DllImport("user32.dll", EntryPoint = "DestroyWindow", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyWindow(nint hwnd);

    [DllImport("user32.dll", EntryPoint = "DefWindowProcW")]
    private static extern nint DefWindowProc(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern nint SendMessage(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll")]
    private static extern nint SetCursor(nint cursor);

    [DllImport("user32.dll")]
    private static extern nint GetCursor();

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    private static extern nint WindowFromPoint(NativePoint point);

    [DllImport("user32.dll", EntryPoint = "GetWindowRect", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(nint hwnd, out NativeRect rect);

    [DllImport("user32.dll", EntryPoint = "GetCursorInfo", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorInfo(ref CursorInfo info);

    [DllImport("user32.dll", EntryPoint = "GetWindowThreadProcessId", SetLastError = true)]
    private static extern uint GetWindowThreadProcessId(nint hwnd, out uint processId);

    [DllImport("user32.dll", EntryPoint = "GetClassNameW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int GetClassName(nint hwnd, System.Text.StringBuilder className, int maxCount);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsChild(nint parent, nint child);

    [DllImport("kernel32.dll", EntryPoint = "GetModuleHandleW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint GetModuleHandle(string? moduleName);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
}
