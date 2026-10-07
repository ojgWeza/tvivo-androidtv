namespace Tvivo.Core;

public enum StallDecision
{
    None,
    Recover,
    GiveUp,
}

public sealed class StallRecoveryPolicy(TimeSpan timeout)
{
    private long _sessionGeneration;
    private bool _hasSession;
    private bool _hasBaseline;
    private long _lastPositionMs;
    private TimeSpan _lastChangeAt;
    private bool _hasAdvanced;
    private bool _gaveUp;

    public bool RecoveryAttempted { get; private set; }

    public void BeginSession(long generation, bool isRecovery)
    {
        if (!isRecovery)
            RecoveryAttempted = false;

        _sessionGeneration = generation;
        _hasSession = true;
        _hasBaseline = false;
        _hasAdvanced = false;
        _gaveUp = false;
    }

    public StallDecision Observe(
        long currentGeneration,
        bool sessionReady,
        bool eligible,
        long positionMs,
        TimeSpan now)
    {
        if (!_hasSession || currentGeneration != _sessionGeneration || !sessionReady)
        {
            ClearBaseline();
            return StallDecision.None;
        }

        if (!eligible)
        {
            ClearBaseline();
            return StallDecision.None;
        }

        if (!_hasBaseline)
        {
            SetBaseline(positionMs, now);
            return StallDecision.None;
        }

        if (positionMs != _lastPositionMs)
        {
            var delta = positionMs - _lastPositionMs;
            var elapsed = now - _lastChangeAt;
            var maxPlausibleAdvanceMs = Math.Max(0, elapsed.TotalMilliseconds) * 2 + 1000;
            if (delta > 0 && delta <= maxPlausibleAdvanceMs)
                _hasAdvanced = true;

            SetBaseline(positionMs, now);
            return StallDecision.None;
        }

        if (now - _lastChangeAt < timeout)
            return StallDecision.None;

        _lastChangeAt = now;
        if (_gaveUp)
            return StallDecision.None;

        if (_hasAdvanced && !RecoveryAttempted)
        {
            RecoveryAttempted = true;
            return StallDecision.Recover;
        }

        _gaveUp = true;
        return StallDecision.GiveUp;
    }

    private void SetBaseline(long positionMs, TimeSpan now)
    {
        _hasBaseline = true;
        _lastPositionMs = positionMs;
        _lastChangeAt = now;
    }

    private void ClearBaseline() => _hasBaseline = false;
}
