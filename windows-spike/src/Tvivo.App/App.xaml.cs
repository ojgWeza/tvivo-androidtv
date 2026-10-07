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
        AppDomain.CurrentDomain.UnhandledException += OnUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
        ConfigureXamlDiagnostics();
        InitializeComponent();
        LaunchDiagnostics.Write("App XAML initialized");
    }

    private void ConfigureXamlDiagnostics()
    {
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
            LaunchDiagnostics.Write("OnLaunched entered");
            if (Environment.GetEnvironmentVariable("TVIVO_NET_LOG") == "1")
            {
                NetworkTally.Log = LaunchDiagnostics.Write;
                NetworkTally.StartPeriodicLog(TimeSpan.FromSeconds(30));
            }
            _window ??= new MainWindow();
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
    private const int MaxExportLines = 200;
    private const int MaxExportChars = 32768;
    internal static string LogPath { get; set; } = Tvivo.Core.TvivoDataPaths.For("tvivo-launch.log");

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
        try
        {
            lock (Sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length + Encoding.UTF8.GetByteCount(line) > MaxLogBytes)
                    File.Move(LogPath, LogPath + ".1", overwrite: true);
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
                foreach (var line in File.ReadLines(LogPath))
                {
                    if (!line.Contains($"session={SessionId} ", StringComparison.Ordinal)) continue;
                    lines.Enqueue(Sanitize(line));
                    if (lines.Count > MaxExportLines) lines.Dequeue();
                }
                var output = string.Join(Environment.NewLine, lines);
                return output.Length > MaxExportChars ? output[^MaxExportChars..] : output;
            }
            catch
            {
                return "Diagnostics could not be read.";
            }
        }
    }

    public static void WriteException(string message, Exception exception) =>
        Write(Scrubber.HasActiveSecrets
            ? $"{message}: {exception.GetType().Name}: {exception.Message}"
            : $"Exception before account registration: {exception.GetType().Name} (message omitted)");

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
