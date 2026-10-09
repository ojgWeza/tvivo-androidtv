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
using System.Globalization;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
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
    private string[] _shufflePlaylistIds = Array.Empty<string>();
    private string? _preparedNextItemId;
    private string? _preparedNextForItemId;
    private bool _preparedNextWasShuffle;
    private PlaybackMode _episodePlaybackMode = PlaybackMode.Next;
    private PlaybackMode _moviePlaybackMode = PlaybackMode.Shuffle;
    private MinimizeToMiniMode _minimizeToMiniMode = MinimizeToMiniMode.MiniPlayerWhilePlaying;
    private WindowMiniGeometry _windowMiniGeometry = WindowMiniGeometry.Default;
    private WindowMiniGeometry _windowMiniGeometryAtEntry;
    private bool _windowMiniGeometryDirty;
    private bool _windowMiniTransitionInProgress;
    private ShellPage? _windowMiniPreviousPage;
    private PlayerPresentation _windowMiniPreviousPresentation = PlayerPresentation.Full;
    private bool _windowMiniPreviousCinema;
    private bool _initializingPlaybackOptions = true;
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
    private long _activePlaybackSessionGeneration = -1;
    private long _metadataGeneration;
    private long _playerEntryTraceId;
    private long _playerEntryTraceStartedAt;
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
    private readonly CinemaCursorController _cinemaCursorController;
    // Cinema mode shows the transport as an overlay that fades out while playing and the mouse is idle.
    private bool _cinemaControlsVisible = true;
    private bool _cinemaPointerOverControls;
    private bool _compactPointerOverControls;
    private bool _cinemaPointerMoveDiagnosticWritten;
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
    private long? _pendingResumePosition;
    private long _lastGoodPlaybackPositionMilliseconds;
    private bool _resumeReady;
    private long _diagnosticOpenStartedAt;
    private long _diagnosticLastPosition;
    private bool _diagnosticFirstProgressReported;
    private DateTimeOffset _lastResumeSavedAt;
    private static readonly TimeSpan StallTimeout = TimeSpan.FromSeconds(12);
    private readonly StallRecoveryPolicy _stallPolicy = new(StallTimeout);
    private readonly long _stallClockStart = Stopwatch.GetTimestamp();
    private readonly DispatcherTimer _playbackUiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _cinemaCursorIdleTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private bool _backTransitionTraceActive;
    private int _backTransitionRenderSamples;
    private long _backTransitionTraceId;
    private long _backTransitionStartedAt;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int value, int valueSize);

    public MainWindow()
    {
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
        _cinemaCursorController = new CinemaCursorController(WinRT.Interop.WindowNative.GetWindowHandle(this));
        var playbackOptions = ReadPlaybackOptions();
        _episodePlaybackMode = playbackOptions.EpisodeMode;
        _moviePlaybackMode = playbackOptions.MovieMode;
        _minimizeToMiniMode = playbackOptions.MinimizeToMiniMode;
        try
        {
            SyncPlaybackModeToggles(_episodePlaybackMode);
        }
        finally
        {
            _initializingPlaybackOptions = false;
        }
        AudioSubtitlesFlyout.Opened += AudioSubtitlesFlyout_Opened;
        AudioSubtitlesFlyout.Closed += AudioSubtitlesFlyout_Closed;
        CompactAudioSubtitlesFlyout.Opened += AudioSubtitlesFlyout_Opened;
        CompactAudioSubtitlesFlyout.Closed += CompactAudioSubtitlesFlyout_Closed;
        SetMutePresentation();
        SetTrackSelectingEngine(_engine);
        _epgCoordinator.EpgUpdated += EpgCoordinator_EpgUpdated;
        _playerEpgTimer.Tick += PlayerEpgTimer_Tick;
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
        ApplyWindowChromeState();
        _catalogLandingPage.InteractionReadinessChanged += CatalogLandingPage_InteractionReadinessChanged;
        _catalogLandingPage.SearchTextChanged += CatalogLandingPage_SearchTextChanged;
        _nativeEngine.Player.Volume = 1;
        NativePlayerElement.SetMediaPlayer(_nativeEngine.Player);
        _accountPage.SetPlaybackEngine(useVlc ? "LibVLC" : "Native");
        _accountPage.SetMinimizeToMiniMode(_minimizeToMiniMode);
        _accountPage.MinimizeToMiniModeChanged += AccountPage_MinimizeToMiniModeChanged;
        _accountPage.PlaybackEngineChanged += AccountPage_PlaybackEngineChanged;
        ApplyPlaybackEngineVisuals();
        _playbackUiTimer.Tick += PlaybackUiTimer_Tick;
        _playbackUiTimer.Start();
        _heldSeekTimer.Tick += HeldSeekTimer_Tick;
        Activated += MainWindow_Activated;
        _cinemaCursorIdleTimer.Tick += CinemaCursorIdleTimer_Tick;
        PlayerPage.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(PlayerPage_PointerMoved), true);
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _catalogLandingPage.VisibleCatalogDataChanged += (_, _) => _homePage.MarkDataChanged();
        _homePage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
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
        if (args.Handled || IsTrackFlyoutOpen || VolumeFlyout.IsOpen)
            return;

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

    private void SetPlaybackSurfaceVisibility(bool visible)
    {
        var showVlc = visible && ReferenceEquals(_engine, _vlcEngine);
        VideoView.Visibility = showVlc ? Visibility.Visible : Visibility.Collapsed;
        NativePlayerElement.Visibility = visible && ReferenceEquals(_engine, _nativeEngine)
            ? Visibility.Visible
            : Visibility.Collapsed;
        TraceBackTransition($"playback-surface-state requestedVisible={visible} {VideoSurfaceState()}");
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
                Foreground = (Brush)Application.Current.Resources["AppMutedTextBrush"],
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
                        ? (Brush)Application.Current.Resources["AppAccentBrush"]
                        : (Brush)Application.Current.Resources["AppTextBrush"],
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
                ? (Brush)Application.Current.Resources["AppAccentBrush"]
                : (Brush)Application.Current.Resources["AppTextBrush"],
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
                    ? (Brush)Application.Current.Resources["AppAccentBrush"]
                    : (Brush)Application.Current.Resources["AppTextBrush"],
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, $"Subtitle: {track.DisplayName}");
            item.Click += SubtitleTrackMenuItem_Click;
            flyout.Items.Add(item);
        }
    }

    private static MenuFlyoutItem CreateTrackSectionHeader(string text) => new()
    {
        Text = text,
        IsEnabled = false,
        Foreground = (Brush)Application.Current.Resources["AppAccentSoftBrush"],
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
                _episodePlaybackMode, _moviePlaybackMode, _minimizeToMiniMode).ToJson());
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

    private void AutoplayNextToggle_Checked(object sender, RoutedEventArgs args) => SetPlaybackMode(PlaybackMode.Next);

    private void AutoplayNextToggle_Unchecked(object sender, RoutedEventArgs args) => SetPlaybackMode(PlaybackMode.Off);

    private void ShuffleToggle_Checked(object sender, RoutedEventArgs args) => SetPlaybackMode(PlaybackMode.Shuffle);

    private void ShuffleToggle_Unchecked(object sender, RoutedEventArgs args) => SetPlaybackMode(PlaybackMode.Off);

    private void SetPlaybackMode(PlaybackMode mode)
    {
        if (_initializingPlaybackOptions) return;
        // The toggles show the mode of the kind they were last configured for; edit that one,
        // not whatever _nowPlayingChannel happens to be mid-transition (null after a stop).
        var kind = _playlistOptionsKind ?? _nowPlayingChannel?.Source.Kind;
        if (kind == StreamKind.Episode)
            _episodePlaybackMode = mode;
        else if (kind == StreamKind.Movie)
            _moviePlaybackMode = mode;
        else return;

        _initializingPlaybackOptions = true;
        try
        {
            SyncPlaybackModeToggles(mode);
        }
        finally
        {
            _initializingPlaybackOptions = false;
        }
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
        _initializingPlaybackOptions = true;
        try
        {
            var hasPlaylist = kind is StreamKind.Episode or StreamKind.Movie;
            PlayerPlaylistOptionsRow.Visibility = hasPlaylist ? Visibility.Visible : Visibility.Collapsed;
            SyncPlaybackModeToggles(GetPlaybackMode(kind));
            if (!hasPlaylist)
            {
                _nextPlaybackSelector.Reset();
                ClearPreparedNextItem();
            }
        }
        finally
        {
            _initializingPlaybackOptions = false;
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
        CopyDiagnosticsStatus.Text = "Diagnostics copied";
    }

    private void ShowPage(ShellPage page, bool updateTopNavigation = true)
    {
        if (_currentPage == page)
            return;

        if (page != ShellPage.Player && _isCinemaMode)
            StopCinemaCursorIdleTimer(showCursor: true);

        if (page == ShellPage.Player && _playerPresentation == PlayerPresentation.Compact)
            SetPlayerPresentation(PlayerPresentation.Full);

        var shouldStopPlayback = _currentPage is { } currentPage && page != ShellPage.Player &&
            PlayerPresentationPolicy.ShouldStopPlaybackOnPageChange(
                _playerPresentation, ToPlayerPageKind(currentPage), ToPlayerPageKind(page));

        if (shouldStopPlayback)
            StopHeldSeek(commit: true, released: false);

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
        var keepCompactVisible = _playerPresentation == PlayerPresentation.Compact &&
            PlayerPresentationPolicy.CanCoexist(_playerPresentation, ToPlayerPageKind(page));
        if (isPlayer)
            _playbackUiTimer.Start();
        var isCatalog = page == ShellPage.Catalog;
        var isStaticPage = page is ShellPage.Home or ShellPage.Setup or ShellPage.Account;
        TraceBackTransition($"show-page-visibility-begin page={page}");
        PageHost.Visibility = isStaticPage ? Visibility.Visible : Visibility.Collapsed;
        _homePage.Visibility = page == ShellPage.Home ? Visibility.Visible : Visibility.Collapsed;
        _providerSetupPage.Visibility = page == ShellPage.Setup ? Visibility.Visible : Visibility.Collapsed;
        _accountPage.Visibility = page == ShellPage.Account ? Visibility.Visible : Visibility.Collapsed;
        PlayerPage.Visibility = isPlayer || keepCompactVisible ? Visibility.Visible : Visibility.Collapsed;
        TraceBackTransition($"show-page-player-visibility-set page={page} player={PlayerPage.Visibility}");
        SetPlaybackSurfaceVisibility(isPlayer || keepCompactVisible);
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
        TopSearchBox.IsEnabled = enabled;
        TopSortBox.IsEnabled = enabled;
    }

    private void TopSortBox_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (TopSortBox.SelectedItem is ComboBoxItem { Tag: string tag })
            _catalogLandingPage.SetCategorySort(tag);
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
        }
        finally
        {
            _suppressTopSearchChanged = false;
        }
    }

    private async void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        if (IsPlaybackUiActive) SaveResumePosition(force: true);
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
        _seriesEpisodeProgress = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonWatchedCount.Visibility = Visibility.Collapsed;
        SeriesFavoriteButton.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        var type = args.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live;
        if (_catalogLandingPage.Account is { } account)
        {
            QueueVisit(account, type, args.Channel.Id);
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
        UpdatePlaylistOptionControls(args.Source.Kind);
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
        UpdateEpisodeNavigationButtons();
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

        _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(account, series.Id);
        var selected = _catalogRepository.SelectResumeEpisode(account, details);
        if (selected is null)
        {
            StatusText.Text = "This series has no episodes available.";
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
        OpenSeriesEpisode(ToEpisodeChannel(account, series.Id, selected));
    }

    private static Channel ToEpisodeChannel(ProviderAccount account, string seriesId, SeriesEpisode episode)
    {
        var displayName = $"S{episode.SeasonNumber:00} E{episode.EpisodeNumber:00} · {episode.Title}";
        return new Channel(account.AccountId, episode.Id, seriesId, episode.Title, displayName, null, null,
            episode.EpisodeNumber, episode.Source,
            new Dictionary<string, string> { ["seriesId"] = seriesId, ["seasonId"] = episode.SeasonId });
    }

    private void OpenSeriesEpisode(Channel episode, bool preserveCompact = false)
    {
        if (episode.Source.DirectUri is null) return;
        if (IsPlaybackUiActive) SaveResumePosition(force: true);
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        _currentSeriesId ??= episode.Metadata.TryGetValue("seriesId", out var seriesId) ? seriesId : null;
        _currentSeriesAccount = _catalogLandingPage.Account;
        if (_currentSeriesId is not null && _currentSeriesAccount is not null)
        {
            _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(_currentSeriesAccount, _currentSeriesId);
            _seriesEpisodeProgress.TryGetValue(episode.Id, out var episodeProgress);
            _resumePositionOnStart = episodeProgress?.State == PlaybackProgressState.InProgress ? episodeProgress.ResumeMs : null;
            _catalogRepository.SaveProgress(_currentSeriesAccount, "episode", episode.Id, _currentSeriesId,
                _resumePositionOnStart ?? 0, episodeProgress?.DurationMs, finished: false);
            _seriesEpisodeProgress = _catalogRepository.GetEpisodeProgressForSeries(_currentSeriesAccount, _currentSeriesId);
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
        _ = StartPlaybackAsync(episode.Source, preserveCompact);
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
        if (target.Source.Kind == StreamKind.Episode)
            OpenSeriesEpisode(target);
        else
            PlayRelatedChannel(target);
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
                    channel.Source.Kind == StreamKind.Episode && _seriesEpisodeProgress.TryGetValue(channel.Id, out var progress) ? progress : null));
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

    private async Task StartPlaybackAsync(
        StreamSource source,
        bool preserveCompact = false,
        bool isRecovery = false,
        long? recoveryPositionMs = null)
    {
        StopHeldSeek(commit: true, released: false);
        _engine.IsMuted = _playbackMuteState.IsMuted;
        var resumePosition = source.Kind switch
        {
            StreamKind.Movie when _catalogLandingPage.Account is { } account && _nowPlayingChannel is { } movie =>
                _catalogRepository.GetResumePosition(account, CatalogItemType.Movie, movie.Id),
            StreamKind.Episode => _resumePositionOnStart ??
                (_currentSeriesAccount is { } seriesAccount && _nowPlayingChannel is { } episode
                    ? _catalogRepository.GetEpisodeResumePosition(seriesAccount, episode.Id) : 0),
            _ => 0,
        };
        _resumePositionOnStart = null;
        _movieCompletionRecorded = false;
        _pendingResumePosition = null;
        _resumeReady = false;
        _unverifiedPlaybackEndHandled = false;
        _lastGoodPlaybackPositionMilliseconds = Math.Max(0, isRecovery
            ? recoveryPositionMs ?? resumePosition
            : resumePosition);
        var generation = Interlocked.Increment(ref _playbackSessionGeneration);
        _diagnosticOpenStartedAt = Stopwatch.GetTimestamp();
        _diagnosticLastPosition = 0;
        _diagnosticFirstProgressReported = false;
        _stallPolicy.BeginSession(generation, isRecovery);
        var playerEntryTraceId = Volatile.Read(ref _playerEntryTraceId);
        var sessionCts = new CancellationTokenSource();
        var previousCts = Interlocked.Exchange(ref _playbackSessionCts, sessionCts);
        previousCts?.Cancel();
        previousCts?.Dispose();
        _currentSource = source;
        ResetTrackSelectionForSession();
        ClearPlayerNowNext();
        _playbackCompletionShown = false;
        if (_nowPlayingChannel is { } selectedChannel)
        {
            PlayerTitleText.Text = selectedChannel.DisplayName;
            CompactTitleText.Text = selectedChannel.DisplayName;
        }
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        if (!preserveCompact) ShowPage(ShellPage.Player);
        if (source.Kind == StreamKind.Live)
        {
            StartPlayerEpgUpdates();
            _ = RefreshPlayerNowNextAsync();
        }
        StatusText.Text = "Starting playback…";
        var engineName = ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native";
        LaunchDiagnostics.Write($"event=playback.start engine={engineName} kind={source.Kind}");
        LaunchDiagnostics.Write($"event=playback.attempt generation={generation} engine={engineName} kind={source.Kind} extensionPresent={(string.IsNullOrWhiteSpace(source.ContainerExtension) ? "no" : "yes")} resume={(resumePosition > 0 || isRecovery && recoveryPositionMs > 0 ? "yes" : "no")} httpStatus=unknown redirect=unknown");
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
                token => OnUiAsync(() =>
                {
                    if (generation != Volatile.Read(ref _playbackSessionGeneration) || sessionCts.IsCancellationRequested)
                        return Task.FromResult(PlaybackAttemptResult.Cancelled);
                    _diagnosticOpenStartedAt = Stopwatch.GetTimestamp();
                    return _playback.PlayAsync(source, token);
                }),
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
        if (source.Kind == StreamKind.Live && result != PlaybackAttemptResult.FirstFrame)
        {
            StopPlayerEpgUpdates();
            ClearPlayerNowNext();
        }
        if (_isCinemaMode && result == PlaybackAttemptResult.FirstFrame)
            ShowCinemaControls();
        StatusText.Text = PlaybackStatusMessage(result, source.Kind);
        if (result == PlaybackAttemptResult.FirstFrame)
        {
            PlayerVideoCurtain.Visibility = Visibility.Collapsed;
            _pendingResumePosition = isRecovery && recoveryPositionMs is > 0
                ? recoveryPositionMs
                : resumePosition > 0 ? resumePosition : null;
            _activePlaybackSessionGeneration = generation;
            _resumeReady = true;
        }
        SetPauseButtonState(result == PlaybackAttemptResult.FirstFrame
            ? "Pause"
            : result == PlaybackAttemptResult.Cancelled ? "Play" : "Retry");
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
        StatusText.Text = string.Empty;
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

        _playbackStopTask = StopBothPlaybackEnginesAsync();
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

    private void EpgCoordinator_EpgUpdated(object? sender, EventArgs args)
    {
        if (_isClosing || !_catalogLandingPage.EpgEnabled) return;
        if (!WindowRoot.DispatcherQueue.HasThreadAccess)
        {
            WindowRoot.DispatcherQueue.TryEnqueue(() => EpgCoordinator_EpgUpdated(sender, args));
            return;
        }
        if (IsPlaybackUiActive && _currentSource?.Kind == StreamKind.Live)
            _ = RefreshPlayerNowNextAsync();
    }

    private void PlayerEpgTimer_Tick(object? sender, object args)
    {
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
            WindowRoot.DispatcherQueue.TryEnqueue(() => _ = RefreshPlayerNowNextAsync());
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
        if (_currentSource is null) return;
        if (!_engine.IsEnded && (_engine.IsPlaying || _engine.IsBuffering || _engine.IsPaused))
        {
            _engine.TogglePause();
            SetPauseButtonState(_engine.IsPlaying || _engine.IsBuffering ? "Pause" : "Play");
        }
        else
            await StartPlaybackAsync(_currentSource, preserveCompact: _playerPresentation == PlayerPresentation.Compact);
    }

    private void PlaybackUiTimer_Tick(object? sender, object args)
    {
        if (!IsPlaybackUiActive)
        {
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            return;
        }
        if (_engine.IsEnded)
            StopHeldSeek(commit: true, released: false);
        if (_isCinemaMode)
        {
            if (_engine.IsPlaying || _engine.IsBuffering)
            {
                // Resumed after a pause: start hiding the overlay again.
                if (_cinemaControlsVisible && !_cinemaCursorIdleTimer.IsEnabled && !_cinemaPointerOverControls)
                    _cinemaCursorIdleTimer.Start();
            }
            else ShowCinemaControls(); // paused, ended or failed: keep the controls up
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
        var sessionIsCurrent = _resumeReady && _activePlaybackSessionGeneration == Volatile.Read(ref _playbackSessionGeneration);
        var playbackFailed = sessionIsCurrent && _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
        var ended = _engine.IsEnded;
        var endDuration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
        var endPosition = ended ? _lastGoodPlaybackPositionMilliseconds : timeline.PositionMilliseconds;
        var genuineEnd = PlaybackEndPolicy.IsGenuineEnd(endPosition, endDuration, ended);
        if (sessionIsCurrent && !ended && !_isHeldSeeking)
        {
            var lastObservedPosition = ClampPosition(timeline.PositionMilliseconds, endDuration);
            if (lastObservedPosition > 0 || _lastGoodPlaybackPositionMilliseconds == 0)
                _lastGoodPlaybackPositionMilliseconds = lastObservedPosition;
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
        if (sessionIsCurrent && ended &&
            (_nowPlayingChannel?.Source.Kind == StreamKind.Live || genuineEnd) &&
            _playbackFailurePolicy.ShouldAutoAdvance(_activePlaybackSessionGeneration) &&
            !_nextEpisodeAutoPlaySuppressed && !_nextEpisodeAdvanceStarted &&
            PlaybackModeLogic.ShouldAutoAdvance(GetPlaybackMode(_nowPlayingChannel?.Source.Kind)) && GetNextPlaylistItem() is { } endedNextItem)
        {
            _nextEpisodeAdvanceStarted = true;
            NextEpisodePrompt.Visibility = Visibility.Collapsed;
            if (endedNextItem.Source.Kind == StreamKind.Episode)
                OpenSeriesEpisode(endedNextItem, preserveCompact: _playerPresentation == PlayerPresentation.Compact);
            else
                PlayRelatedChannel(endedNextItem, preserveCompact: _playerPresentation == PlayerPresentation.Compact);
            return;
        }
        if (_windowState.IsWindowMini && (_engine.IsEnded ||
            (_engine.IsStopped && Volatile.Read(ref _playbackSessionCts) is null)))
        {
            ExitWindowMiniPlayer(expandToPlayer: false);
            return;
        }
        if (sessionIsCurrent && !playbackFailed && ended && !genuineEnd && !_unverifiedPlaybackEndHandled &&
            _nowPlayingChannel?.Source.Kind is StreamKind.Movie or StreamKind.Episode)
        {
            SaveResumePosition(force: true);
            StatusText.Text = "Playback ended before completion could be verified. Select Retry to resume.";
            SetPauseButtonState("Retry");
            _unverifiedPlaybackEndHandled = true;
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
        if (promptLeadMilliseconds > 0 && nextItem is not null &&
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
        var stallEligible = !playbackFailed && _currentSource is not null && !_isDraggingProgress && !_isHeldSeeking &&
            !_engine.IsPaused && !_engine.IsEnded && (_engine.IsPlaying || _engine.IsBuffering);
        var stallDecision = _stallPolicy.Observe(
            Volatile.Read(ref _playbackSessionGeneration),
            sessionIsCurrent,
            stallEligible,
            time,
            Stopwatch.GetElapsedTime(_stallClockStart));
        if (stallDecision == StallDecision.Recover)
            HandleStall(time);
        else if (stallDecision == StallDecision.GiveUp)
            ShowStallRetry(time);
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

    private async void HandleStall(long lastPositionMs)
    {
        if (_currentSource is not { } source) return;
        var engineName = ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native";
        LaunchDiagnostics.Write($"event=playback.stall engine={engineName} kind={source.Kind} positionMs={lastPositionMs} outcome=reconnecting");
        await StartPlaybackAsync(
            source,
            preserveCompact: _playerPresentation == PlayerPresentation.Compact,
            isRecovery: true,
            recoveryPositionMs: source.Kind == StreamKind.Live ? null : lastPositionMs);
    }

    private void ShowStallRetry(long lastPositionMs)
    {
        if (_currentSource is not { } source) return;
        var engineName = ReferenceEquals(_engine, _vlcEngine) ? "LibVLC" : "Native";
        LaunchDiagnostics.Write($"event=playback.stall engine={engineName} kind={source.Kind} positionMs={lastPositionMs} outcome=retry-exhausted");
        StatusText.Text = "Playback stalled. Select Retry to resume.";
        SetPauseButtonState("Retry");
    }

    private void SaveResumePosition(bool force = false, long? positionOverride = null)
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
            : ClampPosition(positionOverride ?? timeline.PositionMilliseconds, duration);
        if (!ended || positionOverride.HasValue)
            _lastGoodPlaybackPositionMilliseconds = position;
        if (type == CatalogItemType.Movie)
        {
            var failed = _playbackFailurePolicy.HasFailed(_activePlaybackSessionGeneration);
            var movieFinished = !failed && MovieCompletion.IsFinished(position, duration, ended);
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
            var finished = !failed && EpisodeCompletion.IsFinished(position, duration, ended);
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
        LaunchDiagnostics.Write($"event=resume.save kind={type} positionMs={position} completed={_engine.IsEnded}");
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
        // Activation must NOT leave window-mini: clicking the mini window to drag or resize it
        // activates it. Leaving is explicit only (Expand, Close, Escape, double-click, playback end).
        if (_windowState.IsWindowMini)
            _windowState.EnsureWindowMiniTopmost();
        if (args.WindowActivationState == WindowActivationState.Deactivated)
        {
            StopHeldSeek(commit: true, released: false);
            if (_isCinemaMode) StopCinemaCursorIdleTimer(showCursor: true);
        }
        else if (_isCinemaMode && _currentPage == ShellPage.Player && (_engine.IsPlaying || _engine.IsBuffering))
            ShowCinemaControls();
    }

    private void VlcEngine_StateChanged(object? sender, VlcPlaybackStateChangedEventArgs args)
    {
        if (args.State != VlcPlaybackState.Failed || !ReferenceEquals(sender, _vlcEngine) ||
            !ReferenceEquals(_engine, _vlcEngine)) return;
        var callbackGeneration = Volatile.Read(ref _playbackSessionGeneration);
        WindowRoot.DispatcherQueue.TryEnqueue(() =>
        {
            HandleLatePlaybackFailure(callbackGeneration);
            if (_windowState.IsWindowMini) ExitWindowMiniPlayer(expandToPlayer: false);
        });
    }

    private void NativeEngine_PlaybackFailed(PlaybackSessionToken session)
    {
        if (!ReferenceEquals(_engine, _nativeEngine)) return;
        var callbackGeneration = Volatile.Read(ref _playbackSessionGeneration);
        WindowRoot.DispatcherQueue.TryEnqueue(() => HandleLatePlaybackFailure(callbackGeneration));
    }

    private void HandleLatePlaybackFailure(long callbackGeneration)
    {
        var currentGeneration = Volatile.Read(ref _playbackSessionGeneration);
        if (!_playbackFailurePolicy.TryHandle(
                callbackGeneration, currentGeneration, _activePlaybackSessionGeneration, _resumeReady))
            return;

        var timeline = _engine.Timeline;
        var duration = timeline.DurationMilliseconds is > 0 ? timeline.DurationMilliseconds : null;
        var position = ClampPosition(timeline.PositionMilliseconds, duration);
        if (position > _lastGoodPlaybackPositionMilliseconds)
            _lastGoodPlaybackPositionMilliseconds = position;
        _nextEpisodeAutoPlaySuppressed = true;
        _nextEpisodeAdvanceStarted = true;
        SaveResumePosition(force: true, positionOverride: _lastGoodPlaybackPositionMilliseconds);
        StatusText.Text = "Playback failed. Select Retry to resume.";
        SetPauseButtonState("Retry");
        LaunchDiagnostics.Write($"event=playback.failure engine={(_engine == _vlcEngine ? "LibVLC" : "Native")} generation={currentGeneration} positionMs={_lastGoodPlaybackPositionMilliseconds} outcome=retry");
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

    private void VolumeFlyout_Opened(object? sender, object args) => _volumeFlyoutOpenedAtTick = Environment.TickCount64;

    // The first click of a double-click opens the flyout and its light-dismiss layer swallows the second
    // click, so DoubleTapped never fires on the button. A flyout dismissed by a click on the button within
    // the system double-click time of opening is that second click: toggle mute.
    private void VolumeFlyout_Closed(object? sender, object args)
    {
        if (Environment.TickCount64 - _volumeFlyoutOpenedAtTick <= GetDoubleClickTime() && CursorIsOverVolumeButton())
            ToggleMute();
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

    private void PlayRelatedChannel(Channel channel, bool preserveCompact = false)
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
        _ = StartPlaybackAsync(channel.Source, preserveCompact);
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
            ExitWindowMiniPlayer(expandToPlayer: false, afterExit: () =>
            {
                SetPlayerPresentation(PlayerPresentation.Full);
                StopHeldSeek(commit: false, released: false);
                // Closing the mini player ends the session and leaves the player: going back through
                // ShowPage stops playback once and lands on the page the user came from, instead of
                // leaving an empty Player page behind.
                if (_currentPage == ShellPage.Player) ReturnFromPlayer();
                else StopPlaybackForNavigation();
            });
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

    private void ExitWindowMiniPlayer(bool expandToPlayer, bool enterCinema = false, Action? afterExit = null)
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
            else
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
            ? (Brush)Application.Current.Resources["AppPanelSurfaceBrush"]
            : null;
        if (presentation == PlayerPresentation.Compact)
        {
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
        _cinemaPointerOverControls = false;
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
        if (enabled) ShowCinemaControls();
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
        HandleCinemaPointerMoved("PlayerPage");
    }

    private void VideoSurface_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (!_isCinemaMode) return;
        HandleCinemaPointerMoved(sender is FrameworkElement element ? element.Name : "VideoSurface");
    }

    private void HandleCinemaPointerMoved(string source)
    {
        if (!_cinemaPointerMoveDiagnosticWritten)
        {
            _cinemaPointerMoveDiagnosticWritten = true;
            LaunchDiagnostics.Write($"event=cinema.pointer-move source={source}");
        }
        ShowCinemaControls();
    }

    private void VlcTransportBar_PointerEntered(object sender, PointerRoutedEventArgs args) =>
        _cinemaPointerOverControls = true;

    private void VlcTransportBar_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        _cinemaPointerOverControls = false;
        if (_isCinemaMode && (_engine.IsPlaying || _engine.IsBuffering)) ShowCinemaControls();
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
        if (!_isCinemaMode || _currentPage != ShellPage.Player || !(_engine.IsPlaying || _engine.IsBuffering)) return;
        // Keep the overlay while it is being used.
        if (_cinemaPointerOverControls || IsTrackFlyoutOpen || VolumeFlyout.IsOpen || _isDraggingProgress)
        {
            _cinemaCursorIdleTimer.Start();
            return;
        }
        SetCinemaControlsVisible(false);
        SetCinemaCursorVisible(false);
    }

    private void ShowCinemaControls()
    {
        if (!_isCinemaMode || _currentPage != ShellPage.Player) return;
        SetCinemaControlsVisible(true);
        SetCinemaCursorVisible(true);
        _cinemaCursorIdleTimer.Stop();
        if (_engine.IsPlaying || _engine.IsBuffering) _cinemaCursorIdleTimer.Start();
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
        if (showCursor) SetCinemaCursorVisible(true);
        SetCinemaControlsVisible(true);
    }

    private void SetCinemaCursorVisible(bool visible)
    {
        if (_cinemaCursorHidden == !visible) return;
        _cinemaCursorHidden = !visible;
        _cinemaCursorController.SetHidden(_cinemaCursorHidden);
    }

    private void ApplyPlayerLayout()
    {
        var cinema = _isCinemaMode;
        var compact = _playerPresentation == PlayerPresentation.Compact;

        PlayerPage.Padding = cinema || compact ? new Thickness(0) : new Thickness(24);
        PlayerNavigation.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;
        PlayerNavigationRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        PlayerLayoutGrid.ColumnSpacing = cinema || compact ? 0 : 18;
        PlayerContentColumn.Width = new GridLength(cinema || compact ? 1 : 2.35, GridUnitType.Star);
        PlayerSideColumn.Width = new GridLength(cinema || compact ? 0 : 0.9, GridUnitType.Star);
        PlayerSidePanel.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;

        PlayerContentGrid.RowSpacing = cinema || compact ? 0 : 10;
        PlayerVideoRow.Height = new GridLength(1, GridUnitType.Star);
        PlayerDetailsRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        PlayerTransportRow.Height = cinema || compact ? new GridLength(0) : GridLength.Auto;
        PlayerDetailsPanel.Visibility = cinema || compact ? Visibility.Collapsed : Visibility.Visible;
        // Cinema: the same transport floats over the bottom of the video instead of sitting below it.
        Grid.SetRow(VlcTransportBar, cinema ? 0 : 2);
        VlcTransportBar.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        VlcTransportBar.VerticalAlignment = cinema ? VerticalAlignment.Bottom : VerticalAlignment.Stretch;
        VlcTransportBar.HorizontalAlignment = HorizontalAlignment.Stretch;
        VlcTransportBar.MaxWidth = cinema ? 1100 : double.PositiveInfinity;
        VlcTransportBar.Margin = cinema ? new Thickness(24, 0, 24, 28) : new Thickness(0);
        var panelBrush = (Brush)Application.Current.Resources["AppPanelSurfaceBrush"];
        VlcTransportBar.Background = cinema && panelBrush is SolidColorBrush solid
            ? new SolidColorBrush(Windows.UI.Color.FromArgb(0xE0, solid.Color.R, solid.Color.G, solid.Color.B))
            : panelBrush;
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
        NativeCinemaButton.Visibility = Visibility.Collapsed;
        NativePlayerElement.AreTransportControlsEnabled = false;
        if (compact)
        {
            CompactTitleText.Text = PlayerTitleText.Text;
            CompactSeekRow.Visibility = _currentSource?.Kind == StreamKind.Live ? Visibility.Collapsed : Visibility.Visible;
        }
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
        _heldSeekTimer.Stop();
        _heldSeekTimer.Tick -= HeldSeekTimer_Tick;
        Activated -= MainWindow_Activated;
        _vlcEngine.StateChanged -= VlcEngine_StateChanged;
        _nativeEngine.PlaybackFailed -= NativeEngine_PlaybackFailed;
        _accountPage.MinimizeToMiniModeChanged -= AccountPage_MinimizeToMiniModeChanged;
        _windowState.WindowMiniGeometryChanged -= WindowState_WindowMiniGeometryChanged;
        _windowState.Dispose();
        StopPlayerEpgUpdates();
        _playerEpgTimer.Tick -= PlayerEpgTimer_Tick;
        _epgCoordinator.EpgUpdated -= EpgCoordinator_EpgUpdated;
        _cinemaCursorIdleTimer.Stop();
        _cinemaCursorIdleTimer.Tick -= CinemaCursorIdleTimer_Tick;
        SetCinemaCursorVisible(true);
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

    public sealed class PlayerListEntry(Channel channel, bool isFavorite = false, PlaybackProgress? progress = null) : INotifyPropertyChanged
    {
        private bool _isCurrent;
        private bool _isFavorite = isFavorite;
        private PlaybackProgress? _progress = progress;
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
        // Row bar: full once watched, otherwise how far playback got (hidden until a duration is known).
        private PlaybackProgressPresentation ProgressPresentation => PlaybackProgressPresentation.For(_progress);
        public Visibility ProgressVisibility => ProgressPresentation.IsVisible ? Visibility.Visible : Visibility.Collapsed;
        public double ProgressValue => ProgressPresentation.Value;
        public Brush ProgressBarBrush => _isCurrent ? SelectedTextBrush : AccentBrush;
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
