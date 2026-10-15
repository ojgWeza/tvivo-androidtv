namespace Tvivo.Core;

public enum SleepTimerState
{
    Off,
    Armed,
    Warning,
    Fading,
    Ended,
}

public enum SleepTimerMode
{
    Duration,
    EndOfCurrentItem,
}

public enum SleepTimerNoticeReason
{
    None,
    Cancelled,
    ItemChanged,
    FinalFailure,
}

public enum SleepTimerRemainingUnit
{
    Minutes,
    Seconds,
}

public sealed record SleepTimerDecision(
    SleepTimerState State,
    bool ShowWarning = false,
    bool StopPlayback = false,
    double FadeFraction = 0,
    SleepTimerNoticeReason NoticeReason = SleepTimerNoticeReason.None);

public readonly record struct SleepTimerRemainingDisplay(int Value, SleepTimerRemainingUnit Unit);

/// <summary>Applies sleep-timer transitions using caller-supplied monotonic timestamps.</summary>
public sealed class SleepTimerPolicy
{
    public static IReadOnlyList<int> PresetMinutes { get; } = Array.AsReadOnly(new[] { 15, 30, 45, 60, 90 });
    public static readonly TimeSpan MinimumDuration = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan MaximumDuration = TimeSpan.FromMinutes(240);
    public static readonly TimeSpan DurationStep = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan DefaultDuration = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan WarningLead = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan FadeDuration = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan ExtensionDuration = TimeSpan.FromMinutes(15);

    private bool _warningShown;

    public SleepTimerState State { get; private set; } = SleepTimerState.Off;
    public SleepTimerMode? Mode { get; private set; }
    public TimeSpan? Deadline { get; private set; }
    public string? TargetItemIdentity { get; private set; }

    public SleepTimerDecision StartPreset(TimeSpan now, int minutes)
    {
        if (!PresetMinutes.Contains(minutes))
            throw new ArgumentOutOfRangeException(nameof(minutes), "The value must be a supported sleep-timer preset.");

        return StartDuration(now, TimeSpan.FromMinutes(minutes));
    }

    public SleepTimerDecision StartCustom(TimeSpan now, int minutes = 30) =>
        StartDuration(now, TimeSpan.FromMinutes(ClampCustomMinutes(minutes)));

    public SleepTimerDecision StartDuration(TimeSpan now, TimeSpan duration)
    {
        var clamped = ClampDuration(duration);
        SetTimer(SleepTimerMode.Duration, now + clamped, targetItemIdentity: null);
        return Decision();
    }

    /// <summary>Returns false for Live, leaving the current timer unchanged.</summary>
    public bool TryStartEndOfCurrentItem(string itemIdentity, bool isLive)
    {
        ArgumentException.ThrowIfNullOrEmpty(itemIdentity);
        if (isLive)
            return false;

        SetTimer(SleepTimerMode.EndOfCurrentItem, deadline: null, itemIdentity);
        return true;
    }

    public SleepTimerDecision Tick(TimeSpan now)
    {
        if (State is SleepTimerState.Off or SleepTimerState.Ended || Mode != SleepTimerMode.Duration ||
            Deadline is not { } deadline)
            return Decision();

        var remaining = deadline - now;
        if (remaining <= TimeSpan.Zero)
        {
            State = SleepTimerState.Ended;
            return Decision(stopPlayback: true);
        }

        var showWarning = false;
        if (!_warningShown && remaining <= WarningLead)
        {
            _warningShown = true;
            showWarning = true;
        }

        State = remaining <= FadeDuration
            ? SleepTimerState.Fading
            : _warningShown ? SleepTimerState.Warning : SleepTimerState.Armed;

        return Decision(showWarning: showWarning, fadeFraction: GetFadeFraction(now));
    }

    /// <summary>Adds fifteen minutes to the existing deadline and dismisses the current warning.</summary>
    public SleepTimerDecision Extend(TimeSpan now)
    {
        if (State is SleepTimerState.Off or SleepTimerState.Ended || Mode != SleepTimerMode.Duration ||
            Deadline is not { } deadline)
            return Decision();

        if (now >= deadline)
            return Tick(now);

        Deadline = deadline + ExtensionDuration;
        _warningShown = false;
        State = SleepTimerState.Armed;
        return Decision();
    }

    public SleepTimerDecision Cancel()
    {
        if (State is SleepTimerState.Off or SleepTimerState.Ended)
            return Decision();

        TurnOff();
        return Decision(noticeReason: SleepTimerNoticeReason.Cancelled);
    }

    /// <summary>Manual changes cancel an end-of-item timer but leave a duration timer running.</summary>
    public SleepTimerDecision ManualItemChange()
    {
        if (State is SleepTimerState.Off or SleepTimerState.Ended || Mode != SleepTimerMode.EndOfCurrentItem)
            return Decision();

        TurnOff();
        return Decision(noticeReason: SleepTimerNoticeReason.ItemChanged);
    }

    public SleepTimerDecision GenuineNaturalEnd(string itemIdentity, bool isGenuineEnd)
    {
        ArgumentException.ThrowIfNullOrEmpty(itemIdentity);
        if (State is SleepTimerState.Off or SleepTimerState.Ended || Mode != SleepTimerMode.EndOfCurrentItem ||
            !isGenuineEnd || !StringComparer.Ordinal.Equals(TargetItemIdentity, itemIdentity))
            return Decision();

        State = SleepTimerState.Ended;
        return Decision(stopPlayback: true);
    }

    /// <summary>Ignores callbacks from an older playback generation before applying the natural-end decision.</summary>
    public SleepTimerDecision GenuineNaturalEnd(
        string itemIdentity,
        bool isGenuineEnd,
        long callbackGeneration,
        long currentGeneration)
    {
        if (callbackGeneration != currentGeneration)
            return Decision();

        return GenuineNaturalEnd(itemIdentity, isGenuineEnd);
    }

    public SleepTimerDecision FinalFailure()
    {
        if (State is SleepTimerState.Off or SleepTimerState.Ended)
            return Decision();

        TurnOff();
        return Decision(noticeReason: SleepTimerNoticeReason.FinalFailure);
    }

    /// <summary>Returns a 0..1 fade progress for the final five seconds; v1 may ignore it.</summary>
    public double GetFadeFraction(TimeSpan now)
    {
        if (Mode != SleepTimerMode.Duration || Deadline is not { } deadline)
            return 0;

        var remaining = deadline - now;
        if (remaining >= FadeDuration)
            return 0;
        if (remaining <= TimeSpan.Zero)
            return 1;

        return Math.Clamp(1d - remaining.Ticks / (double)FadeDuration.Ticks, 0d, 1d);
    }

    public SleepTimerRemainingDisplay? GetRemainingDisplay(TimeSpan now)
    {
        if (Mode != SleepTimerMode.Duration || Deadline is not { } deadline)
            return null;

        var remaining = deadline - now;
        if (remaining < TimeSpan.FromMinutes(1))
            return new SleepTimerRemainingDisplay(CeilUnits(remaining.Ticks, TimeSpan.TicksPerSecond), SleepTimerRemainingUnit.Seconds);

        return new SleepTimerRemainingDisplay(CeilUnits(remaining.Ticks, TimeSpan.TicksPerMinute), SleepTimerRemainingUnit.Minutes);
    }

    public static int ClampCustomMinutes(int minutes)
    {
        var clamped = Math.Clamp(minutes, 5, 240);
        return (int)(Math.Round(clamped / 5d, MidpointRounding.AwayFromZero) * 5);
    }

    private static TimeSpan ClampDuration(TimeSpan duration)
    {
        var clampedMinutes = Math.Clamp(duration.TotalMinutes, 5d, 240d);
        var steppedMinutes = Math.Round(clampedMinutes / 5d, MidpointRounding.AwayFromZero) * 5;
        return TimeSpan.FromMinutes(steppedMinutes);
    }

    private static int CeilUnits(long ticks, long ticksPerUnit)
    {
        if (ticks <= 0)
            return 0;

        var units = ticks / ticksPerUnit + (ticks % ticksPerUnit == 0 ? 0 : 1);
        return (int)Math.Min(int.MaxValue, units);
    }

    private void SetTimer(SleepTimerMode mode, TimeSpan? deadline, string? targetItemIdentity)
    {
        Mode = mode;
        Deadline = deadline;
        TargetItemIdentity = targetItemIdentity;
        _warningShown = false;
        State = SleepTimerState.Armed;
    }

    private void TurnOff()
    {
        State = SleepTimerState.Off;
        Mode = null;
        Deadline = null;
        TargetItemIdentity = null;
        _warningShown = false;
    }

    private SleepTimerDecision Decision(
        bool showWarning = false,
        bool stopPlayback = false,
        double fadeFraction = 0,
        SleepTimerNoticeReason noticeReason = SleepTimerNoticeReason.None) =>
        new(State, showWarning, stopPlayback, fadeFraction, noticeReason);
}
