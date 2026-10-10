using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace Tvivo.App;

internal static class SingleInstanceGuard
{
    private const string DefaultMutexName = @"Local\Tvivo.WinUI.SingleInstance";
    private const int SwRestore = 9;

    internal static string GetMutexName(string? dataRoot)
    {
        if (dataRoot is null)
            return DefaultMutexName;

        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(dataRoot)))[..8];
        return $"{DefaultMutexName}.{hash}";
    }

    internal static bool TryAcquire(string mutexName, out Mutex? mutex)
    {
        var candidate = new Mutex(initiallyOwned: true, mutexName, out var createdNew);
        if (createdNew)
        {
            mutex = candidate;
            return true;
        }

        var acquired = false;
        try
        {
            acquired = candidate.WaitOne(0);
        }
        catch (AbandonedMutexException)
        {
            acquired = true;
        }

        mutex = acquired ? candidate : null;
        if (!acquired)
            candidate.Dispose();
        return acquired;
    }

    internal static bool ActivateExistingInstance(int currentProcessId)
    {
        try
        {
            var otherProcessIds = new HashSet<int>();
            foreach (var process in Process.GetProcessesByName("Tvivo.App"))
            {
                using (process)
                {
                    if (process.Id != currentProcessId)
                        otherProcessIds.Add(process.Id);
                }
            }

            if (otherProcessIds.Count == 0)
                return false;

            var targetWindow = IntPtr.Zero;
            EnumWindows((window, _) =>
            {
                if (!IsWindowVisible(window))
                    return true;

                GetWindowThreadProcessId(window, out var processId);
                if (!otherProcessIds.Contains((int)processId))
                    return true;

                targetWindow = window;
                return false;
            }, IntPtr.Zero);

            if (targetWindow == IntPtr.Zero)
                return false;

            if (IsIconic(targetWindow))
                ShowWindow(targetWindow, SwRestore);
            return SetForegroundWindow(targetWindow);
        }
        catch
        {
            return false;
        }
    }

    private delegate bool EnumWindowsCallback(IntPtr window, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(IntPtr window);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr window, int command);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr window);
}
