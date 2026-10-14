using System.ComponentModel;
using System.Runtime.InteropServices;

namespace Tvivo.App;

/// <summary>Applies cinema cursor visibility only to this window and its native child windows.</summary>
internal sealed class CinemaCursorController : IDisposable
{
    private const uint WmSetCursor = 0x0020;
    private const uint WmMouseMove = 0x0200;
    private const uint WmNcDestroy = 0x0082;
    private const int HtClient = 1;
    private const nuint SubclassId = 0x43494E45;

    private readonly nint _rootHwnd;
    private readonly nint _transparentCursor;
    private readonly HashSet<nint> _subclassedWindows = new();
    private readonly SubclassProcedure _subclassProcedure;
    private bool _hidden;
    private bool _loggedHide;
    private bool _disposed;
    private NativePoint _hideBaseline;

    internal CinemaCursorController(nint rootHwnd)
    {
        _rootHwnd = rootHwnd;
        _transparentCursor = LoadCursor(LoadLibrary(Path.Combine(AppContext.BaseDirectory, "Assets", "TvivoCursors.dll")), (nint)101);
        if (_transparentCursor == 0)
            LaunchDiagnostics.Write($"event=cinema.cursor-resource.failed error={Marshal.GetLastWin32Error()}");
        _subclassProcedure = WindowSubclassProc;
        AddSubclass(rootHwnd, required: true);
    }

    internal void SetHidden(bool hidden)
    {
        if (_disposed) return;
        _hidden = hidden;
        _loggedHide = false;
        LaunchDiagnostics.Write($"event=cinema.cursor-set hidden={hidden}");
        _loggedPostHide = false;
        _hiddenAtTick = Environment.TickCount64;
        if (hidden && GetCursorPos(out _hideBaseline))
            WriteHideDiagnostics(_hideBaseline);
        RefreshChildWindows();
        // Re-evaluate the cursor under the pointer now in both directions; without this a hide only
        // takes effect on the next WM_SETCURSOR, i.e. after a mouse move, which immediately reveals it again.
        RestoreCursorAtPointer();
        if (hidden) NudgePointer(_hideBaseline);
        ApplySystemCursorVisibility(hidden);
    }

    internal bool HasMovedSinceHide(int thresholdPixels = 2)
    {
        if (!_hidden || !GetCursorPos(out var current)) return false;
        if (!_loggedPostHide && Environment.TickCount64 - _hiddenAtTick >= 300)
        {
            // Probe from the UI thread that owns the ShowCursor count: an external probe cannot see it.
            _loggedPostHide = true;
            var probe = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
            GetCursorInfo(ref probe);
            LaunchDiagnostics.Write($"event=cinema.cursor-posthide flags=0x{probe.Flags:X} hCursor=0x{probe.Cursor:X}");
        }
        var dx = (long)current.X - _hideBaseline.X;
        var dy = (long)current.Y - _hideBaseline.Y;
        return dx * dx + dy * dy >= (long)thresholdPixels * thresholdPixels;
    }

    private static void WriteHideDiagnostics(NativePoint point)
    {
        var info = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
        GetCursorInfo(ref info);
        var screenPoint = info.ScreenPosition;
        var hwnd = WindowFromPoint(screenPoint);
        var className = new char[128];
        var length = hwnd == 0 ? 0 : GetClassName(hwnd, className, className.Length);
        var name = length > 0 ? new string(className, 0, length) : "unknown";
        LaunchDiagnostics.Write($"event=cinema.cursor-hide-diagnostic flags=0x{info.Flags:X} hCursor=0x{info.Cursor:X} ptScreenPos={screenPoint.X},{screenPoint.Y} windowClass=\"{name}\"");
    }

    private void RefreshChildWindows()
    {
        foreach (var child in EnumerateDescendants(_rootHwnd))
            AddSubclass(child, required: false);
    }

    private IEnumerable<nint> EnumerateDescendants(nint parent)
    {
        var child = FindWindowEx(parent, 0, 0, 0);
        while (child != 0)
        {
            yield return child;
            foreach (var descendant in EnumerateDescendants(child))
                yield return descendant;
            child = FindWindowEx(parent, child, 0, 0);
        }
    }

    private void AddSubclass(nint hwnd, bool required)
    {
        if (hwnd == 0 || !_subclassedWindows.Add(hwnd)) return;
        if (SetWindowSubclass(hwnd, _subclassProcedure, SubclassId, 0)) return;

        _subclassedWindows.Remove(hwnd);
        var error = Marshal.GetLastWin32Error();
        LaunchDiagnostics.Write($"event=cinema.cursor.hook.failed hwnd=0x{hwnd:X} error={error} required={required}");
        if (required) throw new Win32Exception(error, "Could not install the cinema cursor window hook.");
    }

    private nint WindowSubclassProc(nint hwnd, uint message, nint wParam, nint lParam,
        nuint idSubclass, nuint refData)
    {
        if (message == WmSetCursor && _hidden && unchecked((short)(long)lParam) == HtClient)
        {
            if (!_loggedHide)
            {
                _loggedHide = true;
                LaunchDiagnostics.Write($"event=cinema.cursor-hidden hwnd=0x{hwnd:X} subclassed={_subclassedWindows.Count}");
            }
            SetCursor(_transparentCursor);
            return 1;
        }

        var result = DefSubclassProc(hwnd, message, wParam, lParam);
        if (message == WmNcDestroy)
        {
            RemoveWindowSubclass(hwnd, _subclassProcedure, SubclassId);
            _subclassedWindows.Remove(hwnd);
        }
        return result;
    }

    // A same-position refresh is not enough: Windows only re-queries the cursor on real pointer motion, so
    // an idle pointer would keep the arrow. Move one pixel out and back to force WM_SETCURSOR; this stays
    // below the movement threshold so it is not mistaken for user input.
    // The robust, shape-independent hide: one balanced ShowCursor(FALSE) while hidden, one ShowCursor(TRUE)
    // on show/dispose. The island re-applies its own arrow after a nudge, but a negative display count wins.
    private bool _displayCountLowered;
    private bool _loggedPostHide;
    private long _hiddenAtTick;

    private void ApplySystemCursorVisibility(bool hidden)
    {
        if (hidden && !_displayCountLowered)
        {
            _displayCountLowered = true;
            var count = ShowCursor(false);
            LaunchDiagnostics.Write($"event=cinema.cursor-displaycount count={count}");
        }
        else if (!hidden && _displayCountLowered)
        {
            _displayCountLowered = false;
            ShowCursor(true);
        }
    }

    private static void NudgePointer(NativePoint point)
    {
        SetCursorPos(point.X + 1, point.Y);
        SetCursorPos(point.X, point.Y);
    }

    private void RestoreCursorAtPointer()
    {
        if (!GetCursorPos(out var point)) return;
        var hwnd = WindowFromPoint(point);
        if (hwnd == 0 || (hwnd != _rootHwnd && !IsChild(_rootHwnd, hwnd))) return;
        SendMessage(hwnd, WmSetCursor, hwnd, (nint)((WmMouseMove << 16) | HtClient));
    }

    public void Dispose()
    {
        if (_disposed) return;
        SetHidden(false);
        _disposed = true;
        foreach (var hwnd in _subclassedWindows.ToArray())
        {
            if (IsWindow(hwnd)) RemoveWindowSubclass(hwnd, _subclassProcedure, SubclassId);
        }
        _subclassedWindows.Clear();
    }

    private delegate nint SubclassProcedure(nint hwnd, uint message, nint wParam, nint lParam,
        nuint idSubclass, nuint refData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(nint hwnd, SubclassProcedure callback, nuint idSubclass, nuint refData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProcedure callback, nuint idSubclass);

    [DllImport("comctl32.dll")]
    private static extern nint DefSubclassProc(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll", EntryPoint = "FindWindowExW", SetLastError = true)]
    private static extern nint FindWindowEx(nint parent, nint childAfter, nint className, nint windowName);

    [DllImport("user32.dll")]
    private static extern nint SetCursor(nint cursor);

    [DllImport("user32.dll", EntryPoint = "LoadCursorW", SetLastError = true)]
    private static extern nint LoadCursor(nint module, nint resourceId);

    [DllImport("kernel32.dll", EntryPoint = "LoadLibraryW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint LoadLibrary(string fileName);

    [DllImport("kernel32.dll", EntryPoint = "GetModuleHandleW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint GetModuleHandle(string? moduleName);

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern nint SendMessage(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern int ShowCursor([MarshalAs(UnmanagedType.Bool)] bool show);

    [DllImport("user32.dll")]
    private static extern nint WindowFromPoint(NativePoint point);

    [DllImport("user32.dll", EntryPoint = "GetCursorInfo", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorInfo(ref CursorInfo info);

    [DllImport("user32.dll", EntryPoint = "GetClassNameW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int GetClassName(nint hwnd, [Out] char[] className, int maxCount);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsChild(nint parent, nint child);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(nint hwnd);

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
}
