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
    private readonly HashSet<nint> _subclassedWindows = new();
    private readonly SubclassProcedure _subclassProcedure;
    private bool _hidden;
    private bool _loggedHide;
    private bool _disposed;

    internal CinemaCursorController(nint rootHwnd)
    {
        _rootHwnd = rootHwnd;
        _subclassProcedure = WindowSubclassProc;
        AddSubclass(rootHwnd, required: true);
    }

    internal void SetHidden(bool hidden)
    {
        if (_disposed) return;
        _hidden = hidden;
        _loggedHide = false;
        LaunchDiagnostics.Write($"event=cinema.cursor-set hidden={hidden}");
        RefreshChildWindows();
        // Re-evaluate the cursor under the pointer now in both directions; without this a hide only
        // takes effect on the next WM_SETCURSOR, i.e. after a mouse move, which immediately reveals it again.
        RestoreCursorAtPointer();
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
        if (required) throw new Win32Exception(error, "Could not install the cinema cursor window hook.");
        LaunchDiagnostics.Write($"Cinema cursor child hook failed: hwnd=0x{hwnd:X}; error={error}");
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
            SetCursor(0);
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

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern nint SendMessage(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    private static extern nint WindowFromPoint(NativePoint point);

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
}
