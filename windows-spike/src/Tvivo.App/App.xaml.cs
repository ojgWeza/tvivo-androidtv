using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using System.Diagnostics;
using System.Runtime.CompilerServices;
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
        debugSettings.XamlResourceReferenceFailed += (_, args) =>
            LaunchDiagnostics.Write($"XAML resource reference failed: {args.Message}");
        debugSettings.BindingFailed += (_, args) =>
            LaunchDiagnostics.Write($"XAML binding failed: {args.Message}");
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
    private static readonly string SessionId = Guid.NewGuid().ToString("N");
    private const long MaxLogBytes = 1024 * 1024;
    internal static string LogPath { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Tvivo",
        "tvivo-launch.log");

    public static void Write(string message)
    {
        // Exception messages can contain a provider URI; never persist its authority or query.
        var safeMessage = System.Text.RegularExpressions.Regex.Replace(
            message, @"(?i)\b(?:https?|rtsp)://[^\s\]\)\}""']+", "[redacted-url]");
        safeMessage = System.Text.RegularExpressions.Regex.Replace(
            safeMessage, @"(?i)\b(?:username|password|accountid|account_id)\s*[=:]\s*[^\s;,]+", "[redacted-field]");
        if (safeMessage.Length > 4096) safeMessage = safeMessage[..4096] + "[truncated]";
        var line = $"{DateTimeOffset.Now:O} session={SessionId} {safeMessage}{Environment.NewLine}";
        Debug.Write(line);
        try
        {
            lock (Sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length + System.Text.Encoding.UTF8.GetByteCount(line) > MaxLogBytes)
                    File.Move(LogPath, LogPath + ".1", overwrite: true);
                File.AppendAllText(LogPath, line);
            }
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"Unable to write tvivo-launch.log: {exception}");
        }
    }

    public static void WriteException(string message, Exception exception) =>
        Write($"{message}: {exception}");

    public static void WriteExceptionDetails(string message, Exception exception)
    {
        WriteException(message, exception);
        Write($"{message} hresult: 0x{exception.HResult:X8}");
        for (var inner = exception.InnerException; inner is not null; inner = inner.InnerException)
        {
            Write($"{message} inner: {inner.GetType().Name}");
            Write($"{message} inner hresult: 0x{inner.HResult:X8}");
        }
    }
}
