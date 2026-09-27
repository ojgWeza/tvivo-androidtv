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
    private PlaybackService _playback;
    private readonly VlcPlaybackEngine _vlcEngine;
    private readonly FFmpegInteropPlaybackEngine _nativeEngine;
    private readonly PlaybackService _vlcPlayback;
    private readonly PlaybackService _nativePlayback;
    private IPlaybackEngine _engine;
    private readonly HomePage _homePage;
    private readonly ProviderSetupPage _providerSetupPage;
    private readonly CatalogLandingPage _catalogLandingPage;
    private readonly SqliteCatalogRepository _catalogRepository;
    private readonly PlaybackHandoff _playbackHandoff = new(TimeSpan.FromMilliseconds(350));
    private ShellPage? _currentPage;
    private ShellPage _playerReturnPage = ShellPage.Catalog;
    private IReadOnlyList<Channel> _playerSiblings = Array.Empty<Channel>();
    private readonly ObservableCollection<PlayerListEntry> _playerEntries = new();
    private IReadOnlyList<SeriesSeason> _playerSeriesSeasons = Array.Empty<SeriesSeason>();
    private string? _currentSeriesId;
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
    private readonly DispatcherTimer _playbackUiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _cinemaCursorIdleTimer = new() { Interval = TimeSpan.FromSeconds(3) };

    [DllImport("user32.dll")]
    private static extern int ShowCursor([MarshalAs(UnmanagedType.Bool)] bool show);

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
        _catalogRepository = App.Services.GetRequiredService<SqliteCatalogRepository>();
        InitializeComponent();
        PlayerRelatedList.ItemsSource = _playerEntries;
        Title = "Tvivo";
        var iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "Tvivo.ico");
        if (System.IO.File.Exists(iconPath)) AppWindow.SetIcon(iconPath);
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
        _cinemaCursorIdleTimer.Tick += CinemaCursorIdleTimer_Tick;
        PlayerPage.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(PlayerPage_PointerMoved), true);
        _catalogLandingPage.ChannelSelected += CatalogLandingPage_ChannelSelected;
        _homePage.ProviderSetupRequested += (_, _) => ShowPage(ShellPage.Setup);
        _providerSetupPage.ConnectionSaved += ProviderSetupPage_ConnectionSaved;
        PageHost.Children.Add(_homePage);
        PageHost.Children.Add(_providerSetupPage);
        _homePage.Visibility = Visibility.Visible;
        _providerSetupPage.Visibility = Visibility.Collapsed;
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
        var isStaticPage = page is ShellPage.Home or ShellPage.Setup;
        PageHost.Visibility = isStaticPage ? Visibility.Visible : Visibility.Collapsed;
        _homePage.Visibility = page == ShellPage.Home ? Visibility.Visible : Visibility.Collapsed;
        _providerSetupPage.Visibility = page == ShellPage.Setup ? Visibility.Visible : Visibility.Collapsed;
        PlayerPage.Visibility = isPlayer ? Visibility.Visible : Visibility.Collapsed;
        CatalogPageArea.Visibility = isCatalog ? Visibility.Visible : Visibility.Collapsed;
        if (isStaticPage) FadeIn(PageHost);
        else if (isPlayer) FadeIn(PlayerPage);
        else if (isCatalog) FadeIn(CatalogPageArea);
        _catalogLandingPage.SetActive(isCatalog);
        SetCatalogSearchEnabled(isCatalog && _catalogLandingPage.IsReadyForInteraction);
        if (updateTopNavigation)
            UpdateTopNavigationState();

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

    private async void CatalogLandingPage_ChannelSelected(object? sender, ChannelSelectedEventArgs args)
    {
        var selectionGeneration = Interlocked.Increment(ref _itemSelectionGeneration);
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
        _currentSeriesAccount = null;
        _currentSeriesMetadata = null;
        _playerSeriesSeasons = Array.Empty<SeriesSeason>();
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonComboBox.ItemsSource = null;
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        var type = args.Source.Kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Live;
        if (_catalogLandingPage.Account is { } account)
            _catalogRepository.RecordVisit(account, type, args.Channel.Id);
        _playerSiblings = args.Source.Kind == StreamKind.Movie
            ? args.RelatedChannels.Where(channel => channel.Source.Kind == StreamKind.Movie)
                .Append(args.Channel).DistinctBy(channel => channel.Id).ToArray()
            : Array.Empty<Channel>();
        _currentSource = args.Source;
        _nowPlayingChannel = args.Channel;
        PlayerTitleText.Text = args.Channel.DisplayName;
        PlayerSideTitle.Text = args.Source.Kind == StreamKind.Movie ? "More movies" : "Now playing";
        PlayerSideSubtitle.Text = args.Source.Kind == StreamKind.Movie
            ? _playerSiblings.Count <= 1 ? "No other movies are available." : "Other movies in this category"
            : string.Empty;
        PlayerNowPlayingText.Text = string.Empty;
        SetPlayerMetadata(null);
        PlayerVideoCurtain.Visibility = Visibility.Visible;
        _playbackCompletionShown = false;
        SetPlayerList(_playerSiblings, args.Channel);
        ShowPage(ShellPage.Player);
        _ = LoadPlayerMetadataAsync(args.Channel, type);
        _ = StartPlaybackAsync(args.Source);
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
        _currentSeriesAccount = account;
        _currentSeriesMetadata = details.Metadata;
        _playerSeriesSeasons = details.Seasons;
        ApplyMetadataToChannel(series, CatalogItemType.Series, details.Metadata);
        SetPlayerMetadata(details.Metadata ?? new CatalogMetadata(), CatalogItemType.Series);
        _playerSiblings = details.Seasons.SelectMany(season => season.Episodes)
            .Select(episode => ToEpisodeChannel(account, series.Id, episode)).ToArray();
        ConfigureSeasonSelector(details.Seasons);
        _catalogRepository.RecordVisit(account, CatalogItemType.Series, series.Id);
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
        if (_currentPage != ShellPage.Player)
            _playerReturnPage = _currentPage ?? ShellPage.Catalog;
        _currentSeriesId ??= episode.Metadata.TryGetValue("seriesId", out var seriesId) ? seriesId : null;
        _currentSeriesAccount = _catalogLandingPage.Account;
        _nowPlayingChannel = episode;
        _currentSource = episode.Source;
        if (_currentSeriesId is not null && _currentSeriesAccount is not null)
            _catalogRepository.UpdateSeriesPlayback(_currentSeriesAccount, _currentSeriesId, episode.Id, finished: false);
        _seriesCompletionRecorded = false;
        _playbackCompletionShown = false;
        PlayerTitleText.Text = episode.DisplayName;
        PlayerSideTitle.Text = "Season and episodes";
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
        if (!sameRows)
        {
            _playerEntries.Clear();
            foreach (var channel in channels)
                _playerEntries.Add(new PlayerListEntry(channel));
        }

        SetCurrentPlayerEntry(current);
    }

    private void SetCurrentPlayerEntry(Channel current)
    {
        foreach (var entry in _playerEntries)
            entry.IsCurrent = entry.Channel.Source.Kind == current.Source.Kind && entry.Channel.Id == current.Id;
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
        var generation = Interlocked.Increment(ref _playbackSessionGeneration);
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
                    await _playbackStopTask;
                    await StopBothPlaybackEnginesAsync();
                    await Task.Yield();
                    // PlaybackHandoff's delay resumes on a pool thread. Keep UI
                    // layout and both engine lifecycle calls on the window's STA.
                    PlayerPage.UpdateLayout();
                }),
                token => OnUiAsync(() => generation == Volatile.Read(ref _playbackSessionGeneration) && !sessionCts.IsCancellationRequested
                    ? _playback.PlayAsync(source, token)
                    : Task.FromResult(PlaybackAttemptResult.Cancelled)),
                sessionCts.Token);
        }
        catch (OperationCanceledException) when (sessionCts.IsCancellationRequested)
        {
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

        if (generation != Volatile.Read(ref _playbackSessionGeneration) || _currentPage != ShellPage.Player)
            return;
        LaunchDiagnostics.Write($"Playback result: engine={engineName}, kind={source.Kind}, result={result}");
        if (_isCinemaMode && result == PlaybackAttemptResult.FirstFrame)
            RestartCinemaCursorIdleTimer();
        StatusText.Text = PlaybackStatusMessage(result, source.Kind);
        if (result == PlaybackAttemptResult.FirstFrame)
            PlayerVideoCurtain.Visibility = Visibility.Collapsed;
        PauseButton.Content = result == PlaybackAttemptResult.FirstFrame
            ? "Pause"
            : result == PlaybackAttemptResult.Cancelled ? "Play" : "Retry";
        VolumeSlider.Value = _engine.Volume;
    }

    private void StopPlaybackForNavigation()
    {
        Interlocked.Increment(ref _itemSelectionGeneration);
        Interlocked.Increment(ref _metadataGeneration);
        Interlocked.Increment(ref _playbackSessionGeneration);
        var sessionCts = Interlocked.Exchange(ref _playbackSessionCts, null);
        sessionCts?.Cancel();
        sessionCts?.Dispose();

        _currentSource = null;
        _nowPlayingChannel = null;
        _currentSeriesId = null;
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
    }

    private async Task StopBothPlaybackEnginesAsync()
    {
        await Task.WhenAll(_vlcPlayback.StopAsync(), _nativePlayback.StopAsync());
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

    private static void FadeIn(UIElement element)
    {
        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.StopAnimation(nameof(Visual.Opacity));
        visual.Opacity = 0;
        var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(1f, 1f);
        animation.Duration = TimeSpan.FromMilliseconds(150);
        visual.StartAnimation(nameof(Visual.Opacity), animation);
    }

    private async Task LoadPlayerMetadataAsync(Channel channel, CatalogItemType type)
    {
        var generation = Interlocked.Increment(ref _metadataGeneration);
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

            if (generation != Volatile.Read(ref _metadataGeneration) || _nowPlayingChannel?.Id != channel.Id)
                return;
            metadata ??= new CatalogMetadata();
            ApplyMetadataToChannel(channel, type, metadata);
            SetPlayerMetadata(metadata, type);
        }
        catch
        {
            if (generation == Volatile.Read(ref _metadataGeneration) && _nowPlayingChannel?.Id == channel.Id)
                SetPlayerMetadata(new CatalogMetadata(), type);
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
            metadata.Rating is null ? null : $"★ {metadata.Rating}",
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
        if (args.ClickedItem is not PlayerListEntry entry || entry.Channel.Source.DirectUri is null) return;
        if (entry.Channel.Source.Kind == StreamKind.Episode)
            OpenSeriesEpisode(entry.Channel);
        else
            CatalogLandingPage_ChannelSelected(this, new ChannelSelectedEventArgs(entry.Channel, _playerSiblings));
    }

    private void PlayerBackButton_Click(object sender, RoutedEventArgs args) => ReturnFromPlayer();

    private void PlayerHomeButton_Click(object sender, RoutedEventArgs args) =>
        _ = ShowCatalogAsync(CatalogLandingPage.CatalogMode.MyTvivo);

    private void ReturnFromPlayer()
    {
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
            ApplyWindowChromeState();
            var focusTarget = _preCinemaFocus is { Visibility: Visibility.Visible, IsTabStop: true }
                ? _preCinemaFocus
                : CinemaButton;
            focusTarget.Focus(FocusState.Programmatic);
            _preCinemaFocus = null;
        }
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
        Catalog,
        Player
    }

    public sealed class PlayerListEntry(Channel channel) : INotifyPropertyChanged
    {
        private bool _isCurrent;
        private static Brush AccentBrush => (Brush)Application.Current.Resources["AppAccentBrush"];
        private static Brush NormalBrush => (Brush)Application.Current.Resources["AppTextBrush"];
        private static Brush SelectedTextBrush => (Brush)Application.Current.Resources["AppDeepBrush"];

        public Channel Channel { get; } = channel;
        public string Title => Channel.DisplayName;
        public Brush BackgroundBrush => _isCurrent ? AccentBrush : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        public Brush ForegroundBrush => _isCurrent ? SelectedTextBrush : NormalBrush;
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
