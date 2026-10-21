using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.RegularExpressions;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Tvivo.Playback;

[assembly: InternalsVisibleTo("Tvivo.App.Tests")]

namespace Tvivo.App;

public partial class App : Application
{
    public static IServiceProvider Services { get; }
    private static Mutex? _singleInstanceMutex;
    private Window? _window;

    static App()
    {
        LaunchDiagnostics.Write("App static initialization entered");
        try
        {
            Services = ConfigureServices();
            LaunchDiagnostics.Write("App services configured");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("App static initialization failed", exception);
            throw;
        }
    }

    public App()
    {
        LaunchDiagnostics.Write("App constructor entered");
        PerfToggles.LogStartup(LaunchDiagnostics.Write);
        AppDomain.CurrentDomain.UnhandledException += OnUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
        ConfigureXamlDiagnostics();
        InitializeComponent();
        LaunchDiagnostics.Write("App XAML initialized");
    }

    private void ConfigureXamlDiagnostics()
    {
        if (PerfToggles.IsDisabled("xamltrace")) return;

        var debugSettings = DebugSettings;
        debugSettings.IsXamlResourceReferenceTracingEnabled = true;
        debugSettings.IsBindingTracingEnabled = true;
        debugSettings.XamlResourceReferenceFailed += (_, _) =>
            LaunchDiagnostics.Write("XAML resource reference failed");
        debugSettings.BindingFailed += (_, _) =>
            LaunchDiagnostics.Write("XAML binding failed");
#if TVIVO_XAML_DIAGNOSTICS
        debugSettings.FailFastOnErrors = true;
        LaunchDiagnostics.Write("XAML diagnostics enabled: FailFastOnErrors=true");
#else
        LaunchDiagnostics.Write("XAML diagnostics enabled: FailFastOnErrors=false");
#endif
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            var mutexName = SingleInstanceGuard.GetMutexName(Environment.GetEnvironmentVariable("TVIVO_DATA_ROOT"));
            if (!SingleInstanceGuard.TryAcquire(mutexName, out var acquiredMutex))
            {
                var activated = SingleInstanceGuard.ActivateExistingInstance(Process.GetCurrentProcess().Id);
                LaunchDiagnostics.Write($"event=app.second-instance activated-existing={activated.ToString().ToLowerInvariant()}");
                Environment.Exit(0);
                return;
            }
            _singleInstanceMutex = acquiredMutex;
            GC.KeepAlive(_singleInstanceMutex);

            LaunchDiagnostics.Write("OnLaunched entered");
            if (Environment.GetEnvironmentVariable("TVIVO_NET_LOG") == "1")
            {
                NetworkTally.Log = LaunchDiagnostics.Write;
                NetworkTally.StartPeriodicLog(TimeSpan.FromSeconds(30));
            }
            _window ??= new MainWindow();
            ((MainWindow)_window).WriteAppStartDiagnostics();
            ((MainWindow)_window).PrepareForActivation();
            LaunchDiagnostics.Write("MainWindow constructed; startup window state applied; activating");
            _window.Activate();
            LaunchDiagnostics.Write("MainWindow activated");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteExceptionDetails("OnLaunched failed", exception);
        }
    }

    private static IServiceProvider ConfigureServices()
    {
        var services = new ServiceCollection();
        services.AddSingleton<ICatalogProvider>(_ => new XtreamCatalogProvider(epgLog: LaunchDiagnostics.Write));
        services.AddSingleton<IEpgProvider>(sp => (IEpgProvider)sp.GetRequiredService<ICatalogProvider>());
        services.AddSingleton<EpgCoordinator>();
        services.AddSingleton<SqliteCatalogRepository>();
        services.AddSingleton<IFileSizeProbe>(_ => new HttpFileSizeProbe(log: LaunchDiagnostics.Write));
        services.AddSingleton<SpotlightSettingsService>();
        services.AddSingleton<CatalogRefreshService>();
        services.AddSingleton<ICredentialStore, DpapiCredentialStore>();
        services.AddSingleton<VlcPlaybackEngine>();
        services.AddSingleton<FFmpegInteropPlaybackEngine>();
        return services.BuildServiceProvider();
    }

    private static void OnUnhandledException(object sender, System.UnhandledExceptionEventArgs args)
    {
        if (args.ExceptionObject is Exception exception)
            LaunchDiagnostics.WriteException($"Unhandled exception (terminating={args.IsTerminating})", exception);
        else
            LaunchDiagnostics.Write($"Unhandled exception (terminating={args.IsTerminating})");
    }

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs args)
    {
        LaunchDiagnostics.WriteException("Unobserved task exception", args.Exception);
        args.SetObserved();
    }
}

internal static class LaunchDiagnostics
{
    private static readonly object Sync = new();
    private static readonly SecretScrubber Scrubber = new();
    private static readonly string SessionId = Guid.NewGuid().ToString("N");
    private const long MaxLogBytes = 1024 * 1024;
    private const int MaxExportLines = 400;
    private const int MaxExportChars = 65536;
    internal static string LogPath { get; set; } = Tvivo.Core.TvivoDataPaths.For("tvivo-launch.log");
    private static string _engine = "unknown";
    private static string _theme = "unknown";

    internal static void SetAppContext(string engine, string theme)
    {
        lock (Sync)
        {
            _engine = SanitizeLabel(engine);
            _theme = SanitizeLabel(theme);
        }
    }

    internal static void WriteAppStart()
    {
#if DEBUG
        const string configuration = "Debug";
#else
        const string configuration = "Release";
#endif
        var version = typeof(App).Assembly.GetName().Version?.ToString() ?? "unknown";
#if DEBUG
        SentryBridge.Start(version);
#endif
        Write($"event=app.start version={version} config={configuration} engine={_engine} theme={_theme}");
    }

    public static void SetActiveSecrets(ProviderConnection connection) =>
        Scrubber.SetActiveSecrets(connection.Endpoint.Host, connection.Username, connection.Password);

    public static void ClearActiveSecrets() => Scrubber.Clear();

    private static string Sanitize(string message)
    {
        // Values are replaced first: a bare media path, quoted MRL or exception message
        // may contain credentials without a URL scheme or named field.
        var safeMessage = Scrubber.Scrub(message);
        safeMessage = Regex.Replace(safeMessage,
            @"(?i)\b(?:https?|rtsp)://[^\s\]\)\}""']+", "[redacted-url]");
        safeMessage = Regex.Replace(safeMessage,
            @"(?i)/(?:series|movie|live)/[^/\s""']+/[^/\s""']+/[^\s\)\}""']+", "[redacted-path]");
        safeMessage = Regex.Replace(safeMessage,
            @"(?i)\b(?:username|password|accountid|account_id|itemid|item_id|streamid|stream_id|episodeid|episode_id)\s*[=:]\s*[^\s;,]+", "[redacted-field]");
        safeMessage = Regex.Replace(safeMessage,
            @"(?i)\b(?:set-cookie|cookie)\s*:\s*[^\r\n]+", "[redacted-cookie]");
        if (safeMessage.Length > 4096) safeMessage = safeMessage[..4096] + "[truncated]";
        return safeMessage;
    }

    public static void Write(string message)
    {
        var safeMessage = Sanitize(message);
        var line = $"{DateTimeOffset.Now:O} session={SessionId} {safeMessage}{Environment.NewLine}";
        Debug.Write(line);
#if DEBUG
        SentryBridge.OnLog(safeMessage);
#endif
        try
        {
            lock (Sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
                DiagnosticLogRotation.RotateIfNeeded(LogPath, Encoding.UTF8.GetByteCount(line), MaxLogBytes);
                File.AppendAllText(LogPath, line);
            }
        }
        catch
        {
            Debug.WriteLine("Unable to write tvivo-launch.log");
        }
    }

    public static string ExportRecent()
    {
        lock (Sync)
        {
            try
            {
                if (!File.Exists(LogPath)) return "No diagnostics available.";
                var lines = new Queue<string>();
                var noisyLinesOmitted = 0;
                foreach (var line in File.ReadLines(LogPath))
                {
                    if (!line.Contains($"session={SessionId} ", StringComparison.Ordinal)) continue;
                    var safeLine = Sanitize(line);
                    if (IsNoisy(safeLine))
                    {
                        noisyLinesOmitted++;
                        continue;
                    }
                    lines.Enqueue(safeLine);
                    if (lines.Count > MaxExportLines) lines.Dequeue();
                }
                var header = Sanitize($"Diagnostics appVersion={typeof(App).Assembly.GetName().Version?.ToString() ?? "unknown"} config={BuildConfiguration()} engine={_engine} theme={_theme} os=\"{SanitizeLabel(Environment.OSVersion.VersionString)}\" session={SessionId} utc={DateTimeOffset.UtcNow:O}");
                var outputLines = new List<string> { header, $"{noisyLinesOmitted} noisy lines omitted" };
                outputLines.AddRange(lines);
                while (outputLines.Count > 2 && string.Join(Environment.NewLine, outputLines).Length > MaxExportChars)
                    outputLines.RemoveAt(2);
                var output = string.Join(Environment.NewLine, outputLines);
                return output.Length > MaxExportChars ? output[..MaxExportChars] : output;
            }
            catch
            {
                return "Diagnostics could not be read.";
            }
        }
    }

    private static bool IsNoisy(string line) =>
        line.Contains("event=perf.surface", StringComparison.Ordinal) ||
        line.Contains("event=perf.process", StringComparison.Ordinal) ||
        line.Contains("event=perf.timergap", StringComparison.Ordinal) ||
        line.Contains("event=perf.shelfwindow", StringComparison.Ordinal) ||
        line.Contains("event=hover.metadata", StringComparison.Ordinal) ||
        line.Contains("event=artwork.", StringComparison.Ordinal) &&
            !line.Contains("event=artwork.failure", StringComparison.Ordinal) ||
        line.Contains("BACK-TRACE", StringComparison.Ordinal) &&
            line.Contains("compositor-frame", StringComparison.Ordinal);

    private static string SanitizeLabel(string value) =>
        Regex.Replace(value, @"[\r\n\t\x00-\x1F\x7F]+", " ").Trim();

    private static string BuildConfiguration()
    {
#if DEBUG
        return "Debug";
#else
        return "Release";
#endif
    }

    public static void WriteException(string message, Exception exception) =>
        Write($"{message}: {exception.GetType().Name} (message omitted)");

    public static void WriteExceptionDetails(string message, Exception exception)
    {
        var label = Scrubber.HasActiveSecrets ? message : "Exception before account registration";
        WriteException(message, exception);
        Write($"{label} hresult: 0x{exception.HResult:X8}");
        for (var inner = exception.InnerException; inner is not null; inner = inner.InnerException)
        {
            Write($"{label} inner: {inner.GetType().Name}");
            Write($"{label} inner hresult: 0x{inner.HResult:X8}");
        }
    }
}
