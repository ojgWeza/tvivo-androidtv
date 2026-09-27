using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Windowing;
using Microsoft.UI;
using Microsoft.Extensions.DependencyInjection;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using Tvivo.Playback;
using Tvivo.App.Pages;

namespace Tvivo.App;

public sealed partial class MainWindow : Window
{
    private PlaybackService _playback;
    private readonly VlcPlaybackEngine _vlcEngine;
    private readonly FFmpegInteropPlaybackEngine _nativeEngine;
    private readonly PlaybackService _vlcPlayback;
    private readonly PlaybackService _nativePlayback;
    private IPlaybackEngine _engine;
    private readonly HomePage _homePage;
    private readonly ProviderSetupPage _providerSetupPage;
    private readonly CatalogLandingPage _catalogLandingPage;
    private ShellPage? _currentPage;
    private ShellPage _playerReturnPage = ShellPage.Catalog;
    private IReadOnlyList<Channel> _playerSiblings = Array.Empty<Channel>();
    private long _catalogRequestGeneration;
    private StreamSource? _currentSource;
    private long _playbackSessionGeneration;
    private CancellationTokenSource? _playbackSessionCts;
    private Task _playbackStopTask = Task.CompletedTask;
    private bool _isCinemaMode;
    private WindowStateController.WindowMode _preCinemaWindowMode;
    private FrameworkElement? _preCinemaFocus;
    private DateTimeOffset _lastVideoTapAt;
    private Windows.Foundation.Point _lastVideoTapPoint;
    private readonly WindowStateController _windowState;
    private bool _isDraggingProgress;
    private bool _isUpdatingProgress;
    private readonly DispatcherTimer _playbackUiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };

    public MainWindow()
    {
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _vlcEngine = App.Services.GetRequiredService<VlcPlaybackEngine>();
        _nativeEngine = App.Services.GetRequiredService<FFmpegInteropPlaybackEngine>();
        _vlcPlayback = new PlaybackService(_vlcEngine);
        _nativePlayback = new PlaybackService(_nativeEngine);
        var useVlc = string.Equals(ReadPlaybackEnginePreference(), "LibVLC", StringComparison.Ordinal);
        _engine = useVlc ? _vlcEngine : _nativeEngine;
        _playback = useVlc ? _vlcPlayback : _nativePlayback;
        _homePage = new HomePage();
        _providerSetupPage = new ProviderSetupPage();
        _catalogLandingPage = new CatalogLandingPage();
        InitializeComponent();
        Title = "Tvivo";
        TitleBarDragRegion.PointerPressed += TitleBarDragRegion_PointerPressed;
        WindowRoot.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler(WindowRoot_KeyDown), true);
        _windowState = new WindowStateController(this);
        ApplyWindowChromeState();
        _catalogLandingPage.InteractionReadinessChanged += CatalogLandingPage_InteractionReadinessChanged;
        _nativeEngine.Player.Volume = 1;
        NativePlayerElement.SetMediaPlayer(_nativeEngine.Player);
        PlaybackEngineSelector.SelectedIndex = useVlc ? 1 : 0;
        ApplyPlaybackEngineVisuals();
        _playbackUiTimer.Tick += PlaybackUiTimer_Tick;
        _playbackUiTimer.Start();
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
        PageHost.Content = _homePage;
        CatalogPageHost.Content = _catalogLandingPage;
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.MyTvivo);
        LaunchDiagnostics.Write("MainWindow XAML initialized");
        Closed += OnClosed;
        LaunchDiagnostics.Write("MainWindow constructor completed");
    }

    public void PrepareForActivation()
    {
        _windowState.Apply(WindowStateController.WindowMode.Fullscreen);
        ApplyWindowChromeState();
    }

    private void WindowStateButton_Click(object sender, RoutedEventArgs args)
    {
        var target = _windowState.Mode == WindowStateController.WindowMode.Fullscreen
            ? WindowStateController.WindowMode.Windowed
            : WindowStateController.WindowMode.Fullscreen;
        _windowState.Apply(target);
        ApplyWindowChromeState();
    }

    private void MinimizeWindowButton_Click(object sender, RoutedEventArgs args)
    {
        if (_windowState.Mode == WindowStateController.WindowMode.Fullscreen)
        {
            _windowState.Apply(WindowStateController.WindowMode.Windowed);
            ApplyWindowChromeState();
        }
        _windowState.Minimize();
    }

    private void CloseWindowButton_Click(object sender, RoutedEventArgs args) => Close();

    private void TitleBarDragRegion_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (args.GetCurrentPoint(TitleBarDragRegion).Properties.IsLeftButtonPressed)
            _windowState.BeginWindowDrag();
    }

    private void WindowRoot_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key == Windows.System.VirtualKey.F11)
        {
            WindowStateButton_Click(sender, new RoutedEventArgs());
            args.Handled = true;
        }
        else if (args.Key == Windows.System.VirtualKey.Escape && _currentPage == ShellPage.Player)
        {
            if (_isCinemaMode) SetCinemaMode(false);
            else ReturnFromPlayer();
            args.Handled = true;
        }
    }

    private void ApplyWindowChromeState()
    {
        var fullscreen = _windowState.Mode == WindowStateController.WindowMode.Fullscreen;
        var showChrome = fullscreen && !_isCinemaMode;
        WindowChrome.Visibility = showChrome ? Visibility.Visible : Visibility.Collapsed;
        WindowRoot.RowDefinitions[0].Height = new GridLength(showChrome ? 44 : 0);
        WindowStateButton.Content = fullscreen ? "▢" : "□";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(WindowStateButton, fullscreen
            ? "Switch to windowed mode"
            : "Switch to fullscreen mode");
    }

    private void VideoView_Initialized(object? sender, InitializedEventArgs args)
    {
        if (sender is VideoView view)
            _vlcEngine.InitializeView(view, args);
    }

    private async void PlaybackEngineSelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (PlaybackEngineSelector.SelectedItem is not ComboBoxItem selected || _currentPage is null)
            return;

        var selectedEngine = (selected.Tag as string) == "LibVLC" ? (IPlaybackEngine)_vlcEngine : _nativeEngine;
        if (ReferenceEquals(selectedEngine, _engine))
            return;

        var restartCurrentPlayback = _currentPage == ShellPage.Player && _currentSource is not null;
        await _playback.StopAsync();
        _engine = selectedEngine;
        _playback = ReferenceEquals(_engine, _vlcEngine) ? _vlcPlayback : _nativePlayback;
        WritePlaybackEnginePreference(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native");
        ApplyPlaybackEngineVisuals();
        if (restartCurrentPlayback && _currentSource is not null)
            await StartPlaybackAsync(_currentSource);
    }

    private void ApplyPlaybackEngineVisuals()
    {
        var native = ReferenceEquals(_engine, _nativeEngine);
        VideoView.Visibility = native ? Visibility.Collapsed : Visibility.Visible;
        NativePlayerElement.Visibility = native ? Visibility.Visible : Visibility.Collapsed;
        VlcTransportBar.Visibility = Visibility.Visible;
        NativeCinemaButton.Visibility = Visibility.Collapsed;
        if (native)
        {
            _nativeEngine.Player.PlaybackSession.PlaybackRate = 1;
            NativePlayerElement.AreTransportControlsEnabled = false;
        }
        ApplyPlayerLayout();
    }

    private static string? ReadPlaybackEnginePreference()
    {
        var path = GetPlaybackEnginePreferencePath();
        return File.Exists(path) ? File.ReadAllText(path).Trim() : null;
    }

    private static void WritePlaybackEnginePreference(string engine)
    {
        var path = GetPlaybackEnginePreferencePath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, engine);
    }

    private static string GetPlaybackEnginePreferencePath() => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Tvivo",
        "playback-engine.txt");

    private void MyTvivoNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.MyTvivo);

    private void MoviesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Movies);

    private void SeriesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Series);

    private void LiveTvNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.LiveTv);

    private void AccountNavigation_Click(object sender, RoutedEventArgs args) => ShowPage(ShellPage.Setup);

    private void CatalogLandingPage_InteractionReadinessChanged(object? sender, EventArgs args) =>
        SetCatalogSearchEnabled(_currentPage == ShellPage.Catalog && _catalogLandingPage.IsReadyForInteraction);

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
        await _catalogLandingPage.LoadAsync(args.Account, args.Connection);
        UpdateTopNavigationState();
    }

    private void ShowPage(ShellPage page, bool updateTopNavigation = true)
    {
        if (_currentPage == page)
            return;

        if (_currentPage == ShellPage.Player && page != ShellPage.Player)
            StopPlaybackForNavigation();

        _currentPage = page;
        var isPlayer = page == ShellPage.Player;
        if (isPlayer)
            _playbackUiTimer.Start();
        var isCatalog = page == ShellPage.Catalog;
        PageHost.Visibility = isPlayer || isCatalog ? Visibility.Collapsed : Visibility.Visible;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        _catalogLandingPage.SetActive(isCatalog);
        SetCatalogSearchEnabled(isCatalog && _catalogLandingPage.IsReadyForInteraction);
        if (updateTopNavigation)
            UpdateTopNavigationState();

        if (page == ShellPage.Setup) PageHost.Content = _providerSetupPage;
        else if (page == ShellPage.Home) PageHost.Content = _homePage;
    }

    private void SetCatalogSearchEnabled(bool enabled)
    {
        TopSearchBox.IsEnabled = enabled;
        TopClearSearchButton.IsEnabled = enabled;
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
        if (!_catalogLandingPage.IsReadyForInteraction)
            return;
        _catalogLandingPage.SetSearchText(TopSearchBox.Text);
    }

    private void TopClearSearchButton_Click(object sender, RoutedEventArgs args)
    {
        if (!_catalogLandingPage.IsReadyForInteraction)
            return;
        TopSearchBox.Text = string.Empty;
        TopSearchBox.Focus(FocusState.Programmatic);
    }

    private void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        if (args.Source.DirectUri is null)
        {
            StatusText.Text = "This channel does not have a playable stream URL yet.";
            return;
        }

        if (_currentPage != ShellPage.Player)
        {
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
            _playerSiblings = args.RelatedChannels;
        }
        else if (args.RelatedChannels.Count > 0)
            _playerSiblings = args.RelatedChannels;
        _currentSource = args.Source;
        PlayerTitleText.Text = args.Channel.DisplayName;
        var related = _playerSiblings.Where(channel => channel.Id != args.Channel.Id).ToArray();
        var isEpisode = args.Source.Kind == StreamKind.Episode;
        PlayerSideTitle.Text = isEpisode ? "Season and episodes" : "More from this folder";
        PlayerSideSubtitle.Text = isEpisode
            ? "Episode details are not available in the current series catalog."
            : related.Length == 0 ? "No other items are available in this folder." : "Other titles in this folder";
        PlayerRelatedList.ItemsSource = isEpisode ? Array.Empty<Channel>() : related;
        ShowPage(ShellPage.Player);
        _ = StartPlaybackAsync(args.Source);
    }

    private async Task StartPlaybackAsync(StreamSource source)
    {
        var generation = Interlocked.Increment(ref _playbackSessionGeneration);
        var sessionCts = new CancellationTokenSource();
        var previousCts = Interlocked.Exchange(ref _playbackSessionCts, sessionCts);
        previousCts?.Cancel();
        previousCts?.Dispose();
        _currentSource = source;
        ShowPage(ShellPage.Player);
        StatusText.Text = "Starting playback…";
        PlaybackAttemptResult result;
        try
        {
            await _playbackStopTask;
            // The first catalog activation reveals a collapsed player surface. Give
            // WinUI a layout pass before opening media, as side-list clicks already do.
            await Task.Yield();
            PlayerPage.UpdateLayout();
            if (generation != Volatile.Read(ref _playbackSessionGeneration) || sessionCts.IsCancellationRequested)
                return;
            result = await _playback.PlayAsync(source, sessionCts.Token);
        }
        catch (OperationCanceledException) when (sessionCts.IsCancellationRequested)
        {
            return;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Playback start failed: {exception.GetType().Name}");
            result = PlaybackAttemptResult.HostFailure;
        }
        finally
        {
            if (ReferenceEquals(Interlocked.CompareExchange(ref _playbackSessionCts, null, sessionCts), sessionCts))
                sessionCts.Dispose();
        }

        if (generation != Volatile.Read(ref _playbackSessionGeneration) || _currentPage != ShellPage.Player)
            return;
        StatusText.Text = result == PlaybackAttemptResult.FirstFrame ? "Playing" : $"Playback: {result}";
        PauseButton.Content = result == PlaybackAttemptResult.FirstFrame
            ? "Pause"
            : result == PlaybackAttemptResult.Cancelled ? "Play" : "Retry";
        VolumeSlider.Value = _engine.Volume;
    }

    private void StopPlaybackForNavigation()
    {
        Interlocked.Increment(ref _playbackSessionGeneration);
        var sessionCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        sessionCts?.Cancel();
        sessionCts?.Dispose();

        _currentSource = null;
        _playbackUiTimer.Stop();
        _isDraggingProgress = false;
        _isUpdatingProgress = false;
        StatusText.Text = string.Empty;
        ElapsedText.Text = FormatTime(0);
        DurationText.Text = FormatTime(0);
        ProgressSlider.Minimum = 0;
        ProgressSlider.Maximum = 1;
        ProgressSlider.Value = 0;
        ProgressSlider.IsEnabled = false;
        RewindButton.IsEnabled = false;
        ForwardButton.IsEnabled = false;
        PauseButton.Content = "Play";
        SetCinemaMode(false);

        _playbackStopTask = StopBothPlaybackEnginesAsync();
    }

    private async Task StopBothPlaybackEnginesAsync()
    {
        await Task.WhenAll(_vlcPlayback.StopAsync(), _nativePlayback.StopAsync());
    }

    private async void PauseButton_Click(object sender, RoutedEventArgs args)
    {
        if (_currentSource is null) return;
        if (!_engine.IsEnded && (_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused))
        {
            _engine.TogglePause();
            PauseButton.Content = _engine.IsPlaying || _engine.IsBuffering ? "Pause" : "Play";
        }
        else
            await StartPlaybackAsync(_currentSource);
    }

    private void PlaybackUiTimer_Tick(object? sender, object args)
    {
        if (_currentPage != ShellPage.Player) return;
        var timeline = _engine.Timeline;
        var duration = timeline.DurationMilliseconds;
        var length = duration ?? 0;
        var time = timeline.PositionMilliseconds;
        var seekEnabled = timeline.CanSeek && _currentSource is not null &&
            (_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded);
        _isUpdatingProgress = true;
        ProgressSlider.Maximum = Math.Max(length, 1);
        ProgressSlider.IsEnabled = seekEnabled;
        RewindButton.IsEnabled = seekEnabled;
        ForwardButton.IsEnabled = seekEnabled;
        if (!_isDraggingProgress)
            ProgressSlider.Value = Math.Clamp(time, 0, Math.Max(length, 1));
        ElapsedText.Text = FormatTime(time);
        DurationText.Text = duration.HasValue ? FormatTime(length) : "—:—";
        _isUpdatingProgress = false;
        PauseButton.Content = _engine.IsPlaying || _engine.IsBuffering ? "Pause" : "Play";
    }

    private static string FormatTime(long milliseconds)
    {
        var time = TimeSpan.FromMilliseconds(Math.Max(0, milliseconds));
        return time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss") : time.ToString(@"m\:ss");
    }

    private void ProgressSlider_PointerPressed(object sender, PointerRoutedEventArgs args) => _isDraggingProgress = true;

    private void ProgressSlider_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        _isDraggingProgress = false;
        SeekTo((long)ProgressSlider.Value);
    }

    private void ProgressSlider_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (_isDraggingProgress)
        {
            _isDraggingProgress = false;
            SeekTo((long)ProgressSlider.Value);
        }
    }

    private void ProgressSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (!_isUpdatingProgress && !_isDraggingProgress)
            SeekTo((long)args.NewValue);
    }

    private void RewindButton_Click(object sender, RoutedEventArgs args) => SeekBy(-10_000);

    private void ForwardButton_Click(object sender, RoutedEventArgs args) => SeekBy(10_000);

    private void SeekBy(long offsetMilliseconds)
    {
        var timeline = _engine.Timeline;
        if (!timeline.CanSeek || _currentSource is null ||
            !(_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded))
            return;

        SeekTo(timeline.PositionMilliseconds + offsetMilliseconds);
    }

    private void SeekTo(long targetMilliseconds)
    {
        var timeline = _engine.Timeline;
        if (!timeline.CanSeek || _currentSource is null ||
            !(_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded))
            return;

        if (timeline.ClampSeekTarget(targetMilliseconds) is { } target)
            _engine.Seek(target);
    }

    private void VolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args) =>
        _engine.Volume = (int)args.NewValue;

    private void PlayerRelatedList_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is Channel channel && channel.Source.DirectUri is not null)
            CatalogLandingPage_ChannelSelected(this, new ChannelSelectedEventArgs(channel, _playerSiblings));
    }

    private void PlayerBackButton_Click(object sender, RoutedEventArgs args) => ReturnFromPlayer();

    private void PlayerHomeButton_Click(object sender, RoutedEventArgs args) => ShowPage(ShellPage.Home);

    private void ReturnFromPlayer() => ShowPage(_playerReturnPage);

    private void CinemaButton_Click(object sender, RoutedEventArgs args) => SetCinemaMode(!_isCinemaMode);

    private void PlayerPage_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key == Windows.System.VirtualKey.F)
        {
            SetCinemaMode(!_isCinemaMode);
            args.Handled = true;
        }
    }

    private void VideoSurface_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        // Deliberately leave the event unhandled so a normal click reaches the playback surface.
        var point = args.GetCurrentPoint(sender as UIElement).Position;
        var now = DateTimeOffset.UtcNow;
        var delta = point.X - _lastVideoTapPoint.X;
        var deltaY = point.Y - _lastVideoTapPoint.Y;
        if (now - _lastVideoTapAt <= TimeSpan.FromMilliseconds(500) &&
            delta * delta + deltaY * deltaY <= 24 * 24)
        {
            SetCinemaMode(!_isCinemaMode);
            _lastVideoTapAt = default;
            return;
        }

        _lastVideoTapAt = now;
        _lastVideoTapPoint = point;
    }

    private void VideoSurface_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        // Keep pointer release available to the underlying playback implementation.
    }

    private void SetCinemaMode(bool enabled)
    {
        if (_isCinemaMode == enabled)
            return;

        if (enabled)
        {
            _preCinemaWindowMode = _windowState.Mode;
            _preCinemaFocus = FocusManager.GetFocusedElement(WindowRoot.XamlRoot) as FrameworkElement;
            _windowState.Apply(WindowStateController.WindowMode.Fullscreen);
        }

        _isCinemaMode = enabled;
        ApplyWindowChromeState();
        MainHeader.Visibility = enabled ? Visibility.Collapsed : Visibility.Visible;
        ((Grid)Content).RowDefinitions[1].Height = new GridLength(enabled ? 0 : 72);
        PlayerPage.Padding = enabled ? new Thickness(0) : new Thickness(24);
        PlayerNavigation.Visibility = enabled ? Visibility.Collapsed : Visibility.Visible;
        PlayerNavigationRow.Height = enabled ? new GridLength(0) : GridLength.Auto;
        CinemaButton.Content = enabled ? "Exit cinema" : "Cinema mode";
        NativeCinemaButton.Content = CinemaButton.Content;
        ApplyPlayerLayout();

        if (!enabled)
        {
            _windowState.Apply(_preCinemaWindowMode);
            ApplyWindowChromeState();
            var focusTarget = _preCinemaFocus is { Visibility: Visibility.Visible, IsTabStop: true }
                ? _preCinemaFocus
                : CinemaButton;
            focusTarget.Focus(FocusState.Programmatic);
            _preCinemaFocus = null;
        }
    }

    private void ApplyPlayerLayout()
    {
        var cinema = _isCinemaMode;

        PlayerLayoutGrid.ColumnSpacing = cinema ? 0 : 18;
        PlayerContentColumn.Width = new GridLength(cinema ? 1 : 2.35, GridUnitType.Star);
        PlayerSideColumn.Width = new GridLength(cinema ? 0 : 0.9, GridUnitType.Star);
        PlayerSidePanel.Visibility = cinema ? Visibility.Collapsed : Visibility.Visible;

        PlayerContentGrid.RowSpacing = cinema ? 0 : 10;
        PlayerVideoRow.Height = new GridLength(1, GridUnitType.Star);
        PlayerDetailsRow.Height = cinema ? new GridLength(0) : GridLength.Auto;
        PlayerTransportRow.Height = cinema ? new GridLength(0) : GridLength.Auto;
        PlayerDetailsPanel.Visibility = cinema ? Visibility.Collapsed : Visibility.Visible;
        VlcTransportBar.Visibility = cinema ? Visibility.Collapsed : Visibility.Visible;
        NativeCinemaButton.Visibility = Visibility.Collapsed;
        NativePlayerElement.AreTransportControlsEnabled = false;
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        _playbackUiTimer.Stop();
        _playbackUiTimer.Tick -= PlaybackUiTimer_Tick;
        _catalogLandingPage.Shutdown();
        Interlocked.Increment(ref _playbackSessionGeneration);
        var sessionCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        sessionCts?.Cancel();
        LaunchDiagnostics.Write("MainWindow closed");
        try
        {
            await StopBothPlaybackEnginesAsync().WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            LaunchDiagnostics.Write("Playback stop timed out during window close");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback stop failed during window close", exception);
        }

        try
        {
            await _vlcEngine.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            LaunchDiagnostics.Write("Playback engine disposal timed out during window close");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback engine disposal failed during window close", exception);
        }

        try
        {
            _nativeEngine.Dispose();
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Native playback engine disposal failed during window close", exception);
        }
        finally
        {
            // WinUI's process can outlive its last window while LibVLC owns threads.
            // Both engines have had a bounded stop/dispose opportunity above.
            Environment.Exit(0);
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
