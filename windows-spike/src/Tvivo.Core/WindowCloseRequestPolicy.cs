namespace Tvivo.Core;

public static class WindowCloseRequestPolicy
{
    // A non-Alt system close is normally issued by the taskbar; WM_CLOSE alone
    // does not identify its source.
    public static string ClassifyNative(bool systemCloseCommand, bool altHeld) =>
        !systemCloseCommand ? "unknown" : altHeld ? "alt-f4" : "taskbar";
}
