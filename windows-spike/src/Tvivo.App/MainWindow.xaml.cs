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
    private ShellPage? _currentPage;

    public MainWindow()
    {
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _playback = App.Services.GetRequiredService<PlaybackService>();
        _engine = App.Services.GetRequiredService<VlcPlaybackEngine>();
        _homePage = new HomePage();
        InitializeComponent();
        PathBox.Text = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        _homePage.OpenPlayerRequested += (_, _) => ShowPage(ShellPage.Player);
        PageHost.Content = _homePage;
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

    private void ShowPage(ShellPage page)
    {
        if (_currentPage == page)
            return;

        _currentPage = page;
        var isPlayer = page == ShellPage.Player;
        PageHost.Visibility = isPlayer ? Visibility.Collapsed : Visibility.Visible;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        HomeNavigation.IsSelected = !isPlayer;
        PlayerNavigation.IsSelected = isPlayer;
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
        Player
    }
}
