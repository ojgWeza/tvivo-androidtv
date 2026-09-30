using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Windowing;
using Microsoft.UI;
using Microsoft.Extensions.DependencyInjection;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Tvivo.Playback;
using Tvivo.App.Pages;

namespace Tvivo.App;

public sealed partial class MainWindow : Window
{
    private const int DwmwaUseImmersiveDarkMode = 20;

    private PlaybackService _playback;
    private readonly VlcPlaybackEngine _vlcEngine;
    private readonly FFmpegInteropPlaybackEngine _nativeEngine;
    private readonly PlaybackService _vlcPlayback;
    private readonly PlaybackService _nativePlayback;
    private IPlaybackEngine _engine;
    private readonly HomePage _homePage;
    private readonly ProviderSetupPage _providerSetupPage;
    private readonly AccountPage _accountPage;
    private readonly CatalogLandingPage _catalogLandingPage;
    private readonly SqliteCatalogRepository _catalogRepository;
    private readonly PlaybackHandoff _playbackHandoff = new(TimeSpan.FromMilliseconds(350));
    private ShellPage? _currentPage;
    private ShellPage _playerReturnPage = ShellPage.Catalog;
    private IReadOnlyList<Channel> _playerSiblings = Array.Empty<Channel>();
    private readonly ObservableCollection<PlayerListEntry> _playerEntries = new();
    private double? _playerListPointerPressOffset;
    private IReadOnlyList<SeriesSeason> _playerSeriesSeasons = Array.Empty<SeriesSeason>();
    private string? _currentSeriesId;
    private string? _currentSeriesTitle;
    private ProviderAccount? _currentSeriesAccount;
    private CatalogMetadata? _currentSeriesMetadata;
    private Channel? _nowPlayingChannel;
    private bool _seriesCompletionRecorded;
    private bool _playbackCompletionShown;
    private long _catalogRequestGeneration;
    private long _itemSelectionGeneration;
    private StreamSource? _currentSource;
    private long _playbackSessionGeneration;
    private long _metadataGeneration;
    private long _playerEntryTraceId;
    private long _playerEntryTraceStartedAt;
    private CancellationTokenSource? _playbackSessionCts;
    private Task _playbackStopTask = Task.CompletedTask;
    private bool _isCinemaMode;
    private bool _cinemaCursorHidden;
    private WindowStateController.WindowMode _preCinemaWindowMode;
    private FrameworkElement? _preCinemaFocus;
    private DateTimeOffset _lastVideoTapAt;
    private Windows.Foundation.Point _lastVideoTapPoint;
    private readonly WindowStateController _windowState;
    private bool _isDraggingProgress;
    private bool _isUpdatingProgress;
    private long? _resumePositionOnStart;
    private long? _pendingResumePosition;
    private bool _resumeReady;
    private DateTimeOffset _lastResumeSavedAt;
    private readonly DispatcherTimer _playbackUiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _cinemaCursorIdleTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private readonly DispatcherTimer _idleTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer _featuredRotationTimer = new() { Interval = TimeSpan.FromSeconds(8) };
    private IReadOnlyList<string> _featuredSeries = Array.Empty<string>();
    private int _featuredIndex;
    private static readonly TimeSpan IdleTimeout = TimeSpan.FromMinutes(5);
    private DateTimeOffset _lastIdleInputAt = DateTimeOffset.UtcNow;
    private bool _windowFocused;
    private bool _idleOverlayVisible;
    private long _idleOverlayShownAt;
    private bool _windowClosed;
    private bool _backTransitionTraceActive;
    private int _backTransitionRenderSamples;
    private long _backTransitionTraceId;
    private long _backTransitionStartedAt;

    [DllImport("user32.dll")]
    private static extern int ShowCursor([MarshalAs(UnmanagedType.Bool)] bool show);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int value, int valueSize);

    public MainWindow()
    {
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _vlcEngine = App.Services.GetRequiredService<VlcPlaybackEngine>();
        _nativeEngine = App.Services.GetRequiredService<FFmpegInteropPlaybackEngine>();
        _vlcEngine.LifecycleEvent += LaunchDiagnostics.Write;
        _nativeEngine.LifecycleEvent += LaunchDiagnostics.Write;
        _vlcPlayback = new PlaybackService(_vlcEngine);
        _nativePlayback = new PlaybackService(_nativeEngine);
        var useVlc = string.Equals(ReadPlaybackEnginePreference(), "LibVLC", StringComparison.Ordinal);
        _engine = useVlc ? _vlcEngine : _nativeEngine;
        _playback = useVlc ? _vlcPlayback : _nativePlayback;
        _homePage = new HomePage();
        _providerSetupPage = new ProviderSetupPage();
        _accountPage = new AccountPage();
        _catalogLandingPage = new CatalogLandingPage();
        _homePage.UseArtworkPipeline(_catalogLandingPage);
        _catalogRepository = App.Services.GetRequiredService<SqliteCatalogRepository>();
        InitializeComponent();
        var useDarkMode = 1;
        var darkModeResult = DwmSetWindowAttribute(WinRT.Interop.WindowNative.GetWindowHandle(this),
            DwmwaUseImmersiveDarkMode, ref useDarkMode, sizeof(int));
        if (darkModeResult < 0)
            LaunchDiagnostics.Write($"DWM dark mode attribute failed: 0x{darkModeResult:X8}");
        PlayerRelatedList.ItemsSource = _playerEntries;
        Title = "Tvivo";
        var iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "Tvivo.ico");
        if (System.IO.File.Exists(iconPath)) AppWindow.SetIcon(iconPath);
        TitleBarDragRegion.PointerPressed += TitleBarDragRegion_PointerPressed;
        WindowRoot.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler(WindowRoot_KeyDown), true);
        WindowRoot.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(WindowRoot_PointerInput), true);
        WindowRoot.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(WindowRoot_PointerWheelInput), true);
        WindowRoot.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(WindowRoot_PointerMoved), true);
        WindowRoot.SizeChanged += WindowRoot_SizeChanged;
        VideoView.Unloaded += (_, _) => TraceBackTransition("video-view-unloaded");
        NativePlayerElement.Unloaded += (_, _) => TraceBackTransition("native-player-unloaded");
        _windowState = new WindowStateController(this);
        ApplyWindowChromeState();
        _catalogLandingPage.InteractionReadinessChanged += CatalogLandingPage_InteractionReadinessChanged;
        _nativeEngine.Player.Volume = 1;
        NativePlayerElement.SetMediaPlayer(_nativeEngine.Player);
        PlaybackEngineSelector.SelectedIndex = useVlc ? 1 : 0;
        ApplyPlaybackEngineVisuals();
        _playbackUiTimer.Tick += PlaybackUiTimer_Tick;
        _playbackUiTimer.Start();
        _cinemaCursorIdleTimer.Tick += CinemaCursorIdleTimer_Tick;
        _idleTimer.Tick += IdleTimer_Tick;
        _featuredRotationTimer.Tick += FeaturedRotationTimer_Tick;
        _idleTimer.Start();
        Activated += MainWindow_Activated;
        PlayerPage.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(PlayerPage_PointerMoved), true);
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
        _accountPage.SignOutCompleted += AccountPage_SignOutCompleted;
        _accountPage.ChangeUserRequested += AccountPage_ChangeUserRequested;
        _accountPage.ExitRequested += (_, _) => Close();
        PageHost.Children.Add(_homePage);
        PageHost.Children.Add(_providerSetupPage);
        PageHost.Children.Add(_accountPage);
        _homePage.Visibility = Visibility.Visible;
        _providerSetupPage.Visibility = Visibility.Collapsed;
        _accountPage.Visibility = Visibility.Collapsed;
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
        if (_idleOverlayVisible)
        {
            DismissIdleOverlay("keyboard");
            args.Handled = true;
            return;
        }
        ResetIdleDeadline();
        if (args.Key == Windows.System.VirtualKey.F11)
        {
            WindowStateButton_Click(sender, new RoutedEventArgs());
            args.Handled = true;
        }
        else if (args.Key == Windows.System.VirtualKey.Escape && _currentPage == ShellPage.Player)
        {
            ReturnFromPlayer();
            args.Handled = true;
        }
    }

    private void MainWindow_Activated(object sender, WindowActivatedEventArgs args)
    {
        _windowFocused = args.WindowActivationState != WindowActivationState.Deactivated;
        if (!_windowFocused) DismissIdleOverlay("deactivation");
        ResetIdleDeadline();
    }

    private void WindowRoot_PointerInput(object sender, PointerRoutedEventArgs args) =>
        HandleWindowRootPointerInput(args, "pointer");

    private void WindowRoot_PointerWheelInput(object sender, PointerRoutedEventArgs args) =>
        HandleWindowRootPointerInput(args, "wheel");

    private void HandleWindowRootPointerInput(PointerRoutedEventArgs args, string reason)
    {
        if (_idleOverlayVisible)
        {
            // The overlay owns the hit-test surface; its first gesture never reaches a page card.
            DismissIdleOverlay(reason);
            args.Handled = true;
            return;
        }
        ResetIdleDeadline();
    }

    private void WindowRoot_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_idleOverlayVisible) ResetIdleDeadline();
    }

    private void IdleOverlay_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        DismissIdleOverlay("pointer");
        args.Handled = true;
    }

    private void IdleOverlay_PointerWheelChanged(object sender, PointerRoutedEventArgs args)
    {
        DismissIdleOverlay("wheel");
        args.Handled = true;
    }

    private void ResetIdleDeadline() => _lastIdleInputAt = DateTimeOffset.UtcNow;

    private bool IdleEligible()
    {
        if (_windowClosed || !_windowFocused || _currentPage == ShellPage.Player ||
            _engine.IsPlaying || _engine.IsBuffering || WindowRoot.XamlRoot is null) return false;
        return !VisualTreeHelper.GetOpenPopupsForXamlRoot(WindowRoot.XamlRoot)
            .Any(popup => HasContentDialog(popup.Child));
    }

    private static bool HasContentDialog(DependencyObject? element)
    {
        if (element is null) return false;
        if (element is ContentDialog) return true;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
            if (HasContentDialog(VisualTreeHelper.GetChild(element, index))) return true;
        return false;
    }

    private async void IdleTimer_Tick(object? sender, object args)
    {
        if (!IdleEligible())
        {
            ResetIdleDeadline();
            return;
        }
        if (_idleOverlayVisible || DateTimeOffset.UtcNow - _lastIdleInputAt < IdleTimeout) return;
        _idleTimer.Stop();
        var account = _catalogLandingPage.Account;
        _idleOverlayShownAt = Stopwatch.GetTimestamp();
        _idleOverlayVisible = true;
        IdleOverlay.Visibility = Visibility.Visible;
        LaunchDiagnostics.Write($"event=idle.overlay.show hasAccount={(account is null ? "false" : "true")} featuredCount={(account is null ? "0" : "pending")}");
        IdleOverlay.Focus(FocusState.Programmatic);
        if (account is null)
        {
            _featuredSeries = Array.Empty<string>();
            IdleFeaturedTitle.Text = "Explore Tvivo";
            IdleFeaturedDescription.Text = "Connect a provider to discover your library.";
            return;
        }
        try
        {
            var titles = await Task.Run(() => _catalogRepository.GetFeaturedSeriesTitles(account, 20));
            LaunchDiagnostics.Write($"event=idle.overlay.featured count={titles.Count}");
            if (_windowClosed || !_idleOverlayVisible || _catalogLandingPage.Account?.AccountId != account.AccountId) return;
            _featuredSeries = titles;
            _featuredIndex = 0;
            IdleFeaturedTitle.Text = titles.FirstOrDefault() ?? "Explore Tvivo";
            IdleFeaturedDescription.Text = titles.Count == 0 ? "Browse your library to find something to watch." : "A highly rated series from your library.";
            if (titles.Count > 1) _featuredRotationTimer.Start();
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Featured series lookup failed", exception);
        }
    }

    private void FeaturedRotationTimer_Tick(object? sender, object args)
    {
        if (!_idleOverlayVisible || _featuredSeries.Count < 2) return;
        _featuredIndex = (_featuredIndex + 1) % _featuredSeries.Count;
        IdleFeaturedTitle.Text = _featuredSeries[_featuredIndex];
    }

    private void DismissIdleOverlay(string reason)
    {
        if (!_idleOverlayVisible) return;
        LaunchDiagnostics.Write($"event=idle.overlay.dismiss reason={reason} elapsedVisibleMs={(long)Stopwatch.GetElapsedTime(_idleOverlayShownAt).TotalMilliseconds}");
        _idleOverlayVisible = false;
        _featuredRotationTimer.Stop();
        IdleOverlay.Visibility = Visibility.Collapsed;
        ResetIdleDeadline();
        if (!_windowClosed) _idleTimer.Start();
    }

    private void ApplyWindowChromeState()
    {
        var fullscreen = _windowState.Mode == WindowStateController.WindowMode.Fullscreen;
        var showChrome = !_isCinemaMode;
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
        if (restartCurrentPlayback) SaveResumePosition(force: true);
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
        SetPlaybackSurfaceVisibility(_currentPage == ShellPage.Player);
        VlcTransportBar.Visibility = Visibility.Visible;
        NativeCinemaButton.Visibility = Visibility.Collapsed;
        if (native)
        {
            _nativeEngine.Player.PlaybackSession.PlaybackRate = 1;
            NativePlayerElement.AreTransportControlsEnabled = false;
        }
        ApplyPlayerLayout();
        TraceBackTransition($"playback-surface-visibility engine={(native ? "native" : "vlc")} {VideoSurfaceState()}");
    }

    private void SetPlaybackSurfaceVisibility(bool visible)
    {
        var showVlc = visible && ReferenceEquals(_engine, _vlcEngine);
        VideoView.Visibility = showVlc ? Visibility.Visible : Visibility.Collapsed;
        NativePlayerElement.Visibility = visible && ReferenceEquals(_engine, _nativeEngine)
            ? Visibility.Visible
            : Visibility.Collapsed;
        TraceBackTransition($"playback-surface-state requestedVisible={visible} {VideoSurfaceState()}");
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

    private void HomeNavigation_Click(object sender, RoutedEventArgs args) => ShowPage(ShellPage.Home);

    private void MoviesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Movies);

    private void SeriesNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.Series);

    private void LiveTvNavigation_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.LiveTv);

    private void AccountNavigation_Click(object sender, RoutedEventArgs args)
    {
        if (_catalogLandingPage.Account is not { } account)
        {
            ShowPage(ShellPage.Setup);
            return;
        }

        var playbackWasActive = _currentPage == ShellPage.Player && (_engine.IsPlaying || _engine.IsBuffering);
        _accountPage.ShowAccount(account, playbackWasActive);
        ShowPage(ShellPage.Account);
    }

    private void AccountPage_SignOutCompleted(object? sender, EventArgs args)
    {
        Interlocked.Increment(ref _catalogRequestGeneration);
        _catalogLandingPage.ClearAccountState();
        _providerSetupPage.ClearCredentials();
        ShowPage(ShellPage.Setup);
    }

    private void AccountPage_ChangeUserRequested(object? sender, EventArgs args)
    {
        // Same UI reset as sign-out, but intentionally does not touch the credential store:
        // if the user backs out of setup without connecting, the previous account's saved
        // credentials are still there next launch.
        Interlocked.Increment(ref _catalogRequestGeneration);
        _catalogLandingPage.ClearAccountState();
        _providerSetupPage.ClearCredentials();
        ShowPage(ShellPage.Setup);
    }

    private void CatalogLandingPage_InteractionReadinessChanged(object? sender, EventArgs args) =>
        SetCatalogSearchEnabled(_currentPage == ShellPage.Catalog && _catalogLandingPage.IsReadyForInteraction);

    private async Task ShowCatalogAsync(CatalogLandingPage.CatalogMode mode)
    {
        var returningFromPlayer = CatalogTransitionPolicy.ShouldUsePlayerReturnLayoutBarrier(
            mode, _currentPage == ShellPage.Player);
        var generation = Interlocked.Increment(ref _catalogRequestGeneration);
        TraceBackTransition($"show-catalog-start mode={mode} fromPlayer={returningFromPlayer} generation={generation}");
        ShowPage(ShellPage.Catalog, updateTopNavigation: false);
        TraceBackTransition($"show-catalog-after-showpage catalog={CatalogPageArea.Visibility} player={PlayerPage.Visibility}");
        await _catalogLandingPage.PrepareModeAsync(mode);
        if (generation != Volatile.Read(ref _catalogRequestGeneration)) return;
        UpdateTopNavigationState();

        if (_catalogLandingPage.Account is null)
            await _catalogLandingPage.LoadSavedAsync();
        else
            await _catalogLandingPage.LoadAsync(_catalogLandingPage.Account);
        if (generation == Volatile.Read(ref _catalogRequestGeneration))
            UpdateTopNavigationState();
        TraceBackTransition($"show-catalog-complete generation={generation}");
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

        var returningFromPlayer = page == ShellPage.Catalog && _currentPage == ShellPage.Player;

        if (_currentPage == ShellPage.Player && page != ShellPage.Player && !_backTransitionTraceActive)
            BeginBackTransitionTrace($"player-leave-navigation destination={page} cinema={_isCinemaMode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");

        TraceBackTransition($"show-page-enter from={_currentPage} to={page} cinema={_isCinemaMode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
        if (_currentPage == ShellPage.Player && page != ShellPage.Player)
        {
            TraceBackTransition("show-page-before-player-stop");
            StopPlaybackForNavigation();
            TraceBackTransition("show-page-after-player-stop");
        }

        _currentPage = page;
        DismissIdleOverlay("navigation");
        ResetIdleDeadline();
        if (page == ShellPage.Home)
            _ = _homePage.LoadAsync(_catalogLandingPage.Account, _catalogLandingPage.Connection, _catalogRepository);
        var isPlayer = page == ShellPage.Player;
        if (isPlayer)
            _playbackUiTimer.Start();
        var isCatalog = page == ShellPage.Catalog;
        var isStaticPage = page is ShellPage.Home or ShellPage.Setup or ShellPage.Account;
        TraceBackTransition($"show-page-visibility-begin page={page}");
        PageHost.Visibility = isStaticPage ? Visibility.Visible : Visibility.Collapsed;
        _homePage.Visibility = page == ShellPage.Home ? Visibility.Visible : Visibility.Collapsed;
        _providerSetupPage.Visibility = page == ShellPage.Setup ? Visibility.Visible : Visibility.Collapsed;
        _accountPage.Visibility = page == ShellPage.Account ? Visibility.Visible : Visibility.Collapsed;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        TraceBackTransition($"show-page-player-visibility-set page={page} player={PlayerPage.Visibility}");
        SetPlaybackSurfaceVisibility(isPlayer);
        if (isPlayer && _nowPlayingChannel is { } selectedPlayerChannel)
            SetCurrentPlayerEntry(selectedPlayerChannel);
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        TraceBackTransition($"show-page-catalog-visibility-set page={page} catalog={CatalogPageArea.Visibility}");
        if (isCatalog && returningFromPlayer)
        {
            var catalogVisual = ElementCompositionPreview.GetElementVisual(CatalogPageArea);
            catalogVisual.StopAnimation(nameof(Visual.Opacity));
            catalogVisual.Opacity = 0;
            CatalogPageArea.UpdateLayout();
            WindowRoot.UpdateLayout();
            TraceBackTransition("show-page-player-return-layout-barrier catalogOpacity=0 forcedLayout=true");
        }
        if (isStaticPage) FadeIn(PageHost);
        else if (isPlayer) FadeIn(PlayerPage);
        else if (isCatalog) FadeIn(CatalogPageArea);
        _catalogLandingPage.SetActive(isCatalog);
        TraceBackTransition($"show-page-visibility page={page} host={PageHost.Visibility} catalog={CatalogPageArea.Visibility} player={PlayerPage.Visibility} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility} cinema={_isCinemaMode}");
        SetCatalogSearchEnabled(isCatalog && _catalogLandingPage.IsReadyForInteraction);
        if (updateTopNavigation)
            UpdateTopNavigationState();

    }

    private void SetCatalogSearchEnabled(bool enabled)
    {
        TopSearchBox.IsEnabled = enabled;
        TopClearSearchButton.IsEnabled = enabled;
    }

    private void BeginBackTransitionTrace(string message)
    {
        _backTransitionTraceId++;
        _backTransitionStartedAt = Stopwatch.GetTimestamp();
        _backTransitionRenderSamples = 0;
        _backTransitionTraceActive = true;
        CompositionTarget.Rendering -= BackTransition_Rendering;
        CompositionTarget.Rendering += BackTransition_Rendering;
        TraceBackTransition(message);
    }

    private void BackTransition_Rendering(object? sender, object args)
    {
        var catalogOpacity = ElementCompositionPreview.GetElementVisual(CatalogPageArea).Opacity;
        var playerOpacity = ElementCompositionPreview.GetElementVisual(PlayerPage).Opacity;
        TraceBackTransition($"compositor-frame sample={++_backTransitionRenderSamples} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0} catalog={CatalogPageArea.Visibility}/opacity:{catalogOpacity:0.000} player={PlayerPage.Visibility}/opacity:{playerOpacity:0.000} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility} cinema={_isCinemaMode}");
        if (_backTransitionRenderSamples < 12) return;
        CompositionTarget.Rendering -= BackTransition_Rendering;
        _backTransitionTraceActive = false;
    }

    private void WindowRoot_SizeChanged(object sender, SizeChangedEventArgs args) =>
        TraceBackTransition($"xaml-size-changed old={args.PreviousSize.Width:0.0}x{args.PreviousSize.Height:0.0} new={args.NewSize.Width:0.0}x{args.NewSize.Height:0.0}");

    private string VideoSurfaceState() =>
        $"vlc={VideoView.Visibility}/loaded:{VideoView.IsLoaded}/size:{VideoView.ActualWidth:0.0}x{VideoView.ActualHeight:0.0}/host:WinUI-D3D11-swapchain/nativeHwnd:not-applicable,native={NativePlayerElement.Visibility}";

    private void TraceBackTransition(string message)
    {
        if (!_backTransitionTraceActive) return;
        var elapsed = Stopwatch.GetTimestamp() - _backTransitionStartedAt;
        var elapsedMilliseconds = elapsed * 1000d / Stopwatch.Frequency;
        LaunchDiagnostics.Write($"BACK-TRACE id={_backTransitionTraceId} utc={DateTimeOffset.UtcNow:O} elapsedMs={elapsedMilliseconds:0.000} {message}");
    }

    private void UpdateTopNavigationState()
    {
        var isCatalog = _currentPage == ShellPage.Catalog;
        SetTopNavState(MyTvivoNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.MyTvivo);
        SetTopNavState(MoviesNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.Movies);
        SetTopNavState(SeriesNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.Series);
        SetTopNavState(LiveTvNavigation, isCatalog && _catalogLandingPage.ActiveMode == CatalogLandingPage.CatalogMode.LiveTv);
        SetTopNavState(AccountNavigation, _currentPage is ShellPage.Setup or ShellPage.Account);
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

    private async void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        if (_currentPage == ShellPage.Player) SaveResumePosition(force: true);
        var selectionGeneration = Interlocked.Increment(ref _itemSelectionGeneration);
        var playerEntryTraceId = Interlocked.Increment(ref _playerEntryTraceId);
        Volatile.Write(ref _playerEntryTraceStartedAt, Stopwatch.GetTimestamp());
        LaunchDiagnostics.Write($"CLICK-AWAY-TRACE id={playerEntryTraceId} event=content-selected kind={args.Source.Kind}");
        Interlocked.Increment(ref _metadataGeneration);
        if (args.Source.Kind == StreamKind.Series)
        {
            await OpenSeriesAsync(args.Channel, selectionGeneration);
            return;
        }

        if (args.Source.DirectUri is null)
        {
            StatusText.Text = "This channel does not have a playable stream URL yet.";
            return;
        }

        if (args.Source.Kind == StreamKind.Episode)
        {
            OpenSeriesEpisode(args.Channel);
            return;
        }

        _currentSeriesId = null;
        _currentSeriesTitle = null;
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        SeriesFavoriteButton.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        var type = args.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live;
        if (_catalogLandingPage.Account is { } account)
        {
            _catalogRepository.RecordVisit(account, type, args.Channel.Id);
            _catalogLandingPage.NotifyVisitRecorded();
        }
        _playerSiblings = args.Source.Kind == StreamKind.Movie
            ? new[] { args.Channel }
            : args.Source.Kind == StreamKind.Live
                ? args.RelatedChannels.Where(channel => channel.Source.Kind == StreamKind.Live)
                    .Append(args.Channel).DistinctBy(channel => channel.Id).ToArray()
                : Array.Empty<Channel>();
        _currentSource = args.Source;
        _resumePositionOnStart = null;
        _nowPlayingChannel = args.Channel;
        PlayerTitleText.Text = args.Channel.DisplayName;
        PlayerSideTitle.Text = args.Source.Kind is StreamKind.Movie or StreamKind.Live
            ? "Loading category…"
            : "Now playing";
        PlayerSideSubtitle.Text = args.Source.Kind switch
        {
            StreamKind.Movie => _playerSiblings.Count <= 1 ? "No other movies are available." : "Other movies in this category",
            StreamKind.Live => _playerSiblings.Count <= 1 ? "No other channels are available." : "Other channels in this shelf",
            _ => string.Empty,
        };
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        _playbackCompletionShown = false;
        SetPlayerList(_playerSiblings, args.Channel);
        ShowPage(ShellPage.Player);
        if (args.Source.Kind == StreamKind.Movie)
            _ = LoadMovieCategorySiblingsAsync(args.Channel, selectionGeneration);
        else if (args.Source.Kind == StreamKind.Live)
            _ = LoadPlayerCategoryTitleAsync(args.Channel, selectionGeneration, "Live TV");
        _ = LoadPlayerMetadataAsync(args.Channel, type);
        _ = StartPlaybackAsync(args.Source);
    }

    private async Task LoadMovieCategorySiblingsAsync(Channel selected, long selectionGeneration)
    {
        var fallbackTitle = PlayerSideTitleResolver.ForMovie(selected.Metadata.GetValueOrDefault("genre"));
        await LoadPlayerCategoryTitleAsync(selected, selectionGeneration, fallbackTitle);
        if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration) ||
            _currentPage != ShellPage.Player || _nowPlayingChannel?.Id != selected.Id)
            return;

        var channels = await _catalogLandingPage.GetFullCategoryChannelsForAsync(selected);
        if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration) ||
            _currentPage != ShellPage.Player || _nowPlayingChannel?.Id != selected.Id)
            return;

        _playerSiblings = channels
            .Where(channel => channel.Source.Kind == StreamKind.Movie)
            .Append(selected)
            .DistinctBy(channel => channel.Id)
            .ToArray();
        PlayerSideSubtitle.Text = _playerSiblings.Count <= 1
            ? "No other movies are available."
            : "Other movies in this category";
        SetPlayerList(_playerSiblings, selected);
    }

    private async Task LoadPlayerCategoryTitleAsync(Channel selected, long selectionGeneration, string fallbackTitle)
    {
        var categoryName = await _catalogLandingPage.GetCategoryNameForAsync(selected);
        if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration) ||
            _currentPage != ShellPage.Player || _nowPlayingChannel?.Id != selected.Id)
            return;
        PlayerSideTitle.Text = !string.IsNullOrWhiteSpace(categoryName) ? categoryName : fallbackTitle;
    }

    private async Task OpenSeriesAsync(Channel series, long selectionGeneration)
    {
        if (_catalogLandingPage.Account is not { } account ||
            App.Services.GetRequiredService<ICatalogProvider>() is not ISeriesCatalogProvider provider)
        {
            StatusText.Text = "Series episodes are unavailable for this provider.";
            return;
        }

        SeriesDetails details;
        try
        {
            details = await provider.GetSeriesInfoAsync(account, series.Id);
            if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration))
                return;
        }
        catch
        {
            if (selectionGeneration == Volatile.Read(ref _itemSelectionGeneration))
                StatusText.Text = "Couldn't load this series' episodes. Try opening it again.";
            return;
        }

        var progress = _catalogRepository.GetSeriesPlayback(account, series.Id);
        var selected = SeriesEpisodeResolver.Resolve(details, progress.EpisodeId, progress.Finished);
        if (selected is null)
        {
            StatusText.Text = "This series has no episodes available.";
            return;
        }

        _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        Interlocked.Increment(ref _metadataGeneration);
        _currentSeriesId = series.Id;
        _currentSeriesTitle = string.IsNullOrWhiteSpace(details.Title) ? series.DisplayName : details.Title;
        _currentSeriesAccount = account;
        UpdateSeriesFavoriteButton(_catalogRepository.IsFavorite(account, CatalogItemType.Series, series.Id));
        _currentSeriesMetadata = details.Metadata;
        _playerSeriesSeasons = details.Seasons;
        ApplyMetadataToChannel(series, CatalogItemType.Series, details.Metadata);
        SetPlayerMetadata(details.Metadata ?? new CatalogMetadata(), CatalogItemType.Series);
        _playerSiblings = details.Seasons.SelectMany(season => season.Episodes)
            .Select(episode => ToEpisodeChannel(account, series.Id, episode)).ToArray();
        ConfigureSeasonSelector(details.Seasons);
        _catalogRepository.RecordVisit(account, CatalogItemType.Series, series.Id);
        _catalogLandingPage.NotifyVisitRecorded();
        OpenSeriesEpisode(ToEpisodeChannel(account, series.Id, selected));
    }

    private static Channel ToEpisodeChannel(ProviderAccount account, string seriesId, SeriesEpisode episode)
    {
        var displayName = $"S{episode.SeasonNumber:00} E{episode.EpisodeNumber:00} · {episode.Title}";
        return new Channel(account.AccountId, episode.Id, seriesId, episode.Title, displayName, null, null,
            episode.EpisodeNumber, episode.Source,
            new Dictionary<string, string> { ["seriesId"] = seriesId, ["seasonId"] = episode.SeasonId });
    }

    private void OpenSeriesEpisode(Channel episode)
    {
        if (episode.Source.DirectUri is null) return;
        if (_currentPage == ShellPage.Player) SaveResumePosition(force: true);
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        _currentSeriesId ??= episode.Metadata.TryGetValue("seriesId", out var seriesId) ? seriesId : null;
        _currentSeriesAccount = _catalogLandingPage.Account;
        if (_currentSeriesId is not null && _currentSeriesAccount is not null)
        {
            var previous = _catalogRepository.GetSeriesPlayback(_currentSeriesAccount, _currentSeriesId);
            _resumePositionOnStart = previous.EpisodeId == episode.Id && !previous.Finished
                ? _catalogRepository.GetResumePosition(_currentSeriesAccount, CatalogItemType.Series, _currentSeriesId)
                : null;
            if (_resumePositionOnStart is null)
            {
                _catalogRepository.UpdateResumePosition(_currentSeriesAccount, CatalogItemType.Series, _currentSeriesId, 0);
                LaunchDiagnostics.Write("event=resume.save kind=Series positionMs=0 completed=false reason=new-episode");
            }
        }
        _nowPlayingChannel = episode;
        _currentSource = episode.Source;
        if (_currentSeriesId is not null && _currentSeriesAccount is not null)
            _catalogRepository.UpdateSeriesPlayback(_currentSeriesAccount, _currentSeriesId, episode.Id, finished: false);
        _seriesCompletionRecorded = false;
        _playbackCompletionShown = false;
        PlayerTitleText.Text = episode.DisplayName;
        PlayerSideTitle.Text = PlayerSideTitleResolver.ForEpisodes(_currentSeriesTitle);
        PlayerSideSubtitle.Text = "Select an episode to play";
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(_currentSeriesMetadata, CatalogItemType.Series);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        ConfigureSeasonSelector(_playerSeriesSeasons);
        var seasonId = episode.Metadata.TryGetValue("seasonId", out var id) ? id : null;
        var selectedSeason = _playerSeriesSeasons.FirstOrDefault(season => season.Id == seasonId);
        PlayerSeasonComboBox.SelectedItem = selectedSeason;
        var account = _currentSeriesAccount ?? _catalogLandingPage.Account;
        var visibleEpisodes = selectedSeason is not null && account is not null && _currentSeriesId is not null
            ? selectedSeason.Episodes.Select(item => ToEpisodeChannel(account, _currentSeriesId, item)).ToArray()
            : _playerSiblings;
        SetPlayerList(visibleEpisodes, episode);
        ShowPage(ShellPage.Player);
        _ = StartPlaybackAsync(episode.Source);
    }

    private void SetPlayerList(IReadOnlyList<Channel> channels, Channel current)
    {
        var sameRows = _playerEntries.Count == channels.Count &&
            _playerEntries.Select((entry, index) => entry.Channel.Source.Kind == channels[index].Source.Kind && entry.Channel.Id == channels[index].Id).All(matches => matches);
        var scrollOffset = FindPlayerListScrollViewer()?.VerticalOffset;
        if (!sameRows)
        {
            _playerEntries.Clear();
            foreach (var channel in channels)
                _playerEntries.Add(new PlayerListEntry(channel, IsChannelFavorite(channel)));
        }
        else
            foreach (var entry in _playerEntries)
                entry.IsFavorite = IsChannelFavorite(entry.Channel);

        SetCurrentPlayerEntry(current);
        if (!sameRows && scrollOffset.HasValue)
            RestorePlayerListScrollOffset(scrollOffset.Value);
    }

    private bool IsChannelFavorite(Channel channel)
    {
        if (_catalogLandingPage.Account is not { } account) return false;
        var (type, id) = FavoriteTargetResolver.Resolve(channel);
        return _catalogRepository.IsFavorite(account, type, id);
    }

    private void PlayerFavorite_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: PlayerListEntry entry } || _catalogLandingPage.Account is not { } account) return;
        var (type, id) = FavoriteTargetResolver.Resolve(entry.Channel);
        entry.IsFavorite = _catalogRepository.ToggleFavorite(account, type, id);
        if (entry.Channel.Source.Kind == StreamKind.Episode)
            UpdateSeriesFavoriteButton(entry.IsFavorite);
        _catalogLandingPage.NotifyFavoriteChanged();
    }

    private void SeriesFavorite_Click(object sender, RoutedEventArgs args)
    {
        if (_currentSeriesId is null || _currentSeriesAccount is null) return;
        var isFavorite = _catalogRepository.ToggleFavorite(_currentSeriesAccount, CatalogItemType.Series, _currentSeriesId);
        UpdateSeriesFavoriteButton(isFavorite);
        _catalogLandingPage.NotifyFavoriteChanged();
    }

    private void UpdateSeriesFavoriteButton(bool isFavorite)
    {
        SeriesFavoriteButton.Content = isFavorite ? "★" : "☆";
        SeriesFavoriteButton.Foreground = (Brush)Application.Current.Resources[
            isFavorite ? "AppFavoriteBrush" : "AppMutedTextBrush"];
        SeriesFavoriteButton.Visibility = Visibility.Visible;
    }

    private void SetCurrentPlayerEntry(Channel current)
    {
        PlayerListEntry? currentEntry = null;
        foreach (var entry in _playerEntries)
        {
            entry.IsCurrent = entry.Channel.Source.Kind == current.Source.Kind && entry.Channel.Id == current.Id;
            if (entry.IsCurrent) currentEntry = entry;
        }
        PlayerRelatedList.SelectedItem = currentEntry;
        if (currentEntry is not null && PlayerRelatedList.Visibility == Visibility.Visible)
            EnsurePlayerEntryVisible(currentEntry);
    }

    private void PlayerRelatedList_PointerPressed(object sender, PointerRoutedEventArgs args) =>
        _playerListPointerPressOffset = FindPlayerListScrollViewer()?.VerticalOffset;

    private void RestorePlayerListScrollOffset(double verticalOffset)
    {
        // Restore after the ListView has processed selection and any collection/layout changes.
        PlayerRelatedList.DispatcherQueue.TryEnqueue(() =>
        {
            var scrollViewer = FindPlayerListScrollViewer();
            if (scrollViewer is null) return;
            var maxOffset = Math.Max(0, scrollViewer.ExtentHeight - scrollViewer.ViewportHeight);
            scrollViewer.ChangeView(null, Math.Clamp(verticalOffset, 0, maxOffset), null, true);
            PlayerRelatedList.UpdateLayout();
            if (PlayerRelatedList.SelectedItem is PlayerListEntry selectedEntry)
                EnsurePlayerEntryVisible(selectedEntry, scrollViewer);
        });
    }

    private void EnsurePlayerEntryVisible(PlayerListEntry entry, ScrollViewer? scrollViewer = null)
    {
        scrollViewer ??= FindPlayerListScrollViewer();
        if (scrollViewer is null) return;
        if (PlayerRelatedList.ContainerFromItem(entry) is not FrameworkElement container)
        {
            PlayerRelatedList.ScrollIntoView(entry);
            return;
        }

        var position = container.TransformToVisual(scrollViewer).TransformPoint(new Windows.Foundation.Point(0, 0));
        if (position.Y < 0 || position.Y + container.ActualHeight > scrollViewer.ViewportHeight)
            PlayerRelatedList.ScrollIntoView(entry);
    }

    private ScrollViewer? FindPlayerListScrollViewer(DependencyObject? element = null)
    {
        element ??= PlayerRelatedList;
        if (element is ScrollViewer scrollViewer) return scrollViewer;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
        {
            var found = FindPlayerListScrollViewer(VisualTreeHelper.GetChild(element, index));
            if (found is not null) return found;
        }
        return null;
    }

    private void ConfigureSeasonSelector(IReadOnlyList<SeriesSeason> seasons)
    {
        PlayerSeasonComboBox.ItemsSource = seasons;
        var hasMultipleSeasons = seasons.Count > 1;
        PlayerSeasonComboBox.Visibility = hasMultipleSeasons ? Visibility.Visible : Visibility.Collapsed;
        PlayerSeasonLabel.Text = seasons.Count == 1 ? seasons[0].Name : string.Empty;
        PlayerSeasonLabel.Visibility = seasons.Count == 1 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void PlayerSeasonComboBox_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (PlayerSeasonComboBox.SelectedItem is not SeriesSeason season || _nowPlayingChannel is null) return;
        var account = _currentSeriesAccount ?? _catalogLandingPage.Account;
        if (account is null || _currentSeriesId is null) return;
        var episodes = season.Episodes.Select(episode => ToEpisodeChannel(account, _currentSeriesId, episode)).ToArray();
        SetPlayerList(episodes, _nowPlayingChannel);
    }

    private async Task StartPlaybackAsync(StreamSource source)
    {
        var resumePosition = source.Kind switch
        {
            StreamKind.Movie when _catalogLandingPage.Account is { } account && _nowPlayingChannel is { } movie =>
                _catalogRepository.GetResumePosition(account, CatalogItemType.Movie, movie.Id),
            StreamKind.Episode => _resumePositionOnStart ??
                (_currentSeriesAccount is { } seriesAccount && _currentSeriesId is { } seriesId
                    ? _catalogRepository.GetResumePosition(seriesAccount, CatalogItemType.Series, seriesId) : 0),
            _ => 0,
        };
        _resumePositionOnStart = null;
        _pendingResumePosition = null;
        _resumeReady = false;
        var generation = Interlocked.Increment(ref _playbackSessionGeneration);
        var playerEntryTraceId = Volatile.Read(ref _playerEntryTraceId);
        var sessionCts = new CancellationTokenSource();
        var previousCts = Interlocked.Exchange(ref _playbackSessionCts, sessionCts);
        previousCts?.Cancel();
        previousCts?.Dispose();
        _currentSource = source;
        _playbackCompletionShown = false;
        if (_nowPlayingChannel is { } selectedChannel)
            PlayerTitleText.Text = selectedChannel.DisplayName;
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        ShowPage(ShellPage.Player);
        StatusText.Text = "Starting playback…";
        var engineName = ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native";
        LaunchDiagnostics.Write($"event=playback.start engine={engineName} kind={source.Kind}");
        LaunchDiagnostics.Write($"Playback start: engine={engineName}, kind={source.Kind}, extension={source.ContainerExtension ?? "unspecified"}");
        PlaybackAttemptResult result;
        try
        {
            // Stop both backends before opening a replacement. Each backend owns
            // native/network state independently, so stopping only the selected
            // PlaybackService can leave the previous provider connection alive.
            result = await _playbackHandoff.RunAsync(
                _ => OnUiAsync(async () =>
                {
                    if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                    await _playbackStopTask;
                    if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                    await StopBothPlaybackEnginesAsync();
                    if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                    await Task.Yield();
                    // PlaybackHandoff's delay resumes on a pool thread. Keep UI
                    // layout and both engine lifecycle calls on the window's STA.
                    if (IsCurrentPlaybackStart(generation, sessionCts))
                        PlayerPage.UpdateLayout();
                }),
                token => OnUiAsync(() => generation == Volatile.Read(ref _playbackSessionGeneration) && !sessionCts.IsCancellationRequested
                    ? _playback.PlayAsync(source, token)
                    : Task.FromResult(PlaybackAttemptResult.Cancelled)),
                sessionCts.Token);
        }
        catch (OperationCanceledException) when (sessionCts.IsCancellationRequested)
        {
            LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason=user-cancelled");
            return;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Playback start failed: {exception.GetType().Name} (HRESULT 0x{exception.HResult:X8})");
            result = PlaybackAttemptResult.HostFailure;
        }
        finally
        {
            if (ReferenceEquals(Interlocked.CompareExchange(ref _playbackSessionCts, null, sessionCts), sessionCts))
                sessionCts.Dispose();
        }

        if (!IsCurrentPlaybackStart(generation, sessionCts))
        {
            LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason=user-cancelled");
            TraceClickAway(playerEntryTraceId, $"playback-completion-discarded generation={generation} currentGeneration={Volatile.Read(ref _playbackSessionGeneration)} page={_currentPage}");
            return;
        }
        LaunchDiagnostics.Write($"Playback result: engine={engineName}, kind={source.Kind}, result={result}");
        if (result != PlaybackAttemptResult.FirstFrame)
            LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason={result switch { PlaybackAttemptResult.Cancelled => "user-cancelled", PlaybackAttemptResult.Timeout => "timeout", _ => "error" }}");
        if (_isCinemaMode && result == PlaybackAttemptResult.FirstFrame)
            RestartCinemaCursorIdleTimer();
        StatusText.Text = PlaybackStatusMessage(result, source.Kind);
        if (result == PlaybackAttemptResult.FirstFrame)
        {
            PlayerVideoCurtain.Visibility = Visibility.Collapsed;
            _pendingResumePosition = resumePosition > 0 ? resumePosition : null;
            _resumeReady = true;
        }
        PauseButton.Content = result == PlaybackAttemptResult.FirstFrame
            ? "Pause"
            : result == PlaybackAttemptResult.Cancelled ? "Play" : "Retry";
        VolumeSlider.Value = _engine.Volume;
    }

    private bool IsCurrentPlaybackStart(long generation, CancellationTokenSource sessionCts) =>
        generation == Volatile.Read(ref _playbackSessionGeneration) &&
        !sessionCts.IsCancellationRequested &&
        _currentPage == ShellPage.Player;

    private void TraceClickAway(long traceId, string message)
    {
        if (traceId == 0) return;
        var elapsed = Stopwatch.GetTimestamp() - Volatile.Read(ref _playerEntryTraceStartedAt);
        var elapsedMilliseconds = elapsed * 1000d / Stopwatch.Frequency;
        LaunchDiagnostics.Write($"CLICK-AWAY-TRACE id={traceId} elapsedMs={elapsedMilliseconds:0.000} {message}");
    }

    private void StopPlaybackForNavigation()
    {
        SaveResumePosition(force: true);
        if (_currentSource is { } stoppingSource)
            LaunchDiagnostics.Write($"event=playback.stop engine={(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native")} kind={stoppingSource.Kind} reason={(_engine.IsEnded ? "completed" : "user-cancelled")}");
        // Hide the video host before touching playback or starting the catalog fade.
        // The WinUI LibVLC host is swap-chain-backed, so there is no child HWND whose
        // visibility or z-order can be controlled independently from this XAML element.
        SetPlaybackSurfaceVisibility(false);
        var playerEntryTraceId = Volatile.Read(ref _playerEntryTraceId);
        var playerEntryElapsed = Stopwatch.GetTimestamp() - Volatile.Read(ref _playerEntryTraceStartedAt);
        var playerEntryElapsedMilliseconds = playerEntryElapsed * 1000d / Stopwatch.Frequency;
        if (playerEntryTraceId != 0 && playerEntryElapsedMilliseconds <= 5000)
            TraceClickAway(playerEntryTraceId, $"event=rapid-navigation-away delayMs={playerEntryElapsedMilliseconds:0.000} destination={_currentPage}");
        TraceBackTransition($"playback-surface-hidden-before-navigation {VideoSurfaceState()}");
        TraceBackTransition($"player-stop-begin source={_currentSource?.Kind.ToString() ?? "none"} playing={_engine.IsPlaying} buffering={_engine.IsBuffering} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility}");
        Interlocked.Increment(ref _itemSelectionGeneration);
        Interlocked.Increment(ref _metadataGeneration);
        Interlocked.Increment(ref _playbackSessionGeneration);
        var sessionCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        sessionCts?.Cancel();
        sessionCts?.Dispose();

        _currentSource = null;
        _pendingResumePosition = null;
        _resumeReady = false;
        _nowPlayingChannel = null;
        _currentSeriesId = null;
        _currentSeriesTitle = null;
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        _playerSiblings = Array.Empty<Channel>();
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
        PlayerTitleText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        PlayerSideTitle.Text = string.Empty;
        PlayerSideSubtitle.Text = string.Empty;
        PlayerNowPlayingText.Text = string.Empty;
        PlayerNowPlayingText.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.SelectedItem = null;
        PlayerSeasonComboBox.ItemsSource = null;
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonLabel.Text = string.Empty;
        PlayerSeasonLabel.Visibility = Visibility.Collapsed;
        _playerEntries.Clear();
        SetCinemaMode(false);

        _playbackStopTask = StopBothPlaybackEnginesAsync();
        TraceBackTransition($"player-stop-return asyncStopStarted={!_playbackStopTask.IsCompleted} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility}");
    }

    private async Task StopBothPlaybackEnginesAsync()
    {
        TraceBackTransition($"engine-stop-both-begin vlcPlaying={_vlcEngine.IsPlaying} nativePlaying={_nativeEngine.IsPlaying}");
        await Task.WhenAll(_vlcPlayback.StopAsync(), _nativePlayback.StopAsync());
        TraceBackTransition($"engine-stop-both-complete vlcPlaying={_vlcEngine.IsPlaying} nativePlaying={_nativeEngine.IsPlaying}");
    }

    private Task<T> OnUiAsync<T>(Func<Task<T>> action)
    {
        var dispatcher = WindowRoot.DispatcherQueue;
        if (dispatcher.HasThreadAccess)
            return action();

        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!dispatcher.TryEnqueue(async () =>
            {
                try
                {
                    completion.TrySetResult(await action());
                }
                catch (Exception exception)
                {
                    completion.TrySetException(exception);
                }
            }))
        {
            completion.TrySetException(new InvalidOperationException("The window dispatcher is no longer available."));
        }
        return completion.Task;
    }

    private Task OnUiAsync(Func<Task> action) => OnUiAsync(async () =>
    {
        await action();
        return true;
    });

    private static string PlaybackStatusMessage(PlaybackAttemptResult result, StreamKind kind) => result switch
    {
        PlaybackAttemptResult.FirstFrame => "Playing",
        PlaybackAttemptResult.Cancelled => "Playback cancelled",
        PlaybackAttemptResult.Timeout => $"The {kind.ToString().ToLowerInvariant()} stream did not start before the timeout. Check the provider connection and stream URL, then retry.",
        PlaybackAttemptResult.HostFailure => $"The player could not open this {kind.ToString().ToLowerInvariant()} stream. Check its content ID and container extension, the provider account's active streams, and the network, then retry.",
        PlaybackAttemptResult.DecodeFailure => "The stream opened but could not be decoded. Try another episode or playback engine.",
        PlaybackAttemptResult.UnsupportedMedia => "This stream format is not supported by the selected playback engine.",
        PlaybackAttemptResult.NetworkFailure => "The stream could not be reached. Check the provider connection and retry.",
        _ => $"Playback failed ({result}). Check the provider connection and stream details, then retry.",
    };

    private void FadeIn(UIElement element)
    {
        var visual = ElementCompositionPreview.GetElementVisual(element);
        TraceBackTransition($"showpage-fade-start element={(element as FrameworkElement)?.Name ?? element.GetType().Name} priorOpacity={visual.Opacity:0.000}");
        visual.StopAnimation(nameof(Visual.Opacity));
        visual.Opacity = 0;
        var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(1f, 1f);
        animation.Duration = TimeSpan.FromMilliseconds(150);
        visual.StartAnimation(nameof(Visual.Opacity), animation);
        TraceBackTransition($"showpage-fade-animated element={(element as FrameworkElement)?.Name ?? element.GetType().Name} durationMs=150");
    }

    private async Task LoadPlayerMetadataAsync(Channel channel, CatalogItemType type)
    {
        var generation = Interlocked.Increment(ref _metadataGeneration);
        var playerEntryTraceId = Volatile.Read(ref _playerEntryTraceId);
        var account = _catalogLandingPage.Account;
        SetPlayerMetadata(null, type);
        if (account is null)
        {
            SetPlayerMetadata(new CatalogMetadata(), type);
            return;
        }
        try
        {
            var metadata = _catalogRepository.GetMetadata(account, type, channel.Id);
            if (metadata is null)
            {
                var provider = App.Services.GetRequiredService<ICatalogProvider>();
                if (type == CatalogItemType.Movie && provider is IMovieInfoProvider movieProvider)
                    metadata = (await movieProvider.GetMovieInfoAsync(account, channel.Id)).Metadata;
                if (metadata is not null)
                    _catalogRepository.SaveMetadata(account, type, channel.Id, metadata);
            }

            if (generation != Volatile.Read(ref _metadataGeneration) ||
                _currentPage != ShellPage.Player ||
                _nowPlayingChannel?.Id != channel.Id)
            {
                TraceClickAway(playerEntryTraceId, $"metadata-completion-discarded generation={generation} currentGeneration={Volatile.Read(ref _metadataGeneration)} page={_currentPage}");
                return;
            }
            metadata ??= new CatalogMetadata();
            ApplyMetadataToChannel(channel, type, metadata);
            SetPlayerMetadata(metadata, type);
        }
        catch
        {
            if (generation == Volatile.Read(ref _metadataGeneration) &&
                _currentPage == ShellPage.Player &&
                _nowPlayingChannel?.Id == channel.Id)
                SetPlayerMetadata(new CatalogMetadata(), type);
            else
                TraceClickAway(playerEntryTraceId, $"metadata-failure-discarded generation={generation} currentGeneration={Volatile.Read(ref _metadataGeneration)} page={_currentPage}");
        }
    }

    private void ApplyMetadataToChannel(Channel channel, CatalogItemType type, CatalogMetadata? metadata)
    {
        if (metadata is not null && channel.Metadata is IDictionary<string, string> values)
        {
            Add("year", metadata.Year);
            Add("rating", metadata.Rating);
            Add("genre", metadata.Genre);
            Add("plot", metadata.Plot);
            Add("cast", metadata.Cast);
            void Add(string key, string? value)
            {
                if (!string.IsNullOrWhiteSpace(value)) values[key] = value;
            }
        }
        if (metadata is not null && _catalogLandingPage.Account is { } account)
            _catalogRepository.SaveMetadata(account, type, channel.Id, metadata);
        _catalogLandingPage.NotifyMetadataChanged(channel.Id);
    }

    private void SetPlayerMetadata(CatalogMetadata? metadata, CatalogItemType? type = null)
    {
        var hasAbout = type is CatalogItemType.Movie or CatalogItemType.Series;
        var hasDescription = !string.IsNullOrWhiteSpace(metadata?.Plot) || !string.IsNullOrWhiteSpace(metadata?.Cast);
        PlayerAboutButton.Visibility = hasAbout && hasDescription
            ? Visibility.Visible
            : Visibility.Collapsed;
        PlayerMetadataLine.Visibility = hasAbout ? Visibility.Visible : Visibility.Collapsed;
        if (metadata is null)
        {
            PlayerMetadataLine.Text = string.Empty;
            PlayerAboutText.Text = "Loading details…";
            return;
        }
        PlayerMetadataLine.Text = string.Join(" · ", new[]
        {
            metadata.Year,
            RatingDisplayFormatter.Format(metadata.Rating) is { } rating ? $"★ {rating}" : null,
            metadata.Genre,
        }.Where(value => !string.IsNullOrWhiteSpace(value)));
        var about = new List<string>();
        if (!string.IsNullOrWhiteSpace(metadata.Plot)) about.Add(metadata.Plot);
        if (!string.IsNullOrWhiteSpace(metadata.Cast)) about.Add($"Cast: {metadata.Cast}");
        PlayerAboutText.Text = about.Count == 0 ? "No description is available for this title." : string.Join(Environment.NewLine + Environment.NewLine, about);
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
        if (_isCinemaMode && !(_engine.IsPlaying || _engine.IsBuffering))
            StopCinemaCursorIdleTimer(showCursor: true);
        if (!_seriesCompletionRecorded && _engine.IsEnded && _currentSeriesId is not null &&
            _currentSeriesAccount is not null && _nowPlayingChannel?.Source.Kind == StreamKind.Episode)
        {
            _catalogRepository.UpdateSeriesPlayback(_currentSeriesAccount, _currentSeriesId, _nowPlayingChannel.Id, finished: true);
            _seriesCompletionRecorded = true;
        }
        if (!_playbackCompletionShown && _engine.IsEnded && _nowPlayingChannel is { } finishedChannel)
        {
            SetCurrentPlayerEntry(finishedChannel);
            _playbackCompletionShown = true;
        }
        var timeline = _engine.Timeline;
        if (_pendingResumePosition is { } resume && timeline.CanSeek)
        {
            _engine.Seek(Math.Min(resume, Math.Max(0, timeline.DurationMilliseconds!.Value - 1000)));
            _pendingResumePosition = null;
            timeline = _engine.Timeline;
        }
        SaveResumePosition();
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

    private void SaveResumePosition(bool force = false)
    {
        if (!_resumeReady || _currentPage != ShellPage.Player || _nowPlayingChannel is not { } channel ||
            _catalogLandingPage.Account is not { } account || _currentSource is null)
            return;

        CatalogItemType type;
        string id;
        if (channel.Source.Kind == StreamKind.Movie)
        {
            type = CatalogItemType.Movie;
            id = channel.Id;
        }
        else if (channel.Source.Kind == StreamKind.Episode && _currentSeriesId is not null)
        {
            type = CatalogItemType.Series;
            id = _currentSeriesId;
        }
        else return;

        var now = DateTimeOffset.UtcNow;
        if (!force && now - _lastResumeSavedAt < TimeSpan.FromSeconds(5)) return;
        var position = _engine.IsEnded ? 0 : _engine.Timeline.PositionMilliseconds;
        if (position <= 0 && !_engine.IsEnded) return;
        _catalogRepository.UpdateResumePosition(account, type, id, position);
        LaunchDiagnostics.Write($"event=resume.save kind={type} positionMs={position} completed={_engine.IsEnded}");
        _lastResumeSavedAt = now;
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
        var pressOffset = _playerListPointerPressOffset;
        _playerListPointerPressOffset = null;
        if (args.ClickedItem is not PlayerListEntry entry || entry.Channel.Source.DirectUri is null)
        {
            if (pressOffset.HasValue) RestorePlayerListScrollOffset(pressOffset.Value);
            return;
        }
        if (entry.Channel.Source.Kind == StreamKind.Episode)
        {
            Interlocked.Increment(ref _itemSelectionGeneration);
            Interlocked.Increment(ref _metadataGeneration);
            OpenSeriesEpisode(entry.Channel);
        }
        else
            PlayRelatedChannel(entry.Channel);
        if (pressOffset.HasValue)
            RestorePlayerListScrollOffset(pressOffset.Value);
    }

    private void PlayRelatedChannel(Channel channel)
    {
        if (channel.Source.DirectUri is null || channel.Source.Kind is not (StreamKind.Movie or StreamKind.Live)) return;
        SaveResumePosition(force: true);

        // ItemClick supplies the clicked data item directly. Keep the category rows
        // bound in place and update only the playing marker; rebuilding the list here
        // used to replace it with a one-item movie list and reset its scroll offset.
        Interlocked.Increment(ref _itemSelectionGeneration);
        Interlocked.Increment(ref _metadataGeneration);
        _currentSeriesId = null;
        _currentSeriesTitle = null;
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        SeriesFavoriteButton.Visibility = Visibility.Collapsed;

        if (_catalogLandingPage.Account is { } account)
        {
            _catalogRepository.RecordVisit(account,
                channel.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live,
                channel.Id);
            _catalogLandingPage.NotifyVisitRecorded();
        }

        _nowPlayingChannel = channel;
        _resumePositionOnStart = null;
        _currentSource = channel.Source;
        PlayerTitleText.Text = channel.DisplayName;
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        _playbackCompletionShown = false;
        SetCurrentPlayerEntry(channel);
        _ = LoadPlayerMetadataAsync(channel,
            channel.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live);
        _ = StartPlaybackAsync(channel.Source);
        if (channel.Source.Kind == StreamKind.Movie && _playerSiblings.Count <= 1)
            _ = LoadMovieCategorySiblingsAsync(channel, Volatile.Read(ref _itemSelectionGeneration));
    }

    private void PlayerBackButton_Click(object sender, RoutedEventArgs args) => ReturnFromPlayer();

    private void PlayerHomeButton_Click(object sender, RoutedEventArgs args) =>
        ShowPage(ShellPage.Home);

    private void ReturnFromPlayer()
    {
        BeginBackTransitionTrace($"return-from-player input returnPage={_playerReturnPage} cinema={_isCinemaMode} bounds={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
        if (_playerReturnPage == ShellPage.Catalog)
        {
            _ = ShowCatalogAsync(_catalogLandingPage.ActiveMode);
        }
        else ShowPage(_playerReturnPage);
    }

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

        TraceBackTransition($"cinema-layout-enter enabled={enabled} windowMode={_windowState.Mode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");

        if (enabled)
        {
            _preCinemaWindowMode = _windowState.Mode;
            _preCinemaFocus = FocusManager.GetFocusedElement(WindowRoot.XamlRoot) as FrameworkElement;
            _windowState.Apply(WindowStateController.WindowMode.Fullscreen);
        }

        _isCinemaMode = enabled;
        if (enabled && (_engine.IsPlaying || _engine.IsBuffering)) RestartCinemaCursorIdleTimer();
        else StopCinemaCursorIdleTimer(showCursor: true);
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
            TraceBackTransition($"cinema-window-restored mode={_windowState.Mode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
            ApplyWindowChromeState();
            var focusTarget = _preCinemaFocus is { Visibility: Visibility.Visible, IsTabStop: true }
                ? _preCinemaFocus
                : CinemaButton;
            focusTarget.Focus(FocusState.Programmatic);
            _preCinemaFocus = null;
        }
        TraceBackTransition($"cinema-layout-exit enabled={enabled} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
    }

    private void PlayerPage_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_isCinemaMode || _currentPage != ShellPage.Player || !(_engine.IsPlaying || _engine.IsBuffering)) return;
        SetCinemaCursorVisible(true);
        RestartCinemaCursorIdleTimer();
    }

    private void CinemaCursorIdleTimer_Tick(object? sender, object args)
    {
        _cinemaCursorIdleTimer.Stop();
        if (_isCinemaMode && _currentPage == ShellPage.Player && (_engine.IsPlaying || _engine.IsBuffering))
            SetCinemaCursorVisible(false);
    }

    private void RestartCinemaCursorIdleTimer()
    {
        if (!_isCinemaMode || _currentPage != ShellPage.Player || !(_engine.IsPlaying || _engine.IsBuffering)) return;
        SetCinemaCursorVisible(true);
        _cinemaCursorIdleTimer.Stop();
        _cinemaCursorIdleTimer.Start();
    }

    private void StopCinemaCursorIdleTimer(bool showCursor)
    {
        _cinemaCursorIdleTimer.Stop();
        if (showCursor) SetCinemaCursorVisible(true);
    }

    private void SetCinemaCursorVisible(bool visible)
    {
        if (_cinemaCursorHidden == !visible) return;
        if (visible)
        {
            while (ShowCursor(true) < 0) { }
            _cinemaCursorHidden = false;
        }
        else
        {
            while (ShowCursor(false) >= 0) { }
            _cinemaCursorHidden = true;
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
        _windowClosed = true;
        DismissIdleOverlay("window-close");
        _idleTimer.Stop();
        _idleTimer.Tick -= IdleTimer_Tick;
        _featuredRotationTimer.Stop();
        _featuredRotationTimer.Tick -= FeaturedRotationTimer_Tick;
        Activated -= MainWindow_Activated;
        SaveResumePosition(force: true);
        if (_currentSource is { } closingSource)
            LaunchDiagnostics.Write($"event=playback.stop engine={(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native")} kind={closingSource.Kind} reason={(_engine.IsEnded ? "completed" : "user-cancelled")}");
        _playbackUiTimer.Stop();
        _playbackUiTimer.Tick -= PlaybackUiTimer_Tick;
        _cinemaCursorIdleTimer.Stop();
        _cinemaCursorIdleTimer.Tick -= CinemaCursorIdleTimer_Tick;
        SetCinemaCursorVisible(true);
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
        Account,
        Catalog,
        Player
    }

    public sealed class PlayerListEntry(Channel channel, bool isFavorite = false) : INotifyPropertyChanged
    {
        private bool _isCurrent;
        private bool _isFavorite = isFavorite;
        private static Brush AccentBrush => (Brush)Application.Current.Resources["AppAccentBrush"];
        private static Brush NormalBrush => (Brush)Application.Current.Resources["AppTextBrush"];
        private static Brush SelectedTextBrush => (Brush)Application.Current.Resources["AppDeepBrush"];
        private static Brush MutedBrush => (Brush)Application.Current.Resources["AppMutedTextBrush"];

        public Channel Channel { get; } = channel;
        public string Title => Channel.DisplayName;
        public Visibility FavoriteVisibility => FavoriteTargetResolver.ShowsPerItemFavorite(Channel)
            ? Visibility.Visible
            : Visibility.Collapsed;
        public Brush BackgroundBrush => _isCurrent ? AccentBrush : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        public Brush ForegroundBrush => _isCurrent ? SelectedTextBrush : NormalBrush;
        public Brush FavoriteBrush => _isFavorite
            ? (Brush)Application.Current.Resources["AppFavoriteBrush"]
            : MutedBrush;
        public string FavoriteGlyph => _isFavorite ? "★" : "☆";
        public bool IsFavorite
        {
            get => _isFavorite;
            set
            {
                if (_isFavorite == value) return;
                _isFavorite = value;
                OnPropertyChanged(nameof(IsFavorite));
                OnPropertyChanged(nameof(FavoriteBrush));
                OnPropertyChanged(nameof(FavoriteGlyph));
            }
        }
        public bool IsCurrent
        {
            get => _isCurrent;
            set
            {
                if (_isCurrent == value) return;
                _isCurrent = value;
                OnPropertyChanged(nameof(IsCurrent));
                OnPropertyChanged(nameof(BackgroundBrush));
                OnPropertyChanged(nameof(ForegroundBrush));
            }
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}
