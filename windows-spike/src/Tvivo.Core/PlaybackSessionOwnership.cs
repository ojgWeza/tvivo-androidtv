namespace Tvivo.Core;

public static class PlaybackSessionOwnership
{
    public static bool CanCompleteSleepTimerStop(long endedGeneration, long currentGeneration, bool sleepTimerEnded) =>
        endedGeneration == currentGeneration && sleepTimerEnded;

    public static bool CanStopProgress(long ownerGeneration, long completingGeneration) =>
        ownerGeneration >= 0 && ownerGeneration == completingGeneration;
}
