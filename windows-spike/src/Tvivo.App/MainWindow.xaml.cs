using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;
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
using System.Globalization;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Text.Json;
using Windows.ApplicationModel.DataTransfer;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Tvivo.Playback;
using Tvivo.App.Pages;

namespace Tvivo.App;

public sealed partial class MainWindow : Window
{
    private const int DwmwaUseImmersiveDarkMode = 20;
    private readonly long _windowConstructionTimestamp = Stopwatch.GetTimestamp();
    private readonly HashSet<string> _startupPerfPhases = new(StringComparer.Ordinal);
    private readonly ThemePreferenceStore _themePreferenceStore = new();
    private ThemePreference _selectedTheme;

    private PlaybackService _playback;
    private readonly VlcPlaybackEngine _vlcEngine;
    private readonly FFmpegInteropPlaybackEngine _nativeEngine;
    private readonly PlaybackService _vlcPlayback;
    private readonly PlaybackService _nativePlayback;
    private IPlaybackEngine _engine;
    private readonly EpgCoordinator _epgCoordinator;
    private ITrackSelectingEngine? _trackSelectingEngine;
    private PlaybackTrackSnapshot _trackSnapshot = PlaybackTrackSnapshot.Unresolved;
    private bool _defaultSubtitleAppliedForSession;
    private int _trackRefreshQueued;
    private readonly HomePage _homePage;
    private readonly ProviderSetupPage _providerSetupPage;
    private readonly AccountPage _accountPage;
    private readonly CatalogLandingPage _catalogLandingPage;
    private readonly SqliteCatalogRepository _catalogRepository;
    private readonly PlaybackHandoff _playbackHandoff = new(TimeSpan.FromMilliseconds(350));
    private ShellPage? _currentPage;
    private ShellPage _playerReturnPage = ShellPage.Catalog;
    private PlayerPresentation _playerPresentation = PlayerPresentation.Full;
    private CompactPlayerGeometry _compactGeometry = CompactPlayerGeometry.Default;
    private CompactPlayerGeometry _compactDisplayedGeometry = CompactPlayerGeometry.Default;
    private bool _compactGeometryLoaded;
    private bool _compactGeometryDirty;
    private bool _compactGeometryNeedsDefaultAnchor;
    private bool _compactGeometryGestureActive;
    private bool _compactGeometryResize;
    private CompactResizeEdges _compactResizeEdges;
    private uint _compactGeometryPointerId;
    private Windows.Foundation.Point _compactPointerStart;
    private CompactPlayerGeometry _compactGeometryStart;
    private CompactPlayerGeometry _compactGeometryLogicalStart;
    private IReadOnlyList<Channel> _playerSiblings = Array.Empty<Channel>();
    private readonly ObservableCollection<PlayerListEntry> _playerEntries = new();
    private readonly NextPlaybackSelector _nextPlaybackSelector = new();
    private readonly PlaybackFailurePolicy _playbackFailurePolicy = new();
    private readonly PlaybackInterruptionBudget _interruptionBudget = new();
    private readonly SleepTimerPolicy _sleepTimerPolicy = new();
    private readonly long _sleepTimerClockOrigin = Stopwatch.GetTimestamp();
    private int _sleepTimerMinutes;
    private bool _sleepTimerWarningVisible;
    private bool _sleepTimerStopStarted;
    private bool _sleepTimerCustomEntryOpen;
    private bool _sleepTimerChangeEntryOpen;
    private readonly DispatcherTimer _sleepTimerNoticeTimer = new() { Interval = TimeSpan.FromSeconds(4) };
    private string _sleepTimerRemainingText = string.Empty;
    private string[] _shufflePlaylistIds = Array.Empty<string>();
    private string? _preparedNextItemId;
    private string? _preparedNextForItemId;
    private bool _preparedNextWasShuffle;
    private PlaybackMode _episodePlaybackMode = PlaybackMode.Next;
    private PlaybackMode _moviePlaybackMode = PlaybackMode.Shuffle;
    private MinimizeToMiniMode _minimizeToMiniMode = MinimizeToMiniMode.MiniPlayerWhilePlaying;
    private int _playbackAttempts = PlaybackRetryPolicy.DefaultAttempts;
    private WindowMiniGeometry _windowMiniGeometry = WindowMiniGeometry.Default;
    private WindowMiniGeometry _windowMiniGeometryAtEntry;
    private bool _windowMiniGeometryDirty;
    private bool _windowMiniTransitionInProgress;
    private ShellPage? _windowMiniPreviousPage;
    private PlayerPresentation _windowMiniPreviousPresentation = PlayerPresentation.Full;
    private bool _windowMiniPreviousCinema;
    private bool _suppressTopSearchChanged;
    private bool _playerMovieProgressReady = true;
    private long _playerMovieProgressGeneration;
    private double? _playerListPointerPressOffset;
    private IReadOnlyList<SeriesSeason> _playerSeriesSeasons = Array.Empty<SeriesSeason>();
    private IReadOnlyDictionary<string, PlaybackProgress> _seriesEpisodeProgress = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
    private string? _currentSeriesId;
    private string? _currentSeriesTitle;
    private ProviderAccount? _currentSeriesAccount;
    private CatalogMetadata? _currentSeriesMetadata;
    private Channel? _nowPlayingChannel;
    private bool _seriesCompletionRecorded;
    private bool _movieCompletionRecorded;
    private bool _playbackCompletionShown;
    private bool _unverifiedPlaybackEndHandled;
    private bool _nextEpisodeAutoPlaySuppressed;
    private bool _nextEpisodeAdvanceStarted;
    private long _catalogRequestGeneration;
    private long _itemSelectionGeneration;
    private StreamSource? _currentSource;
    private long _playbackSessionGeneration;
    private long _pendingPlaybackTargetGeneration = -1;
    private string? _pendingPlaybackTargetLine;
    private long _activePlaybackSessionGeneration = -1;
    private long _metadataGeneration;
    private long _playerEntryTraceId;
    private long _playerEntryTraceStartedAt;
    private string? _seriesOpeningId;
    private CancellationTokenSource? _seriesOpenCancellation;
    private bool _seriesOpenInfoVisible;
    private bool _seriesOpenMessageIsFailure;
    private bool _terminalPlaybackFailure;
    private bool _sleepTimerEnded;
    private bool _playerLoadingArtActive;
    private CancellationTokenSource? _playbackSessionCts;
    private Task _playbackStopTask = Task.CompletedTask;
    private readonly DispatcherTimer _playerEpgTimer = new() { Interval = TimeSpan.FromSeconds(45) };
    private EpgNowNext? _playerNowNext;
    private long _playerEpgGeneration;
    private bool _isClosing;
    private bool _isCinemaMode;
    private bool _cinemaTransitionInProgress;
    private int _cinemaTransitionGeneration;
    private bool _cinemaCursorHidden;
    private readonly Dictionary<Button, object?> _cinemaToolTips = new();
    private readonly CinemaCursorController _cinemaCursorController;
    // Cinema mode shows the transport as an overlay that fades out while playing and the mouse is idle.
    private bool _cinemaControlsVisible = true;
    private bool _compactPointerOverControls;
    private bool _cinemaPointerMoveDiagnosticWritten;
    private DateTimeOffset _cinemaLastMouseActivityAt = DateTimeOffset.UtcNow;
    private DateTimeOffset _cinemaLastHideAt = DateTimeOffset.MinValue;
    private Windows.Foundation.Point _lastCinemaPointerPosition;
    private bool _hasCinemaPointerPosition;
    private bool _cinemaWindowActive = true;
    private WindowStateController.WindowMode _preCinemaWindowMode;
    private FrameworkElement? _preCinemaFocus;
    private DateTimeOffset _lastVideoTapAt;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _videoClickPauseTimer;
    private Windows.Foundation.Point _lastVideoTapPoint;
    private bool _lastVideoTapWasCompact;
    private bool _compactVideoPointerActive;
    private bool _compactVideoPointerDragged;
    private uint _compactVideoPointerId;
    private Windows.Foundation.Point _compactVideoPointerStart;
    private readonly WindowStateController _windowState;
    private bool _isDraggingProgress;
    private bool _isHeldSeeking;
    private bool _heldSeekDidScrub;
    private bool _suppressHeldSeekClick;
    private Button? _heldSeekButton;
    private int _heldSeekDirection;
    private long _heldSeekTarget;
    private DateTimeOffset _heldSeekStartedAt;
    private DateTimeOffset _heldSeekLastStepAt;
    private readonly DispatcherTimer _heldSeekTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };
    private bool _isUpdatingProgress;
    private bool _isUpdatingVolume;
    private PlaybackMuteState _playbackMuteState;
    private long? _resumePositionOnStart;
    // The engine the user picked in settings. A per-item fallback may borrow the other engine without
    // changing it; the next item goes back to this one unless it has its own working override.
    // The side playlist can be hidden to give the video the full width. Kept for the session.
    private bool _playlistHidden;
    private IPlaybackEngine _preferredEngine = null!;
    private readonly Dictionary<string, IPlaybackEngine> _itemEngineOverride = new();
    private readonly HashSet<string> _engineFallbackTried = new();
    private long? _pendingResumePosition;
    private long _lastGoodPlaybackPositionMilliseconds;
    private long _lastInterruptionObservedPosition;
    private bool _hasPlaybackAdvancedThisSession;
    private TimeSpan _lastForwardProgressAt;
    private PlaybackUserIntent _playbackIntent = PlaybackUserIntent.StoppedByUser;
    private bool _awaitingForwardProgressAfterAction;
    private long _recoveryGeneration = -1;
    private bool _recoveryInFlight;
    private bool _resumeReady;
    private long _diagnosticOpenStartedAt;
    private long _diagnosticLastPosition;
    private bool _diagnosticFirstProgressReported;
    private DateTimeOffset _lastResumeSavedAt;
    private readonly long _stallClockStart = Stopwatch.GetTimestamp();
    private readonly DispatcherTimer _playbackUiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _cinemaCursorIdleTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private readonly DispatcherTimer _cinemaPointerPollTimer = new() { Interval = TimeSpan.FromMilliseconds(150) };
    private bool _backTransitionTraceActive;
    private int _backTransitionRenderSamples;
    private long _backTransitionTraceId;
    private long _backTransitionStartedAt;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int value, int valueSize);

    public MainWindow()
    {
        PerfScope.Logger = LaunchDiagnostics.Write;
        _selectedTheme = _themePreferenceStore.Read();
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _vlcEngine = App.Services.GetRequiredService<VlcPlaybackEngine>();
        _vlcEngine.StateChanged += VlcEngine_StateChanged;
        _nativeEngine = App.Services.GetRequiredService<FFmpegInteropPlaybackEngine>();
        _nativeEngine.PlaybackFailed += NativeEngine_PlaybackFailed;
        _vlcEngine.LifecycleEvent += LaunchDiagnostics.Write;
        _nativeEngine.LifecycleEvent += LaunchDiagnostics.Write;
        _vlcPlayback = new PlaybackService(_vlcEngine);
        _nativePlayback = new PlaybackService(_nativeEngine);
        var useVlc = string.Equals(ReadPlaybackEnginePreference(), "LibVLC", StringComparison.Ordinal);
        Volatile.Write(ref _engine, useVlc ? _vlcEngine : _nativeEngine);
        _preferredEngine = _engine;
        LaunchDiagnostics.SetAppContext(EngineLabel(_engine), _selectedTheme.ToString());
        _playback = useVlc ? _vlcPlayback : _nativePlayback;
        _epgCoordinator = App.Services.GetRequiredService<EpgCoordinator>();
        _epgCoordinator.PlaybackBusy = () =>
        {
            try
            {
                var sessionCts = Volatile.Read(ref _playbackSessionCts);
                var engine = Volatile.Read(ref _engine);
                return sessionCts is not null || engine.IsBuffering;
            }
            catch
            {
                return false;
            }
        };
        _homePage = new HomePage();
        _providerSetupPage = new ProviderSetupPage();
        _accountPage = new AccountPage();
        _catalogLandingPage = new CatalogLandingPage();
        _homePage.UseArtworkPipeline(_catalogLandingPage);
        _catalogRepository = App.Services.GetRequiredService<SqliteCatalogRepository>();
        InitializeComponent();
        SleepTimerStateChanged += MainWindow_SleepTimerStateChanged;
        _sleepTimerNoticeTimer.Tick += SleepTimerNoticeTimer_Tick;
        WindowRoot.ActualThemeChanged += WindowRoot_ActualThemeChanged;
        // Sliders mark pointer presses handled, so listen for handled events too.
        VolumeSlider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(VolumeSlider_PointerPressed), true);
        CompactVolumeSlider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(VolumeSlider_PointerPressed), true);
        _cinemaCursorController = new CinemaCursorController(
            WinRT.Interop.WindowNative.GetWindowHandle(this),
            reason => ShowCinemaControls(reason, resetIdleDeadline: true));
        var playbackOptions = ReadPlaybackOptions();
        _episodePlaybackMode = playbackOptions.EpisodeMode;
        _moviePlaybackMode = playbackOptions.MovieMode;
        _minimizeToMiniMode = playbackOptions.MinimizeToMiniMode;
        _playbackAttempts = playbackOptions.PlaybackAttempts;
        SyncPlaybackModeToggles(_episodePlaybackMode);
        AudioSubtitlesFlyout.Opened += AudioSubtitlesFlyout_Opened;
        AudioSubtitlesFlyout.Closed += AudioSubtitlesFlyout_Closed;
        CompactAudioSubtitlesFlyout.Opened += AudioSubtitlesFlyout_Opened;
        CompactAudioSubtitlesFlyout.Closed += CompactAudioSubtitlesFlyout_Closed;
        SetMutePresentation();
        SetTrackSelectingEngine(_engine);
        _epgCoordinator.EpgUpdated += EpgCoordinator_EpgUpdated;
        _playerEpgTimer.Tick += PlayerEpgTimer_Tick;
        PlayerRelatedList.ItemsSource = _playerEntries;
        Title = "Tvivo";
        var iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "Tvivo.ico");
        if (System.IO.File.Exists(iconPath)) AppWindow.SetIcon(iconPath);
        TitleBarDragRegion.PointerPressed += TitleBarDragRegion_PointerPressed;
        WindowRoot.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler(WindowRoot_KeyDown), true);
        WindowRoot.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(WindowRoot_PointerWheelInput), true);
        CompactChrome.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(CompactChrome_PointerPressed), true);
        CompactChrome.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(CompactChrome_PointerReleased), true);
        CompactChrome.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler(CompactChrome_PointerCaptureLost), true);
        CompactChrome.GotFocus += CompactChrome_GotFocus;
        CompactChrome.LostFocus += CompactChrome_LostFocus;
        WindowRoot.SizeChanged += WindowRoot_SizeChanged;
        ContentSurfaceHost.Loaded += ContentSurfaceHost_Loaded;
        ContentSurfaceHost.SizeChanged += ContentSurfaceHost_SizeChanged;
        VideoView.Unloaded += (_, _) => TraceBackTransition("video-view-unloaded");
        NativePlayerElement.Unloaded += (_, _) => TraceBackTransition("native-player-unloaded");
        _windowState = new WindowStateController(this);
        _windowMiniGeometry = ReadWindowMiniGeometry() ?? _windowState.GetDefaultWindowMiniGeometry();
        _windowState.InstallMinimizeInterceptor(WindowRoot.DispatcherQueue,
            () => ShouldMinimizeBecomeWindowMini(), () => EnterWindowMiniPlayer(), HandleWindowMiniModeRequest, ForceExitWindowMini);
        _windowState.WindowMiniGeometryChanged += WindowState_WindowMiniGeometryChanged;
        ApplySelectedTheme();
        ApplyWindowChromeState();
        _catalogLandingPage.InteractionReadinessChanged += CatalogLandingPage_InteractionReadinessChanged;
        _catalogLandingPage.StartupPhaseCompleted += CatalogLandingPage_StartupPhaseCompleted;
        _catalogLandingPage.SearchTextChanged += CatalogLandingPage_SearchTextChanged;
        _nativeEngine.Player.Volume = 1;
        NativePlayerElement.SetMediaPlayer(_nativeEngine.Player);
        _accountPage.SetPlaybackEngine(useVlc ? "LibVLC" : "Native");
        _accountPage.SetPlaybackAttempts(_playbackAttempts);
        _accountPage.SetMinimizeToMiniMode(_minimizeToMiniMode);
        _accountPage.MinimizeToMiniModeChanged += AccountPage_MinimizeToMiniModeChanged;
        _accountPage.PlaybackAttemptsChanged += AccountPage_PlaybackAttemptsChanged;
        _accountPage.PlaybackEngineChanged += AccountPage_PlaybackEngineChanged;
        ApplyPlaybackEngineVisuals();
        _playbackUiTimer.Tick += PlaybackUiTimer_Tick;
        if (!PerfToggles.IsDisabled("playbacktimer"))
            _playbackUiTimer.Start();
        _heldSeekTimer.Tick += HeldSeekTimer_Tick;
        Activated += MainWindow_Activated;
        _cinemaCursorIdleTimer.Tick += CinemaCursorIdleTimer_Tick;
        _cinemaPointerPollTimer.Tick += CinemaPointerPollTimer_Tick;
        PlayerPage.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(PlayerPage_PointerMoved), true);
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _catalogLandingPage.RefreshStateChanged += CatalogLandingPage_RefreshStateChanged;
        _catalogLandingPage.VisibleCatalogDataChanged += (_, _) => _homePage.MarkDataChanged();
        _homePage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
        _accountPage.MyTvivoLayoutChanged += (_, _) => _catalogLandingPage.InvalidateCatalogSnapshots();
        _accountPage.SignOutCompleted += AccountPage_SignOutCompleted;
        _accountPage.ChangeUserRequested += AccountPage_ChangeUserRequested;
        _accountPage.RefreshCatalogRequested += AccountPage_RefreshCatalogRequested;
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
        _catalogLandingPage.BrowseContextChanging += (_, _) => FlushPendingVisits();
        LaunchDiagnostics.Write("MainWindow constructor completed");
    }

    public void PrepareForActivation()
    {
        _windowState.RequestWindowMode(WindowStateController.WindowMode.Fullscreen);
        ApplyWindowChromeState();
    }

    private void WindowStateButton_Click(object sender, RoutedEventArgs args)
    {
        var target = _windowState.EffectiveMode == WindowStateController.WindowMode.Fullscreen
            ? WindowStateController.WindowMode.Windowed
            : WindowStateController.WindowMode.Fullscreen;
        _windowState.RequestWindowMode(target);
        ApplyWindowChromeState();
    }

    private void MinimizeWindowButton_Click(object sender, RoutedEventArgs args)
    {
        if (ShouldMinimizeBecomeWindowMini(_windowState.IsShiftDown)) EnterWindowMiniPlayer();
        else _windowState.Minimize();
    }

    private void CloseWindowButton_Click(object sender, RoutedEventArgs args) => Close();

    private void TitleBarDragRegion_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (args.GetCurrentPoint(TitleBarDragRegion).Properties.IsLeftButtonPressed)
            _windowState.BeginWindowDrag();
    }

    private void WindowRoot_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key == Windows.System.VirtualKey.Escape && SleepTimerFlyout.IsOpen)
        {
            SleepTimerFlyout.Hide();
            args.Handled = true;
            return;
        }

        if (args.Handled || IsTrackFlyoutOpen || VolumeFlyout.IsOpen)
            return;

        var focusedElement = FocusManager.GetFocusedElement(WindowRoot.XamlRoot);
        var editingText = focusedElement is TextBox or PasswordBox or NumberBox;
        var controlDown = (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Control) &
                           Windows.UI.Core.CoreVirtualKeyStates.Down) != 0;
        var shiftDown = (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Shift) &
                         Windows.UI.Core.CoreVirtualKeyStates.Down) != 0;
        if (args.Key == Windows.System.VirtualKey.T && controlDown && shiftDown && !editingText &&
            _currentPage == ShellPage.Player && _currentSource is not null &&
            _playerPresentation != PlayerPresentation.Compact && !_windowState.IsWindowMini)
        {
            PrepareSleepTimerFlyout();
            SleepTimerFlyout.ShowAt(SleepTimerButton);
            if (_isCinemaMode) ShowCinemaControls("sleep-timer-shortcut");
            args.Handled = true;
            return;
        }

        if (_isCinemaMode && _currentPage == ShellPage.Player)
            ShowCinemaControls();

        if (_windowState.IsWindowMini && args.Key == Windows.System.VirtualKey.Escape)
        {
            ExitWindowMiniPlayer(expandToPlayer: false);
            args.Handled = true;
            return;
        }

        if (args.Key == Windows.System.VirtualKey.F11)
        {
            WindowStateButton_Click(sender, new RoutedEventArgs());
            args.Handled = true;
        }
        else if (args.Key == Windows.System.VirtualKey.Escape &&
            _playerPresentation == PlayerPresentation.Compact && HasCompactKeyboardFocus())
        {
            CompactCloseButton_Click(this, new RoutedEventArgs());
            args.Handled = true;
        }
        else if (args.Key == Windows.System.VirtualKey.Escape && _currentPage == ShellPage.Player)
        {
            ReturnFromPlayer();
            args.Handled = true;
        }
        else if (_currentPage == ShellPage.Player && _currentSource is not null)
        {
            switch (args.Key)
            {
                case Windows.System.VirtualKey.Space:
                    if (!_sleepTimerEnded)
                        PauseButton_Click(sender, new RoutedEventArgs());
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.PageUp:
                    PlayAdjacentEpisode(-1);
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.PageDown:
                    PlayAdjacentEpisode(1);
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.Left:
                    SeekBy(-10_000);
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.Right:
                    SeekBy(10_000);
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.Up:
                    AdjustVolume(5);
                    args.Handled = true;
                    break;
                case Windows.System.VirtualKey.Down:
                    AdjustVolume(-5);
                    args.Handled = true;
                    break;
            }
        }
    }

    private void WindowRoot_PointerWheelInput(object sender, PointerRoutedEventArgs args)
    {
        if (args.Handled || _currentPage != ShellPage.Player || _currentSource is null) return;
        var delta = args.GetCurrentPoint(null).Properties.MouseWheelDelta;
        if (delta == 0) return;
        AdjustVolume(delta > 0 ? 5 : -5);
        args.Handled = true;
    }

    private void ApplyWindowChromeState()
    {
        var fullscreen = _windowState.Mode == WindowStateController.WindowMode.Fullscreen;
        var showChrome = !_isCinemaMode;
        WindowChrome.Visibility = showChrome ? Visibility.Visible : Visibility.Collapsed;
        WindowRoot.RowDefinitions[0].Height = new GridLength(showChrome ? 44 : 0);
        WindowStateButton.Content = fullscreen ? "\uE923" : "\uE922";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(WindowStateButton, fullscreen
            ? "Switch to windowed mode"
            : "Switch to fullscreen mode");
    }

    private void VideoView_Initialized(object? sender, InitializedEventArgs args)
    {
        if (sender is VideoView view)
            _vlcEngine.InitializeView(view, args);
    }

    private async void AccountPage_PlaybackEngineChanged(object? sender, string engineTag)
    {
        if (_currentPage is null)
            return;

        var selectedEngine = engineTag == "LibVLC" ? (IPlaybackEngine)_vlcEngine : _nativeEngine;
        if (ReferenceEquals(selectedEngine, _engine))
            return;

        StopHeldSeek(commit: true, released: false);
        StopCinemaCursorIdleTimer(showCursor: true);

        var restartCurrentPlayback = IsPlaybackUiActive;
        if (restartCurrentPlayback) SaveResumePosition(force: true);
        if (_playerPresentation == PlayerPresentation.Compact)
        {
            SetPlayerPresentation(PlayerPresentation.Full);
            ShowPage(ShellPage.Player);
        }
        try
        {
            await _playback.StopAsync();
        }
        catch (Exception exception)
        {
            // A failing stop must not leave the engine switch half-applied (stale visuals/buttons).
            LaunchDiagnostics.WriteException("Stopping playback before the engine switch failed", exception);
        }
        selectedEngine.IsMuted = _playbackMuteState.IsMuted;
        Volatile.Write(ref _engine, selectedEngine);
        // An explicit choice in settings replaces any per-item fallback decisions.
        _preferredEngine = selectedEngine;
        LaunchDiagnostics.SetAppContext(EngineLabel(_engine), _selectedTheme.ToString());
        _itemEngineOverride.Clear();
        _engineFallbackTried.Clear();
        SetTrackSelectingEngine(_engine);
        _playback = ReferenceEquals(_engine, _vlcEngine) ? _vlcPlayback : _nativePlayback;
        WritePlaybackEnginePreference(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native");
        ApplyPlaybackEngineVisuals();
        if (restartCurrentPlayback && _currentSource is not null)
            await StartPlaybackAsync(_currentSource);
    }

    private void ApplyPlaybackEngineVisuals()
    {
        var native = ReferenceEquals(_engine, _nativeEngine);
        SetPlaybackSurfaceVisibility(_currentPage == ShellPage.Player || _playerPresentation == PlayerPresentation.Compact);
        // Mini player (button + minimize-to-mini setting) is LibVLC-only; the button is hidden
        // under Native by ApplyPlayerLayout so it follows the engine on every layout pass.
        MiniPlayerButton.IsEnabled = true;
        _accountPage.SetMinimizeToMiniAvailable(!native);
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

    private void SetPlaybackSurfaceVisibility(bool visible, bool allowCurtain = false)
    {
        var showSurface = visible && (allowCurtain || PlayerVideoCurtain.Visibility != Visibility.Visible);
        var showVlc = showSurface && ReferenceEquals(_engine, _vlcEngine);
        VideoView.Visibility = showVlc ? Visibility.Visible : Visibility.Collapsed;
        NativePlayerElement.Visibility = showSurface && ReferenceEquals(_engine, _nativeEngine)
            ? Visibility.Visible
            : Visibility.Collapsed;
        TraceBackTransition($"playback-surface-state requestedVisible={visible} allowCurtain={allowCurtain} {VideoSurfaceState()}");
    }

    private void SetTrackSelectingEngine(IPlaybackEngine? engine)
    {
        var next = engine as ITrackSelectingEngine;
        if (ReferenceEquals(next, _trackSelectingEngine))
            return;

        if (_trackSelectingEngine is not null)
            _trackSelectingEngine.TracksChanged -= TrackSelectingEngine_TracksChanged;

        _trackSelectingEngine = next;
        _trackSnapshot = next?.GetTracks() ?? PlaybackTrackSnapshot.Unresolved;
        _defaultSubtitleAppliedForSession = false;
        if (next is not null)
            next.TracksChanged += TrackSelectingEngine_TracksChanged;
        RefreshAudioSubtitlesButton();
    }

    private void TrackSelectingEngine_TracksChanged(object? sender, EventArgs args)
    {
        if (Interlocked.Exchange(ref _trackRefreshQueued, 1) != 0)
            return;

        if (!WindowRoot.DispatcherQueue.TryEnqueue(() =>
            {
                using var perfScope = PerfScope.Begin("PlaybackTracksUiUpdate");
                Interlocked.Exchange(ref _trackRefreshQueued, 0);
                if (_trackSelectingEngine is not { } engine)
                {
                    _trackSnapshot = PlaybackTrackSnapshot.Unresolved;
                    RefreshAudioSubtitlesButton();
                    return;
                }

                var snapshot = engine.GetTracks();
                _trackSnapshot = snapshot;
                if (snapshot.Subtitles.Count > 0 && !_defaultSubtitleAppliedForSession)
                {
                    _defaultSubtitleAppliedForSession = true;
                    // Off/language persistence is deferred until the format-by-engine matrix exists.
                    engine.ApplyDefaultSubtitle(
                        preferredLanguage: null,
                        explicitOff: false,
                        uiCultureTwoLetterCode: CultureInfo.CurrentUICulture.TwoLetterISOLanguageName);
                }
                RefreshAudioSubtitlesButton();
            }))
        {
            Interlocked.Exchange(ref _trackRefreshQueued, 0);
        }
    }

    private void AudioSubtitlesFlyout_Opened(object? sender, object args)
    {
        if (_isCinemaMode) ShowCinemaControls();
        if (_trackSelectingEngine is { } engine)
            _trackSnapshot = engine.GetTracks();
        RefreshAudioSubtitlesButton();
    }

    private bool IsTrackFlyoutOpen => AudioSubtitlesFlyout.IsOpen || CompactAudioSubtitlesFlyout.IsOpen;

    private void CompactAudioSubtitlesFlyout_Closed(object? sender, object args)
    {
        if (_currentPage == ShellPage.Player && _playerPresentation == PlayerPresentation.Compact)
            CompactAudioSubtitlesButton.Focus(FocusState.Programmatic);
    }

    private void AudioSubtitlesFlyout_Closed(object? sender, object args)
    {
        if (_currentPage == ShellPage.Player)
        {
            var focusTarget = AudioSubtitlesButton.Visibility == Visibility.Visible
                ? AudioSubtitlesButton
                : PauseButton;
            focusTarget.Focus(FocusState.Programmatic);
        }
        if (_isCinemaMode) ScheduleCinemaIdleCheck();
    }

    private void RefreshAudioSubtitlesButton()
    {
        var hasTracks = _trackSnapshot.Audio.Count > 0 || _trackSnapshot.Subtitles.Count > 0;
        var isLiveWithoutTracks = _currentSource?.Kind == StreamKind.Live && !hasTracks;
        var inPlayer = _currentPage == ShellPage.Player && _currentSource is not null;
        AudioSubtitlesButton.Visibility = inPlayer && !isLiveWithoutTracks
            ? Visibility.Visible
            : Visibility.Collapsed;
        AudioSubtitlesButton.IsEnabled = inPlayer && hasTracks && _trackSelectingEngine is not null;
        var inCompact = _playerPresentation == PlayerPresentation.Compact && _currentSource is not null;
        CompactAudioSubtitlesButton.Visibility = inCompact && !isLiveWithoutTracks
            ? Visibility.Visible
            : Visibility.Collapsed;
        CompactAudioSubtitlesButton.IsEnabled = inCompact && hasTracks && _trackSelectingEngine is not null;
        if (AudioSubtitlesFlyout.IsOpen)
            RebuildAudioSubtitlesFlyout(AudioSubtitlesFlyout);
        if (CompactAudioSubtitlesFlyout.IsOpen)
            RebuildAudioSubtitlesFlyout(CompactAudioSubtitlesFlyout);
    }

    private void RebuildAudioSubtitlesFlyout(MenuFlyout flyout)
    {
        flyout.Items.Clear();
        var snapshot = _trackSnapshot;
        if (snapshot.Audio.Count == 0 && snapshot.Subtitles.Count == 0)
        {
            flyout.Items.Add(new MenuFlyoutItem
            {
                Text = "No alternate tracks",
                IsEnabled = false,
                Foreground = ThemeBrush("AppMutedTextBrush"),
            });
            return;
        }

        if (snapshot.Audio.Count > 0)
        {
            flyout.Items.Add(CreateTrackSectionHeader("Audio"));
            foreach (var track in snapshot.Audio)
            {
                var item = new RadioMenuFlyoutItem
                {
                    Text = track.DisplayName,
                    Tag = track.Key,
                    GroupName = "AudioTracks",
                    IsChecked = track.IsSelected,
                    Foreground = track.IsSelected
                        ? ThemeBrush("AppAccentBrush")
                        : ThemeBrush("AppTextBrush"),
                };
                Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, $"Audio: {track.DisplayName}");
                item.Click += AudioTrackMenuItem_Click;
                flyout.Items.Add(item);
            }
        }

        if (snapshot.Audio.Count > 0)
            flyout.Items.Add(new MenuFlyoutSeparator());

        flyout.Items.Add(CreateTrackSectionHeader("Subtitles"));
        var subtitleOff = new RadioMenuFlyoutItem
        {
            Text = "Off",
            GroupName = "SubtitleTracks",
            IsChecked = !snapshot.Subtitles.Any(track => track.IsSelected),
            Foreground = !snapshot.Subtitles.Any(track => track.IsSelected)
                ? ThemeBrush("AppAccentBrush")
                : ThemeBrush("AppTextBrush"),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(subtitleOff, "Subtitles off");
        subtitleOff.Click += SubtitleTrackMenuItem_Click;
        flyout.Items.Add(subtitleOff);
        foreach (var track in snapshot.Subtitles)
        {
            var item = new RadioMenuFlyoutItem
            {
                Text = track.DisplayName,
                Tag = track.Key,
                GroupName = "SubtitleTracks",
                IsChecked = track.IsSelected,
                Foreground = track.IsSelected
                    ? ThemeBrush("AppAccentBrush")
                    : ThemeBrush("AppTextBrush"),
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, $"Subtitle: {track.DisplayName}");
            item.Click += SubtitleTrackMenuItem_Click;
            flyout.Items.Add(item);
        }
    }

    private MenuFlyoutItem CreateTrackSectionHeader(string text) => new()
    {
        Text = text,
        IsEnabled = false,
        Foreground = ThemeBrush("AppAccentSoftBrush"),
    };

    private void AudioTrackMenuItem_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not RadioMenuFlyoutItem item || item.Tag is not string key || _trackSelectingEngine is not { } engine)
            return;
        if (engine.SelectAudio(key))
            item.IsChecked = true;
    }

    private void SubtitleTrackMenuItem_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not RadioMenuFlyoutItem item || _trackSelectingEngine is not { } engine)
            return;
        if (engine.SelectSubtitle(item.Tag as string))
            item.IsChecked = true;
    }

    private void ResetTrackSelectionForSession()
    {
        _defaultSubtitleAppliedForSession = false;
        _trackSnapshot = PlaybackTrackSnapshot.Unresolved;
        RefreshAudioSubtitlesButton();
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

    private static string GetPlaybackEnginePreferencePath() => TvivoDataPaths.For("playback-engine.txt");

    private static string GetPlaybackOptionsPath() => TvivoDataPaths.For("playback-options.json");

    private static string GetWindowMiniGeometryPath() => TvivoDataPaths.For("window-mini-geometry.json");

    private static WindowMiniGeometry? ReadWindowMiniGeometry()
    {
        try
        {
            var path = GetWindowMiniGeometryPath();
            return File.Exists(path)
                ? JsonSerializer.Deserialize<WindowMiniGeometry>(File.ReadAllText(path))
                : null;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Window mini geometry could not be read; using defaults", exception);
            return null;
        }
    }

    private static PlaybackModePreferences ReadPlaybackOptions()
    {
        try
        {
            var path = GetPlaybackOptionsPath();
            return File.Exists(path)
                ? PlaybackModePreferences.FromJson(File.ReadAllText(path))
                : PlaybackModePreferences.Defaults;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback options could not be read; using defaults", exception);
            return PlaybackModePreferences.Defaults;
        }
    }

    private void WritePlaybackOptions()
    {
        try
        {
            var path = GetPlaybackOptionsPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, new PlaybackModePreferences(
                _episodePlaybackMode, _moviePlaybackMode, _minimizeToMiniMode, _playbackAttempts).ToJson());
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Playback options could not be saved", exception);
        }
    }

    private void AccountPage_MinimizeToMiniModeChanged(object? sender, MinimizeToMiniMode mode)
    {
        _minimizeToMiniMode = mode;
        WritePlaybackOptions();
    }

    private void AccountPage_PlaybackAttemptsChanged(object? sender, int attempts)
    {
        _playbackAttempts = PlaybackRetryPolicy.ClampAttempts(attempts);
        WritePlaybackOptions();
    }

    private bool ShouldMinimizeBecomeWindowMini(bool shiftHeld = false) => PlayerPresentationPolicy.ShouldMinimizeBecomeMini(
        _minimizeToMiniMode,
        _engine.IsPlaying,
        _engine.IsPaused,
        _currentSource is not null,
        _engine.IsEnded,
        _currentSource?.Kind,
        ReferenceEquals(_engine, _vlcEngine) ? MiniPlayerEngineKind.LibVlc : MiniPlayerEngineKind.Native,
        _windowState.IsWindowMini,
        shiftHeld);

    private bool ShouldExplicitCinemaMiniButtonBecomeWindowMini() =>
        PlayerPresentationPolicy.ShouldEnterMiniFromCinemaButton(
            _currentSource is not null && (_engine.IsPlaying || _engine.IsPaused || _engine.IsBuffering),
            _currentSource?.Kind,
            ReferenceEquals(_engine, _vlcEngine) ? MiniPlayerEngineKind.LibVlc : MiniPlayerEngineKind.Native,
            _windowState.IsWindowMini,
            _engine.IsEnded);

    private void WindowState_WindowMiniGeometryChanged(WindowMiniGeometry geometry)
    {
        if (!_windowState.IsWindowMini) return;
        _windowMiniGeometry = geometry;
        _windowMiniGeometryDirty = _windowMiniGeometry != _windowMiniGeometryAtEntry;
    }

    private void SaveWindowMiniGeometryIfChanged()
    {
        if (!_windowMiniGeometryDirty) return;
        try
        {
            var path = GetWindowMiniGeometryPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporaryPath = path + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(_windowMiniGeometry));
            File.Move(temporaryPath, path, overwrite: true);
            _windowMiniGeometryDirty = false;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Window mini geometry could not be saved", exception);
        }
    }

    private void PlaybackModeToggle_Click(object sender, RoutedEventArgs args)
    {
        var clicked = sender == AutoplayNextToggle
            ? PlaybackMode.Next
            : sender == ShuffleToggle
                ? PlaybackMode.Shuffle
                : PlaybackMode.Off;
        // The toggles show the mode of the kind they were last configured for; edit that one,
        // not whatever _nowPlayingChannel happens to be mid-transition (null after a stop).
        var kind = _playlistOptionsKind ?? _nowPlayingChannel?.Source.Kind;
        var before = GetPlaybackMode(kind);
        var after = kind is StreamKind.Episode or StreamKind.Movie
            ? PlaybackModeTransitions.Next(before, clicked)
            : PlaybackMode.Off;

        SetPlaybackMode(kind, after);
        LaunchDiagnostics.Write($"event=playback-mode.click clicked={clicked} before={before} after={after} kind={kind?.ToString() ?? "unknown"} toggles=({AutoplayNextToggle.IsChecked == true},{ShuffleToggle.IsChecked == true})");
    }

    private void SetPlaybackMode(StreamKind? kind, PlaybackMode mode)
    {
        if (kind == StreamKind.Episode)
            _episodePlaybackMode = mode;
        else if (kind == StreamKind.Movie)
            _moviePlaybackMode = mode;

        SyncPlaybackModeToggles(kind is StreamKind.Episode or StreamKind.Movie ? mode : PlaybackMode.Off);
        if (kind is not (StreamKind.Episode or StreamKind.Movie)) return;

        _nextPlaybackSelector.Reset();
        ClearPreparedNextItem();
        WritePlaybackOptions();
        if (mode == PlaybackMode.Off)
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
        UpdateEpisodeNavigationButtons();
    }

    private PlaybackMode GetPlaybackMode(StreamKind? kind) => kind switch
    {
        StreamKind.Episode => _episodePlaybackMode,
        StreamKind.Movie => _moviePlaybackMode,
        _ => PlaybackMode.Off,
    };

    private void SyncPlaybackModeToggles(PlaybackMode mode)
    {
        AutoplayNextToggle.IsChecked = mode == PlaybackMode.Next;
        ShuffleToggle.IsChecked = mode == PlaybackMode.Shuffle;
    }

    private StreamKind? _playlistOptionsKind;

    private void UpdatePlaylistOptionControls(StreamKind kind)
    {
        _playlistOptionsKind = kind;
        var hasPlaylist = kind is StreamKind.Episode or StreamKind.Movie;
        PlayerPlaylistOptionsRow.Visibility = hasPlaylist ? Visibility.Visible : Visibility.Collapsed;
        var mode = GetPlaybackMode(kind);
        SyncPlaybackModeToggles(mode);
        LaunchDiagnostics.Write($"event=playback-mode.sync kind={kind} mode={mode}");
        if (!hasPlaylist)
        {
            _nextPlaybackSelector.Reset();
            ClearPreparedNextItem();
        }
    }

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

        _accountPage.ShowAccount(account, _catalogLandingPage.LastRefreshAt);
        ShowPage(ShellPage.Account);
    }

    private async void AccountPage_RefreshCatalogRequested(object? sender, EventArgs args)
    {
        var generation = Interlocked.Increment(ref _catalogRequestGeneration);
        _homePage.MarkDataChanged();
        ShowPage(ShellPage.Catalog, updateTopNavigation: false);
        await _catalogLandingPage.RefreshNowAsync();
        if (generation == Volatile.Read(ref _catalogRequestGeneration))
            UpdateTopNavigationState();
    }

    private void AccountPage_SignOutCompleted(object? sender, EventArgs args)
    {
        LaunchDiagnostics.ClearActiveSecrets();
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
        LaunchDiagnostics.ClearActiveSecrets();
        Interlocked.Increment(ref _catalogRequestGeneration);
        _catalogLandingPage.ClearAccountState();
        _providerSetupPage.ClearCredentials();
        ShowPage(ShellPage.Setup);
    }

    private void CatalogLandingPage_InteractionReadinessChanged(object? sender, EventArgs args) =>
        SetCatalogSearchEnabled(_currentPage == ShellPage.Catalog && _catalogLandingPage.IsReadyForInteraction);

    private void CatalogLandingPage_StartupPhaseCompleted(string phase)
    {
        if (!_startupPerfPhases.Add(phase)) return;
        var elapsedMs = Stopwatch.GetElapsedTime(_windowConstructionTimestamp).TotalMilliseconds;
        LaunchDiagnostics.Write($"event=perf.startup phase={phase} ms={elapsedMs:0}");
        if (phase == "authDone" && _catalogLandingPage.Connection is { } connection)
            LaunchDiagnostics.SetActiveSecrets(connection);
    }

    private async Task ShowCatalogAsync(CatalogLandingPage.CatalogMode mode)
    {
        var returningFromPlayer = _currentPage == ShellPage.Player;
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
            await _catalogLandingPage.LoadAsync(_catalogLandingPage.Account,
                reason: returningFromPlayer ? "player-return" : "navigation");
        if (_catalogLandingPage.Connection is { } activeConnection && _catalogLandingPage.Account is not null)
            LaunchDiagnostics.SetActiveSecrets(activeConnection);
        if (generation == Volatile.Read(ref _catalogRequestGeneration))
            UpdateTopNavigationState();
        TraceBackTransition($"show-catalog-complete generation={generation}");
    }

    private async void ProviderSetupPage_ConnectionSaved(object? sender, ProviderConnectedEventArgs args)
    {
        LaunchDiagnostics.SetActiveSecrets(args.Connection);
        _homePage.MarkDataChanged();
        Interlocked.Increment(ref _catalogRequestGeneration);
        ShowPage(ShellPage.Catalog, updateTopNavigation: false);
        await _catalogLandingPage.LoadAsync(args.Account, args.Connection, reason: "account-change");
        UpdateTopNavigationState();
    }

    private void CopyDiagnostics_Click(object sender, RoutedEventArgs args)
    {
        _nativeEngine.PublishPendingWarnings();
        _vlcEngine.PublishPendingWarnings();
        var package = new DataPackage();
        package.SetText(LaunchDiagnostics.ExportRecent());
        Clipboard.SetContent(package);
        // The time makes a second copy visibly new; the note is cleared after a few seconds and whenever
        // another title starts, so it never describes a different movie.
        CopyDiagnosticsStatus.Text = $"Diagnostics copied {DateTime.Now:HH:mm:ss}";
        var copyToken = ++_copyDiagnosticsNoteVersion;
        _ = ClearCopyNoteAsync(copyToken);
    }

    private int _copyDiagnosticsNoteVersion;

    private async Task ClearCopyNoteAsync(int version)
    {
        await Task.Delay(TimeSpan.FromSeconds(6));
        if (version == _copyDiagnosticsNoteVersion)
            CopyDiagnosticsStatus.Text = string.Empty;
    }

    private void ShowPage(ShellPage page, bool updateTopNavigation = true)
    {
        if (_currentPage == page)
            return;

        if (page == ShellPage.Home && _currentPage == ShellPage.Player)
            CancelSleepTimer("home");

        if (page != ShellPage.Player && _isCinemaMode)
            StopCinemaCursorIdleTimer(showCursor: true);

        if (page == ShellPage.Player && _playerPresentation == PlayerPresentation.Compact)
            SetPlayerPresentation(PlayerPresentation.Full);

        var shouldStopPlayback = _currentPage is { } currentPage && page != ShellPage.Player &&
            PlayerPresentationPolicy.ShouldStopPlaybackOnPageChange(
                _playerPresentation, ToPlayerPageKind(currentPage), ToPlayerPageKind(page));

        if (shouldStopPlayback)
        {
            CancelSleepTimer(page == ShellPage.Home ? "home" : "back");
            SetPlaybackIntent(PlaybackUserIntent.StoppedByUser, awaitForwardProgress: false);
            StopHeldSeek(commit: true, released: false);
        }

        var returningFromPlayer = page == ShellPage.Catalog && _currentPage == ShellPage.Player;

        if (shouldStopPlayback && !_backTransitionTraceActive)
            BeginBackTransitionTrace($"player-leave-navigation destination={page} cinema={_isCinemaMode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");

        TraceBackTransition($"show-page-enter from={_currentPage} to={page} cinema={_isCinemaMode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
        if (shouldStopPlayback)
        {
            FlushPendingVisits();
            TraceBackTransition("show-page-before-player-stop");
            StopPlaybackForNavigation();
            TraceBackTransition("show-page-after-player-stop");
        }

        _currentPage = page;
        if (page == ShellPage.Home)
            _ = _homePage.LoadAsync(_catalogLandingPage.Account, _catalogLandingPage.Connection, _catalogRepository);
        var isPlayer = page == ShellPage.Player;
        var playerVisible = PlayerPresentationPolicy.ShouldShowPlayerPage(
            _playerPresentation, ToPlayerPageKind(page));
        var keepCompactVisible = playerVisible && !isPlayer;
        if (isPlayer)
        {
            if (!PerfToggles.IsDisabled("playbacktimer"))
                _playbackUiTimer.Start();
        }
        var isCatalog = page == ShellPage.Catalog;
        var isStaticPage = page is ShellPage.Home or ShellPage.Setup or ShellPage.Account;
        TraceBackTransition($"show-page-visibility-begin page={page}");
        PageHost.Visibility = isStaticPage ? Visibility.Visible : Visibility.Collapsed;
        _homePage.Visibility = page == ShellPage.Home ? Visibility.Visible : Visibility.Collapsed;
        _providerSetupPage.Visibility = page == ShellPage.Setup ? Visibility.Visible : Visibility.Collapsed;
        _accountPage.Visibility = page == ShellPage.Account ? Visibility.Visible : Visibility.Collapsed;
        PlayerPage.Visibility = playerVisible ? Visibility.Visible : Visibility.Collapsed;
        TraceBackTransition($"show-page-player-visibility-set page={page} player={PlayerPage.Visibility}");
        SetPlaybackSurfaceVisibility(playerVisible);
        CompactChrome.Visibility = keepCompactVisible ? Visibility.Visible : Visibility.Collapsed;
        if (keepCompactVisible) ApplyCompactGeometryToLayout();
        else if (!isPlayer)
        {
            PlayerPage.Width = double.NaN;
            PlayerPage.Height = double.NaN;
            PlayerPage.HorizontalAlignment = HorizontalAlignment.Stretch;
            PlayerPage.VerticalAlignment = VerticalAlignment.Stretch;
            PlayerPage.Margin = new Thickness(0);
        }
        if (isPlayer && _nowPlayingChannel is { } selectedPlayerChannel)
            SetCurrentPlayerEntry(selectedPlayerChannel);
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        TraceBackTransition($"show-page-catalog-visibility-set page={page} catalog={CatalogPageArea.Visibility}");
        if (isStaticPage) FadeIn(PageHost);
        else if (isPlayer) FadeIn(PlayerPage);
        else if (isCatalog && !returningFromPlayer) FadeIn(CatalogPageArea);
        _catalogLandingPage.SetActive(isCatalog);
        TraceBackTransition($"show-page-visibility page={page} host={PageHost.Visibility} catalog={CatalogPageArea.Visibility} player={PlayerPage.Visibility} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility} cinema={_isCinemaMode}");
        CatalogSearchBar.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        SetCatalogSearchEnabled(isCatalog && _catalogLandingPage.IsReadyForInteraction);
        if (updateTopNavigation)
            UpdateTopNavigationState();

    }

    private void SetCatalogSearchEnabled(bool enabled)
    {
        TopSearchButton.IsEnabled = enabled;
        TopSearchBox.IsEnabled = enabled;
        TopSortButton.IsEnabled = enabled;
    }

    private void TopRefreshButton_Click(object sender, RoutedEventArgs args) =>
        _catalogLandingPage.RefreshCurrentType();

    private void ThemeToggleButton_Click(object sender, RoutedEventArgs args)
    {
        _selectedTheme = _selectedTheme == ThemePreference.Dark ? ThemePreference.Light : ThemePreference.Dark;
        ApplySelectedTheme();
        try
        {
            _themePreferenceStore.Write(_selectedTheme);
            WindowInfoBar.IsOpen = false;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteExceptionDetails("Saving theme preference failed", exception);
            WindowInfoBar.IsOpen = true;
        }
    }

    private void ApplySelectedTheme()
    {
        using var perfScope = PerfScope.Begin("WindowThemeApply");
        WindowRoot.RequestedTheme = _selectedTheme == ThemePreference.Light ? ElementTheme.Light : ElementTheme.Dark;
        LaunchDiagnostics.SetAppContext(EngineLabel(_engine), _selectedTheme.ToString());
        var isDark = _selectedTheme == ThemePreference.Dark;
        RefreshThemeAwareBrushes();
        ThemeToggleButton.Content = isDark ? "\uE706" : "\uE708";
        var actionName = isDark ? "Switch to light theme" : "Switch to dark theme";
        AutomationProperties.SetName(ThemeToggleButton, actionName);
        ToolTipService.SetToolTip(ThemeToggleButton, actionName);

        var darkCaptionValue = isDark ? 1 : 0;
        var darkModeResult = DwmSetWindowAttribute(WinRT.Interop.WindowNative.GetWindowHandle(this),
            DwmwaUseImmersiveDarkMode, ref darkCaptionValue, sizeof(int));
        if (darkModeResult < 0)
            LaunchDiagnostics.Write($"DWM theme caption attribute failed: 0x{darkModeResult:X8}");
    }

    private void WindowRoot_ActualThemeChanged(FrameworkElement sender, object args) => RefreshThemeAwareBrushes();

    private void RefreshThemeAwareBrushes()
    {
        using var perfScope = PerfScope.Begin("WindowThemeBrushRefresh");
        UpdateTopNavigationState();
        foreach (var entry in _playerEntries)
            entry.RefreshTheme(WindowRoot.ActualTheme);
        if (SeriesFavoriteButton.Visibility == Visibility.Visible)
            UpdateSeriesFavoriteButton(SeriesFavoriteButton.Content?.ToString() == "★");
        if (AudioSubtitlesFlyout.IsOpen) RebuildAudioSubtitlesFlyout(AudioSubtitlesFlyout);
        if (CompactAudioSubtitlesFlyout.IsOpen) RebuildAudioSubtitlesFlyout(CompactAudioSubtitlesFlyout);
        ApplyPlayerLayout();
    }

    private void CatalogLandingPage_RefreshStateChanged(object? sender, EventArgs args)
    {
        TopRefreshButton.Visibility = _catalogLandingPage.CanRefreshType ? Visibility.Visible : Visibility.Collapsed;
        TopRefreshButton.IsEnabled = !_catalogLandingPage.IsRefreshingType;
        ToolTipService.SetToolTip(TopRefreshButton, _catalogLandingPage.UpdatedText);
    }

    private void TopSortItem_Click(object sender, RoutedEventArgs args)
    {
        if (sender is RadioMenuFlyoutItem { Tag: string tag })
            _catalogLandingPage.SetCategorySort(tag);
    }

    private const double TopSearchExpandedWidth = 280;

    private void SetTopSearchExpanded(bool expanded, bool focus = false)
    {
        if (expanded == (TopSearchPill.Visibility == Visibility.Visible && TopSearchPill.Width > 0))
        {
            if (expanded && focus) TopSearchBox.Focus(FocusState.Programmatic);
            return;
        }
        if (expanded)
        {
            TopSearchButton.Visibility = Visibility.Collapsed;
            TopSearchPill.Visibility = Visibility.Visible;
        }
        var animation = new Microsoft.UI.Xaml.Media.Animation.DoubleAnimation
        {
            From = TopSearchPill.Width,
            To = expanded ? TopSearchExpandedWidth : 0,
            Duration = new Duration(TimeSpan.FromMilliseconds(180)),
            EnableDependentAnimation = true,
            EasingFunction = new Microsoft.UI.Xaml.Media.Animation.CubicEase { EasingMode = Microsoft.UI.Xaml.Media.Animation.EasingMode.EaseOut },
        };
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(animation, TopSearchPill);
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(animation, "Width");
        var storyboard = new Microsoft.UI.Xaml.Media.Animation.Storyboard();
        storyboard.Children.Add(animation);
        storyboard.Completed += (_, _) =>
        {
            if (expanded) return;
            TopSearchPill.Visibility = Visibility.Collapsed;
            TopSearchButton.Visibility = Visibility.Visible;
        };
        storyboard.Begin();
        if (expanded && focus) TopSearchBox.Focus(FocusState.Programmatic);
    }

    private void TopSearchButton_Click(object sender, RoutedEventArgs args) =>
        SetTopSearchExpanded(true, focus: true);

    // The pill has its own always-available ✕, so hide the text box's built-in one (shown only while focused).
    private void TopSearchBox_Loaded(object sender, RoutedEventArgs args)
    {
        TopSearchBox.ApplyTemplate();
        if (FindDescendantByName(TopSearchBox, "DeleteButton") is { } builtIn)
        {
            builtIn.Opacity = 0;
            builtIn.IsHitTestVisible = false;
            builtIn.Width = 0;
        }
    }

    private static FrameworkElement? FindDescendantByName(DependencyObject root, string name)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (child is FrameworkElement { } element && element.Name == name) return element;
            if (FindDescendantByName(child, name) is { } found) return found;
        }
        return null;
    }

    private void TopSearchCloseButton_Click(object sender, RoutedEventArgs args)
    {
        TopSearchBox.Text = string.Empty;
        SetTopSearchExpanded(false);
    }

    private void TopSearchBox_LostFocus(object sender, RoutedEventArgs args)
    {
        if (string.IsNullOrEmpty(TopSearchBox.Text))
            SetTopSearchExpanded(false);
    }

    private void TopSearchBox_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key != Windows.System.VirtualKey.Escape) return;
        TopSearchBox.Text = string.Empty;
        SetTopSearchExpanded(false);
        args.Handled = true;
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

    private void ContentSurfaceHost_Loaded(object sender, RoutedEventArgs args)
    {
        WindowRoot.XamlRoot.Changed += (_, _) =>
        {
            if (_compactGeometryLoaded) ClampAndApplyCompactGeometry();
        };
        if (_compactGeometryLoaded) return;
        _compactGeometryLoaded = true;
        try
        {
            var path = GetCompactGeometryPath();
            if (File.Exists(path))
                _compactGeometry = JsonSerializer.Deserialize<CompactPlayerGeometry>(File.ReadAllText(path));
            else
                _compactGeometryNeedsDefaultAnchor = true;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Compact player geometry could not be read; using defaults", exception);
            _compactGeometry = CompactPlayerGeometry.Default;
            _compactGeometryNeedsDefaultAnchor = true;
        }
        ClampAndApplyCompactGeometry();
    }

    private void WindowRoot_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        TraceBackTransition($"xaml-size-changed old={args.PreviousSize.Width:0.0}x{args.PreviousSize.Height:0.0} new={args.NewSize.Width:0.0}x{args.NewSize.Height:0.0}");
        if (_windowState.IsWindowMini && _windowState.GetWindowMiniInvalidStateReason() is { } invalidState)
            ForceExitWindowMini($"xaml-size-changed:{invalidState}");
        if (_compactGeometryLoaded) ClampAndApplyCompactGeometry();
    }

    private void ContentSurfaceHost_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        if (_compactGeometryLoaded) ClampAndApplyCompactGeometry();
    }

    private void ClampAndApplyCompactGeometry()
    {
        if (!_compactGeometry.TryClamp(ContentSurfaceHost.ActualWidth, ContentSurfaceHost.ActualHeight, out var displayed)) return;
        if (_compactGeometryNeedsDefaultAnchor)
        {
            _compactGeometry = _compactGeometry with
            {
                Left = Math.Max(0, ContentSurfaceHost.ActualWidth - displayed.Width - 24),
                Top = Math.Max(0, ContentSurfaceHost.ActualHeight - displayed.Height - 24),
            };
            _compactGeometryNeedsDefaultAnchor = false;
            if (!_compactGeometry.TryClamp(ContentSurfaceHost.ActualWidth, ContentSurfaceHost.ActualHeight, out displayed)) return;
        }
        _compactDisplayedGeometry = displayed;
        if (_playerPresentation == PlayerPresentation.Compact)
            ApplyCompactGeometryToLayout();
    }

    private void ApplyCompactGeometryToLayout()
    {
        var host = _windowState.IsWindowMini ? PlayerPresentationHost.WindowMini : PlayerPresentationHost.MainWindow;
        if (!PlayerPresentationPolicy.UsesInWindowCompactGeometry(host))
        {
            PlayerPage.Width = double.NaN;
            PlayerPage.Height = double.NaN;
            PlayerPage.HorizontalAlignment = HorizontalAlignment.Stretch;
            PlayerPage.VerticalAlignment = VerticalAlignment.Stretch;
            PlayerPage.Margin = new Thickness(0);
            return;
        }

        PlayerPage.Width = _compactDisplayedGeometry.Width;
        PlayerPage.Height = _compactDisplayedGeometry.Height;
        PlayerPage.HorizontalAlignment = HorizontalAlignment.Left;
        PlayerPage.VerticalAlignment = VerticalAlignment.Top;
        PlayerPage.Margin = new Thickness(_compactDisplayedGeometry.Left, _compactDisplayedGeometry.Top, 0, 0);
    }

    private static string GetCompactGeometryPath() => TvivoDataPaths.For("compact-player-geometry.json");

    private void SaveCompactGeometry()
    {
        try
        {
            var path = GetCompactGeometryPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporaryPath = path + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(_compactGeometry));
            File.Move(temporaryPath, path, overwrite: true);
            _compactGeometryDirty = false;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.WriteException("Compact player geometry could not be saved", exception);
        }
    }

    private void CompactChrome_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        var source = args.OriginalSource as DependencyObject;
        if (FindCompactResizeZone(source) is { } resizeZone)
        {
            if (!_windowState.IsWindowMini) BeginCompactGeometryGesture(sender, args, resizeZone.Edges);
        }
        else if (_windowState.IsWindowMini && IsVisualDescendantOf(source, CompactDragRegion) &&
                 !IsVisualDescendantOf(source, CompactExpandButton) &&
                 !IsVisualDescendantOf(source, CompactCloseButton) &&
                 !IsVisualDescendantOf(source, CompactMinimizeButton))
        {
            if (args.GetCurrentPoint(CompactChrome).Properties.IsLeftButtonPressed) _windowState.BeginWindowDrag();
        }
        else if (IsVisualDescendantOf(source, CompactTitleText))
        {
            BeginCompactGeometryGesture(sender, args, CompactResizeEdges.None);
        }
    }

    private static CompactResizeZone? FindCompactResizeZone(DependencyObject? source)
    {
        while (source is not null)
        {
            if (source is CompactResizeZone zone) return zone;
            source = VisualTreeHelper.GetParent(source);
        }
        return null;
    }

    private static bool IsVisualDescendantOf(DependencyObject? source, DependencyObject target)
    {
        while (source is not null)
        {
            if (ReferenceEquals(source, target)) return true;
            source = VisualTreeHelper.GetParent(source);
        }
        return false;
    }

    private void CompactChrome_PointerReleased(object sender, PointerRoutedEventArgs args) => CompactGeometry_PointerReleased(sender, args);

    private void CompactChrome_PointerCaptureLost(object sender, PointerRoutedEventArgs args) => CompactGeometry_PointerCaptureLost(sender, args);

    private void CompactVideoClickTarget_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (_playerPresentation != PlayerPresentation.Compact || sender is not UIElement target ||
            !args.GetCurrentPoint(target).Properties.IsLeftButtonPressed) return;
        _compactVideoPointerStart = args.GetCurrentPoint(target).Position;
        _compactVideoPointerId = args.Pointer.PointerId;
        _compactVideoPointerDragged = false;
        _compactVideoPointerActive = target.CapturePointer(args.Pointer);
        ShowCompactControls();
        args.Handled = _compactVideoPointerActive;
    }

    private void CompactVideoClickTarget_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_compactVideoPointerActive || args.Pointer.PointerId != _compactVideoPointerId || sender is not UIElement target) return;
        var point = args.GetCurrentPoint(target).Position;
        var dx = point.X - _compactVideoPointerStart.X;
        var dy = point.Y - _compactVideoPointerStart.Y;
        if (dx * dx + dy * dy > 4 * 4) _compactVideoPointerDragged = true;
    }

    private void CompactVideoClickTarget_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (!_compactVideoPointerActive || args.Pointer.PointerId != _compactVideoPointerId || sender is not UIElement target) return;
        var wasClick = !_compactVideoPointerDragged;
        var point = args.GetCurrentPoint(target).Position;
        _compactVideoPointerActive = false;
        target.ReleasePointerCapture(args.Pointer);
        if (wasClick) RegisterVideoClick(point, compact: true);
        ShowCompactControls();
        args.Handled = true;
    }

    private void CompactVideoClickTarget_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (args.Pointer.PointerId == _compactVideoPointerId) _compactVideoPointerActive = false;
    }

    private void BeginCompactGeometryGesture(object sender, PointerRoutedEventArgs args, CompactResizeEdges edges)
    {
        if (_playerPresentation != PlayerPresentation.Compact || sender is not UIElement element ||
            !_compactGeometry.TryClamp(ContentSurfaceHost.ActualWidth, ContentSurfaceHost.ActualHeight, out var displayed)) return;
        _compactResizeEdges = edges;
        _compactGeometryResize = edges != CompactResizeEdges.None;
        _compactGeometryStart = displayed;
        _compactDisplayedGeometry = displayed;
        _compactGeometryLogicalStart = _compactGeometry;
        _compactPointerStart = args.GetCurrentPoint(ContentSurfaceHost).Position;
        if (!element.CapturePointer(args.Pointer)) return;
        _compactGeometryPointerId = args.Pointer.PointerId;
        _compactGeometryGestureActive = true;
        _compactGeometryDirty = false;
        ShowCompactControls();
        args.Handled = true;
    }

    private void CompactGeometry_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (_playerPresentation != PlayerPresentation.Compact || !_compactGeometryGestureActive ||
            args.Pointer.PointerId != _compactGeometryPointerId || args.Pointer.IsInContact is false) return;
        var point = args.GetCurrentPoint(ContentSurfaceHost).Position;
        var deltaX = point.X - _compactPointerStart.X;
        var deltaY = point.Y - _compactPointerStart.Y;
        var candidate = _compactGeometryResize
            ? _compactGeometryStart.ResizeFromEdges(_compactResizeEdges, deltaX, deltaY,
                ContentSurfaceHost.ActualWidth, ContentSurfaceHost.ActualHeight)
            : _compactGeometryStart with { Left = _compactGeometryStart.Left + deltaX, Top = _compactGeometryStart.Top + deltaY };
        if (!candidate.TryClamp(ContentSurfaceHost.ActualWidth, ContentSurfaceHost.ActualHeight, out var clamped)) return;
        _compactGeometry = clamped;
        _compactDisplayedGeometry = clamped;
        _compactGeometryDirty = clamped != _compactGeometryStart;
        ApplyCompactGeometryToLayout();
        args.Handled = true;
    }

    private void CompactGeometry_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (!_compactGeometryGestureActive || args.Pointer.PointerId != _compactGeometryPointerId) return;
        _compactGeometryGestureActive = false;
        if (!_compactGeometryDirty)
            _compactGeometry = _compactGeometryLogicalStart;
        if (_compactGeometryDirty) SaveCompactGeometry();
        _compactGeometryDirty = false;
        if (sender is UIElement element) element.ReleasePointerCapture(args.Pointer);
        ShowCompactControls();
        args.Handled = true;
    }

    private void CompactGeometry_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (!_compactGeometryGestureActive || args.Pointer.PointerId != _compactGeometryPointerId) return;
        _compactGeometryGestureActive = false;
        _compactGeometry = _compactGeometryLogicalStart;
        _compactGeometryDirty = false;
        if (_playerPresentation == PlayerPresentation.Compact) ApplyCompactGeometryToLayout();
        ShowCompactControls();
    }

    private bool IsPlaybackUiActive => _currentSource is not null &&
        PlayerPresentationPolicy.CanCoexist(_playerPresentation, CurrentPageKind,
            _windowState.IsWindowMini ? PlayerPresentationHost.WindowMini : PlayerPresentationHost.MainWindow);

    private PlayerPageKind CurrentPageKind => _currentPage switch
    {
        ShellPage.Player => PlayerPageKind.Player,
        ShellPage.Catalog => PlayerPageKind.Catalog,
        _ => PlayerPageKind.Other,
    };

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
        AccountNavigation.Foreground = ThemeBrush(
            _currentPage is ShellPage.Setup or ShellPage.Account ? "AppAccentBrush" : "AppMutedTextBrush");
    }

    private void SetTopNavState(Button button, bool selected)
    {
        button.Foreground = selected ? ThemeBrush("AppTextBrush") : ThemeBrush("AppMutedTextBrush");
        button.BorderBrush = selected
            ? ThemeBrush("AppAccentBrush")
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    /// <summary>A brush from the dictionary of the theme this window is set to, for colours assigned in code.</summary>
    private Brush ThemeBrush(string key) => AppThemeBrushResolver.For(WindowRoot, key);

    private void TopSearchBox_TextChanged(object sender, TextChangedEventArgs args)
    {
        if (_suppressTopSearchChanged || !_catalogLandingPage.IsReadyForInteraction)
            return;
        _catalogLandingPage.SetSearchText(TopSearchBox.Text);
    }

    private void CatalogLandingPage_SearchTextChanged(string text)
    {
        _suppressTopSearchChanged = true;
        try
        {
            TopSearchBox.Text = text;
            if (!string.IsNullOrEmpty(text)) SetTopSearchExpanded(true);
            else if (TopSearchBox.FocusState == FocusState.Unfocused)
                SetTopSearchExpanded(false);
        }
        finally
        {
            _suppressTopSearchChanged = false;
        }
    }

    private async void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        var selectedChannel = args.Channel;
        if (selectedChannel.Source.Kind == StreamKind.Series &&
            _seriesOpeningId == selectedChannel.Id &&
            _seriesOpenCancellation is { IsCancellationRequested: false })
        {
            LogSeriesOpenExit(Stopwatch.GetTimestamp(), selectedChannel, "duplicate-in-flight");
            return;
        }

        _seriesOpenCancellation?.Cancel();
        _seriesOpeningId = null;
        if (_seriesOpenInfoVisible)
            ClearSeriesOpenMessage();

        if (IsPlaybackUiActive) SaveResumePosition(force: true);
        var selectionGeneration = Interlocked.Increment(ref _itemSelectionGeneration);
        var playerEntryTraceId = Interlocked.Increment(ref _playerEntryTraceId);
        Volatile.Write(ref _playerEntryTraceStartedAt, Stopwatch.GetTimestamp());
        LaunchDiagnostics.Write($"CLICK-AWAY-TRACE id={playerEntryTraceId} event=content-selected kind={selectedChannel.Source.Kind}");
        Interlocked.Increment(ref _metadataGeneration);

        if (selectedChannel.Source.Kind == StreamKind.Series)
        {
            LaunchDiagnostics.Write("event=content-selected outcome=opened-series kind=Series");
            await OpenSeriesAsync(selectedChannel, selectionGeneration);
            return;
        }

        if (selectedChannel.Source.Kind == StreamKind.Episode)
        {
            if (selectedChannel.Source.DirectUri is null && _catalogLandingPage.Connection is { } episodeConnection)
                selectedChannel = selectedChannel with { Source = selectedChannel.Source with
                {
                    DirectUri = new Uri(StreamUrlBuilder.Series(episodeConnection.Endpoint, episodeConnection.Username,
                        episodeConnection.Password, selectedChannel.Source.StreamId, selectedChannel.Source.ContainerExtension))
                } };
            if (!OpenSeriesEpisode(selectedChannel))
                ShowContentSelectionError(selectedChannel.Source.Kind, "unavailable");
            else
                LaunchDiagnostics.Write("event=content-selected outcome=started kind=Episode");
            return;
        }

        if (selectedChannel.Source.DirectUri is null)
        {
            var resolvedChannel = await _catalogLandingPage.ResolvePlayableChannelAsync(selectedChannel);
            if (resolvedChannel?.Source.DirectUri is null)
            {
                ShowContentSelectionError(args.Channel.Source.Kind, "unavailable");
                return;
            }
            selectedChannel = resolvedChannel;
        }
        LaunchDiagnostics.Write($"event=content-selected outcome=resolved kind={selectedChannel.Source.Kind}");

        _currentSeriesId = null;
        _currentSeriesTitle = null;
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        _seriesEpisodeProgress = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonWatchedCount.Visibility = Visibility.Collapsed;
        SeriesFavoriteButton.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        var type = selectedChannel.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live;
        if (_catalogLandingPage.Account is { } account)
        {
            QueueVisit(account, type, selectedChannel.Id);
        }
        _playerSiblings = selectedChannel.Source.Kind == StreamKind.Movie
            ? new[] { selectedChannel }
            : selectedChannel.Source.Kind == StreamKind.Live
                ? args.RelatedChannels.Where(channel => channel.Source.Kind == StreamKind.Live)
                    .Append(selectedChannel).DistinctBy(channel => channel.Id).ToArray()
                : Array.Empty<Channel>();
        _currentSource = selectedChannel.Source;
        _resumePositionOnStart = null;
        _nowPlayingChannel = selectedChannel;
        UpdatePlaylistOptionControls(selectedChannel.Source.Kind);
        PlayerTitleText.Text = selectedChannel.DisplayName;
        PlayerSideTitle.Text = selectedChannel.Source.Kind is StreamKind.Movie or StreamKind.Live
            ? "Loading category…"
            : "Now playing";
        PlayerSideSubtitle.Text = selectedChannel.Source.Kind switch
        {
            StreamKind.Movie => _playerSiblings.Count <= 1 ? "No other movies are available." : "Other movies in this category",
            StreamKind.Live => _playerSiblings.Count <= 1 ? "No other channels are available." : "Other channels in this shelf",
            _ => string.Empty,
        };
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        _playbackCompletionShown = false;
        SetPlayerList(_playerSiblings, selectedChannel);
        UpdateEpisodeNavigationButtons();
        ShowPage(ShellPage.Player);
        if (selectedChannel.Source.Kind == StreamKind.Movie)
            _ = LoadMovieCategorySiblingsAsync(selectedChannel, selectionGeneration);
        else if (selectedChannel.Source.Kind == StreamKind.Live)
            _ = LoadPlayerCategoryTitleAsync(selectedChannel, selectionGeneration, "Live TV");
        _ = LoadPlayerMetadataAsync(selectedChannel, type);
        LaunchDiagnostics.Write($"event=content-selected outcome=started kind={selectedChannel.Source.Kind}");
        _ = StartPlaybackAsync(selectedChannel.Source, entryKind: PlayerResetEntryKind.NewItem);
    }

    private void ShowContentSelectionError(StreamKind kind, string outcome)
    {
        var message = _catalogLandingPage.PlaybackUnavailableMessage;
        StatusText.Text = message;
        WindowInfoBar.Message = message;
        AutomationProperties.SetName(WindowInfoBar, message);
        WindowInfoBar.Severity = InfoBarSeverity.Error;
        WindowInfoBar.IsOpen = true;
        LaunchDiagnostics.Write($"event=content-selected outcome={outcome} kind={kind}");
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
        UpdateEpisodeNavigationButtons();
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
        var startedAt = Stopwatch.GetTimestamp();
        LaunchDiagnostics.Write("event=series.open.start ms=0");

        if (_catalogLandingPage.Account is not { } account)
        {
            ShowSeriesOpenMessage("Couldn't load episodes. Try again.", isFailure: true);
            LogSeriesOpenExit(startedAt, series, "no-account");
            return;
        }

        if (App.Services.GetService<ICatalogProvider>() is not ISeriesCatalogProvider provider)
        {
            ShowSeriesOpenMessage("Couldn't load episodes. Try again.", isFailure: true);
            LogSeriesOpenExit(startedAt, series, "provider-unsupported");
            return;
        }

        var selectionCts = new CancellationTokenSource();
        _seriesOpeningId = series.Id;
        _seriesOpenCancellation = selectionCts;
        ShowSeriesOpenMessage($"Opening {series.DisplayName}…");
        try
        {
            SeriesDetails details;
            using (var infoCts = CancellationTokenSource.CreateLinkedTokenSource(selectionCts.Token))
            {
                infoCts.CancelAfter(TimeSpan.FromSeconds(20));
                try
                {
                    details = await provider.GetSeriesInfoAsync(account, series.Id, infoCts.Token);
                }
                catch (Exception exception)
                {
                    if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration))
                    {
                        LogSeriesOpenExit(startedAt, series, "stale-generation");
                        return;
                    }

                    var timedOut = infoCts.IsCancellationRequested && !selectionCts.IsCancellationRequested;
                    var exceptionType = timedOut ? nameof(TimeoutException) : exception.GetType().Name;
                    ShowSeriesOpenMessage("Couldn't load episodes. Try again.", isFailure: true);
                    LogSeriesOpenExit(startedAt, series, "info-failed", $"exceptionType={exceptionType}");
                    return;
                }
            }

            var episodeCount = details.Seasons.Sum(season => season.Episodes.Count);
            LaunchDiagnostics.Write($"event=series.open.info ms={Stopwatch.GetElapsedTime(startedAt).TotalMilliseconds:0.000} seasons={details.Seasons.Count} episodes={episodeCount}");
            if (selectionGeneration != Volatile.Read(ref _itemSelectionGeneration))
            {
                LogSeriesOpenExit(startedAt, series, "stale-generation");
                return;
            }

            try
            {
                _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(account, series.Id);
                var selected = _catalogRepository.SelectResumeEpisode(account, details);
                if (selected is null)
                {
                    ShowSeriesOpenMessage("This series has no episodes available.", isFailure: true);
                    LogSeriesOpenExit(startedAt, series, "no-episodes");
                    return;
                }

                if (!string.Equals(_currentSeriesId, series.Id, StringComparison.Ordinal))
                {
                    _nextPlaybackSelector.Reset();
                    ClearPreparedNextItem();
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
                QueueVisit(account, CatalogItemType.Series, series.Id);
                if (!OpenSeriesEpisode(ToEpisodeChannel(account, series.Id, selected)))
                {
                    LogSeriesOpenExit(startedAt, series, "no-direct-uri");
                    return;
                }

                ClearSeriesOpenMessage();
                LogSeriesOpenExit(startedAt, series, "ok");
            }
            catch (Exception exception)
            {
                if (selectionGeneration == Volatile.Read(ref _itemSelectionGeneration))
                    ShowSeriesOpenMessage("Couldn't load episodes. Try again.", isFailure: true);
                LaunchDiagnostics.Write($"event=series.open.exception type={exception.GetType().Name}");
                LogSeriesOpenExit(startedAt, series, "exception");
            }
        }
        finally
        {
            if (ReferenceEquals(_seriesOpenCancellation, selectionCts))
            {
                _seriesOpenCancellation = null;
                _seriesOpeningId = null;
                if (!_seriesOpenMessageIsFailure)
                    ClearSeriesOpenMessage();
            }
            selectionCts.Dispose();
        }
    }

    private void ShowSeriesOpenMessage(string message, bool isFailure = false)
    {
        WindowInfoBar.Message = message;
        AutomationProperties.SetName(WindowInfoBar, message);
        WindowInfoBar.Severity = isFailure ? InfoBarSeverity.Error : InfoBarSeverity.Informational;
        WindowInfoBar.IsOpen = true;
        _seriesOpenInfoVisible = true;
        _seriesOpenMessageIsFailure = isFailure;
    }

    private void ClearSeriesOpenMessage()
    {
        WindowInfoBar.IsOpen = false;
        _seriesOpenInfoVisible = false;
        _seriesOpenMessageIsFailure = false;
    }

    private void ShowPlaybackRetryProgress(string message)
    {
        RetryPlaybackButton.Visibility = Visibility.Collapsed;
        SetPlaybackStatus(message);
    }

    private void SetPlaybackStatus(string message)
    {
        StatusText.Text = message;
        AutomationProperties.SetName(StatusText, message);
        PlaybackStatusPanel.Visibility = string.IsNullOrEmpty(message) && RetryPlaybackButton.Visibility != Visibility.Visible
            ? Visibility.Collapsed
            : Visibility.Visible;
    }

    private void ClearPlaybackStatus()
    {
        _terminalPlaybackFailure = false;
        RetryPlaybackButton.Visibility = Visibility.Collapsed;
        PauseButton.IsEnabled = true;
        SetPlaybackStatus(string.Empty);
    }

    private void ShowFinalPlaybackFailure(
        PlaybackAttemptResult reason,
        string status = "Couldn't start playback",
        long? expectedGeneration = null)
    {
        if (expectedGeneration is { } expected && expected != Volatile.Read(ref _playbackSessionGeneration))
            return;
        CancelSleepTimer("final-failure");
        _terminalPlaybackFailure = true;
        _playbackFailurePolicy.MarkFinalFailure(Volatile.Read(ref _playbackSessionGeneration));
        SetPlayerLoadingArtState(PlayerLoadingArtState.FinalFailure);
        SetPlaybackSurfaceVisibility(false);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        PauseButton.IsEnabled = false;
        SetPauseButtonState("Play");
        SetPlaybackStatus(status);
        RetryPlaybackButton.Visibility = Visibility.Visible;
        LaunchDiagnostics.Write($"event=playback.final-failure stay=true reason={reason}");
        LaunchDiagnostics.Write($"event=player.final-failure reveal={PlayerResetPolicy.ShouldRevealAfterFinalFailure().ToString().ToLowerInvariant()}");
        if (_isCinemaMode)
            ShowCinemaControls("playback-failure");
    }

    private void PlaybackFailureRetry_Click(object sender, RoutedEventArgs args)
    {
        if (_sleepTimerEnded || !_terminalPlaybackFailure || _currentSource is not { } source) return;
        var position = source.Kind == StreamKind.Live ? 0 : Math.Max(0, _lastGoodPlaybackPositionMilliseconds);
        _resumePositionOnStart = position;
        _engineFallbackTried.Remove(PlaybackItemKey(source));
        _ = StartPlaybackAsync(source, recoveryPositionMs: position, entryKind: PlayerResetEntryKind.Retry);
    }

    private void LogSeriesOpenExit(long startedAt, Channel series, string reason, string details = "")
    {
        var suffix = string.IsNullOrEmpty(details) ? string.Empty : $" {details}";
        var elapsed = Stopwatch.GetElapsedTime(startedAt).TotalMilliseconds;
        if (reason == "ok")
        {
            LaunchDiagnostics.Write($"event=series.open.exit ms={elapsed:0.000} reason=ok{suffix}");
            return;
        }

        var seriesTitle = SanitizePlaybackLabel(
            _currentSeriesId == series.Id ? _currentSeriesTitle ?? series.DisplayName : series.DisplayName);
        var itemRef = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{series.ProviderAccountId}\0{series.Id}")))[..8];
        LaunchDiagnostics.Write($"event=series.open.exit ms={elapsed:0.000} reason={reason}{suffix} title=\"{seriesTitle}\" itemRef={itemRef}");
    }

    private static Channel ToEpisodeChannel(ProviderAccount account, string seriesId, SeriesEpisode episode)
    {
        var displayName = $"S{episode.SeasonNumber:00} E{episode.EpisodeNumber:00} · {episode.Title}";
        return new Channel(account.AccountId, episode.Id, seriesId, episode.Title, displayName, null, null,
            episode.EpisodeNumber, episode.Source,
            new Dictionary<string, string>
            {
                ["seriesId"] = seriesId,
                ["seasonId"] = episode.SeasonId,
                ["seasonNumber"] = episode.SeasonNumber.ToString(CultureInfo.InvariantCulture),
            });
    }

    private bool OpenSeriesEpisode(Channel episode, bool preserveCompact = false, bool autoplayNext = false)
    {
        if (autoplayNext && ShouldStopBeforeStartingNextItem()) return false;
        var startedAt = Stopwatch.GetTimestamp();
        LaunchDiagnostics.Write("event=episode.open.start ms=0");
        if (episode.Source.DirectUri is null)
        {
            ShowSeriesOpenMessage("Couldn't load episodes. Try again.", isFailure: true);
            LogEpisodeOpenExit(startedAt, episode, "no-direct-uri");
            return false;
        }
        if (!autoplayNext) CancelEndOfCurrentItemTimerForManualChange(episode);

        try
        {
            if (IsPlaybackUiActive) SaveResumePosition(force: true);
            if (_currentPage != ShellPage.Player)
                _playerReturnPage = _currentPage ?? ShellPage.Catalog;
            _currentSeriesId ??= episode.Metadata.TryGetValue("seriesId", out var seriesId) ? seriesId : null;
            _currentSeriesAccount = _catalogLandingPage.Account;
            if (_currentSeriesId is not null && _currentSeriesAccount is not null)
            {
                _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(_currentSeriesAccount, _currentSeriesId);
                RefreshSeasonProgressCount();
                _seriesEpisodeProgress.TryGetValue(episode.Id, out var episodeProgress);
                _resumePositionOnStart = episodeProgress?.State == PlaybackProgressState.InProgress ? episodeProgress.ResumeMs : null;
                if (episodeProgress?.State != PlaybackProgressState.Finished)
                {
                    _catalogRepository.SaveProgress(_currentSeriesAccount, "episode", episode.Id, _currentSeriesId,
                        _resumePositionOnStart ?? 0, episodeProgress?.DurationMs, finished: false);
                    _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(_currentSeriesAccount, _currentSeriesId);
                    RefreshSeasonProgressCount();
                }
            }
            _nowPlayingChannel = episode;
            _currentSource = episode.Source;
            UpdatePlaylistOptionControls(StreamKind.Episode);
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
            _nextEpisodeAutoPlaySuppressed = false;
            _nextEpisodeAdvanceStarted = false;
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            UpdateEpisodeNavigationButtons();
            if (!preserveCompact) ShowPage(ShellPage.Player);
            _ = StartPlaybackAsync(episode.Source, preserveCompact,
                entryKind: autoplayNext ? PlayerResetEntryKind.AutoplayNext : PlayerResetEntryKind.NewItem);
            LaunchDiagnostics.Write($"event=episode.open.exit ms={Stopwatch.GetElapsedTime(startedAt).TotalMilliseconds:0.000} reason=ok");
            return true;
        }
        catch
        {
            LogEpisodeOpenExit(startedAt, episode, "exception");
            throw;
        }
    }

    private void LogEpisodeOpenExit(long startedAt, Channel episode, string reason)
    {
        var elapsed = Stopwatch.GetElapsedTime(startedAt).TotalMilliseconds;
        var seriesTitle = SanitizePlaybackLabel(_currentSeriesTitle ?? PlayerSideTitle.Text);
        var seriesId = episode.Metadata.GetValueOrDefault("seriesId", _currentSeriesId ?? string.Empty);
        var accountId = _currentSeriesAccount?.AccountId ?? episode.ProviderAccountId;
        var itemRef = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{accountId}\0{seriesId}")))[..8];
        LaunchDiagnostics.Write($"event=episode.open.exit ms={elapsed:0.000} reason={reason} title=\"{seriesTitle}\" itemRef={itemRef}");
    }

    private Channel? GetAdjacentEpisode(int offset)
    {
        if (_nowPlayingChannel?.Source.Kind != StreamKind.Episode || offset == 0) return null;
        var details = new SeriesDetails(_currentSeriesId ?? string.Empty, _currentSeriesTitle ?? string.Empty, _playerSeriesSeasons);
        var episodes = _playerSeriesSeasons
            .OrderBy(season => season.Number)
            .SelectMany(season => season.Episodes.OrderBy(episode => episode.EpisodeNumber))
            .ToArray();
        var index = Array.FindIndex(episodes, episode => episode.Id == _nowPlayingChannel.Id);
        var target = index + offset;
        if (index < 0 || target < 0 || target >= episodes.Length || _currentSeriesAccount is null || _currentSeriesId is null)
            return null;
        var resolved = offset > 0
            ? SeriesEpisodeResolver.Resolve(details, _nowPlayingChannel.Id, lastEpisodeFinished: true)
            : episodes[target];
        return resolved is null ? null : _playerSiblings.FirstOrDefault(channel => channel.Id == resolved.Id);
    }

    private void UpdateEpisodeNavigationButtons()
    {
        var kind = _nowPlayingChannel?.Source.Kind;
        var hasPlaylist = kind is StreamKind.Episode or StreamKind.Movie;
        PreviousEpisodeButton.Visibility = hasPlaylist ? Visibility.Visible : Visibility.Collapsed;
        NextEpisodeButton.Visibility = hasPlaylist ? Visibility.Visible : Visibility.Collapsed;
        CompactPreviousButton.Visibility = PreviousEpisodeButton.Visibility;
        CompactNextButton.Visibility = NextEpisodeButton.Visibility;
        var previous = kind == StreamKind.Episode
            ? GetAdjacentEpisode(-1)
            : GetAdjacentVisiblePlaylistItem(-1);
        PreviousEpisodeButton.IsEnabled = previous is not null;
        NextEpisodeButton.IsEnabled = GetNextPlaylistItem() is not null;
        CompactPreviousButton.IsEnabled = PreviousEpisodeButton.IsEnabled;
        CompactNextButton.IsEnabled = NextEpisodeButton.IsEnabled;
        var previousLabel = kind == StreamKind.Movie ? "Previous movie" : "Previous episode";
        var nextLabel = kind == StreamKind.Movie ? "Next movie" : "Next episode";
        ToolTipService.SetToolTip(PreviousEpisodeButton, previousLabel);
        ToolTipService.SetToolTip(NextEpisodeButton, nextLabel);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(PreviousEpisodeButton, previousLabel);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(NextEpisodeButton, nextLabel);
        ToolTipService.SetToolTip(CompactPreviousButton, previousLabel);
        ToolTipService.SetToolTip(CompactNextButton, nextLabel);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(CompactPreviousButton, previousLabel);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(CompactNextButton, nextLabel);
    }

    private void PlayAdjacentEpisode(int offset)
    {
        var kind = _nowPlayingChannel?.Source.Kind;
        var target = offset < 0
            ? kind == StreamKind.Episode ? GetAdjacentEpisode(-1) : GetAdjacentVisiblePlaylistItem(-1)
            : GetNextPlaylistItem();
        if (target is null) return;
        // Same as the auto-advance path: from the mini player (or any compact player) stay compact. Without
        // this, ShowPage(Player) pulled the whole window into the mini size and the new video had no surface.
        var preserveCompact = _playerPresentation == PlayerPresentation.Compact;
        if (target.Source.Kind == StreamKind.Episode)
            OpenSeriesEpisode(target, preserveCompact);
        else
            PlayRelatedChannel(target, preserveCompact);
    }

    private Channel? GetAdjacentVisiblePlaylistItem(int offset)
    {
        if (_nowPlayingChannel is not { } current || current.Source.Kind is not (StreamKind.Movie or StreamKind.Episode))
            return null;
        var entries = _playerEntries
            .Where(entry => entry.Channel.Source.Kind == current.Source.Kind)
            .Select(entry => entry.Channel)
            .ToArray();
        var index = Array.FindIndex(entries, channel => channel.Id == current.Id);
        var target = index + offset;
        return index >= 0 && target >= 0 && target < entries.Length ? entries[target] : null;
    }

    private Channel? GetNextPlaylistItem()
    {
        if (_nowPlayingChannel is not { } current) return null;
        var shuffle = PlaybackModeLogic.ShouldShuffle(GetPlaybackMode(current.Source.Kind));
        if (!shuffle)
            return current.Source.Kind == StreamKind.Episode
                ? GetAdjacentEpisode(1)
                : current.Source.Kind == StreamKind.Movie ? GetAdjacentVisiblePlaylistItem(1) : null;

        if (current.Source.Kind == StreamKind.Movie && !_playerMovieProgressReady)
            return null;
        if (_preparedNextForItemId == current.Id && _preparedNextWasShuffle)
            return _playerEntries.FirstOrDefault(entry => entry.Channel.Id == _preparedNextItemId)?.Channel;

        var entries = _playerEntries
            .Where(entry => entry.Channel.Source.Kind == current.Source.Kind)
            .ToArray();
        var ids = entries.Select(entry => entry.Channel.Id).ToArray();
        var finishedIds = entries.Where(entry => entry.IsFinished)
            .Select(entry => entry.Channel.Id).ToHashSet(StringComparer.Ordinal);
        _preparedNextForItemId = current.Id;
        _preparedNextWasShuffle = true;
        _preparedNextItemId = _nextPlaybackSelector.SelectNext(ids, current.Id, finishedIds, shuffle: true);
        return entries.FirstOrDefault(entry => entry.Channel.Id == _preparedNextItemId)?.Channel;
    }

    private void ClearPreparedNextItem()
    {
        _preparedNextItemId = null;
        _preparedNextForItemId = null;
        _preparedNextWasShuffle = false;
    }

    private void PreviousEpisode_Click(object sender, RoutedEventArgs args) => PlayAdjacentEpisode(-1);

    private void NextEpisode_Click(object sender, RoutedEventArgs args) => PlayAdjacentEpisode(1);

    private void NextEpisodePlayNow_Click(object sender, RoutedEventArgs args)
    {
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
        PlayAdjacentEpisode(1);
    }

    private void NextEpisodeCancel_Click(object sender, RoutedEventArgs args)
    {
        _nextEpisodeAutoPlaySuppressed = true;
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
    }

    private void SetPlayerList(IReadOnlyList<Channel> channels, Channel current)
    {
        var playlistIds = channels.Select(channel => channel.Id).ToArray();
        if (!_shufflePlaylistIds.SequenceEqual(playlistIds, StringComparer.Ordinal))
        {
            _shufflePlaylistIds = playlistIds;
            _nextPlaybackSelector.Reset();
            ClearPreparedNextItem();
        }
        _playerMovieProgressReady = !channels.Any(channel => channel.Source.Kind == StreamKind.Movie);
        if (!_playerMovieProgressReady)
            ClearPreparedNextItem();
        var movieProgressGeneration = Interlocked.Increment(ref _playerMovieProgressGeneration);
        var sameRows = _playerEntries.Count == channels.Count &&
            _playerEntries.Select((entry, index) => entry.Channel.Source.Kind == channels[index].Source.Kind && entry.Channel.Id == channels[index].Id).All(matches => matches);
        var scrollOffset = FindPlayerListScrollViewer()?.VerticalOffset;
        if (!sameRows)
        {
            _playerEntries.Clear();
            foreach (var channel in channels)
                _playerEntries.Add(new PlayerListEntry(channel, IsChannelFavorite(channel),
                    channel.Source.Kind == StreamKind.Episode && _seriesEpisodeProgress.TryGetValue(channel.Id, out var progress) ? progress : null,
                    WindowRoot.ActualTheme));
        }
        else
            foreach (var entry in _playerEntries)
            {
                entry.IsFavorite = IsChannelFavorite(entry.Channel);
                entry.SetProgress(entry.Channel.Source.Kind == StreamKind.Episode && _seriesEpisodeProgress.TryGetValue(entry.Channel.Id, out var progress) ? progress : null);
            }

        SetCurrentPlayerEntry(current);
        if (!sameRows && scrollOffset.HasValue)
            RestorePlayerListScrollOffset(scrollOffset.Value);
        _ = RefreshPlayerMovieProgressAsync(channels, movieProgressGeneration);
    }

    private async Task RefreshPlayerMovieProgressAsync(IReadOnlyList<Channel> channels, long generation)
    {
        var movieIds = channels.Where(channel => channel.Source.Kind == StreamKind.Movie)
            .Select(channel => channel.Id).Distinct(StringComparer.Ordinal).ToArray();
        if (movieIds.Length == 0)
        {
            _playerMovieProgressReady = true;
            return;
        }
        if (_catalogLandingPage.Account is not { } account)
        {
            _playerMovieProgressReady = true;
            UpdateEpisodeNavigationButtons();
            return;
        }
        var progress = await Task.Run(() => _catalogRepository.GetPlaybackProgress(account, "movie", movieIds));
        if (generation != Volatile.Read(ref _playerMovieProgressGeneration) ||
            _catalogLandingPage.Account?.AccountId != account.AccountId)
            return;
        foreach (var entry in _playerEntries.Where(entry => entry.Channel.Source.Kind == StreamKind.Movie))
            entry.SetProgress(progress.GetValueOrDefault(entry.Channel.Id));
        _playerMovieProgressReady = true;
        UpdateEpisodeNavigationButtons();
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
        _homePage.MarkDataChanged();
        _catalogLandingPage.NotifyFavoriteChanged();
    }

    private void SeriesFavorite_Click(object sender, RoutedEventArgs args)
    {
        if (_currentSeriesId is null || _currentSeriesAccount is null) return;
        var isFavorite = _catalogRepository.ToggleFavorite(_currentSeriesAccount, CatalogItemType.Series, _currentSeriesId);
        UpdateSeriesFavoriteButton(isFavorite);
        _homePage.MarkDataChanged();
        _catalogLandingPage.NotifyFavoriteChanged();
    }

    private void UpdateSeriesFavoriteButton(bool isFavorite)
    {
        SeriesFavoriteButton.Content = isFavorite ? "★" : "☆";
        SeriesFavoriteButton.Foreground = ThemeBrush(isFavorite ? "AppFavoriteBrush" : "AppMutedTextBrush");
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
            using var perfScope = PerfScope.Begin("PlayerListScrollRestore");
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
        PlayerSeasonLabel.Text = seasons.Count == 1 ? SeasonProgressLabel(seasons[0]) : string.Empty;
        PlayerSeasonLabel.Visibility = seasons.Count == 1 ? Visibility.Visible : Visibility.Collapsed;
        PlayerSeasonWatchedCount.Visibility = hasMultipleSeasons ? Visibility.Visible : Visibility.Collapsed;
        PlayerSeasonWatchedCount.Text = hasMultipleSeasons && PlayerSeasonComboBox.SelectedItem is SeriesSeason selected
            ? SeasonWatchedCount(selected) : string.Empty;
    }

    private void PlayerSeasonComboBox_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (PlayerSeasonComboBox.SelectedItem is not SeriesSeason season || _nowPlayingChannel is null) return;
        var account = _currentSeriesAccount ?? _catalogLandingPage.Account;
        if (account is null || _currentSeriesId is null) return;
        var episodes = season.Episodes.Select(episode => ToEpisodeChannel(account, _currentSeriesId, episode)).ToArray();
        SetPlayerList(episodes, _nowPlayingChannel);
        UpdateEpisodeNavigationButtons();
        PlayerSeasonWatchedCount.Text = SeasonWatchedCount(season);
        if (_playerSeriesSeasons.Count == 1) PlayerSeasonLabel.Text = SeasonProgressLabel(season);
    }

    private string SeasonProgressLabel(SeriesSeason season) => $"{season.Name} · {SeasonWatchedCount(season)}";

    private string SeasonWatchedCount(SeriesSeason season)
    {
        var watched = season.Episodes.Count(episode => _seriesEpisodeProgress.TryGetValue(episode.Id, out var progress) && progress.State == PlaybackProgressState.Finished);
        return $"{watched}/{season.Episodes.Count} watched";
    }

    private void RefreshSeasonProgressCount()
    {
        if (PlayerSeasonComboBox.SelectedItem is SeriesSeason season)
            PlayerSeasonWatchedCount.Text = SeasonWatchedCount(season);
        if (_playerSeriesSeasons.Count == 1 && _playerSeriesSeasons[0] is { } onlySeason)
            PlayerSeasonLabel.Text = SeasonProgressLabel(onlySeason);
    }

    // Visits feed the "most visited" ranking. Applying one immediately re-sorts the list the user
    // is about to return to (the played title jumps to the top and the scroll position is lost),
    // so visits are held until the browse context changes or the app closes.
    private readonly List<(ProviderAccount Account, CatalogItemType Type, string Id)> _pendingVisits = new();

    private void QueueVisit(ProviderAccount account, CatalogItemType type, string id) =>
        _pendingVisits.Add((account, type, id));

    private void FlushPendingVisits()
    {
        if (_pendingVisits.Count == 0) return;
        foreach (var visit in _pendingVisits)
            _catalogRepository.RecordVisit(visit.Account, visit.Type, visit.Id);
        _pendingVisits.Clear();
        _homePage.MarkDataChanged();
        _catalogLandingPage.NotifyVisitRecorded();
    }

    private (long Generation, CancellationTokenSource SessionCts, Task StopTask) ResetPlayerForNewPlayback(
        PlayerResetEntryKind reason,
        bool keepFrame = false)
    {
        var generation = Interlocked.Increment(ref _playbackSessionGeneration);
        var previousCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        previousCts?.Cancel();
        previousCts?.Dispose();
        var sessionCts = new CancellationTokenSource();
        Volatile.Write(ref _playbackSessionCts, sessionCts);

        if (keepFrame)
        {
            if (reason != PlayerResetEntryKind.SameMediaRecovery || PlayerResetPolicy.RequiresReset(reason))
                throw new ArgumentException("Only same-media recovery may keep the current frame.", nameof(keepFrame));
            SetPlayerLoadingArtState(PlayerLoadingArtState.KeepFrameRecovery);
            return (generation, sessionCts, Task.CompletedTask);
        }

        if (!PlayerResetPolicy.RequiresReset(reason))
            throw new ArgumentException("A reset requires a fresh playback entry.", nameof(reason));

        SetPlayerLoadingArtState(PlayerLoadingArtState.Loading);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        UpdatePlayerLoadingArtVisibility();
        SetPlaybackSurfaceVisibility(false);
        var surfaceCleared = VideoView.Visibility == Visibility.Collapsed &&
            NativePlayerElement.Visibility == Visibility.Collapsed &&
            PlayerVideoCurtain.Visibility == Visibility.Visible;
        LaunchDiagnostics.Write(
            $"event=player.reset reason={reason} generation={generation} surfaceCleared={surfaceCleared.ToString().ToLowerInvariant()}");
        _terminalPlaybackFailure = false;
        _playbackFailurePolicy.ClearFailure(generation);
        _resumeReady = false;
        _pendingResumePosition = null;
        _movieCompletionRecorded = false;
        _unverifiedPlaybackEndHandled = false;
        _playbackCompletionShown = false;
        _hasPlaybackAdvancedThisSession = false;
        _lastGoodPlaybackPositionMilliseconds = 0;
        _lastInterruptionObservedPosition = 0;
        _diagnosticLastPosition = 0;
        _diagnosticFirstProgressReported = false;
        _isDraggingProgress = false;
        _isUpdatingProgress = false;
        ClearPlaybackStatus();
        SetPlaybackStatus("Starting playback…");
        PauseButton.IsEnabled = true;
        SetPauseButtonState("Play");
        ElapsedText.Text = FormatTime(0);
        DurationText.Text = "—:—";
        CompactElapsedText.Text = FormatTime(0);
        CompactDurationText.Text = "—:—";
        _isUpdatingProgress = true;
        try
        {
            ProgressSlider.Minimum = 0;
            ProgressSlider.Maximum = 1;
            ProgressSlider.Value = 0;
            ProgressSlider.IsEnabled = false;
            CompactProgressSlider.Minimum = 0;
            CompactProgressSlider.Maximum = 1;
            CompactProgressSlider.Value = 0;
            CompactProgressSlider.IsEnabled = false;
        }
        finally
        {
            _isUpdatingProgress = false;
        }
        RewindButton.IsEnabled = false;
        ForwardButton.IsEnabled = false;
        CompactRewindButton.IsEnabled = false;
        CompactForwardButton.IsEnabled = false;

        var stopTask = QueueBothPlaybackEnginesStop(generation);
        _playbackStopTask = stopTask;
        return (generation, sessionCts, stopTask);
    }

    private void PlayerVideoCurtain_SizeChanged(object sender, SizeChangedEventArgs args) =>
        UpdatePlayerLoadingArtVisibility();

    private void SetPlayerLoadingArtState(PlayerLoadingArtState state)
    {
        _playerLoadingArtActive = PlayerResetPolicy.ShowsLoadingArt(state);
        UpdatePlayerLoadingArtVisibility();
    }

    private void UpdatePlayerLoadingArtVisibility() =>
        PlayerLoadingArt.Visibility = _playerLoadingArtActive && PlayerVideoCurtain.ActualHeight >= 300
            ? Visibility.Visible
            : Visibility.Collapsed;

    private FrameworkElement SelectedPlaybackHost => ReferenceEquals(_engine, _vlcEngine)
        ? VideoView
        : NativePlayerElement;

    private string SelectedPlaybackHostSize() =>
        $"{SelectedPlaybackHost.ActualWidth:0.0}x{SelectedPlaybackHost.ActualHeight:0.0}";

    private async Task<bool> EnsureSelectedPlaybackHostReadyAsync(CancellationToken cancellationToken)
    {
        // Poll instead of waiting for Loaded/SizeChanged: those events do not fire again when a host that
        // was already loaded is merely re-shown, which left the wait hanging until its budget expired.
        var host = SelectedPlaybackHost;
        try
        {
            while (true)
            {
                PlayerPage.UpdateLayout();
                host.UpdateLayout();
                if (host.Visibility == Visibility.Visible && host.IsLoaded &&
                    host.ActualWidth > 0 && host.ActualHeight > 0)
                    return true;
                await Task.Delay(50, cancellationToken);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return false;
        }
    }

    private void LogPlaybackHostNotReady(long generation)
    {
        var host = SelectedPlaybackHost;
        LaunchDiagnostics.Write(
            $"event=playback.host-not-ready generation={generation} visibility={host.Visibility} loaded={host.IsLoaded.ToString().ToLowerInvariant()} size={SelectedPlaybackHostSize()} playerPage={PlayerPage.Visibility} curtain={PlayerVideoCurtain.Visibility}");
    }

    private async Task<PlaybackAttemptResult> OpenPlaybackWithTimeoutAsync(
        StreamSource source,
        CancellationToken sessionToken)
    {
        using var openCts = CancellationTokenSource.CreateLinkedTokenSource(sessionToken);
        openCts.CancelAfter(TimeSpan.FromSeconds(10));
        return await _playback.PlayAsync(source, openCts.Token);
    }

    private Task<bool> QueueBothPlaybackEnginesStop(long? onlyIfCurrentGeneration = null) =>
        _playbackHandoff.RunAsync(
            _ => OnUiAsync(async () =>
            {
                if (onlyIfCurrentGeneration is null ||
                    onlyIfCurrentGeneration == Volatile.Read(ref _playbackSessionGeneration))
                    await StopBothPlaybackEnginesAsync();
                return true;
            }),
            _ => Task.FromResult(true),
            CancellationToken.None);

    private async Task StartPlaybackAsync(
        StreamSource source,
        bool preserveCompact = false,
        bool isRecovery = false,
        long? recoveryPositionMs = null,
        bool engineFallbackAttempt = false,
        PlayerResetEntryKind entryKind = PlayerResetEntryKind.FreshStart)
    {
        if ((isRecovery || entryKind == PlayerResetEntryKind.AutoplayNext) && ShouldStopBeforeStartingNextItem())
            return;
        if (_sleepTimerEnded && !isRecovery && entryKind is not (PlayerResetEntryKind.Retry or PlayerResetEntryKind.AutoplayNext))
        {
            _sleepTimerEnded = false;
            _sleepTimerStopStarted = false;
            RaiseSleepTimerStateChanged();
        }
        var resetKind = isRecovery
            ? PlayerResetEntryKind.SameMediaRecovery
            : engineFallbackAttempt ? PlayerResetEntryKind.EngineFallback : entryKind;
        var playbackStart = ResetPlayerForNewPlayback(resetKind, keepFrame: isRecovery);
        var generation = playbackStart.Generation;
        var sessionCts = playbackStart.SessionCts;
        if (!isRecovery)
            _interruptionBudget.Reset();
        if (!isRecovery)
            SetPlaybackIntent(PlaybackUserIntent.Playing, awaitForwardProgress: false);
        if (!isRecovery)
        {
            ClearPlaybackStatus();
            SetPlaybackStatus("Starting playback…");
        }
        var retryClock = new Stopwatch();
        var itemKey = PlaybackItemKey(source);
        var resumeOnStartForFallback = _resumePositionOnStart;
        if (!engineFallbackAttempt)
        {
            var wantedEngine = _itemEngineOverride.TryGetValue(itemKey, out var overrideEngine) ? overrideEngine : _preferredEngine;
            if (!ReferenceEquals(wantedEngine, _engine))
                SwitchEngineForPlayback(wantedEngine);
        }
        if (!isRecovery)
            SetPlaybackIntent(PlaybackUserIntent.Playing, awaitForwardProgress: false);
        StopHeldSeek(commit: true, released: false);
        _engine.IsMuted = _playbackMuteState.IsMuted;
        var resumePosition = source.Kind switch
        {
            StreamKind.Movie when _catalogLandingPage.Account is { } account && _nowPlayingChannel is { } movie =>
                _resumePositionOnStart ?? _catalogRepository.GetResumePosition(account, CatalogItemType.Movie, movie.Id),
            StreamKind.Episode => _resumePositionOnStart ??
                (_currentSeriesAccount is { } seriesAccount && _nowPlayingChannel is { } playingEpisode
                    ? _catalogRepository.GetEpisodeResumePosition(seriesAccount, playingEpisode.Id) : 0),
            _ => 0,
        };
        _resumePositionOnStart = null;
        _movieCompletionRecorded = false;
        _pendingResumePosition = null;
        _resumeReady = false;
        _unverifiedPlaybackEndHandled = false;
        var requestedStartPosition = Math.Max(0, isRecovery
            ? recoveryPositionMs ?? resumePosition
            : resumePosition);
        _lastGoodPlaybackPositionMilliseconds = isRecovery
            ? Math.Max(_lastGoodPlaybackPositionMilliseconds, requestedStartPosition)
            : requestedStartPosition;
        _lastForwardProgressAt = Stopwatch.GetElapsedTime(_stallClockStart);
        _lastInterruptionObservedPosition = requestedStartPosition;
        _hasPlaybackAdvancedThisSession = false;
        // LibVLC can open straight at the resume point; the player then skips the seek-after-first-frame.
        var openAtResume = ReferenceEquals(_engine, _vlcEngine) && source.Kind != StreamKind.Live &&
            requestedStartPosition > 5_000;
        var playSource = openAtResume ? source with { StartPositionMilliseconds = requestedStartPosition } : source;
        _copyDiagnosticsNoteVersion++;
        CopyDiagnosticsStatus.Text = string.Empty;
        _diagnosticOpenStartedAt = Stopwatch.GetTimestamp();
        _diagnosticLastPosition = 0;
        _diagnosticFirstProgressReported = false;
        var playerEntryTraceId = Volatile.Read(ref _playerEntryTraceId);
        _currentSource = source;
        ResetTrackSelectionForSession();
        ClearPlayerNowNext();
        _playbackCompletionShown = false;
        if (_nowPlayingChannel is { } selectedChannel)
        {
            PlayerTitleText.Text = selectedChannel.DisplayName;
            CompactTitleText.Text = selectedChannel.DisplayName;
        }
        if (!preserveCompact) ShowPage(ShellPage.Player);
        if (source.Kind == StreamKind.Live)
        {
            StartPlayerEpgUpdates();
            _ = RefreshPlayerNowNextAsync();
        }
        SetPlaybackStatus(isRecovery ? "Reconnecting…" : "Starting playback…");
        var engineName = ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native";
        var targetChannel = _nowPlayingChannel;
        string? category = null;
        if (targetChannel is not null)
        {
            try { category = await _catalogLandingPage.GetCategoryNameForAsync(targetChannel); }
            catch { /* Category labels are optional diagnostics; playback must still start. */ }
        }
        if (generation != Volatile.Read(ref _playbackSessionGeneration))
            return;

        var titleSource = source.Kind == StreamKind.Episode
            ? targetChannel?.Name
            : targetChannel?.DisplayName;
        var title = SanitizePlaybackLabel(titleSource ?? PlayerTitleText.Text);
        var categoryLabel = SanitizePlaybackLabel(category ?? string.Empty);
        var seriesLabel = source.Kind == StreamKind.Episode
            ? SanitizePlaybackLabel(_currentSeriesTitle ?? string.Empty)
            : string.Empty;
        var seasonValue = targetChannel?.Metadata.GetValueOrDefault("seasonNumber");
        var season = int.TryParse(seasonValue, NumberStyles.None, CultureInfo.InvariantCulture, out var seasonNumber)
            ? seasonNumber.ToString(CultureInfo.InvariantCulture)
            : "unknown";
        var episode = source.Kind == StreamKind.Episode && targetChannel?.SortOrder is { } episodeNumber
            ? episodeNumber.ToString(CultureInfo.InvariantCulture)
            : "unknown";
        var itemRef = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{targetChannel?.ProviderAccountId ?? string.Empty}\0{source.StreamId}")))[..8];
        var extension = Regex.Replace(source.ContainerExtension ?? string.Empty, @"[^a-zA-Z0-9.+_-]", string.Empty)
            .ToLowerInvariant();
        var kind = source.Kind == StreamKind.Live ? "Live" : source.Kind == StreamKind.Episode ? "Episode" : "Movie";
        _pendingPlaybackTargetGeneration = generation;
        _pendingPlaybackTargetLine = $"event=playback.target kind={kind} title=\"{title}\" ext={extension} category=\"{categoryLabel}\" itemRef={itemRef} series=\"{seriesLabel}\" season={season} episode={episode} engine={engineName}";
        LaunchDiagnostics.Write($"event=playback.start.ref kind={kind} ext={extension} engine={engineName} itemRef={itemRef}");
        LaunchDiagnostics.Write($"event=playback.attempt generation={generation} engine={engineName} kind={source.Kind} extensionPresent={(string.IsNullOrWhiteSpace(source.ContainerExtension) ? "no" : "yes")} resume={(resumePosition > 0 || isRecovery && recoveryPositionMs > 0 ? "yes" : "no")} httpStatus=unknown redirect=unknown");
        PlaybackAttemptResult result;
        var attemptCount = 0;
        var sawNotFound = false;
        try
        {
            await playbackStart.StopTask;
            if (!IsCurrentPlaybackStart(generation, sessionCts))
                return;
            retryClock.Start();
            while (true)
            {
                if (isRecovery && !_interruptionBudget.TryConsume(_playbackAttempts))
                {
                    result = PlaybackAttemptResult.Timeout;
                    break;
                }
                attemptCount++;
                // Stop both backends before opening a replacement. Each backend owns
                // native/network state independently, so stopping only the selected
                // PlaybackService can leave the previous provider connection alive.
                var handoffStartedAt = Stopwatch.GetTimestamp();
                if (sawNotFound && retryClock.Elapsed >= PlaybackRetryPolicy.MaximumNotFoundHardElapsed)
                {
                    result = PlaybackAttemptResult.NotFound;
                    break;
                }
                PlaybackAttemptResult? preOpenFailure = null;
                try
                {
                    result = await _playbackHandoff.RunAsync(
                        _ => OnUiAsync(async () =>
                        {
                            // Time spent queued behind an earlier start/stop (the handoff gate is not cancellable
                            // while stopping) shows up here as a large gateWaitMs.
                            LaunchDiagnostics.Write($"event=playback.handoff stage=gate-acquired generation={generation} gateWaitMs={Stopwatch.GetElapsedTime(handoffStartedAt).TotalMilliseconds:0}");
                            if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                            var stopStartedAt = Stopwatch.GetTimestamp();
                            await StopBothPlaybackEnginesAsync();
                            LaunchDiagnostics.Write(
                                $"event=playback.handoff-stopped generation={generation} stopMs={Stopwatch.GetElapsedTime(stopStartedAt).TotalMilliseconds:0} hostVisible={SelectedPlaybackHost.Visibility} hostLoaded={SelectedPlaybackHost.IsLoaded.ToString().ToLowerInvariant()} hostSize={SelectedPlaybackHostSize()} playerPage={PlayerPage.Visibility}");
                            if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                            // Keep the opaque curtain over the selected host while LibVLC
                            // initializes its swap-chain device.
                            SetPlaybackSurfaceVisibility(true, allowCurtain: true);
                            PlayerPage.UpdateLayout();
                            SelectedPlaybackHost.UpdateLayout();
                            using var hostWaitCts = CancellationTokenSource.CreateLinkedTokenSource(sessionCts.Token);
                            hostWaitCts.CancelAfter(TimeSpan.FromSeconds(3));
                            var hostReady = await EnsureSelectedPlaybackHostReadyAsync(hostWaitCts.Token);
                            if (!hostReady)
                            {
                                if (sessionCts.IsCancellationRequested) return;
                                // Best effort: the host is visible under the curtain, so let the engine try
                                // rather than failing the attempt on a readiness probe alone.
                                LogPlaybackHostNotReady(generation);
                            }
                            if (!IsCurrentPlaybackStart(generation, sessionCts)) return;
                            LaunchDiagnostics.Write(
                                $"event=player.host-ready generation={generation} visibility={SelectedPlaybackHost.Visibility} loaded={SelectedPlaybackHost.IsLoaded.ToString().ToLowerInvariant()} size={SelectedPlaybackHostSize()} playerPage={PlayerPage.Visibility} curtain={PlayerVideoCurtain.Visibility}");
                        }),
                        token => OnUiAsync(() =>
                        {
                            if (generation != Volatile.Read(ref _playbackSessionGeneration) || sessionCts.IsCancellationRequested)
                                return Task.FromResult(PlaybackAttemptResult.Cancelled);
                            if (preOpenFailure is { } failure)
                                return Task.FromResult(failure);
                            LaunchDiagnostics.Write($"event=playback.handoff stage=engine-open generation={generation} sinceClickMs={Stopwatch.GetElapsedTime(handoffStartedAt).TotalMilliseconds:0}");
                            _diagnosticOpenStartedAt = Stopwatch.GetTimestamp();
                            return OpenPlaybackWithTimeoutAsync(playSource, sessionCts.Token);
                        }),
                        sessionCts.Token);
                }
                catch (OperationCanceledException) when (sessionCts.IsCancellationRequested)
                {
                    LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason=user-cancelled");
                    return;
                }
                catch (OperationCanceledException)
                {
                    // Per-open cancellation is a timeout for this attempt. Keep it inside
                    // the loop so ShouldRetry and the normal retry diagnostic still run.
                    result = PlaybackAttemptResult.Timeout;
                }
                if (result != PlaybackAttemptResult.HostNotReady)
                    result = PlaybackFailureClassifier.RefineHttpStatus(result, _engine.HttpStatus);
                sawNotFound |= result == PlaybackAttemptResult.NotFound;
                var canRetry = isRecovery
                    ? _interruptionBudget.AttemptsUsed < PlaybackRetryPolicy.ClampAttempts(_playbackAttempts) &&
                      result is PlaybackAttemptResult.NetworkFailure or PlaybackAttemptResult.Timeout or PlaybackAttemptResult.UnknownFailure
                    : PlaybackRetryPolicy.ShouldRetry(result, attemptCount, _playbackAttempts, retryClock.Elapsed);
                if (!canRetry) break;

                var delay = isRecovery
                    ? PlaybackRetryPolicy.InterruptionDelay(_interruptionBudget.AttemptsUsed)
                    : PlaybackRetryPolicy.Delay(result, attemptCount);
                if (isRecovery)
                {
                    LaunchDiagnostics.Write($"event=playback.resume attempt={_interruptionBudget.AttemptsUsed + 1} of {_playbackAttempts} positionMs={_lastGoodPlaybackPositionMilliseconds}");
                    ShowPlaybackRetryProgress($"Reconnecting ({_interruptionBudget.AttemptsUsed + 1} of {_playbackAttempts})…");
                }
                else
                {
                    LaunchDiagnostics.Write($"event=playback.retry attempt={attemptCount + 1} of {_playbackAttempts} reason={result} delayMs={delay.TotalMilliseconds:0}");
                    ShowPlaybackRetryProgress($"Retrying ({attemptCount + 1} of {_playbackAttempts})…");
                }
                await Task.Delay(delay, sessionCts.Token);
                if (!IsCurrentPlaybackStart(generation, sessionCts))
                    return;
            }
        }
        catch (OperationCanceledException) when (sessionCts.IsCancellationRequested)
        {
            LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason=user-cancelled");
            return;
        }
        catch (OperationCanceledException)
        {
            result = sawNotFound ? PlaybackAttemptResult.NotFound : PlaybackAttemptResult.Timeout;
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Playback start failed: {exception.GetType().Name} (HRESULT 0x{exception.HResult:X8})");
            result = PlaybackFailureClassifier.FromOpenException(exception);
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
        if (result != PlaybackAttemptResult.FirstFrame && result != PlaybackAttemptResult.Cancelled)
        {
            try
            {
                await QueueBothPlaybackEnginesStop(generation);
            }
            catch (Exception exception)
            {
                LaunchDiagnostics.WriteException("Stopping both playback engines after startup failure failed", exception);
            }
            if (!IsCurrentPlaybackStart(generation, sessionCts))
                return;
        }
        LaunchDiagnostics.Write($"Playback result: engine={engineName}, kind={source.Kind}, result={result}");
        if (result == PlaybackAttemptResult.FirstFrame && sawNotFound)
            LaunchDiagnostics.Write($"event=playback.recovered attempts={attemptCount}");
        if (result is PlaybackAttemptResult.UnknownFailure or PlaybackAttemptResult.DecodeFailure or
            PlaybackAttemptResult.NetworkFailure or PlaybackAttemptResult.UnsupportedMedia or
            PlaybackAttemptResult.HostFailure or PlaybackAttemptResult.Timeout or PlaybackAttemptResult.Forbidden or
            PlaybackAttemptResult.NotFound)
        {
            if (!isRecovery)
                WritePendingPlaybackTarget(generation);
        }
        if (result != PlaybackAttemptResult.FirstFrame)
            LaunchDiagnostics.Write($"event=playback.stop engine={engineName} kind={source.Kind} reason={result switch { PlaybackAttemptResult.Cancelled => "user-cancelled", PlaybackAttemptResult.Timeout => "timeout", PlaybackAttemptResult.Forbidden => "forbidden", _ => "error" }}");
        if (result == PlaybackAttemptResult.NotFound)
            LaunchDiagnostics.Write("event=playback.notfound");
        if (!engineFallbackAttempt &&
            (!isRecovery || _interruptionBudget.AttemptsUsed < PlaybackRetryPolicy.ClampAttempts(_playbackAttempts)) &&
            EngineFallbackPolicy.ShouldFallback(result, _engineFallbackTried.Contains(itemKey)))
        {
            // The selected engine exhausted its attempts; the other player may handle the format or connection.
            _engineFallbackTried.Add(itemKey);
            var otherEngine = ReferenceEquals(_engine, _vlcEngine) ? (IPlaybackEngine)_nativeEngine : _vlcEngine;
            if (!isRecovery)
                WritePendingPlaybackTarget(generation);
            LaunchDiagnostics.Write($"event=playback.fallback from={engineName} to={EngineLabel(otherEngine)} reason={result}");
            SetPlaybackStatus("Trying the other player…");
            SwitchEngineForPlayback(otherEngine);
            _resumePositionOnStart = resumeOnStartForFallback;
            await StartPlaybackAsync(source, preserveCompact, isRecovery, recoveryPositionMs,
                engineFallbackAttempt: true);
            return;
        }
        if (engineFallbackAttempt && result == PlaybackAttemptResult.FirstFrame)
        {
            _itemEngineOverride[itemKey] = _engine;
            LaunchDiagnostics.Write($"event=playback.fallback outcome=playing engine={engineName}");
        }
        if (source.Kind == StreamKind.Live && result != PlaybackAttemptResult.FirstFrame)
        {
            StopPlayerEpgUpdates();
            ClearPlayerNowNext();
        }
        if (_isCinemaMode && result == PlaybackAttemptResult.FirstFrame)
            ShowCinemaControls();
        if (result == PlaybackAttemptResult.FirstFrame)
        {
            SetPlayerLoadingArtState(PlayerLoadingArtState.FirstFrame);
            ClearPlaybackStatus();
            if (engineFallbackAttempt)
                SetPlaybackStatus("Playing with the other player");
            if (_playbackIntent == PlaybackUserIntent.Paused && (_engine.IsPlaying || _engine.IsBuffering))
            {
                _engine.TogglePause();
                _awaitingForwardProgressAfterAction = false;
            }
            if (isRecovery)
            {
                LaunchDiagnostics.Write($"event=playback.resume.ok positionMs={_lastGoodPlaybackPositionMilliseconds}");
            }
            SetPlaybackSurfaceVisibility(true, allowCurtain: true);
            PlayerVideoCurtain.Visibility = Visibility.Collapsed;
            LaunchDiagnostics.Write($"event=player.curtain-lowered generation={generation}");
            _pendingResumePosition = openAtResume ? null
                : isRecovery && recoveryPositionMs is > 0
                ? recoveryPositionMs
                : resumePosition > 0 ? resumePosition : null;
            _activePlaybackSessionGeneration = generation;
            _resumeReady = true;
        }
        if (result == PlaybackAttemptResult.FirstFrame)
            SetPauseButtonState(_playbackIntent == PlaybackUserIntent.Paused ? "Play" : "Pause");
        else if (result == PlaybackAttemptResult.Cancelled)
        {
            ClearPlaybackStatus();
            SetPauseButtonState("Play");
        }
        _isUpdatingVolume = true;
        try
        {
            VolumeSlider.Value = _engine.Volume;
            CompactVolumeSlider.Value = VolumeSlider.Value;
        }
        finally
        {
            _isUpdatingVolume = false;
        }
        if (result != PlaybackAttemptResult.FirstFrame && result != PlaybackAttemptResult.Cancelled)
            ShowFinalPlaybackFailure(result, expectedGeneration: generation);
    }

    private static string SanitizePlaybackLabel(string value)
    {
        var withoutUrls = Regex.Replace(value,
            @"(?i)(?:https?|rtsp|ftp)://\S+|\bwww\.\S+|\b[\w-]+(?:\.[\w-]+)*\.[a-z]{2,}(?::\d+)?(?:[/?#]\S*)?", " ");
        var safe = Regex.Replace(withoutUrls, "[\\p{Cc}\"']", " ");
        safe = Regex.Replace(safe, @"\s+", " ").Trim();
        return safe.Length > 120 ? safe[..120] : safe;
    }

    private void WritePendingPlaybackTarget(long generation)
    {
        if (_pendingPlaybackTargetGeneration != generation || _pendingPlaybackTargetLine is not { } line)
            return;

        _pendingPlaybackTargetLine = null;
        LaunchDiagnostics.Write(line);
    }

    internal void WriteAppStartDiagnostics()
    {
        LaunchDiagnostics.SetAppContext(EngineLabel(_engine), _selectedTheme.ToString());
        LaunchDiagnostics.WriteAppStart();
    }

    private bool IsCurrentPlaybackStart(long generation, CancellationTokenSource sessionCts) =>
        generation == Volatile.Read(ref _playbackSessionGeneration) &&
        !sessionCts.IsCancellationRequested &&
        IsPlaybackUiActive;

    private void TraceClickAway(long traceId, string message)
    {
        if (traceId == 0) return;
        var elapsed = Stopwatch.GetTimestamp() - Volatile.Read(ref _playerEntryTraceStartedAt);
        var elapsedMilliseconds = elapsed * 1000d / Stopwatch.Frequency;
        LaunchDiagnostics.Write($"CLICK-AWAY-TRACE id={traceId} elapsedMs={elapsedMilliseconds:0.000} {message}");
    }

    private void StopPlaybackForNavigation()
    {
        CancelSleepTimer("navigation");
        SetPlaybackIntent(PlaybackUserIntent.StoppedByUser, awaitForwardProgress: false);
        SaveResumePosition(force: true);
        if (_currentSource is { } stoppingSource)
            LaunchDiagnostics.Write($"event=playback.stop engine={(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native")} kind={stoppingSource.Kind} reason={(_engine.IsEnded ? "completed" : "user-cancelled")}");
        // Hide the video host before touching playback or starting the catalog fade.
        // The WinUI LibVLC host is swap-chain-backed, so there is no child HWND whose
        // visibility or z-order can be controlled independently from this XAML element.
        SetPlaybackSurfaceVisibility(false);
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
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
        ClearPlayerNowNext();
        ResetTrackSelectionForSession();
        _pendingResumePosition = null;
        _resumeReady = false;
        _nowPlayingChannel = null;
        _currentSeriesId = null;
        _currentSeriesTitle = null;
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        _playerSiblings = Array.Empty<Channel>();
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
        PreviousEpisodeButton.Visibility = Visibility.Collapsed;
        NextEpisodeButton.Visibility = Visibility.Collapsed;
        CompactPreviousButton.Visibility = Visibility.Collapsed;
        CompactNextButton.Visibility = Visibility.Collapsed;
        _playbackUiTimer.Stop();
        _isDraggingProgress = false;
        _isUpdatingProgress = false;
        ClearPlaybackStatus();
        ElapsedText.Text = FormatTime(0);
        DurationText.Text = FormatTime(0);
        ProgressSlider.Minimum = 0;
        ProgressSlider.Maximum = 1;
        ProgressSlider.Value = 0;
        ProgressSlider.IsEnabled = false;
        CompactProgressSlider.Minimum = 0;
        CompactProgressSlider.Maximum = 1;
        CompactProgressSlider.Value = 0;
        CompactProgressSlider.IsEnabled = false;
        CompactElapsedText.Text = FormatTime(0);
        CompactDurationText.Text = FormatTime(0);
        RewindButton.IsEnabled = false;
        ForwardButton.IsEnabled = false;
        SetPauseButtonState("Play");
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
        PlayerSeasonWatchedCount.Text = string.Empty;
        PlayerSeasonWatchedCount.Visibility = Visibility.Collapsed;
        _playerEntries.Clear();
        SetCinemaMode(false, animate: false);

        _playbackStopTask = QueueBothPlaybackEnginesStop();
        TraceBackTransition($"player-stop-return asyncStopStarted={!_playbackStopTask.IsCompleted} video={VideoSurfaceState()} curtain={PlayerVideoCurtain.Visibility}");
    }

    // The pause control is an icon; its label lives in the automation name and tooltip so
    // screen readers and UI tests still see "Pause" / "Play" / "Retry".
    private void SetPauseButtonState(string label)
    {
        PauseButton.Content = label switch { "Pause" => "\uE769", "Retry" => "\uE72C", _ => "\uE768" };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(PauseButton, label);
        ToolTipService.SetToolTip(PauseButton, label);
        CompactPauseButton.Content = PauseButton.Content;
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(CompactPauseButton, label);
        ToolTipService.SetToolTip(CompactPauseButton, label);
    }

    private async Task StopBothPlaybackEnginesAsync()
    {
        TraceBackTransition($"engine-stop-both-begin vlcPlaying={_vlcEngine.IsPlaying} nativePlaying={_nativeEngine.IsPlaying}");
        await Task.WhenAll(TimedStopAsync("LibVLC", _vlcPlayback), TimedStopAsync("Native", _nativePlayback));
        TraceBackTransition($"engine-stop-both-complete vlcPlaying={_vlcEngine.IsPlaying} nativePlaying={_nativeEngine.IsPlaying}");
    }

    // A stop that blocks delays the next open (the handoff gate is held meanwhile), so a slow one is
    // always logged with the engine that caused it.
    private static async Task TimedStopAsync(string engineName, PlaybackService service)
    {
        var started = Stopwatch.GetTimestamp();
        await service.StopAsync();
        var elapsed = Stopwatch.GetElapsedTime(started);
        if (elapsed >= TimeSpan.FromMilliseconds(500))
            LaunchDiagnostics.Write($"event=playback.handoff.slow-stop engine={engineName} elapsedMs={elapsed.TotalMilliseconds:0}");
    }

    private Task<T> OnUiAsync<T>(Func<Task<T>> action)
    {
        var dispatcher = WindowRoot.DispatcherQueue;
        if (dispatcher.HasThreadAccess)
        {
            using var perfScope = PerfScope.Begin("RunOnUiAsync");
            return action();
        }

        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!dispatcher.TryEnqueue(async () =>
            {
                Task<T> actionTask;
                try
                {
                    using (PerfScope.Begin("RunOnUiAsync"))
                        actionTask = action();
                    var result = await actionTask;
                    using (PerfScope.Begin("RunOnUiAsyncCompletion"))
                        completion.TrySetResult(result);
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

    private string EngineLabel(IPlaybackEngine engine) => ReferenceEquals(engine, _vlcEngine) ? "LibVLC" : "Native";

    // The key never leaves memory (the stream path can carry provider credentials) and is never logged.
    private static string PlaybackItemKey(StreamSource source) => $"{source.Kind}|{source.DirectUri?.AbsolutePath}";

    // Switches the engine for the current item only; unlike the settings toggle it does not touch the saved preference.
    private void SwitchEngineForPlayback(IPlaybackEngine target)
    {
        SetPlaybackIntent(_playbackIntent, awaitForwardProgress: _playbackIntent == PlaybackUserIntent.Playing,
            baselinePosition: _engine.Timeline.PositionMilliseconds);
        target.IsMuted = _playbackMuteState.IsMuted;
        Volatile.Write(ref _engine, target);
        LaunchDiagnostics.SetAppContext(EngineLabel(_engine), _selectedTheme.ToString());
        SetTrackSelectingEngine(_engine);
        _playback = ReferenceEquals(target, _vlcEngine) ? _vlcPlayback : _nativePlayback;
        ApplyPlaybackEngineVisuals();
    }

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
                !IsPlaybackUiActive ||
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
                IsPlaybackUiActive &&
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
        _catalogLandingPage.NotifyMetadataChanged(_catalogLandingPage.Account?.AccountId ?? channel.ProviderAccountId, type, channel.Id);
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
            CatalogRatingFormatter.Format(metadata.Rating) is { } rating ? $"★ {rating}" : null,
            metadata.Genre,
        }.Where(value => !string.IsNullOrWhiteSpace(value)));
        var about = new List<string>();
        if (!string.IsNullOrWhiteSpace(metadata.Plot)) about.Add(metadata.Plot);
        if (!string.IsNullOrWhiteSpace(metadata.Cast)) about.Add($"Cast: {metadata.Cast}");
        PlayerAboutText.Text = about.Count == 0 ? "No description is available for this title." : string.Join(Environment.NewLine + Environment.NewLine, about);
    }

    private void EpgCoordinator_EpgUpdated(object? sender, EventArgs args)
    {
        using var perfScope = PerfScope.Begin("PlayerEpgUpdate");
        if (_isClosing || !_catalogLandingPage.EpgEnabled) return;
        if (!WindowRoot.DispatcherQueue.HasThreadAccess)
        {
            WindowRoot.DispatcherQueue.TryEnqueue(() =>
            {
                using var dispatchScope = PerfScope.Begin("PlayerEpgDispatch");
                EpgCoordinator_EpgUpdated(sender, args);
            });
            return;
        }
        if (IsPlaybackUiActive && _currentSource?.Kind == StreamKind.Live)
            _ = RefreshPlayerNowNextAsync();
    }

    private void PlayerEpgTimer_Tick(object? sender, object args)
    {
        using var perfScope = PerfScope.Begin("PlayerEpgTimerTick");
        if (!_catalogLandingPage.EpgEnabled || !IsPlaybackUiActive || _currentSource?.Kind != StreamKind.Live)
        {
            StopPlayerEpgUpdates();
            return;
        }
        _ = RefreshPlayerNowNextAsync();
    }

    private void StartPlayerEpgUpdates()
    {
        StopPlayerEpgUpdates();
        if (!_catalogLandingPage.EpgEnabled || _isClosing || !IsPlaybackUiActive || _currentSource?.Kind != StreamKind.Live ||
            _nowPlayingChannel?.Metadata.TryGetValue("epg_channel_id", out var epgChannelId) != true ||
            string.IsNullOrWhiteSpace(epgChannelId) || _catalogLandingPage.Account is null)
            return;
        _playerEpgTimer.Start();
    }

    private void StopPlayerEpgUpdates() => _playerEpgTimer.Stop();

    private void ClearPlayerNowNext()
    {
        Interlocked.Increment(ref _playerEpgGeneration);
        StopPlayerEpgUpdates();
        ApplyPlayerNowNext(null);
    }

    private async Task RefreshPlayerNowNextAsync()
    {
        if (_isClosing) return;
        if (!_catalogLandingPage.EpgEnabled)
        {
            ClearPlayerNowNext();
            return;
        }
        if (!WindowRoot.DispatcherQueue.HasThreadAccess)
        {
            WindowRoot.DispatcherQueue.TryEnqueue(() =>
            {
                using var dispatchScope = PerfScope.Begin("RefreshPlayerNowNextDispatch");
                _ = RefreshPlayerNowNextAsync();
            });
            return;
        }

        if (!IsPlaybackUiActive || _currentSource?.Kind != StreamKind.Live ||
            _nowPlayingChannel is not { } channel ||
            channel.Metadata.TryGetValue("epg_channel_id", out var epgChannelId) != true ||
            string.IsNullOrWhiteSpace(epgChannelId) || _catalogLandingPage.Account is not { } account)
        {
            ClearPlayerNowNext();
            return;
        }

        var generation = Volatile.Read(ref _playerEpgGeneration);
        var id = epgChannelId;
        try
        {
            var nowNext = await Task.Run(() => _epgCoordinator.GetNowNext(account, new[] { id }));
            if (_isClosing || generation != Volatile.Read(ref _playerEpgGeneration) ||
                !IsPlaybackUiActive || _currentSource?.Kind != StreamKind.Live ||
                _nowPlayingChannel?.Id != channel.Id)
                return;

            nowNext.TryGetValue(id, out var programme);
            ApplyPlayerNowNext(programme);
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Player EPG update failed: {exception.GetType().Name}");
            if (!_isClosing && generation == Volatile.Read(ref _playerEpgGeneration))
                ApplyPlayerNowNext(null);
        }
    }

    private void ApplyPlayerNowNext(EpgNowNext? nowNext)
    {
        using var perfScope = PerfScope.Begin("PlayerNowNextUiApply");
        nowNext = CatalogLandingPage.FilterEpgForUi(_catalogLandingPage.EpgEnabled, nowNext);
        _playerNowNext = nowNext?.Now is null && nowNext?.Next is null ? null : nowNext;
        if (_playerNowNext?.Now is { } now)
        {
            PlayerNowText.Text = $"Now: {now.Title}";
            PlayerNowText.Visibility = Visibility.Visible;
            PlayerNowProgress.Value = NowNextProgress(now);
            PlayerNowProgress.Visibility = Visibility.Visible;
        }
        else
        {
            PlayerNowText.Text = string.Empty;
            PlayerNowText.Visibility = Visibility.Collapsed;
            PlayerNowProgress.Value = 0;
            PlayerNowProgress.Visibility = Visibility.Collapsed;
        }

        if (_playerNowNext?.Next is { } next)
        {
            PlayerNextText.Text = $"Next: {next.Title} · {FormatLocalTime(next.StartUtc)}";
            PlayerNextText.Visibility = Visibility.Visible;
        }
        else
        {
            PlayerNextText.Text = string.Empty;
            PlayerNextText.Visibility = Visibility.Collapsed;
        }

        var accessibleParts = new List<string>();
        if (_playerNowNext?.Now is { } accessibleNow)
            accessibleParts.Add($"Now playing: {accessibleNow.Title}, {NowNextProgress(accessibleNow):0} percent through");
        if (_playerNowNext?.Next is { } accessibleNext)
            accessibleParts.Add($"next: {accessibleNext.Title} at {FormatLocalTime(accessibleNext.StartUtc)}");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(
            PlayerNowNextPanel, string.Join(", ", accessibleParts));
        ApplyPlayerNowNextVisibility();
    }

    private void ApplyPlayerNowNextVisibility() =>
        PlayerNowNextPanel.Visibility = _playerNowNext is not null &&
            _currentPage == ShellPage.Player && !_isCinemaMode &&
            VlcTransportBar.Visibility == Visibility.Visible
            ? Visibility.Visible
            : Visibility.Collapsed;

    private static double NowNextProgress(EpgProgramme programme) =>
        Math.Clamp((DateTimeOffset.UtcNow - programme.StartUtc).TotalSeconds /
            Math.Max(1, (programme.EndUtc - programme.StartUtc).TotalSeconds) * 100, 0, 100);

    private static string FormatLocalTime(DateTimeOffset utcTime) =>
        utcTime.ToLocalTime().ToString("HH:mm", CultureInfo.CurrentCulture);

    private async void PauseButton_Click(object sender, RoutedEventArgs args)
    {
        if (_sleepTimerEnded || _currentSource is null || _terminalPlaybackFailure) return;
        if (Volatile.Read(ref _playbackSessionCts) is not null)
        {
            // A start is still in flight. Space or a click on the video must not restart it: that cancels
            // the open in progress and queues a second one behind the handoff gate.
            var nextIntent = PlaybackInterruptionPolicy.ToggleIntent(_playbackIntent);
            SetPlaybackIntent(nextIntent, awaitForwardProgress: nextIntent == PlaybackUserIntent.Playing,
                baselinePosition: _engine.Timeline.PositionMilliseconds);
            LaunchDiagnostics.Write($"event=playback.toggle-deferred intent={nextIntent}");
            return;
        }
        if (!_engine.IsEnded && (_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused))
        {
            var nextIntent = PlaybackInterruptionPolicy.ToggleIntent(_playbackIntent);
            SetPlaybackIntent(nextIntent, awaitForwardProgress: nextIntent == PlaybackUserIntent.Playing,
                baselinePosition: _engine.Timeline.PositionMilliseconds);
            _engine.TogglePause();
            SetPauseButtonState(nextIntent == PlaybackUserIntent.Playing ? "Pause" : "Play");
            if (_isCinemaMode)
            {
                if (nextIntent == PlaybackUserIntent.Playing) ShowCinemaControls("user-resume", resetIdleDeadline: true);
                else StopCinemaCursorIdleTimer(showCursor: true);
            }
        }
        else
            await StartPlaybackAsync(_currentSource, preserveCompact: _playerPresentation == PlayerPresentation.Compact);
    }

    private void SetPlaybackIntent(PlaybackUserIntent intent, bool awaitForwardProgress, long? baselinePosition = null)
    {
        _playbackIntent = intent;
        _awaitingForwardProgressAfterAction = awaitForwardProgress;
        if (baselinePosition is { } position)
            _lastInterruptionObservedPosition = Math.Max(0, position);
    }

    internal string SleepTimerRemainingText => _sleepTimerRemainingText;
    internal bool SleepTimerWarningVisible => _sleepTimerWarningVisible;
    internal bool SleepTimerEnded => _sleepTimerEnded;
    internal string SleepTimerNoticeText { get; private set; } = string.Empty;
    internal event EventHandler? SleepTimerStateChanged;

    internal void StartSleepTimer(SleepTimerMode mode, int minutes)
    {
        var now = Stopwatch.GetElapsedTime(_sleepTimerClockOrigin);
        SleepTimerDecision decision;
        if (mode == SleepTimerMode.EndOfCurrentItem)
        {
            if (_nowPlayingChannel is not { } current ||
                !_sleepTimerPolicy.TryStartEndOfCurrentItem(
                    SleepTimerItemIdentity(current), current.Source.Kind == StreamKind.Live))
                return;
            _sleepTimerMinutes = 0;
            decision = new SleepTimerDecision(_sleepTimerPolicy.State);
        }
        else
        {
            _sleepTimerMinutes = SleepTimerPolicy.ClampCustomMinutes(minutes);
            decision = _sleepTimerPolicy.StartDuration(now, TimeSpan.FromMinutes(_sleepTimerMinutes));
        }

        _sleepTimerEnded = false;
        _sleepTimerStopStarted = false;
        _sleepTimerWarningVisible = false;
        SleepTimerNoticeText = string.Empty;
        _sleepTimerNoticeTimer.Stop();
        UpdateSleepTimerDisplay(now, decision);
        LaunchDiagnostics.Write($"event=sleep-timer.armed mode={mode} minutes={_sleepTimerMinutes}");
        AnnounceSleepTimer(mode == SleepTimerMode.EndOfCurrentItem
            ? "Sleep timer set to stop at the end of this video"
            : $"Sleep timer set for {_sleepTimerMinutes} minutes");
        RaiseSleepTimerStateChanged();
    }

    internal void ExtendSleepTimer()
    {
        if (_sleepTimerPolicy.Mode != SleepTimerMode.Duration || _sleepTimerEnded) return;
        var decision = _sleepTimerPolicy.Extend(Stopwatch.GetElapsedTime(_sleepTimerClockOrigin));
        if (decision.StopPlayback)
        {
            MarkSleepTimerEnded();
            _ = EndPlaybackForSleepTimerAsync();
            return;
        }

        _sleepTimerMinutes += (int)SleepTimerPolicy.ExtensionDuration.TotalMinutes;
        _sleepTimerWarningVisible = false;
        SleepTimerNoticeText = string.Empty;
        _sleepTimerNoticeTimer.Stop();
        UpdateSleepTimerDisplay(Stopwatch.GetElapsedTime(_sleepTimerClockOrigin), decision);
        LaunchDiagnostics.Write($"event=sleep-timer.extended mode={SleepTimerMode.Duration} minutes={_sleepTimerMinutes}");
        RaiseSleepTimerStateChanged();
    }

    internal void CancelSleepTimer(string reason)
    {
        if (_sleepTimerPolicy.State is SleepTimerState.Off or SleepTimerState.Ended) return;
        var mode = _sleepTimerPolicy.Mode ?? SleepTimerMode.Duration;
        var minutes = _sleepTimerMinutes;
        var decision = string.Equals(reason, "item-changed", StringComparison.Ordinal)
            ? _sleepTimerPolicy.ManualItemChange()
            : _sleepTimerPolicy.Cancel();
        if (decision.State == SleepTimerState.Off)
        {
            _sleepTimerWarningVisible = false;
            _sleepTimerRemainingText = string.Empty;
            SleepTimerNoticeText = string.Equals(reason, "item-changed", StringComparison.Ordinal)
                ? "Sleep timer turned off after changing video"
                : string.Empty;
            if (!string.IsNullOrEmpty(SleepTimerNoticeText)) _sleepTimerNoticeTimer.Start();
            else _sleepTimerNoticeTimer.Stop();
            LaunchDiagnostics.Write($"event=sleep-timer.cancelled mode={mode} minutes={minutes}");
            RaiseSleepTimerStateChanged();
        }
    }

    private void CancelEndOfCurrentItemTimerForManualChange(Channel target)
    {
        if (_sleepTimerPolicy.Mode == SleepTimerMode.EndOfCurrentItem &&
            !StringComparer.Ordinal.Equals(_sleepTimerPolicy.TargetItemIdentity, SleepTimerItemIdentity(target)))
            CancelSleepTimer("item-changed");
    }

    private static string SleepTimerItemIdentity(Channel channel) =>
        $"{channel.ProviderAccountId}|{channel.Source.Kind}|{channel.Id}|{channel.GroupId ?? string.Empty}";

    private bool ShouldStopBeforeStartingNextItem()
    {
        EvaluateSleepTimer();
        return _sleepTimerEnded;
    }

    private bool EvaluateSleepTimer()
    {
        if (_sleepTimerEnded) return true;
        var now = Stopwatch.GetElapsedTime(_sleepTimerClockOrigin);
        var decision = _sleepTimerPolicy.Tick(now);
        UpdateSleepTimerDisplay(now, decision);
        if (!decision.StopPlayback) return false;

        MarkSleepTimerEnded();
        _ = EndPlaybackForSleepTimerAsync();
        return true;
    }

    private void UpdateSleepTimerDisplay(TimeSpan now, SleepTimerDecision decision)
    {
        var previousText = _sleepTimerRemainingText;
        var previousWarning = _sleepTimerWarningVisible;
        if (decision.ShowWarning && !_sleepTimerWarningVisible)
        {
            _sleepTimerWarningVisible = true;
            AnnounceSleepTimer("Playback will stop in 2 minutes");
        }
        if (decision.State is SleepTimerState.Off or SleepTimerState.Ended)
        {
            _sleepTimerRemainingText = string.Empty;
        }
        else if (_sleepTimerPolicy.Mode == SleepTimerMode.EndOfCurrentItem)
        {
            _sleepTimerRemainingText = _nowPlayingChannel?.Source.Kind == StreamKind.Movie
                ? "Stops when this movie ends"
                : "Stops when this episode ends";
        }
        else if (_sleepTimerPolicy.GetRemainingDisplay(now) is { } remaining)
        {
            _sleepTimerRemainingText = remaining.Unit == SleepTimerRemainingUnit.Seconds
                ? $"{remaining.Value} sec left"
                : $"{remaining.Value} min left";
        }
        if (previousText != _sleepTimerRemainingText || previousWarning != _sleepTimerWarningVisible)
            RaiseSleepTimerStateChanged();
    }

    private void MarkSleepTimerEnded()
    {
        if (_sleepTimerEnded) return;
        _sleepTimerEnded = true;
        _sleepTimerWarningVisible = false;
        _sleepTimerRemainingText = string.Empty;
        SleepTimerNoticeText = string.Empty;
        _sleepTimerNoticeTimer.Stop();
        if (SleepTimerFlyout.IsOpen) SleepTimerFlyout.Hide();
        AnnounceSleepTimer("Sleep timer ended");
    }

    private void RaiseSleepTimerStateChanged() => SleepTimerStateChanged?.Invoke(this, EventArgs.Empty);

    private void MainWindow_SleepTimerStateChanged(object? sender, EventArgs args) => RefreshSleepTimerPresentation();

    private void RefreshSleepTimerPresentation()
    {
        if (SleepTimerStatusText is null) return;
        var armed = _sleepTimerPolicy.State is not (SleepTimerState.Off or SleepTimerState.Ended) && !_sleepTimerEnded;
        var fullPlayer = _playerPresentation != PlayerPresentation.Compact && !_windowState.IsWindowMini;
        var status = !string.IsNullOrEmpty(SleepTimerNoticeText)
            ? SleepTimerNoticeText
            : armed && !string.IsNullOrEmpty(_sleepTimerRemainingText)
                ? $"Sleep timer · {_sleepTimerRemainingText}"
                : string.Empty;
        SleepTimerStatusText.Text = status;
        SleepTimerStatusPanel.Visibility = fullPlayer && !string.IsNullOrEmpty(status) ? Visibility.Visible : Visibility.Collapsed;
        SleepTimerWarningPanel.Visibility = fullPlayer && _sleepTimerWarningVisible && !_sleepTimerEnded
            ? Visibility.Visible
            : Visibility.Collapsed;
        SleepTimerEndedOverlay.Visibility = fullPlayer && _sleepTimerEnded ? Visibility.Visible : Visibility.Collapsed;
        if (_sleepTimerWarningVisible || _sleepTimerEnded)
            NextEpisodePrompt.Visibility = Visibility.Collapsed;

        var kind = _nowPlayingChannel?.Source.Kind;
        SleepTimerEndOfItemButton.Visibility = kind == StreamKind.Live ? Visibility.Collapsed : Visibility.Visible;
        SleepTimerEndOfItemButton.Content = kind == StreamKind.Episode
            ? "At the end of this episode"
            : "At the end of this movie";
        SleepTimerEndedReplayActions.Visibility = kind is StreamKind.Movie or StreamKind.Episode or StreamKind.Live
            ? Visibility.Visible
            : Visibility.Collapsed;
        SleepTimerWatchAgainButton.Visibility = kind is StreamKind.Movie or StreamKind.Episode
            ? Visibility.Visible
            : Visibility.Collapsed;
        SleepTimerWatchChannelButton.Visibility = kind == StreamKind.Live
            ? Visibility.Visible
            : Visibility.Collapsed;

        SleepTimerFlyoutStatusText.Text = _sleepTimerRemainingText;
        var accessibleRemaining = _sleepTimerRemainingText
            .Replace(" min left", " minutes left", StringComparison.Ordinal)
            .Replace(" sec left", " seconds left", StringComparison.Ordinal);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(
            SleepTimerButton,
            armed && !string.IsNullOrEmpty(_sleepTimerRemainingText)
                ? $"Sleep timer, {accessibleRemaining}"
                : "Sleep timer");
        ToolTipService.SetToolTip(SleepTimerButton, status.Length == 0 ? "Sleep timer" : status);
        if (SleepTimerFlyout.IsOpen) PrepareSleepTimerFlyout();
    }

    private void PrepareSleepTimerFlyout()
    {
        var armed = _sleepTimerPolicy.State is not (SleepTimerState.Off or SleepTimerState.Ended) && !_sleepTimerEnded;
        SleepTimerChoicesPanel.Visibility = (armed && !_sleepTimerChangeEntryOpen) || _sleepTimerCustomEntryOpen
            ? Visibility.Collapsed
            : Visibility.Visible;
        SleepTimerArmedPanel.Visibility = armed && !_sleepTimerCustomEntryOpen && !_sleepTimerChangeEntryOpen
            ? Visibility.Visible
            : Visibility.Collapsed;
        SleepTimerCustomPanel.Visibility = _sleepTimerCustomEntryOpen
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void SleepTimerPreset_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button button || button.Tag is null ||
            !int.TryParse(Convert.ToString(button.Tag, CultureInfo.InvariantCulture),
                NumberStyles.Integer, CultureInfo.InvariantCulture, out var minutes)) return;
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        if (minutes == 0) CancelSleepTimer("user");
        else StartSleepTimer(SleepTimerMode.Duration, minutes);
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerEndOfItem_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        StartSleepTimer(SleepTimerMode.EndOfCurrentItem, 0);
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerChooseMinutes_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = true;
        _sleepTimerChangeEntryOpen = false;
        SleepTimerMinutesNumberBox.Value = 30;
        PrepareSleepTimerFlyout();
    }

    private void SleepTimerCustomStart_Click(object sender, RoutedEventArgs args)
    {
        var value = SleepTimerMinutesNumberBox.Value;
        if (!double.IsFinite(value)) value = 30;
        var minutes = Math.Clamp((int)(Math.Round(value / 5, MidpointRounding.AwayFromZero) * 5), 5, 240);
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        StartSleepTimer(SleepTimerMode.Duration, minutes);
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerCustomCancel_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerExtend_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        ExtendSleepTimer();
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerCancel_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
        CancelSleepTimer("user");
        SleepTimerFlyout.Hide();
    }

    private void SleepTimerChange_Click(object sender, RoutedEventArgs args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = true;
        PrepareSleepTimerFlyout();
    }

    private void SleepTimerFlyout_Closed(object sender, object args)
    {
        _sleepTimerCustomEntryOpen = false;
        _sleepTimerChangeEntryOpen = false;
    }

    private void SleepTimerWatchAgain_Click(object sender, RoutedEventArgs args)
    {
        if (_nowPlayingChannel is not { } channel) return;
        SleepTimerEndedOverlay.Visibility = Visibility.Collapsed;
        if (channel.Source.Kind == StreamKind.Episode)
            OpenSeriesEpisode(channel);
        else if (channel.Source.Kind is StreamKind.Movie or StreamKind.Live)
            PlayRelatedChannel(channel);
    }

    private void SleepTimerBack_Click(object sender, RoutedEventArgs args) => ReturnFromPlayer();

    private void SleepTimerNoticeTimer_Tick(object? sender, object args)
    {
        _sleepTimerNoticeTimer.Stop();
        if (_sleepTimerPolicy.State == SleepTimerState.Off && !_sleepTimerEnded)
        {
            SleepTimerNoticeText = string.Empty;
            RaiseSleepTimerStateChanged();
        }
    }

    private void AnnounceSleepTimer(string announcement)
    {
        SleepTimerAnnouncementText.Text = announcement;
        Microsoft.UI.Xaml.Automation.Peers.FrameworkElementAutomationPeer
            .CreatePeerForElement(SleepTimerAnnouncementText)?
            .RaiseAutomationEvent(Microsoft.UI.Xaml.Automation.Peers.AutomationEvents.LiveRegionChanged);
    }

    private async Task EndPlaybackForSleepTimerAsync(bool forceInProgress = true)
    {
        if (_sleepTimerStopStarted) return;
        _sleepTimerStopStarted = true;
        SetPlaybackIntent(PlaybackUserIntent.StoppedByUser, awaitForwardProgress: false);
        _nextEpisodeAutoPlaySuppressed = true;
        _nextEpisodeAdvanceStarted = true;
        ClearPreparedNextItem();
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
        StopHeldSeek(commit: false, released: false);
        var position = _currentSource?.Kind == StreamKind.Live
            ? 0
            : Math.Max(_lastGoodPlaybackPositionMilliseconds, _engine.Timeline.PositionMilliseconds);
        SaveResumePosition(force: true, positionOverride: position, forceInProgress: forceInProgress);

        Interlocked.Increment(ref _playbackSessionGeneration);
        Volatile.Write(ref _activePlaybackSessionGeneration, -1);
        var sessionCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        sessionCts?.Cancel();
        sessionCts?.Dispose();
        _resumeReady = false;
        _pendingResumePosition = null;
        _recoveryInFlight = false;
        _recoveryGeneration = -1;
        LaunchDiagnostics.Write($"event=sleep-timer.ended mode={_sleepTimerPolicy.Mode ?? SleepTimerMode.Duration} minutes={_sleepTimerMinutes}");
        _terminalPlaybackFailure = false;
        RetryPlaybackButton.Visibility = Visibility.Collapsed;
        PauseButton.IsEnabled = false;
        SetPauseButtonState("Play");
        ClearPlaybackStatus();
        SetPlayerLoadingArtState(PlayerLoadingArtState.FinalFailure);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        try
        {
            _playbackStopTask = StopBothPlaybackEnginesAsync();
            await _playbackStopTask;
        }
        catch
        {
        }
        finally
        {
            SetPlaybackSurfaceVisibility(false);
            PlayerVideoCurtain.Visibility = Visibility.Visible;
            SleepTimerEndedOverlay.Visibility = Visibility.Visible;
            _playbackUiTimer.Stop();
            RaiseSleepTimerStateChanged();
        }
    }

    private void PlaybackUiTimer_Tick(object? sender, object args)
    {
        if (EvaluateSleepTimer()) return;
        if (_sleepTimerEnded)
        {
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            return;
        }
        if (!IsPlaybackUiActive)
        {
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            return;
        }
        if (_engine.IsEnded)
            StopHeldSeek(commit: true, released: false);
        var sessionIsCurrent = _resumeReady && _activePlaybackSessionGeneration == Volatile.Read(ref _playbackSessionGeneration);
        var playbackFailed = sessionIsCurrent && _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
        if (_isCinemaMode)
        {
            if (_engine.IsPlaying || _engine.IsBuffering)
            {
                // Resumed after a pause: start hiding the overlay again.
                if (_cinemaControlsVisible && !_cinemaCursorIdleTimer.IsEnabled)
                    ShowCinemaControls("resume", resetIdleDeadline: true);
            }
            else if (_engine.IsPaused || _engine.IsEnded || playbackFailed)
                ShowCinemaControls("paused-or-terminal");
        }
        else if (_playerPresentation == PlayerPresentation.Compact)
        {
            if (!_engine.IsPlaying || _engine.IsBuffering)
            {
                _cinemaCursorIdleTimer.Stop();
                SetCompactControlsVisible(true);
            }
            else if (CompactChrome.IsHitTestVisible && !_cinemaCursorIdleTimer.IsEnabled &&
                     !_compactPointerOverControls && !HasCompactKeyboardNavigationFocus() &&
                     !_compactGeometryGestureActive && !IsTrackFlyoutOpen && !VolumeFlyout.IsOpen)
            {
                _cinemaCursorIdleTimer.Start();
            }
        }
        var timeline = _engine.Timeline;
        var ended = _engine.IsEnded;
        var endDuration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
        var endPosition = ended ? _lastGoodPlaybackPositionMilliseconds : timeline.PositionMilliseconds;
        var genuineEnd = PlaybackEndPolicy.IsGenuineEnd(endPosition, endDuration, ended);
        if (sessionIsCurrent && !ended && !_isHeldSeeking)
        {
            var lastObservedPosition = ClampPosition(timeline.PositionMilliseconds, endDuration);
            if (lastObservedPosition > _lastInterruptionObservedPosition)
            {
                _lastInterruptionObservedPosition = lastObservedPosition;
                _hasPlaybackAdvancedThisSession = true;
                _lastForwardProgressAt = Stopwatch.GetElapsedTime(_stallClockStart);
                _interruptionBudget.RecordForwardProgress(_lastForwardProgressAt);
                _awaitingForwardProgressAfterAction = false;
            }
            if (lastObservedPosition > _lastGoodPlaybackPositionMilliseconds)
            {
                _lastGoodPlaybackPositionMilliseconds = lastObservedPosition;
            }
        }
        if (sessionIsCurrent && !_isHeldSeeking && !_isDraggingProgress && !_recoveryInFlight)
        {
            var nowElapsed = Stopwatch.GetElapsedTime(_stallClockStart);
            var state = ended ? PlaybackEngineState.Ended
                : _engine.IsPaused ? PlaybackEngineState.Paused
                : _engine.IsStopped ? PlaybackEngineState.Stopped
                : _engine.IsBuffering ? PlaybackEngineState.Buffering
                : _engine.IsPlaying ? PlaybackEngineState.Playing
                : PlaybackEngineState.Other;
            var interruption = PlaybackInterruptionPolicy.Classify(new PlaybackInterruptionSnapshot(
                state,
                null,
                nowElapsed - _lastForwardProgressAt,
                _hasPlaybackAdvancedThisSession,
                _lastGoodPlaybackPositionMilliseconds,
                endDuration,
                _playbackIntent,
                _awaitingForwardProgressAfterAction,
                _activePlaybackSessionGeneration,
                Volatile.Read(ref _playbackSessionGeneration),
                _engine.HttpStatus));
            if (interruption != PlaybackInterruption.None)
            {
                _ = RecoverPlaybackAsync(interruption, _activePlaybackSessionGeneration);
                return;
            }
        }
        if (sessionIsCurrent && !playbackFailed && !_isHeldSeeking && _nowPlayingChannel?.Source.Kind == StreamKind.Episode &&
            _currentSeriesId is not null && _currentSeriesAccount is not null)
        {
            var measuredDuration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
            var position = ended
                ? _lastGoodPlaybackPositionMilliseconds
                : ClampPosition(timeline.PositionMilliseconds, measuredDuration);
            RecordEpisodeCompletion(position, measuredDuration);
        }
        else if (sessionIsCurrent && !playbackFailed && !_isHeldSeeking && _nowPlayingChannel?.Source.Kind == StreamKind.Movie &&
            _catalogLandingPage.Account is not null)
        {
            var movieDuration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
            var moviePosition = ended ? _lastGoodPlaybackPositionMilliseconds : ClampPosition(timeline.PositionMilliseconds, movieDuration);
            if (MovieCompletion.IsFinished(moviePosition, movieDuration, ended) != _movieCompletionRecorded)
                SaveResumePosition(force: true);
        }
        if (sessionIsCurrent && ended && genuineEnd && _nowPlayingChannel is { } naturallyEndedChannel)
        {
            var sleepDecision = _sleepTimerPolicy.GenuineNaturalEnd(
                SleepTimerItemIdentity(naturallyEndedChannel), isGenuineEnd: true);
            if (sleepDecision.StopPlayback)
            {
                MarkSleepTimerEnded();
                _ = EndPlaybackForSleepTimerAsync(forceInProgress: false);
                return;
            }
        }
        if (!_sleepTimerEnded && sessionIsCurrent && ended &&
            (_nowPlayingChannel?.Source.Kind == StreamKind.Live || genuineEnd) &&
            _playbackFailurePolicy.ShouldAutoAdvance(_activePlaybackSessionGeneration) &&
            !_nextEpisodeAutoPlaySuppressed && !_nextEpisodeAdvanceStarted &&
            PlaybackModeLogic.ShouldAutoAdvance(GetPlaybackMode(_nowPlayingChannel?.Source.Kind)) && GetNextPlaylistItem() is { } endedNextItem)
        {
            if (ShouldStopBeforeStartingNextItem()) return;
            _nextEpisodeAdvanceStarted = true;
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            if (endedNextItem.Source.Kind == StreamKind.Episode)
                OpenSeriesEpisode(endedNextItem, preserveCompact: _playerPresentation == PlayerPresentation.Compact, autoplayNext: true);
            else
                PlayRelatedChannel(endedNextItem, preserveCompact: _playerPresentation == PlayerPresentation.Compact, autoplayNext: true);
            return;
        }
        if (_windowState.IsWindowMini && !_terminalPlaybackFailure && (_engine.IsEnded ||
            (_engine.IsStopped && Volatile.Read(ref _playbackSessionCts) is null)))
        {
            ExitWindowMiniPlayer(expandToPlayer: false);
            return;
        }
        if (sessionIsCurrent && !playbackFailed && ended && !genuineEnd && !_unverifiedPlaybackEndHandled &&
            _nowPlayingChannel?.Source.Kind is StreamKind.Movie or StreamKind.Episode)
        {
            SaveResumePosition(force: true);
            _unverifiedPlaybackEndHandled = true;
            StatusText.Text = "Playback ended before completion could be verified. Select Retry to resume.";
            SetPauseButtonState("Retry");
        }
        if (sessionIsCurrent && !playbackFailed && !_playbackCompletionShown && ended && genuineEnd && _nowPlayingChannel is { } finishedChannel)
        {
            SetCurrentPlayerEntry(finishedChannel);
            _playbackCompletionShown = true;
        }
        if (_pendingResumePosition is { } resume && timeline.CanSeek)
        {
            var target = Math.Min(resume, Math.Max(0, timeline.DurationMilliseconds!.Value - 1000));
            _engine.Seek(target);
            _diagnosticLastPosition = target;
            LaunchDiagnostics.Write($"event=playback.resume_seek generation={_activePlaybackSessionGeneration} positionMs={target} elapsedMs={Stopwatch.GetElapsedTime(_diagnosticOpenStartedAt).TotalMilliseconds:0}");
            _pendingResumePosition = null;
            timeline = _engine.Timeline;
        }
        if (sessionIsCurrent && !_diagnosticFirstProgressReported && timeline.PositionMilliseconds > _diagnosticLastPosition &&
            !_isHeldSeeking && _pendingResumePosition is null)
        {
            _diagnosticFirstProgressReported = true;
            LaunchDiagnostics.Write($"event=playback.first_progress generation={_activePlaybackSessionGeneration} positionMs={timeline.PositionMilliseconds} elapsedMs={Stopwatch.GetElapsedTime(_diagnosticOpenStartedAt).TotalMilliseconds:0} qualification=positive-position-advance visibleFrame=unverified");
        }
        if (sessionIsCurrent && timeline.PositionMilliseconds > _diagnosticLastPosition)
            _diagnosticLastPosition = timeline.PositionMilliseconds;
        SaveResumePosition();
        var duration = timeline.DurationMilliseconds;
        var length = duration ?? 0;
        var time = timeline.PositionMilliseconds;
        var compactSeekEnabled = timeline.CanSeek && _currentSource?.Kind != StreamKind.Live;
        var compactIsLive = _currentSource?.Kind == StreamKind.Live;
        CompactRewindButton.IsEnabled = compactSeekEnabled;
        CompactForwardButton.IsEnabled = compactSeekEnabled;
        CompactRewindButton.Visibility = compactIsLive ? Visibility.Collapsed : Visibility.Visible;
        CompactForwardButton.Visibility = compactIsLive ? Visibility.Collapsed : Visibility.Visible;
        CompactSeekRow.Visibility = compactIsLive || duration is not > 0 ? Visibility.Collapsed : Visibility.Visible;
        var playbackKind = _nowPlayingChannel?.Source.Kind;
        var nextItem = !playbackFailed && PlaybackModeLogic.ShouldShowNextPrompt(GetPlaybackMode(playbackKind)) && !_nextEpisodeAutoPlaySuppressed && !_nextEpisodeAdvanceStarted
            ? GetNextPlaylistItem()
            : null;
        var remainingMilliseconds = duration is > 0 ? duration.Value - time : long.MaxValue;
        var promptLeadMilliseconds = playbackKind is { } kind && duration is > 0
            ? NextPrompt.LeadMilliseconds(kind, duration.Value)
            : 0;
        if (!_sleepTimerWarningVisible && !_sleepTimerEnded && promptLeadMilliseconds > 0 && nextItem is not null &&
            remainingMilliseconds <= promptLeadMilliseconds && !_engine.IsEnded)
        {
            var label = playbackKind == StreamKind.Movie ? "Next movie" : "Next episode";
            var countdown = remainingMilliseconds <= 10_000
                ? $" · {Math.Max(1, (remainingMilliseconds + 999) / 1000)}s"
                : string.Empty;
            NextEpisodePromptTitle.Text = $"{label}: {nextItem.DisplayName}{countdown}";
            NextEpisodePrompt.Visibility = Visibility.Visible;
        }
        else
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
        var seekEnabled = timeline.CanSeek && _currentSource is not null &&
            (_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded);
        _isUpdatingProgress = true;
        ProgressSlider.Maximum = Math.Max(length, 1);
        ProgressSlider.IsEnabled = seekEnabled;
        CompactProgressSlider.Maximum = Math.Max(length, 1);
        CompactProgressSlider.IsEnabled = seekEnabled;
        RewindButton.IsEnabled = seekEnabled;
        ForwardButton.IsEnabled = seekEnabled;
        if (_isHeldSeeking)
        {
            ProgressSlider.Value = Math.Clamp(_heldSeekTarget, 0, Math.Max(length, 1));
            CompactProgressSlider.Value = ProgressSlider.Value;
            ElapsedText.Text = FormatTime(_heldSeekTarget);
            CompactElapsedText.Text = ElapsedText.Text;
        }
        else if (!_isDraggingProgress)
        {
            ProgressSlider.Value = Math.Clamp(time, 0, Math.Max(length, 1));
            CompactProgressSlider.Value = ProgressSlider.Value;
            ElapsedText.Text = FormatTime(time);
            CompactElapsedText.Text = ElapsedText.Text;
        }
        DurationText.Text = duration.HasValue ? FormatTime(length) : "—:—";
        CompactDurationText.Text = DurationText.Text;
        _isUpdatingProgress = false;
        if (_terminalPlaybackFailure)
        {
            SetPauseButtonState("Play");
            PauseButton.IsEnabled = false;
        }
        else
            SetPauseButtonState(playbackFailed || _unverifiedPlaybackEndHandled
                ? "Retry"
                : _engine.IsPlaying || _engine.IsBuffering ? "Pause" : "Play");
    }

    private void RecordEpisodeCompletion(long position, long? duration)
    {
        if (_currentSeriesAccount is null || _currentSeriesId is null ||
            _nowPlayingChannel?.Source.Kind != StreamKind.Episode) return;
        if (EpisodeCompletion.IsFinished(position, duration, _engine.IsEnded) != _seriesCompletionRecorded)
            SaveResumePosition(force: true);
    }

    private async Task RecoverPlaybackAsync(PlaybackInterruption interruption, long generation)
    {
        if (_recoveryInFlight || generation != Volatile.Read(ref _playbackSessionGeneration) ||
            generation != _activePlaybackSessionGeneration || !_resumeReady ||
            _playbackIntent != PlaybackUserIntent.Playing || _awaitingForwardProgressAfterAction ||
            _currentSource is not { } source)
            return;

        _recoveryInFlight = true;
        _recoveryGeneration = generation;
        var engineName = EngineLabel(_engine);
        var position = _lastGoodPlaybackPositionMilliseconds;
        var status = _engine.HttpStatus;
        var failure = status == 403 ? (PlaybackAttemptResult?)PlaybackAttemptResult.Forbidden : null;
        var action = PlaybackInterruptionPolicy.NextAction(
            interruption, source.Kind, _engine.HasMedia,
            _interruptionBudget.AttemptsUsed, _playbackAttempts, status,
            connectionLimit: status is 403 or 429, failure: failure);
        var expectedGeneration = generation;
        LaunchDiagnostics.Write($"event=playback.interrupt reason={interruption} engine={engineName} positionMs={position}");
        _nextEpisodeAutoPlaySuppressed = true;
        _nextEpisodeAdvanceStarted = true;
        SaveResumePosition(force: true, positionOverride: position);
        try
        {
            if (action == PlaybackRecoveryAction.ResumeSameMedia)
            {
                if (!_interruptionBudget.TryConsume(_playbackAttempts))
                    action = PlaybackRecoveryAction.Stop;
                else
                {
                    var target = Math.Max(0, position - 2_000);
                    var before = _engine.Timeline.PositionMilliseconds;
                    ShowPlaybackRetryProgress($"Reconnecting ({_interruptionBudget.AttemptsUsed} of {_playbackAttempts})…");
                    LaunchDiagnostics.Write($"event=playback.resume attempt={_interruptionBudget.AttemptsUsed} of {_playbackAttempts} positionMs={target}");
                    if (_engine.TryResume(target))
                    {
                        await Task.Delay(TimeSpan.FromSeconds(3));
                        if (generation != Volatile.Read(ref _playbackSessionGeneration) ||
                            _playbackIntent != PlaybackUserIntent.Playing || _awaitingForwardProgressAfterAction) return;
                        var resumed = _engine.Timeline.PositionMilliseconds;
                        if (resumed > Math.Max(target, before))
                        {
                            _lastGoodPlaybackPositionMilliseconds = Math.Max(_lastGoodPlaybackPositionMilliseconds, resumed);
                            _lastInterruptionObservedPosition = Math.Max(_lastInterruptionObservedPosition, resumed);
                            _hasPlaybackAdvancedThisSession = true;
                            _lastForwardProgressAt = Stopwatch.GetElapsedTime(_stallClockStart);
                            _interruptionBudget.RecordForwardProgress(_lastForwardProgressAt);
                            _playbackFailurePolicy.ClearFailure(generation);
                            ClearPlaybackStatus();
                            LaunchDiagnostics.Write($"event=playback.resume.ok positionMs={resumed}");
                            return;
                        }
                    }
                    LaunchDiagnostics.Write($"event=playback.resume.failed positionMs={target}");
                    action = PlaybackRecoveryAction.Reopen;
                }
            }

            if (action == PlaybackRecoveryAction.Reopen && _interruptionBudget.AttemptsUsed < PlaybackRetryPolicy.ClampAttempts(_playbackAttempts))
            {
                var nextAttempt = _interruptionBudget.AttemptsUsed + 1;
                ShowPlaybackRetryProgress($"Reconnecting ({nextAttempt} of {_playbackAttempts})…");
                LaunchDiagnostics.Write($"event=playback.resume attempt={nextAttempt} of {_playbackAttempts} positionMs={Math.Max(0, position - 2_000)}");
                if (_interruptionBudget.AttemptsUsed > 0)
                    await Task.Delay(PlaybackRetryPolicy.InterruptionDelay(_interruptionBudget.AttemptsUsed));
                if (generation != Volatile.Read(ref _playbackSessionGeneration) ||
                    _playbackIntent != PlaybackUserIntent.Playing || _awaitingForwardProgressAfterAction) return;
                var reopenTask = StartPlaybackAsync(
                    source,
                    preserveCompact: _playerPresentation == PlayerPresentation.Compact,
                    isRecovery: true,
                    recoveryPositionMs: source.Kind == StreamKind.Live ? null : Math.Max(0, position - 2_000));
                var reopenGeneration = Volatile.Read(ref _playbackSessionGeneration);
                expectedGeneration = reopenGeneration;
                await reopenTask;
                if (reopenGeneration != Volatile.Read(ref _playbackSessionGeneration))
                    return;
                if (_terminalPlaybackFailure)
                    return;
                if (_playbackIntent != PlaybackUserIntent.Playing || _awaitingForwardProgressAfterAction)
                    return;
                if (_resumeReady && _activePlaybackSessionGeneration == Volatile.Read(ref _playbackSessionGeneration))
                    return;
                action = PlaybackRecoveryAction.Stop;
            }

            if (action == PlaybackRecoveryAction.Stop || _interruptionBudget.AttemptsUsed >= PlaybackRetryPolicy.ClampAttempts(_playbackAttempts))
            {
                LaunchDiagnostics.Write($"event=playback.resume.failed attempts={_interruptionBudget.AttemptsUsed} of {_playbackAttempts}");
                var finalReason = PlaybackFailureClassifier.RefineHttpStatus(
                    PlaybackAttemptResult.UnknownFailure, _engine.HttpStatus);
                ShowFinalPlaybackFailure(finalReason, "Couldn't reconnect", expectedGeneration);
            }
        }
        catch (OperationCanceledException)
        {
            // A new playback session or explicit navigation superseded this recovery.
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"event=playback.resume.failed error={exception.GetType().Name}");
            ShowFinalPlaybackFailure(PlaybackFailureClassifier.FromOpenException(exception), "Couldn't reconnect", expectedGeneration);
        }
        finally
        {
            if (_recoveryGeneration == generation)
            {
                _recoveryInFlight = false;
                _recoveryGeneration = -1;
            }
        }
    }

    private void SaveResumePosition(bool force = false, long? positionOverride = null, bool forceInProgress = false)
    {
        var sessionReady = _resumeReady && _activePlaybackSessionGeneration == Volatile.Read(ref _playbackSessionGeneration);
        var resumeEligible = _nowPlayingChannel?.Source.Kind == StreamKind.Movie ||
            (_nowPlayingChannel?.Source.Kind == StreamKind.Episode && _currentSeriesId is not null);
        if ((!force && _isHeldSeeking) ||
            !PlayerPresentationPolicy.ShouldSaveProgress(_playerPresentation, sessionReady, resumeEligible) ||
            _nowPlayingChannel is not { } channel ||
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
        var timeline = _engine.Timeline;
        var duration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
        var ended = _engine.IsEnded;
        var position = ended
            ? positionOverride is { } endPosition
                ? ClampPosition(endPosition, duration)
                : _lastGoodPlaybackPositionMilliseconds
            : Math.Max(_lastGoodPlaybackPositionMilliseconds,
                ClampPosition(positionOverride ?? timeline.PositionMilliseconds, duration));
        if (!ended || positionOverride.HasValue)
            _lastGoodPlaybackPositionMilliseconds = position;
        if (type == CatalogItemType.Movie)
        {
            var failed = _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
            var movieFinished = !forceInProgress && !failed && MovieCompletion.IsFinished(position, duration, ended);
            _catalogRepository.SaveProgress(account, "movie", id, null, position, duration, movieFinished);
            _movieCompletionRecorded = movieFinished;
            if (_playerEntries.FirstOrDefault(row => row.Channel.Id == id) is { } playingMovieRow)
                playingMovieRow.SetProgress(new PlaybackProgress(id,
                    movieFinished ? PlaybackProgressState.Finished : PlaybackProgressState.InProgress,
                    movieFinished ? 0 : position, duration));
        }
        else if (channel.Source.Kind == StreamKind.Episode && _currentSeriesId is not null)
        {
            var failed = _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
            var finished = !forceInProgress && !failed && EpisodeCompletion.IsFinished(position, duration, ended);
            var completionChanged = finished != _seriesCompletionRecorded;
            _catalogRepository.SaveProgress(account, "episode", channel.Id, _currentSeriesId, position, duration, finished);
            _seriesCompletionRecorded = finished;
            // Keep the row bar in step with what was just saved, without re-reading the database.
            if (_playerEntries.FirstOrDefault(row => row.Channel.Id == channel.Id) is { } playingRow)
                playingRow.SetProgress(new PlaybackProgress(channel.Id,
                    finished ? PlaybackProgressState.Finished : PlaybackProgressState.InProgress, finished ? 0 : position, duration));
            if (completionChanged)
            {
                _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(account, _currentSeriesId);
                foreach (var row in _playerEntries)
                    row.SetProgress(_seriesEpisodeProgress.TryGetValue(row.Channel.Id, out var progress) ? progress : null);
                RefreshSeasonProgressCount();
            }
        }
        else return;
        LaunchDiagnostics.Write($"event=resume.save kind={type} positionMs={position} completed={(!forceInProgress && _engine.IsEnded).ToString().ToLowerInvariant()}");
        _lastResumeSavedAt = now;
    }

    private static long ClampPosition(long position, long? duration) =>
        duration is > 0 ? Math.Clamp(position, 0, duration.Value) : Math.Max(0, position);

    private static string FormatTime(long milliseconds)
    {
        var time = TimeSpan.FromMilliseconds(Math.Max(0, milliseconds));
        return time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss") : time.ToString(@"m\:ss");
    }

    private void ProgressSlider_PointerPressed(object sender, PointerRoutedEventArgs args) => _isDraggingProgress = true;

    private void ProgressSlider_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        _isDraggingProgress = false;
        if (SeekTo((long)ProgressSlider.Value) is { } target)
            SaveResumePosition(force: true, positionOverride: target);
    }

    private void ProgressSlider_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (_isDraggingProgress)
        {
            _isDraggingProgress = false;
            if (SeekTo((long)ProgressSlider.Value) is { } target)
                SaveResumePosition(force: true, positionOverride: target);
        }
    }

    private void ProgressSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (!_isUpdatingProgress && !_isDraggingProgress)
            SeekTo((long)args.NewValue);
    }

    private void CompactProgressSlider_PointerPressed(object sender, PointerRoutedEventArgs args) => _isDraggingProgress = true;

    private void CompactProgressSlider_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        _isDraggingProgress = false;
        if (SeekTo((long)CompactProgressSlider.Value) is { } target)
            SaveResumePosition(force: true, positionOverride: target);
    }

    private void CompactProgressSlider_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (!_isDraggingProgress) return;
        _isDraggingProgress = false;
        if (SeekTo((long)CompactProgressSlider.Value) is { } target)
            SaveResumePosition(force: true, positionOverride: target);
    }

    private void CompactProgressSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (_isUpdatingProgress || CompactElapsedText is null) return;
        if (_isDraggingProgress)
            CompactElapsedText.Text = FormatTime((long)args.NewValue);
        else
            SeekTo((long)args.NewValue);
    }

    private void RewindButton_Click(object sender, RoutedEventArgs args) => SeekButtonClick(-1);

    private void ForwardButton_Click(object sender, RoutedEventArgs args) => SeekButtonClick(1);

    private void SeekButton_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (sender is not Button button) return;
        var timeline = _engine.Timeline;
        if (!timeline.CanSeek || _currentSource is null) return;
        _suppressHeldSeekClick = false;
        _heldSeekButton = button;
        _heldSeekDirection = ReferenceEquals(button, RewindButton) || ReferenceEquals(button, CompactRewindButton) ? -1 : 1;
        _heldSeekTarget = ClampHeldSeekTarget(timeline.PositionMilliseconds, timeline.DurationMilliseconds);
        _heldSeekStartedAt = DateTimeOffset.UtcNow;
        _heldSeekLastStepAt = _heldSeekStartedAt - TimeSpan.FromMilliseconds(250);
        _heldSeekDidScrub = false;
        _heldSeekTimer.Start();
    }

    private void SeekButton_PointerReleased(object sender, PointerRoutedEventArgs args) =>
        StopHeldSeek(commit: true, released: true);

    private void SeekButton_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        if (ReferenceEquals(sender, _heldSeekButton))
            StopHeldSeek(commit: true, released: false);
    }

    private void SeekButton_PointerCaptureLost(object sender, PointerRoutedEventArgs args)
    {
        if (ReferenceEquals(sender, _heldSeekButton))
            StopHeldSeek(commit: true, released: false);
    }

    private void SeekButton_LostFocus(object sender, RoutedEventArgs args)
    {
        if (ReferenceEquals(sender, _heldSeekButton))
            StopHeldSeek(commit: true, released: false);
    }

    private void MainWindow_Activated(object sender, WindowActivatedEventArgs args)
    {
        if (args.WindowActivationState != WindowActivationState.Deactivated)
            EvaluateSleepTimer();
        // Activation must NOT leave window-mini: clicking the mini window to drag or resize it
        // activates it. Leaving is explicit only (Expand, Close, Escape, double-click, playback end).
        if (_windowState.IsWindowMini)
            _windowState.EnsureWindowMiniTopmost();
        if (args.WindowActivationState == WindowActivationState.Deactivated)
        {
            _cinemaWindowActive = false;
            StopHeldSeek(commit: true, released: false);
            if (_isCinemaMode) StopCinemaCursorIdleTimer(showCursor: true);
        }
        else
        {
            _cinemaWindowActive = true;
            if (_isCinemaMode && _currentPage == ShellPage.Player && (_engine.IsPlaying || _engine.IsBuffering))
                ShowCinemaControls(resetIdleDeadline: true);
        }
    }

    private void VlcEngine_StateChanged(object? sender, VlcPlaybackStateChangedEventArgs args)
    {
        if (_sleepTimerEnded || args.State is not (VlcPlaybackState.Failed or VlcPlaybackState.Completed) ||
            !ReferenceEquals(sender, _vlcEngine) || !ReferenceEquals(_engine, _vlcEngine) ||
            _vlcEngine.CurrentSession != args.Session) return;
        var callbackGeneration = Volatile.Read(ref _activePlaybackSessionGeneration);
        WindowRoot.DispatcherQueue.TryEnqueue(() =>
        {
            if (_vlcEngine.CurrentSession != args.Session ||
                callbackGeneration != Volatile.Read(ref _playbackSessionGeneration) ||
                callbackGeneration != _activePlaybackSessionGeneration || !_resumeReady || _sleepTimerEnded) return;
            if (args.State == VlcPlaybackState.Failed && !_playbackFailurePolicy.TryHandle(
                    callbackGeneration, callbackGeneration, _activePlaybackSessionGeneration, _resumeReady)) return;
            var timeline = _engine.Timeline;
            var interruption = PlaybackInterruptionPolicy.Classify(new PlaybackInterruptionSnapshot(
                args.State == VlcPlaybackState.Failed ? PlaybackEngineState.Failed : PlaybackEngineState.Ended,
                args.State == VlcPlaybackState.Failed ? PlaybackAttemptResult.UnknownFailure : null,
                Stopwatch.GetElapsedTime(_stallClockStart) - _lastForwardProgressAt,
                _lastGoodPlaybackPositionMilliseconds > 0,
                _lastGoodPlaybackPositionMilliseconds,
                timeline.DurationMilliseconds,
                _playbackIntent,
                _awaitingForwardProgressAfterAction,
                callbackGeneration,
                Volatile.Read(ref _playbackSessionGeneration),
                _engine.HttpStatus));
            if (interruption != PlaybackInterruption.None)
                _ = RecoverPlaybackAsync(interruption, callbackGeneration);
        });
    }

    private void NativeEngine_PlaybackFailed(PlaybackSessionToken session)
    {
        if (_sleepTimerEnded || !ReferenceEquals(_engine, _nativeEngine) || _nativeEngine.CurrentSession != session) return;
        var callbackGeneration = Volatile.Read(ref _activePlaybackSessionGeneration);
        WindowRoot.DispatcherQueue.TryEnqueue(() =>
        {
            if (callbackGeneration != Volatile.Read(ref _playbackSessionGeneration) ||
                callbackGeneration != _activePlaybackSessionGeneration || !_resumeReady || _sleepTimerEnded) return;
            if (!_playbackFailurePolicy.TryHandle(
                    callbackGeneration, callbackGeneration, _activePlaybackSessionGeneration, _resumeReady)) return;
            _ = RecoverPlaybackAsync(PlaybackInterruption.Error, callbackGeneration);
        });
    }

    private void HeldSeekTimer_Tick(object? sender, object args)
    {
        if (!_isClosing && IsPlaybackUiActive && _currentSource is not null &&
            _engine.Timeline.CanSeek && !_engine.IsEnded && _heldSeekButton is not null)
        {
            var now = DateTimeOffset.UtcNow;
            var heldDuration = now - _heldSeekStartedAt;
            var step = HeldSeekAccelerator.StepMilliseconds(heldDuration);
            if (step > 0 && now - _heldSeekLastStepAt >= TimeSpan.FromMilliseconds(250))
            {
                var timeline = _engine.Timeline;
                _heldSeekTarget = ClampHeldSeekTarget(
                    _heldSeekTarget + _heldSeekDirection * step, timeline.DurationMilliseconds);
                _heldSeekLastStepAt = now;
                _heldSeekDidScrub = true;
                _isHeldSeeking = true;
                var seekIntent = PlaybackInterruptionPolicy.IntentAfterSeek(_playbackIntent);
                SetPlaybackIntent(seekIntent, awaitForwardProgress: seekIntent == PlaybackUserIntent.Playing,
                    baselinePosition: _heldSeekTarget);
                _engine.Seek(_heldSeekTarget);
            }
            if (_heldSeekDidScrub)
                ShowHeldSeekTarget();
            return;
        }
        StopHeldSeek(commit: true, released: false);
    }

    private void ShowHeldSeekTarget()
    {
        var duration = _engine.Timeline.DurationMilliseconds;
        _isUpdatingProgress = true;
        try
        {
            var maximum = Math.Max(duration ?? _heldSeekTarget, 1);
            ProgressSlider.Maximum = maximum;
            ProgressSlider.Value = Math.Clamp(_heldSeekTarget, 0, maximum);
            CompactProgressSlider.Maximum = maximum;
            CompactProgressSlider.Value = ProgressSlider.Value;
            ElapsedText.Text = FormatTime(_heldSeekTarget);
        }
        finally
        {
            _isUpdatingProgress = false;
        }
    }

    private void StopHeldSeek(bool commit, bool released)
    {
        if (_heldSeekButton is null) return;
        _heldSeekTimer.Stop();
        var didScrub = _heldSeekDidScrub;
        var target = _heldSeekTarget;
        _heldSeekButton = null;
        _heldSeekDidScrub = false;
        _isHeldSeeking = false;
        if (didScrub)
            _suppressHeldSeekClick = true;
        if (commit && didScrub && IsPlaybackUiActive && _currentSource is not null)
        {
            if (SeekTo(target) is { } committedTarget)
                SaveResumePosition(force: true, positionOverride: committedTarget);
        }
    }

    private static long ClampHeldSeekTarget(long target, long? duration) =>
        duration is > 0 ? Math.Clamp(target, 0, Math.Max(0, duration.Value - 1000)) : Math.Max(0, target);

    private void SeekButtonClick(int direction)
    {
        if (_suppressHeldSeekClick)
        {
            _suppressHeldSeekClick = false;
            return;
        }
        SeekBy(direction * 10_000L);
    }

    private void SeekBy(long offsetMilliseconds)
    {
        var timeline = _engine.Timeline;
        if (!timeline.CanSeek || _currentSource is null ||
            !(_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded))
            return;

        if (SeekTo(timeline.PositionMilliseconds + offsetMilliseconds) is { } target)
            SaveResumePosition(force: true, positionOverride: target);
    }

    private long? SeekTo(long targetMilliseconds)
    {
        var timeline = _engine.Timeline;
        if (!timeline.CanSeek || _currentSource is null ||
            !(_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused || _engine.IsEnded))
            return null;

        if (timeline.ClampSeekTarget(targetMilliseconds) is { } target)
        {
            var seekIntent = PlaybackInterruptionPolicy.IntentAfterSeek(_playbackIntent);
            SetPlaybackIntent(seekIntent, awaitForwardProgress: seekIntent == PlaybackUserIntent.Playing,
                baselinePosition: target);
            _engine.Seek(target);
            if (timeline.DurationMilliseconds is { } duration &&
                duration - target > NextPrompt.LeadMilliseconds(_nowPlayingChannel?.Source.Kind ?? StreamKind.Live, duration))
                NextEpisodePrompt.Visibility = Visibility.Collapsed;
            return target;
        }
        return null;
    }

    private void VolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (_isUpdatingVolume) return;
        UnmuteIfMuted();
        _engine.Volume = (int)args.NewValue;
        if (CompactVolumeSlider is null) return;
        _isUpdatingVolume = true;
        try { CompactVolumeSlider.Value = args.NewValue; }
        finally { _isUpdatingVolume = false; }
    }

    private void CompactVolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs args)
    {
        if (!_isUpdatingVolume && VolumeSlider is not null)
            VolumeSlider.Value = args.NewValue;
    }

    [DllImport("user32.dll")]
    private static extern uint GetDoubleClickTime();

    private long _volumeFlyoutOpenedAtTick;

    private void VolumeFlyout_Opened(object? sender, object args)
    {
        _volumeFlyoutOpenedAtTick = Environment.TickCount64;
        if (_isCinemaMode) ShowCinemaControls();
    }

    // The first click of a double-click opens the flyout and its light-dismiss layer swallows the second
    // click, so DoubleTapped never fires on the button. A flyout dismissed by a click on the button within
    // the system double-click time of opening is that second click: toggle mute.
    private void VolumeFlyout_Closed(object? sender, object args)
    {
        if (Environment.TickCount64 - _volumeFlyoutOpenedAtTick <= GetDoubleClickTime() && CursorIsOverVolumeButton())
            ToggleMute();
        if (_isCinemaMode) ScheduleCinemaIdleCheck();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint { public int X, Y; }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    private static extern bool ScreenToClient(nint hwnd, ref NativePoint point);

    // The pointer enter/exit flags can be stale right after a layout change, so ask the OS where the
    // cursor is and hit-test the button's bounds directly.
    private bool CursorIsOverVolumeButton()
    {
        if (!GetCursorPos(out var point)) return false;
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        if (!ScreenToClient(hwnd, ref point)) return false;
        var scale = VolumeButton.XamlRoot?.RasterizationScale ?? 1.0;
        var bounds = VolumeButton.TransformToVisual(null).TransformBounds(
            new Windows.Foundation.Rect(0, 0, VolumeButton.ActualWidth, VolumeButton.ActualHeight));
        return bounds.Contains(new Windows.Foundation.Point(point.X / scale, point.Y / scale));
    }

    private void VolumeButton_DoubleTapped(object sender, DoubleTappedRoutedEventArgs args)
    {
        ToggleMute();
        VolumeFlyout.Hide();
        args.Handled = true;
    }

    private void ToggleMute_Click(object sender, RoutedEventArgs args) => ToggleMute();

    /// <summary>Touching the volume control while muted brings the sound back.</summary>
    private void UnmuteIfMuted()
    {
        if (_playbackMuteState.IsMuted) ToggleMute();
    }

    private void VolumeSlider_PointerPressed(object sender, PointerRoutedEventArgs args) => UnmuteIfMuted();

    private void ToggleMute()
    {
        _playbackMuteState = _playbackMuteState.Toggle();
        _engine.IsMuted = _playbackMuteState.IsMuted;
        SetMutePresentation();
    }

    private void SetMutePresentation()
    {
        var muted = _playbackMuteState.IsMuted;
        var glyph = muted ? "\uE74F" : "\uE767";
        VolumeButton.Content = glyph;
        CompactMuteButton.Content = glyph;
        VolumeStateText.Text = muted ? "Muted" : "";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(VolumeButton, muted ? "Unmute" : "Volume");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(CompactMuteButton, muted ? "Unmute" : "Mute");
        ToolTipService.SetToolTip(VolumeButton, muted ? "Unmute or adjust volume" : "Volume");
        ToolTipService.SetToolTip(CompactMuteButton, muted ? "Unmute" : "Mute");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetHelpText(VolumeSlider,
            muted ? "Muted; the slider retains the selected volume." : "Volume level");
    }

    private void AdjustVolume(int deltaPercent) =>
        VolumeSlider.Value = Math.Clamp(VolumeSlider.Value + deltaPercent, VolumeSlider.Minimum, VolumeSlider.Maximum);

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

    private void PlayRelatedChannel(Channel channel, bool preserveCompact = false, bool autoplayNext = false)
    {
        if (channel.Source.DirectUri is null || channel.Source.Kind is not (StreamKind.Movie or StreamKind.Live)) return;
        if (autoplayNext && ShouldStopBeforeStartingNextItem()) return;
        if (!autoplayNext) CancelEndOfCurrentItemTimerForManualChange(channel);
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
        _seriesEpisodeProgress = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonWatchedCount.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        SeriesFavoriteButton.Visibility = Visibility.Collapsed;

        if (_catalogLandingPage.Account is { } account)
        {
            QueueVisit(account,
                channel.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live,
                channel.Id);
        }

        _nowPlayingChannel = channel;
        _resumePositionOnStart = null;
        _currentSource = channel.Source;
        UpdatePlaylistOptionControls(channel.Source.Kind);
        _nextEpisodeAutoPlaySuppressed = false;
        _nextEpisodeAdvanceStarted = false;
        NextEpisodePrompt.Visibility = Visibility.Collapsed;
        UpdateEpisodeNavigationButtons();
        PlayerTitleText.Text = channel.DisplayName;
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        _playbackCompletionShown = false;
        SetCurrentPlayerEntry(channel);
        _ = LoadPlayerMetadataAsync(channel,
            channel.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live);
        _ = StartPlaybackAsync(channel.Source, preserveCompact,
            entryKind: autoplayNext ? PlayerResetEntryKind.AutoplayNext : PlayerResetEntryKind.NewItem);
        if (channel.Source.Kind == StreamKind.Movie && _playerSiblings.Count <= 1)
            _ = LoadMovieCategorySiblingsAsync(channel, Volatile.Read(ref _itemSelectionGeneration));
    }

    private void PlayerBackButton_Click(object sender, RoutedEventArgs args) => ReturnFromPlayer();

    private void PlayerHomeButton_Click(object sender, RoutedEventArgs args) =>
        ShowPage(ShellPage.Home);

    private void ReturnFromPlayer()
    {
        CancelSleepTimer("back");
        SetPlaybackIntent(PlaybackUserIntent.StoppedByUser, awaitForwardProgress: false);
        BeginBackTransitionTrace($"return-from-player input returnPage={_playerReturnPage} cinema={_isCinemaMode} bounds={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");
        if (_playerReturnPage == ShellPage.Catalog)
        {
            _ = ShowCatalogAsync(_catalogLandingPage.ActiveMode);
        }
        else ShowPage(_playerReturnPage);
    }

    private void MiniPlayerButton_Click(object sender, RoutedEventArgs args)
    {
        if (ReferenceEquals(_engine, _nativeEngine) || _currentSource is null) return;
        if (_isCinemaMode)
        {
            EnterWindowMiniPlayer(explicitCinemaButton: true);
            return;
        }
        _playerReturnPage = ShellPage.Catalog;
        SetPlayerPresentation(PlayerPresentation.Compact);
        ShowPage(ShellPage.Catalog);
        CompactTitleText.Text = PlayerTitleText.Text;
        CompactPauseButton.Focus(FocusState.Programmatic);
    }

    private void CompactExpandButton_Click(object sender, RoutedEventArgs args)
    {
        if (_windowState.IsWindowMini)
        {
            ExitWindowMiniPlayer(expandToPlayer: true);
            PauseButton.Focus(FocusState.Programmatic);
            return;
        }
        SetPlayerPresentation(PlayerPresentation.Full);
        ShowPage(ShellPage.Player);
        PauseButton.Focus(FocusState.Programmatic);
    }

    private void CompactCloseButton_Click(object sender, RoutedEventArgs args)
    {
        if (_windowState.IsWindowMini)
        {
            var destination = _windowMiniPreviousPage == ShellPage.Player
                ? _playerReturnPage
                : _windowMiniPreviousPage ?? _playerReturnPage;
            ExitWindowMiniPlayer(
                expandToPlayer: false,
                afterExit: () => StopCompactPlaybackAndNavigate(destination, windowMiniClose: true),
                deferPageRestore: true);
            return;
        }
        StopHeldSeek(commit: true, released: false);
        SetPlayerPresentation(PlayerPresentation.Full);
        ShowPage(ShellPage.Player);
        PauseButton.Focus(FocusState.Programmatic);
    }

    private void CompactMinimizeButton_Click(object sender, RoutedEventArgs args) => _windowState.Minimize();

    private void HandleWindowMiniModeRequest(WindowStateController.WindowMiniModeRequest request)
    {
        if (!_windowState.IsWindowMini) return;
        if (request == WindowStateController.WindowMiniModeRequest.Expand)
        {
            ExitWindowMiniPlayer(expandToPlayer: true);
            return;
        }

        ExitWindowMiniPlayer(expandToPlayer: true, afterExit: () =>
        {
            switch (request)
            {
                case WindowStateController.WindowMiniModeRequest.Windowed:
                    if (_windowState.Mode == WindowStateController.WindowMode.Fullscreen)
                        _windowState.RequestWindowMode(WindowStateController.WindowMode.Windowed);
                    else
                        _windowState.RestoreWindow();
                    break;
                case WindowStateController.WindowMiniModeRequest.Fullscreen:
                    _windowState.RequestWindowMode(WindowStateController.WindowMode.Fullscreen);
                    break;
                case WindowStateController.WindowMiniModeRequest.Maximize:
                    _windowState.MaximizeWindow();
                    break;
                case WindowStateController.WindowMiniModeRequest.Restore:
                    if (_windowState.Mode == WindowStateController.WindowMode.Fullscreen)
                        _windowState.RequestWindowMode(WindowStateController.WindowMode.Windowed);
                    else
                        _windowState.RestoreWindow();
                    break;
            }
            ApplyWindowChromeState();
        });
    }

    private void ForceExitWindowMini(string reason)
    {
        if (!_windowState.IsWindowMini || _windowMiniTransitionInProgress) return;
        LaunchDiagnostics.Write($"event=window-mini.forced-exit reason={reason}");
        Action? applyDetectedMode = reason.EndsWith(":maximized", StringComparison.Ordinal)
            ? () => _windowState.MaximizeWindow()
            : reason.EndsWith(":fullscreen", StringComparison.Ordinal)
                ? () => _windowState.RequestWindowMode(WindowStateController.WindowMode.Fullscreen)
                : null;
        ExitWindowMiniPlayer(expandToPlayer: true, afterExit: applyDetectedMode);
    }

    private void EnterWindowMiniPlayer(bool explicitCinemaButton = false)
    {
        var eligible = explicitCinemaButton && _isCinemaMode
            ? ShouldExplicitCinemaMiniButtonBecomeWindowMini()
            : ShouldMinimizeBecomeWindowMini();
        if (!eligible || _windowState.IsWindowMini || _windowMiniTransitionInProgress) return;
        StopHeldSeek(commit: true, released: false);
        var leavingCinema = _isCinemaMode;
        if (leavingCinema) StopCinemaCursorIdleTimer(showCursor: true);
        _windowMiniPreviousPage = leavingCinema ? ShellPage.Player : _currentPage;
        _windowMiniPreviousPresentation = leavingCinema ? PlayerPresentation.Full : _playerPresentation;
        // Mini expands back to the normal player. Cinema is restored only when explicitly
        // requested later (for example, by the mini title's double-click-to-cinema path).
        _windowMiniPreviousCinema = false;
        _windowMiniGeometryAtEntry = _windowState.ClampWindowMiniGeometry(_windowMiniGeometry);
        RunWindowMiniTransition(() =>
        {
            if (leavingCinema) ApplyCinemaMode(false);
            SetPlayerPresentation(PlayerPresentation.Compact);
            _windowMiniGeometry = _windowState.EnterWindowMini(_windowMiniGeometryAtEntry);
            _windowMiniGeometryDirty = false;
            ApplyWindowMiniLayout();
            LaunchDiagnostics.Write($"event=window-mini.enter kind={_currentSource?.Kind} engine=LibVLC bounds={_windowMiniGeometry}");
        });
    }

    private void ApplyWindowMiniLayout()
    {
        WindowChrome.Visibility = Visibility.Collapsed;
        MainHeader.Visibility = Visibility.Collapsed;
        WindowRoot.RowDefinitions[0].Height = new GridLength(0);
        WindowRoot.RowDefinitions[1].Height = new GridLength(0);
        PageHost.Visibility = Visibility.Collapsed;
        CatalogPageArea.Visibility = Visibility.Collapsed;
        PlayerPage.Visibility = Visibility.Visible;
        PlayerPage.Width = double.NaN;
        PlayerPage.Height = double.NaN;
        PlayerPage.HorizontalAlignment = HorizontalAlignment.Stretch;
        PlayerPage.VerticalAlignment = VerticalAlignment.Stretch;
        PlayerPage.Margin = new Thickness(0);
        CompactChrome.Visibility = Visibility.Visible;
        SetCompactResizeZonesVisible(false);
        ApplyPlayerLayout();
        ApplyCompactGeometryToLayout();
        PlayerPage.UpdateLayout();
    }

    private void ExitWindowMiniPlayer(bool expandToPlayer, bool enterCinema = false, Action? afterExit = null,
        bool deferPageRestore = false)
    {
        if (!_windowState.IsWindowMini || _windowMiniTransitionInProgress) return;
        RunWindowMiniTransition(() =>
        {
            _windowMiniGeometry = _windowState.GetCurrentWindowMiniGeometry();
            _windowMiniGeometryDirty = _windowMiniGeometry != _windowMiniGeometryAtEntry;
            _windowState.ExitWindowMini();
            SaveWindowMiniGeometryIfChanged();
            WindowChrome.Visibility = Visibility.Visible;
            MainHeader.Visibility = Visibility.Visible;
            WindowRoot.RowDefinitions[1].Height = new GridLength(72);
            SetCompactResizeZonesVisible(true);
            _currentPage = null;
            if (expandToPlayer)
            {
                SetPlayerPresentation(PlayerPresentation.Full);
                ShowPage(ShellPage.Player);
                if (enterCinema || _windowMiniPreviousCinema) SetCinemaMode(true, animate: false);
                else PauseButton.Focus(FocusState.Programmatic);
            }
            else if (!deferPageRestore)
            {
                SetPlayerPresentation(_windowMiniPreviousPresentation);
                ShowPage(_windowMiniPreviousPage ?? ShellPage.Player);
                if (enterCinema || _windowMiniPreviousCinema) SetCinemaMode(true, animate: false);
            }
            _windowMiniPreviousPage = null;
            ApplyWindowChromeState();
            LaunchDiagnostics.Write($"event=window-mini.exit expand={expandToPlayer} page={_currentPage}");
            afterExit?.Invoke();
        });
    }

    private void StopCompactPlaybackAndNavigate(ShellPage destination, bool windowMiniClose = false)
    {
        CancelSleepTimer(windowMiniClose ? "mini-close" : "back");
        StopHeldSeek(commit: false, released: false);
        SetPlayerPresentation(PlayerPresentation.Full);
        StopPlaybackForNavigation();

        // Recompute every page's visibility after changing presentation and tearing down playback.
        // The window-mini close route leaves _currentPage unset until this final layout is applied.
        _currentPage = null;
        ShowPage(destination);
        if (windowMiniClose)
            LaunchDiagnostics.Write($"event=window-mini.close-teardown destination={destination} playerVisible={PlayerPage.Visibility}");
    }

    private void SetCompactResizeZonesVisible(bool visible)
    {
        var visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        CompactResizeEdgeLeft.Visibility = visibility;
        CompactResizeEdgeTop.Visibility = visibility;
        CompactResizeEdgeRight.Visibility = visibility;
        CompactResizeEdgeBottom.Visibility = visibility;
        CompactResizeCornerTopLeft.Visibility = visibility;
        CompactResizeCornerTopRight.Visibility = visibility;
        CompactResizeCornerBottomLeft.Visibility = visibility;
        CompactResizeCornerBottomRight.Visibility = visibility;
    }

    private void RunWindowMiniTransition(Action apply)
    {
        _windowMiniTransitionInProgress = true;
        var visual = ElementCompositionPreview.GetElementVisual(PlayerPage);
        var compositor = visual.Compositor;
        var dispatcherQueue = WindowRoot.DispatcherQueue;
        visual.StopAnimation(nameof(Visual.Opacity));
        var fadeOut = compositor.CreateScalarKeyFrameAnimation();
        fadeOut.InsertKeyFrame(1f, 0f);
        fadeOut.Duration = TimeSpan.FromMilliseconds(90);
        var batch = compositor.CreateScopedBatch(CompositionBatchTypes.Animation);
        batch.Completed += (_, _) => dispatcherQueue.TryEnqueue(() =>
        {
            using var perfScope = PerfScope.Begin("WindowMiniTransitionApply");
            try
            {
                apply();
                PlayerPage.Opacity = 1;
                WindowRoot.UpdateLayout();
                FadeIn(PlayerPage);
            }
            finally
            {
                _windowMiniTransitionInProgress = false;
            }
        });
        visual.StartAnimation(nameof(Visual.Opacity), fadeOut);
        batch.End();
    }

    private void SetPlayerPresentation(PlayerPresentation presentation)
    {
        if (presentation == PlayerPresentation.Compact && ReferenceEquals(_engine, _nativeEngine))
            return;
        _playerPresentation = presentation;
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(PlayerPage,
            presentation == PlayerPresentation.Compact ? "Mini player card" : "Player");
        CompactChrome.Visibility = presentation == PlayerPresentation.Compact ? Visibility.Visible : Visibility.Collapsed;
        PlayerPage.Background = presentation == PlayerPresentation.Compact
            ? ThemeBrush("AppPanelSurfaceBrush")
            : null;
        if (presentation == PlayerPresentation.Compact)
        {
            _cinemaCursorIdleTimer.Interval = TimeSpan.FromSeconds(3);
            ClampAndApplyCompactGeometry();
            CompactTitleText.Text = PlayerTitleText.Text;
            RefreshAudioSubtitlesButton();
            ShowCompactControls();
        }
        else
        {
            _cinemaCursorIdleTimer.Stop();
            SetCompactControlsVisible(true);
            PlayerPage.Width = double.NaN;
            PlayerPage.Height = double.NaN;
            PlayerPage.HorizontalAlignment = HorizontalAlignment.Stretch;
            PlayerPage.VerticalAlignment = VerticalAlignment.Stretch;
            PlayerPage.Margin = new Thickness(0);
        }
        ApplyPlayerLayout();
    }

    private static PlayerPageKind ToPlayerPageKind(ShellPage page) => page switch
    {
        ShellPage.Player => PlayerPageKind.Player,
        ShellPage.Catalog => PlayerPageKind.Catalog,
        _ => PlayerPageKind.Other,
    };

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
        if (_playerPresentation == PlayerPresentation.Compact)
        {
            ShowCompactControls();
            return;
        }
        // Deliberately leave the event unhandled so a normal click reaches the playback surface.
        var currentPoint = args.GetCurrentPoint(sender as UIElement);
        if (!currentPoint.Properties.IsLeftButtonPressed)
            return;
        RegisterVideoClick(currentPoint.Position, compact: false);
    }

    private void RegisterVideoClick(Windows.Foundation.Point point, bool compact)
    {
        var now = DateTimeOffset.UtcNow;
        var delta = point.X - _lastVideoTapPoint.X;
        var deltaY = point.Y - _lastVideoTapPoint.Y;
        if (_lastVideoTapWasCompact == compact &&
            now - _lastVideoTapAt <= TimeSpan.FromMilliseconds(500) &&
            delta * delta + deltaY * deltaY <= 24 * 24)
        {
            // The first click of this double-click has a pending pause toggle; drop it so a
            // double-click does not pause and resume the video.
            _videoClickPauseTimer?.Stop();
            if (_playerPresentation == PlayerPresentation.Compact)
            {
                if (_windowState.IsWindowMini) ExitWindowMiniPlayer(expandToPlayer: true, enterCinema: true);
                else
                {
                    CompactExpandButton_Click(this, new RoutedEventArgs());
                    SetCinemaMode(true);
                }
            }
            else
                SetCinemaMode(!_isCinemaMode);
            _lastVideoTapAt = default;
            return;
        }

        _lastVideoTapAt = now;
        _lastVideoTapPoint = point;
        _lastVideoTapWasCompact = compact;

        // A single click pauses/resumes, but only once the double-click window has passed.
        _videoClickPauseTimer ??= CreateVideoClickPauseTimer();
        _videoClickPauseTimer.Stop();
        _videoClickPauseTimer.Start();
    }

    private Microsoft.UI.Dispatching.DispatcherQueueTimer CreateVideoClickPauseTimer()
    {
        var timer = WindowRoot.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(520);
        timer.IsRepeating = false;
        timer.Tick += (_, _) =>
        {
            if (IsPlaybackUiActive)
                PauseButton_Click(this, new RoutedEventArgs());
        };
        return timer;
    }

    private void VideoSurface_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        // Keep pointer release available to the underlying playback implementation.
    }

    private void SetCinemaMode(bool enabled, bool animate = true)
    {
        if (_windowState.IsWindowMini)
        {
            ExitWindowMiniPlayer(expandToPlayer: true, enterCinema: enabled);
            return;
        }
        if (!animate)
        {
            _cinemaTransitionGeneration++;
            _cinemaTransitionInProgress = false;
            var playerVisual = ElementCompositionPreview.GetElementVisual(PlayerPage);
            playerVisual.StopAnimation(nameof(Visual.Opacity));
            playerVisual.Opacity = 1;
            if (_isCinemaMode != enabled)
                ApplyCinemaMode(enabled);
            PlayerPage.UpdateLayout();
            WindowRoot.UpdateLayout();
            return;
        }

        if (_isCinemaMode == enabled || _cinemaTransitionInProgress)
            return;

        _cinemaTransitionInProgress = true;
        var transitionGeneration = ++_cinemaTransitionGeneration;
        BeginBackTransitionTrace($"cinema-layout-enter enabled={enabled} windowMode={_windowState.Mode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0} {VideoSurfaceState()}");

        var visual = ElementCompositionPreview.GetElementVisual(PlayerPage);
        var compositor = visual.Compositor;
        var dispatcherQueue = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        visual.StopAnimation(nameof(Visual.Opacity));
        var fadeOut = compositor.CreateScalarKeyFrameAnimation();
        fadeOut.InsertKeyFrame(1f, 0f);
        fadeOut.Duration = TimeSpan.FromMilliseconds(90);
        var batch = compositor.CreateScopedBatch(CompositionBatchTypes.Animation);
        batch.Completed += (_, _) => dispatcherQueue.TryEnqueue(() =>
        {
            using var perfScope = PerfScope.Begin("CinemaTransitionApply");
            if (transitionGeneration != _cinemaTransitionGeneration)
                return;
            ApplyCinemaMode(enabled);
            PlayerPage.UpdateLayout();
            WindowRoot.UpdateLayout();
            TraceBackTransition($"cinema-layout-ready enabled={enabled} windowMode={_windowState.Mode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0} {VideoSurfaceState()}");
            _cinemaTransitionInProgress = false;
            FadeIn(PlayerPage);
        });
        visual.StartAnimation(nameof(Visual.Opacity), fadeOut);
        batch.End();
    }

    private void ApplyCinemaMode(bool enabled)
    {
        TraceBackTransition($"cinema-layout-apply enabled={enabled} windowMode={_windowState.Mode} root={WindowRoot.ActualWidth:0.0}x{WindowRoot.ActualHeight:0.0}");

        if (enabled)
        {
            _preCinemaWindowMode = _windowState.EffectiveMode;
            _preCinemaFocus = FocusManager.GetFocusedElement(WindowRoot.XamlRoot) as FrameworkElement;
            _windowState.RequestWindowMode(WindowStateController.WindowMode.Fullscreen);
        }

        _isCinemaMode = enabled;
        _playerPresentation = enabled ? PlayerPresentation.Cinema : PlayerPresentation.Full;
        _cinemaPointerMoveDiagnosticWritten = false;
        ApplyWindowChromeState();
        MainHeader.Visibility = enabled ? Visibility.Collapsed : Visibility.Visible;
        ((Grid)Content).RowDefinitions[1].Height = new GridLength(enabled ? 0 : 72);
        PlayerPage.Padding = enabled ? new Thickness(0) : new Thickness(24);
        PlayerNavigation.Visibility = enabled ? Visibility.Collapsed : Visibility.Visible;
        PlayerNavigationRow.Height = enabled ? new GridLength(0) : GridLength.Auto;
        var cinemaLabel = enabled ? "Exit cinema" : "Cinema mode";
        foreach (var button in new[] { CinemaButton, NativeCinemaButton })
        {
            button.Content = enabled ? "\uE73F" : "\uE740";
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, cinemaLabel);
            ToolTipService.SetToolTip(button, cinemaLabel);
        }
        ApplyPlayerLayout();
        if (enabled) ShowCinemaControls(resetIdleDeadline: true);
        else StopCinemaCursorIdleTimer(showCursor: true);

        if (!enabled)
        {
            _windowState.RequestWindowMode(_preCinemaWindowMode);
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
        if (_playerPresentation == PlayerPresentation.Compact)
        {
            ShowCompactControls();
            return;
        }
        if (!_isCinemaMode) return;
        HandleCinemaPointerMoved("PlayerPage", args);
    }

    private void VideoSurface_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_isCinemaMode) return;
        HandleCinemaPointerMoved(sender is FrameworkElement element ? element.Name : "VideoSurface", args);
    }

    private void HandleCinemaPointerMoved(string source, PointerRoutedEventArgs args)
    {
        var position = args.GetCurrentPoint(WindowRoot).Position;
        var previous = _lastCinemaPointerPosition;
        _lastCinemaPointerPosition = position;
        var hadPrevious = _hasCinemaPointerPosition;
        _hasCinemaPointerPosition = true;
        if (!hadPrevious || DateTimeOffset.UtcNow - _cinemaLastHideAt < TimeSpan.FromMilliseconds(100))
            return;
        var dx = position.X - previous.X;
        var dy = position.Y - previous.Y;
        if (dx * dx + dy * dy < 4)
            return;
        if (!_cinemaPointerMoveDiagnosticWritten)
        {
            _cinemaPointerMoveDiagnosticWritten = true;
            LaunchDiagnostics.Write($"event=cinema.pointer-move source={source}");
        }
        ShowCinemaControls("pointer-move", resetIdleDeadline: true);
    }

    private void CinemaCursorIdleTimer_Tick(object? sender, object args)
    {
        _cinemaCursorIdleTimer.Stop();
        if (_playerPresentation == PlayerPresentation.Compact)
        {
            var keepVisible = CompactOverlayPolicy.ShouldShow(
                _engine.IsPlaying, _engine.IsBuffering, _compactPointerOverControls,
                _compactGeometryGestureActive && !_compactGeometryResize,
                _compactGeometryGestureActive && _compactGeometryResize,
                idleElapsed: true) || HasCompactKeyboardNavigationFocus() || IsTrackFlyoutOpen || VolumeFlyout.IsOpen;
            SetCompactControlsVisible(keepVisible);
            return;
        }
        if (!_isCinemaMode || _currentPage != ShellPage.Player) return;
        if (!_cinemaWindowActive)
        {
            StopCinemaCursorIdleTimer(showCursor: true);
            return;
        }
        var playbackFailed = _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
        if (_terminalPlaybackFailure || _playbackIntent != PlaybackUserIntent.Playing || _engine.IsEnded || playbackFailed)
        {
            StopCinemaCursorIdleTimer(showCursor: true);
            return;
        }
        if (!(_engine.IsPlaying || _engine.IsBuffering))
        {
            _cinemaCursorIdleTimer.Stop();
            return;
        }
        var idleFor = DateTimeOffset.UtcNow - _cinemaLastMouseActivityAt;
        var flyoutOpen = IsTrackFlyoutOpen || VolumeFlyout.IsOpen;
        if (!CinemaIdlePolicy.ShouldHideControls(
                idleFor, isPlaying: true, flyoutOpen: flyoutOpen, dragging: _isDraggingProgress,
                keyboardInteractionActive: HasCinemaKeyboardInteractionFocus()))
        {
            ScheduleCinemaIdleCheck();
            return;
        }
        SetCinemaControlsVisible(false);
        SetCinemaCursorVisible(false);
        _cinemaLastHideAt = DateTimeOffset.UtcNow;
        LaunchDiagnostics.Write("event=cinema.hide");
    }

    private void CinemaPointerPollTimer_Tick(object? sender, object args)
    {
        if (_cinemaCursorHidden) _cinemaCursorController.TryWakeFromPoll();
    }

    private void ShowCinemaControls(string reason = "interaction", bool resetIdleDeadline = false)
    {
        if (!_isCinemaMode || _currentPage != ShellPage.Player) return;
        if (!_cinemaControlsVisible || _cinemaCursorHidden)
            LaunchDiagnostics.Write($"event=cinema.show reason={reason}");
        if (resetIdleDeadline) _cinemaLastMouseActivityAt = DateTimeOffset.UtcNow;
        SetCinemaControlsVisible(true);
        SetCinemaCursorVisible(true);
        if (_engine.IsPlaying || _engine.IsBuffering) ScheduleCinemaIdleCheck();
        else _cinemaCursorIdleTimer.Stop();
    }

    private void ScheduleCinemaIdleCheck()
    {
        _cinemaCursorIdleTimer.Stop();
        if (!_isCinemaMode || _currentPage != ShellPage.Player || !_cinemaWindowActive || _isClosing || _terminalPlaybackFailure ||
            !(_engine.IsPlaying || _engine.IsBuffering)) return;

        var remaining = CinemaIdlePolicy.HideDelay - (DateTimeOffset.UtcNow - _cinemaLastMouseActivityAt);
        _cinemaCursorIdleTimer.Interval = remaining > TimeSpan.Zero
            ? remaining
            : TimeSpan.FromMilliseconds(150);
        _cinemaCursorIdleTimer.Start();
    }

    private bool HasCinemaKeyboardInteractionFocus()
    {
        if (FocusManager.GetFocusedElement(WindowRoot.XamlRoot) is not Control { FocusState: FocusState.Keyboard } focused)
            return false;
        DependencyObject? current = focused;
        while (current is not null)
        {
            if (ReferenceEquals(current, PlayerPage)) return true;
            current = VisualTreeHelper.GetParent(current);
        }
        return false;
    }

    private void CompactChrome_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        CompactGeometry_PointerMoved(sender, args);
        ShowCompactControls();
    }

    private void CompactControls_PointerEntered(object sender, PointerRoutedEventArgs args)
    {
        _compactPointerOverControls = true;
        ShowCompactControls();
    }

    private void CompactControls_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        _compactPointerOverControls = false;
        ShowCompactControls();
    }

    private void CompactChrome_GotFocus(object sender, RoutedEventArgs args) => ShowCompactControls();

    private void CompactChrome_LostFocus(object sender, RoutedEventArgs args) => ShowCompactControls();

    private void ShowCompactControls()
    {
        if (_playerPresentation != PlayerPresentation.Compact) return;
        SetCompactControlsVisible(true);
        _cinemaCursorIdleTimer.Stop();
        if (_engine.IsPlaying && !_engine.IsBuffering) _cinemaCursorIdleTimer.Start();
    }

    private void SetCompactControlsVisible(bool visible)
    {
        CompactControlsChrome.Opacity = visible ? 1 : 0;
        CompactControlsChrome.IsHitTestVisible = visible;
    }

    // Only focus reached with the keyboard keeps the overlay up; focus left behind by a mouse click or by
    // entering the mini player programmatically must not pin the controls on screen forever.
    private bool HasCompactKeyboardNavigationFocus() =>
        HasCompactKeyboardFocus() &&
        FocusManager.GetFocusedElement(WindowRoot.XamlRoot) is Control { FocusState: FocusState.Keyboard };

    private bool HasCompactKeyboardFocus()
    {
        var focused = FocusManager.GetFocusedElement(WindowRoot.XamlRoot) as DependencyObject;
        while (focused is not null)
        {
            if (ReferenceEquals(focused, CompactChrome)) return true;
            focused = VisualTreeHelper.GetParent(focused);
        }
        return false;
    }

    private void SetCinemaControlsVisible(bool visible)
    {
        _cinemaControlsVisible = visible;
        if (!_isCinemaMode) return;
        if (visible) CinemaTitleText.Text = PlayerTitleText.Text;
        VlcTransportBar.Opacity = visible ? 1 : 0;
        VlcTransportBar.IsHitTestVisible = visible;
        CinemaTitleStrip.Opacity = visible ? 1 : 0;
    }

    private void StopCinemaCursorIdleTimer(bool showCursor)
    {
        _cinemaCursorIdleTimer.Stop();
        var wasHidden = !_cinemaControlsVisible || _cinemaCursorHidden;
        if (showCursor) SetCinemaCursorVisible(true);
        if (showCursor && wasHidden)
            LaunchDiagnostics.Write("event=cinema.show reason=state-change");
        SetCinemaControlsVisible(true);
    }

    private void SetCinemaCursorVisible(bool visible)
    {
        if (_cinemaCursorHidden == !visible) return;
        _cinemaCursorHidden = !visible;
        _cinemaCursorController.SetHidden(_cinemaCursorHidden);
        if (_cinemaCursorHidden)
        {
            foreach (var button in new[] { ForwardButton, RewindButton, PreviousEpisodeButton, NextEpisodeButton })
            {
                var toolTip = ToolTipService.GetToolTip(button);
                if (toolTip is ToolTip openToolTip) openToolTip.IsOpen = false;
                _cinemaToolTips[button] = toolTip;
                ToolTipService.SetToolTip(button, null);
            }
            _cinemaPointerPollTimer.Start();
        }
        else
        {
            _cinemaPointerPollTimer.Stop();
            foreach (var (button, toolTip) in _cinemaToolTips)
                ToolTipService.SetToolTip(button, toolTip);
            _cinemaToolTips.Clear();
        }
    }

    private void PlaylistToggleButton_Click(object sender, RoutedEventArgs args)
    {
        _playlistHidden = !_playlistHidden;
        LaunchDiagnostics.Write($"event=player.playlist hidden={_playlistHidden}");
        ApplyPlayerLayout();
    }

    private void ApplyPlayerLayout()
    {
        var cinema = _isCinemaMode;
        var compact = _playerPresentation == PlayerPresentation.Compact;

        PlayerPage.Padding = cinema || compact ? new Thickness(0) : new Thickness(24);
        PlayerNavigation.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;
        PlayerNavigationRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        var showPlaylist = !cinema && !compact && !_playlistHidden;
        PlayerLayoutGrid.ColumnSpacing = showPlaylist ? 18 : 0;
        PlayerContentColumn.Width = new GridLength(showPlaylist ? 2.35 : 1, GridUnitType.Star);
        PlayerSideColumn.Width = new GridLength(showPlaylist ? 0.9 : 0, GridUnitType.Star);
        PlayerSidePanel.Visibility = showPlaylist ? Visibility.Visible : Visibility.Collapsed;
        // The toggle only makes sense where the playlist can appear (not in cinema or the compact card).
        PlaylistToggleButton.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;
        var playlistLabel = _playlistHidden ? "Show playlist" : "Hide playlist";
        PlaylistToggleButton.Content = _playlistHidden ? "\uE89F" : "\uE8A0";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(PlaylistToggleButton, playlistLabel);
        ToolTipService.SetToolTip(PlaylistToggleButton, playlistLabel);

        PlayerContentGrid.RowSpacing = cinema || compact ? 0 : 10;
        PlayerVideoRow.Height = new GridLength(1, GridUnitType.Star);
        PlayerDetailsRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        PlayerTransportRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        PlayerDetailsPanel.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;
        // Cinema: the same transport floats over the bottom of the video instead of sitting below it.
        Grid.SetRow(VlcTransportBar, cinema ? 0 : 2);
        VlcTransportBar.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        SleepTimerButton.Visibility = compact || _windowState.IsWindowMini ? Visibility.Collapsed : Visibility.Visible;
        VlcTransportBar.VerticalAlignment = cinema ? VerticalAlignment.Bottom : VerticalAlignment.Stretch;
        VlcTransportBar.HorizontalAlignment = HorizontalAlignment.Stretch;
        VlcTransportBar.MaxWidth = cinema ? 1100 : double.PositiveInfinity;
        VlcTransportBar.Margin = cinema ? new Thickness(24, 0, 24, 28) : new Thickness(0);
        var panelBrush = ThemeBrush("AppPanelSurfaceBrush");
        VlcTransportBar.Background = cinema ? ThemeBrush("AppMediaScrimBrush") : panelBrush;
        CinemaTitleStrip.Visibility = cinema ? Visibility.Visible : Visibility.Collapsed;
        CompactChrome.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
        SetWindowMiniCompactControls(_windowState.IsWindowMini);
        MiniPlayerButton.Visibility = compact || ReferenceEquals(_engine, _nativeEngine)
            ? Visibility.Collapsed
            : Visibility.Visible;
        ToolTipService.SetToolTip(MiniPlayerButton, cinema ? "Mini player (always on top)" : "Mini player");
        NextEpisodePrompt.VerticalAlignment = cinema ? VerticalAlignment.Center : VerticalAlignment.Bottom;
        if (!cinema)
        {
            VlcTransportBar.Opacity = 1;
            VlcTransportBar.IsHitTestVisible = true;
            CinemaTitleStrip.Opacity = 0;
        }
        ApplyPlayerNowNextVisibility();
        ApplyPlayerSurfaceColors(cinema, compact);
        NativeCinemaButton.Visibility = Visibility.Collapsed;
        NativePlayerElement.AreTransportControlsEnabled = false;
        if (compact)
        {
            CompactTitleText.Text = PlayerTitleText.Text;
            CompactSeekRow.Visibility = _currentSource?.Kind == StreamKind.Live ? Visibility.Collapsed : Visibility.Visible;
        }
        RefreshSleepTimerPresentation();
    }

    private void ApplyPlayerSurfaceColors(bool cinema, bool compact)
    {
        var textKey = cinema || compact ? "AppOnMediaTextBrush" : "AppTextBrush";
        var mutedKey = cinema || compact ? "AppOnMediaMutedBrush" : "AppMutedTextBrush";
        foreach (var button in new[]
                 {
                     PauseButton, RewindButton, ForwardButton, PreviousEpisodeButton, NextEpisodeButton,
                     RetryPlaybackButton,
                     AudioSubtitlesButton, VolumeButton, SleepTimerButton, CinemaButton, MiniPlayerButton, PlaylistToggleButton
                 })
            button.Foreground = ThemeBrush(textKey);
        StatusText.Foreground = ThemeBrush(mutedKey);
        ElapsedText.Foreground = ThemeBrush(mutedKey);
        DurationText.Foreground = ThemeBrush(mutedKey);
        VolumeStateText.Foreground = ThemeBrush(mutedKey);

        var compactTextKey = compact ? "AppOnMediaTextBrush" : "AppTextBrush";
        foreach (var button in new[]
                 {
                     CompactExpandButton, CompactCloseButton, CompactMinimizeButton, CompactPreviousButton,
                     CompactRewindButton, CompactPauseButton, CompactForwardButton, CompactNextButton,
                     CompactAudioSubtitlesButton, CompactMuteButton
                 })
            button.Foreground = ThemeBrush(compactTextKey);
        CompactProgressSlider.Foreground = ThemeBrush(compactTextKey);
        CompactVolumeSlider.Foreground = ThemeBrush(compactTextKey);
    }

    private void SetWindowMiniCompactControls(bool isWindowMini)
    {
        CompactMinimizeButton.Visibility = isWindowMini ? Visibility.Visible : Visibility.Collapsed;
        var closeName = isWindowMini ? "Close (stop playback)" : "Return to player";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(CompactCloseButton, closeName);
        ToolTipService.SetToolTip(CompactCloseButton, closeName);
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        _isClosing = true;
        CancelSleepTimer("window-close");
        SetPlaybackIntent(PlaybackUserIntent.StoppedByUser, awaitForwardProgress: false);
        if (_windowState.IsWindowMini)
        {
            _windowMiniGeometry = _windowState.GetCurrentWindowMiniGeometry();
            _windowMiniGeometryDirty = _windowMiniGeometry != _windowMiniGeometryAtEntry;
            SaveWindowMiniGeometryIfChanged();
        }
        StopHeldSeek(commit: true, released: false);
        FlushPendingVisits();
        SaveResumePosition(force: true);
        if (_currentSource is { } closingSource)
            LaunchDiagnostics.Write($"event=playback.stop engine={(ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native")} kind={closingSource.Kind} reason={(_engine.IsEnded ? "completed" : "user-cancelled")}");
        _playbackUiTimer.Stop();
        _playbackUiTimer.Tick -= PlaybackUiTimer_Tick;
        _sleepTimerNoticeTimer.Stop();
        _sleepTimerNoticeTimer.Tick -= SleepTimerNoticeTimer_Tick;
        SleepTimerStateChanged -= MainWindow_SleepTimerStateChanged;
        _heldSeekTimer.Stop();
        _heldSeekTimer.Tick -= HeldSeekTimer_Tick;
        Activated -= MainWindow_Activated;
        _vlcEngine.StateChanged -= VlcEngine_StateChanged;
        _nativeEngine.PlaybackFailed -= NativeEngine_PlaybackFailed;
        _catalogLandingPage.RefreshStateChanged -= CatalogLandingPage_RefreshStateChanged;
        _accountPage.MinimizeToMiniModeChanged -= AccountPage_MinimizeToMiniModeChanged;
        _windowState.WindowMiniGeometryChanged -= WindowState_WindowMiniGeometryChanged;
        _windowState.Dispose();
        StopPlayerEpgUpdates();
        _playerEpgTimer.Tick -= PlayerEpgTimer_Tick;
        _epgCoordinator.EpgUpdated -= EpgCoordinator_EpgUpdated;
        StopCinemaCursorIdleTimer(showCursor: true);
        _cinemaCursorIdleTimer.Tick -= CinemaCursorIdleTimer_Tick;
        _cinemaPointerPollTimer.Stop();
        _cinemaPointerPollTimer.Tick -= CinemaPointerPollTimer_Tick;
        _cinemaCursorController.Dispose();
        _windowState.ClearFullscreenHint();
        _catalogLandingPage.Shutdown();
        SetTrackSelectingEngine(null);
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
            await _nativeEngine.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            LaunchDiagnostics.Write("Native playback engine is still closing during window close");
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

    public sealed class PlayerListEntry(Channel channel, bool isFavorite = false, PlaybackProgress? progress = null,
        ElementTheme theme = ElementTheme.Dark) : INotifyPropertyChanged
    {
        private bool _isCurrent;
        private bool _isFavorite = isFavorite;
        private PlaybackProgress? _progress = progress;
        private ElementTheme _theme = theme;
        private Brush ThemeBrush(string key) => AppThemeBrushResolver.For(_theme, key);
        private Brush AccentBrush => ThemeBrush("AppAccentBrush");
        private Brush NormalBrush => ThemeBrush("AppTextBrush");
        private Brush SelectedTextBrush => ThemeBrush("AppOnAccentBrush");
        private Brush MutedBrush => ThemeBrush("AppMutedTextBrush");

        public Channel Channel { get; } = channel;
        public string Title => Channel.DisplayName;
        public Visibility FavoriteVisibility => FavoriteTargetResolver.ShowsPerItemFavorite(Channel)
            ? Visibility.Visible
            : Visibility.Collapsed;
        public Brush BackgroundBrush => _isCurrent ? AccentBrush : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        public Brush ForegroundBrush => _isCurrent ? SelectedTextBrush : NormalBrush;
        public Brush FavoriteBrush => _isFavorite ? ThemeBrush("AppFavoriteBrush") : MutedBrush;
        public string FavoriteGlyph => _isFavorite ? "★" : "☆";
        // Row bar: full once watched, otherwise how far playback got (hidden until a duration is known).
        private PlaybackProgressPresentation ProgressPresentation => PlaybackProgressPresentation.For(_progress);
        public Visibility ProgressVisibility => ProgressPresentation.IsVisible ? Visibility.Visible : Visibility.Collapsed;
        public double ProgressValue => ProgressPresentation.Value;
        public Brush ProgressBarBrush => _isCurrent ? SelectedTextBrush : AccentBrush;
        public Brush ProgressTrackBrush => _isCurrent
            ? new SolidColorBrush(_theme == ElementTheme.Dark
                ? Windows.UI.Color.FromArgb(0x33, 255, 255, 255)
                : Windows.UI.Color.FromArgb(0x33, 255, 245, 229))
            : _theme == ElementTheme.Dark
                ? new SolidColorBrush(Windows.UI.Color.FromArgb(0x33, 255, 255, 255))
                : ThemeBrush("AppLineBrush");

        public void RefreshTheme(ElementTheme actualTheme)
        {
            _theme = actualTheme;
            OnPropertyChanged(nameof(BackgroundBrush));
            OnPropertyChanged(nameof(ForegroundBrush));
            OnPropertyChanged(nameof(FavoriteBrush));
            OnPropertyChanged(nameof(ProgressBarBrush));
            OnPropertyChanged(nameof(ProgressTrackBrush));
        }
        public string ProgressAutomationName => ProgressPresentation.AutomationName;
        public bool IsFinished => _progress?.State == PlaybackProgressState.Finished;
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
                OnPropertyChanged(nameof(ProgressBarBrush));
                OnPropertyChanged(nameof(ProgressTrackBrush));
            }
        }

        public void SetProgress(PlaybackProgress? progress)
        {
            if (_progress == progress) return;
            _progress = progress;
            OnPropertyChanged(nameof(ProgressVisibility));
            OnPropertyChanged(nameof(ProgressValue));
            OnPropertyChanged(nameof(ProgressAutomationName));
            OnPropertyChanged(nameof(IsFinished));
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}
