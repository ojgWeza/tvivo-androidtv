using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using System.Diagnostics;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Tvivo.Playback;

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
            LaunchDiagnostics.Write("MainWindow constructed; activating");
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
        services.AddSingleton<ICatalogProvider, XtreamCatalogProvider>();
        services.AddSingleton<SqliteCatalogRepository>();
        services.AddSingleton<CatalogRefreshService>();
        services.AddSingleton<ICredentialStore, DpapiCredentialStore>();
        services.AddSingleton<VlcPlaybackEngine>();
        services.AddSingleton<IPlaybackEngine>(sp => sp.GetRequiredService<VlcPlaybackEngine>());
        services.AddSingleton<PlaybackService>();
        return services.BuildServiceProvider();
    }

    private static void OnUnhandledException(object sender, System.UnhandledExceptionEventArgs args) =>
        LaunchDiagnostics.Write($"Unhandled exception (terminating={args.IsTerminating}): {args.ExceptionObject}");

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs args)
    {
        LaunchDiagnostics.WriteException("Unobserved task exception", args.Exception);
        args.SetObserved();
    }
}

internal static class LaunchDiagnostics
{
    private static readonly object Sync = new();
    private static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Tvivo",
        "tvivo-launch.log");

    public static void Write(string message)
    {
        var line = $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}";
        Debug.Write(line);
        try
        {
            lock (Sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
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
        Write($"{message}: {exception}");
        Write($"{message} message: {exception.Message}");
        Write($"{message} hresult: 0x{exception.HResult:X8}");
        for (var inner = exception.InnerException; inner is not null; inner = inner.InnerException)
        {
            Write($"{message} inner: {inner}");
            Write($"{message} inner message: {inner.Message}");
            Write($"{message} inner hresult: 0x{inner.HResult:X8}");
        }
    }
}
