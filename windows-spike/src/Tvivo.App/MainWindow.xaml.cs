using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Extensions.DependencyInjection;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using Tvivo.Playback;
using Tvivo.App.Pages;

namespace Tvivo.App;

public sealed partial class MainWindow : Window
{
    private readonly PlaybackService _playback;
    private readonly VlcPlaybackEngine _engine;
    private readonly HomePage _homePage;
    private readonly ProviderSetupPage _providerSetupPage;
    private readonly CatalogLandingPage _catalogLandingPage;
    private ShellPage? _currentPage;

    public MainWindow()
    {
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _playback = App.Services.GetRequiredService<PlaybackService>();
        _engine = App.Services.GetRequiredService<VlcPlaybackEngine>();
        _homePage = new HomePage();
        _providerSetupPage = new ProviderSetupPage();
        _catalogLandingPage = new CatalogLandingPage();
        InitializeComponent();
        PathBox.Text = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
        PageHost.Content = _homePage;
        CatalogPageHost.Content = _catalogLandingPage;
        ShowPage(ShellPage.Home);
        LaunchDiagnostics.Write("MainWindow XAML initialized");
        Closed += OnClosed;
        LaunchDiagnostics.Write("MainWindow constructor completed");
    }

    private void VideoView_Initialized(object? sender, InitializedEventArgs args)
    {
        if (sender is VideoView view)
            _engine.InitializeView(view, args);
    }

    private void HomeNavigation_Clicked(object? sender, EventArgs args) => ShowPage(ShellPage.Home);

    private void PlayerNavigation_Clicked(object? sender, EventArgs args) => ShowPage(ShellPage.Player);

    private async void CatalogNavigation_Clicked(object? sender, EventArgs args)
    {
        ShowPage(ShellPage.Catalog);
        if (_catalogLandingPage.Account is null)
            await _catalogLandingPage.LoadSavedAsync();
        else
            await _catalogLandingPage.LoadAsync(_catalogLandingPage.Account);
    }

    private async void ProviderSetupPage_ConnectionSaved(object? sender, ProviderConnectedEventArgs args)
    {
        ShowPage(ShellPage.Catalog);
        await _catalogLandingPage.LoadAsync(args.Account);
    }

    private void ShowPage(ShellPage page)
    {
        if (_currentPage == page)
            return;

        _currentPage = page;
        var isPlayer = page == ShellPage.Player;
        var isCatalog = page == ShellPage.Catalog;
        PageHost.Visibility = isPlayer || isCatalog ? Visibility.Collapsed : Visibility.Visible;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        HomeNavigation.IsSelected = page is ShellPage.Home or ShellPage.Setup;
        PlayerNavigation.IsSelected = isPlayer;
        CatalogNavigation.IsSelected = isCatalog;

        if (page == ShellPage.Setup) PageHost.Content = _providerSetupPage;
        else if (page == ShellPage.Home) PageHost.Content = _homePage;
    }

    private async void Play_Click(object sender, RoutedEventArgs args)
    {
        if (!Uri.TryCreate(PathBox.Text.Trim(), UriKind.Absolute, out var uri))
        {
            StatusText.Text = "Enter a valid media URI.";
            return;
        }

        StatusText.Text = "Starting…";
        var result = await _playback.PlayAsync(new StreamSource("desktop", StreamKind.Movie, DirectUri: uri));
        StatusText.Text = result.ToString();
    }

    private async void Stop_Click(object sender, RoutedEventArgs args)
    {
        await _playback.StopAsync();
        StatusText.Text = "Stopped";
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        LaunchDiagnostics.Write("MainWindow closed");
        using var cleanupCts = new CancellationTokenSource(TimeSpan.FromSeconds(2));

        try
        {
            await _playback.StopAsync(cleanupCts.Token);
        }
        catch (OperationCanceledException)
        {
            LaunchDiagnostics.Write("Playback stop timed out during window close");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback stop failed during window close", exception);
        }

        try
        {
            await _engine.DisposeAsync().AsTask().WaitAsync(cleanupCts.Token);
        }
        catch (OperationCanceledException)
        {
            LaunchDiagnostics.Write("Playback engine disposal timed out during window close");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback engine disposal failed during window close", exception);
        }
    }

    private enum ShellPage
    {
        Home,
        Setup,
        Catalog,
        Player
    }
}
