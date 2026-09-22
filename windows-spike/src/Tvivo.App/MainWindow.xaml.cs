using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
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
    private long _catalogRequestGeneration;

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
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
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

    private void MyTvivoNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.MyTvivo);

    private void MoviesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Movies);

    private void SeriesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Series);

    private void LiveTvNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.LiveTv);

    private void AccountNavigation_Click(object sender, RoutedEventArgs args) => ShowPage(ShellPage.Setup);

    private async Task ShowCatalogAsync(CatalogLandingPage.CatalogMode mode)
    {
        var generation = Interlocked.Increment(ref _catalogRequestGeneration);
        ShowPage(ShellPage.Catalog, updateTopNavigation: false);
        _catalogLandingPage.PrepareMode(mode);
        UpdateTopNavigationState();
        if (_catalogLandingPage.Account is null)
            await _catalogLandingPage.LoadSavedAsync();
        else
            await _catalogLandingPage.LoadAsync(_catalogLandingPage.Account);
        if (generation == Volatile.Read(ref _catalogRequestGeneration))
            UpdateTopNavigationState();
    }

    private async void ProviderSetupPage_ConnectionSaved(object? sender, ProviderConnectedEventArgs args)
    {
        Interlocked.Increment(ref _catalogRequestGeneration);
        ShowPage(ShellPage.Catalog, updateTopNavigation: false);
        await _catalogLandingPage.LoadAsync(args.Account);
        UpdateTopNavigationState();
    }

    private void ShowPage(ShellPage page, bool updateTopNavigation = true)
    {
        if (_currentPage == page)
            return;

        _currentPage = page;
        var isPlayer = page == ShellPage.Player;
        var isCatalog = page == ShellPage.Catalog;
        PageHost.Visibility = isPlayer || isCatalog ? Visibility.Collapsed : Visibility.Visible;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        TopSearchBox.IsEnabled = isCatalog;
        if (updateTopNavigation)
            UpdateTopNavigationState();

        if (page == ShellPage.Setup) PageHost.Content = _providerSetupPage;
        else if (page == ShellPage.Home) PageHost.Content = _homePage;
    }

    private void UpdateTopNavigationState()
    {
        var isCatalog = _currentPage == ShellPage.Catalog;
        SetTopNavState(MyTvivoNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.MyTvivo);
        SetTopNavState(MoviesNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.Movies);
        SetTopNavState(SeriesNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.Series);
        SetTopNavState(LiveTvNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.LiveTv);
        SetTopNavState(AccountNavigation, _currentPage == ShellPage.Setup);
    }

    private void SetTopNavState(Button button, bool selected)
    {
        button.Foreground = selected
            ? (Brush)Application.Current.Resources["AppTextBrush"]
            : (Brush)Application.Current.Resources["AppMutedTextBrush"];
        button.BorderBrush = selected
            ? (Brush)Application.Current.Resources["AppAccentBrush"]
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    private void TopSearchBox_TextChanged(object sender, TextChangedEventArgs args)
    {
        TopClearSearchButton.Visibility = string.IsNullOrWhiteSpace(TopSearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        _catalogLandingPage.SetSearchText(TopSearchBox.Text);
    }

    private void TopClearSearchButton_Click(object sender, RoutedEventArgs args)
    {
        TopSearchBox.Text = string.Empty;
        TopSearchBox.Focus(FocusState.Programmatic);
    }

    private void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        if (args.Source.DirectUri is not { } uri)
        {
            StatusText.Text = "The selected channel has no direct stream URI.";
            return;
        }

        PathBox.Text = uri.IsFile ? uri.LocalPath : uri.AbsoluteUri;
        ShowPage(ShellPage.Player);
        Play_Click(this, new RoutedEventArgs());
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
