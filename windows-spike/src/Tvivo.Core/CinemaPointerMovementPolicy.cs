namespace Tvivo.Core;

public static class CinemaPointerMovementPolicy
{
    public const int CreationGracePeriodMilliseconds = 400;

    public static bool HasMoved(int baselineX, int baselineY, int currentX, int currentY, int thresholdPixels = 2)
    {
        var dx = Math.Abs((long)currentX - baselineX);
        var dy = Math.Abs((long)currentY - baselineY);
        var threshold = (long)Math.Max(0, thresholdPixels);
        if (threshold == 0 || dx >= threshold || dy >= threshold)
            return true;

        var thresholdSquared = threshold * threshold;
        return dx * dx >= thresholdSquared - dy * dy;
    }

    public static bool IsWithinCreationGracePeriod(long elapsedMilliseconds) =>
        elapsedMilliseconds < CreationGracePeriodMilliseconds;

    public static bool ShouldWakeForPointerMove(
        long elapsedMilliseconds,
        int baselineX,
        int baselineY,
        int currentX,
        int currentY,
        int thresholdPixels = 2) =>
        !IsWithinCreationGracePeriod(elapsedMilliseconds) &&
        HasMoved(baselineX, baselineY, currentX, currentY, thresholdPixels);

    public static bool ShouldWakeForPointerPoll(
        long elapsedMilliseconds,
        bool pointerOutsidePopup,
        int baselineX,
        int baselineY,
        int currentX,
        int currentY,
        int thresholdPixels = 2) =>
        !IsWithinCreationGracePeriod(elapsedMilliseconds) &&
        (pointerOutsidePopup || HasMoved(baselineX, baselineY, currentX, currentY, thresholdPixels));

    public static bool ShouldWakeForLeave() => false;

    public static string? GetInputWakeReason(long elapsedMilliseconds, bool isWheel) =>
        IsWithinCreationGracePeriod(elapsedMilliseconds) ? null : isWheel ? "wheel" : "click";
}
