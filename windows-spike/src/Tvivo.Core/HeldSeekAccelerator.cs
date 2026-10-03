namespace Tvivo.Core;

/// <summary>Returns the seek distance for one held-seek repeat at the given hold duration.</summary>
public static class HeldSeekAccelerator
{
    private static readonly TimeSpan AccelerationStart = TimeSpan.FromMilliseconds(400);
    private static readonly TimeSpan RampInterval = TimeSpan.FromMilliseconds(1500);
    private const long InitialStepMilliseconds = 30_000;
    private const long MaximumStepMilliseconds = 300_000;

    public static long StepMilliseconds(TimeSpan heldDuration)
    {
        if (heldDuration < AccelerationStart)
            return 0;

        var rampIntervals = (int)((heldDuration - AccelerationStart).Ticks / RampInterval.Ticks);
        var step = InitialStepMilliseconds;
        for (var i = 0; i < rampIntervals && step < MaximumStepMilliseconds; i++)
            step = Math.Min(MaximumStepMilliseconds, step * 2);
        return step;
    }
}
