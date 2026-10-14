namespace Tvivo.Core;

public enum PlaybackEngineState
{
    Other,
    Playing,
    Buffering,
    Paused,
    Stopped,
    Ended,
    Failed,
}

public enum PlaybackInterruption
{
    None,
    Stall,
    UnexpectedEnd,
    Error,
}

public enum PlaybackRecoveryAction
{
    None,
    ResumeSameMedia,
    Reopen,
    Stop,
}

public sealed record PlaybackInterruptionSnapshot(
    PlaybackEngineState State,
    PlaybackAttemptResult? Failure,
    TimeSpan NoForwardProgressFor,
    bool HasForwardProgress,
    long PositionMilliseconds,
    long? DurationMilliseconds,
    bool ExplicitUserAction,
    long SessionGeneration,
    long CurrentGeneration,
    int? HttpStatus = null,
    bool ConnectionLimit = false);

/// <summary>Classifies playback interruptions without depending on an engine or UI framework.</summary>
public static class PlaybackInterruptionPolicy
{
    public static readonly TimeSpan StallThreshold = TimeSpan.FromSeconds(8);

    public static PlaybackInterruption Classify(PlaybackInterruptionSnapshot snapshot)
    {
        if (snapshot.SessionGeneration != snapshot.CurrentGeneration || snapshot.ExplicitUserAction)
            return PlaybackInterruption.None;

        if (snapshot.State == PlaybackEngineState.Failed || snapshot.Failure is not null)
            return PlaybackInterruption.Error;

        if (snapshot.State == PlaybackEngineState.Ended)
            return PlaybackEndPolicy.IsGenuineEnd(
                snapshot.PositionMilliseconds, snapshot.DurationMilliseconds, ended: true)
                ? PlaybackInterruption.None
                : PlaybackInterruption.UnexpectedEnd;

        if (snapshot.State is PlaybackEngineState.Stopped or PlaybackEngineState.Paused)
            return PlaybackInterruption.UnexpectedEnd;

        if (snapshot.State is PlaybackEngineState.Playing or PlaybackEngineState.Buffering &&
            snapshot.HasForwardProgress && snapshot.NoForwardProgressFor >= StallThreshold)
            return PlaybackInterruption.Stall;

        return PlaybackInterruption.None;
    }

    public static PlaybackRecoveryAction NextAction(
        PlaybackInterruption interruption,
        StreamKind kind,
        bool engineHasMedia,
        int attemptsUsed,
        int maximumAttempts,
        int? httpStatus = null,
        bool connectionLimit = false,
        PlaybackAttemptResult? failure = null)
    {
        if (interruption == PlaybackInterruption.None)
            return PlaybackRecoveryAction.None;
        if (httpStatus is 403 or 429 || connectionLimit || failure == PlaybackAttemptResult.Forbidden ||
            attemptsUsed >= PlaybackRetryPolicy.ClampAttempts(maximumAttempts))
            return PlaybackRecoveryAction.Stop;
        if (engineHasMedia && kind != StreamKind.Live)
            return PlaybackRecoveryAction.ResumeSameMedia;
        return PlaybackRecoveryAction.Reopen;
    }
}

/// <summary>Counts all recovery operations in an interruption episode, resetting after sustained progress.</summary>
public sealed class PlaybackInterruptionBudget
{
    public static readonly TimeSpan HealthyResetPeriod = TimeSpan.FromSeconds(30);
    private TimeSpan? _healthyProgressBeganAt;

    public int AttemptsUsed { get; private set; }

    public bool TryConsume(int maximumAttempts)
    {
        if (AttemptsUsed >= PlaybackRetryPolicy.ClampAttempts(maximumAttempts))
            return false;
        AttemptsUsed++;
        _healthyProgressBeganAt = null;
        return true;
    }

    public void RecordForwardProgress(TimeSpan now)
    {
        _healthyProgressBeganAt ??= now;
        if (now - _healthyProgressBeganAt >= HealthyResetPeriod)
        {
            AttemptsUsed = 0;
            _healthyProgressBeganAt = now;
        }
    }

    public void Reset()
    {
        AttemptsUsed = 0;
        _healthyProgressBeganAt = null;
    }
}
