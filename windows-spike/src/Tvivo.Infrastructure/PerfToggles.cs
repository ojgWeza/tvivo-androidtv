namespace Tvivo.Infrastructure;

/// <summary>Startup-only switches for isolating UI performance suspects.</summary>
public static class PerfToggles
{
    private static readonly string[] KnownToggles =
    [
        "spotlight",
        "epg",
        "xamltrace",
        "playbacktimer",
        "perftimers",
        "spotlightart",
        "clips",
        "lens",
        "shelfclip",
        "artwork",
    ];

    private static readonly IReadOnlySet<string> DisabledToggles = ParseDisabled(
        Environment.GetEnvironmentVariable("TVIVO_PERF_DISABLE"));
    private static int _startupLogWritten;

    public static string DisabledList => string.Join(",", KnownToggles.Where(DisabledToggles.Contains));

    public static bool IsDisabled(string toggle) => DisabledToggles.Contains(toggle);

    public static IReadOnlySet<string> ParseDisabled(string? value)
    {
        var disabled = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(value)) return disabled;

        foreach (var item in value.Split(','))
        {
            var toggle = item.Trim();
            if (KnownToggles.Contains(toggle, StringComparer.OrdinalIgnoreCase))
                disabled.Add(toggle);
        }

        return disabled;
    }

    public static void LogStartup(Action<string> log)
    {
        ArgumentNullException.ThrowIfNull(log);
        if (Interlocked.Exchange(ref _startupLogWritten, 1) != 0) return;
        log($"event=perf.toggles disabled={DisabledList}");
    }
}
