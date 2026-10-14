using System.Diagnostics;

namespace Tvivo.Infrastructure;

/// <summary>Records completed named operations in a bounded, non-blocking ring buffer.</summary>
public readonly struct PerfScope : IDisposable
{
    private const int Capacity = 64;
    private static readonly long SlowOperationThresholdTicks = Stopwatch.Frequency / 4;
    private static readonly long SlowLogWindowTicks = Stopwatch.Frequency * 10;
    private static readonly Slot[] Slots = Enumerable.Range(0, Capacity).Select(_ => new Slot()).ToArray();
    private static long _nextSequence;
    private static long _slowWindowStartedAt;
    private static int _slowLogsInWindow;
    private static Action<string>? _logger;
    private readonly string? _name;
    private readonly long _startedAt;

    private PerfScope(string name, long startedAt)
    {
        _name = name;
        _startedAt = startedAt;
    }

    public static Action<string>? Logger
    {
        get => Volatile.Read(ref _logger);
        set => Volatile.Write(ref _logger, value);
    }

    public static PerfScope Begin(string name) => new(name, Stopwatch.GetTimestamp());

    public static PerfPhaseScope BeginPhase(string phase) => new(phase, Stopwatch.GetTimestamp());

    public static void LogRefreshPhase(string phase, long durationTicks)
    {
        var milliseconds = durationTicks * 1000d / Stopwatch.Frequency;
        Logger?.Invoke($"event=perf.refresh phase={phase} ms={milliseconds:0.0}");
    }

    public static IReadOnlyList<PerfOperation> GetOverlapping(long startTick, long endTick, int maxCount = 5)
    {
        if (endTick <= startTick || maxCount <= 0) return Array.Empty<PerfOperation>();
        var matches = new List<PerfOperation>(Math.Min(maxCount, Capacity));
        foreach (var slot in Slots)
        {
            if (Interlocked.CompareExchange(ref slot.Gate, 1, 0) != 0) continue;
            try
            {
                var name = slot.Name;
                var start = slot.StartTick;
                var end = slot.EndTick;
                if (name is not null && start < endTick && end > startTick)
                    matches.Add(new PerfOperation(name, start, end));
            }
            finally
            {
                Volatile.Write(ref slot.Gate, 0);
            }
        }

        return matches.OrderByDescending(operation => operation.DurationTicks)
            .Take(maxCount)
            .ToArray();
    }

    public void Dispose()
    {
        if (_name is null) return;
        var endedAt = Stopwatch.GetTimestamp();
        Record(_name, _startedAt, endedAt);
        var duration = endedAt - _startedAt;
        if (duration > SlowOperationThresholdTicks && ShouldLogSlowOperation(endedAt))
            Logger?.Invoke($"event=perf.slowop name={_name} ms={duration * 1000d / Stopwatch.Frequency:0.0}");
    }

    private static void Record(string name, long startTick, long endTick)
    {
        var sequence = Interlocked.Increment(ref _nextSequence);
        var slot = Slots[(int)((sequence - 1) % Capacity)];
        if (Interlocked.CompareExchange(ref slot.Gate, 1, 0) != 0) return;
        try
        {
            slot.Name = name;
            slot.StartTick = startTick;
            slot.EndTick = endTick;
        }
        finally
        {
            Volatile.Write(ref slot.Gate, 0);
        }
    }

    private static bool ShouldLogSlowOperation(long now)
    {
        var windowStart = Interlocked.Read(ref _slowWindowStartedAt);
        if (windowStart == 0 || now - windowStart >= SlowLogWindowTicks)
        {
            if (Interlocked.CompareExchange(ref _slowWindowStartedAt, now, windowStart) == windowStart)
                Interlocked.Exchange(ref _slowLogsInWindow, 0);
        }
        return Interlocked.Increment(ref _slowLogsInWindow) <= 10;
    }

    private sealed class Slot
    {
        public int Gate;
        public string? Name;
        public long StartTick;
        public long EndTick;
    }
}

public readonly struct PerfPhaseScope : IDisposable
{
    private readonly string? _phase;
    private readonly long _startedAt;

    internal PerfPhaseScope(string phase, long startedAt)
    {
        _phase = phase;
        _startedAt = startedAt;
    }

    public void Dispose()
    {
        if (_phase is null) return;
        PerfScope.LogRefreshPhase(_phase, Stopwatch.GetTimestamp() - _startedAt);
    }
}

public readonly record struct PerfOperation(string Name, long StartTick, long EndTick)
{
    public long DurationTicks => EndTick - StartTick;
}
