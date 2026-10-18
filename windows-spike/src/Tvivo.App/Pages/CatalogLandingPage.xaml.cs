using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml.Hosting;
using Windows.Storage.Streams;
using System.ComponentModel;
using System.Collections.ObjectModel;
using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public sealed partial class CatalogLandingPage : UserControl
{
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly SqliteCatalogRepository _repository = App.Services.GetRequiredService<SqliteCatalogRepository>();
    private readonly HoverMetadataLookup _hoverMetadataLookup;
    private readonly HoverRequestScheduler _hoverRequestScheduler = new();
    private CatalogCard? _hoveredCard;
    private FrameworkElement? _hoveredCardElement;
    private FrameworkElement? _cardToolTipOwner;
    private ToolTip? _cardToolTip;
    private TextBlock? _cardToolTipTitle;
    private TextBlock? _cardToolTipDetails;
    private TextBlock? _cardToolTipSummary;
    private TextBlock? _cardToolTipNow;
    private TextBlock? _cardToolTipNext;
    private TextBlock? _cardToolTipCast;
    private bool _summaryTriggeredByFocus;
    private HoverRequestScheduler.Lease? _hoverLease;
    private readonly CatalogRefreshService _refresh = App.Services.GetRequiredService<CatalogRefreshService>();
    private readonly CatalogRefreshPolicyStore _refreshPolicyStore = new();
    private readonly CatalogRefreshRetry _refreshRetry = new();
    private readonly CatalogRefreshTasks _refreshTasks;
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private CancellationTokenSource? _savedStartupAuthCts;
    private readonly EpgCoordinator _epgCoordinator = App.Services.GetRequiredService<EpgCoordinator>();
    internal bool EpgEnabled => _epgCoordinator.Feature.Enabled;
    private ProviderAccount? _account;
    private ProviderConnection? _connection;
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private string? _selectedGroupId;
    private string? _openShelfId;
    private CatalogItemType? _openShelfType;
    private CatalogMode _activeMode = CatalogMode.MyTvivo;
    private CategorySort _categorySort = CategorySort.Visited;
    private CategorySort _renderedShelfSort = CategorySort.Visited;
    private int _offset;
    private long _loadGeneration;
    private readonly HashSet<(string AccountId, CatalogItemType Type)> _scheduledRetries = new();
    private readonly HashSet<(string AccountId, CatalogItemType Type)> _refreshedThisStart = new();
    private readonly DateTimeOffset _sessionStartedAt = DateTimeOffset.UtcNow;
    private long _pageQueryGeneration;
    private bool _suppressSearchChanged;
    private bool _isReadyForInteraction;
    private bool _isActive;
    private bool _firstCatalogLoadCompleted;
    private bool _cachedStartupCatalogActive;
    private bool _showModeLoadingFrame;
    private long _modeTransitionGeneration;
    private long _contentFadeGeneration;
    private long _browseRestoreGeneration;
    private readonly SemaphoreSlim _modeTransitionGate = new(1, 1);
    private CatalogCard? _spotlightCard;
    private readonly ObservableCollection<CatalogCard> _spotlightCards = new();
    private readonly SpotlightSettingsService _spotlightSettingsService;
    private SpotlightStripState _spotlightState = SpotlightStripState.Create(0);
    private CancellationTokenSource? _spotlightDwellCts;
    private CancellationTokenSource? _spotlightMetadataCts;
    private CatalogCard? _spotlightMetadataCard;
    private string? _spotlightFocusedId;
    private bool _spotlightArtworkLayoutPending;
    private int _spotlightWindowFirst = -1;
    private int _spotlightWindowLast = -1;
    private readonly Dictionary<Button, Storyboard> _spotlightWidthAnimations = new();
    private readonly Windows.UI.ViewManagement.UISettings _uiSettings = new();
    private readonly Dictionary<CatalogSnapshotKey, CachedCatalogSnapshot> _snapshotCache = new();
    private readonly Queue<CatalogSnapshotKey> _snapshotCacheOrder = new();
    private long _snapshotCacheBytes;
    private readonly ObservableCollection<ShelfRowViewModel> _renderedShelves = new();
    private IReadOnlyList<CatalogShelf>? _deferredShelfStructure;
    private long _lastShelfScrollAt;
    private long _shelfRowReplacements;
    private long _shelfRowInserts;
    private long _shelfRowMoves;
    private long _shelfRowRemovals;
    private long _shelfRowDeferredCommits;
    private long _lastShelfRowsPerfAt;
    private bool _activityDataDirty;
    private bool _favoriteDataDirty;
    private readonly Dictionary<CatalogMode, ModeInteractionState> _modeStates = new();
    private readonly Dictionary<(CatalogMode Mode, string ShelfId), BrowsePosition> _browsePositions = new();
    private IReadOnlyDictionary<string, PlaybackProgress> _movieProgressById = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
    private IReadOnlyDictionary<string, PlaybackProgress> _seriesProgressById = new Dictionary<string, PlaybackProgress>(StringComparer.Ordinal);
    // Managed-retention estimates; decoded artwork has a separate 128-entry / 96 MiB budget.
    private const int SnapshotCacheCapacity = 8;
    private const long SnapshotCacheByteLimit = 64L * 1024 * 1024;
    private const int BrowsePositionCapacity = 64;
    private const int ArtworkFailureCategoryCapacity = 64;
    private const int EpgLoadedAccountCapacity = 32;
    private static readonly Regex QualityToken = new(
        @"(?i)(?<![\p{L}\p{N}])(?<quality>FULL[\s_\-]?HD|FHD|UHD|4K|2160p|1080[pi]|720p|576p|480p|HD|SD)(?![\p{L}\p{N}])",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex CodecToken = new(
        @"(?i)(?<![\p{L}\p{N}])(?:HEVC|H[\s.]?26[45]|x26[45])(?![\p{L}\p{N}])",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex EmptyBrackets = new(@"[\(\[\{]\s*[\)\]\}]", RegexOptions.Compiled);
    private static readonly Regex RepeatedSpaces = new(@"\s{2,}", RegexOptions.Compiled);
    private readonly HashSet<GridView> _wiredShelfGrids = new();
    private readonly SemaphoreSlim _artworkSlots = new(6);
    private readonly SemaphoreSlim _spotlightArtworkSlots = new(2);
    private static readonly TimeSpan ArtworkRequestTimeout = TimeSpan.FromSeconds(30);
    private static readonly HttpClient ArtworkClient = CreateArtworkClient();
    private static readonly ArtworkDiskCache ArtworkBytes = new(ArtworkClient);
    private static long _artworkSetSourceTicks;
    private static long _artworkStreamCopyTicks;
    private static long _artworkSourceBytes;
    private static long _artworkSourceBytesMax;
    private static long _artworkJpegCount;
    private static long _artworkPngCount;
    private static long _artworkWebpCount;
    private static long _artworkOtherCount;
    private static int _artworkPixelWidthMax;
    private static int _artworkPixelHeightMax;
    private static readonly object ArtworkPagesLock = new();
    private static readonly List<WeakReference<CatalogLandingPage>> ArtworkPages = new();
    private readonly Dictionary<Image, ArtworkLoad> _artworkLoads = new();
    private readonly Dictionary<Image, AppliedArtwork> _appliedArtwork = new();
    private readonly HashSet<Image> _loadedArtworkImages = new(ReferenceEqualityComparer.Instance);
    private readonly HashSet<UIElement> _liveCatalogCardContainers = new(ReferenceEqualityComparer.Instance);
    private readonly HashSet<FrameworkElement> _liveShelfContainerElements = new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<FrameworkElement, string> _lastShelfContainerIds = new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<UIElement, GridView> _catalogCardContainerOwners = new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<string, LinkedListNode<CachedArtworkBitmap>> _artworkBitmapCache = new(StringComparer.Ordinal);
    private readonly LinkedList<CachedArtworkBitmap> _artworkBitmapLru = new();
    private long _artworkBitmapCacheBytes;
    private long _shelfContainersRealized;
    private long _shelfContainersRecycled;
    private long _liveShelfContainers;
    private long _artworkImagesLoaded;
    private long _artworkImagesUnloaded;
    private long _refreshApplyDurationTicks;
    private long _refreshApplyMaxDurationTicks;
    private long _refreshApplyCount;
    private long _snapshotApplyDurationTicks;
    private long _snapshotApplyMaxDurationTicks;
    private long _snapshotApplyCount;
    private long _synchronizeShelvesDurationTicks;
    private long _synchronizeShelvesCount;
    private long _shelfPreviewLoadDurationTicks;
    private long _shelfPreviewLoadGeneration;
    private DispatcherQueueTimer? _shelfViewportTimer;
    private DispatcherQueueTimer? _shelfViewportPollTimer;
    private DispatcherQueueTimer? _shelfDeferredCommitTimer;
    private ScrollViewer? _shelfScrollViewer;
    private string _shelfViewportReason = "scroll";
    private long _lastShelfWindowLogAt;
    private readonly HashSet<(CatalogMode Mode, string ShelfId)> _shelfPreviewLoadsInFlight = new();
    private long _dispatchProbePostedAt;
    private long _dispatchProbeMaxTicks;
    private int _dispatchProbeOutstanding;
    private long _dispatchProbeLastTickAt;
    private int _dispatchProbeLastGc2Count;
    private long _dispatchProbeLastGcPauseTicks;
    private readonly object _dispatchProbeGapLogLock = new();
    private long _dispatchProbeGapWindowStartedAt;
    private int _dispatchProbeGapCount;
    private readonly object _processSampleLock = new();
    private long _lastProcessSampleAt;
    private double _lastProcessCpuSeconds;
    private int _lastPerfGc0Count;
    private int _lastPerfGc1Count;
    private int _lastPerfGc2Count;
    private long _lastPerfGcPauseTicks;
    private readonly DispatcherTimer _perfSummaryTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly System.Threading.Timer _dispatchProbeTimer;
    private PerfSurfaceSnapshot? _lastPerfSurfaceSnapshot;
    private ArtworkPerfSnapshot? _lastArtworkPerfSnapshot;
    private readonly Dictionary<string, (long LastLoggedAt, int Count)> _artworkFailureLogState = new(StringComparer.Ordinal);
    // Decoded BGRA pixels dominate retained memory; 96 MiB holds roughly 120 poster-sized cards.
    private const long ArtworkBitmapCacheByteLimit = 96L * 1024 * 1024;
    private const int ArtworkBitmapCacheCountLimit = 128;

    private static HttpClient CreateArtworkClient()
    {
        var client = NetworkTally.CreateClient("artwork", ArtworkRequestTimeout);
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Tvivo/1.0");
        client.DefaultRequestHeaders.Accept.ParseAdd("image/jpeg,image/png,image/gif,image/*;q=0.8");
        return client;
    }
    private readonly DispatcherTimer _epgTimer = new() { Interval = TimeSpan.FromSeconds(60) };
    private readonly SemaphoreSlim _epgQueryGate = new(1, 1);
    private readonly SemaphoreSlim _epgStartGate = new(1, 1);
    private readonly HashSet<string> _epgMapLoadedAccounts = new(StringComparer.Ordinal);
    private CatalogSnapshot? _renderedSnapshot;
    private string? _renderedOpenShelfId;
    private GridView? _pressedShelfGrid;
    private GridView? _draggedShelfGrid;
    private ScrollViewer? _pressedShelfScrollViewer;
    private uint _shelfPointerId;
    private double _shelfPressX;
    private double _shelfPressOffset;
    private bool _shelfDragStarted;
    private const int PageSize = CatalogGridPaging.PageSize;
    private const int ShelfPreviewSize = 12;
    private const int PrefetchShelves = 6;
    private const int RetainedShelfMargin = 10;
    private const string GenericLoadError = "Couldn't load the catalog. Check your connection and provider details, then retry.";
    public ProviderAccount? Account => _account;
    public ProviderConnection? Connection => _connection;
    public string PlaybackUnavailableMessage => _connection is null
        ? "Playback is unavailable until the saved account can be authenticated."
        : "This channel does not have a playable stream URL yet.";
    public CatalogMode ActiveMode => _activeMode;
    public bool IsReadyForInteraction => _isReadyForInteraction;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;
    public event EventHandler? InteractionReadinessChanged;
    public event EventHandler? VisibleCatalogDataChanged;
    public event Action<string>? StartupPhaseCompleted;
    public event Action<string>? SearchTextChanged;
    // Raised when the user changes what they are browsing (mode, category, sort, search), so
    // deferred visit ranking can be applied then and not while a returning list is on screen.
    public event EventHandler? BrowseContextChanging;
    public event EventHandler? NavigateToSpotlightSettingsRequested;

    public Task<string?> GetCategoryNameForAsync(Channel channel)
    {
        if (_account is not { } account || string.IsNullOrWhiteSpace(channel.GroupId))
            return Task.FromResult<string?>(null);

        var type = TypeForSource(channel.Source.Kind);
        var groupId = channel.GroupId;
        return Task.Run(() => _repository.GetGroups(account, type)
            .FirstOrDefault(group => group.Id == groupId)?.DisplayName);
    }

    public Task<Channel?> ResolvePlayableChannelAsync(Channel channel)
    {
        if (channel.Source.DirectUri is not null)
            return Task.FromResult<Channel?>(channel);
        if (_account is not { } account || _connection is not { } connection ||
            !StringComparer.Ordinal.Equals(account.AccountId, channel.ProviderAccountId))
            return Task.FromResult<Channel?>(null);
        var type = TypeForSource(channel.Source.Kind);
        return Task.Run(() => _repository.GetChannel(account, connection, type, channel.Id));
    }

    public Task<IReadOnlyList<Channel>> GetFullCategoryChannelsForAsync(Channel channel)
    {
        if (_account is not { } account || string.IsNullOrWhiteSpace(channel.GroupId))
            return Task.FromResult<IReadOnlyList<Channel>>(Array.Empty<Channel>());

        var type = TypeForSource(channel.Source.Kind);
        var groupId = channel.GroupId;
        var connection = _connection;
        return Task.Run<IReadOnlyList<Channel>>(() =>
            _repository.GetAllChannels(account, connection, type, groupId, PageSize));
    }

    public CatalogLandingPage()
    {
        PerfScope.Logger = LaunchDiagnostics.Write;
        _hoverMetadataLookup = new HoverMetadataLookup(_provider, _repository, log: LaunchDiagnostics.Write);
        _spotlightSettingsService = App.Services.GetRequiredService<SpotlightSettingsService>();
        _refreshTasks = new CatalogRefreshTasks((account, type, cancellationToken) =>
            Task.Run(() => _refresh.RefreshTypeAsync(account, type, cancellationToken), cancellationToken));
        lock (ArtworkPagesLock) ArtworkPages.Add(new WeakReference<CatalogLandingPage>(this));
        InitializeComponent();
        SpotlightTouchHint.Visibility = Windows.Devices.Input.PointerDevice.GetPointerDevices()
            .Any(device => device.PointerDeviceType == Windows.Devices.Input.PointerDeviceType.Touch)
            ? Visibility.Visible : Visibility.Collapsed;
        ShelvesItems.ContainerContentChanging += ShelfContainerContentChanging;
        OpenShelfGrid.ContainerContentChanging += CatalogCardContainerContentChanging;
        OpenShelfGrid.Unloaded += OpenShelfGrid_Unloaded;
        CatalogPageRoot.ActualThemeChanged += CatalogPageRoot_ActualThemeChanged;
        SpotlightStripItems.ItemsSource = _spotlightCards;
        SpotlightStripItems.LayoutUpdated += SpotlightStripItems_LayoutUpdated;
        _epgTimer.Tick += EpgTimer_Tick;
        _perfSummaryTimer.Tick += PerfSummaryTimer_Tick;
        _lastPerfGc0Count = GC.CollectionCount(0);
        _lastPerfGc1Count = GC.CollectionCount(1);
        _lastPerfGc2Count = GC.CollectionCount(2);
        _lastPerfGcPauseTicks = GC.GetTotalPauseDuration().Ticks;
        _lastArtworkPerfSnapshot = CaptureArtworkPerfSnapshot();
        _lastProcessSampleAt = System.Diagnostics.Stopwatch.GetTimestamp();
        _lastShelfRowsPerfAt = System.Diagnostics.Stopwatch.GetTimestamp();
        using (var process = System.Diagnostics.Process.GetCurrentProcess())
            _lastProcessCpuSeconds = process.TotalProcessorTime.TotalSeconds;
        _dispatchProbeTimer = new System.Threading.Timer(DispatchProbeTimer_Tick, null,
            TimeSpan.FromMilliseconds(250), TimeSpan.FromMilliseconds(250));
        _epgCoordinator.EpgUpdated += EpgCoordinator_EpgUpdated;
        Unloaded += CatalogLandingPage_Unloaded;
        if (!PerfToggles.IsDisabled("perftimers"))
            _perfSummaryTimer.Start();
        UpdateModeChrome();
        _lastPerfSurfaceSnapshot = CreatePerfSurfaceSnapshot(0);
    }

    private Brush ThemeBrush(string key) => AppThemeBrushResolver.For(CatalogPageRoot, key);

    private void CatalogPageRoot_ActualThemeChanged(FrameworkElement sender, object args)
    {
        using var perfScope = PerfScope.Begin("CatalogThemeApply");
        UpdateModeChrome();
        foreach (var button in SearchCategoryChips.Children.OfType<Button>())
        {
            var groupId = button.Tag as string ?? string.Empty;
            ApplySearchCategoryChipState(button, string.Equals(groupId, _selectedGroupId ?? string.Empty, StringComparison.Ordinal));
        }

        ApplyCardToolTipTheme();
    }

    private void ApplyCardToolTipTheme()
    {
        if (_cardToolTip is not { } toolTip) return;
        toolTip.RequestedTheme = CatalogPageRoot.ActualTheme;
        toolTip.Background = ThemeBrush("AppPanelSurfaceBrush");
        toolTip.Foreground = ThemeBrush("AppTextBrush");
        toolTip.BorderBrush = ThemeBrush("AppLineBrush");
        var foreground = ThemeBrush("AppTextBrush");
        foreach (var textBlock in new[]
                 { _cardToolTipTitle, _cardToolTipDetails, _cardToolTipSummary, _cardToolTipNow, _cardToolTipNext, _cardToolTipCast })
            if (textBlock is not null) textBlock.Foreground = foreground;
    }

    public void SetActive(bool active)
    {
        _isActive = active;
        if (!active) CancelHoverRequest();
        if (active) ScheduleShelfViewportUpdate("activate");
        else _shelfViewportPollTimer?.Stop();
        if (!active) CancelSpotlightAsyncWork();
        else
        {
            RequestSpotlightArtworkLayout();
            if (_spotlightFocusedId is { } focusedId)
                DispatcherQueue.TryEnqueue(() => _spotlightCards.FirstOrDefault(card => card.Id == focusedId)
                    ?.SpotlightButton?.Focus(FocusState.Programmatic));
        }
        UpdateEpgLifecycle();
    }

    public void InvalidateCatalogSnapshots()
    {
        _snapshotCache.Clear();
        _snapshotCacheOrder.Clear();
        _snapshotCacheBytes = 0;
        _renderedSnapshot = null;
        _renderedOpenShelfId = null;
    }

    public void ClearAccountState()
    {
        CancelSavedStartupAuthentication();
        CancelHoverRequest();
        Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        _account = null;
        _connection = null;
        _cachedStartupCatalogActive = false;
        _selectedGroupId = null;
        _openShelfId = null;
        _openShelfType = null;
        _offset = 0;
        _firstCatalogLoadCompleted = false;
        _modeStates.Clear();
        _browsePositions.Clear();
        SetSearchTextWithoutReload(string.Empty);
        CancelSpotlightAsyncWork();
        ClearSpotlightCards();
        _scheduledRetries.Clear();
        _activityDataDirty = false;
        _favoriteDataDirty = false;
        InvalidateCatalogSnapshots();
        ShelvesItems.ItemsSource = null;
        ClearRenderedShelves();
        CancelArtworkLoads();
        RefreshInfoBar.IsOpen = false;
        ShowError("No saved provider connection. Choose Account in the top navigation to connect a provider.");
    }

    public void NotifyVisitRecorded()
    {
        if (_account is null) return;
        _activityDataDirty = true;
    }

    public void NotifyFavoriteChanged()
    {
        if (_account is null) return;
        _favoriteDataDirty = true;
    }

    public void Shutdown()
    {
        CancelSavedStartupAuthentication();
        CancelSpotlightAsyncWork();
        SpotlightStripItems.LayoutUpdated -= SpotlightStripItems_LayoutUpdated;
        _epgTimer.Stop();
        _epgTimer.Tick -= EpgTimer_Tick;
        _perfSummaryTimer.Stop();
        _perfSummaryTimer.Tick -= PerfSummaryTimer_Tick;
        _dispatchProbeTimer.Dispose();
        _epgCoordinator.EpgUpdated -= EpgCoordinator_EpgUpdated;
        CancelArtworkLoads();
    }

    private void DispatchProbeTimer_Tick(object? state)
    {
        using var perfScope = PerfScope.Begin("DispatchProbeTimer");
        var tickAt = System.Diagnostics.Stopwatch.GetTimestamp();
        var previousTickAt = Interlocked.Exchange(ref _dispatchProbeLastTickAt, tickAt);
        var gc2Count = GC.CollectionCount(2);
        var previousGc2Count = Interlocked.Exchange(ref _dispatchProbeLastGc2Count, gc2Count);
        var gcPauseTicks = GC.GetTotalPauseDuration().Ticks;
        var previousGcPauseTicks = Interlocked.Exchange(ref _dispatchProbeLastGcPauseTicks, gcPauseTicks);
        var gapMs = previousTickAt == 0 ? 0 : ToMilliseconds(tickAt - previousTickAt);
        var gcPauseMsSinceLast = TimeSpan.FromTicks(Math.Max(0, gcPauseTicks - previousGcPauseTicks)).TotalMilliseconds;
        var gc2Delta = gc2Count - previousGc2Count;

        WriteProcessPerfSample(tickAt);

        if (Interlocked.CompareExchange(ref _dispatchProbeOutstanding, 1, 0) != 0)
        {
            WriteDispatchProbeGap(gapMs, gcPauseMsSinceLast, gc2Delta, tickAt, -1);
            return;
        }

        var postedAt = System.Diagnostics.Stopwatch.GetTimestamp();
        Interlocked.Exchange(ref _dispatchProbePostedAt, postedAt);
        var enqueueStartedAt = System.Diagnostics.Stopwatch.GetTimestamp();
        var enqueued = DispatcherQueue.TryEnqueue(() =>
        {
            using var perfScope = PerfScope.Begin("DispatchProbeCallback");
            var callbackStartedAt = System.Diagnostics.Stopwatch.GetTimestamp();
            var probePostedAt = Interlocked.Read(ref _dispatchProbePostedAt);
            var elapsedTicks = callbackStartedAt - probePostedAt;
            Interlocked.Exchange(ref _dispatchProbeOutstanding, 0);
            if (elapsedTicks <= 0) return;
            UpdateMaximum(ref _dispatchProbeMaxTicks, elapsedTicks);
            if (System.Diagnostics.Stopwatch.GetElapsedTime(0, elapsedTicks) > TimeSpan.FromMilliseconds(100))
            {
                var operations = PerfScope.GetOverlapping(probePostedAt, callbackStartedAt);
                var attribution = string.Join(";", operations.Select(operation =>
                    $"{operation.Name}:{ToMilliseconds(operation.DurationTicks):0.0}"));
                LaunchDiagnostics.Write($"event=perf.stall timestamp={DateTimeOffset.UtcNow:O} delayMs={ToMilliseconds(elapsedTicks):0.0} ops={attribution}");
            }
        });
        var enqueueMs = ToMilliseconds(System.Diagnostics.Stopwatch.GetTimestamp() - enqueueStartedAt);
        if (!enqueued)
        {
            Interlocked.Exchange(ref _dispatchProbeOutstanding, 0);
        }

        WriteDispatchProbeGap(gapMs, gcPauseMsSinceLast, gc2Delta, tickAt, enqueueMs);
    }

    private void WriteDispatchProbeGap(double gapMs, double gcPauseMsSinceLast, int gc2Delta, long now, double enqueueMs)
    {
        if (gapMs <= 1000 || !ShouldLogDispatchProbeGap(now)) return;
        LaunchDiagnostics.Write(
            $"event=perf.timergap timestamp={DateTimeOffset.UtcNow:O} gapMs={gapMs:0.0} " +
            $"gcPauseMsSinceLast={gcPauseMsSinceLast:0.0} gc2Delta={gc2Delta} " +
            $"tpPending={ThreadPool.PendingWorkItemCount} enqueueMs={enqueueMs:0.0}");
    }

    private bool ShouldLogDispatchProbeGap(long now)
    {
        lock (_dispatchProbeGapLogLock)
        {
            if (_dispatchProbeGapWindowStartedAt == 0 ||
                System.Diagnostics.Stopwatch.GetElapsedTime(_dispatchProbeGapWindowStartedAt, now) >= TimeSpan.FromSeconds(10))
            {
                _dispatchProbeGapWindowStartedAt = now;
                _dispatchProbeGapCount = 0;
            }

            if (_dispatchProbeGapCount >= 10) return false;
            _dispatchProbeGapCount++;
            return true;
        }
    }

    private void WriteProcessPerfSample(long now)
    {
        lock (_processSampleLock)
        {
            if (System.Diagnostics.Stopwatch.GetElapsedTime(_lastProcessSampleAt, now) < TimeSpan.FromSeconds(10))
                return;

            using var process = System.Diagnostics.Process.GetCurrentProcess();
            var cpuSeconds = process.TotalProcessorTime.TotalSeconds;
            var cpuSecondsDelta = cpuSeconds - _lastProcessCpuSeconds;
            _lastProcessCpuSeconds = cpuSeconds;
            _lastProcessSampleAt = now;
            LaunchDiagnostics.Write(
                $"event=perf.process timestamp={DateTimeOffset.UtcNow:O} cpuSecondsDelta={cpuSecondsDelta:0.000} " +
                $"threads={process.Threads.Count} handles={process.HandleCount}");
        }
    }

    private void PerfSummaryTimer_Tick(object? sender, object args)
    {
        using var perfScope = PerfScope.Begin("PerfSummaryTimer");
        WriteArtworkPerfSample();
        WriteShelfRowsPerfSample();
        var dispatchMaxTicks = Interlocked.Exchange(ref _dispatchProbeMaxTicks, 0);
        var snapshot = CreatePerfSurfaceSnapshot(dispatchMaxTicks);
        if (_lastPerfSurfaceSnapshot == snapshot && snapshot.GcPauseMs <= 50) return;

        _lastPerfSurfaceSnapshot = snapshot with { DispatchMaxTicks = 0 };
        var liveImages = _loadedArtworkImages.Count;
        LaunchDiagnostics.Write(
            $"event=perf.surface timestamp={DateTimeOffset.UtcNow:O} " +
            $"shelfRealized={snapshot.ShelfContainersRealized} shelfRecycled={snapshot.ShelfContainersRecycled} liveShelves={snapshot.LiveShelfContainers} " +
            $"shelvesLoaded={snapshot.ShelvesLoaded} shelvesSkeleton={snapshot.ShelvesSkeleton} shelfPreviewLoadMs={ToMilliseconds(snapshot.ShelfPreviewLoadDurationTicks):0.0} " +
            $"liveCards={snapshot.LiveCatalogCards} imageLoaded={snapshot.ArtworkImagesLoaded} imageUnloaded={snapshot.ArtworkImagesUnloaded} liveImages={liveImages} " +
            $"artworkActive={snapshot.ActiveArtworkLoads} artworkApplied={snapshot.AppliedArtworkLoads} " +
            $"cacheEntries={snapshot.DecodedCacheEntries} cacheBytes={snapshot.DecodedCacheBytes} wiredShelfGrids={snapshot.WiredShelfGrids} " +
            $"refreshApplyMs={ToMilliseconds(snapshot.RefreshApplyDurationTicks):0.0} " +
            $"refreshApplyMaxMs={ToMilliseconds(snapshot.RefreshApplyMaxDurationTicks):0.0} refreshApplyCount={snapshot.RefreshApplyCount} " +
            $"snapshotApplyMs={ToMilliseconds(snapshot.SnapshotApplyDurationTicks):0.0} snapshotApplyMaxMs={ToMilliseconds(snapshot.SnapshotApplyMaxDurationTicks):0.0} snapshotApplyCount={snapshot.SnapshotApplyCount} " +
            $"synchronizeRenderedShelvesMs={ToMilliseconds(snapshot.SynchronizeShelvesDurationTicks):0.0} synchronizeRenderedShelvesCount={snapshot.SynchronizeShelvesCount} " +
            $"dispatchMaxMs={ToMilliseconds(snapshot.DispatchMaxTicks):0.0} " +
            $"gc0={snapshot.Gc0} gc1={snapshot.Gc1} gc2={snapshot.Gc2} gcPauseMs={snapshot.GcPauseMs:0.0} " +
            $"gcHeapMB={snapshot.GcHeapMB:0.0} gcCommittedMB={snapshot.GcCommittedMB:0.0} " +
            $"tpThreads={snapshot.TpThreads} tpPending={snapshot.TpPending}");
    }

    private void WriteArtworkPerfSample()
    {
        if (PerfToggles.IsDisabled("artwork")) return;
        var snapshot = CaptureArtworkPerfSnapshot();
        if (_lastArtworkPerfSnapshot == snapshot) return;
        _lastArtworkPerfSnapshot = snapshot;
        var cache = snapshot.Cache;
        LaunchDiagnostics.Write(
            $"event=perf.artwork fetchMs={ToMilliseconds(cache.FetchTicks):0.0} hashMs={ToMilliseconds(cache.HashTicks):0.0} " +
            $"storeMs={ToMilliseconds(cache.StoreTicks):0.0} trimMs={ToMilliseconds(cache.TrimTicks):0.0} trimCalls={cache.TrimCalls} " +
            $"streamCopyMs={ToMilliseconds(cache.StreamCopyTicks + snapshot.PageStreamCopyTicks):0.0} " +
            $"setSourceMs={ToMilliseconds(snapshot.SetSourceTicks):0.0} sourceBytes={snapshot.SourceBytes} sourceBytesMax={snapshot.SourceBytesMax} " +
            $"typeJpeg={snapshot.JpegCount} typePng={snapshot.PngCount} typeWebp={snapshot.WebpCount} typeOther={snapshot.OtherCount} " +
            $"pixelWidthMax={snapshot.PixelWidthMax} pixelHeightMax={snapshot.PixelHeightMax} liveCards={_liveCatalogCardContainers.Count}");
    }

    private static ArtworkPerfSnapshot CaptureArtworkPerfSnapshot()
    {
        var cache = ArtworkBytes.GetPerfCounters();
        return new ArtworkPerfSnapshot(
            cache, Interlocked.Read(ref _artworkStreamCopyTicks), Interlocked.Read(ref _artworkSetSourceTicks),
            Interlocked.Read(ref _artworkSourceBytes), Interlocked.Read(ref _artworkSourceBytesMax),
            Interlocked.Read(ref _artworkJpegCount), Interlocked.Read(ref _artworkPngCount),
            Interlocked.Read(ref _artworkWebpCount), Interlocked.Read(ref _artworkOtherCount),
            Volatile.Read(ref _artworkPixelWidthMax), Volatile.Read(ref _artworkPixelHeightMax));
    }

    private static void RecordArtworkSource(byte[] bytes)
    {
        Interlocked.Add(ref _artworkSourceBytes, bytes.Length);
        UpdateArtworkMax(ref _artworkSourceBytesMax, bytes.Length);
        if (bytes.AsSpan().StartsWith(new byte[] { 0xff, 0xd8, 0xff })) Interlocked.Increment(ref _artworkJpegCount);
        else if (bytes.AsSpan().StartsWith(new byte[] { 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a })) Interlocked.Increment(ref _artworkPngCount);
        else if (bytes.Length >= 12 && bytes.AsSpan(0, 4).SequenceEqual("RIFF"u8) && bytes.AsSpan(8, 4).SequenceEqual("WEBP"u8)) Interlocked.Increment(ref _artworkWebpCount);
        else Interlocked.Increment(ref _artworkOtherCount);
    }

    private static void UpdateArtworkMax(ref long maximum, long value)
    {
        long previous;
        do
        {
            previous = Interlocked.Read(ref maximum);
            if (value <= previous) return;
        } while (Interlocked.CompareExchange(ref maximum, value, previous) != previous);
    }

    private static void UpdateArtworkMax(ref int maximum, int value)
    {
        int previous;
        do
        {
            previous = Volatile.Read(ref maximum);
            if (value <= previous) return;
        } while (Interlocked.CompareExchange(ref maximum, value, previous) != previous);
    }

    private void WriteShelfRowsPerfSample()
    {
        var now = System.Diagnostics.Stopwatch.GetTimestamp();
        var elapsed = Math.Max(0.001, System.Diagnostics.Stopwatch.GetElapsedTime(_lastShelfRowsPerfAt, now).TotalSeconds);
        _lastShelfRowsPerfAt = now;
        var replacements = Interlocked.Exchange(ref _shelfRowReplacements, 0);
        var inserts = Interlocked.Exchange(ref _shelfRowInserts, 0);
        var moves = Interlocked.Exchange(ref _shelfRowMoves, 0);
        var removals = Interlocked.Exchange(ref _shelfRowRemovals, 0);
        var commits = Interlocked.Exchange(ref _shelfRowDeferredCommits, 0);
        if (replacements + inserts + moves + removals + commits == 0) return;
        LaunchDiagnostics.Write($"event=perf.shelfrows replacePerSec={replacements / elapsed:0.0} insertPerSec={inserts / elapsed:0.0} movePerSec={moves / elapsed:0.0} removePerSec={removals / elapsed:0.0} deferredCommitPerSec={commits / elapsed:0.0}");
    }

    private PerfSurfaceSnapshot CreatePerfSurfaceSnapshot(long dispatchMaxTicks)
    {
        var gc0Count = GC.CollectionCount(0);
        var gc1Count = GC.CollectionCount(1);
        var gc2Count = GC.CollectionCount(2);
        var gcPauseTicks = GC.GetTotalPauseDuration().Ticks;
        var gc0Delta = gc0Count - _lastPerfGc0Count;
        var gc1Delta = gc1Count - _lastPerfGc1Count;
        var gc2Delta = gc2Count - _lastPerfGc2Count;
        var gcPauseMs = TimeSpan.FromTicks(Math.Max(0, gcPauseTicks - _lastPerfGcPauseTicks)).TotalMilliseconds;
        _lastPerfGc0Count = gc0Count;
        _lastPerfGc1Count = gc1Count;
        _lastPerfGc2Count = gc2Count;
        _lastPerfGcPauseTicks = gcPauseTicks;
        var memoryInfo = GC.GetGCMemoryInfo();

        return new PerfSurfaceSnapshot(
            Interlocked.Read(ref _shelfContainersRealized),
            Interlocked.Read(ref _shelfContainersRecycled),
            Interlocked.Read(ref _liveShelfContainers),
            _liveCatalogCardContainers.Count,
            Interlocked.Read(ref _artworkImagesLoaded),
            Interlocked.Read(ref _artworkImagesUnloaded),
            _artworkLoads.Count,
            _appliedArtwork.Count,
            _artworkBitmapCache.Count,
            _artworkBitmapCacheBytes,
            _wiredShelfGrids.Count,
            Interlocked.Read(ref _refreshApplyDurationTicks),
            Interlocked.Read(ref _refreshApplyMaxDurationTicks),
            Interlocked.Read(ref _refreshApplyCount),
            Interlocked.Read(ref _snapshotApplyDurationTicks),
            Interlocked.Read(ref _snapshotApplyMaxDurationTicks),
            Interlocked.Read(ref _snapshotApplyCount),
            Interlocked.Read(ref _synchronizeShelvesDurationTicks),
            Interlocked.Read(ref _synchronizeShelvesCount),
            _renderedShelves.Count(shelf => shelf.Cards.Count > 0),
            _renderedShelves.Count(shelf => shelf.Cards.Count == 0),
            Interlocked.Read(ref _shelfPreviewLoadDurationTicks),
            dispatchMaxTicks,
            gc0Delta,
            gc1Delta,
            gc2Delta,
            gcPauseMs,
            GC.GetTotalMemory(false) / (1024d * 1024d),
            memoryInfo.TotalCommittedBytes / (1024d * 1024d),
            ThreadPool.ThreadCount,
            ThreadPool.PendingWorkItemCount);
    }

    private static double ToMilliseconds(long stopwatchTicks) =>
        stopwatchTicks * 1000d / System.Diagnostics.Stopwatch.Frequency;

    private static void UpdateMaximum(ref long target, long value)
    {
        var observed = Interlocked.Read(ref target);
        while (value > observed)
        {
            var previous = Interlocked.CompareExchange(ref target, value, observed);
            if (previous == observed) return;
            observed = previous;
        }
    }

    private readonly record struct PerfSurfaceSnapshot(
        long ShelfContainersRealized,
        long ShelfContainersRecycled,
        long LiveShelfContainers,
        long LiveCatalogCards,
        long ArtworkImagesLoaded,
        long ArtworkImagesUnloaded,
        int ActiveArtworkLoads,
        int AppliedArtworkLoads,
        int DecodedCacheEntries,
        long DecodedCacheBytes,
        int WiredShelfGrids,
        long RefreshApplyDurationTicks,
        long RefreshApplyMaxDurationTicks,
        long RefreshApplyCount,
        long SnapshotApplyDurationTicks,
        long SnapshotApplyMaxDurationTicks,
        long SnapshotApplyCount,
        long SynchronizeShelvesDurationTicks,
        long SynchronizeShelvesCount,
        int ShelvesLoaded,
        int ShelvesSkeleton,
        long ShelfPreviewLoadDurationTicks,
        long DispatchMaxTicks,
        int Gc0,
        int Gc1,
        int Gc2,
        double GcPauseMs,
        double GcHeapMB,
        double GcCommittedMB,
        int TpThreads,
        long TpPending);

    private readonly record struct ArtworkPerfSnapshot(
        ArtworkDiskCachePerfCounters Cache,
        long PageStreamCopyTicks,
        long SetSourceTicks,
        long SourceBytes,
        long SourceBytesMax,
        long JpegCount,
        long PngCount,
        long WebpCount,
        long OtherCount,
        int PixelWidthMax,
        int PixelHeightMax);

    internal static void ClearArtworkCache()
    {
        lock (ArtworkPagesLock)
            ArtworkPages.RemoveAll(reference => !reference.TryGetTarget(out _));
        ArtworkBytes.Clear();
        lock (ArtworkPagesLock)
        {
            foreach (var reference in ArtworkPages)
                if (reference.TryGetTarget(out var page)) page.ClearDecodedArtworkCache();
        }
    }

    private void ClearDecodedArtworkCache()
    {
        var images = _artworkLoads.Keys.Concat(_appliedArtwork.Keys).Distinct().ToArray();
        CancelArtworkLoads();
        _artworkBitmapCache.Clear();
        _artworkBitmapLru.Clear();
        _artworkBitmapCacheBytes = 0;
        foreach (var image in images)
        {
            var item = image.DataContext;
            var url = item switch
                {
                    CatalogCard card => card.ArtworkUrl,
                    HomePage.HomeShelfCard homeCard => homeCard.ArtworkUrl,
                    _ => null,
                };
            _appliedArtwork.Remove(image);
            image.Source = null;
            image.Opacity = 0;
            if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Visible;
            if (image.IsLoaded && !string.IsNullOrWhiteSpace(url)) StartArtwork(image, url);
        }
    }

    public async Task PrepareModeAsync(CatalogMode mode)
    {
        var transitionGeneration = Interlocked.Increment(ref _modeTransitionGeneration);
        ResetContentFadeForGeneration(transitionGeneration);
        if (_activeMode == mode)
        {
            ContentState.IsHitTestVisible = true;
            // Same-mode navigation still starts a fresh cache-first load. Keep a
            // ready snapshot interactive, but don't leave an empty/error surface
            // looking ready while that load is about to retry.
            if (_renderedSnapshot is not null)
                SetState(content: true);
            else if (ContentState.Visibility != Visibility.Visible && LoadingState.Visibility != Visibility.Visible)
                SetState(loading: true);
            return;
        }

        CancelHoverRequest();

        var leavingOpenFolder = _renderedSnapshot is not null && _renderedOpenShelfId is not null;
        if (leavingOpenFolder)
        {
            ContentState.IsHitTestVisible = false;
            await FadeCatalogContentAsync(fadeOut: true, transitionGeneration: transitionGeneration);
            if (transitionGeneration != Volatile.Read(ref _modeTransitionGeneration)) return;
        }

        // Invalidate any load tied to the previous mode before replacing its UI.
        Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        Interlocked.Increment(ref _modeTransitionGeneration);
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        // Each tab keeps its own place: remember where this one was scrolled before leaving it.
        if (_renderedSnapshot?.Mode == _activeMode && ContentState.Visibility == Visibility.Visible)
            CaptureBrowseState();
        SaveModeState(_activeMode);
        _activeMode = mode;
        UpdateEpgLifecycle();
        var keepVisibleSnapshot = _renderedSnapshot is not null;
        _showModeLoadingFrame = !keepVisibleSnapshot;
        CancelSpotlightAsyncWork();
        _openShelfType = null;
        if (_modeStates.TryGetValue(mode, out var state))
        {
            _openShelfId = state.OpenShelfId;
            _openShelfType = state.OpenShelfType;
            _selectedGroupId = state.SelectedGroupId;
            _offset = state.Offset;
            SetSearchTextWithoutReload(state.Filter);
        }
        else
        {
            _openShelfId = null;
            _openShelfType = null;
            _selectedGroupId = null;
            _offset = 0;
            SetSearchTextWithoutReload(string.Empty);
        }
        if (_savedStartupAuthCts is null && !_cachedStartupCatalogActive &&
            !RefreshInfoBar.Message.StartsWith("Could not update.", StringComparison.Ordinal))
            RefreshInfoBar.IsOpen = false;
        UpdateModeChrome();
        UpdateEmptyCopy();
        if (keepVisibleSnapshot)
        {
            // Keep the old category attached until the incoming snapshot is ready.
            // The snapshot swap itself is performed while the content is faded out.
            ContentState.IsHitTestVisible = false;
            _ = WatchModeSwapAsync(mode, Volatile.Read(ref _modeTransitionGeneration));
        }
        else
        {
            ShelvesItems.ItemsSource = null;
            ClearRenderedShelves();
            _renderedSnapshot = null;
            CancelArtworkLoads();
            OpenShelfGrid.ItemsSource = null;
            SpotlightPanel.Visibility = Visibility.Collapsed;
            SetState(loading: true);
        }
    }

    private bool ModeSwapPending(CatalogMode mode, long transitionGeneration) =>
        transitionGeneration == Volatile.Read(ref _modeTransitionGeneration) && _activeMode == mode &&
        _renderedSnapshot is not null && _renderedSnapshot.Mode != mode;

    /// <summary>
    /// While the incoming mode loads, the previous mode's shelves stay attached (no flicker on fast swaps).
    /// If that takes noticeable time the old content would sit under the new tab, so cover it with the
    /// loading state; if the swap never happens, ask for the page again.
    /// </summary>
    private async Task WatchModeSwapAsync(CatalogMode mode, long transitionGeneration)
    {
        await Task.Delay(300);
        if (!ModeSwapPending(mode, transitionGeneration)) return;
        SetState(loading: true);
        await Task.Delay(2700);
        if (!ModeSwapPending(mode, transitionGeneration)) return;
        LaunchDiagnostics.Write($"event=catalog.mode-swap-stalled mode={mode}");
        await ShowCachedPageAsync();
    }

    public async Task SetModeAsync(CatalogMode mode)
    {
        if (_activeMode != mode) CommitDeferredShelfStructure();
        if (_activeMode != mode) CancelShelfPreviewLoads();
        if (_activeMode == mode)
        {
            await PrepareModeAsync(mode);
            return;
        }

        await PrepareModeAsync(mode);
        if (_activeMode != mode) return;
        await ShowCachedPageAsync();
    }

    public void SetSearchText(string text)
    {
        if (!_isReadyForInteraction)
            return;
        if (SearchBox.Text == text)
            return;

        CommitDeferredShelfStructure();
        CancelShelfPreviewLoads();
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        _suppressSearchChanged = true;
        try
        {
            SearchBox.Text = text;
        }
        finally
        {
            _suppressSearchChanged = false;
        }
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(text) ? Visibility.Collapsed : Visibility.Visible;
        SearchTextChanged?.Invoke(text);
        _offset = 0;
        _ = ShowCachedPageAsync(_activeMode, text, _selectedGroupId, 0);
    }

    public async Task LoadAsync(ProviderAccount account, ProviderConnection? connection = null,
        bool forceRefresh = false, string reason = "navigation", bool refreshAll = false)
    {
        var returningFromPlayer = reason == "player-return";
        var canReuseRenderedSnapshot = returningFromPlayer && _account?.AccountId == account.AccountId &&
            _renderedSnapshot?.Mode == _activeMode && HasCatalogData(_renderedSnapshot);
        LaunchDiagnostics.Write($"event=catalog.load reason={reason} reused={canReuseRenderedSnapshot} mode={_activeMode}");
        if (canReuseRenderedSnapshot)
        {
            RestoreBrowseStateIfMatching(_activeMode, preserveScrollOffset: true);
            if (_activityDataDirty || _favoriteDataDirty)
                _ = RefreshDirtySnapshotAsync(account);
            return;
        }
        var generation = Interlocked.Increment(ref _loadGeneration);
        var startupWorkForAccount = _savedStartupAuthCts is not null && _account?.AccountId == account.AccountId;
        var authenticationPending = startupWorkForAccount && _connection is null;
        if (!startupWorkForAccount) CancelSavedStartupAuthentication();
        Interlocked.Increment(ref _pageQueryGeneration);
        var accountChanged = _account?.AccountId != account.AccountId;
        var previousAccountId = _account?.AccountId;
        if (accountChanged) CancelShelfPreviewLoads();
        if (accountChanged) CancelHoverRequest();
        _account = account;
        if (connection is not null || accountChanged)
            _connection = connection;
        if (accountChanged)
        {
            _cachedStartupCatalogActive = false;
            _activityDataDirty = false;
            _favoriteDataDirty = false;
            if (previousAccountId is not null) InvalidateAccountSnapshots(previousAccountId);
            ShelvesItems.ItemsSource = null;
            _renderedSnapshot = null;
            CancelArtworkLoads();
            _firstCatalogLoadCompleted = false;
            _modeStates.Clear();
            _browsePositions.Clear();
            CancelSpotlightAsyncWork();
            ClearSpotlightCards();
            _selectedGroupId = null;
            _openShelfId = null;
            _openShelfType = null;
            _offset = 0;
            RefreshInfoBar.IsOpen = false;
            SetState(loading: true);
        }
        await LoadCatalogAsync(account, generation, forceRefresh, refreshAll,
            cacheOnly: authenticationPending || _connection is null);
    }

    public async Task LoadSavedAsync()
    {
        CancelSavedStartupAuthentication();
        CancelShelfPreviewLoads();
        CancelHoverRequest();
        LaunchDiagnostics.Write("event=catalog.load reason=saved-account-startup reused=false");
        var generation = Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        _account = null;
        ShelvesItems.ItemsSource = null;
        ClearRenderedShelves();
        _renderedSnapshot = null;
        CancelArtworkLoads();
        _connection = null;
        _cachedStartupCatalogActive = false;
        _selectedGroupId = null;
        _openShelfId = null;
        _openShelfType = null;
        _offset = 0;
        RefreshInfoBar.IsOpen = false;
        SetState(loading: true);
        try
        {
            var connection = await _credentialStore.LoadAsync();
            if (!IsCurrentLoad(generation)) return;
            if (connection is null)
            {
                ShowError("No saved provider connection. Choose Account in the top navigation to connect a provider.");
                return;
            }
            var accountId = connection.AccountId ?? AccountIdentity.For(connection.Endpoint, connection.Username);
            var cachedAccount = new ProviderAccount(accountId, connection.Endpoint, connection.Username);
            var hasCachedCatalog = await Task.Run(() => _repository.HasAnyCatalogRows(cachedAccount));
            if (!IsCurrentLoad(generation)) return;
            var startupPlan = StartupPlan.Decide(true, hasCachedCatalog);
            if (startupPlan == StartupRoute.CachedFirst)
            {
                _account = cachedAccount;
                _connection = null;
                _cachedStartupCatalogActive = true;
                await LoadCatalogAsync(cachedAccount, generation, cacheOnly: true);
                if (!IsCurrentLoad(generation)) return;
                if (_renderedSnapshot is not null && HasCatalogData(_renderedSnapshot))
                {
                    ShowCheckingAccountStatus();
                    var authCts = new CancellationTokenSource();
                    _savedStartupAuthCts = authCts;
                    _ = AuthenticateSavedAccountAndRefreshAsync(connection, cachedAccount, authCts);
                    return;
                }
            }

            await AuthenticateSavedAccountNetworkFirstAsync(connection, generation);
        }
        catch (Exception exception)
        {
            if (IsCurrentLoad(generation)) ShowError(GenericLoadError);
            LaunchDiagnostics.Write($"Saved account startup failed: {DescribeForLog(exception)}");
        }
    }

    private async Task AuthenticateSavedAccountNetworkFirstAsync(ProviderConnection connection, long generation)
    {
        ShowCheckingAccountStatus();
        AuthenticationResult result;
        try
        {
            result = await _provider.AuthenticateAsync(connection);
        }
        catch
        {
            if (IsCurrentLoad(generation)) StartupPhaseCompleted?.Invoke("authDone");
            throw;
        }
        if (!IsCurrentLoad(generation)) return;
        if (!result.Success || result.Account is null)
        {
            StartupPhaseCompleted?.Invoke("authDone");
            ShowError(GenericLoadError);
            return;
        }

        _account = result.Account;
        _connection = connection with { AccountId = result.Account.AccountId };
        StartupPhaseCompleted?.Invoke("authDone");
        await LoadCatalogAsync(result.Account, generation);
    }

    private async Task AuthenticateSavedAccountAndRefreshAsync(
        ProviderConnection connection, ProviderAccount cachedAccount, CancellationTokenSource authCts)
    {
        try
        {
            AuthenticationResult result;
            try
            {
                result = await _provider.AuthenticateAsync(connection, authCts.Token);
            }
            catch (OperationCanceledException) when (authCts.IsCancellationRequested)
            {
                return;
            }

            if (!IsCurrentSavedAuthentication(authCts, cachedAccount) || authCts.IsCancellationRequested) return;
            if (!result.Success || result.Account is null)
            {
                StartupPhaseCompleted?.Invoke("authDone");
                ShowCachedUpdateFailure(result);
                return;
            }

            var authenticatedAccount = result.Account with { AccountId = cachedAccount.AccountId };
            _account = authenticatedAccount;
            _connection = connection with { AccountId = authenticatedAccount.AccountId };
            StartupPhaseCompleted?.Invoke("authDone");
            await LoadCatalogAsync(authenticatedAccount, Volatile.Read(ref _loadGeneration));
        }
        catch (Exception exception)
        {
            if (IsCurrentSavedAuthentication(authCts, cachedAccount) && !authCts.IsCancellationRequested)
            {
                StartupPhaseCompleted?.Invoke("authDone");
                ShowCachedUpdateFailure(exception);
            }
        }
        finally
        {
            if (ReferenceEquals(_savedStartupAuthCts, authCts)) _savedStartupAuthCts = null;
            authCts.Dispose();
        }
    }

    private void CancelSavedStartupAuthentication()
    {
        var cancellation = Interlocked.Exchange(ref _savedStartupAuthCts, null);
        cancellation?.Cancel();
    }

    private bool IsCurrentSavedAuthentication(CancellationTokenSource authCts, ProviderAccount account) =>
        ReferenceEquals(_savedStartupAuthCts, authCts) && _account?.AccountId == account.AccountId;

    private async Task LoadCatalogAsync(ProviderAccount account, long generation, bool forceRefresh = false,
        bool refreshAll = false, bool cacheOnly = false)
    {
        var cacheStarted = System.Diagnostics.Stopwatch.GetTimestamp();
        var cacheCompleted = false;
        LaunchDiagnostics.Write($"event=catalog.cache.load.start mode={_activeMode}");
        try
        {
            if (_showModeLoadingFrame)
            {
                _showModeLoadingFrame = false;
                await Task.Delay(32);
                if (!IsCurrentLoad(generation)) return;
            }
            var cacheDirty = _activityDataDirty || _favoriteDataDirty;
            var cached = cacheDirty ? null : TryGetCachedSnapshot(account);
            cached ??= await ReadCurrentCatalogSnapshotAsync(account, generation);
            cacheCompleted = true;
            LaunchDiagnostics.Write($"event=catalog.cache.load.complete outcome={(cached is null ? "failure" : "success")} durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(cacheStarted).TotalMilliseconds:0} mode={_activeMode}");
            if (!IsCurrentLoad(generation)) return;
            if (cached is null) return;

            if (HasCatalogData(cached))
            {
                await ApplySnapshotAsync(cached, () => IsCurrentLoad(generation));
                _activityDataDirty = false;
                _favoriteDataDirty = false;
                _firstCatalogLoadCompleted = true;
            }
            else
            {
                SetState(loading: true);
                if (!_firstCatalogLoadCompleted)
                    ShowFirstLoadTakeover();
            }
            if (cacheOnly)
            {
                if (HasCatalogData(cached))
                {
                    _firstCatalogLoadCompleted = true;
                    StartupPhaseCompleted?.Invoke("cachedRender");
                }
                return;
            }
            await UpdateRefreshChromeAsync(account, generation);

            var policy = _refreshPolicyStore.Read();
            var types = refreshAll ? AllRefreshTypes : TypesForMode(_activeMode);
            var refreshedThisStart = types.Where(type => _refreshedThisStart.Contains((account.AccountId, type))).ToHashSet();
            var dueTypes = forceRefresh ? types : await Task.Run(() => types
                .Where(type =>
                {
                    var stamp = _repository.GetLastRefreshSuccess(account, type);
                    return ShouldAutoRefresh(policy, stamp, DateTimeOffset.UtcNow,
                        refreshedThisStart.Contains(type) || stamp >= _sessionStartedAt,
                        _refreshRetry.CanAttempt(account.AccountId, type, DateTimeOffset.UtcNow));
                }).ToArray());
            if (!IsCurrentLoad(generation)) return;
            if (dueTypes.Length == 0)
            {
                StartEpgIfVisible();
                _cachedStartupCatalogActive = false;
                ShowPersistedRefreshStatus();
                DismissFirstLoadTakeover();
                if (!HasCatalogData(cached)) SetState(empty: true);
                return;
            }

            if (HasCatalogData(cached)) CaptureBrowseState();
            ShowRefreshingStatus();
            var refreshStarted = System.Diagnostics.Stopwatch.GetTimestamp();
            LaunchDiagnostics.Write($"event=catalog.cache.refresh.start mode={_activeMode}");
            var anyChanged = false;
            var anyFailed = false;
            Exception? refreshFailure = null;
            var liveChanged = false;
            foreach (var type in dueTypes)
            {
                try
                {
                    var changed = await _refreshTasks.RefreshTypeAsync(account, type,
                        _savedStartupAuthCts?.Token ?? CancellationToken.None);
                    if (!IsCurrentLoad(generation)) return;
                    if (!changed) throw new InvalidOperationException("Catalog replacement was rejected");
                    anyChanged = true;
                    if (type == CatalogItemType.Live) liveChanged = true;
                    _refreshRetry.RecordSuccess(account.AccountId, type);
                    _refreshedThisStart.Add((account.AccountId, type));
                }
                catch (Exception exception)
                {
                    if (!IsCurrentLoad(generation)) return;
                    anyFailed = true;
                    refreshFailure ??= exception;
                    LaunchDiagnostics.Write($"Catalog {type} refresh failed: {DescribeForLog(exception)}");
                    var retryAt = _refreshRetry.RecordFailure(account.AccountId, type, DateTimeOffset.UtcNow);
                    if (!forceRefresh && policy != CatalogRefreshPolicy.OnDemandOnly)
                        ScheduleRefreshRetry(account, type, retryAt);
                }
            }
            LaunchDiagnostics.Write($"event=catalog.cache.refresh.complete outcome={(anyFailed ? "failure" : "success")} durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(refreshStarted).TotalMilliseconds:0} mode={_activeMode}");
            if (liveChanged)
                MarkEpgMapLoaded(account.AccountId);
            if (!IsCurrentLoad(generation)) return;
            if (!anyChanged)
            {
                if (anyFailed) ShowRefreshFailure(userInitiated: forceRefresh, refreshFailure);
                else if (_cachedStartupCatalogActive)
                {
                    _cachedStartupCatalogActive = false;
                    ShowPersistedRefreshStatus();
                }
                else RefreshInfoBar.IsOpen = false;
                DismissFirstLoadTakeover();
                if (!HasCatalogData(cached)) SetState(empty: true);
                return;
            }
            InvalidateAccountSnapshots(account.AccountId);

            var refreshed = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (!IsCurrentLoad(generation)) return;
            if (refreshed is null) return;

            CacheSnapshot(account, refreshed);
            await ApplySnapshotAsync(refreshed, () => IsCurrentLoad(generation), deferShelfStructure: true);
            StartEpgIfVisible();
            _firstCatalogLoadCompleted = true;
            DismissFirstLoadTakeover();
            await UpdateRefreshChromeAsync(account, generation);
            if (anyFailed) ShowRefreshFailure(userInitiated: forceRefresh, refreshFailure);
            else
            {
                _cachedStartupCatalogActive = false;
                ShowPersistedRefreshStatus();
            }
        }
        catch (Exception exception)
        {
            if (!cacheCompleted)
                LaunchDiagnostics.Write($"event=catalog.cache.load.complete outcome=failure durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(cacheStarted).TotalMilliseconds:0} mode={_activeMode}");
            if (!IsCurrentLoad(generation)) return;
            LaunchDiagnostics.Write($"Catalog load failed: {DescribeForLog(exception)} (HRESULT 0x{exception.HResult:X8})");
            if (ContentState.Visibility == Visibility.Visible)
            {
                ShowRefreshFailure(userInitiated: forceRefresh);
                var visual = ElementCompositionPreview.GetElementVisual(ContentState);
                if (visual.Opacity < 1f)
                    await FadeCatalogContentAsync(fadeOut: false, transitionGeneration: Volatile.Read(ref _modeTransitionGeneration));
            }
            else
                ShowError(GenericLoadError);
        }
    }

    private async Task RefreshDirtySnapshotAsync(ProviderAccount account)
    {
        var generation = Volatile.Read(ref _loadGeneration);
        var mode = _activeMode;
        try
        {
            var refreshed = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (refreshed is null || !IsCurrentLoad(generation) || _account?.AccountId != account.AccountId ||
                _activeMode != mode)
                return;
            var current = _renderedSnapshot;
            if (current is null) return;
            var activityDirty = _activityDataDirty;
            var favoriteDirty = _favoriteDataDirty;
            bool ShouldReplace(string id) =>
                (activityDirty && (id is "__continue" or "__recent_played" || id.StartsWith("__my_continue_", StringComparison.Ordinal) || id.StartsWith("__my_played_", StringComparison.Ordinal))) ||
                (favoriteDirty && (id == "__favorites" || id.StartsWith("__my_favorites_", StringComparison.Ordinal)));
            var shelves = MergeDirtyShelves(current.Shelves, refreshed.Shelves, ShouldReplace);
            var spotlight = MergeDirtyShelves(current.SpotlightShelves, refreshed.SpotlightShelves, ShouldReplace);
            var pageIsDirtyShelf = _openShelfId is { } openShelfId && ShouldReplace(openShelfId);
            var updated = current with
            {
                Groups = activityDirty ? refreshed.Groups : current.Groups,
                Page = pageIsDirtyShelf ? refreshed.Page : current.Page,
                Shelves = shelves,
                SpotlightSource = spotlight,
            };
            CacheSnapshot(account, updated);
            await ApplySnapshotAsync(updated, () => IsCurrentLoad(generation), deferShelfStructure: true);
            _activityDataDirty = false;
            _favoriteDataDirty = false;
            LaunchDiagnostics.Write($"event=catalog.activity-refresh outcome=success mode={mode}");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"event=catalog.activity-refresh outcome=failure error={exception.GetType().Name} mode={mode}");
        }
    }

    private static IReadOnlyList<CatalogShelf> MergeDirtyShelves(
        IReadOnlyList<CatalogShelf> current,
        IReadOnlyList<CatalogShelf> refreshed,
        Func<string, bool> shouldReplace)
    {
        var currentById = current.ToDictionary(shelf => shelf.Id, StringComparer.Ordinal);
        return refreshed
            .Where(shelf => shouldReplace(shelf.Id) || currentById.ContainsKey(shelf.Id))
            .Select(shelf => !shouldReplace(shelf.Id) && currentById.TryGetValue(shelf.Id, out var unchanged)
                ? unchanged : shelf)
            .ToArray();
    }

    private async Task<CatalogSnapshot> ReadCatalogSnapshotAsync(
        ProviderAccount account,
        string? requestedGroupId,
        string? filter,
        int requestedOffset,
        CatalogMode mode,
        CategorySort sort)
    {
        var openShelfId = _openShelfId;
        var openShelfType = _openShelfType;
        var snapshot = await Task.Run(() =>
        {
            if (mode == CatalogMode.MyTvivo)
            {
                var myShelves = BuildShelves(account, Array.Empty<ChannelGroup>(), filter, mode, sort);
                if (openShelfId is not null)
                {
                    var definition = MyTvivoShelfDefinitions.All.FirstOrDefault(shelf => shelf.Id == openShelfId);
                    IReadOnlyList<Channel> allItems = definition is null
                        ? Array.Empty<Channel>()
                        : GetMyTvivoShelfItems(account, definition, filter);
                    allItems = SortOpenShelfItems(allItems, sort);
                    var lastOffset = allItems.Count == 0 ? 0 : ((allItems.Count - 1) / PageSize) * PageSize;
                    var shelfOffset = Math.Clamp(requestedOffset, 0, lastOffset);
                    var shelfItems = allItems.Skip(shelfOffset).Take(PageSize).ToArray();
                    return new CatalogSnapshot(mode, Array.Empty<ChannelGroup>(), null, shelfOffset,
                        new CatalogPage(shelfItems, allItems.Count), myShelves);
                }
                return new CatalogSnapshot(mode, Array.Empty<ChannelGroup>(), null, 0, new CatalogPage(Array.Empty<Channel>(), 0), myShelves);
            }

            var type = TypeForMode(mode);
            var categoryDescriptors = _repository.GetCategoryShelfDescriptors(account, type, filter);
            var groups = categoryDescriptors.Select(descriptor => descriptor.Group).ToArray();
            if (openShelfId is null && mode != CatalogMode.MyTvivo && !string.IsNullOrWhiteSpace(filter))
            {
                var groupNames = groups.ToDictionary(group => group.Id, group => group.DisplayName, StringComparer.Ordinal);
                var matchCounts = _repository.GetCategoryMatchCounts(account, type, filter)
                    .Where(match => match.MatchCount > 0)
                    .OrderByDescending(match => match.MatchCount)
                    .ThenBy(match => groupNames.GetValueOrDefault(match.CategoryId, match.CategoryId), StringComparer.CurrentCultureIgnoreCase)
                    .ToArray();
                var searchGroupId = requestedGroupId is not null && matchCounts.Any(match => match.CategoryId == requestedGroupId)
                    ? requestedGroupId
                    : null;
                var searchFirstPage = _repository.GetChannels(account, _connection, type, searchGroupId, filter, 0, PageSize, sortOrder: ToItemSortOrder(sort));
                var searchLastValidOffset = searchFirstPage.TotalCount == 0 ? 0 : ((searchFirstPage.TotalCount - 1) / PageSize) * PageSize;
                var searchOffset = Math.Clamp(requestedOffset, 0, searchLastValidOffset);
                var searchPage = searchOffset == 0
                    ? searchFirstPage
                    : _repository.GetChannels(account, _connection, type, searchGroupId, filter, searchOffset, PageSize, sortOrder: ToItemSortOrder(sort));
                return new CatalogSnapshot(mode, groups, searchGroupId, searchOffset, searchPage, Array.Empty<CatalogShelf>(),
                    SearchCategoryCounts: matchCounts.Select(match => new SearchCategoryCount(match.CategoryId,
                        groupNames.GetValueOrDefault(match.CategoryId, match.CategoryId), match.MatchCount)).ToArray(),
                    IsFlatSearch: true, SearchFilter: filter);
            }
            if (openShelfId is "__continue" or "__recent_added" or "__recent_played" or "__favorites")
            {
                var allItems = openShelfId switch
                {
                    "__continue" when type == CatalogItemType.Live => _repository.GetRecentlyPlayed(account, type, _connection, int.MaxValue, filter),
                    "__continue" => _repository.GetContinueWatching(account, type, _connection, int.MaxValue, filter),
                    "__recent_added" => _repository.GetRecentlyAdded(account, type, _connection, int.MaxValue, filter),
                    "__recent_played" => _repository.GetRecentlyPlayed(account, type, _connection, int.MaxValue, filter),
                    _ => _repository.GetFavorites(account, type, _connection, int.MaxValue, filter),
                };
                allItems = SortOpenShelfItems(allItems, sort);
                var lastOffset = allItems.Count == 0 ? 0 : ((allItems.Count - 1) / PageSize) * PageSize;
                var shelfOffset = Math.Clamp(requestedOffset, 0, lastOffset);
                var shelfItems = allItems.Skip(shelfOffset).Take(PageSize).ToArray();
                var shelfList = BuildShelves(account, groups, filter, mode, sort, categoryDescriptors);
                return new CatalogSnapshot(mode, groups, null, shelfOffset,
                    new CatalogPage(shelfItems, allItems.Count), shelfList);
            }

            var groupId = requestedGroupId is not null && groups.Any(group => group.Id == requestedGroupId)
                ? requestedGroupId
                : null;
            var itemSort = ToItemSortOrder(sort);
            var firstPage = _repository.GetChannels(account, _connection, type, groupId, filter, 0, PageSize, sortOrder: itemSort);
            var lastValidOffset = firstPage.TotalCount == 0
                ? 0
                : ((firstPage.TotalCount - 1) / PageSize) * PageSize;
            var offset = Math.Clamp(requestedOffset, 0, lastValidOffset);
            var page = offset == 0
                ? firstPage
                : _repository.GetChannels(account, _connection, type, groupId, filter, offset, PageSize, sortOrder: itemSort);
            var shelves = BuildShelves(account, groups, filter, mode, sort, categoryDescriptors);
            return new CatalogSnapshot(mode, groups, groupId, offset, page, shelves);
        });

        var spotlight = await Task.Run(() => BuildSpotlightShelves(account, mode, snapshot.Shelves));
        return snapshot with { SpotlightSource = spotlight };
    }

    private IReadOnlyList<CatalogShelf> BuildSpotlightShelves(
        ProviderAccount account, CatalogMode mode, IReadOnlyList<CatalogShelf> fallback)
    {
        if (mode is not (CatalogMode.MyTvivo or CatalogMode.Movies or CatalogMode.Series)) return Array.Empty<CatalogShelf>();
        var settings = _spotlightSettingsService.ReadEffectiveSettings(account.AccountId);
        var page = mode switch
        {
            CatalogMode.Movies => SpotlightPageKind.Movies,
            CatalogMode.Series => SpotlightPageKind.Series,
            _ => SpotlightPageKind.ForYou,
        };
        var moviePool = mode == CatalogMode.Series ? Array.Empty<Channel>() :
            _spotlightSettingsService.GetChannelsForSelection(account, CatalogItemType.Movie, settings);
        var seriesPool = mode == CatalogMode.Movies ? Array.Empty<Channel>() :
            _spotlightSettingsService.GetChannelsForSelection(account, CatalogItemType.Series, settings);
        var selection = _spotlightSettingsService.Select(account, page,
            DateOnly.FromDateTime(DateTime.UtcNow), moviePool, seriesPool);
        var channels = moviePool.Concat(seriesPool).ToDictionary(channel => (TypeForSource(channel.Source.Kind), channel.Id));
        var cards = selection.Items.Select(item => channels.TryGetValue((item.Type, item.ItemId), out var channel)
            ? ToCard(channel, item.Type) : null).Where(card => card is not null).Cast<CatalogCard>().ToArray();
        return cards.Length == 0 ? Array.Empty<CatalogShelf>() :
            new[] { CreateShelf(account, "__spotlight_strip", "Spotlight", CountLabel(cards.Length, "title"), cards,
                cards[0].Type, null, false, null) };
    }

    private static string SpotlightTitleKey(Channel channel) =>
        channel.DisplayName.Trim().ToLowerInvariant();

    private async Task<CatalogSnapshot?> ReadCurrentCatalogSnapshotAsync(ProviderAccount account, long generation)
    {
        while (IsCurrentLoad(generation))
        {
            var pageQueryGeneration = Volatile.Read(ref _pageQueryGeneration);
            var mode = _activeMode;
            var sort = _categorySort;
            var snapshot = await ReadCatalogSnapshotAsync(account, _selectedGroupId, SearchBox.Text, _offset, mode, sort);
            if (pageQueryGeneration == Volatile.Read(ref _pageQueryGeneration))
            {
                CacheSnapshot(account, snapshot);
                return snapshot;
            }
        }

        return null;
    }

    private static readonly CatalogItemType[] AllRefreshTypes =
        [CatalogItemType.Live, CatalogItemType.Movie, CatalogItemType.Series];

    public static CatalogItemType[] TypesForMode(CatalogMode mode) => mode switch
    {
        CatalogMode.LiveTv => [CatalogItemType.Live],
        CatalogMode.Movies => [CatalogItemType.Movie],
        CatalogMode.Series => [CatalogItemType.Series],
        _ => AllRefreshTypes,
    };

    public static bool ShouldAutoRefresh(CatalogRefreshPolicy policy, DateTimeOffset? lastSuccess,
        DateTimeOffset now, bool refreshedThisStart, bool canAttempt) =>
        policy != CatalogRefreshPolicy.OnDemandOnly && canAttempt &&
        CatalogRefreshPolicyStore.IsDue(policy, lastSuccess, now, refreshedThisStart);

    private async Task UpdateRefreshChromeAsync(ProviderAccount account, long generation)
    {
        var mode = _activeMode;
        RaiseRefreshStateChanged();
        // For You spans every content type, so its "updated" time is the most recent refresh of any of them.
        var refreshed = mode == CatalogMode.MyTvivo
            ? await Task.Run(() => LastRefreshAt)
            : await Task.Run(() => _repository.GetLastRefreshSuccess(account, TypesForMode(mode)[0]));
        if (!IsCurrentLoad(generation) || _activeMode != mode) return;
        UpdatedText = FormatUpdated(refreshed, DateTimeOffset.UtcNow);
        RaiseRefreshStateChanged();
    }

    /// <summary>The "Updated 3 hr ago" text, shown by the shell as the refresh button's tooltip.</summary>
    public string UpdatedText { get; private set; } = "Updated never";

    public bool CanRefreshType => true;

    public bool IsRefreshingType { get; private set; }

    public event EventHandler? RefreshStateChanged;

    private void RaiseRefreshStateChanged() => RefreshStateChanged?.Invoke(this, EventArgs.Empty);

    public static string FormatUpdated(DateTimeOffset? refreshed, DateTimeOffset now)
        => CatalogStartupStatus.FormatUpdated(refreshed, now);

    private void ScheduleRefreshRetry(ProviderAccount account, CatalogItemType type, DateTimeOffset retryAt)
    {
        var key = (account.AccountId, type);
        if (!_scheduledRetries.Add(key)) return;
        _ = RetryRefreshAfterCooldownAsync(account, type, retryAt);
    }

    private async Task RetryRefreshAfterCooldownAsync(ProviderAccount account, CatalogItemType type, DateTimeOffset retryAt)
    {
        var removed = false;
        try
        {
            await Task.Delay(retryAt - DateTimeOffset.UtcNow > TimeSpan.Zero ? retryAt - DateTimeOffset.UtcNow : TimeSpan.Zero);
            _scheduledRetries.Remove((account.AccountId, type));
            removed = true;
            if (!_isActive || _account?.AccountId != account.AccountId ||
                _refreshPolicyStore.Read() == CatalogRefreshPolicy.OnDemandOnly) return;
            if (!TypesForMode(_activeMode).Contains(type)) return;
            await LoadAsync(account, reason: "auto-retry");
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Quiet refresh retry failed: {DescribeForLog(exception)}");
        }
        finally
        {
            if (!removed)
                _scheduledRetries.Remove((account.AccountId, type));
        }
    }

    private void UpdateEpgLifecycle()
    {
        if (!PerfToggles.IsDisabled("epg") && EpgEnabled && _isActive && _activeMode == CatalogMode.LiveTv)
            _epgTimer.Start();
        else
            _epgTimer.Stop();
    }

    private void StartEpgIfVisible()
    {
        if (!EpgEnabled) return;
        if (!_isActive || _activeMode != CatalogMode.LiveTv ||
            _account is not { } account || _connection is not { } connection)
        {
            LaunchDiagnostics.Write($"EPG start skipped: active={_isActive} mode={_activeMode} account={_account is not null} connection={_connection is not null}");
            return;
        }

        _refreshTasks.TryGetActive(account.AccountId, CatalogItemType.Live, out var catalogRefreshTask);
        _ = StartEpgAsync(account, connection, catalogRefreshTask);
    }

    private async Task StartEpgAsync(ProviderAccount account, ProviderConnection connection, Task? catalogRefreshTask)
    {
        if (!EpgEnabled) return;
        if (!await _epgStartGate.WaitAsync(0).ConfigureAwait(false)) return;

        try
        {
            if (catalogRefreshTask is not null)
            {
                try
                {
                    await catalogRefreshTask.ConfigureAwait(false);
                }
                catch (Exception exception)
                {
                    LaunchDiagnostics.Write($"Catalog refresh before EPG warm-up failed: {exception.GetType().Name}");
                }
            }

            var mapLoaded = false;
            lock (_epgMapLoadedAccounts)
                mapLoaded = _epgMapLoadedAccounts.Contains(account.AccountId);

            if (!mapLoaded)
            {
                try
                {
                    await _provider.GetChannelsAsync(account, CatalogItemType.Live).ConfigureAwait(false);
                    MarkEpgMapLoaded(account.AccountId);
                }
                catch (Exception exception)
                {
                    LaunchDiagnostics.Write($"EPG channel map warm-up failed: {exception.GetType().Name}");
                }
            }

            await _epgCoordinator.StartIfDueAsync(account, connection).ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"EPG start failed: {exception.GetType().Name}");
        }
        finally
        {
            _epgStartGate.Release();
        }
    }

    private void EpgTimer_Tick(object? sender, object args)
    {
        using var perfScope = PerfScope.Begin("EpgTimerTick");
        if (!EpgEnabled) return;
        StartEpgIfVisible();
        _ = RefreshVisibleEpgAsync();
    }

    private void EpgCoordinator_EpgUpdated(object? sender, EventArgs args)
    {
        if (!_isActive || _activeMode != CatalogMode.LiveTv) return;
        if (DispatcherQueue.HasThreadAccess)
        {
            _ = RefreshVisibleEpgAsync();
            return;
        }

        DispatcherQueue.TryEnqueue(() =>
        {
            using var dispatchScope = PerfScope.Begin("EpgRefreshDispatch");
            _ = RefreshVisibleEpgAsync();
        });
    }

    private async Task RefreshVisibleEpgAsync()
    {
        if (!EpgEnabled || !_isActive || _activeMode != CatalogMode.LiveTv || _account is not { } account ||
            !await _epgQueryGate.WaitAsync(0))
            return;

        try
        {
            var cards = RenderedCards()
                .Where(card => card.Channel?.Metadata.TryGetValue("epg_channel_id", out var id) == true &&
                               !string.IsNullOrWhiteSpace(id))
                .ToArray();
            if (cards.Length == 0) return;

            var ids = cards.Select(card => card.Channel!.Metadata["epg_channel_id"])
                .Distinct(StringComparer.Ordinal)
                .ToArray();
            var nowNext = await Task.Run(() => _epgCoordinator.GetNowNext(account, ids));
            if (!_isActive || _activeMode != CatalogMode.LiveTv || _account?.AccountId != account.AccountId)
                return;

            using var perfScope = PerfScope.Begin("EpgVisibleUpdate");
            foreach (var card in cards)
            {
                var epgId = card.Channel!.Metadata["epg_channel_id"];
                nowNext.TryGetValue(epgId, out var programme);
                card.ApplyNowNext(programme);
                if (ReferenceEquals(_hoveredCard, card) && _hoveredCardElement is { } hoveredElement)
                    ShowCardToolTip(card, hoveredElement);
            }
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"EPG card update failed: {exception.GetType().Name}");
        }
        finally
        {
            _epgQueryGate.Release();
        }
    }

    private IEnumerable<CatalogCard> RenderedCards()
    {
        var shelfCards = _renderedSnapshot?.Shelves.SelectMany(shelf => shelf.Cards)
            ?? Enumerable.Empty<CatalogCard>();
        return shelfCards.Concat(OpenShelfGrid.Items.OfType<CatalogCard>()).Distinct();
    }

    private IReadOnlyList<CatalogShelf> BuildShelves(
        ProviderAccount account,
        IReadOnlyList<ChannelGroup> groups,
        string? filter,
        CatalogMode mode,
        CategorySort sort,
        IReadOnlyList<CatalogCategoryShelfDescriptor>? categoryDescriptors = null)
    {
        using var perfScope = PerfScope.Begin("BuildShelves");
        using var refreshPhase = PerfScope.BeginPhase("snapshotBuild");
        if (mode == CatalogMode.MyTvivo)
            return BuildMyTvivoShelves(account, filter);

        var type = TypeForMode(mode);
        var noun = NounFor(type);
        var shelves = new List<CatalogShelf>();
        AddActivityShelf(account, shelves, "__continue", "Continue watching", type,
            type == CatalogItemType.Live
                ? _repository.GetRecentlyPlayed(account, type, _connection, int.MaxValue, filter)
                : _repository.GetContinueWatching(account, type, _connection, int.MaxValue, filter),
            isContinueWatching: type != CatalogItemType.Live);
        AddActivityShelf(account, shelves, "__recent_added", "Recently added", type,
            _repository.GetRecentlyAdded(account, type, _connection, int.MaxValue, filter));
        AddActivityShelf(account, shelves, "__favorites", "Favorites", type,
            _repository.GetFavorites(account, type, _connection, int.MaxValue, filter));

        var allChannels = _repository.GetChannels(account, _connection, type, filter: filter, offset: 0, limit: ShelfPreviewSize);
        if (allChannels.Items.Count > 0)
        {
            shelves.Add(CreateShelf(account,
                "__recent",
                AllItemsShelfTitle(type),
                CountLabel(allChannels.TotalCount, noun),
                allChannels.Items.Select(channel => ToCard(channel, type)).ToArray(),
                type,
                null,
                false,
                allChannels.LatestAddedAt));
        }

        categoryDescriptors ??= _repository.GetCategoryShelfDescriptors(account, type, filter);
        foreach (var descriptor in SortGroups(categoryDescriptors, sort))
        {
            var group = descriptor.Group;
            var titleMatchesFilter = !string.IsNullOrWhiteSpace(filter) &&
                group.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase);
            shelves.Add(CreateShelf(account,
                group.Id,
                group.DisplayName,
                CountLabel(descriptor.TotalCount, noun),
                Array.Empty<CatalogCard>(),
                type,
                group.Id,
                true,
                descriptor.LatestAddedAt,
                ignoreItemFilterForPreview: titleMatchesFilter));
        }

        return DeduplicateShelves(shelves);
    }

    private static IReadOnlyList<ChannelGroup> SortGroups(IReadOnlyList<ChannelGroup> groups, CategorySort sort) =>
        sort switch
        {
            CategorySort.AlphabeticalAsc => groups.OrderBy(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            CategorySort.AlphabeticalDesc => groups.OrderByDescending(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            _ => groups.OrderBy(group => group.SortOrder ?? int.MaxValue).ThenBy(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
        };

    private static IReadOnlyList<CatalogCategoryShelfDescriptor> SortGroups(
        IReadOnlyList<CatalogCategoryShelfDescriptor> groups, CategorySort sort) =>
        sort switch
        {
            CategorySort.AlphabeticalAsc => groups.OrderBy(group => group.Group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            CategorySort.AlphabeticalDesc => groups.OrderByDescending(group => group.Group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            _ => groups.OrderBy(group => group.Group.SortOrder ?? int.MaxValue).ThenBy(group => group.Group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
        };

    private static CatalogItemSortOrder ToItemSortOrder(CategorySort sort) => sort switch
    {
        CategorySort.AlphabeticalAsc => CatalogItemSortOrder.AlphabeticalAsc,
        CategorySort.AlphabeticalDesc => CatalogItemSortOrder.AlphabeticalDesc,
        CategorySort.RecentlyAdded => CatalogItemSortOrder.RecentlyAdded,
        CategorySort.RecentlyUpdated => CatalogItemSortOrder.RecentlyUpdated,
        _ => CatalogItemSortOrder.MostVisited,
    };

    private static IReadOnlyList<Channel> SortOpenShelfItems(IReadOnlyList<Channel> channels, CategorySort sort) => sort switch
    {
        CategorySort.AlphabeticalAsc => channels.OrderBy(channel => channel.DisplayName, StringComparer.CurrentCultureIgnoreCase).ThenBy(channel => channel.Id, StringComparer.Ordinal).ToArray(),
        CategorySort.AlphabeticalDesc => channels.OrderByDescending(channel => channel.DisplayName, StringComparer.CurrentCultureIgnoreCase).ThenBy(channel => channel.Id, StringComparer.Ordinal).ToArray(),
        CategorySort.RecentlyAdded => channels.OrderByDescending(channel => channel.AddedAt).ThenBy(channel => channel.Id, StringComparer.Ordinal).ToArray(),
        CategorySort.RecentlyUpdated => channels.OrderByDescending(ItemUpdatedAt).ThenBy(channel => channel.Id, StringComparer.Ordinal).ToArray(),
        _ => channels,
    };

    private static DateTimeOffset? ItemUpdatedAt(Channel channel) =>
        DateTimeOffset.TryParse(channel.Metadata.GetValueOrDefault("updated_at"), out var updatedAt)
            ? updatedAt
            : channel.AddedAt;

    private IReadOnlyList<CatalogShelf> BuildMyTvivoShelves(ProviderAccount account, string? filter)
    {
        var shelves = MyTvivoShelfDefinitions.Resolve(new MyTvivoLayoutStore().Read())
            .Where(entry => entry.Visible)
            .Select(entry => entry.Definition)
            .Select(definition => CreateMyTvivoShelf(account, definition, GetMyTvivoShelfItems(account, definition, filter)))
            .Where(shelf => ShelfVisibilityPolicy.HasItems(shelf.Cards.Count))
            .ToArray();
        return DeduplicateShelves(shelves);
    }

    private static IReadOnlyList<CatalogShelf> DeduplicateShelves(IEnumerable<CatalogShelf> shelves)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var result = new List<CatalogShelf>();
        var hadDuplicates = false;
        foreach (var shelf in shelves)
        {
            if (seen.Add(shelf.Id)) result.Add(shelf);
            else hadDuplicates = true;
        }
        if (hadDuplicates)
            LaunchDiagnostics.Write("event=perf.shelfrows duplicateIds=true source=build");
        return result;
    }

    private IReadOnlyList<Channel> GetMyTvivoShelfItems(
        ProviderAccount account,
        MyTvivoShelfDefinition definition,
        string? filter) => definition.Kind switch
    {
        // Live has no resume position: its "continue" shelf is the channels watched most recently.
        MyTvivoShelfKind.ContinueWatching when definition.Type == CatalogItemType.Live => _repository.GetRecentlyPlayed(account, definition.Type, _connection, int.MaxValue, filter),
        MyTvivoShelfKind.ContinueWatching => _repository.GetContinueWatching(account, definition.Type, _connection, int.MaxValue, filter),
        MyTvivoShelfKind.RecentlyAdded => _repository.GetRecentlyAdded(account, definition.Type, _connection, int.MaxValue, filter),
        MyTvivoShelfKind.Favorites => _repository.GetFavorites(account, definition.Type, _connection, int.MaxValue, filter),
        _ => Array.Empty<Channel>(),
    };

    private void AddActivityShelf(
        ProviderAccount account,
        ICollection<CatalogShelf> shelves,
        string id,
        string title,
        CatalogItemType type,
        IReadOnlyList<Channel> channels,
        bool isContinueWatching = false)
    {
        if (channels.Count == 0) return;
        shelves.Add(CreateShelf(account, id, title, CountLabel(channels.Count, NounFor(type)),
            channels.Take(ShelfPreviewSize).Select(channel => ToCard(channel, type, isContinueWatching: isContinueWatching)).ToArray(), type, null, false,
            channels.Where(channel => channel.AddedAt.HasValue).Select(channel => channel.AddedAt).Max()));
    }

    private CatalogShelf CreateMyTvivoShelf(ProviderAccount account, MyTvivoShelfDefinition definition, IReadOnlyList<Channel> channels)
    {
        var preview = channels.Take(ShelfPreviewSize)
            .Select(channel => ToCard(channel, definition.Type,
                isContinueWatching: definition.Kind == MyTvivoShelfKind.ContinueWatching && definition.Type != CatalogItemType.Live))
            .ToArray();
        return CreateShelf(account, definition.Id, definition.Title, CountLabel(channels.Count, NounFor(definition.Type)), preview,
            definition.Type, null, false,
            channels.Where(channel => channel.AddedAt.HasValue).Select(channel => channel.AddedAt).Max());
    }

    private CatalogShelf CreateShelf(ProviderAccount account, string id, string title, string countLabel,
        IReadOnlyList<CatalogCard> cards, CatalogItemType type, string? groupId, bool opensPagedGrid,
        DateTimeOffset? latestAddedAt = null, bool ignoreItemFilterForPreview = false)
    {
        var lastVisitedAt = GetLastShelfVisitAt(account, type, id) ?? DateTimeOffset.UtcNow;
        return new CatalogShelf(id, title, countLabel, cards, type, groupId, opensPagedGrid,
            latestAddedAt, latestAddedAt is { } addedAt && addedAt > lastVisitedAt, ignoreItemFilterForPreview);
    }

    private static readonly string ShelfVisitsPath = TvivoDataPaths.For("shelf-visits.json");
    private static readonly object ShelfVisitsLock = new();

    private static string ShelfVisitSettingKey(ProviderAccount account, CatalogItemType type, string shelfId) =>
        $"catalog.shelf.last-visited.{account.AccountId}.{type}.{shelfId}";

    private static Dictionary<string, long> ReadShelfVisits()
    {
        try
        {
            return File.Exists(ShelfVisitsPath)
                ? JsonSerializer.Deserialize<Dictionary<string, long>>(File.ReadAllText(ShelfVisitsPath)) ?? new()
                : new();
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"Shelf-visit settings read failed: {exception.GetType().Name}");
            return new();
        }
    }

    private static DateTimeOffset? GetLastShelfVisitAt(ProviderAccount account, CatalogItemType type, string shelfId)
    {
        lock (ShelfVisitsLock)
        {
            var key = ShelfVisitSettingKey(account, type, shelfId);
            return ReadShelfVisits().TryGetValue(key, out var milliseconds) && milliseconds > 0
                ? DateTimeOffset.FromUnixTimeMilliseconds(milliseconds)
                : null;
        }
    }

    private static IReadOnlyCollection<string> GetRecentlyOpenedCategoryIds(
        ProviderAccount account, CatalogItemType type, DateTimeOffset now)
    {
        const int recentDays = 14;
        var prefix = $"catalog.shelf.last-visited.{account.AccountId}.{type}.";
        var cutoff = now.AddDays(-recentDays).ToUnixTimeMilliseconds();
        lock (ShelfVisitsLock)
            return ReadShelfVisits()
                .Where(pair => pair.Key.StartsWith(prefix, StringComparison.Ordinal) && pair.Value >= cutoff)
                .Select(pair => pair.Key[prefix.Length..])
                .ToArray();
    }

    private static void RecordShelfVisit(ProviderAccount account, CatalogItemType type, string shelfId)
    {
        lock (ShelfVisitsLock)
        {
            try
            {
                var visits = ReadShelfVisits();
                visits[ShelfVisitSettingKey(account, type, shelfId)] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                Directory.CreateDirectory(Path.GetDirectoryName(ShelfVisitsPath)!);
                File.WriteAllText(ShelfVisitsPath, JsonSerializer.Serialize(visits));
            }
            catch (Exception exception)
            {
                LaunchDiagnostics.Write($"Shelf-visit settings write failed: {exception.GetType().Name}");
            }
        }
    }

    private static CatalogItemType TypeForSource(StreamKind kind) => kind switch
    {
        StreamKind.Live => CatalogItemType.Live,
        StreamKind.Series => CatalogItemType.Series,
        _ => CatalogItemType.Movie,
    };

    private CatalogCard ToCard(Channel channel, CatalogItemType type, string? categoryQuality = null,
        string? subtitleOverride = null, bool isContinueWatching = false)
    {
        var (title, qualityBadge) = SplitQualityBadge(channel.DisplayName);
        var card = new CatalogCard(
            $"{channel.ProviderAccountId}:{channel.Source.Kind}:{channel.Id}",
            title,
            subtitleOverride ?? SubtitleFor(type),
            channel.LogoUri?.ToString(),
            190,
            type == CatalogItemType.Live ? 138 : 250,
            channel,
            qualityBadge ?? categoryQuality,
            type,
            isContinueWatching,
            EpgEnabled);
        if (type == CatalogItemType.Movie)
            card.ApplyWatchProgress(_movieProgressById.GetValueOrDefault(channel.Id));
        else if (type == CatalogItemType.Series)
            card.ApplyWatchProgress(_seriesProgressById.GetValueOrDefault(channel.Id));
        return card;
    }

    private static int QualityRank(string badge) => badge switch { "SD" => 1, "HD" => 2, "FHD" => 3, _ => 4 };

    private static string NormalizeQuality(string token)
    {
        var value = token.ToUpperInvariant().Replace(" ", "").Replace("_", "").Replace("-", "");
        return value switch
        {
            "FULLHD" or "1080P" or "1080I" => "FHD",
            "2160P" => "4K",
            "720P" => "HD",
            "576P" or "480P" => "SD",
            _ => value
        };
    }

    // Quality can sit anywhere in a provider title ("Name FHD (2023)", "4K - Name", "Name HD H265"), so it is
    // read from any position and stripped from the display title; a category name is the fallback source.
    private static string? QualityFromText(string text)
    {
        string? best = null;
        foreach (Match match in QualityToken.Matches(text))
        {
            var badge = NormalizeQuality(match.Groups["quality"].Value);
            if (best is null || QualityRank(badge) > QualityRank(best)) best = badge;
        }
        return best;
    }

    private static (string Title, string? QualityBadge) SplitQualityBadge(string title)
    {
        var badge = QualityFromText(title);
        if (badge is null) return (title, null);
        var clean = CodecToken.Replace(QualityToken.Replace(title, " "), " ");
        clean = EmptyBrackets.Replace(clean, " ");
        clean = RepeatedSpaces.Replace(clean, " ").Trim();
        clean = CatalogTitleFormatter.StripLeadingDelimiters(clean);
        return string.IsNullOrWhiteSpace(clean) ? (title, null) : (clean, badge);
    }

    public void NotifyMetadataChanged(string accountId, CatalogItemType type, string itemId)
    {
        using var perfScope = PerfScope.Begin("NotifyMetadataChanged");
        var account = _account;
        var metadata = account is { } activeAccount && activeAccount.AccountId == accountId
            ? _repository.GetMetadata(activeAccount, type, itemId)
            : null;
        foreach (var card in AllKnownCards().Where(card => IsMatchingCard(card, accountId, type, itemId)))
        {
            if (metadata is not null) card.ApplyMetadata(metadata);
            card.NotifyMetadataChanged();
        }
    }

    private IEnumerable<CatalogCard> AllKnownCards()
    {
        var snapshotCards = _snapshotCache.Values.SelectMany(value => value.Snapshot.Shelves)
            .Concat(_snapshotCache.Values.SelectMany(value => value.Snapshot.SpotlightShelves))
            .SelectMany(shelf => shelf.Cards);
        var renderedCards = _renderedSnapshot is null
            ? Enumerable.Empty<CatalogCard>()
            : _renderedSnapshot.Shelves.Concat(_renderedSnapshot.SpotlightShelves).SelectMany(shelf => shelf.Cards);
        var openCards = OpenShelfGrid.Items.OfType<CatalogCard>();
        var spotlightCards = _spotlightCards.ToArray();
        return snapshotCards.Concat(renderedCards).Concat(_renderedShelves.SelectMany(shelf => shelf.Cards)).Concat(openCards)
            .Concat(spotlightCards)
            .Distinct();
    }

    private static bool IsMatchingCard(CatalogCard card, string accountId, CatalogItemType type, string itemId) =>
        card.Type == type && card.Channel is { } channel && channel.ProviderAccountId == accountId && channel.Id == itemId;

    private void CatalogCard_PointerEntered(object sender, PointerRoutedEventArgs args)
    {
        if (sender is FrameworkElement cardRoot)
            SetCardArtworkState(cardRoot, IsCardKeyboardFocused(cardRoot) ? "Focused" : "PointerOver");
        BeginCardSummary(sender as FrameworkElement, triggeredByFocus: false);
    }

    private void CatalogCard_GotFocus(object sender, RoutedEventArgs args)
    {
        if (sender is FrameworkElement cardRoot)
            SetCardArtworkState(cardRoot, IsCardKeyboardFocused(cardRoot) ? "Focused" : "PointerOver");

        var focusState = args.OriginalSource is UIElement focusedElement
            ? focusedElement.FocusState
            : (sender as UIElement)?.FocusState;
        if (focusState is not (FocusState.Keyboard or FocusState.Programmatic))
            return;

        BeginCardSummary(sender as FrameworkElement, triggeredByFocus: true);
    }

    private void BeginCardSummary(FrameworkElement? cardElement, bool triggeredByFocus)
    {
        if (cardElement is not { DataContext: CatalogCard { Channel: { } channel } card } ||
            !_isActive || _connection is null || _account is not { } account || account.AccountId != channel.ProviderAccountId)
            return;

        if (card.Type == CatalogItemType.Live)
        {
            _hoverRequestScheduler.Cancel();
            if (_hoveredCardElement is { } previousLiveElement && !ReferenceEquals(previousLiveElement, cardElement))
                DetachCardToolTip(previousLiveElement);
            _hoveredCard = card;
            _hoveredCardElement = cardElement;
            _hoverLease = null;
            _summaryTriggeredByFocus = triggeredByFocus;
            ShowCardToolTip(card, cardElement);
            return;
        }

        var key = $"{account.AccountId}\0{card.Type}\0{channel.Id}";
        var lease = _hoverRequestScheduler.Begin(key);
        if (_hoveredCardElement is { } previousElement && !ReferenceEquals(previousElement, cardElement))
            DetachCardToolTip(previousElement);
        _hoveredCard = card;
        _hoveredCardElement = cardElement;
        _hoverLease = lease;
        _summaryTriggeredByFocus = triggeredByFocus;
        if (!HoverMetadataLookup.NeedsLookup(channel.Metadata.GetValueOrDefault("plot")))
        {
            // The card already carries a summary: show it, never ask the provider.
            LaunchDiagnostics.Write($"event=hover.metadata source=card type={card.Type}");
            ShowCardToolTipIfCurrent(card, lease);
            return;
        }
        _ = _hoverRequestScheduler.RunAfterDelayAsync(lease, TimeSpan.FromMilliseconds(800), async cancellationToken =>
        {
            try
            {
                var metadata = await _hoverMetadataLookup.GetAsync(account, card.Type, channel.Id, cancellationToken);
                await UpdateHoverResultOnUiAsync(card, account, channel.Id, lease, metadata);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch
            {
                await UpdateHoverResultOnUiAsync(card, account, channel.Id, lease, null);
            }
        });
        _ = ApplyCachedHoverMetadataAsync(card, account, lease);
    }

    private async Task ApplyCachedHoverMetadataAsync(CatalogCard card, ProviderAccount account,
        HoverRequestScheduler.Lease lease)
    {
        try
        {
            var channel = card.Channel;
            if (channel is null) return;
            var metadata = await _hoverMetadataLookup.GetCachedAsync(account, card.Type, channel.Id, lease.CancellationToken);
            if (metadata is null) return;
            await RunOnUiThreadAsync(() =>
            {
                if (!IsCurrentHover(card, lease)) return;
                card.SetHoverMetadata(metadata);
                NotifyMetadataChanged(account.AccountId, card.Type, channel.Id);
                ShowCardToolTipIfCurrent(card, lease);
                _hoverRequestScheduler.Cancel(lease);
            });
        }
        catch (OperationCanceledException) when (lease.CancellationToken.IsCancellationRequested)
        {
        }
        catch
        {
            // The delayed lookup applies its own failure cooldown and keeps the card browseable.
        }
    }

    private bool IsCurrentHover(CatalogCard card, HoverRequestScheduler.Lease lease) =>
        ReferenceEquals(_hoveredCard, card) && _hoverLease == lease && _isActive &&
        _hoverRequestScheduler.IsCurrent(lease) && _account?.AccountId == card.Channel?.ProviderAccountId;

    private Task UpdateHoverResultOnUiAsync(CatalogCard card, ProviderAccount account, string itemId,
        HoverRequestScheduler.Lease lease, CatalogMetadata? metadata) =>
        RunOnUiThreadAsync(() =>
        {
            if (!IsCurrentHover(card, lease)) return;
            card.SetHoverMetadata(metadata);
            if (metadata is not null) NotifyMetadataChanged(account.AccountId, card.Type, itemId);
            ShowCardToolTipIfCurrent(card, lease);
        });

    private void ShowCardToolTipIfCurrent(CatalogCard card, HoverRequestScheduler.Lease lease)
    {
        if (!IsCurrentHover(card, lease) || _hoveredCardElement is not { } cardElement)
            return;

        ShowCardToolTip(card, cardElement);
    }

    private void ShowCardToolTip(CatalogCard card, FrameworkElement cardElement)
    {
        using var perfScope = PerfScope.Begin("HoverLookupUiUpdate");
        if (!card.HasHoverSummary)
        {
            DetachCardToolTip(cardElement);
            return;
        }

        EnsureCardToolTip();
        if (_cardToolTip is not { } toolTip || _cardToolTipTitle is null || _cardToolTipDetails is null ||
            _cardToolTipSummary is null || _cardToolTipNow is null || _cardToolTipNext is null || _cardToolTipCast is null)
            return;

        _cardToolTipTitle.Text = card.Title;
        _cardToolTipDetails.Text = card.DetailsLine;
        _cardToolTipDetails.Visibility = string.IsNullOrWhiteSpace(card.DetailsLine) ? Visibility.Collapsed : Visibility.Visible;
        _cardToolTipSummary.Text = card.HoverSummaryText;
        _cardToolTipSummary.Visibility = string.IsNullOrWhiteSpace(card.HoverSummaryText) ? Visibility.Collapsed : Visibility.Visible;
        _cardToolTipNow.Text = card.NowText;
        _cardToolTipNow.Visibility = string.IsNullOrWhiteSpace(card.NowText) ? Visibility.Collapsed : Visibility.Visible;
        _cardToolTipNext.Text = card.NextText;
        _cardToolTipNext.Visibility = string.IsNullOrWhiteSpace(card.NextText) ? Visibility.Collapsed : Visibility.Visible;
        _cardToolTipCast.Text = CatalogHoverTextFormatter.Cast(card.HoverCast);
        _cardToolTipCast.Visibility = string.IsNullOrWhiteSpace(_cardToolTipCast.Text) ? Visibility.Collapsed : Visibility.Visible;
        AutomationProperties.SetName(toolTip, string.Join(". ", new[]
        {
            card.Title, card.DetailsLine, card.HoverSummaryText, card.NowText, card.NextText, _cardToolTipCast.Text,
        }.Where(value => !string.IsNullOrWhiteSpace(value))));

        if (_cardToolTipOwner is { } previousOwner && !ReferenceEquals(previousOwner, cardElement))
            DetachCardToolTip(previousOwner);
        if (!ReferenceEquals(_cardToolTipOwner, cardElement))
        {
            ToolTipService.SetToolTip(cardElement, toolTip);
            _cardToolTipOwner = cardElement;
        }
        ApplyCardToolTipTheme();
        // Pointer-enter can happen before lazy content exists. Open explicitly once its summary arrives.
        toolTip.IsOpen = true;
    }

    private void EnsureCardToolTip()
    {
        if (_cardToolTip is not null) return;

        _cardToolTipTitle = CreateCardToolTipText(fontSize: 18, semiBold: true);
        _cardToolTipDetails = CreateCardToolTipText();
        _cardToolTipSummary = CreateCardToolTipText();
        _cardToolTipNow = CreateCardToolTipText();
        _cardToolTipNext = CreateCardToolTipText();
        _cardToolTipCast = CreateCardToolTipText();
        var content = new StackPanel { Width = 380, Spacing = 8 };
        content.Children.Add(_cardToolTipTitle);
        content.Children.Add(_cardToolTipDetails);
        content.Children.Add(_cardToolTipSummary);
        content.Children.Add(_cardToolTipNow);
        content.Children.Add(_cardToolTipNext);
        content.Children.Add(_cardToolTipCast);
        _cardToolTip = new ToolTip
        {
            Padding = new Thickness(14),
            MaxWidth = 420,
            MaxHeight = 280,
            BorderThickness = new Thickness(1),
            Content = new ScrollViewer
            {
                MaxHeight = 250,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                Content = content,
            },
        };
        ApplyCardToolTipTheme();
    }

    private static TextBlock CreateCardToolTipText(double fontSize = 14, bool semiBold = false) => new()
    {
        FontSize = fontSize,
        FontWeight = semiBold ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal,
        TextWrapping = TextWrapping.WrapWholeWords,
    };

    private Task RunOnUiThreadAsync(Action action)
    {
        var dispatcher = DispatcherQueue;
        if (dispatcher.HasThreadAccess)
        {
            using var perfScope = PerfScope.Begin("RunOnUiThread");
            action();
            return Task.CompletedTask;
        }

        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!dispatcher.TryEnqueue(() =>
            {
                using var perfScope = PerfScope.Begin("RunOnUiThread");
                try
                {
                    action();
                    completion.TrySetResult();
                }
                catch (Exception exception)
                {
                    completion.TrySetException(exception);
                }
            }))
            completion.TrySetCanceled();
        return completion.Task;
    }

    private void CatalogCard_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        if (sender is FrameworkElement cardRoot)
            SetCardArtworkState(cardRoot, IsCardKeyboardFocused(cardRoot) ? "Focused" : "Normal");
        if (ReferenceEquals(_hoveredCardElement, sender) && !_summaryTriggeredByFocus)
            CancelHoverRequest();
    }

    private void CatalogCard_LostFocus(object sender, RoutedEventArgs args)
    {
        if (sender is FrameworkElement cardRoot)
            SetCardArtworkState(cardRoot, "Normal");
        if (!ReferenceEquals(_hoveredCardElement, sender) || !_summaryTriggeredByFocus) return;
        CancelHoverRequest();
    }

    private static bool IsCardKeyboardFocused(FrameworkElement cardRoot)
    {
        for (var current = VisualTreeHelper.GetParent(cardRoot); current is not null; current = VisualTreeHelper.GetParent(current))
        {
            if (current is GridViewItem item)
                return item.FocusState is FocusState.Keyboard or FocusState.Programmatic;
        }

        return false;
    }

    private static void SetCardArtworkState(FrameworkElement cardRoot, string state)
    {
        if (PerfToggles.IsDisabled("lens")) return;

        var scale = state switch { "Focused" => 1.12, "PointerOver" => 1.10, _ => 1.0 };
        if (FindFirstImage(cardRoot) is { RenderTransform: ScaleTransform imageScale })
            AnimateScale(imageScale, scale);
        if (FindVisualElement(cardRoot, "PlaceholderArtworkGlyph") is { RenderTransform: ScaleTransform glyphScale })
            AnimateScale(glyphScale, scale);
    }

    // Lens effect: ease the artwork's own scale; the viewport clip keeps it inside the card.
    private static void AnimateScale(ScaleTransform transform, double target)
    {
        if (Math.Abs(transform.ScaleX - target) < 0.001 && Math.Abs(transform.ScaleY - target) < 0.001) return;
        var storyboard = new Storyboard();
        foreach (var property in new[] { "ScaleX", "ScaleY" })
        {
            var animation = new DoubleAnimation
            {
                To = target,
                Duration = TimeSpan.FromMilliseconds(140),
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
                EnableDependentAnimation = true,
            };
            Storyboard.SetTarget(animation, transform);
            Storyboard.SetTargetProperty(animation, property);
            storyboard.Children.Add(animation);
        }
        storyboard.Begin();
    }

    private static Image? FindFirstImage(DependencyObject root)
    {
        if (root is Image image) return image;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            if (FindFirstImage(VisualTreeHelper.GetChild(root, index)) is { } match)
                return match;
        return null;
    }

    // Rounded clip for the artwork viewport (WinUI's RectangleGeometry has no corner radius, so use a composition clip).
    private static void ApplyArtworkClip(Image image)
    {
        if (PerfToggles.IsDisabled("clips") || PerfToggles.IsDisabled("shelfclip")) return;
        if (VisualTreeHelper.GetParent(image) is not Grid viewport || image.DataContext is not CatalogCard card || card.SpotlightIndex >= 0 ||
            card.Width <= 0 || card.Height <= 0)
            return;
        var visual = ElementCompositionPreview.GetElementVisual(viewport);
        var geometry = visual.Compositor.CreateRoundedRectangleGeometry();
        geometry.Size = new System.Numerics.Vector2((float)card.Width, (float)card.Height);
        geometry.CornerRadius = new System.Numerics.Vector2(12, 12);
        visual.Clip = visual.Compositor.CreateGeometricClip(geometry);
    }

    private static FrameworkElement? FindVisualElement(DependencyObject root, string name)
    {
        if (root is FrameworkElement element && element.Name == name) return element;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            if (FindVisualElement(VisualTreeHelper.GetChild(root, index), name) is { } match)
                return match;
        return null;
    }

    private void CatalogCard_DataContextChanged(FrameworkElement sender, DataContextChangedEventArgs args)
    {
        if (ReferenceEquals(_hoveredCardElement, sender) && !ReferenceEquals(args.NewValue, _hoveredCard))
            CancelHoverRequest();
    }

    private void CatalogCard_Unloaded(object sender, RoutedEventArgs args)
    {
        if (ReferenceEquals(_hoveredCardElement, sender))
            CancelHoverRequest();
    }

    private void CatalogLandingPage_Unloaded(object sender, RoutedEventArgs args)
    {
        CommitDeferredShelfStructure();
        CancelShelfPreviewLoads();
        CancelHoverRequest();
    }

    private void CancelHoverRequest()
    {
        DetachCardToolTip(_hoveredCardElement);
        _hoverRequestScheduler.Cancel();
        _hoveredCard = null;
        _hoveredCardElement = null;
        _hoverLease = null;
        _summaryTriggeredByFocus = false;
    }

    private void DetachCardToolTip(FrameworkElement? element)
    {
        if (element is null || !ReferenceEquals(_cardToolTipOwner, element)) return;
        if (_cardToolTip is { } toolTip) toolTip.IsOpen = false;
        ToolTipService.SetToolTip(element, null);
        _cardToolTipOwner = null;
    }

    private static CatalogItemType TypeForMode(CatalogMode mode) => mode switch
    {
        CatalogMode.Movies => CatalogItemType.Movie,
        CatalogMode.Series => CatalogItemType.Series,
        _ => CatalogItemType.Live,
    };

    private static string SubtitleFor(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "Movie",
        CatalogItemType.Series => "Series",
        _ => "Live channel",
    };

    private static string SubtitleFor(StreamKind kind) => kind switch
    {
        StreamKind.Movie => "Movie",
        StreamKind.Series => "Series",
        StreamKind.Episode => "Series episode",
        _ => "Live",
    };

    private static string TypeLabel(StreamKind? kind) => kind switch
    {
        StreamKind.Movie => "MOVIE",
        StreamKind.Series or StreamKind.Episode => "SERIES",
        StreamKind.Live => "LIVE",
        _ => "CATALOG",
    };

    private static string NounFor(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "movies",
        CatalogItemType.Series => "series",
        _ => "channels",
    };

    private static string AllItemsShelfTitle(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "All movies",
        CatalogItemType.Series => "All series",
        _ => "All Live TV",
    };

    private static string CountLabel(int count, string noun)
    {
        if (count != 1) return $"{count} {noun}";
        var singular = noun switch
        {
            "movies" => "movie",
            "series" => "series",
            "channels" => "channel",
            "items" => "item",
            _ => noun.TrimEnd('s'),
        };
        return $"1 {singular}";
    }

    private static bool HasCatalogData(CatalogSnapshot snapshot) =>
        snapshot.IsFlatSearch || snapshot.Groups.Count > 0 || snapshot.Page.TotalCount > 0 || snapshot.Shelves.Count > 0;

    private static bool HasSameRenderedContent(CatalogSnapshot left, CatalogSnapshot right) =>
        ReferenceEquals(left, right) ||
        (left.Mode == right.Mode && left.SelectedGroupId == right.SelectedGroupId && left.Offset == right.Offset &&
        left.Page.TotalCount == right.Page.TotalCount && left.IsFlatSearch == right.IsFlatSearch &&
        left.SearchFilter == right.SearchFilter && left.Groups.SequenceEqual(right.Groups) &&
        left.Page.Items.Count == right.Page.Items.Count &&
        left.Page.Items.Zip(right.Page.Items).All(pair => SameVisibleChannel(pair.First, pair.Second)) &&
        left.Shelves.Count == right.Shelves.Count &&
        left.Shelves.Zip(right.Shelves).All(pair => SameVisibleShelf(pair.First, pair.Second)) &&
        left.SpotlightShelves.Count == right.SpotlightShelves.Count &&
        left.SpotlightShelves.Zip(right.SpotlightShelves).All(pair => SameVisibleShelf(pair.First, pair.Second)));

    private static bool SameVisibleShelf(CatalogShelf left, CatalogShelf right) =>
        ReferenceEquals(left, right) ||
        (left.Id == right.Id && left.Title == right.Title && left.CountLabel == right.CountLabel &&
        left.Type == right.Type && left.GroupId == right.GroupId && left.OpensPagedGrid == right.OpensPagedGrid &&
        left.IgnoreItemFilterForPreview == right.IgnoreItemFilterForPreview &&
        left.LatestAddedAt == right.LatestAddedAt && left.HasNewItems == right.HasNewItems &&
        left.Cards.Count == right.Cards.Count && (ReferenceEquals(left.Cards, right.Cards) || left.Cards.Zip(right.Cards).All(pair =>
            pair.First.Id == pair.Second.Id && pair.First.Title == pair.Second.Title &&
            pair.First.Subtitle == pair.Second.Subtitle && pair.First.ArtworkUrl == pair.Second.ArtworkUrl &&
            pair.First.QualityBadge == pair.Second.QualityBadge && pair.First.Type == pair.Second.Type)));

    private bool IsSameVisibleShelfListContext(CatalogSnapshot? previous, CatalogSnapshot next) =>
        previous is not null && _renderedOpenShelfId is null && _openShelfId is null &&
        ShelvesItems.Visibility == Visibility.Visible && !next.IsFlatSearch && !previous.IsFlatSearch &&
        previous.Mode == next.Mode && previous.SelectedGroupId == next.SelectedGroupId &&
        previous.SearchFilter == next.SearchFilter && _renderedShelfSort == _categorySort;

    private static bool SameVisibleChannel(Channel left, Channel right) =>
        left.Id == right.Id && left.DisplayName == right.DisplayName && left.GroupId == right.GroupId &&
        left.LogoUri == right.LogoUri && left.Source == right.Source;

    private async Task ApplySnapshotAsync(CatalogSnapshot snapshot, Func<bool>? isCurrent = null,
        bool deferShelfStructure = false)
    {
        CancelShelfPreviewLoads();
        using var refreshPhase = PerfScope.BeginPhase("apply");
        var applyStarted = System.Diagnostics.Stopwatch.GetTimestamp();
        var transitionGeneration = Volatile.Read(ref _modeTransitionGeneration);
        var fadedOut = false;
        var animateIncomingContent = false;
        var contentApplied = false;
        var applyScope = default(PerfScope);
        var snapshotApplyStarted = System.Diagnostics.Stopwatch.GetTimestamp();
        await _modeTransitionGate.WaitAsync();

        try
        {
            if (isCurrent is not null && !isCurrent()) return;
            if (snapshot.Mode != _activeMode || transitionGeneration != Volatile.Read(ref _modeTransitionGeneration))
                return;
            var previousSnapshot = _renderedSnapshot;
            var previousMode = previousSnapshot?.Mode;
            var modeChanged = previousMode is not null && previousMode != snapshot.Mode;
            animateIncomingContent = CatalogTransitionPolicy.ShouldAnimateIncomingContent(
                previousSnapshot is not null, modeChanged);
            if (modeChanged)
            {
                await FadeCatalogContentAsync(fadeOut: true, transitionGeneration: transitionGeneration);
                fadedOut = true;
            }
            else if (animateIncomingContent)
            {
                // The first snapshot has no outgoing surface to fade. Hide the
                // content before binding it so its first realized shelf/grid
                // layout is revealed by the same fade used after later swaps.
                var visual = ElementCompositionPreview.GetElementVisual(ContentState);
                visual.StopAnimation(nameof(Visual.Opacity));
                visual.Opacity = 0;
            }
            if ((isCurrent is not null && !isCurrent()) || snapshot.Mode != _activeMode ||
                transitionGeneration != Volatile.Read(ref _modeTransitionGeneration))
                return;

            await RefreshWatchProgressAsync(snapshot);
            if ((isCurrent is not null && !isCurrent()) || snapshot.Mode != _activeMode ||
                transitionGeneration != Volatile.Read(ref _modeTransitionGeneration))
                return;

            applyScope = PerfScope.Begin("ApplySnapshot");
            Interlocked.Increment(ref _pageQueryGeneration);
            _activeMode = snapshot.Mode;
            _groups = snapshot.Groups;
            _selectedGroupId = snapshot.SelectedGroupId;
            _offset = snapshot.Offset;
            SaveModeState(snapshot.Mode);

            UpdateModeChrome();
            UpdateEmptyCopy();
            if (snapshot.IsFlatSearch)
            {
                SearchResultsCount.Text = $"{snapshot.Page.TotalCount} {(snapshot.Page.TotalCount == 1 ? "match" : "matches")}";
                UpdateSearchCategoryChips(snapshot);
                if (snapshot.Page.TotalCount == 0)
                {
                    EmptyTitle.Text = "No matches";
                    EmptyMessage.Text = $"No matches for \"{snapshot.SearchFilter}\"";
                }
            }
            bool snapshotChanged;
            bool contentChanged;
            bool spotlightChanged;
            bool shouldRender;
            var sameOrderedShelfIds = _openShelfId is null && !snapshot.IsFlatSearch &&
                _renderedShelves.Select(shelf => shelf.Id)
                    .SequenceEqual(DeduplicateShelves(snapshot.Shelves).Select(shelf => shelf.Id), StringComparer.Ordinal);
            var preserveShelfOrder = deferShelfStructure || IsSameVisibleShelfListContext(previousSnapshot, snapshot);
            using (PerfScope.Begin("ApplySnapshotCompare"))
            {
                snapshotChanged = !ReferenceEquals(_renderedSnapshot, snapshot);
                contentChanged = previousSnapshot is null || !HasSameRenderedContent(previousSnapshot, snapshot);
                spotlightChanged = previousSnapshot is null ||
                    previousSnapshot.SpotlightShelves.Count != snapshot.SpotlightShelves.Count ||
                    !previousSnapshot.SpotlightShelves.Zip(snapshot.SpotlightShelves)
                        .All(pair => SameVisibleShelf(pair.First, pair.Second));
                shouldRender = (snapshotChanged && contentChanged) ||
                    _renderedOpenShelfId != _openShelfId;
            }
            LaunchDiagnostics.Write($"event=catalog.render reused={!shouldRender} mode={snapshot.Mode}");
            if (previousSnapshot is not null && contentChanged)
                VisibleCatalogDataChanged?.Invoke(this, EventArgs.Empty);
            if (shouldRender)
            {
                if (snapshotChanged && spotlightChanged) RenderSpotlight(snapshot.SpotlightShelves);
                using (PerfScope.Begin("BuildShelvesResultApply"))
                    RenderShelfSurface(snapshot, preserveShelfOrder);
                _renderedOpenShelfId = _openShelfId;
            }
            _renderedShelfSort = _categorySort;
            _renderedSnapshot = _openShelfId is null && !snapshot.IsFlatSearch
                ? snapshot with { Shelves = ProjectRenderedShelves() }
                : snapshot;
            // The shelf list is one shared scroller: a tab never visited before must open at its top,
            // not at the offset the previous tab was left at.
            if (modeChanged && !_browsePositions.ContainsKey((snapshot.Mode, _openShelfId ?? string.Empty)))
                ResetBrowseScroll();
            UpdatePaging(snapshot.Page.TotalCount);

            if ((snapshot.IsFlatSearch && snapshot.Page.TotalCount == 0) || (!snapshot.IsFlatSearch && snapshot.Shelves.Count == 0))
                SetState(empty: true);
            else
                SetState(content: true);
            // Browse restoration is applied before the rebound surface is revealed.
            var preserveScrollOffset = !shouldRender || (contentChanged && sameOrderedShelfIds);
            using (PerfScope.Begin("ApplySnapshotRestore"))
                RestoreBrowseStateIfMatching(snapshot.Mode, preserveScrollOffset);
            if (_openShelfId is null && !snapshot.IsFlatSearch && snapshot.Mode != CatalogMode.MyTvivo)
                ScheduleShelfViewportUpdate("apply");
            if (ContentState.Opacity >= 1)
                ContentState.IsHitTestVisible = true;
            contentApplied = true;
            if (_isActive && _activeMode == CatalogMode.LiveTv)
                _ = RefreshVisibleEpgAsync();
            applyScope.Dispose();
            applyScope = default;
        }
        finally
        {
            applyScope.Dispose();
            Interlocked.Exchange(ref _refreshApplyDurationTicks,
                System.Diagnostics.Stopwatch.GetTimestamp() - applyStarted);
            UpdateMaximum(ref _refreshApplyMaxDurationTicks, Interlocked.Read(ref _refreshApplyDurationTicks));
            Interlocked.Increment(ref _refreshApplyCount);
            Interlocked.Exchange(ref _snapshotApplyDurationTicks,
                System.Diagnostics.Stopwatch.GetTimestamp() - snapshotApplyStarted);
            UpdateMaximum(ref _snapshotApplyMaxDurationTicks, Interlocked.Read(ref _snapshotApplyDurationTicks));
            Interlocked.Increment(ref _snapshotApplyCount);
            if ((fadedOut || (animateIncomingContent && contentApplied)) && snapshot.Mode == _activeMode &&
                transitionGeneration == Volatile.Read(ref _modeTransitionGeneration))
                await FadeCatalogContentAsync(fadeOut: false, transitionGeneration: transitionGeneration);
            _modeTransitionGate.Release();
        }
    }

    private async Task FadeCatalogContentAsync(bool fadeOut, long transitionGeneration)
    {
        LaunchDiagnostics.Write($"event=perf.surface timestamp={DateTimeOffset.UtcNow:O} visibility=ContentState opacity={(fadeOut ? 0 : 1)}");
        var visual = ElementCompositionPreview.GetElementVisual(ContentState);
        Interlocked.Exchange(ref _contentFadeGeneration, transitionGeneration);
        visual.StopAnimation(nameof(Visual.Opacity));
        var start = visual.Opacity;
        var end = fadeOut ? 0f : 1f;
        visual.Opacity = start;
        var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(1f, end);
        animation.Duration = TimeSpan.FromMilliseconds(110);
        visual.StartAnimation(nameof(Visual.Opacity), animation);
        await Task.Delay(110);
        if (transitionGeneration != Volatile.Read(ref _modeTransitionGeneration))
        {
            if (transitionGeneration == Interlocked.Read(ref _contentFadeGeneration))
            {
                visual.StopAnimation(nameof(Visual.Opacity));
                visual.Opacity = 1;
            }
            return;
        }
        visual.StopAnimation(nameof(Visual.Opacity));
        visual.Opacity = end;
    }

    private void ResetContentFadeForGeneration(long transitionGeneration)
    {
        var visual = ElementCompositionPreview.GetElementVisual(ContentState);
        visual.StopAnimation(nameof(Visual.Opacity));
        visual.Opacity = 1;
        Interlocked.Exchange(ref _contentFadeGeneration, transitionGeneration);
    }

    private async Task RefreshWatchProgressAsync(CatalogSnapshot snapshot)
    {
        if (_account is not { } account)
            return;

        IEnumerable<CatalogCard> cards = snapshot.Shelves.SelectMany(shelf => shelf.Cards)
            .Concat(snapshot.SpotlightShelves.SelectMany(shelf => shelf.Cards));
        if (_spotlightCard is { } spotlightCard)
            cards = cards.Append(spotlightCard);
        if (OpenShelfGrid.ItemsSource is IEnumerable<CatalogCard> visibleCards)
            cards = cards.Concat(visibleCards);
        cards = cards.Distinct();
        var cardsToRefresh = cards.ToArray();
        var movieIds = snapshot.Page.Items.Where(channel => channel.Source.Kind == StreamKind.Movie)
            .Select(channel => channel.Id)
            .Concat(cardsToRefresh.Where(card => card.Type == CatalogItemType.Movie && card.Channel is not null)
                .Select(card => card.Channel!.Id))
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        var seriesIds = snapshot.Page.Items.Where(channel => channel.Source.Kind == StreamKind.Series)
            .Select(channel => channel.Id)
            .Concat(cardsToRefresh.Where(card => card.Type == CatalogItemType.Series && card.Channel is not null)
                .Select(card => card.Channel!.Id))
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        var (movieProgress, seriesProgress) = await Task.Run(() =>
            (_repository.GetPlaybackProgress(account, "movie", movieIds),
             _repository.GetPlaybackProgress(account, "series", seriesIds)));
        _movieProgressById = movieProgress;
        _seriesProgressById = seriesProgress;

        foreach (var card in cardsToRefresh.Where(card => card.Channel is not null))
        {
            var progress = card.Type switch
            {
                CatalogItemType.Movie => movieProgress.GetValueOrDefault(card.Channel!.Id),
                CatalogItemType.Series => seriesProgress.GetValueOrDefault(card.Channel!.Id),
                _ => null,
            };
            card.ApplyWatchProgress(progress);
        }
    }

    private void RenderSpotlight(IReadOnlyList<CatalogShelf> shelves)
    {
        _spotlightDwellCts?.Cancel();
        _spotlightDwellCts = null;
        _spotlightWindowFirst = _spotlightWindowLast = -1;
        var oldId = _spotlightCard?.Id;
        var focusedId = _spotlightCards.FirstOrDefault(card =>
            card.SpotlightButton?.FocusState is FocusState.Keyboard or FocusState.Programmatic)?.Id;
        var cards = shelves.SelectMany(shelf => shelf.Cards).Where(card => card.Channel is not null).Take(12).ToArray();
        for (var i = 0; i < cards.Length; i++)
        {
            var existingIndex = -1;
            for (var j = i; j < _spotlightCards.Count; j++)
                if (_spotlightCards[j].Id == cards[i].Id) { existingIndex = j; break; }
            if (existingIndex >= 0)
            {
                var existing = _spotlightCards[existingIndex];
                if (existing.UpdateSpotlightData(cards[i]) && existing.SpotlightButton is { } button &&
                    FindDescendant<Image>(button) is { } image) StopArtwork(image);
                if (existingIndex > i) _spotlightCards.Move(existingIndex, i);
            }
            else _spotlightCards.Insert(i, cards[i]);
        }
        while (_spotlightCards.Count > cards.Length)
        {
            _spotlightCards[^1].SpotlightButton = null;
            _spotlightCards.RemoveAt(_spotlightCards.Count - 1);
        }
        _spotlightState = SpotlightStripState.Create(cards.Length);
        if (oldId is not null)
        {
            var preserved = _spotlightCards.ToList().FindIndex(card => card.Id == oldId);
            if (preserved >= 0) _spotlightState = _spotlightState.Open(preserved);
        }
        _spotlightCard = _spotlightState.OpenIndex >= 0 ? _spotlightCards[_spotlightState.OpenIndex] : null;
        SpotlightPanel.Visibility = _openShelfId is null && _renderedSnapshot?.IsFlatSearch != true &&
            _activeMode != CatalogMode.LiveTv ? Visibility.Visible : Visibility.Collapsed;
        SpotlightEmptySettingsButton.Visibility = cards.Length == 0 && SpotlightPanel.Visibility == Visibility.Visible
            ? Visibility.Visible : Visibility.Collapsed;
        SpotlightPositionText.Text = cards.Length == 0 ? "Nothing to show yet" : $"Item {_spotlightState.OpenIndex + 1} of {cards.Length}";
        UpdateSpotlightCardStates();
        RequestSpotlightArtworkLayout();
        if (focusedId is not null)
        {
            var focusCard = _spotlightCards.FirstOrDefault(card => card.Id == focusedId);
            if (focusCard is not null)
                DispatcherQueue.TryEnqueue(() => focusCard.SpotlightButton?.Focus(FocusState.Programmatic));
        }
    }

    private void ClearSpotlightCards()
    {
        foreach (var card in _spotlightCards) card.SpotlightButton = null;
        foreach (var animation in _spotlightWidthAnimations.Values) animation.Stop();
        _spotlightWidthAnimations.Clear();
        _spotlightCards.Clear();
        _spotlightCard = null;
        _spotlightFocusedId = null;
        _spotlightWindowFirst = _spotlightWindowLast = -1;
    }

    private void UpdateSpotlightCardStates(int previousOpenIndex = -1, int newOpenIndex = -1)
    {
        var viewport = SpotlightStripViewer.ActualWidth > 0 ? SpotlightStripViewer.ActualWidth : 776;
        for (var i = 0; i < _spotlightCards.Count; i++)
        {
            var card = _spotlightCards[i];
            var targetWidth = i == _spotlightState.OpenIndex ? SpotlightStripState.OpenWidth(viewport) : 64;
            var animateOpening = i == newOpenIndex && _uiSettings.AnimationsEnabled &&
                card.SpotlightButton is { ActualWidth: > 0 } openingButton &&
                Math.Abs(openingButton.ActualWidth - targetWidth) > 0.5;
            card.SetSpotlightState(i, _spotlightCards.Count, i == _spotlightState.OpenIndex,
                targetWidth, i == _spotlightState.OpenIndex, !animateOpening);
            if (card.SpotlightButton is { } button)
            {
                if (_spotlightWidthAnimations.Remove(button, out var running)) running.Stop();
                var text = FindDescendant<StackPanel>(button);
                if (text is not null)
                {
                    if (i == newOpenIndex && _uiSettings.AnimationsEnabled) text.Opacity = 0;
                    else text.Opacity = card.SpotlightExpanded ? 1 : 0;
                }
                if (_uiSettings.AnimationsEnabled && (i == previousOpenIndex || i == newOpenIndex) &&
                    button.ActualWidth > 0 && Math.Abs(button.ActualWidth - card.SpotlightWidth) > 0.5)
                {
                    var animation = new DoubleAnimation
                    {
                        From = button.ActualWidth,
                        To = card.SpotlightWidth,
                        Duration = TimeSpan.FromMilliseconds(220),
                        EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
                        EnableDependentAnimation = true,
                    };
                    Storyboard.SetTarget(animation, button);
                    Storyboard.SetTargetProperty(animation, "Width");
                    var storyboard = new Storyboard();
                    storyboard.Children.Add(animation);
                    var cardIndex = i;
                    var finalWidth = card.SpotlightWidth;
                    _spotlightWidthAnimations[button] = storyboard;
                    storyboard.Completed += (_, _) =>
                    {
                        if (!_spotlightWidthAnimations.TryGetValue(button, out var current) || !ReferenceEquals(current, storyboard)) return;
                        _spotlightWidthAnimations.Remove(button);
                        storyboard.Stop();
                        if (ReferenceEquals(card.SpotlightButton, button)) button.Width = finalWidth;
                        RequestSpotlightArtworkLayout();
                        if (cardIndex == newOpenIndex && text is not null && ReferenceEquals(card.SpotlightButton, button))
                        {
                            card.RevealSpotlightText();
                            var reveal = new DoubleAnimation { From = 0, To = 1,
                                Duration = TimeSpan.FromMilliseconds(120), EnableDependentAnimation = false };
                            Storyboard.SetTarget(reveal, text);
                            Storyboard.SetTargetProperty(reveal, "Opacity");
                            var revealStoryboard = new Storyboard();
                            revealStoryboard.Children.Add(reveal);
                            revealStoryboard.Completed += (_, _) => { revealStoryboard.Stop(); text.Opacity = 1; };
                            revealStoryboard.Begin();
                        }
                    };
                    storyboard.Begin();
                }
                else
                {
                    button.Width = card.SpotlightWidth;
                    if (text is not null) text.Opacity = card.SpotlightExpanded ? 1 : 0;
                }
            }
        }
        if (_spotlightState.OpenIndex >= 0)
            SpotlightPositionText.Text = $"Item {_spotlightState.OpenIndex + 1} of {_spotlightCards.Count}";
    }

    private void OpenSpotlight(int index, string via, bool announce = false)
    {
        if (index < 0 || index >= _spotlightCards.Count || index == _spotlightState.OpenIndex) return;
        var previousOpenIndex = _spotlightState.OpenIndex;
        _spotlightState = _spotlightState.Open(index);
        _spotlightCard = _spotlightCards[index];
        UpdateSpotlightCardStates(previousOpenIndex, index);
        EnsureSpotlightVisible(index);
        UpdateSpotlightArtworkWindow();
        LaunchDiagnostics.Write($"event=spotlight.open index={index + 1} via={via}");
        if (announce && via == "keyboard")
        {
            var peer = FrameworkElementAutomationPeer.FromElement(SpotlightPositionText)
                ?? FrameworkElementAutomationPeer.CreatePeerForElement(SpotlightPositionText);
            peer?.RaiseNotificationEvent(AutomationNotificationKind.Other, AutomationNotificationProcessing.ImportantMostRecent,
                SpotlightStripState.Announcement(_spotlightCard.Title, index, _spotlightCards.Count), "SpotlightOpened");
        }
    }

    private void SpotlightItem_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { DataContext: CatalogCard card }) return;
        if (!_spotlightState.TouchTapActivates(card.SpotlightIndex))
        {
            OpenSpotlight(card.SpotlightIndex, "click");
            return;
        }
        if (card.Channel is { } channel) RaiseChannelSelected(channel, _spotlightCards.Select(item => item.Channel!).ToArray());
    }

    private async void SpotlightItem_PointerEntered(object sender, PointerRoutedEventArgs args)
    {
        if (sender is not Button { DataContext: CatalogCard card }) return;
        _spotlightDwellCts?.Cancel();
        var cancellation = _spotlightDwellCts = new CancellationTokenSource();
        try
        {
            await Task.Delay(SpotlightStripState.DwellDuration, cancellation.Token);
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!cancellation.IsCancellationRequested && _isActive &&
                    SpotlightStripState.ShouldOpenAfterDwell(_spotlightState.OpenIndex, card.SpotlightIndex,
                        SpotlightStripState.DwellDuration)) OpenSpotlight(card.SpotlightIndex, "hover");
            });
        }
        catch (OperationCanceledException) { }
    }

    private void SpotlightItem_PointerExited(object sender, PointerRoutedEventArgs args)
    {
        _spotlightDwellCts?.Cancel();
        _spotlightDwellCts = null;
    }

    private void SpotlightItem_GotFocus(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { DataContext: CatalogCard card } button) return;
        _spotlightFocusedId = card.Id;
        var keyboard = button.FocusState == FocusState.Keyboard;
        if (keyboard && card.SpotlightIndex != _spotlightState.OpenIndex)
            OpenSpotlight(card.SpotlightIndex, "keyboard", keyboard);
        card.SetSpotlightFocused(true);
    }

    private void SpotlightItem_LostFocus(object sender, RoutedEventArgs args)
    {
        if (sender is Button { DataContext: CatalogCard card }) card.SetSpotlightFocused(false);
    }

    private void SpotlightItem_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (sender is not Button { DataContext: CatalogCard card }) return;
        var next = args.Key switch
        {
            Windows.System.VirtualKey.Left => _spotlightState.Open(card.SpotlightIndex).Move(-1).OpenIndex,
            Windows.System.VirtualKey.Right => _spotlightState.Open(card.SpotlightIndex).Move(1).OpenIndex,
            _ => card.SpotlightIndex,
        };
        if (next == card.SpotlightIndex) return;
        args.Handled = true;
        OpenSpotlight(next, "keyboard", announce: true);
        _spotlightCards[next].SpotlightButton?.Focus(FocusState.Programmatic);
    }

    private void SpotlightItem_Loaded(object sender, RoutedEventArgs args)
    {
        if (sender is Button { DataContext: CatalogCard card } button)
        {
            card.SpotlightButton = button;
            button.Width = Math.Clamp(card.SpotlightWidth, 64, 672);
            RequestSpotlightArtworkLayout();
        }
    }

    private void SpotlightItem_Unloaded(object sender, RoutedEventArgs args)
    {
        if (sender is Button { DataContext: CatalogCard card } button && ReferenceEquals(card.SpotlightButton, button))
            card.SpotlightButton = null;
        if (sender is Button oldButton && _spotlightWidthAnimations.Remove(oldButton, out var animation)) animation.Stop();
    }

    private void RequestSpotlightArtworkLayout() => _spotlightArtworkLayoutPending = true;

    private void SpotlightStripItems_LayoutUpdated(object? sender, object args)
    {
        if (!_spotlightArtworkLayoutPending || SpotlightStripViewer.ViewportWidth <= 0 ||
            _spotlightCards.Any(card => card.SpotlightButton is not { ActualWidth: > 0 })) return;
        _spotlightArtworkLayoutPending = false;
        UpdateSpotlightArtworkWindow();
    }

    private void SpotlightStripViewer_ViewChanged(object sender, ScrollViewerViewChangedEventArgs args) => UpdateSpotlightArtworkWindow();

    private void SpotlightStripViewer_SizeChanged(object sender, SizeChangedEventArgs args)
    {
        UpdateSpotlightCardStates();
        RequestSpotlightArtworkLayout();
    }

    private void EnsureSpotlightVisible(int index)
    {
        if (index < 0 || index >= _spotlightCards.Count) return;
        var button = _spotlightCards[index].SpotlightButton;
        if (button is null) return;
        try
        {
            var transform = button.TransformToVisual(SpotlightStripViewer);
            var point = transform.TransformPoint(new Windows.Foundation.Point(0, 0));
            var target = SpotlightStripState.ScrollIntoViewTarget(point.X + SpotlightStripViewer.HorizontalOffset,
                button.ActualWidth, SpotlightStripViewer.HorizontalOffset, SpotlightStripViewer.ViewportWidth);
            SpotlightStripViewer.ChangeView(target, null, null, true);
        }
        catch (InvalidOperationException) { RequestSpotlightArtworkLayout(); }
        catch (ArgumentException) { RequestSpotlightArtworkLayout(); }
    }

    private void UpdateSpotlightArtworkWindow()
    {
        if (SpotlightPanel.Visibility != Visibility.Visible || _spotlightArtworkLayoutPending) return;
        var viewport = Math.Max(0, SpotlightStripViewer.ViewportWidth);
        var visibleIndices = new List<int>();
        foreach (var card in _spotlightCards)
        {
            if (card.SpotlightButton is not { IsLoaded: true, ActualWidth: > 0 } button) continue;
            try
            {
                var point = button.TransformToVisual(SpotlightStripViewer).TransformPoint(new Windows.Foundation.Point(0, 0));
                if (point.X + button.ActualWidth > 0 && point.X < viewport) visibleIndices.Add(card.SpotlightIndex);
            }
            catch (InvalidOperationException) { }
            catch (ArgumentException) { }
        }
        if (visibleIndices.Count == 0)
        {
            _spotlightMetadataCts?.Cancel();
            if (_spotlightCards.Count > 0) RequestSpotlightArtworkLayout();
            return;
        }
        var first = visibleIndices.Min();
        var last = visibleIndices.Max();
        _spotlightWindowFirst = Math.Max(0, first - 1);
        _spotlightWindowLast = Math.Min(_spotlightCards.Count - 1, last + 1);
        foreach (var card in _spotlightCards)
        {
            if (card.SpotlightButton is not { IsLoaded: true } button) continue;
            var inWindow = ShouldLoadSpotlightArtwork(card);
            if (FindDescendant<Image>(button) is { } image)
                if (inWindow && image.Source is null && !_artworkLoads.ContainsKey(image))
                {
                    ApplyArtworkClip(image);
                    StartArtwork(image, card.ArtworkUrl);
                }
                else if (!inWindow) StopArtwork(image);
        }
        if (_spotlightCard is { } openCard && !visibleIndices.Contains(openCard.SpotlightIndex))
            _spotlightMetadataCts?.Cancel();
        else if (_spotlightCard is { } visibleOpen && !visibleOpen.SpotlightSummaryResolved)
            ScheduleSpotlightMetadata(visibleOpen);
    }

    private async void ScheduleSpotlightMetadata(CatalogCard card)
    {
        if (card.SpotlightSummaryResolved ||
            ReferenceEquals(_spotlightMetadataCard, card) && _spotlightMetadataCts is { IsCancellationRequested: false }) return;
        _spotlightMetadataCts?.Cancel();
        var cancellation = _spotlightMetadataCts = new CancellationTokenSource();
        _spotlightMetadataCard = card;
        var token = cancellation.Token;
        try
        {
            await Task.Delay(350, token);
            if (token.IsCancellationRequested || !ReferenceEquals(_spotlightCard, card) ||
                SpotlightPanel.Visibility != Visibility.Visible || _account is not { } account || card.Channel is not { } channel ||
                !HoverMetadataLookup.NeedsLookup(channel.Metadata.GetValueOrDefault("plot"))) return;
            var metadata = await _hoverMetadataLookup.GetAsync(account, card.Type, channel.Id, token);
            if (metadata is null || token.IsCancellationRequested) return;
            await RunOnUiThreadAsync(() =>
            {
                if (token.IsCancellationRequested || !ReferenceEquals(_spotlightCard, card)) return;
                card.ApplyMetadata(metadata);
                card.ResolveSpotlightSummary();
                card.NotifyMetadataChanged();
            });
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch { }
        finally
        {
            if (ReferenceEquals(_spotlightMetadataCts, cancellation))
            {
                _spotlightMetadataCts = null;
                _spotlightMetadataCard = null;
            }
        }
    }

    private void SpotlightEmptySettingsButton_Click(object sender, RoutedEventArgs args) =>
        NavigateToSpotlightSettingsRequested?.Invoke(this, EventArgs.Empty);

    private void CancelSpotlightAsyncWork()
    {
        _spotlightDwellCts?.Cancel();
        _spotlightDwellCts = null;
        _spotlightMetadataCts?.Cancel();
        _spotlightMetadataCts = null;
        _spotlightMetadataCard = null;
        _spotlightArtworkLayoutPending = false;
        foreach (var card in _spotlightCards)
            if (card.SpotlightButton is not null)
                if (FindDescendant<Image>(card.SpotlightButton) is { } image) StopArtwork(image);
    }

    private bool ShouldLoadSpotlightArtwork(CatalogCard card)
    {
        return card.SpotlightIndex >= _spotlightWindowFirst && card.SpotlightIndex <= _spotlightWindowLast;
    }

    private CatalogSnapshot? TryGetCachedSnapshot(ProviderAccount account) =>
        _snapshotCache.TryGetValue(CurrentSnapshotKey(account), out var entry) ? entry.Snapshot : null;

    private CatalogSnapshotKey CurrentSnapshotKey(ProviderAccount account) =>
        new(account.AccountId, _activeMode, _openShelfId ?? string.Empty, _openShelfType?.ToString() ?? string.Empty,
            _selectedGroupId ?? string.Empty, SearchBox.Text ?? string.Empty, _categorySort, _offset);

    private void CacheSnapshot(ProviderAccount account, CatalogSnapshot snapshot)
    {
        var key = new CatalogSnapshotKey(account.AccountId, snapshot.Mode, _openShelfId ?? string.Empty,
            _openShelfType?.ToString() ?? string.Empty, snapshot.SelectedGroupId ?? string.Empty,
            SearchBox.Text ?? string.Empty, _categorySort, snapshot.Offset);
        var estimatedBytes = EstimateSnapshotBytes(snapshot);
        if (estimatedBytes > SnapshotCacheByteLimit)
        {
            RemoveCachedSnapshot(key);
            _snapshotCacheOrder.Clear();
            foreach (var cachedKey in _snapshotCache.Keys) _snapshotCacheOrder.Enqueue(cachedKey);
            return;
        }
        if (_snapshotCache.TryGetValue(key, out var previous))
            _snapshotCacheBytes -= previous.EstimatedBytes;
        else
            _snapshotCacheOrder.Enqueue(key);
        _snapshotCache[key] = new CachedCatalogSnapshot(snapshot, estimatedBytes);
        _snapshotCacheBytes += estimatedBytes;
        while ((_snapshotCache.Count > SnapshotCacheCapacity || _snapshotCacheBytes > SnapshotCacheByteLimit) &&
               _snapshotCacheOrder.TryDequeue(out var oldest))
        {
            if (_snapshotCache.Remove(oldest, out var removed)) _snapshotCacheBytes -= removed.EstimatedBytes;
        }
        TrimInactiveCachedShelfPreviews(key);
    }

    private void TrimInactiveCachedShelfPreviews(CatalogSnapshotKey keepKey)
    {
        foreach (var key in _snapshotCache.Keys.Where(key => key != keepKey).ToArray())
        {
            var cached = _snapshotCache[key];
            var snapshot = cached.Snapshot;
            IReadOnlyList<CatalogShelf> Strip(IReadOnlyList<CatalogShelf> shelves) => shelves.Select(shelf =>
                key.Mode != CatalogMode.MyTvivo && shelf.OpensPagedGrid && shelf.Cards.Count > 0
                    ? shelf with { Cards = Array.Empty<CatalogCard>() }
                    : shelf).ToArray();
            var strippedShelves = Strip(snapshot.Shelves);
            var strippedSpotlight = ReferenceEquals(snapshot.Shelves, snapshot.SpotlightShelves)
                ? null
                : Strip(snapshot.SpotlightShelves);
            var stripped = snapshot with { Shelves = strippedShelves, SpotlightSource = strippedSpotlight };
            var bytes = EstimateSnapshotBytes(stripped);
            _snapshotCache[key] = new CachedCatalogSnapshot(stripped, bytes);
            _snapshotCacheBytes += bytes - cached.EstimatedBytes;
        }
    }

    private void InvalidateAccountSnapshots(string accountId)
    {
        foreach (var key in _snapshotCache.Keys.Where(key => key.AccountId == accountId).ToArray())
            RemoveCachedSnapshot(key);
        _snapshotCacheOrder.Clear();
        foreach (var key in _snapshotCache.Keys) _snapshotCacheOrder.Enqueue(key);
    }

    private void RemoveCachedSnapshot(CatalogSnapshotKey key)
    {
        if (_snapshotCache.Remove(key, out var removed)) _snapshotCacheBytes -= removed.EstimatedBytes;
    }

    private static long EstimateSnapshotBytes(CatalogSnapshot snapshot) =>
        1024L + snapshot.Groups.Count * 192L + snapshot.Page.Items.Sum(EstimateChannelBytes) +
        EstimateShelvesBytes(snapshot.Shelves) +
        (ReferenceEquals(snapshot.Shelves, snapshot.SpotlightShelves) ? 0 : EstimateShelvesBytes(snapshot.SpotlightShelves));

    private static long EstimateShelvesBytes(IReadOnlyList<CatalogShelf> shelves) =>
        256L * shelves.Count + shelves.Sum(shelf =>
            96L + EstimateStringBytes(shelf.Id) + EstimateStringBytes(shelf.Title) + EstimateStringBytes(shelf.CountLabel) +
            shelf.Cards.Sum(card => 192L + EstimateStringBytes(card.Id) + EstimateStringBytes(card.Title) +
                EstimateStringBytes(card.Subtitle) + EstimateStringBytes(card.ArtworkUrl)));

    private static long EstimateChannelBytes(Channel channel) =>
        192L + EstimateStringBytes(channel.Id) + EstimateStringBytes(channel.DisplayName) +
        EstimateStringBytes(channel.LogoUri?.ToString()) + channel.Metadata.Sum(pair =>
            EstimateStringBytes(pair.Key) + EstimateStringBytes(pair.Value));

    private static long EstimateStringBytes(string? value) => value is null ? 0 : 24L + value.Length * 2L;

    private void SaveModeState(CatalogMode mode) =>
        _modeStates[mode] = new ModeInteractionState(_openShelfId, _openShelfType, _selectedGroupId, SearchBox.Text ?? string.Empty, _offset);

    // A new page, sort or search is a fresh list: start at the top and forget the position that
    // was remembered for the previous list, so a later re-render cannot scroll back down.
    private void ResetBrowseScroll()
    {
        _browsePositions.Remove((_activeMode, _openShelfId ?? string.Empty));

        void ScrollToTop()
        {
            var scrollViewer = ActiveVerticalScrollViewer();
            scrollViewer?.ChangeView(null, 0, null, true);
        }

        ScrollToTop();
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            using var perfScope = PerfScope.Begin("BrowseScrollResetDispatch");
            ScrollToTop();
        });
    }

    private void CaptureBrowseState()
    {
        var scrollViewer = ActiveVerticalScrollViewer();
        var offset = scrollViewer?.VerticalOffset ?? 0;
        var firstVisibleShelf = _openShelfId is null ? FirstVisibleShelfPosition() : null;

        string? focusedCardId = null;
        if (XamlRoot is not null && FocusManager.GetFocusedElement(XamlRoot) is DependencyObject focused)
        {
            for (var current = focused; current is not null; current = VisualTreeHelper.GetParent(current))
            {
                if (current is FrameworkElement { DataContext: CatalogCard card })
                {
                    focusedCardId = card.Id;
                    break;
                }
            }
        }

        var key = (_activeMode, _openShelfId ?? string.Empty);
        if (!_browsePositions.ContainsKey(key) && _browsePositions.Count >= BrowsePositionCapacity)
            _browsePositions.Remove(_browsePositions.Keys.First());
        _browsePositions[key] = new BrowsePosition(offset, focusedCardId,
            firstVisibleShelf?.Id, firstVisibleShelf?.OffsetWithinShelf ?? 0);
    }

    private void RestoreBrowseStateIfMatching(CatalogMode mode, bool preserveScrollOffset = false)
    {
        var key = (mode, _openShelfId ?? string.Empty);
        if (!_browsePositions.TryGetValue(key, out var position)) return;
        // Deliberately not removed here: a background activity refresh can re-render this same
        // shelf shortly after we return, and that render needs the same remembered position too.
        // It's only replaced by the next real CaptureBrowseState call (a fresh navigation away).
        var openShelfId = _openShelfId;
        var generation = Interlocked.Increment(ref _browseRestoreGeneration);
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        var attempts = 0;
        var hidden = !preserveScrollOffset;
        if (hidden)
        {
            var visual = ElementCompositionPreview.GetElementVisual(ContentState);
            visual.StopAnimation(nameof(Visual.Opacity));
            ContentState.Opacity = 0;
            ContentState.IsHitTestVisible = false;
        }

        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Normal, () =>
        {
            if (generation != Volatile.Read(ref _browseRestoreGeneration)) return;
            using var restoreScope = PerfScope.Begin("BrowseRestore");
            var retryTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
            var timeout = DispatcherQueue.CreateTimer();
            timeout.Interval = TimeSpan.FromMilliseconds(350);
            timeout.IsRepeating = false;
            void Finish(string path)
            {
                retryTimer.Stop();
                timeout.Stop();
                if (generation != Volatile.Read(ref _browseRestoreGeneration)) return;
                if (hidden)
                {
                    ContentState.Opacity = 1;
                    ContentState.IsHitTestVisible = true;
                }
                var hiddenMs = hidden
                    ? (System.Diagnostics.Stopwatch.GetTimestamp() - started) * 1000.0 / System.Diagnostics.Stopwatch.Frequency
                    : 0;
                LaunchDiagnostics.Write($"event=browse.restore mode={mode} path={path} hiddenMs={hiddenMs:F0} attempts={attempts}");
            }

            bool RestoreOnce()
            {
                attempts++;
                var restored = preserveScrollOffset;
                Control? focusTarget = null;
                var viewer = ActiveVerticalScrollViewer();
                if (!preserveScrollOffset && openShelfId is not null && position.FocusedCardId is { } cardId)
                {
                    var card = OpenShelfGrid.Items.OfType<CatalogCard>().FirstOrDefault(item => item.Id == cardId);
                    if (card is not null)
                    {
                        OpenShelfGrid.ScrollIntoView(card, ScrollIntoViewAlignment.Leading);
                        focusTarget = OpenShelfGrid.ContainerFromItem(card) as Control;
                        if (focusTarget is not null && viewer is not null)
                        {
                            var top = focusTarget.TransformToVisual(viewer).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
                            viewer.ChangeView(null, Math.Clamp(viewer.VerticalOffset + top, 0, viewer.ScrollableHeight), null, true);
                        }
                        restored = focusTarget is not null;
                    }
                }
                else if (!preserveScrollOffset && openShelfId is null && position.FirstVisibleShelfId is { } shelfId &&
                    _renderedShelves.FirstOrDefault(shelf => shelf.Id == shelfId) is { } shelf)
                {
                    ShelvesItems.ScrollIntoView(shelf, ScrollIntoViewAlignment.Leading);
                    if (viewer is not null && ShelvesItems.ContainerFromItem(shelf) is FrameworkElement container)
                    {
                        var top = container.TransformToVisual(viewer).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
                        var target = Math.Clamp(viewer.VerticalOffset + top + position.OffsetWithinShelf, 0, viewer.ScrollableHeight);
                        viewer.ChangeView(null, target, null, true);
                        restored = Math.Abs(top + position.OffsetWithinShelf) < 1.0;
                    }
                }
                else if (!preserveScrollOffset && viewer is not null && position.ScrollOffset > 0)
                {
                    viewer.ChangeView(null, position.ScrollOffset, null, true);
                    restored = Math.Abs(viewer.VerticalOffset - position.ScrollOffset) < 1.0;
                }
                else if (position.FocusedCardId is null && position.FirstVisibleShelfId is null &&
                    position.ScrollOffset <= 0)
                {
                    restored = true;
                }
                if (!restored && !preserveScrollOffset && viewer is not null && position.ScrollOffset > 0)
                {
                    viewer.ChangeView(null, position.ScrollOffset, null, true);
                    restored = Math.Abs(viewer.VerticalOffset - position.ScrollOffset) < 1.0;
                }

                if (openShelfId is not null && position.FocusedCardId is { } focusedId)
                {
                    var card = OpenShelfGrid.Items.OfType<CatalogCard>().FirstOrDefault(item => item.Id == focusedId);
                    focusTarget ??= card is null ? null : OpenShelfGrid.ContainerFromItem(card) as Control;
                    if (focusTarget is not null) focusTarget.Focus(FocusState.Programmatic);
                    restored &= focusTarget is not null && XamlRoot is not null &&
                        ReferenceEquals(FocusManager.GetFocusedElement(XamlRoot), focusTarget);
                }
                else if (openShelfId is null && position.FocusedCardId is { } shelfCardId)
                {
                    if (_renderedSnapshot?.IsFlatSearch == true && OpenShelfGrid.Visibility == Visibility.Visible)
                    {
                        var card = OpenShelfGrid.Items.OfType<CatalogCard>().FirstOrDefault(item => item.Id == shelfCardId);
                        if (card is not null)
                        {
                            if (!preserveScrollOffset) OpenShelfGrid.ScrollIntoView(card, ScrollIntoViewAlignment.Leading);
                            focusTarget = OpenShelfGrid.ContainerFromItem(card) as Control;
                        }
                    }
                    else
                    {
                        var shelf = _renderedShelves.FirstOrDefault(row => row.Cards.Any(card => card.Id == shelfCardId));
                        var shelfContainer = shelf is null ? null : ShelvesItems.ContainerFromItem(shelf) as FrameworkElement;
                        if (!preserveScrollOffset && shelf is not null && shelfContainer is null)
                            ShelvesItems.ScrollIntoView(shelf, ScrollIntoViewAlignment.Leading);
                        shelfContainer ??= shelf is null ? null : ShelvesItems.ContainerFromItem(shelf) as FrameworkElement;
                        var preview = shelfContainer is null ? null : FindDescendant<GridView>(shelfContainer);
                        var card = shelf?.Cards.FirstOrDefault(item => item.Id == shelfCardId);
                        if (preview is not null && card is not null)
                        {
                            if (!preserveScrollOffset) preview.ScrollIntoView(card, ScrollIntoViewAlignment.Leading);
                            focusTarget = preview.ContainerFromItem(card) as Control;
                        }
                    }
                    if (focusTarget is not null) focusTarget.Focus(FocusState.Programmatic);
                    restored &= focusTarget is not null && XamlRoot is not null &&
                        ReferenceEquals(FocusManager.GetFocusedElement(XamlRoot), focusTarget);
                }
                return restored;
            }

            if (RestoreOnce()) { Finish(preserveScrollOffset ? "in-place" : "rebind"); return; }
            retryTimer.Tick += (_, _) =>
            {
                if (generation != Volatile.Read(ref _browseRestoreGeneration)) { retryTimer.Stop(); return; }
                if (RestoreOnce() || attempts >= 7) Finish(preserveScrollOffset ? "in-place" : "rebind");
            };
            retryTimer.Start();
            timeout.Tick += (_, _) => Finish(preserveScrollOffset ? "in-place" : "rebind");
            timeout.Start();
        });
    }

    private (string Id, double OffsetWithinShelf)? FirstVisibleShelfPosition()
    {
        var viewer = _shelfScrollViewer ?? FindShelfScrollViewer(ShelvesItems);
        if (viewer is null || ShelvesItems.Visibility != Visibility.Visible) return null;
        for (var index = 0; index < _renderedShelves.Count; index++)
        {
            if (ShelvesItems.ContainerFromIndex(index) is not FrameworkElement container) continue;
            var top = container.TransformToVisual(viewer).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
            if (top + container.ActualHeight <= 0 || top >= viewer.ViewportHeight) continue;
            return (_renderedShelves[index].Id, Math.Max(0, -top));
        }
        return null;
    }

    private void SetSearchTextWithoutReload(string value)
    {
        _suppressSearchChanged = true;
        try
        {
            SearchBox.Text = value;
        }
        finally
        {
            _suppressSearchChanged = false;
        }
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(value) ? Visibility.Collapsed : Visibility.Visible;
        SearchTextChanged?.Invoke(value);
    }

    private void ArtworkImage_Opened(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ArtworkOpened");
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap) || !IsCurrentArtworkTarget(image, load)) return;
        ReleaseArtworkSlot(image, load, "ImageOpened");
        load.Cancellation.Cancel();
        image.Opacity = 1;
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Collapsed;
        _artworkLoads.Remove(image);
        load.Cancellation.Dispose();
    }

    private void ArtworkImage_Failed(object sender, ExceptionRoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ArtworkFailed");
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap)) return;
        LogArtworkFailure("ImageFailed");
        ReleaseArtworkSlot(image, load, "ImageFailed");
        load.Cancellation.Cancel();
        _artworkLoads.Remove(image);
        image.Opacity = 0;
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Visible;
        load.Cancellation.Dispose();
    }

    private static FrameworkElement? FindArtworkFallback(Image image)
    {
        if (VisualTreeHelper.GetParent(image) is not Grid host) return null;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(host); index++)
            if (VisualTreeHelper.GetChild(host, index) is FrameworkElement child &&
                child.Name == "ArtworkFallback") return child;
        return null;
    }

    private void ArtworkImage_DataContextChanged(FrameworkElement sender, DataContextChangedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ArtworkDataContextChanged");
        if (sender is Image image && image.IsLoaded &&
            (image.DataContext is not CatalogCard card || !_spotlightCards.Contains(card) || ShouldLoadSpotlightArtwork(card)))
            StartArtwork(image, (image.DataContext as CatalogCard)?.ArtworkUrl);
    }

    private void ArtworkImage_Loaded(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ArtworkLoaded");
        if (sender is not Image image) return;
        image.DataContextChanged -= ArtworkImage_DataContextChanged;
        image.ImageOpened -= ArtworkImage_Opened;
        image.ImageFailed -= ArtworkImage_Failed;
        image.DataContextChanged += ArtworkImage_DataContextChanged;
        image.ImageOpened += ArtworkImage_Opened;
        image.ImageFailed += ArtworkImage_Failed;
        if (_loadedArtworkImages.Add(image)) Interlocked.Increment(ref _artworkImagesLoaded);
        if (image.DataContext is CatalogCard spotlightCard && _spotlightCards.Contains(spotlightCard) && !ShouldLoadSpotlightArtwork(spotlightCard)) return;
        ApplyArtworkClip(image);

        var url = (image.DataContext as CatalogCard)?.ArtworkUrl;
        StartArtwork(image, url);
    }

    private void ShelfContainer_Loaded(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ShelfContainerLoaded");
        if (sender is not FrameworkElement container) return;
        container.DataContextChanged -= ShelfContainer_DataContextChanged;
        container.DataContextChanged += ShelfContainer_DataContextChanged;
        ResetRecycledShelfContainer(container);
        Interlocked.Increment(ref _shelfContainersRealized);
        if (_liveShelfContainerElements.Add(container)) Interlocked.Increment(ref _liveShelfContainers);
    }

    private void ShelfContainer_DataContextChanged(object sender, object args)
    {
        if (sender is FrameworkElement container)
            ResetRecycledShelfContainer(container);
    }

    private void ResetRecycledShelfContainer(FrameworkElement container)
    {
        if (container.DataContext is not ShelfRowViewModel row ||
            (_lastShelfContainerIds.TryGetValue(container, out var previousId) && previousId == row.Id))
            return;

        _lastShelfContainerIds[container] = row.Id;
        container.Opacity = 1;
        var preview = FindDescendant<GridView>(container);
        if (preview is not null)
        {
            preview.Opacity = 1;
            preview.ItemsSource = null;
            preview.SetBinding(ItemsControl.ItemsSourceProperty,
                new Microsoft.UI.Xaml.Data.Binding { Path = new PropertyPath(nameof(ShelfRowViewModel.Cards)) });
            preview.SelectedIndex = -1;
            container.DispatcherQueue.TryEnqueue(() => FindShelfScrollViewer(preview)?.ChangeView(0, null, null, true));
        }

        var skeleton = FindDescendant<ItemsControl>(container);
        skeleton?.SetBinding(ItemsControl.ItemsSourceProperty,
            new Microsoft.UI.Xaml.Data.Binding { Path = new PropertyPath(nameof(ShelfRowViewModel.SkeletonTiles)) });
    }

    private static T? FindDescendant<T>(DependencyObject root) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (child is T match) return match;
            if (FindDescendant<T>(child) is { } nested) return nested;
        }
        return null;
    }

    private void ShelfContainer_Unloaded(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ShelfContainerUnloaded");
        if (sender is not FrameworkElement container) return;
        container.DataContextChanged -= ShelfContainer_DataContextChanged;
        _lastShelfContainerIds.Remove(container);
        Interlocked.Increment(ref _shelfContainersRecycled);
        if (_liveShelfContainerElements.Remove(container)) Interlocked.Decrement(ref _liveShelfContainers);
    }

    private void ArtworkImage_Unloaded(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ArtworkUnloaded");
        if (sender is Image image)
        {
            if (_loadedArtworkImages.Remove(image)) Interlocked.Increment(ref _artworkImagesUnloaded);
            StopArtwork(image);
            image.DataContextChanged -= ArtworkImage_DataContextChanged;
            image.ImageOpened -= ArtworkImage_Opened;
            image.ImageFailed -= ArtworkImage_Failed;
            if (VisualTreeHelper.GetParent(image) is Grid viewport)
                ElementCompositionPreview.GetElementVisual(viewport).Clip = null;
        }
    }

    public void LoadHomeArtwork(Image image) => StartArtwork(image, (image.DataContext as HomePage.HomeShelfCard)?.ArtworkUrl);

    public void UnloadHomeArtwork(Image image) => StopArtwork(image);

    private async void StartArtwork(Image image, string? url)
    {
        var isSpotlightCard = image.DataContext is CatalogCard stripCard && _spotlightCards.Contains(stripCard);
        if (image.DataContext is CatalogCard spotlightCard && isSpotlightCard &&
            !ShouldLoadSpotlightArtwork(spotlightCard)) return;
        if (isSpotlightCard && PerfToggles.IsDisabled("spotlightart"))
        {
            image.Source = null;
            image.Opacity = 0;
            image.Visibility = Visibility.Collapsed;
            return;
        }

        var card = image.DataContext as CatalogCard;
        var homeCard = image.DataContext as HomePage.HomeShelfCard;
        var itemKey = card?.Id ?? (homeCard is null ? "card:unresolved" : $"home:{homeCard.Channel.Source.Kind}:{homeCard.Channel.Id}");
        if ((card is not null || homeCard is not null) && _appliedArtwork.TryGetValue(image, out var applied) &&
            ArtworkReusePolicy.ShouldReuse(applied.ItemKey, applied.Url, itemKey, url,
                ReferenceEquals(image.Source, applied.Bitmap)))
        {
            image.Opacity = 1;
            if (FindArtworkFallback(image) is { } appliedFallback) appliedFallback.Visibility = Visibility.Collapsed;
            return;
        }

        StopArtwork(image);
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Visible;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https"))
        {
            LogArtworkFailure("InvalidArtworkUri");
            return;
        }
        var load = new ArtworkLoad();
        load.ItemKey = itemKey;
        load.Url = url!;
        _artworkLoads[image] = load;
        var scale = Math.Clamp(image.XamlRoot?.RasterizationScale ?? 1, 1, 2);
        var decodeWidth = homeCard is not null ? 320 : isSpotlightCard ? (int)Math.Ceiling(190 * scale) : 380;
        var decodeHeight = homeCard is not null ? 450 : isSpotlightCard ? (int)Math.Ceiling(285 * scale) : card?.Height == 138 ? 276 : 500;
        var cacheKey = $"{uri.AbsoluteUri}|{decodeWidth}x{decodeHeight}";
        if (TryGetCachedArtworkBitmap(cacheKey, out var cachedBitmap))
        {
            load.Stage = "cache-hit";
            load.Bitmap = cachedBitmap;
            _ = ExpireArtworkAsync(image, load);
            ApplyArtworkToLiveImage(image, load, cachedBitmap, "cache-hit");
            return;
        }

        var stage = "disk-or-http";
        try
        {
            if (isSpotlightCard)
            {
                load.Stage = "spotlight-permit-wait";
                await _spotlightArtworkSlots.WaitAsync(load.Cancellation.Token);
                load.SpotlightSlotAcquired = true;
            }
            load.Stage = stage;
            var bytes = await ArtworkBytes.GetAsync(uri, load.Cancellation.Token);
            if (bytes is null)
                throw new InvalidDataException("Artwork image was unavailable or exceeded the decode limit.");
            RecordArtworkSource(bytes);
            if (!IsCurrentArtworkLoad(image, load, stage)) return;

            stage = "decode-permit-wait";
            load.Stage = stage;
            await _artworkSlots.WaitAsync(load.Cancellation.Token);
            load.SlotAcquired = true;
            stage = "decode";
            load.Stage = stage;
            if (!IsCurrentArtworkLoad(image, load, "permit-acquired")) return;

            var bitmap = new BitmapImage
            {
                DecodePixelWidth = decodeWidth,
                DecodePixelHeight = decodeHeight,
            };
            stage = "decode";
            load.Stage = stage;
            using var imageStream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(imageStream))
            {
                var copyStarted = System.Diagnostics.Stopwatch.GetTimestamp();
                try
                {
                    writer.WriteBytes(bytes);
                    await writer.StoreAsync();
                    writer.DetachStream();
                }
                finally
                {
                    Interlocked.Add(ref _artworkStreamCopyTicks, System.Diagnostics.Stopwatch.GetTimestamp() - copyStarted);
                }
            }
            imageStream.Seek(0);
            var setSourceStarted = System.Diagnostics.Stopwatch.GetTimestamp();
            try { await bitmap.SetSourceAsync(imageStream); }
            finally { Interlocked.Add(ref _artworkSetSourceTicks, System.Diagnostics.Stopwatch.GetTimestamp() - setSourceStarted); }
            UpdateArtworkMax(ref _artworkPixelWidthMax, bitmap.PixelWidth);
            UpdateArtworkMax(ref _artworkPixelHeightMax, bitmap.PixelHeight);
            using var completionScope = PerfScope.Begin("ArtworkDecodeComplete");
            if (!IsCurrentArtworkLoad(image, load, "decode")) return;
            load.Bitmap = bitmap;
            CacheArtworkBitmap(cacheKey, bitmap);
            _ = ExpireArtworkAsync(image, load);
            ApplyArtworkToLiveImage(image, load, bitmap, "decoded");
        }
        catch (OperationCanceledException)
        {
            var canceledByView = load.Cancellation.IsCancellationRequested;
            if (!canceledByView)
                LogArtworkFailure("HttpTimeout");
        }
        catch (Exception exception)
        {
            LogArtworkFailure(exception.GetType().Name);
            load.Cancellation.Cancel();
            if (_artworkLoads.TryGetValue(image, out var activeLoad) && ReferenceEquals(activeLoad, load))
            {
                if (FindArtworkFallback(image) is { } errorFallback) errorFallback.Visibility = Visibility.Visible;
                image.Opacity = 0;
            }
        }
        finally
        {
            ReleaseArtworkSlot(image, load, "request-finished");
        }
    }

    private bool TryGetCachedArtworkBitmap(string cacheKey, out BitmapImage bitmap)
    {
        if (_artworkBitmapCache.TryGetValue(cacheKey, out var node))
        {
            _artworkBitmapLru.Remove(node);
            _artworkBitmapLru.AddFirst(node);
            bitmap = node.Value.Bitmap;
            return true;
        }
        bitmap = null!;
        return false;
    }

    private void CacheArtworkBitmap(string cacheKey, BitmapImage bitmap)
    {
        if (_artworkBitmapCache.Remove(cacheKey, out var previous))
        {
            _artworkBitmapLru.Remove(previous);
            _artworkBitmapCacheBytes -= previous.Value.DecodedBytes;
        }
        var decodedBytes = ArtworkMemoryBudget.EstimateDecodedBytes(bitmap.PixelWidth, bitmap.PixelHeight);
        var node = _artworkBitmapLru.AddFirst(new CachedArtworkBitmap(cacheKey, bitmap, decodedBytes));
        _artworkBitmapCache[cacheKey] = node;
        _artworkBitmapCacheBytes += decodedBytes;
        while (ArtworkMemoryBudget.ShouldEvict(_artworkBitmapCacheBytes, ArtworkBitmapCacheByteLimit,
                   _artworkBitmapCache.Count, ArtworkBitmapCacheCountLimit))
        {
            var leastRecentlyUsed = _artworkBitmapLru.Last!;
            _artworkBitmapLru.RemoveLast();
            _artworkBitmapCache.Remove(leastRecentlyUsed.Value.CacheKey);
            _artworkBitmapCacheBytes -= leastRecentlyUsed.Value.DecodedBytes;
        }
    }

    private bool IsCurrentArtworkTarget(Image image, ArtworkLoad load)
    {
        if (!image.IsLoaded || image.XamlRoot is null) return false;
        var currentCard = image.DataContext as CatalogCard;
        if (image.DataContext is HomePage.HomeShelfCard homeCard)
            return $"home:{homeCard.Channel.Source.Kind}:{homeCard.Channel.Id}" == load.ItemKey && homeCard.ArtworkUrl == load.Url;
        return currentCard is not null && currentCard.Id == load.ItemKey && currentCard.ArtworkUrl == load.Url;
    }

    private bool IsCurrentArtworkLoad(Image image, ArtworkLoad load, string stage)
    {
        if (!load.Cancellation.IsCancellationRequested &&
            _artworkLoads.TryGetValue(image, out var current) && ReferenceEquals(current, load)) return true;

        return false;
    }

    private void ApplyArtworkToLiveImage(Image image, ArtworkLoad load, BitmapImage bitmap, string source)
    {
        using var perfScope = PerfScope.Begin("ArtworkAssign");
        if (!_artworkLoads.TryGetValue(image, out var current) || !ReferenceEquals(current, load) ||
            !ReferenceEquals(load.Bitmap, bitmap) || !IsCurrentArtworkTarget(image, load))
        {
            return;
        }

        image.Source = bitmap;
        image.Opacity = 1;
        _appliedArtwork[image] = new AppliedArtwork(load.ItemKey, load.Url, bitmap);
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Collapsed;
    }

    private void LogArtworkFailure(string category)
    {
        var now = System.Diagnostics.Stopwatch.GetTimestamp();
        if (_artworkFailureLogState.TryGetValue(category, out var state) &&
            System.Diagnostics.Stopwatch.GetElapsedTime(state.LastLoggedAt, now) < TimeSpan.FromSeconds(10))
        {
            _artworkFailureLogState[category] = (state.LastLoggedAt, state.Count + 1);
            return;
        }

        if (!_artworkFailureLogState.ContainsKey(category) &&
            _artworkFailureLogState.Count >= ArtworkFailureCategoryCapacity)
            _artworkFailureLogState.Remove(_artworkFailureLogState.Keys.First());
        var count = _artworkFailureLogState.TryGetValue(category, out state) ? state.Count + 1 : 1;
        _artworkFailureLogState[category] = (now, 0);
        LaunchDiagnostics.Write($"event=artwork.failure reason={category} count={count}");
    }

    private void MarkEpgMapLoaded(string accountId)
    {
        lock (_epgMapLoadedAccounts)
        {
            if (!_epgMapLoadedAccounts.Contains(accountId) &&
                _epgMapLoadedAccounts.Count >= EpgLoadedAccountCapacity)
                _epgMapLoadedAccounts.Remove(_epgMapLoadedAccounts.First());
            _epgMapLoadedAccounts.Add(accountId);
        }
    }

    private async Task ExpireArtworkAsync(Image image, ArtworkLoad load)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), load.Cancellation.Token);
            if (_artworkLoads.TryGetValue(image, out var current) && ReferenceEquals(current, load))
            {
                // Retire stale per-element state without clearing the decoded image.
                _artworkLoads.Remove(image);
                ReleaseArtworkSlot(image, load, "load-expired");
                load.Cancellation.Dispose();
            }
        }
        catch (OperationCanceledException) { }
    }

    private void StopArtwork(Image image)
    {
        _appliedArtwork.Remove(image);
        if (_artworkLoads.Remove(image, out var load))
        {
            load.Cancellation.Cancel();
            ReleaseArtworkSlot(image, load, "element-stopped");
        }
        image.Source = null;
        image.Opacity = 0;
    }

    private void ReleaseArtworkSlot(Image image)
    {
        if (_artworkLoads.TryGetValue(image, out var load))
        {
            ReleaseArtworkSlot(image, load, "explicit-slot-release");
            load.Cancellation.Cancel();
        }
    }

    private void ReleaseArtworkSlot(Image image, ArtworkLoad load, string reason)
    {
        if (load.SlotAcquired && !load.SlotReleased)
        {
            load.SlotReleased = true;
            _artworkSlots.Release();
        }
        if (load.SpotlightSlotAcquired && !load.SpotlightSlotReleased)
        {
            load.SpotlightSlotReleased = true;
            _spotlightArtworkSlots.Release();
        }
    }

    private void CancelArtworkLoads()
    {
        foreach (var image in _artworkLoads.Keys.ToArray()) StopArtwork(image);
    }

    private void RenderShelfSurface(CatalogSnapshot snapshot, bool deferShelfStructure = false)
    {
        var openShelf = _openShelfId is null ? null : snapshot.Shelves.FirstOrDefault(shelf => shelf.Id == _openShelfId);
        var isSearch = snapshot.IsFlatSearch;
        var isOpen = openShelf is not null;
        SearchResultsPanel.Visibility = isSearch ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfHeader.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfGrid.Visibility = isOpen || isSearch ? Visibility.Visible : Visibility.Collapsed;
        ShelvesItems.Visibility = isOpen || isSearch ? Visibility.Collapsed : Visibility.Visible;
        SpotlightPanel.Visibility = isOpen || isSearch || snapshot.Mode == CatalogMode.LiveTv ? Visibility.Collapsed : Visibility.Visible;
        if (SpotlightPanel.Visibility != Visibility.Visible) CancelSpotlightAsyncWork();
        else UpdateSpotlightArtworkWindow();
        PagerPanel.Visibility = isOpen || isSearch ? Visibility.Visible : Visibility.Collapsed;
        PageSearchPanel.Visibility = Visibility.Collapsed;

        if (isSearch)
        {
            SynchronizeRenderedShelves(Array.Empty<CatalogShelf>());
            var type = TypeForMode(snapshot.Mode);
            OpenShelfGrid.ItemsSource = snapshot.Page.Items.Select(channel =>
            {
                var categoryName = channel.GroupId is { } groupId
                    ? snapshot.Groups.FirstOrDefault(group => group.Id == groupId)?.DisplayName
                    : null;
                return ToCard(channel, type, QualityFromText(categoryName ?? string.Empty), categoryName);
            }).ToArray();
        }
        else if (isOpen)
        {
            SynchronizeRenderedShelves(snapshot.Shelves);
            OpenShelfTitle.Text = openShelf!.Title;
            var type = openShelf!.Type;
            OpenShelfCount.Text = CountLabel(snapshot.Page.TotalCount, snapshot.Mode == CatalogMode.MyTvivo ? "items" : NounFor(type));
            if (!ReferenceEquals(_renderedSnapshot, snapshot) || _renderedOpenShelfId != _openShelfId)
                OpenShelfGrid.ItemsSource = snapshot.Page.Items.Select(channel => ToCard(channel,
                    snapshot.Mode == CatalogMode.MyTvivo ? TypeForSource(channel.Source.Kind) : type,
                    QualityFromText(openShelf!.Title),
                    isContinueWatching: openShelf.Id is "__continue" or "__my_continue_movie" or "__my_continue_series")).ToArray();
        }
        else
        {
            OpenShelfGrid.ItemsSource = null;
            SynchronizeRenderedShelves(snapshot.Shelves, deferShelfStructure);
        }
    }

    private void ShelvesItems_Loaded(object sender, RoutedEventArgs args)
    {
        ShelvesItems.LayoutUpdated -= ShelvesItems_LayoutUpdated;
        ShelvesItems.LayoutUpdated += ShelvesItems_LayoutUpdated;
        AttachShelfScrollViewer();
        _shelfViewportTimer ??= ShelvesItems.DispatcherQueue.CreateTimer();
        _shelfViewportTimer.Interval = TimeSpan.FromMilliseconds(100);
        _shelfViewportTimer.IsRepeating = false;
        _shelfViewportTimer.Tick -= ShelfViewportTimer_Tick;
        _shelfViewportTimer.Tick += ShelfViewportTimer_Tick;
        _shelfViewportPollTimer ??= ShelvesItems.DispatcherQueue.CreateTimer();
        _shelfViewportPollTimer.Interval = TimeSpan.FromSeconds(1);
        _shelfViewportPollTimer.IsRepeating = true;
        _shelfViewportPollTimer.Tick -= ShelfViewportPollTimer_Tick;
        _shelfViewportPollTimer.Tick += ShelfViewportPollTimer_Tick;
        _shelfDeferredCommitTimer ??= ShelvesItems.DispatcherQueue.CreateTimer();
        _shelfDeferredCommitTimer.Interval = TimeSpan.FromMilliseconds(2100);
        _shelfDeferredCommitTimer.IsRepeating = false;
        _shelfDeferredCommitTimer.Tick -= ShelfDeferredCommitTimer_Tick;
        _shelfDeferredCommitTimer.Tick += ShelfDeferredCommitTimer_Tick;
        if (_deferredShelfStructure is not null)
            _shelfDeferredCommitTimer.Start();
        ScheduleShelfViewportUpdate("apply");
    }

    private void ShelvesItems_Unloaded(object sender, RoutedEventArgs args)
    {
        CommitDeferredShelfStructure();
        ShelvesItems.LayoutUpdated -= ShelvesItems_LayoutUpdated;
        if (_shelfScrollViewer is { } viewer)
            viewer.ViewChanged -= ShelvesScrollViewer_ViewChanged;
        _shelfScrollViewer = null;
        _shelfViewportPollTimer?.Stop();
        _shelfDeferredCommitTimer?.Stop();
        CancelShelfPreviewLoads();
    }

    private void ShelvesItems_LayoutUpdated(object? sender, object args) => AttachShelfScrollViewer();

    private void AttachShelfScrollViewer()
    {
        var viewer = FindShelfScrollViewer(ShelvesItems);
        if (ReferenceEquals(viewer, _shelfScrollViewer)) return;
        if (_shelfScrollViewer is { } previous)
            previous.ViewChanged -= ShelvesScrollViewer_ViewChanged;
        _shelfScrollViewer = viewer;
        if (viewer is not null)
            viewer.ViewChanged += ShelvesScrollViewer_ViewChanged;
    }

    private void ShelvesScrollViewer_ViewChanged(object? sender, ScrollViewerViewChangedEventArgs args) =>
        RecordShelfScrollAndSchedule();

    private void RecordShelfScrollAndSchedule()
    {
        Interlocked.Exchange(ref _lastShelfScrollAt, System.Diagnostics.Stopwatch.GetTimestamp());
        if (_deferredShelfStructure is not null)
        {
            _shelfDeferredCommitTimer?.Stop();
            _shelfDeferredCommitTimer?.Start();
        }
        ScheduleShelfViewportUpdate("scroll");
    }

    private void ShelfDeferredCommitTimer_Tick(DispatcherQueueTimer sender, object args)
    {
        TryCommitDeferredShelfStructureAfterIdle();
        if (_deferredShelfStructure is null || _lastShelfScrollAt == 0) return;
        var idle = System.Diagnostics.Stopwatch.GetElapsedTime(_lastShelfScrollAt);
        if (idle < TimeSpan.FromSeconds(2))
        {
            sender.Interval = TimeSpan.FromMilliseconds(Math.Max(100, (TimeSpan.FromSeconds(2) - idle).TotalMilliseconds + 100));
            sender.Start();
        }
    }

    private void ShelfContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (!args.InRecycleQueue && args.ItemIndex >= 0 &&
            args.Item is ShelfRowViewModel shelf && shelf.OpensPagedGrid && shelf.Cards.Count == 0)
            ScheduleShelfViewportUpdate("container");
    }

    private void ScheduleShelfViewportUpdate(string reason)
    {
        if (_shelfViewportTimer is null || !ShelvesItems.IsLoaded) return;
        _shelfViewportReason = reason;
        _shelfViewportTimer.Stop();
        _shelfViewportTimer.Start();
    }

    private void ShelfViewportTimer_Tick(DispatcherQueueTimer sender, object args)
    {
        var generation = Volatile.Read(ref _shelfPreviewLoadGeneration);
        var reason = _shelfViewportReason;
        TryCommitDeferredShelfStructureAfterIdle();
        ShelvesItems.DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low,
            () => UpdateShelfPreviewWindow(reason, generation));
    }

    private void ShelfViewportPollTimer_Tick(DispatcherQueueTimer sender, object args)
    {
        TryCommitDeferredShelfStructureAfterIdle();
        UpdateShelfPreviewWindow("poll", Volatile.Read(ref _shelfPreviewLoadGeneration));
    }

    private void TryCommitDeferredShelfStructureAfterIdle()
    {
        if (_deferredShelfStructure is not { } target || _shelfScrollViewer is not { } viewer ||
            _lastShelfScrollAt == 0 ||
            System.Diagnostics.Stopwatch.GetElapsedTime(_lastShelfScrollAt) <= TimeSpan.FromSeconds(2))
            return;

        var targetPositions = target.Select((shelf, index) => (shelf.Id, index))
            .ToDictionary(pair => pair.Id, pair => pair.index, StringComparer.Ordinal);
        var currentPositions = _renderedShelves.Select((row, index) => (row.Id, index))
            .ToDictionary(pair => pair.Id, pair => pair.index, StringComparer.Ordinal);
        var affected = Enumerable.Range(0, _renderedShelves.Count)
            .Where(index => !targetPositions.TryGetValue(_renderedShelves[index].Id, out var targetIndex) || targetIndex != index)
            .ToHashSet();
        foreach (var pair in targetPositions)
            if (!currentPositions.ContainsKey(pair.Key) && _renderedShelves.Count > 0)
                affected.Add(Math.Clamp(pair.Value, 0, _renderedShelves.Count - 1));
        var viewport = new Windows.Foundation.Rect(0, 0, viewer.ViewportWidth, viewer.ViewportHeight);
        foreach (var index in affected.OrderBy(index => index))
        {
            if (ShelvesItems.ContainerFromIndex(index) is not FrameworkElement container) continue;
            try
            {
                var bounds = container.TransformToVisual(viewer).TransformBounds(
                    new Windows.Foundation.Rect(0, 0, container.ActualWidth, container.ActualHeight));
                if (bounds.Bottom > viewport.Top && bounds.Top < viewport.Bottom) return;
            }
            catch (InvalidOperationException) { return; }
        }

        CommitDeferredShelfStructure();
    }

    private void CommitDeferredShelfStructure()
    {
        if (_deferredShelfStructure is not { } target) return;
        _deferredShelfStructure = null;
        SynchronizeRenderedShelves(target);
        Interlocked.Increment(ref _shelfRowDeferredCommits);
    }

    private void CancelShelfPreviewLoads()
    {
        Interlocked.Increment(ref _shelfPreviewLoadGeneration);
        _shelfViewportTimer?.Stop();
        _shelfViewportPollTimer?.Stop();
    }

    private void UpdateShelfPreviewWindow(string reason, long generation)
    {
        if (generation != Volatile.Read(ref _shelfPreviewLoadGeneration) ||
            _renderedSnapshot is not { IsFlatSearch: false } snapshot || snapshot.Mode == CatalogMode.MyTvivo ||
            _openShelfId is not null || !_isActive || _account is not { } account ||
            !TryGetVisibleShelfRange(snapshot, out var firstVisible, out var lastVisible))
        {
            _shelfViewportPollTimer?.Stop();
            return;
        }

        var loaded = snapshot.Shelves.Select((shelf, index) => (shelf, index))
            .Where(pair => pair.shelf.OpensPagedGrid && pair.shelf.Cards.Count > 0)
            .Select(pair => pair.index)
            .ToArray();
        var windowSize = Math.Min(snapshot.Shelves.Count - 1, lastVisible + PrefetchShelves) -
                         Math.Max(0, firstVisible - PrefetchShelves) + 1;
        var plan = ShelfWindowPlanner.Plan(snapshot.Shelves.Count, firstVisible, lastVisible,
            PrefetchShelves, windowSize + RetainedShelfMargin, loaded);

        var visibleSkeletons = CountVisibleSkeletonShelves(snapshot, firstVisible, lastVisible);

        if (plan.ToUnload.Count > 0)
        {
            var removeIds = plan.ToUnload
                .Where(index => !IsVisibleRealizedShelf(index))
                .Select(index => snapshot.Shelves[index].Id)
                .ToHashSet(StringComparer.Ordinal);
            if (removeIds.Count > 0)
                ReplaceShelfPreviewCards(account, snapshot.Mode,
                    removeIds.ToDictionary(id => id, _ => (IReadOnlyList<CatalogCard>?)null, StringComparer.Ordinal));
            snapshot = _renderedSnapshot ?? snapshot;
        }

        var loadShelves = plan.ToLoad.Select(index => snapshot.Shelves[index])
            .Where(shelf => shelf.OpensPagedGrid && shelf.Cards.Count == 0 &&
                !_shelfPreviewLoadsInFlight.Contains((snapshot.Mode, shelf.Id)))
            .ToArray();
        if (loadShelves.Length > 0)
        {
            foreach (var shelf in loadShelves)
                _shelfPreviewLoadsInFlight.Add((snapshot.Mode, shelf.Id));
            _ = LoadShelfPreviewsAsync(account, snapshot.Mode, loadShelves, generation);
        }

        LogShelfWindow(firstVisible, lastVisible, loadShelves.Length, loaded.Count(index => index >= plan.FirstIndex && index <= plan.LastIndex), visibleSkeletons, reason);
        if (HasRealizedSkeletonShelf(snapshot))
        {
            if (_isActive && !_shelfViewportPollTimer!.IsRunning)
                _shelfViewportPollTimer.Start();
        }
        else
        {
            _shelfViewportPollTimer?.Stop();
        }
    }

    private bool TryGetVisibleShelfRange(CatalogSnapshot snapshot, out int first, out int last)
    {
        first = int.MaxValue;
        last = -1;
        AttachShelfScrollViewer();
        if (_shelfScrollViewer is not { } viewer || viewer.ViewportHeight <= 0) return false;

        if (ShelvesItems.ItemsPanelRoot is { } panel && TryGetPanelVisibleRange(panel, snapshot.Shelves.Count, out first, out last))
            return true;

        var viewport = new Windows.Foundation.Rect(0, 0, viewer.ViewportWidth, viewer.ViewportHeight);
        for (var index = 0; index < ShelvesItems.Items.Count; index++)
        {
            if (ShelvesItems.ContainerFromIndex(index) is not FrameworkElement container ||
                container.Visibility != Visibility.Visible)
                continue;
            try
            {
                var bounds = container.TransformToVisual(viewer).TransformBounds(
                    new Windows.Foundation.Rect(0, 0, container.ActualWidth, container.ActualHeight));
                if (bounds.Bottom <= viewport.Top || bounds.Top >= viewport.Bottom) continue;
                first = Math.Min(first, index);
                last = Math.Max(last, index);
            }
            catch (InvalidOperationException) { }
        }
        if (last >= 0) return true;

        // A fast jump can run before the virtualizing panel has realized its destination containers.
        var headerHeight = SpotlightPanel.Visibility == Visibility.Visible ? SpotlightPanel.ActualHeight + 30 : 0;
        var shelfHeight = _activeMode == CatalogMode.LiveTv ? 230d : 340d;
        var estimatedIndex = Math.Clamp((int)(Math.Max(0, viewer.VerticalOffset - headerHeight) / shelfHeight),
            0, Math.Max(0, ShelvesItems.Items.Count - 1));
        first = estimatedIndex;
        last = estimatedIndex;
        return ShelvesItems.Items.Count > 0;
    }

    private bool IsVisibleRealizedShelf(int index)
    {
        if (ShelvesItems.ContainerFromIndex(index) is not FrameworkElement { Visibility: Visibility.Visible } container)
            return false;
        if (_shelfScrollViewer is not { } viewer) return true;
        try
        {
            var bounds = container.TransformToVisual(viewer).TransformBounds(
                new Windows.Foundation.Rect(0, 0, container.ActualWidth, container.ActualHeight));
            return bounds.Bottom > 0 && bounds.Top < viewer.ViewportHeight;
        }
        catch (InvalidOperationException)
        {
            return true;
        }
    }

    private static bool TryGetPanelVisibleRange(Panel panel, int itemCount, out int first, out int last)
    {
        first = -1;
        last = -1;
        var type = panel.GetType();
        var firstProperty = type.GetProperty("FirstVisibleIndex");
        var lastProperty = type.GetProperty("LastVisibleIndex");
        if (firstProperty?.GetValue(panel) is not int panelFirst || lastProperty?.GetValue(panel) is not int panelLast ||
            panelFirst < 0 || panelLast < panelFirst)
            return false;
        first = Math.Clamp(panelFirst, 0, Math.Max(0, itemCount - 1));
        last = Math.Clamp(panelLast, first, Math.Max(first, itemCount - 1));
        return itemCount > 0;
    }

    private int CountVisibleSkeletonShelves(CatalogSnapshot snapshot, int first, int last)
    {
        var count = 0;
        for (var index = first; index <= last && index < snapshot.Shelves.Count; index++)
            if (snapshot.Shelves[index].OpensPagedGrid && snapshot.Shelves[index].Cards.Count == 0 &&
                ShelvesItems.ContainerFromIndex(index) is FrameworkElement { Visibility: Visibility.Visible })
                count++;
        return count;
    }

    private bool HasRealizedSkeletonShelf(CatalogSnapshot snapshot)
    {
        for (var index = 0; index < ShelvesItems.Items.Count && index < snapshot.Shelves.Count; index++)
            if (snapshot.Shelves[index].OpensPagedGrid && snapshot.Shelves[index].Cards.Count == 0 &&
                ShelvesItems.ContainerFromIndex(index) is FrameworkElement { Visibility: Visibility.Visible })
                return true;
        return false;
    }

    private void LogShelfWindow(int first, int last, int toLoad, int loaded, int skeleton, string reason)
    {
        var now = System.Diagnostics.Stopwatch.GetTimestamp();
        if (_lastShelfWindowLogAt != 0 && System.Diagnostics.Stopwatch.GetElapsedTime(_lastShelfWindowLogAt, now) < TimeSpan.FromMilliseconds(200))
            return;
        _lastShelfWindowLogAt = now;
        LaunchDiagnostics.Write($"event=perf.shelfwindow first={first} last={last} toLoad={toLoad} loaded={loaded} skeleton={skeleton} reason={reason}");
    }

    private async Task LoadShelfPreviewsAsync(ProviderAccount account, CatalogMode mode,
        IReadOnlyList<CatalogShelf> shelves, long generation)
    {
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        var type = TypeForMode(mode);
        var categoryIds = shelves.Select(shelf => shelf.Id).ToArray();
        var unfilteredCategoryIds = shelves.Where(shelf => shelf.IgnoreItemFilterForPreview)
            .Select(shelf => shelf.Id).ToHashSet(StringComparer.Ordinal);
        var filter = SearchBox.Text;
        var providerConnection = _connection;
        var itemSort = ToItemSortOrder(_categorySort);
        IReadOnlyDictionary<string, CatalogPage> pages;
        try
        {
            pages = await Task.Run(() => _repository.GetChannelsGroupedByCategory(account, providerConnection, type,
                filter, ShelfPreviewSize, categoryIds, unfilteredCategoryIds, itemSort));
        }
        catch (Exception exception)
        {
            Interlocked.Add(ref _shelfPreviewLoadDurationTicks, System.Diagnostics.Stopwatch.GetTimestamp() - started);
            LaunchDiagnostics.Write($"event=catalog.shelf-preview.failed mode={mode} error={exception.GetType().Name}");
            foreach (var shelf in shelves)
                _shelfPreviewLoadsInFlight.Remove((mode, shelf.Id));
            ScheduleShelfViewportUpdate("poll");
            return;
        }
        Interlocked.Add(ref _shelfPreviewLoadDurationTicks, System.Diagnostics.Stopwatch.GetTimestamp() - started);

        try
        {
            if (generation != Volatile.Read(ref _shelfPreviewLoadGeneration) || _account?.AccountId != account.AccountId ||
                _activeMode != mode || _openShelfId is not null || !_isActive ||
                _renderedSnapshot is not { IsFlatSearch: false } current ||
                !TryGetVisibleShelfRange(current, out var firstVisible, out var lastVisible))
                return;

            var currentPlan = ShelfWindowPlanner.Plan(current.Shelves.Count, firstVisible, lastVisible,
                PrefetchShelves, int.MaxValue,
                current.Shelves.Select((shelf, index) => (shelf, index))
                    .Where(pair => pair.shelf.OpensPagedGrid && pair.shelf.Cards.Count > 0)
                    .Select(pair => pair.index));
            var currentWindowIds = current.Shelves
                .Skip(currentPlan.FirstIndex)
                .Take(currentPlan.LastIndex - currentPlan.FirstIndex + 1)
                .Where(shelf => shelf.OpensPagedGrid && shelf.Cards.Count == 0)
                .Select(shelf => shelf.Id)
                .ToHashSet(StringComparer.Ordinal);
            var cards = new Dictionary<string, IReadOnlyList<CatalogCard>>(StringComparer.Ordinal);
            foreach (var shelf in shelves)
                if (currentWindowIds.Contains(shelf.Id) && pages.TryGetValue(shelf.Id, out var page) &&
                    current.Shelves.FirstOrDefault(candidate => candidate.Id == shelf.Id)?.Cards.Count == 0)
                    cards[shelf.Id] = page.Items.Select(channel => ToCard(channel, type, QualityFromText(shelf.Title))).ToArray();
            if (cards.Count > 0)
                ReplaceShelfPreviewCards(account, mode,
                    cards.ToDictionary(pair => pair.Key, pair => (IReadOnlyList<CatalogCard>?)pair.Value, StringComparer.Ordinal));
        }
        finally
        {
            foreach (var shelf in shelves)
                _shelfPreviewLoadsInFlight.Remove((mode, shelf.Id));
            ScheduleShelfViewportUpdate("poll");
        }
    }

    private void ReplaceShelfPreviewCards(ProviderAccount account, CatalogMode mode,
        IReadOnlyDictionary<string, IReadOnlyList<CatalogCard>?> replacements)
    {
        if (_renderedSnapshot is not { } current || current.Mode != mode || _account?.AccountId != account.AccountId) return;
        IReadOnlyList<CatalogShelf> Replace(IReadOnlyList<CatalogShelf> shelves) => shelves
            .Select(shelf => replacements.TryGetValue(shelf.Id, out var cards) ? shelf with { Cards = cards ?? Array.Empty<CatalogCard>() } : shelf)
            .ToArray();

        var shelves = Replace(current.Shelves);
        var spotlight = ReferenceEquals(current.Shelves, current.SpotlightShelves) ? null : Replace(current.SpotlightShelves);
        var updated = current with { Shelves = shelves, SpotlightSource = spotlight };
        SynchronizeRenderedShelves(updated.Shelves, preserveDeferredStructure: _deferredShelfStructure is not null);
        updated = updated with { Shelves = ProjectRenderedShelves() };
        _renderedSnapshot = updated;
        if (_openShelfId is null && !updated.IsFlatSearch)
            RenderSpotlight(updated.SpotlightShelves);
        CacheSnapshot(account, updated);
    }

    private void SynchronizeRenderedShelves(IReadOnlyList<CatalogShelf> shelves, bool deferStructuralChanges = false,
        bool preserveDeferredStructure = false)
    {
        using var perfScope = PerfScope.Begin("SynchronizeRenderedShelves");
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        try
        {
            if (!ReferenceEquals(ShelvesItems.ItemsSource, _renderedShelves))
                ShelvesItems.ItemsSource = _renderedShelves;
            var incoming = DeduplicateShelves(shelves);
            var current = _renderedShelves.Select(row => row.ToCatalogShelf()).ToArray();
            var reconciliation = ShelfReconciler.Reconcile(current, incoming,
                shelf => shelf.Id,
                (_, refreshed) => refreshed,
                deferStructuralChanges);
            if (reconciliation.StructuralChangesDeferred)
            {
                if (!preserveDeferredStructure) _deferredShelfStructure = incoming;
                if (_lastShelfScrollAt == 0)
                    Interlocked.Exchange(ref _lastShelfScrollAt, System.Diagnostics.Stopwatch.GetTimestamp());
                _shelfDeferredCommitTimer?.Stop();
                _shelfDeferredCommitTimer?.Start();
                LaunchDiagnostics.Write($"event=catalog.shelves.reconcile deferred=true added={reconciliation.AddedCount} removed={reconciliation.RemovedCount} reordered={reconciliation.ReorderedCount} visibleShelf={FirstVisibleShelfPosition()?.Id ?? "none"}");
            }
            else if (!preserveDeferredStructure)
            {
                var hadDeferredStructure = _deferredShelfStructure is not null;
                _deferredShelfStructure = null;
                _shelfDeferredCommitTimer?.Stop();
                if (hadDeferredStructure) Interlocked.Increment(ref _shelfRowDeferredCommits);
            }

            var target = reconciliation.Shelves;
            var targetIds = target.Select(shelf => shelf.Id).ToHashSet(StringComparer.Ordinal);
            for (var index = _renderedShelves.Count - 1; index >= 0; index--)
                if (!targetIds.Contains(_renderedShelves[index].Id))
                {
                    _renderedShelves.RemoveAt(index);
                    Interlocked.Increment(ref _shelfRowRemovals);
                }

            for (var index = 0; index < target.Count; index++)
            {
                if (index < _renderedShelves.Count)
                {
                    if (_renderedShelves[index].Id == target[index].Id)
                    {
                        _renderedShelves[index].Update(target[index]);
                        continue;
                    }
                    var existingIndex = -1;
                    for (var candidate = index + 1; candidate < _renderedShelves.Count; candidate++)
                        if (_renderedShelves[candidate].Id == target[index].Id) { existingIndex = candidate; break; }
                    if (existingIndex >= 0)
                    {
                        _renderedShelves.Move(existingIndex, index);
                        Interlocked.Increment(ref _shelfRowMoves);
                        _renderedShelves[index].Update(target[index]);
                    }
                    else
                    {
                        _renderedShelves.Insert(index, new ShelfRowViewModel(target[index]));
                        Interlocked.Increment(ref _shelfRowInserts);
                    }
                    continue;
                }
                _renderedShelves.Add(new ShelfRowViewModel(target[index]));
                Interlocked.Increment(ref _shelfRowInserts);
            }

            if (_renderedSnapshot is { } snapshot && _openShelfId is null && !snapshot.IsFlatSearch)
                _renderedSnapshot = snapshot with { Shelves = ProjectRenderedShelves() };
        }
        finally
        {
            Interlocked.Exchange(ref _synchronizeShelvesDurationTicks,
                System.Diagnostics.Stopwatch.GetTimestamp() - started);
            Interlocked.Increment(ref _synchronizeShelvesCount);
        }
    }

    private IReadOnlyList<CatalogShelf> ProjectRenderedShelves() =>
        _renderedShelves.Select(row => row.ToCatalogShelf()).ToArray();

    private void ClearRenderedShelves()
    {
        _deferredShelfStructure = null;
        _shelfDeferredCommitTimer?.Stop();
        if (_renderedShelves.Count > 0)
            Interlocked.Add(ref _shelfRowRemovals, _renderedShelves.Count);
        _renderedShelves.Clear();
    }

    private async void ModeButton_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: string tag } || !Enum.TryParse<CatalogMode>(tag, out var mode)) return;
        await SetModeAsync(mode);
    }

    private async void ShelfTitle_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: ShelfRowViewModel shelf }) return;
        await OpenShelfAsync(shelf.ToCatalogShelf());
    }

    private async void ShelfCount_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: ShelfRowViewModel shelf }) return;
        await OpenShelfAsync(shelf.ToCatalogShelf());
    }

    private async Task OpenShelfAsync(CatalogShelf shelf)
    {
        CommitDeferredShelfStructure();
        shelf = _renderedShelves.FirstOrDefault(row => row.Id == shelf.Id)?.ToCatalogShelf() ?? shelf;
        CancelShelfPreviewLoads();
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        LaunchDiagnostics.Write($"event=catalog.browse.open mode={_activeMode} type={shelf.Type} scope={(shelf.GroupId is null ? "shelf" : "category")}");
        if (_account is { } account)
        {
            RecordShelfVisit(account, shelf.Type, shelf.Id);
            foreach (var key in _snapshotCache.Keys.Where(key => key.AccountId == account.AccountId &&
                         key.Mode == _activeMode && key.OpenShelfId.Length == 0).ToArray())
                RemoveCachedSnapshot(key);
            _snapshotCacheOrder.Clear();
            foreach (var key in _snapshotCache.Keys) _snapshotCacheOrder.Enqueue(key);
        }
        _openShelfId = shelf.Id;
        _openShelfType = shelf.Type;
        _selectedGroupId = shelf.GroupId;
        _offset = 0;
        await ShowCachedPageAsync();
    }

    private async void BackToShelves_Click(object sender, RoutedEventArgs args)
    {
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        _openShelfId = null;
        _openShelfType = null;
        _selectedGroupId = null;
        _offset = 0;
        await ShowCachedPageAsync();
    }

    private void ShelfCards_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (sender is GridView gridView && ReferenceEquals(_draggedShelfGrid, gridView))
        {
            _draggedShelfGrid = null;
            return;
        }

        if (args.ClickedItem is CatalogCard { Channel: { } channel })
        {
            var related = sender is GridView relatedGrid
                ? relatedGrid.Items.OfType<CatalogCard>().Where(card => card.Channel is not null).Select(card => card.Channel!).ToArray()
                    ?? Array.Empty<Channel>()
                : Array.Empty<Channel>();
            RaiseChannelSelected(channel, related);
        }
    }

    private void ShelfGrid_Loaded(object sender, RoutedEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ShelfGridLoaded");
        if (sender is not GridView gridView || !_wiredShelfGrids.Add(gridView))
            return;

        gridView.ContainerContentChanging += CatalogCardContainerContentChanging;
        gridView.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler(ShelfGrid_PointerPressed), true);
        gridView.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler(ShelfGrid_PointerMoved), true);
        gridView.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(ShelfGrid_PointerReleased), true);
        gridView.AddHandler(UIElement.PointerCanceledEvent, new PointerEventHandler(ShelfGrid_PointerCanceled), true);
        gridView.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(ShelfGrid_PointerWheelChanged), true);
    }

    private void ShelfGrid_Unloaded(object sender, RoutedEventArgs args)
    {
        if (sender is not GridView gridView || !_wiredShelfGrids.Remove(gridView))
            return;

        gridView.ContainerContentChanging -= CatalogCardContainerContentChanging;
        ForgetCatalogCardContainers(gridView);

        gridView.RemoveHandler(UIElement.PointerPressedEvent, new PointerEventHandler(ShelfGrid_PointerPressed));
        gridView.RemoveHandler(UIElement.PointerMovedEvent, new PointerEventHandler(ShelfGrid_PointerMoved));
        gridView.RemoveHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(ShelfGrid_PointerReleased));
        gridView.RemoveHandler(UIElement.PointerCanceledEvent, new PointerEventHandler(ShelfGrid_PointerCanceled));
        gridView.RemoveHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(ShelfGrid_PointerWheelChanged));
        if (ReferenceEquals(_pressedShelfGrid, gridView))
            ResetShelfPointerState();
        if (ReferenceEquals(_draggedShelfGrid, gridView))
            _draggedShelfGrid = null;
    }

    private void CatalogCardContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        using var perfScope = PerfScope.Begin("ContainerContentChanging");
        if (args.InRecycleQueue)
        {
            _liveCatalogCardContainers.Remove(args.ItemContainer);
            _catalogCardContainerOwners.Remove(args.ItemContainer);
        }
        else
        {
            _liveCatalogCardContainers.Add(args.ItemContainer);
            if (sender is GridView gridView) _catalogCardContainerOwners[args.ItemContainer] = gridView;
        }
    }

    private void OpenShelfGrid_Unloaded(object sender, RoutedEventArgs args) =>
        ForgetCatalogCardContainers(OpenShelfGrid);

    private void ForgetCatalogCardContainers(GridView gridView)
    {
        foreach (var container in _catalogCardContainerOwners
                     .Where(pair => ReferenceEquals(pair.Value, gridView))
                     .Select(pair => pair.Key)
                     .ToArray())
        {
            _catalogCardContainerOwners.Remove(container);
            _liveCatalogCardContainers.Remove(container);
        }
    }

    private void ShelfGrid_PointerPressed(object sender, PointerRoutedEventArgs args)
    {
        if (sender is not GridView gridView || !args.GetCurrentPoint(gridView).Properties.IsLeftButtonPressed)
            return;

        var scrollViewer = FindShelfScrollViewer(gridView);
        if (scrollViewer is null)
            return;

        _pressedShelfGrid = gridView;
        _draggedShelfGrid = null;
        _pressedShelfScrollViewer = scrollViewer;
        _shelfPointerId = args.Pointer.PointerId;
        _shelfPressX = args.GetCurrentPoint(gridView).Position.X;
        _shelfPressOffset = scrollViewer.HorizontalOffset;
        _shelfDragStarted = false;
    }

    private void ShelfGrid_PointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (sender is not GridView gridView || !ReferenceEquals(_pressedShelfGrid, gridView) ||
            args.Pointer.PointerId != _shelfPointerId || _pressedShelfScrollViewer is null ||
            !args.GetCurrentPoint(gridView).Properties.IsLeftButtonPressed)
            return;

        var deltaX = args.GetCurrentPoint(gridView).Position.X - _shelfPressX;
        if (!_shelfDragStarted && Math.Abs(deltaX) < 6)
            return;

        if (!_shelfDragStarted)
        {
            _shelfDragStarted = true;
            _draggedShelfGrid = gridView;
        }

        var maxOffset = Math.Max(0, _pressedShelfScrollViewer.ExtentWidth - _pressedShelfScrollViewer.ViewportWidth);
        var offset = Math.Clamp(_shelfPressOffset - deltaX, 0, maxOffset);
        _pressedShelfScrollViewer.ChangeView(offset, null, null, true);
        args.Handled = true;
    }

    private void ShelfGrid_PointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (sender is not GridView gridView || !ReferenceEquals(_pressedShelfGrid, gridView) ||
            args.Pointer.PointerId != _shelfPointerId)
            return;

        if (_shelfDragStarted)
        {
            _draggedShelfGrid = gridView;
            args.Handled = true;
        }

        ResetShelfPointerState();
        if (ReferenceEquals(_draggedShelfGrid, gridView))
        {
            gridView.DispatcherQueue?.TryEnqueue(() =>
            {
                using var perfScope = PerfScope.Begin("ShelfPointerCleanup");
                if (ReferenceEquals(_draggedShelfGrid, gridView))
                    _draggedShelfGrid = null;
            });
        }
    }

    private void ShelfGrid_PointerCanceled(object sender, PointerRoutedEventArgs args)
    {
        if (sender is GridView gridView && ReferenceEquals(_draggedShelfGrid, gridView))
            _draggedShelfGrid = null;
        ResetShelfPointerState(sender as GridView);
    }

    private static void ShelfGrid_PointerWheelChanged(object sender, PointerRoutedEventArgs args)
    {
        // The inner ScrollViewer may mark wheel input handled even with its axes disabled.
        // Restore bubbling so the containing page ScrollViewer receives vertical wheel input.
        args.Handled = false;
    }

    private void ResetShelfPointerState(GridView? gridView = null)
    {
        if (gridView is not null && !ReferenceEquals(_pressedShelfGrid, gridView))
            return;

        _pressedShelfGrid = null;
        _pressedShelfScrollViewer = null;
        _shelfPointerId = 0;
        _shelfDragStarted = false;
    }

    // Search results and an opened category both live in OpenShelfGrid and the shelf list is hidden then, so
    // pick the scroller that is on screen rather than guessing from whether a category is open.
    private ScrollViewer? ActiveVerticalScrollViewer() =>
        OpenShelfGrid.Visibility == Visibility.Visible
            ? FindShelfScrollViewer(OpenShelfGrid)
            : FindShelfScrollViewer(ShelvesItems);

    private void ScrollVerticallyTo(Func<ScrollViewer, double> target)
    {
        if (ActiveVerticalScrollViewer() is not { } viewer) return;
        var requestedOffset = Math.Clamp(target(viewer), 0, viewer.ScrollableHeight);
        LaunchDiagnostics.Write($"event=catalog.scroll-nav offset={viewer.VerticalOffset:0} target={requestedOffset:0} shelves={ShelvesItems.Items.Count} itemsSource={(ShelvesItems.ItemsSource is null ? "null" : "bound")} shelvesVisibility={ShelvesItems.Visibility} contentVisibility={ContentState.Visibility}");
        viewer.ChangeView(null, requestedOffset, null, true);
    }

    private void ScrollTop_Click(object sender, RoutedEventArgs args) => ScrollVerticallyTo(_ => 0);

    private void ScrollBottom_Click(object sender, RoutedEventArgs args) => ScrollVerticallyTo(viewer => viewer.ScrollableHeight);

    private void ScrollPageUp_Click(object sender, RoutedEventArgs args) =>
        ScrollVerticallyTo(viewer => viewer.VerticalOffset - viewer.ViewportHeight * 0.85);

    private void ScrollPageDown_Click(object sender, RoutedEventArgs args) =>
        ScrollVerticallyTo(viewer => viewer.VerticalOffset + viewer.ViewportHeight * 0.85);

    private static ScrollViewer? FindShelfScrollViewer(DependencyObject element)
    {
        if (element is ScrollViewer scrollViewer)
            return scrollViewer;

        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
        {
            var found = FindShelfScrollViewer(VisualTreeHelper.GetChild(element, index));
            if (found is not null)
                return found;
        }

        return null;
    }

    private async void SearchBox_TextChanged(object sender, TextChangedEventArgs args)
    {
        if (_suppressSearchChanged || !_isReadyForInteraction) return;
        CommitDeferredShelfStructure();
        CancelShelfPreviewLoads();
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        SearchTextChanged?.Invoke(SearchBox.Text ?? string.Empty);
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        if (string.IsNullOrWhiteSpace(SearchBox.Text) && _openShelfId is null)
            _selectedGroupId = null;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        ResetBrowseScroll();
    }

    private async void ClearSearch_Click(object sender, RoutedEventArgs args)
    {
        CancelShelfPreviewLoads();
        if (!_isReadyForInteraction) return;
        CommitDeferredShelfStructure();
        _suppressSearchChanged = true;
        try
        {
            SearchBox.Text = string.Empty;
        }
        finally
        {
            _suppressSearchChanged = false;
        }
        SearchTextChanged?.Invoke(string.Empty);
        _selectedGroupId = null;
        ClearSearchButton.Visibility = Visibility.Collapsed;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        SearchBox.Focus(FocusState.Programmatic);
    }

    private bool _searchChipsWired;

    private void SearchChipsToggle_Click(object sender, RoutedEventArgs args)
    {
        SearchCategoryChips.MaxRows = SearchCategoryChips.MaxRows == 0 ? 2 : 0;
        UpdateSearchChipsToggle();
    }

    private void UpdateSearchChipsToggle()
    {
        var expanded = SearchCategoryChips.MaxRows == 0;
        var hidden = SearchCategoryChips.HiddenCount;
        SearchChipsToggle.Visibility = expanded || hidden > 0 ? Visibility.Visible : Visibility.Collapsed;
        SearchChipsToggle.Content = expanded ? "Show fewer" : $"+{hidden} more";
    }

    private void UpdateSearchCategoryChips(CatalogSnapshot snapshot)
    {
        if (!_searchChipsWired)
        {
            _searchChipsWired = true;
            SearchCategoryChips.HiddenCountChanged += (_, _) => UpdateSearchChipsToggle();
        }
        SearchCategoryChips.Children.Clear();
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            using var perfScope = PerfScope.Begin("SearchChipsUpdateDispatch");
            UpdateSearchChipsToggle();
        });
        var categories = snapshot.SearchCategoryCounts ?? Array.Empty<SearchCategoryCount>();
        var allCount = categories.Sum(category => category.MatchCount);
        AddSearchCategoryChip(string.Empty, "All", allCount, snapshot.SelectedGroupId is null);
        foreach (var category in categories)
            AddSearchCategoryChip(category.CategoryId, category.Name, category.MatchCount,
                snapshot.SelectedGroupId == category.CategoryId);
    }

    private void AddSearchCategoryChip(string groupId, string name, int count, bool selected)
    {
        var label = $"{name} {count}";
        var button = new Button
        {
            Content = label,
            Tag = groupId,
            Style = (Style)Application.Current.Resources["AppQuietButtonStyle"],
            Padding = new Thickness(12, 7, 12, 7),
            MinHeight = 0,
        };
        ApplySearchCategoryChipState(button, selected);
        AutomationProperties.SetName(button, label);
        button.Click += SearchCategoryChip_Click;
        SearchCategoryChips.Children.Add(button);
    }

    private void ApplySearchCategoryChipState(Button button, bool selected)
    {
        button.Background = selected ? ThemeBrush("AppAccentBrush") : ThemeBrush("AppPanelSurfaceBrush");
        button.Foreground = selected ? ThemeBrush("AppOnAccentBrush") : ThemeBrush("AppTextBrush");
        button.BorderBrush = selected ? ThemeBrush("AppAccentBrush") : ThemeBrush("AppLineBrush");
    }

    private async void SearchCategoryChip_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: string groupId } || !_isReadyForInteraction) return;
        CommitDeferredShelfStructure();
        _selectedGroupId = string.IsNullOrEmpty(groupId) ? null : groupId;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    public DateTimeOffset? LastRefreshAt
    {
        get
        {
            if (_account is not { } account) return null;
            var times = AllRefreshTypes.Select(type => _repository.GetLastRefreshSuccess(account, type))
                .Where(time => time.HasValue).Select(time => time!.Value).ToArray();
            return times.Length == 0 ? null : times.Max();
        }
    }

    public Task RefreshNowAsync() => _account is { } account && _connection is null
        ? RetrySavedAuthenticationAsync(account)
        : _account is { } authenticated
            ? LoadAsync(authenticated, forceRefresh: true, reason: "manual-refresh", refreshAll: true)
            : LoadSavedAsync();

    private async Task RetrySavedAuthenticationAsync(ProviderAccount account)
    {
        CancelSavedStartupAuthentication();
        var connection = await _credentialStore.LoadAsync();
        if (connection is null || _account?.AccountId != account.AccountId) return;
        var cts = new CancellationTokenSource();
        _savedStartupAuthCts = cts;
        _cachedStartupCatalogActive = _renderedSnapshot is not null && HasCatalogData(_renderedSnapshot);
        ShowCheckingAccountStatus();
        await AuthenticateSavedAccountAndRefreshAsync(connection, account, cts);
    }

    public async void RefreshCurrentType()
    {
        if (_account is not { } account || IsRefreshingType) return;
        if (_connection is null)
        {
            await RefreshNowAsync();
            return;
        }
        IsRefreshingType = true;
        RaiseRefreshStateChanged();
        var allTypes = _activeMode == CatalogMode.MyTvivo; // For You shows every type, so refresh them all
        try { await LoadAsync(account, forceRefresh: true, reason: allTypes ? "manual-refresh" : "manual-type-refresh", refreshAll: allTypes); }
        finally
        {
            IsRefreshingType = false;
            RaiseRefreshStateChanged();
        }
    }

    public async void SetCategorySort(string? tag)
    {
        if (!_isReadyForInteraction || !Enum.TryParse<CategorySort>(tag, out var sort) || sort == _categorySort) return;
        CommitDeferredShelfStructure();
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        _categorySort = sort;
        if (_account is null) return;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        ResetBrowseScroll();
    }

    private async void PreviousPage_Click(object sender, RoutedEventArgs args)
    {
        _offset = Math.Max(0, _offset - PageSize);
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        ResetBrowseScroll();
    }

    private async void NextPage_Click(object sender, RoutedEventArgs args)
    {
        _offset += PageSize;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        ResetBrowseScroll();
    }

    private Task ShowCachedPageAsync() =>
        ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);

    private async Task ShowCachedPageAsync(CatalogMode mode, string? filter, string? groupId, int requestedOffset)
    {
        var account = _account;
        if (account is null) return;

        var requestGeneration = Interlocked.Increment(ref _pageQueryGeneration);
        var sort = _categorySort;
        try
        {
            var key = new CatalogSnapshotKey(account.AccountId, mode, _openShelfId ?? string.Empty,
                _openShelfType?.ToString() ?? string.Empty, groupId ?? string.Empty,
                filter ?? string.Empty, sort, requestedOffset);
            if (!_snapshotCache.TryGetValue(key, out var cached))
            {
                var read = await ReadCatalogSnapshotAsync(account, groupId, filter, requestedOffset, mode, sort);
                if (!IsCurrentPageRequest(requestGeneration, account)) return;
                CacheSnapshot(account, read);
                cached = new CachedCatalogSnapshot(read, EstimateSnapshotBytes(read));
            }
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            await ApplySnapshotAsync(cached.Snapshot, () => IsCurrentPageRequest(requestGeneration, account));
        }
        catch (Exception exception)
        {
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            // Saved channels stay on screen; a failed page refresh is not worth interrupting the user.
            LaunchDiagnostics.Write($"Page update failed: {DescribeForLog(exception)}");
            RefreshInfoBar.IsOpen = false;
        }
    }

    private bool IsCurrentPageRequest(long requestGeneration, ProviderAccount account) =>
        requestGeneration == Volatile.Read(ref _pageQueryGeneration) &&
        _account?.AccountId == account.AccountId;

    private void ShowCheckingAccountStatus()
    {
        RefreshInfoBar.Message = CatalogStartupStatus.Resolve(true, false, null, DateTimeOffset.UtcNow);
        RefreshInfoBar.Severity = InfoBarSeverity.Informational;
        RefreshRetryButton.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = true;
    }

    private void ShowRefreshingStatus()
    {
        RefreshInfoBar.Message = CatalogStartupStatus.Resolve(false, true, null, DateTimeOffset.UtcNow);
        RefreshInfoBar.Severity = InfoBarSeverity.Informational;
        RefreshRetryButton.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = true;
    }

    private void ShowPersistedRefreshStatus()
    {
        RefreshInfoBar.Message = UpdatedText;
        RefreshInfoBar.Severity = InfoBarSeverity.Informational;
        RefreshRetryButton.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = true;
    }

    private void ShowCachedUpdateFailure(AuthenticationResult result)
    {
        var reason = result.FailureReason switch
        {
            AuthFailureReason.AccountExpired => "The account has expired.",
            AuthFailureReason.InvalidCredentials => "The provider refused the saved account details.",
            AuthFailureReason.AccountInactive => "The provider account is inactive.",
            AuthFailureReason.NetworkFailure when result.DiagnosticCode == "http_429" => "The provider is rate limiting requests (429).",
            AuthFailureReason.NetworkFailure when result.DiagnosticCode == "http_403" => "The provider refused access (403).",
            AuthFailureReason.NetworkFailure => "The provider is offline or unreachable.",
            AuthFailureReason.ProtocolError => "The provider returned an unexpected response.",
            _ => "The provider refused the update.",
        };
        ShowCachedUpdateFailure(reason);
    }

    private void ShowCachedUpdateFailure(Exception exception)
    {
        var inner = exception;
        while (inner.InnerException is not null && inner is not HttpRequestException) inner = inner.InnerException;
        var reason = inner switch
        {
            HttpRequestException { StatusCode: { } status } when (int)status == 429 => "The provider is rate limiting requests (429).",
            HttpRequestException { StatusCode: { } status } when (int)status == 403 => "The provider refused access (403).",
            HttpRequestException => "The provider is offline or unreachable.",
            TaskCanceledException => "The provider is offline or unreachable.",
            _ => "The provider refused the update.",
        };
        ShowCachedUpdateFailure(reason);
    }

    private void ShowCachedUpdateFailure(string reason)
    {
        RefreshInfoBar.Message = $"Could not update. Showing your saved catalog. {reason}";
        RefreshInfoBar.Severity = InfoBarSeverity.Warning;
        RefreshRetryButton.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = true;
        _cachedStartupCatalogActive = false;
    }

    // Automatic refresh failures stay silent unless this is the initial cached-first update; that result is
    // surfaced once while keeping the catalog available.
    private void ShowRefreshFailure(bool userInitiated, Exception? exception = null)
    {
        if (_cachedStartupCatalogActive)
        {
            _cachedStartupCatalogActive = false;
            ShowCachedUpdateFailure(exception ?? new IOException("Provider refresh failed."));
            DismissFirstLoadTakeover();
            return;
        }
        if (!userInitiated)
        {
            RefreshInfoBar.IsOpen = false;
            DismissFirstLoadTakeover();
            return;
        }
        RefreshInfoBar.Message = "Couldn't refresh — showing saved items";
        RefreshInfoBar.Severity = InfoBarSeverity.Warning;
        RefreshRetryButton.Visibility = Visibility.Visible;
        RefreshInfoBar.IsOpen = true;
        DismissFirstLoadTakeover();
    }

    // Exception type and HTTP status only. Messages can embed provider URLs (which carry credentials).
    private static string DescribeForLog(Exception exception)
    {
        var inner = exception;
        while (inner.InnerException is not null && inner is not HttpRequestException) inner = inner.InnerException;
        return inner is HttpRequestException { StatusCode: { } status }
            ? $"{exception.GetType().Name} (HTTP {(int)status})"
            : exception.GetType().Name;
    }

    private bool IsCurrentLoad(long generation) =>
        generation == Volatile.Read(ref _loadGeneration);

    private void UpdatePaging(int total)
    {
        PageStatus.Text = _openShelfId is null && _activeMode != CatalogMode.MyTvivo && !string.IsNullOrWhiteSpace(SearchBox.Text)
            ? total == 0 ? "0 matches" : $"{_offset + 1}-{Math.Min(_offset + PageSize, total)} of {total} matches"
            : total == 0 ? "0 channels" : $"{_offset + 1}-{Math.Min(_offset + PageSize, total)} of {total}";
        PreviousPageButton.IsEnabled = _offset > 0;
        NextPageButton.IsEnabled = _offset + PageSize < total;
    }

    private void UpdateModeChrome()
    {
        SetModeButtonState(MyTvivoButton, _activeMode == CatalogMode.MyTvivo);
        SetModeButtonState(MoviesButton, _activeMode == CatalogMode.Movies);
        SetModeButtonState(SeriesButton, _activeMode == CatalogMode.Series);
        RaiseRefreshStateChanged();
    }

    private void ShelfGrid_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (sender is GridView gridView)
            MoveFocusInGrid(gridView, args, isWrappingGrid: false);
    }

    private void OpenShelfGrid_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (sender is GridView gridView)
            MoveFocusInGrid(gridView, args, isWrappingGrid: true);
    }

    private static int WrappingGridColumnCount(GridView gridView)
    {
        var wrapGrid = FindItemsWrapGrid(gridView);
        if (wrapGrid is null || wrapGrid.ActualWidth <= 0)
            return 1;

        FrameworkElement? itemContainer = null;
        for (var index = 0; index < gridView.Items.Count; index++)
        {
            if (gridView.ContainerFromIndex(index) is FrameworkElement candidate && candidate.ActualWidth > 0)
            {
                itemContainer = candidate;
                break;
            }
        }

        if (itemContainer is null)
            return 1;

        var itemMargin = itemContainer.Margin;
        var spacing = itemMargin.Left + itemMargin.Right;
        var pitch = itemContainer.ActualWidth + spacing;
        return pitch <= 0
            ? 1
            : Math.Max(1, (int)Math.Floor((wrapGrid.ActualWidth + spacing) / pitch));
    }

    private static ItemsWrapGrid? FindItemsWrapGrid(DependencyObject element)
    {
        if (element is ItemsWrapGrid wrapGrid)
            return wrapGrid;

        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(element); index++)
        {
            var found = FindItemsWrapGrid(VisualTreeHelper.GetChild(element, index));
            if (found is not null)
                return found;
        }

        return null;
    }

    private void MoveFocusInGrid(GridView gridView, KeyRoutedEventArgs args, bool isWrappingGrid)
    {
        var itemCount = gridView.Items.Count;
        if (itemCount == 0) return;

        var index = FocusedIndex(gridView);
        var target = index;

        if (!isWrappingGrid)
        {
            if (args.Key == Windows.System.VirtualKey.Right)
                target = index < 0 ? 0 : Math.Min(itemCount - 1, index + 1);
            else if (args.Key == Windows.System.VirtualKey.Left)
                target = index < 0 ? itemCount - 1 : Math.Max(0, index - 1);
            else
                return;
        }
        else
        {
            var columns = WrappingGridColumnCount(gridView);
            if (args.Key == Windows.System.VirtualKey.Right)
            {
                if (index < 0) target = 0;
                else if (index % columns != columns - 1 && index + 1 < itemCount) target = index + 1;
            }
            else if (args.Key == Windows.System.VirtualKey.Left)
            {
                if (index < 0) target = itemCount - 1;
                else if (index % columns != 0) target = index - 1;
            }
            else if (args.Key == Windows.System.VirtualKey.Down)
            {
                if (index < 0) target = 0;
                else if (index + columns < itemCount) target = index + columns;
            }
            else if (args.Key == Windows.System.VirtualKey.Up)
            {
                if (index >= columns) target = index - columns;
            }
            else
            {
                return;
            }
        }

        if (target == index || target < 0 || target >= itemCount)
            return;

        gridView.ScrollIntoView(gridView.Items[target]);
        if (gridView.ContainerFromIndex(target) is Control container)
        {
            container.Focus(FocusState.Keyboard);
            args.Handled = true;
        }
    }

    private int FocusedIndex(GridView gridView)
    {
        if (FocusManager.GetFocusedElement(XamlRoot) is not DependencyObject focused)
            return -1;

        for (var current = focused; current is not null; current = VisualTreeHelper.GetParent(current))
        {
            if (current is GridViewItem item)
                return gridView.IndexFromContainer(item);
            if (ReferenceEquals(current, gridView))
                return -1;
        }

        return -1;
    }

    private void UpdateEmptyCopy()
    {
        (EmptyTitle.Text, EmptyMessage.Text) = _activeMode switch
        {
            CatalogMode.Movies => ("No movies found", "Movie browsing is waiting for this provider catalog to be indexed."),
            CatalogMode.Series => ("No series found", "Series browsing is waiting for this provider catalog to be indexed."),
            _ => ("No catalog items found", "This provider returned no browsable catalog data yet. Check the account or try again later."),
        };
    }

    private void SetModeButtonState(Button button, bool selected)
    {
        button.Foreground = selected
            ? ThemeBrush("AppOnAccentBrush")
            : ThemeBrush("AppTextBrush");
        button.Background = selected
            ? ThemeBrush("AppAccentBrush")
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    private async void Retry_Click(object sender, RoutedEventArgs args)
    {
        if (_account is not null) await LoadAsync(_account, forceRefresh: true, reason: "manual-refresh");
        else await LoadSavedAsync();
    }

    private void RaiseChannelSelected(Channel channel, IReadOnlyList<Channel>? related = null)
    {
        CaptureBrowseState();
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(channel, related ?? Array.Empty<Channel>()));
    }

    private void ShowError(string message)
    {
        _firstCatalogLoadCompleted = true;
        LogVisibilityChange("FirstLoadTakeover", FirstLoadTakeover.Visibility, Visibility.Collapsed);
        FirstLoadTakeover.Visibility = Visibility.Collapsed;
        FirstLoadTakeover.Opacity = 1;
        RefreshInfoBar.IsOpen = false;
        ErrorMessage.Text = message;
        SetState(error: true);
    }

    private void SetState(bool loading = false, bool empty = false, bool error = false, bool content = false)
    {
        LogVisibilityChange("LoadingState", LoadingState.Visibility, loading ? Visibility.Visible : Visibility.Collapsed);
        LogVisibilityChange("ContentState", ContentState.Visibility, content ? Visibility.Visible : Visibility.Collapsed);
        LoadingState.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
        EmptyState.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        ErrorState.Visibility = error ? Visibility.Visible : Visibility.Collapsed;
        ContentState.Visibility = content ? Visibility.Visible : Visibility.Collapsed;

        // Empty results are still a completed catalog state: search can recover
        // from a filter with no matches. Errors and in-flight replacement states
        // expose Retry/global navigation while keeping catalog controls inert.
        SetInteractionReadiness(!loading && !error);
    }

    private void ShowFirstLoadTakeover()
    {
        FirstLoadTakeover.Opacity = 1;
        LogVisibilityChange("FirstLoadTakeover", FirstLoadTakeover.Visibility, Visibility.Visible);
        FirstLoadTakeover.Visibility = Visibility.Visible;
    }

    private void DismissFirstLoadTakeover()
    {
        if (FirstLoadTakeover.Visibility != Visibility.Visible)
            return;

        var fade = new DoubleAnimation
        {
            To = 0,
            Duration = TimeSpan.FromMilliseconds(260),
            EnableDependentAnimation = true,
        };
        Storyboard.SetTarget(fade, FirstLoadTakeover);
        Storyboard.SetTargetProperty(fade, "Opacity");
        var storyboard = new Storyboard();
        storyboard.Children.Add(fade);
        storyboard.Completed += (_, _) =>
        {
            LogVisibilityChange("FirstLoadTakeover", FirstLoadTakeover.Visibility, Visibility.Collapsed);
            FirstLoadTakeover.Visibility = Visibility.Collapsed;
            FirstLoadTakeover.Opacity = 1;
        };
        storyboard.Begin();
    }

    private static void LogVisibilityChange(string element, Visibility previous, Visibility next)
    {
        if (previous == next) return;
        LaunchDiagnostics.Write($"event=perf.surface timestamp={DateTimeOffset.UtcNow:O} visibility={element} value={next}");
    }

    private void SetInteractionReadiness(bool ready)
    {
        var changed = _isReadyForInteraction != ready;
        _isReadyForInteraction = ready;
        SearchBox.IsEnabled = ready;
        ClearSearchButton.IsEnabled = ready;
        ModeButtons.IsEnabled = ready;
        if (changed)
            InteractionReadinessChanged?.Invoke(this, EventArgs.Empty);
    }

    private sealed record SearchCategoryCount(string CategoryId, string Name, int MatchCount);

    private sealed record CatalogSnapshot(
        CatalogMode Mode,
        IReadOnlyList<ChannelGroup> Groups,
        string? SelectedGroupId,
        int Offset,
        CatalogPage Page,
        IReadOnlyList<CatalogShelf> Shelves,
        IReadOnlyList<CatalogShelf>? SpotlightSource = null,
        IReadOnlyList<SearchCategoryCount>? SearchCategoryCounts = null,
        bool IsFlatSearch = false,
        string? SearchFilter = null)
    {
        public IReadOnlyList<CatalogShelf> SpotlightShelves => SpotlightSource ?? Shelves;
    }

    private sealed record CatalogSnapshotKey(string AccountId, CatalogMode Mode, string OpenShelfId, string OpenShelfType, string GroupId, string Filter, CategorySort Sort, int Offset);
    private sealed record CachedCatalogSnapshot(CatalogSnapshot Snapshot, long EstimatedBytes);
    private sealed record AppliedArtwork(string ItemKey, string Url, BitmapImage Bitmap);
    private sealed class ArtworkLoad
    {
        public string Id { get; } = Guid.NewGuid().ToString("N")[..8];
        public string ItemKey { get; set; } = "unresolved";
        public string Url { get; set; } = string.Empty;
        public CancellationTokenSource Cancellation { get; } = new();
        public BitmapImage? Bitmap { get; set; }
        public string Stage { get; set; } = "queued";
        public bool SlotAcquired { get; set; }
        public bool SlotReleased { get; set; }
        public bool SpotlightSlotAcquired { get; set; }
        public bool SpotlightSlotReleased { get; set; }
    }
    private sealed record CachedArtworkBitmap(string CacheKey, BitmapImage Bitmap, long DecodedBytes);
    private sealed record ModeInteractionState(string? OpenShelfId, CatalogItemType? OpenShelfType, string? SelectedGroupId, string Filter, int Offset);

    private sealed record BrowsePosition(double ScrollOffset, string? FocusedCardId,
        string? FirstVisibleShelfId, double OffsetWithinShelf);

    private sealed record CatalogShelf(
        string Id,
        string Title,
        string CountLabel,
        IReadOnlyList<CatalogCard> Cards,
        CatalogItemType Type,
        string? GroupId,
        bool OpensPagedGrid,
        DateTimeOffset? LatestAddedAt,
        bool HasNewItems,
        bool IgnoreItemFilterForPreview = false)
    {
        public Visibility PreviewVisibility => Cards.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        public Visibility SkeletonVisibility => Cards.Count > 0 ? Visibility.Collapsed : Visibility.Visible;
        public double SkeletonHeight => Type == CatalogItemType.Live ? 138 : 250;
        public double SkeletonRowHeight => SkeletonHeight + 36;
        public IReadOnlyList<ShelfSkeletonTile> SkeletonTiles => ShelfSkeletonTiles.For(Type);
        public Visibility NewSinceLastVisitVisibility => HasNewItems && Cards.Count > 0
            ? Visibility.Visible
            : Visibility.Collapsed;

        // The shelf row is named from ToString() for screen readers; the record's default dumps every field.
        public override string ToString() => $"{Title}, {CountLabel}";
    }

    private sealed class ShelfRowViewModel : INotifyPropertyChanged
    {
        private string _title;
        private string _countLabel;
        private IReadOnlyList<CatalogCard> _cards;
        private CatalogItemType _type;
        private string? _groupId;
        private bool _opensPagedGrid;
        private DateTimeOffset? _latestAddedAt;
        private bool _hasNewItems;
        private bool _ignoreItemFilterForPreview;
        private ShelfPreviewState _previewState;

        public ShelfRowViewModel(CatalogShelf shelf)
        {
            Id = shelf.Id;
            _title = shelf.Title;
            _countLabel = shelf.CountLabel;
            _cards = shelf.Cards;
            _type = shelf.Type;
            _groupId = shelf.GroupId;
            _opensPagedGrid = shelf.OpensPagedGrid;
            _latestAddedAt = shelf.LatestAddedAt;
            _hasNewItems = shelf.HasNewItems;
            _ignoreItemFilterForPreview = shelf.IgnoreItemFilterForPreview;
            _previewState = shelf.Cards.Count > 0 ? ShelfPreviewState.Cards : ShelfPreviewState.Skeleton;
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        public string Id { get; }
        public string Title => _title;
        public string CountLabel => _countLabel;
        public IReadOnlyList<CatalogCard> Cards => _cards;
        public CatalogItemType Type => _type;
        public string? GroupId => _groupId;
        public bool OpensPagedGrid => _opensPagedGrid;
        public DateTimeOffset? LatestAddedAt => _latestAddedAt;
        public bool HasNewItems => _hasNewItems;
        public bool IgnoreItemFilterForPreview => _ignoreItemFilterForPreview;
        public ShelfPreviewState PreviewState => _previewState;
        public Visibility PreviewVisibility => PreviewState == ShelfPreviewState.Cards ? Visibility.Visible : Visibility.Collapsed;
        public Visibility SkeletonVisibility => PreviewState == ShelfPreviewState.Skeleton ? Visibility.Visible : Visibility.Collapsed;
        public double SkeletonHeight => Type == CatalogItemType.Live ? 138 : 250;
        public double SkeletonRowHeight => SkeletonHeight + 36;
        public IReadOnlyList<ShelfSkeletonTile> SkeletonTiles => ShelfSkeletonTiles.For(Type);
        public Visibility NewSinceLastVisitVisibility => HasNewItems && Cards.Count > 0
            ? Visibility.Visible
            : Visibility.Collapsed;

        public void Update(CatalogShelf shelf)
        {
            if (!string.Equals(Id, shelf.Id, StringComparison.Ordinal))
                throw new InvalidOperationException("A shelf row cannot change identity.");
            Set(ref _title, shelf.Title, nameof(Title));
            Set(ref _countLabel, shelf.CountLabel, nameof(CountLabel));
            Set(ref _groupId, shelf.GroupId, nameof(GroupId));
            Set(ref _opensPagedGrid, shelf.OpensPagedGrid, nameof(OpensPagedGrid));
            Set(ref _latestAddedAt, shelf.LatestAddedAt, nameof(LatestAddedAt));
            Set(ref _hasNewItems, shelf.HasNewItems, nameof(HasNewItems));
            Set(ref _ignoreItemFilterForPreview, shelf.IgnoreItemFilterForPreview, nameof(IgnoreItemFilterForPreview));
            if (_type != shelf.Type)
            {
                _type = shelf.Type;
                OnPropertyChanged(nameof(Type));
                OnPropertyChanged(nameof(SkeletonHeight));
                OnPropertyChanged(nameof(SkeletonRowHeight));
                OnPropertyChanged(nameof(SkeletonTiles));
            }

            var cardsChanged = !ReferenceEquals(_cards, shelf.Cards);
            if (cardsChanged)
                _cards = shelf.Cards;
            var previewState = _cards.Count > 0 ? ShelfPreviewState.Cards : ShelfPreviewState.Skeleton;
            var previewStateChanged = _previewState != previewState;
            _previewState = previewState;
            if (previewStateChanged) OnPropertyChanged(nameof(PreviewState));
            if (cardsChanged) OnPropertyChanged(nameof(Cards));
            if (previewStateChanged)
            {
                OnPropertyChanged(nameof(PreviewVisibility));
                OnPropertyChanged(nameof(SkeletonVisibility));
            }
            if (cardsChanged || previewState == ShelfPreviewState.Cards)
                OnPropertyChanged(nameof(NewSinceLastVisitVisibility));
        }

        public CatalogShelf ToCatalogShelf() => new(Id, Title, CountLabel, Cards, Type, GroupId,
            OpensPagedGrid, LatestAddedAt, HasNewItems, IgnoreItemFilterForPreview);

        public override string ToString() => $"{Title}, {CountLabel}";

        private void Set<T>(ref T field, T value, string propertyName)
        {
            if (EqualityComparer<T>.Default.Equals(field, value)) return;
            field = value;
            OnPropertyChanged(propertyName);
            if (propertyName == nameof(HasNewItems)) OnPropertyChanged(nameof(NewSinceLastVisitVisibility));
        }

        private void OnPropertyChanged(string propertyName) =>
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }

    private enum ShelfPreviewState
    {
        Skeleton,
        Cards,
    }

    private sealed record ShelfSkeletonTile(double Width, double Height)
    {
        public double TitleWidth => Width * 0.7;
        public double SubtitleWidth => Width * 0.45;
    }

    private static class ShelfSkeletonTiles
    {
        private static readonly IReadOnlyList<ShelfSkeletonTile> Poster = Enumerable.Range(0, ShelfPreviewSize)
            .Select(_ => new ShelfSkeletonTile(190, 250)).ToArray();
        private static readonly IReadOnlyList<ShelfSkeletonTile> Channel = Enumerable.Range(0, ShelfPreviewSize)
            .Select(_ => new ShelfSkeletonTile(190, 138)).ToArray();
        public static IReadOnlyList<ShelfSkeletonTile> For(CatalogItemType type) =>
            type == CatalogItemType.Live ? Channel : Poster;
    }

    internal static EpgNowNext? FilterEpgForUi(bool enabled, EpgNowNext? nowNext) =>
        enabled ? nowNext : null;

    private sealed class CatalogCard(string id, string title, string subtitle, string? artworkUrl,
        double width, double height, Channel? channel, string? qualityBadge, CatalogItemType type,
        bool isContinueWatching, bool epgEnabled) : INotifyPropertyChanged
    {
        private EpgNowNext? _nowNext;
        private PlaybackProgress? _watchProgress;
        private CatalogMetadata? _hoverMetadata;
        private readonly bool _epgEnabled = epgEnabled;

        public string Id { get; } = id;
        public string Title { get; private set; } = title;
        public string Subtitle { get; } = subtitle;
        public string? ArtworkUrl { get; private set; } = artworkUrl;
        public double Width { get; } = width;
        public double Height { get; } = height;
        public int SpotlightIndex { get; private set; } = -1;
        public double SpotlightWidth { get; private set; } = 64;
        public double SpotlightPosterWidth => SpotlightExpanded ? (SpotlightWidth >= 600 ? 190 : 164) : 64;
        public double SpotlightPosterHeight => SpotlightExpanded ? (SpotlightWidth >= 600 ? 285 : 246) : 304;
        public Stretch SpotlightImageStretch => SpotlightExpanded ? Stretch.Uniform : Stretch.UniformToFill;
        public Visibility SpotlightTextVisibility => SpotlightExpanded && SpotlightTextRevealed ? Visibility.Visible : Visibility.Collapsed;
        private bool SpotlightTextRevealed { get; set; } = true;
        public Visibility SpotlightFocusVisibility { get; private set; } = Visibility.Collapsed;
        public bool SpotlightIsTabStop => SpotlightExpanded;
        public Thickness SpotlightPadding => SpotlightExpanded ? new Thickness(16) : new Thickness(0);
        public bool SpotlightExpanded { get; private set; }
        public string SpotlightDetails => DetailsLine;
        public bool SpotlightSummaryResolved { get; private set; } =
            !string.IsNullOrWhiteSpace(channel?.Metadata.GetValueOrDefault("plot"));
        public string SpotlightSummary => !string.IsNullOrWhiteSpace(HoverPlot) ? HoverPlot! :
            SpotlightSummaryResolved ? "Summary unavailable" : string.Empty;
        public string SpotlightAutomationName => SpotlightStripState.AutomationName(Title,
            Type == CatalogItemType.Movie, SpotlightIndex, Math.Max(1, SpotlightItemCount), SpotlightExpanded);
        private int SpotlightItemCount { get; set; } = 1;
        public Button? SpotlightButton { get; set; }

        public bool UpdateSpotlightData(CatalogCard incoming)
        {
            var artworkChanged = ArtworkUrl != incoming.ArtworkUrl;
            if (Title != incoming.Title)
            {
                Title = incoming.Title;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Title)));
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightAutomationName)));
            }
            ArtworkUrl = incoming.ArtworkUrl;
            if (incoming.Channel is { } newChannel)
            {
                var previousPlot = HoverPlot;
                Channel = newChannel;
                if (_hoverMetadata is null || !string.IsNullOrWhiteSpace(newChannel.Metadata.GetValueOrDefault("plot")))
                    SpotlightSummaryResolved = !string.IsNullOrWhiteSpace(HoverPlot);
                if (previousPlot != HoverPlot)
                    PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightSummary)));
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightDetails)));
            }
            ApplyWatchProgress(incoming._watchProgress);
            return artworkChanged;
        }
        public Channel? Channel { get; private set; } = channel;
        public string? QualityBadge { get; } = qualityBadge;
        public CatalogItemType Type { get; } = type;
        public bool IsContinueWatching { get; } = isContinueWatching;

        // List items are named for screen readers and UI Automation from ToString(); without this the
        // name is the class name ("Tvivo.App.Pages.CatalogLandingPage+CatalogCard").
        public override string ToString() => string.IsNullOrWhiteSpace(Subtitle) ? Title : $"{Title}, {Subtitle}";

        private PlaybackProgressPresentation WatchProgress => PlaybackProgressPresentation.For(_watchProgress);
        private ContinueWatchingProgress ContinueProgress => ContinueWatchingProgress.For(Channel!, _watchProgress);
        public double WatchProgressValue => WatchProgress.Value;
        public Visibility WatchProgressVisibility => WatchProgress.IsVisible ? Visibility.Visible : Visibility.Collapsed;
        public string WatchProgressAutomationName => WatchProgress.AutomationName;
        public string TimeLeft => IsContinueWatching ? ContinueProgress.TimeLeft : string.Empty;
        public Visibility ContinueProgressVisibility => IsContinueWatching && ContinueProgress.HasMeasuredProgress
            ? Visibility.Visible
            : Visibility.Collapsed;
        public Visibility QualityBadgeVisibility => string.IsNullOrWhiteSpace(QualityBadge) ? Visibility.Collapsed : Visibility.Visible;
        public string NowText => _epgEnabled && _nowNext?.Now is { } now ? $"Now: {now.Title}" : string.Empty;
        public Visibility NowVisibility => !_epgEnabled || _nowNext?.Now is null ? Visibility.Collapsed : Visibility.Visible;
        public double ProgressValue => _epgEnabled && _nowNext?.Now is { } now
            ? Math.Clamp((DateTimeOffset.UtcNow - now.StartUtc).TotalSeconds /
                Math.Max(1, (now.EndUtc - now.StartUtc).TotalSeconds) * 100, 0, 100)
            : 0;
        public Visibility ProgressVisibility => !_epgEnabled || _nowNext?.Now is null ? Visibility.Collapsed : Visibility.Visible;
        public string NextText => _epgEnabled && _nowNext?.Next is { } next
            ? $"Next: {next.Title} · {FormatLocalTime(next.StartUtc)}"
            : string.Empty;
        public Visibility NextVisibility => !_epgEnabled || _nowNext?.Next is null ? Visibility.Collapsed : Visibility.Visible;
        public Visibility EpgVisibility => !_epgEnabled || _nowNext?.Now is null && _nowNext?.Next is null
            ? Visibility.Collapsed
            : Visibility.Visible;
        public string EpgAutomationName
        {
            get
            {
                if (!_epgEnabled) return string.Empty;
                var parts = new List<string>();
                if (_nowNext?.Now is { } now)
                    parts.Add($"Now playing: {now.Title}, {Math.Clamp((int)Math.Round(ProgressValue), 0, 100)} percent through");
                if (_nowNext?.Next is { } next)
                    parts.Add($"next: {next.Title} at {FormatLocalTime(next.StartUtc)}");
                return string.Join(", ", parts);
            }
        }
        public string DetailsLine => Channel is null ? string.Empty : string.Join(" · ", new[]
        {
            Channel.Metadata.GetValueOrDefault("year"),
            Channel.Metadata.TryGetValue("rating", out var rating) && CatalogRatingFormatter.Format(rating) is { } formattedRating ? $"★ {formattedRating}" : null,
            Channel.Metadata.GetValueOrDefault("genre"),
        }.Where(value => !string.IsNullOrWhiteSpace(value)));
        private string? HoverPlot => _hoverMetadata is not null
            ? _hoverMetadata.Plot
            : Channel?.Metadata.GetValueOrDefault("plot");
        public string HoverSummaryText => CatalogHoverTextFormatter.Summary(Type, HoverPlot);
        public string? HoverCast => _hoverMetadata is not null
            ? _hoverMetadata.Cast
            : Channel?.Metadata.GetValueOrDefault("cast");
        public bool HasHoverSummary => CatalogHoverTextFormatter.HasSummary(Type, HoverPlot, NowText, NextText);

        public void ApplyMetadata(CatalogMetadata metadata)
        {
            if (Channel is null) return;
            var values = Channel.Metadata.ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
            Add("year", metadata.Year);
            Add("rating", metadata.Rating);
            Add("genre", metadata.Genre);
            Add("plot", metadata.Plot);
            Add("cast", metadata.Cast);
            Channel = Channel with { Metadata = values };
            SetHoverMetadata(metadata);

            void Add(string key, string? value)
            {
                if (!string.IsNullOrWhiteSpace(value)) values[key] = value;
            }
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        public void SetHoverMetadata(CatalogMetadata? metadata)
        {
            _hoverMetadata = metadata;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightSummary)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightDetails)));
        }

        public void SetSpotlightState(int index, int itemCount, bool expanded, double width, bool isFocusedTabStop,
            bool revealText)
        {
            SpotlightIndex = index;
            SpotlightItemCount = Math.Max(1, itemCount);
            SpotlightExpanded = expanded;
            SpotlightTextRevealed = revealText;
            SpotlightWidth = width;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightIndex)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightExpanded)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightWidth)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightPosterWidth)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightPosterHeight)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightImageStretch)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightTextVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightIsTabStop)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightPadding)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightAutomationName)));
        }

        public void SetSpotlightFocused(bool focused)
        {
            SpotlightFocusVisibility = focused ? Visibility.Visible : Visibility.Collapsed;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightFocusVisibility)));
        }

        public void ResolveSpotlightSummary()
        {
            SpotlightSummaryResolved = true;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightSummary)));
        }

        public void RevealSpotlightText()
        {
            SpotlightTextRevealed = true;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SpotlightTextVisibility)));
        }

        public void ApplyWatchProgress(PlaybackProgress? progress)
        {
            if (_watchProgress == progress) return;
            _watchProgress = progress;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(WatchProgressValue)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(WatchProgressVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(WatchProgressAutomationName)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(TimeLeft)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ContinueProgressVisibility)));
        }

        public void ApplyNowNext(EpgNowNext? nowNext)
        {
            nowNext = FilterEpgForUi(_epgEnabled, nowNext);
            _nowNext = nowNext?.Now is null && nowNext?.Next is null ? null : nowNext;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(NowText)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(NowVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ProgressValue)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ProgressVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(NextText)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(NextVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(EpgVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(EpgAutomationName)));
        }

        public void NotifyMetadataChanged()
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(DetailsLine)));
        }

        private static string FormatLocalTime(DateTimeOffset utcTime) =>
            utcTime.ToLocalTime().ToString("HH:mm", CultureInfo.CurrentCulture);
    }

    public enum CatalogMode
    {
        MyTvivo,
        Movies,
        Series,
        LiveTv,
    }

    private enum CategorySort
    {
        Visited,
        AlphabeticalAsc,
        AlphabeticalDesc,
        RecentlyAdded,
        RecentlyUpdated,
    }
}

public sealed class ChannelSelectedEventArgs(Channel channel, IReadOnlyList<Channel> relatedChannels) : EventArgs
{
    public Channel Channel { get; } = channel;
    public StreamSource Source => Channel.Source;
    public IReadOnlyList<Channel> RelatedChannels { get; } = relatedChannels;
}

public static class ArtworkReusePolicy
{
    public static bool ShouldReuse(string? appliedItemKey, string? appliedUrl,
        string requestedItemKey, string? requestedUrl, bool sourceIsApplied) =>
        sourceIsApplied && appliedItemKey == requestedItemKey && appliedUrl == requestedUrl;
}

public static class ArtworkMemoryBudget
{
    public static long EstimateDecodedBytes(int width, int height) =>
        checked((long)Math.Max(0, width) * Math.Max(0, height) * 4);

    public static bool ShouldEvict(long retainedBytes, long limitBytes, int entryCount, int countLimit) =>
        entryCount > 1 && (retainedBytes > limitBytes || entryCount > countLimit);
}

public sealed class CatalogRefreshTasks(Func<ProviderAccount, CatalogItemType, CancellationToken, Task<bool>> refresh)
{
    private readonly object _gate = new();
    private readonly Dictionary<(string AccountId, CatalogItemType Type), Task<bool>> _active = new();

    public Task<bool> RefreshTypeAsync(ProviderAccount account, CatalogItemType type,
        CancellationToken cancellationToken = default)
    {
        var key = (account.AccountId, type);
        lock (_gate)
        {
            if (_active.TryGetValue(key, out var pending) && !pending.IsCompleted) return pending;
            var task = refresh(account, type, cancellationToken);
            _active[key] = task;
            _ = task.ContinueWith(_ =>
            {
                lock (_gate)
                    if (_active.TryGetValue(key, out var current) && ReferenceEquals(current, task))
                        _active.Remove(key);
            }, TaskScheduler.Default);
            return task;
        }
    }

    public bool TryGetActive(string accountId, CatalogItemType type, out Task? task)
    {
        lock (_gate)
        {
            var found = _active.TryGetValue((accountId, type), out var pending);
            task = pending;
            return found;
        }
    }
}
