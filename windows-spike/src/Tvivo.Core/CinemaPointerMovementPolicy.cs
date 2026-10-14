namespace Tvivo.Core;

public static class CinemaPointerMovementPolicy
{
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
}
