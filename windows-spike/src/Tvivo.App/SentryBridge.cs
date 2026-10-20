#if DEBUG
using System.Text.RegularExpressions;
using Sentry;

namespace Tvivo.App;

/// <summary>
/// Debug-only, opt-in Sentry feed. Active only when TVIVO_SENTRY_DSN is set; Release/MSI builds do not
/// reference the SDK at all. It receives ONLY lines that LaunchDiagnostics has already sanitised
/// (no raw exceptions, no screenshots, no PII), so it can never see more than the local log does.
/// </summary>
internal static partial class SentryBridge
{
    private static IDisposable? _sdk;

    internal static void Start(string version)
    {
        var dsn = Environment.GetEnvironmentVariable("TVIVO_SENTRY_DSN");
        if (string.IsNullOrWhiteSpace(dsn) || _sdk is not null) return;
        _sdk = SentrySdk.Init(options =>
        {
            options.Dsn = dsn;
            options.Release = $"tvivo-winui@{version}";
            options.Environment = "debug";
            options.SendDefaultPii = false;
            options.AttachStacktrace = false;
            options.AutoSessionTracking = true;
            options.MaxBreadcrumbs = 100;
            options.ServerName = "";
        });
        if (Environment.GetEnvironmentVariable("TVIVO_SENTRY_SELFTEST") == "1")
            SentrySdk.CaptureMessage("sentry.selftest: Tvivo Debug feed is connected", SentryLevel.Info);
    }

    internal static void Stop()
    {
        _sdk?.Dispose();
        _sdk = null;
    }

    /// <summary>Called with an already-sanitised log message.</summary>
    internal static void OnLog(string safeMessage)
    {
        if (_sdk is null) return;
        var name = EventName(safeMessage);
        if (!IsProblem(name, safeMessage))
        {
            SentrySdk.AddBreadcrumb(safeMessage, name, "tvivo");
            return;
        }
        SentrySdk.CaptureEvent(new SentryEvent
        {
            Message = new SentryMessage { Message = safeMessage },
            Level = name.Contains("fail", StringComparison.OrdinalIgnoreCase) || safeMessage.Contains("Unhandled", StringComparison.Ordinal)
                ? SentryLevel.Error : SentryLevel.Warning,
            // Group by event name, not by the changing numbers in the text.
            Fingerprint = new[] { name },
        });
    }

    private static string EventName(string message)
    {
        var match = EventRegex().Match(message);
        return match.Success ? match.Groups[1].Value : "log";
    }

    private static bool IsProblem(string name, string message) =>
        name is "perf.stall" or "playback.vlc.warning"
        || name.Contains("fail", StringComparison.OrdinalIgnoreCase)
        || name.Contains("error", StringComparison.OrdinalIgnoreCase)
        || name.Contains("timeout", StringComparison.OrdinalIgnoreCase)
        || message.StartsWith("Unhandled exception", StringComparison.Ordinal)
        || message.StartsWith("Unobserved task exception", StringComparison.Ordinal);

    [GeneratedRegex(@"\bevent=([A-Za-z0-9._-]+)")]
    private static partial Regex EventRegex();
}
#endif
