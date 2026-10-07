namespace Tvivo.Core;

public sealed class PlaybackFailurePolicy
{
    private long _failedGeneration = -1;

    public bool TryHandle(long callbackGeneration, long currentGeneration, long activeGeneration, bool ready)
    {
        if (!ready || callbackGeneration != currentGeneration || activeGeneration != currentGeneration)
            return false;

        while (true)
        {
            var previous = Volatile.Read(ref _failedGeneration);
            if (previous == currentGeneration)
                return false;
            if (Interlocked.CompareExchange(ref _failedGeneration, currentGeneration, previous) == previous)
                return true;
        }
    }

    public bool HasFailed(long generation) => Volatile.Read(ref _failedGeneration) == generation;

    public bool ShouldAutoAdvance(long generation) => !HasFailed(generation);
}
