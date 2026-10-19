namespace Tvivo.Core;

public static class WindowCloseRequestPolicy
{
    public static readonly TimeSpan TeardownBudget = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan BackstopGrace = TimeSpan.FromMilliseconds(1500);

    public static TimeSpan? StageTimeout(TimeSpan elapsed)
    {
        var remaining = TeardownBudget - elapsed;
        return remaining <= TimeSpan.Zero ? null : TimeSpan.FromTicks(Math.Min(TimeSpan.FromSeconds(3).Ticks, remaining.Ticks));
    }

    // A non-Alt system close is normally issued by the taskbar; WM_CLOSE alone
    // does not identify its source.
    public static string ClassifyNative(bool systemCloseCommand, bool altHeld) =>
        !systemCloseCommand ? "unknown" : altHeld ? "alt-f4" : "taskbar";
}
