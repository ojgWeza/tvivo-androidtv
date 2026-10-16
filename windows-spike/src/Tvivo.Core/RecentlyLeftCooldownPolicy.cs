namespace Tvivo.Core;

public enum RecentlyLeftCooldownResult
{
    NotEligible,
    WaitForProvider,
}

/// <summary>Tracks recently opened items and describes the quiet 403 cooldown checks.</summary>
public sealed class RecentlyLeftCooldownPolicy
{
    public static readonly TimeSpan RecentOpenWindow = TimeSpan.FromSeconds(70);
    public static readonly TimeSpan MaximumDuration = TimeSpan.FromSeconds(65);
    private static readonly TimeSpan[] CheckOffsets =
    [
        TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(25),
        TimeSpan.FromSeconds(45), TimeSpan.FromSeconds(58), TimeSpan.FromSeconds(65),
    ];
    private static readonly (TimeSpan End, string Message)[] StatusSlices =
    [
        (TimeSpan.FromSeconds(5), "Opening your video..."),
        (TimeSpan.FromSeconds(15), "The provider is getting it ready..."),
        (TimeSpan.FromSeconds(30), "Still connecting, this can take a moment..."),
        (TimeSpan.FromSeconds(45), "Almost there, checking again..."),
        (TimeSpan.FromSeconds(58), "Nearly ready..."),
        (TimeSpan.FromSeconds(65), "Last check..."),
    ];

    private readonly Func<TimeSpan> _monotonicNow;
    private readonly Dictionary<string, TimeSpan> _lastOpenedByItem = new(StringComparer.Ordinal);
    private string? _lastOpenedItem;
    private TimeSpan _lastOpenedAt;

    public RecentlyLeftCooldownPolicy(Func<TimeSpan>? monotonicNow = null) =>
        _monotonicNow = monotonicNow ?? (() => TimeSpan.FromSeconds(System.Diagnostics.Stopwatch.GetTimestamp() / (double)System.Diagnostics.Stopwatch.Frequency));

    public RecentlyLeftCooldownResult BeginOpen(string itemIdentity, StreamKind kind)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(itemIdentity);
        var now = _monotonicNow();
        var eligible = (kind is StreamKind.Movie or StreamKind.Episode) &&
            _lastOpenedByItem.TryGetValue(itemIdentity, out var previousItemOpen) &&
            now >= previousItemOpen && now - previousItemOpen <= RecentOpenWindow &&
            _lastOpenedItem is not null && !StringComparer.Ordinal.Equals(_lastOpenedItem, itemIdentity) &&
            _lastOpenedAt > previousItemOpen;

        _lastOpenedByItem[itemIdentity] = now;
        _lastOpenedItem = itemIdentity;
        _lastOpenedAt = now;
        return eligible ? RecentlyLeftCooldownResult.WaitForProvider : RecentlyLeftCooldownResult.NotEligible;
    }

    public static IReadOnlyList<TimeSpan> BuildSchedule(Func<double>? jitterFactor = null)
    {
        var result = new TimeSpan[CheckOffsets.Length];
        var previous = TimeSpan.Zero;
        for (var i = 0; i < CheckOffsets.Length; i++)
        {
            var factor = Math.Clamp(jitterFactor?.Invoke() ?? 1d, 0.9d, 1.1d);
            var ticks = (long)Math.Round(CheckOffsets[i].Ticks * factor);
            var next = TimeSpan.FromTicks(Math.Clamp(ticks, previous.Ticks + 1, MaximumDuration.Ticks));
            result[i] = next;
            previous = next;
        }
        return result;
    }

    public static string StatusAt(TimeSpan elapsed)
    {
        var clamped = elapsed < TimeSpan.Zero ? TimeSpan.Zero : elapsed;
        foreach (var slice in StatusSlices)
            if (clamped < slice.End)
                return slice.Message;
        return StatusSlices[^1].Message;
    }

    public static double ProgressFraction(TimeSpan elapsed) =>
        Math.Clamp(elapsed.TotalMilliseconds / MaximumDuration.TotalMilliseconds, 0d, 1d);

    public static bool IsCurrentSession(bool cancellationRequested, long expectedGeneration, long currentGeneration) =>
        !cancellationRequested && expectedGeneration == currentGeneration;
}
