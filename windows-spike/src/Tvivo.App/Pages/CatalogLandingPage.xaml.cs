using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml.Hosting;
using Windows.Storage.Streams;
using System.ComponentModel;
using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public sealed partial class CatalogLandingPage : UserControl
{
#if DEBUG
    private static readonly string? LocalFixturePath = FindFixturePath();
#endif
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly SqliteCatalogRepository _repository = App.Services.GetRequiredService<SqliteCatalogRepository>();
    private readonly CatalogRefreshService _refresh = App.Services.GetRequiredService<CatalogRefreshService>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private readonly EpgCoordinator _epgCoordinator = App.Services.GetRequiredService<EpgCoordinator>();
    private ProviderAccount? _account;
    private ProviderConnection? _connection;
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private string? _selectedGroupId;
    private string? _openShelfId;
    private CatalogItemType? _openShelfType;
    private CatalogMode _activeMode = CatalogMode.MyTvivo;
    private const CategorySort SpotlightSort = CategorySort.Visited;
    private CategorySort _categorySort = CategorySort.Visited;
    private int _offset;
    private long _loadGeneration;
    private long _pageQueryGeneration;
    private bool _suppressSearchChanged;
    private bool _isReadyForInteraction;
    private bool _isActive;
    private bool _firstCatalogLoadCompleted;
    private bool _showModeLoadingFrame;
    private long _modeTransitionGeneration;
    private readonly SemaphoreSlim _modeTransitionGate = new(1, 1);
    private CatalogCard? _spotlightCard;
    private readonly Dictionary<CatalogSnapshotKey, CachedCatalogSnapshot> _snapshotCache = new();
    private readonly Queue<CatalogSnapshotKey> _snapshotCacheOrder = new();
    private readonly Dictionary<(string AccountId, CatalogMode Mode), DateTimeOffset> _lastRefreshAt = new();
    private readonly Dictionary<CatalogMode, ModeInteractionState> _modeStates = new();
    private readonly Dictionary<(CatalogMode Mode, string ShelfId), BrowsePosition> _browsePositions = new();
    private readonly HashSet<string> _recentSpotlightIds = new(StringComparer.Ordinal);
    private readonly Dictionary<CatalogMode, CatalogCard> _spotlightByMode = new();
    private readonly object _spotlightShelvesLock = new();
    private readonly Dictionary<(string AccountId, CatalogMode Mode), IReadOnlyList<CatalogShelf>> _spotlightShelvesCache = new();
    private static readonly string SpotlightSessionId = Guid.NewGuid().ToString("N");
    private const int SnapshotCacheCapacity = 16;
    private static readonly TimeSpan SnapshotFreshness = TimeSpan.FromMinutes(15);
    private static readonly Regex QualityToken = new(
        @"(?i)(?<![\p{L}\p{N}])(?<quality>FULL[\s_\-]?HD|FHD|UHD|4K|2160p|1080[pi]|720p|576p|480p|HD|SD)(?![\p{L}\p{N}])",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex CodecToken = new(
        @"(?i)(?<![\p{L}\p{N}])(?:HEVC|H[\s.]?26[45]|x26[45])(?![\p{L}\p{N}])",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex EmptyBrackets = new(@"[\(\[\{]\s*[\)\]\}]", RegexOptions.Compiled);
    private static readonly Regex RepeatedSpaces = new(@"\s{2,}", RegexOptions.Compiled);
    private readonly Dictionary<string, Task> _activeRefreshTasks = new(StringComparer.Ordinal);
    private readonly HashSet<GridView> _wiredShelfGrids = new();
    private readonly SemaphoreSlim _artworkSlots = new(6);
    private static readonly TimeSpan ArtworkRequestTimeout = TimeSpan.FromSeconds(30);
    private static readonly HttpClient ArtworkClient = new() { Timeout = TimeSpan.FromSeconds(30) };
    private readonly Dictionary<Image, ArtworkLoad> _artworkLoads = new();
    private readonly Dictionary<Image, AppliedArtwork> _appliedArtwork = new();
    private readonly Dictionary<string, LinkedListNode<CachedArtworkBitmap>> _artworkBitmapCache = new(StringComparer.Ordinal);
    private readonly LinkedList<CachedArtworkBitmap> _artworkBitmapLru = new();
    private long _artworkBitmapCacheBytes;
    // Decoded BGRA pixels dominate retained memory; 96 MiB holds roughly 120 poster-sized cards.
    private const long ArtworkBitmapCacheByteLimit = 96L * 1024 * 1024;
    private const int ArtworkBitmapCacheCountLimit = 128;
    private readonly HashSet<string> _artworkDiagnosticsLogged = new(StringComparer.Ordinal);
    private readonly HashSet<string> _artworkFailuresLogged = new(StringComparer.Ordinal);
    private readonly DispatcherTimer _spotlightTimer = new() { Interval = TimeSpan.FromSeconds(8) };
    private readonly DispatcherTimer _epgTimer = new() { Interval = TimeSpan.FromSeconds(60) };
    private readonly SemaphoreSlim _epgQueryGate = new(1, 1);
    private readonly SemaphoreSlim _epgStartGate = new(1, 1);
    private readonly HashSet<string> _epgMapLoadedAccounts = new(StringComparer.Ordinal);
#if DEBUG
    private readonly HashSet<FrameworkElement> _realizedShelves = new();
#endif
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
    private const string GenericLoadError = "Couldn't load the catalog. Check your connection and provider details, then retry.";
    public ProviderAccount? Account => _account;
    public ProviderConnection? Connection => _connection;
    public CatalogMode ActiveMode => _activeMode;
    public bool IsReadyForInteraction => _isReadyForInteraction;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;
    public event EventHandler? InteractionReadinessChanged;
    // Raised when the user changes what they are browsing (mode, category, sort, search), so
    // deferred visit ranking can be applied then and not while a returning list is on screen.
    public event EventHandler? BrowseContextChanging;

    public Task<string?> GetCategoryNameForAsync(Channel channel)
    {
        if (_account is not { } account || string.IsNullOrWhiteSpace(channel.GroupId))
            return Task.FromResult<string?>(null);

        var type = TypeForSource(channel.Source.Kind);
        var groupId = channel.GroupId;
        return Task.Run(() => _repository.GetGroups(account, type)
            .FirstOrDefault(group => group.Id == groupId)?.DisplayName);
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
        InitializeComponent();
        _spotlightTimer.Tick += SpotlightTimer_Tick;
        _epgTimer.Tick += EpgTimer_Tick;
        _epgCoordinator.EpgUpdated += EpgCoordinator_EpgUpdated;
        UpdateModeChrome();
#if DEBUG
        if (LocalFixturePath is not null)
        {
            var fixtureButton = new Button
            {
                Content = "Debug: Gate 9 fixture",
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 0, 8),
                Style = (Style)Application.Current.Resources["AppQuietButtonStyle"],
            };
            fixtureButton.Click += LocalFixtureButton_Click;
            Grid.SetRowSpan(fixtureButton, 3);
            CatalogPageRoot.Children.Add(fixtureButton);
        }
#endif
    }

    public void SetActive(bool active)
    {
        _isActive = active;
        if (active) _spotlightTimer.Start();
        else _spotlightTimer.Stop();
        UpdateEpgLifecycle();
    }

    public void InvalidateCatalogSnapshots()
    {
        _snapshotCache.Clear();
        _snapshotCacheOrder.Clear();
        _renderedSnapshot = null;
        _renderedOpenShelfId = null;
    }

    public void ClearAccountState()
    {
        Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        _account = null;
        _connection = null;
        _selectedGroupId = null;
        _openShelfId = null;
        _openShelfType = null;
        _offset = 0;
        _firstCatalogLoadCompleted = false;
        _modeStates.Clear();
        _spotlightByMode.Clear();
        _lastRefreshAt.Clear();
        _recentSpotlightIds.Clear();
        InvalidateCatalogSnapshots();
        ShelvesItems.ItemsSource = null;
        CancelArtworkLoads();
        RefreshInfoBar.IsOpen = false;
        ShowError("No saved provider connection. Choose Account in the top navigation to connect a provider.");
    }

    public void NotifyVisitRecorded()
    {
        if (_account is null) return;
        foreach (var key in _snapshotCache.Keys.Where(key =>
                     key.AccountId == _account.AccountId).ToArray())
            _snapshotCache.Remove(key);
        _snapshotCacheOrder.Clear();
        foreach (var key in _snapshotCache.Keys) _snapshotCacheOrder.Enqueue(key);
    }

    public void NotifyFavoriteChanged() => NotifyVisitRecorded();

    public void Shutdown()
    {
        _spotlightTimer.Stop();
        _spotlightTimer.Tick -= SpotlightTimer_Tick;
        _epgTimer.Stop();
        _epgTimer.Tick -= EpgTimer_Tick;
        _epgCoordinator.EpgUpdated -= EpgCoordinator_EpgUpdated;
        CancelArtworkLoads();
    }

    public async Task PrepareModeAsync(CatalogMode mode)
    {
        var transitionGeneration = Interlocked.Increment(ref _modeTransitionGeneration);
        if (_activeMode == mode)
        {
            var visual = ElementCompositionPreview.GetElementVisual(ContentState);
            visual.StopAnimation(nameof(Visual.Opacity));
            visual.Opacity = 1;
            ContentState.IsHitTestVisible = true;
            // Same-mode navigation still starts a fresh cache-first load. Keep a
            // ready snapshot interactive, but don't leave an empty/error surface
            // looking ready while that load is about to retry.
            if (ContentState.Visibility != Visibility.Visible && LoadingState.Visibility != Visibility.Visible)
                SetState(loading: true);
            return;
        }

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
        SaveModeState(_activeMode);
        _activeMode = mode;
        UpdateEpgLifecycle();
        var keepVisibleSnapshot = _renderedSnapshot is not null;
        _showModeLoadingFrame = !keepVisibleSnapshot;
        _spotlightTimer.Stop();
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
        RefreshInfoBar.IsOpen = false;
        UpdateModeChrome();
        UpdateEmptyCopy();
        if (keepVisibleSnapshot)
        {
            // Keep the old category attached until the incoming snapshot is ready.
            // The snapshot swap itself is performed while the content is faded out.
            ContentState.IsHitTestVisible = false;
        }
        else
        {
            ShelvesItems.ItemsSource = null;
            _renderedSnapshot = null;
            CancelArtworkLoads();
            OpenShelfGrid.ItemsSource = null;
            SpotlightPanel.Visibility = Visibility.Collapsed;
            SetState(loading: true);
        }
    }

    public async Task SetModeAsync(CatalogMode mode)
    {
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

        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        _suppressSearchChanged = true;
        SearchBox.Text = text;
        _suppressSearchChanged = false;
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(text) ? Visibility.Collapsed : Visibility.Visible;
        _offset = 0;
        _ = ShowCachedPageAsync(_activeMode, text, _selectedGroupId, 0);
    }

    public async Task LoadAsync(ProviderAccount account, ProviderConnection? connection = null, bool forceRefresh = false)
    {
        var generation = Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        var accountChanged = _account?.AccountId != account.AccountId;
        var previousAccountId = _account?.AccountId;
        _account = account;
        if (connection is not null || accountChanged)
            _connection = connection;
        if (accountChanged)
        {
            if (previousAccountId is not null) InvalidateAccountSnapshots(previousAccountId);
            ShelvesItems.ItemsSource = null;
            _renderedSnapshot = null;
            CancelArtworkLoads();
            _firstCatalogLoadCompleted = false;
            _modeStates.Clear();
            _spotlightByMode.Clear();
            _selectedGroupId = null;
            _openShelfId = null;
            _openShelfType = null;
            _offset = 0;
            RefreshInfoBar.IsOpen = false;
            SetState(loading: true);
        }
        await LoadCatalogAsync(account, generation, forceRefresh);
    }

    public async Task LoadSavedAsync()
    {
        var previousAccountId = _account?.AccountId;
        var generation = Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        _account = null;
        ShelvesItems.ItemsSource = null;
        _renderedSnapshot = null;
        CancelArtworkLoads();
        _connection = null;
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
            _connection = connection;
            var result = await _provider.AuthenticateAsync(connection);
            if (!IsCurrentLoad(generation)) return;
            if (!result.Success || result.Account is null)
            {
                ShowError(GenericLoadError);
                return;
            }
            _account = result.Account;
            if (previousAccountId is not null && previousAccountId != result.Account.AccountId)
            {
                _firstCatalogLoadCompleted = false;
                InvalidateAccountSnapshots(previousAccountId);
                _modeStates.Clear();
                _spotlightByMode.Clear();
            }
            await LoadCatalogAsync(result.Account, generation);
        }
        catch
        {
            if (IsCurrentLoad(generation)) ShowError(GenericLoadError);
        }
    }

    private async Task LoadCatalogAsync(ProviderAccount account, long generation, bool forceRefresh = false)
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
            var cached = TryGetCachedSnapshot(account);
            cached ??= await ReadCurrentCatalogSnapshotAsync(account, generation);
            cacheCompleted = true;
            LaunchDiagnostics.Write($"event=catalog.cache.load.complete outcome={(cached is null ? "failure" : "success")} durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(cacheStarted).TotalMilliseconds:0} mode={_activeMode}");
            if (!IsCurrentLoad(generation)) return;
            if (cached is null) return;

            if (HasCatalogData(cached))
            {
                await ApplySnapshotAsync(cached, () => IsCurrentLoad(generation));
                _firstCatalogLoadCompleted = true;
                ShowRefreshingStatus();
            }
            else
            {
                SetState(loading: true);
                if (!_firstCatalogLoadCompleted)
                    ShowFirstLoadTakeover();
            }

            var shouldRefresh = forceRefresh || !HasCatalogData(cached) || IsSnapshotStale(account);
            if (!shouldRefresh)
            {
                StartEpgIfVisible();
                RefreshInfoBar.IsOpen = false;
                DismissFirstLoadTakeover();
                return;
            }

            var refreshStarted = System.Diagnostics.Stopwatch.GetTimestamp();
            LaunchDiagnostics.Write($"event=catalog.cache.refresh.start mode={_activeMode}");
            try
            {
                await RefreshAccountAsync(account);
                LaunchDiagnostics.Write($"event=catalog.cache.refresh.complete outcome=success durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(refreshStarted).TotalMilliseconds:0} mode={_activeMode}");
            }
            catch
            {
                LaunchDiagnostics.Write($"event=catalog.cache.refresh.complete outcome=failure durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(refreshStarted).TotalMilliseconds:0} mode={_activeMode}");
                throw;
            }
            if (!IsCurrentLoad(generation)) return;
            InvalidateAccountSnapshots(account.AccountId);
            var refreshedAt = DateTimeOffset.UtcNow;
            foreach (var mode in Enum.GetValues<CatalogMode>())
                _lastRefreshAt[(account.AccountId, mode)] = refreshedAt;

            var refreshed = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (!IsCurrentLoad(generation)) return;
            if (refreshed is null) return;

            CacheSnapshot(account, refreshed);
            await ApplySnapshotAsync(refreshed, () => IsCurrentLoad(generation));
            StartEpgIfVisible();
            _firstCatalogLoadCompleted = true;
            DismissFirstLoadTakeover();
            RefreshInfoBar.IsOpen = false;
        }
        catch (Exception exception)
        {
            if (!cacheCompleted)
                LaunchDiagnostics.Write($"event=catalog.cache.load.complete outcome=failure durationMs={System.Diagnostics.Stopwatch.GetElapsedTime(cacheStarted).TotalMilliseconds:0} mode={_activeMode}");
            if (!IsCurrentLoad(generation)) return;
            LaunchDiagnostics.Write($"Catalog load failed: {exception.GetType().Name} (HRESULT 0x{exception.HResult:X8})");
            if (ContentState.Visibility == Visibility.Visible)
            {
                ShowRefreshFailure(exception);
                var visual = ElementCompositionPreview.GetElementVisual(ContentState);
                if (visual.Opacity < 1f)
                    await FadeCatalogContentAsync(fadeOut: false, transitionGeneration: Volatile.Read(ref _modeTransitionGeneration));
            }
            else
                ShowError($"{GenericLoadError} {FormatRefreshError(exception)}");
        }
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
            var groups = _repository.GetGroups(account, type);
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
            if (openShelfId is "__recent_added" or "__recent_played" or "__favorites")
            {
                var allItems = openShelfId switch
                {
                    "__recent_added" => _repository.GetRecentlyAdded(account, type, _connection, int.MaxValue, filter),
                    "__recent_played" => _repository.GetRecentlyPlayed(account, type, _connection, int.MaxValue, filter),
                    _ => _repository.GetFavorites(account, type, _connection, int.MaxValue, filter),
                };
                allItems = SortOpenShelfItems(allItems, sort);
                var lastOffset = allItems.Count == 0 ? 0 : ((allItems.Count - 1) / PageSize) * PageSize;
                var shelfOffset = Math.Clamp(requestedOffset, 0, lastOffset);
                var shelfItems = allItems.Skip(shelfOffset).Take(PageSize).ToArray();
                var shelfList = BuildShelves(account, groups, filter, mode, sort);
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
            var shelves = BuildShelves(account, groups, filter, mode, sort);
            return new CatalogSnapshot(mode, groups, groupId, offset, page, shelves);
        });

        if (string.IsNullOrWhiteSpace(filter) && sort == SpotlightSort)
        {
            lock (_spotlightShelvesLock) _spotlightShelvesCache[(account.AccountId, mode)] = snapshot.Shelves;
            return snapshot;
        }

        var spotlight = await Task.Run(() =>
        {
            lock (_spotlightShelvesLock)
            {
                if (_spotlightShelvesCache.TryGetValue((account.AccountId, mode), out var cached)) return cached;
            }
            var built = BuildShelves(account, snapshot.Groups, null, mode, SpotlightSort);
            lock (_spotlightShelvesLock) _spotlightShelvesCache[(account.AccountId, mode)] = built;
            return built;
        });
        return snapshot with { SpotlightSource = spotlight };
    }

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

    private async Task RefreshAccountAsync(ProviderAccount account)
    {
        if (!_activeRefreshTasks.TryGetValue(account.AccountId, out var refreshTask) || refreshTask.IsCompleted)
        {
            refreshTask = Task.Run(() => _refresh.RefreshAsync(account));
            _activeRefreshTasks[account.AccountId] = refreshTask;
        }

        try
        {
            await refreshTask;
        }
        finally
        {
            if (refreshTask.IsCompleted &&
                _activeRefreshTasks.TryGetValue(account.AccountId, out var activeTask) &&
                ReferenceEquals(activeTask, refreshTask))
            {
                _activeRefreshTasks.Remove(account.AccountId);
            }
        }
    }

    private void UpdateEpgLifecycle()
    {
        if (_isActive && _activeMode == CatalogMode.LiveTv)
            _epgTimer.Start();
        else
            _epgTimer.Stop();
    }

    private void StartEpgIfVisible()
    {
        if (!_isActive || _activeMode != CatalogMode.LiveTv ||
            _account is not { } account || _connection is not { } connection)
        {
            LaunchDiagnostics.Write($"EPG start skipped: active={_isActive} mode={_activeMode} account={_account is not null} connection={_connection is not null}");
            return;
        }

        _activeRefreshTasks.TryGetValue(account.AccountId, out var catalogRefreshTask);
        _ = StartEpgAsync(account, connection, catalogRefreshTask);
    }

    private async Task StartEpgAsync(ProviderAccount account, ProviderConnection connection, Task? catalogRefreshTask)
    {
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
                    lock (_epgMapLoadedAccounts) _epgMapLoadedAccounts.Add(account.AccountId);
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

        DispatcherQueue.TryEnqueue(() => _ = RefreshVisibleEpgAsync());
    }

    private async Task RefreshVisibleEpgAsync()
    {
        if (!_isActive || _activeMode != CatalogMode.LiveTv || _account is not { } account ||
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

            foreach (var card in cards)
            {
                var epgId = card.Channel!.Metadata["epg_channel_id"];
                nowNext.TryGetValue(epgId, out var programme);
                card.ApplyNowNext(programme);
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
        CategorySort sort)
    {
        if (mode == CatalogMode.MyTvivo)
            return BuildMyTvivoShelves(account, filter);

        var type = TypeForMode(mode);
        var noun = NounFor(type);
        var shelves = new List<CatalogShelf>();
        AddActivityShelf(account, shelves, "__recent_added", "Recently added", type,
            _repository.GetRecentlyAdded(account, type, _connection, int.MaxValue, filter));
        AddActivityShelf(account, shelves, "__recent_played", "Recently played", type,
            _repository.GetRecentlyPlayed(account, type, _connection, int.MaxValue, filter));
        AddActivityShelf(account, shelves, "__favorites", "Favorites", type,
            _repository.GetFavorites(account, type, _connection, int.MaxValue, filter));

        var allChannels = _repository.GetChannels(account, _connection, type, filter: filter, offset: 0, limit: ShelfPreviewSize);
        if (allChannels.Items.Count > 0)
        {
            var latestAddedAt = _repository.GetRecentlyAdded(account, type, _connection, 1, filter)
                .FirstOrDefault()?.AddedAt;
            shelves.Add(CreateShelf(account,
                "__recent",
                AllItemsShelfTitle(type),
                CountLabel(allChannels.TotalCount, noun),
                allChannels.Items.Select(channel => ToCard(channel, type)).ToArray(),
                type,
                null,
                false,
                latestAddedAt));
        }

        var pages = _repository.GetChannelsGroupedByCategory(account, _connection, type, filter, ShelfPreviewSize);
        IReadOnlyDictionary<string, CatalogPage>? unfilteredPages = null;
        foreach (var group in SortGroups(groups, sort))
        {
            var titleMatchesFilter = !string.IsNullOrWhiteSpace(filter) &&
                group.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase);

            CatalogPage page;
            if (titleMatchesFilter)
            {
                unfilteredPages ??= _repository.GetChannelsGroupedByCategory(account, _connection, type, null, ShelfPreviewSize);
                if (!unfilteredPages.TryGetValue(group.Id, out var unfilteredPage)) continue;
                page = unfilteredPage;
            }
            else
            {
                if (!pages.TryGetValue(group.Id, out var filteredPage)) continue;
                page = filteredPage;
            }

            shelves.Add(CreateShelf(account,
                group.Id,
                group.DisplayName,
                CountLabel(page.TotalCount, noun),
                page.Items.Select(channel => ToCard(channel, type, QualityFromText(group.DisplayName))).ToArray(),
                type,
                group.Id,
                true,
                page.LatestAddedAt));
        }

        return shelves;
    }

    private static IReadOnlyList<ChannelGroup> SortGroups(IReadOnlyList<ChannelGroup> groups, CategorySort sort) =>
        sort switch
        {
            CategorySort.AlphabeticalAsc => groups.OrderBy(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            CategorySort.AlphabeticalDesc => groups.OrderByDescending(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
            _ => groups.OrderBy(group => group.SortOrder ?? int.MaxValue).ThenBy(group => group.DisplayName, StringComparer.CurrentCultureIgnoreCase).ToArray(),
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
        return MyTvivoShelfDefinitions.All
            .Select(definition => CreateMyTvivoShelf(account, definition, GetMyTvivoShelfItems(account, definition, filter)))
            .Where(shelf => ShelfVisibilityPolicy.HasItems(shelf.Cards.Count))
            .ToArray();
    }

    private IReadOnlyList<Channel> GetMyTvivoShelfItems(
        ProviderAccount account,
        MyTvivoShelfDefinition definition,
        string? filter) => definition.Kind switch
    {
        MyTvivoShelfKind.RecentlyAdded => _repository.GetRecentlyAdded(account, definition.Type, _connection, int.MaxValue, filter),
        MyTvivoShelfKind.RecentlyPlayed => _repository.GetRecentlyPlayed(account, definition.Type, _connection, int.MaxValue, filter),
        MyTvivoShelfKind.Favorites => _repository.GetFavorites(account, definition.Type, _connection, int.MaxValue, filter),
        _ => Array.Empty<Channel>(),
    };

    private void AddActivityShelf(
        ProviderAccount account,
        ICollection<CatalogShelf> shelves,
        string id,
        string title,
        CatalogItemType type,
        IReadOnlyList<Channel> channels)
    {
        if (channels.Count == 0) return;
        shelves.Add(CreateShelf(account, id, title, CountLabel(channels.Count, NounFor(type)),
            channels.Take(ShelfPreviewSize).Select(channel => ToCard(channel, type)).ToArray(), type, null, false,
            channels.Where(channel => channel.AddedAt.HasValue).Select(channel => channel.AddedAt).Max()));
    }

    private CatalogShelf CreateMyTvivoShelf(ProviderAccount account, MyTvivoShelfDefinition definition, IReadOnlyList<Channel> channels)
    {
        var preview = channels.Take(ShelfPreviewSize)
            .Select(channel => ToCard(channel, definition.Type))
            .ToArray();
        return CreateShelf(account, definition.Id, definition.Title, CountLabel(channels.Count, NounFor(definition.Type)), preview,
            definition.Type, null, false,
            channels.Where(channel => channel.AddedAt.HasValue).Select(channel => channel.AddedAt).Max());
    }

    private static CatalogItemType TypeForSource(StreamKind kind) => kind switch
    {
        StreamKind.Live => CatalogItemType.Live,
        StreamKind.Series => CatalogItemType.Series,
        _ => CatalogItemType.Movie,
    };

    private CatalogShelf CreateShelf(ProviderAccount account, string id, string title, string countLabel,
        IReadOnlyList<CatalogCard> cards, CatalogItemType type, string? groupId, bool opensPagedGrid,
        DateTimeOffset? latestAddedAt)
    {
        var lastVisitedAt = GetLastShelfVisitAt(account, type, id) ?? DateTimeOffset.UtcNow;
        return new CatalogShelf(id, title, countLabel, cards, type, groupId, opensPagedGrid, latestAddedAt,
            latestAddedAt is { } addedAt && addedAt > lastVisitedAt);
    }

    // ApplicationData.Current requires a packaged app identity, which this unpackaged WinUI3 app
    // does not have -- it throws InvalidOperationException at runtime (confirmed live: it broke
    // catalog loading entirely). Match the existing plain-file settings convention used elsewhere
    // in this file (see GetPlaybackEnginePreferencePath in MainWindow.xaml.cs) instead.
    private static readonly string ShelfVisitsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Tvivo", "shelf-visits.json");
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
            return ReadShelfVisits().TryGetValue(key, out var milliseconds)
                ? DateTimeOffset.FromUnixTimeMilliseconds(milliseconds)
                : null;
        }
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

    private static CatalogCard ToCard(Channel channel, CatalogItemType type, string? categoryQuality = null, string? subtitleOverride = null)
    {
        var (title, qualityBadge) = SplitQualityBadge(channel.DisplayName);
        return new CatalogCard(
            $"{channel.ProviderAccountId}:{channel.Source.Kind}:{channel.Id}",
            title,
            subtitleOverride ?? SubtitleFor(type),
            channel.LogoUri?.ToString(),
            190,
            type == CatalogItemType.Live ? 138 : 250,
            channel,
            qualityBadge ?? categoryQuality);
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
        clean = RepeatedSpaces.Replace(clean, " ").Trim(' ', '-', '|', '_', '.', ',', ':', '–', '—');
        return string.IsNullOrWhiteSpace(clean) ? (title, null) : (clean, badge);
    }

    public void NotifyMetadataChanged(string channelId)
    {
        foreach (var card in _snapshotCache.Values.SelectMany(value => value.Snapshot.Shelves)
                     .SelectMany(shelf => shelf.Cards).Where(card => card.Channel?.Id == channelId))
            card.NotifyMetadataChanged();
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

    private async Task ApplySnapshotAsync(CatalogSnapshot snapshot, Func<bool>? isCurrent = null)
    {
        var transitionGeneration = Volatile.Read(ref _modeTransitionGeneration);
        var fadedOut = false;
        var animateIncomingContent = false;
        var contentApplied = false;
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
            var snapshotChanged = !ReferenceEquals(_renderedSnapshot, snapshot);
            if (snapshotChanged || _renderedOpenShelfId != _openShelfId)
            {
                if (snapshotChanged) RenderSpotlight(snapshot.SpotlightShelves);
                RenderShelfSurface(snapshot);
                _renderedSnapshot = snapshot;
                _renderedOpenShelfId = _openShelfId;
            }
            UpdatePaging(snapshot.Page.TotalCount);

            if ((snapshot.IsFlatSearch && snapshot.Page.TotalCount == 0) || (!snapshot.IsFlatSearch && snapshot.Shelves.Count == 0))
                SetState(empty: true);
            else
                SetState(content: true);
            // Finish the layout pass before revealing newly attached content.
            // On first use the surface was hidden before binding; on later mode
            // changes the outgoing surface has already faded away.
            ContentState.UpdateLayout();
            RestoreBrowseStateIfMatching(snapshot.Mode);
            ContentState.IsHitTestVisible = true;
            contentApplied = true;
            if (_isActive && _activeMode == CatalogMode.LiveTv)
                _ = RefreshVisibleEpgAsync();
            if (_isActive) _spotlightTimer.Start();
        }
        finally
        {
            if ((fadedOut || (animateIncomingContent && contentApplied)) && snapshot.Mode == _activeMode &&
                transitionGeneration == Volatile.Read(ref _modeTransitionGeneration))
                await FadeCatalogContentAsync(fadeOut: false, transitionGeneration: transitionGeneration);
            _modeTransitionGate.Release();
        }
    }

    private async Task FadeCatalogContentAsync(bool fadeOut, long transitionGeneration)
    {
        var visual = ElementCompositionPreview.GetElementVisual(ContentState);
        visual.StopAnimation(nameof(Visual.Opacity));
        var start = visual.Opacity;
        var end = fadeOut ? 0f : 1f;
        visual.Opacity = start;
        var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(1f, end);
        animation.Duration = TimeSpan.FromMilliseconds(110);
        visual.StartAnimation(nameof(Visual.Opacity), animation);
        await Task.Delay(110);
        if (transitionGeneration != Volatile.Read(ref _modeTransitionGeneration)) return;
        visual.StopAnimation(nameof(Visual.Opacity));
        visual.Opacity = end;
    }

    private void RenderSpotlight(IReadOnlyList<CatalogShelf> shelves)
    {
        var previousCard = _spotlightCard;
        var candidates = GetSpotlightCandidates(shelves);
        SpotlightPreviousButton.Visibility = candidates.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        SpotlightNextButton.Visibility = candidates.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        var card = candidates.FirstOrDefault(candidate => _spotlightByMode.TryGetValue(_activeMode, out var selected) && selected.Id == candidate.Id);
        if (card is null && candidates.Length > 0)
        {
            var seed = $"{SpotlightSessionId}:{DateTime.UtcNow:yyyy-MM-dd}:{_account?.AccountId}:{_activeMode}";
            var candidateIndex = (int)((uint)StringComparer.Ordinal.GetHashCode(seed) % (uint)candidates.Length);
            card = candidates[candidateIndex];
            if (_recentSpotlightIds.Contains(card.Id) && candidates.Length > 1)
                card = candidates[(candidateIndex + 1) % candidates.Length];
            _recentSpotlightIds.Add(card.Id);
            _spotlightByMode[_activeMode] = card;
            if (_recentSpotlightIds.Count > 64) _recentSpotlightIds.Clear();
        }
        var unchanged = card is not null && previousCard?.Id == card.Id && SpotlightArtwork.Source is not null;
        _spotlightCard = card;
        SpotlightPanel.Visibility = card is null ? Visibility.Collapsed : Visibility.Visible;
        RenderSpotlightIndicators(candidates, card is null
            ? -1
            : Array.FindIndex(candidates, candidate => candidate.Id == card.Id));
        if (card is null || unchanged) return;

        StopArtwork(SpotlightArtwork);
        SpotlightArtworkFallback.Visibility = Visibility.Visible;
        SpotlightArtwork.Visibility = Visibility.Visible;
        SpotlightTitle.Text = card.Channel?.DisplayName ?? card.Title;
        SpotlightSubtitle.Text = card.Channel is { } spotlightChannel
            ? SubtitleFor(spotlightChannel.Source.Kind)
            : card.Subtitle;
        SpotlightEyebrow.Text = $"TVIVO SPOTLIGHT - {TypeLabel(card.Channel?.Source.Kind)}";
        SpotlightAction.Content = card.Channel is null ? "Open shelf" : card.Channel.Source.Kind switch
        {
            StreamKind.Movie => "Play movie",
            StreamKind.Series => "Open series",
            _ => "Open channel",
        };
        StartArtwork(SpotlightArtwork, card.ArtworkUrl);
    }

    private static CatalogCard[] GetSpotlightCandidates(IReadOnlyList<CatalogShelf> shelves) => shelves.SelectMany(shelf => shelf.Cards)
        .Where(card => card.Channel is not null)
        .GroupBy(card => card.Id, StringComparer.Ordinal)
        .Select(group => group.First())
        .OrderBy(card => card.Id, StringComparer.Ordinal)
        .ToArray();

    private void SpotlightTimer_Tick(object? sender, object args)
    {
        if (_openShelfId is not null || _renderedSnapshot is not { } snapshot ||
            ContentState.Visibility != Visibility.Visible) return;
        AdvanceSpotlight(1, snapshot);
    }

    private void SpotlightPrevious_Click(object sender, RoutedEventArgs args) => AdvanceSpotlight(-1);

    private void SpotlightNext_Click(object sender, RoutedEventArgs args) => AdvanceSpotlight(1);

    private void AdvanceSpotlight(int delta, CatalogSnapshot? snapshot = null)
    {
        snapshot ??= _renderedSnapshot;
        if (snapshot is null) return;
        var candidates = GetSpotlightCandidates(snapshot.SpotlightShelves);
        if (candidates.Length < 2) return;
        var index = Array.FindIndex(candidates, card => card.Id == _spotlightCard?.Id);
        if (index < 0) index = delta > 0 ? -1 : 0;
        var nextIndex = ((index + delta) % candidates.Length + candidates.Length) % candidates.Length;
        _spotlightByMode[_activeMode] = candidates[nextIndex];
        RenderSpotlight(snapshot.SpotlightShelves);
    }

    private void RenderSpotlightIndicators(IReadOnlyList<CatalogCard> candidates, int selectedIndex)
    {
        SpotlightIndicatorRow.Children.Clear();
        if (selectedIndex < 0 || candidates.Count == 0) return;
        var (firstIndex, visibleCount) = SpotlightIndicatorPolicy.GetVisibleWindow(candidates.Count, selectedIndex);
        var lastIndex = firstIndex + visibleCount;
        for (var index = firstIndex; index < lastIndex; index++)
        {
            var button = new Button
            {
                Content = index == selectedIndex ? "●" : "○",
                Tag = index,
                Width = 20,
                Height = 30,
                MinWidth = 20,
                MinHeight = 30,
                Padding = new Thickness(0),
                Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                Style = (Style)Application.Current.Resources["AppCaptionButtonStyle"],
                Foreground = (Brush)Application.Current.Resources[
                    index == selectedIndex ? "AppAccentBrush" : "AppMutedTextBrush"],
                FontSize = 10,
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button,
                $"Spotlight item {index + 1} of {candidates.Count}");
            ToolTipService.SetToolTip(button, null);
            button.Click += SpotlightIndicator_Click;
            SpotlightIndicatorRow.Children.Add(button);
        }
    }

    private void SpotlightIndicator_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: int index } || _renderedSnapshot is not { } snapshot) return;
        var candidates = GetSpotlightCandidates(snapshot.SpotlightShelves);
        if (index < 0 || index >= candidates.Length) return;
        _spotlightByMode[_activeMode] = candidates[index];
        RenderSpotlight(snapshot.SpotlightShelves);
    }

    private CatalogSnapshot? TryGetCachedSnapshot(ProviderAccount account) =>
        _snapshotCache.TryGetValue(CurrentSnapshotKey(account), out var entry) ? entry.Snapshot : null;

    private bool IsSnapshotStale(ProviderAccount account) =>
        !_lastRefreshAt.TryGetValue((account.AccountId, _activeMode), out var refreshedAt) ||
        DateTimeOffset.UtcNow - refreshedAt >= SnapshotFreshness;

    private CatalogSnapshotKey CurrentSnapshotKey(ProviderAccount account) =>
        new(account.AccountId, _activeMode, _openShelfId ?? string.Empty, _openShelfType?.ToString() ?? string.Empty,
            _selectedGroupId ?? string.Empty, SearchBox.Text ?? string.Empty, _categorySort, _offset);

    private void CacheSnapshot(ProviderAccount account, CatalogSnapshot snapshot)
    {
        var key = new CatalogSnapshotKey(account.AccountId, snapshot.Mode, _openShelfId ?? string.Empty,
            _openShelfType?.ToString() ?? string.Empty, snapshot.SelectedGroupId ?? string.Empty,
            SearchBox.Text ?? string.Empty, _categorySort, snapshot.Offset);
        if (!_snapshotCache.ContainsKey(key)) _snapshotCacheOrder.Enqueue(key);
        _snapshotCache[key] = new CachedCatalogSnapshot(snapshot);
        while (_snapshotCache.Count > SnapshotCacheCapacity && _snapshotCacheOrder.TryDequeue(out var oldest))
            _snapshotCache.Remove(oldest);
    }

    private void InvalidateAccountSnapshots(string accountId)
    {
        foreach (var key in _snapshotCache.Keys.Where(key => key.AccountId == accountId).ToArray())
            _snapshotCache.Remove(key);
        _snapshotCacheOrder.Clear();
        foreach (var key in _snapshotCache.Keys) _snapshotCacheOrder.Enqueue(key);
        lock (_spotlightShelvesLock)
        {
            foreach (var key in _spotlightShelvesCache.Keys.Where(key => key.AccountId == accountId).ToArray())
                _spotlightShelvesCache.Remove(key);
        }
        foreach (var key in _lastRefreshAt.Keys.Where(key => key.AccountId == accountId).ToArray())
            _lastRefreshAt.Remove(key);
    }

    private void SaveModeState(CatalogMode mode) =>
        _modeStates[mode] = new ModeInteractionState(_openShelfId, _openShelfType, _selectedGroupId, SearchBox.Text ?? string.Empty, _offset);

    // A new page, sort or search is a fresh list: start at the top and forget the position that
    // was remembered for the previous list, so a later re-render cannot scroll back down.
    private void ResetBrowseScroll()
    {
        _browsePositions.Remove((_activeMode, _openShelfId ?? string.Empty));

        void ScrollToTop()
        {
            var scrollViewer = _openShelfId is not null ? FindShelfScrollViewer(OpenShelfGrid) : FindShelfScrollViewer(ShelvesItems);
            scrollViewer?.ChangeView(null, 0, null, true);
        }

        ScrollToTop();
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, ScrollToTop);
    }

    private void CaptureBrowseState()
    {
        var scrollViewer = _openShelfId is not null ? FindShelfScrollViewer(OpenShelfGrid) : FindShelfScrollViewer(ShelvesItems);
        var offset = scrollViewer?.VerticalOffset ?? 0;

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

        _browsePositions[(_activeMode, _openShelfId ?? string.Empty)] = new BrowsePosition(offset, focusedCardId);
    }

    private void RestoreBrowseStateIfMatching(CatalogMode mode)
    {
        var key = (mode, _openShelfId ?? string.Empty);
        if (!_browsePositions.TryGetValue(key, out var position)) return;
        // Deliberately not removed here: a background catalog refresh can re-render this same
        // shelf again shortly after we return (e.g. NotifyVisitRecorded invalidates the snapshot
        // cache on every play), and that second render needs the same remembered position too.
        // It's only replaced by the next real CaptureBrowseState call (a fresh navigation away).
        var openShelfId = _openShelfId;

        // Scrolling to an exact pixel offset right after ItemsSource rebinds can be clamped,
        // because the virtualizing panel hasn't finished measuring its full extent yet. Defer
        // two low-priority dispatch passes so layout settles first, and prefer ScrollIntoView
        // on the remembered card (which handles realization itself) over a raw pixel offset.
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
            DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
            {
                if (openShelfId is not null && position.FocusedCardId is { } cardId)
                {
                    var card = OpenShelfGrid.Items.OfType<CatalogCard>().FirstOrDefault(c => c.Id == cardId);
                    if (card is not null)
                    {
                        OpenShelfGrid.ScrollIntoView(card, ScrollIntoViewAlignment.Leading);
                        // ScrollIntoView queues its own layout pass before the container exists,
                        // and something elsewhere (page-visibility change moving focus off the
                        // unloading Player) can reassign focus around the same time. Poll briefly
                        // instead of guessing a single right moment to focus the container.
                        var attempts = 0;
                        var retryTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80) };
                        retryTimer.Tick += (_, _) =>
                        {
                            attempts++;
                            var container = OpenShelfGrid.ContainerFromItem(card) as Control;
                            if (container is not null)
                                container.Focus(FocusState.Programmatic);
                            var nowFocused = container is not null && ReferenceEquals(FocusManager.GetFocusedElement(XamlRoot), container);
                            if (nowFocused || attempts >= 6)
                                retryTimer.Stop();
                        };
                        retryTimer.Start();
                        return;
                    }
                }

                // Fallback used only when no focused card was captured (e.g. the user scrolled
                // without clicking anything). A single ChangeView here can undershoot because the
                // virtualizing panel may not have measured its full extent yet; retry briefly.
                var scrollViewer = openShelfId is not null ? FindShelfScrollViewer(OpenShelfGrid) : FindShelfScrollViewer(ShelvesItems);
                if (scrollViewer is null || position.ScrollOffset <= 0) return;
                var scrollAttempts = 0;
                var scrollRetryTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80) };
                scrollRetryTimer.Tick += (_, _) =>
                {
                    scrollAttempts++;
                    scrollViewer.ChangeView(null, position.ScrollOffset, null, true);
                    if (Math.Abs(scrollViewer.VerticalOffset - position.ScrollOffset) < 1.0 || scrollAttempts >= 6)
                        scrollRetryTimer.Stop();
                };
                scrollRetryTimer.Start();
            }));
    }

    private void SetSearchTextWithoutReload(string value)
    {
        _suppressSearchChanged = true;
        SearchBox.Text = value;
        _suppressSearchChanged = false;
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(value) ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ArtworkImage_Opened(object sender, RoutedEventArgs args)
    {
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap) || !IsCurrentArtworkTarget(image, load)) return;
        ReleaseArtworkSlot(image, load, "ImageOpened");
        load.Cancellation.Cancel();
        image.Opacity = 1;
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Collapsed;
        LaunchDiagnostics.Write($"Artwork rendered: item={load.ItemKey}; source=ImageOpened; loaded={image.IsLoaded}; attached={image.XamlRoot is not null}");
        _artworkLoads.Remove(image);
        load.Cancellation.Dispose();
    }

    private void ArtworkImage_Failed(object sender, ExceptionRoutedEventArgs args)
    {
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap)) return;
        LogArtworkFailure(ArtworkItemKey(image), "ImageFailed", args.ErrorMessage ?? "No image error details were supplied.");
        ReleaseArtworkSlot(image, load, "ImageFailed");
        load.Cancellation.Cancel();
        image.Opacity = 0;
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Visible;
    }

    private static FrameworkElement? FindArtworkFallback(Image image)
    {
        if (VisualTreeHelper.GetParent(image) is not Grid host) return null;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(host); index++)
            if (VisualTreeHelper.GetChild(host, index) is FrameworkElement child &&
                child.Name is "ArtworkFallback" or "SpotlightArtworkFallback") return child;
        return null;
    }

    private void ArtworkImage_DataContextChanged(FrameworkElement sender, DataContextChangedEventArgs args)
    {
        if (sender is Image image && !ReferenceEquals(image, SpotlightArtwork) && image.IsLoaded)
            StartArtwork(image, (image.DataContext as CatalogCard)?.ArtworkUrl);
    }

    private void ArtworkImage_Loaded(object sender, RoutedEventArgs args)
    {
        if (sender is not Image image) return;
        if (ReferenceEquals(image, SpotlightArtwork) && _openShelfId is not null) return;
        var url = ReferenceEquals(image, SpotlightArtwork)
            ? _spotlightCard?.ArtworkUrl
            : (image.DataContext as CatalogCard)?.ArtworkUrl;
        StartArtwork(image, url);
    }

    private void ShelfContainer_Loaded(object sender, RoutedEventArgs args)
    {
#if DEBUG
        if (sender is FrameworkElement shelf && _realizedShelves.Add(shelf)) LogRealizedShelves();
#endif
    }

    private void ShelfContainer_Unloaded(object sender, RoutedEventArgs args)
    {
#if DEBUG
        if (sender is FrameworkElement shelf && _realizedShelves.Remove(shelf)) LogRealizedShelves();
#endif
    }

#if DEBUG
    private void LogRealizedShelves() => System.Diagnostics.Debug.WriteLine(
        $"Catalog realized shelves={_realizedShelves.Count}, preview cards={_realizedShelves.Sum(element => (element.DataContext as CatalogShelf)?.Cards.Count ?? 0)}, total shelves={ShelvesItems.Items.Count}");
#endif

    private void ArtworkImage_Unloaded(object sender, RoutedEventArgs args)
    {
        if (sender is Image image) StopArtwork(image);
    }

    public void LoadHomeArtwork(Image image) => StartArtwork(image, (image.DataContext as HomePage.HomeShelfCard)?.ArtworkUrl);

    public void UnloadHomeArtwork(Image image) => StopArtwork(image);

    private async void StartArtwork(Image image, string? url)
    {
        var card = ReferenceEquals(image, SpotlightArtwork) ? _spotlightCard : image.DataContext as CatalogCard;
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
        var safeUrl = Uri.TryCreate(url, UriKind.Absolute, out var diagnosticUri)
            ? SanitizeArtworkUri(diagnosticUri)
            : "<missing-or-invalid>";
        if (_artworkDiagnosticsLogged.Add($"{itemKey}|{safeUrl}"))
            LaunchDiagnostics.Write($"Artwork source: item={itemKey}; model=Channel.LogoUri; projection=CatalogCard.ArtworkUrl; target=Image.Source (code-behind, no XAML Source binding); url={safeUrl}");
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https"))
        {
            LogArtworkFailure(itemKey, "InvalidArtworkUri", "The item has no absolute HTTP or HTTPS artwork URL.");
            return;
        }
        var load = new ArtworkLoad();
        load.ItemKey = itemKey;
        load.Url = url!;
        _artworkLoads[image] = load;
        var spotlight = ReferenceEquals(image, SpotlightArtwork);
        var decodeWidth = spotlight ? 600 : homeCard is not null ? 320 : 380;
        var decodeHeight = spotlight ? 336 : homeCard is not null ? 450 : card?.Height == 138 ? 276 : 500;
        var cacheKey = $"{uri.AbsoluteUri}|{decodeWidth}x{decodeHeight}";
        if (TryGetCachedArtworkBitmap(cacheKey, out var cachedBitmap))
        {
            load.Stage = "cache-hit";
            load.Bitmap = cachedBitmap;
            _ = ExpireArtworkAsync(image, load);
            ApplyArtworkToLiveImage(image, load, cachedBitmap, "cache-hit");
            LaunchDiagnostics.Write($"Artwork cache hit: id={load.Id}; item={itemKey}; cacheEntries={_artworkBitmapCache.Count}");
            return;
        }
        LaunchDiagnostics.Write($"Artwork cache miss: item={itemKey}; url={SanitizeArtworkUri(uri)}; decode={decodeWidth}x{decodeHeight}; cacheEntries={_artworkBitmapCache.Count}");

        var stage = "permit-wait";
        try
        {
            load.Stage = stage;
            LaunchDiagnostics.Write($"Artwork request queued: id={load.Id}; item={itemKey}; permitsAvailable={_artworkSlots.CurrentCount}");
            await _artworkSlots.WaitAsync(load.Cancellation.Token);
            load.SlotAcquired = true;
            stage = "http";
            load.Stage = stage;
            LaunchDiagnostics.Write($"Artwork permit acquired: id={load.Id}; item={itemKey}; permitsAvailable={_artworkSlots.CurrentCount}");
            if (!IsCurrentArtworkLoad(image, load, "permit-acquired")) return;

            using var requestCancellation = CancellationTokenSource.CreateLinkedTokenSource(load.Cancellation.Token);
            requestCancellation.CancelAfter(ArtworkRequestTimeout);
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Tvivo/1.0");
            request.Headers.Accept.ParseAdd("image/jpeg,image/png,image/gif,image/*;q=0.8");
            using var response = await ArtworkClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, requestCancellation.Token);
            stage = "response-body";
            load.Stage = stage;
            var responseStatus = $"{(int)response.StatusCode} {response.StatusCode}";
            var contentType = response.Content.Headers.ContentType?.MediaType ?? "unknown";
            response.EnsureSuccessStatusCode();
            var bytes = await response.Content.ReadAsByteArrayAsync(requestCancellation.Token);
            if (bytes.Length == 0 || bytes.Length > 20 * 1024 * 1024)
                throw new InvalidDataException("Artwork image was empty or exceeded the decode limit.");
            if (!IsCurrentArtworkLoad(image, load, "response-body")) return;

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
                writer.WriteBytes(bytes);
                await writer.StoreAsync();
                writer.DetachStream();
            }
            imageStream.Seek(0);
            await bitmap.SetSourceAsync(imageStream);
            if (!IsCurrentArtworkLoad(image, load, "decode")) return;
            load.Bitmap = bitmap;
            CacheArtworkBitmap(cacheKey, bitmap);
            _ = ExpireArtworkAsync(image, load);
            ApplyArtworkToLiveImage(image, load, bitmap, "decoded");
            LaunchDiagnostics.Write($"Artwork downloaded and decoded: id={load.Id}; item={itemKey}; status={responseStatus}; type={contentType}; bytes={bytes.Length}; source=BitmapImage; cacheEntries={_artworkBitmapCache.Count}");
        }
        catch (OperationCanceledException exception)
        {
            var canceledByView = load.Cancellation.IsCancellationRequested;
            var category = canceledByView ? "Canceled" : "HttpTimeout";
            LaunchDiagnostics.Write($"Artwork request ended: id={load.Id}; item={itemKey}; outcome={category}; stage={stage}; permitAcquired={load.SlotAcquired}; HRESULT=0x{exception.HResult:X8}");
            if (!canceledByView)
                LogArtworkFailure(itemKey, category, $"stage={stage}; timeout={ArtworkRequestTimeout.TotalSeconds:0}s; HRESULT=0x{exception.HResult:X8}");
        }
        catch (Exception exception)
        {
            var status = exception is HttpRequestException requestException
                ? requestException.StatusCode?.ToString() ?? "none"
                : "not-http";
            LogArtworkFailure(itemKey, exception.GetType().Name,
                $"url={SanitizeArtworkUri(uri)}; HTTP status={status}; HRESULT=0x{exception.HResult:X8}; message={exception.Message}");
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
        var currentCard = ReferenceEquals(image, SpotlightArtwork) ? _spotlightCard : image.DataContext as CatalogCard;
        if (image.DataContext is HomePage.HomeShelfCard homeCard)
            return $"home:{homeCard.Channel.Source.Kind}:{homeCard.Channel.Id}" == load.ItemKey && homeCard.ArtworkUrl == load.Url;
        return currentCard is not null && currentCard.Id == load.ItemKey && currentCard.ArtworkUrl == load.Url;
    }

    private bool IsCurrentArtworkLoad(Image image, ArtworkLoad load, string stage)
    {
        if (!load.Cancellation.IsCancellationRequested &&
            _artworkLoads.TryGetValue(image, out var current) && ReferenceEquals(current, load)) return true;

        var reason = load.Cancellation.IsCancellationRequested ? "canceled" : "replaced-or-unloaded";
        LaunchDiagnostics.Write($"Artwork result discarded: id={load.Id}; item={load.ItemKey}; stage={stage}; reason={reason}; currentItem={ArtworkItemKey(image)}");
        return false;
    }

    private void ApplyArtworkToLiveImage(Image image, ArtworkLoad load, BitmapImage bitmap, string source)
    {
        if (!_artworkLoads.TryGetValue(image, out var current) || !ReferenceEquals(current, load) ||
            !ReferenceEquals(load.Bitmap, bitmap) || !IsCurrentArtworkTarget(image, load))
        {
            LaunchDiagnostics.Write($"Artwork assignment deferred: id={load.Id}; item={load.ItemKey}; source={source}; loaded={image.IsLoaded}; attached={image.XamlRoot is not null}; currentItem={ArtworkItemKey(image)}");
            return;
        }

        image.Source = bitmap;
        image.Opacity = 1;
        _appliedArtwork[image] = new AppliedArtwork(load.ItemKey, load.Url, bitmap);
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Collapsed;
        LaunchDiagnostics.Write($"Artwork applied: id={load.Id}; item={load.ItemKey}; source={source}; loaded={image.IsLoaded}; attached={image.XamlRoot is not null}");
    }

    private string ArtworkItemKey(Image image) =>
        image.DataContext is HomePage.HomeShelfCard homeCard
            ? $"home:{homeCard.Channel.Source.Kind}:{homeCard.Channel.Id}"
            : (ReferenceEquals(image, SpotlightArtwork) ? _spotlightCard : image.DataContext as CatalogCard)?.Id ?? "unresolved";

    private string SanitizeArtworkUri(Uri uri)
    {
        var safeUri = new UriBuilder(uri) { UserName = string.Empty, Password = string.Empty, Query = string.Empty, Fragment = string.Empty }.Uri.ToString();
        if (_connection is { } connection)
        {
            foreach (var secret in new[] { connection.Username, connection.Password }.Where(value => !string.IsNullOrEmpty(value)))
                safeUri = safeUri.Replace(Uri.EscapeDataString(secret), "***", StringComparison.OrdinalIgnoreCase);
        }
        return safeUri;
    }

    private void LogArtworkFailure(string itemKey, string category, string details)
    {
        if (!_artworkFailuresLogged.Add($"{itemKey}|{category}")) return;
        var safeDetails = System.Text.RegularExpressions.Regex.Replace(details, @"https?://\S+", "[image URL]");
        LaunchDiagnostics.Write($"Artwork load failed: item={itemKey}; {category}; {safeDetails}");
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
            LaunchDiagnostics.Write($"Artwork request cancelled: id={load.Id}; item={load.ItemKey}; stage={load.Stage}; permitAcquired={load.SlotAcquired}; source=element-unloaded-or-rebound");
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
        if (!load.SlotAcquired || load.SlotReleased) return;
        load.SlotReleased = true;
        _artworkSlots.Release();
        LaunchDiagnostics.Write($"Artwork permit released: id={load.Id}; item={load.ItemKey}; reason={reason}; permitsAvailable={_artworkSlots.CurrentCount}");
    }

    private void CancelArtworkLoads()
    {
        foreach (var image in _artworkLoads.Keys.ToArray()) StopArtwork(image);
    }

    private void RenderShelfSurface(CatalogSnapshot snapshot)
    {
        var openShelf = _openShelfId is null ? null : snapshot.Shelves.FirstOrDefault(shelf => shelf.Id == _openShelfId);
        var isSearch = snapshot.IsFlatSearch;
        var isOpen = openShelf is not null;
        SearchResultsPanel.Visibility = isSearch ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfHeader.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfGrid.Visibility = isOpen || isSearch ? Visibility.Visible : Visibility.Collapsed;
        ShelvesItems.Visibility = isOpen || isSearch ? Visibility.Collapsed : Visibility.Visible;
        SpotlightPanel.Visibility = isOpen || isSearch || _spotlightCard is null ? Visibility.Collapsed : Visibility.Visible;
        if (isOpen || isSearch)
            StopArtwork(SpotlightArtwork);
        else if (_spotlightCard is not null && SpotlightArtwork.Source is null)
            StartArtwork(SpotlightArtwork, _spotlightCard.ArtworkUrl);
        PagerPanel.Visibility = isOpen || isSearch ? Visibility.Visible : Visibility.Collapsed;
        PageSearchPanel.Visibility = Visibility.Collapsed;

        if (isSearch)
        {
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
            OpenShelfTitle.Text = openShelf!.Title;
            var type = openShelf!.Type;
            OpenShelfCount.Text = CountLabel(snapshot.Page.TotalCount, snapshot.Mode == CatalogMode.MyTvivo ? "items" : NounFor(type));
            if (!ReferenceEquals(_renderedSnapshot, snapshot) || _renderedOpenShelfId != _openShelfId)
                OpenShelfGrid.ItemsSource = snapshot.Page.Items.Select(channel => ToCard(channel,
                    snapshot.Mode == CatalogMode.MyTvivo ? TypeForSource(channel.Source.Kind) : type,
                    QualityFromText(openShelf!.Title))).ToArray();
        }
        else
        {
            OpenShelfGrid.ItemsSource = null;
            if (!ReferenceEquals(ShelvesItems.ItemsSource, snapshot.Shelves))
                ShelvesItems.ItemsSource = snapshot.Shelves;
        }
    }

    private async void ModeButton_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: string tag } || !Enum.TryParse<CatalogMode>(tag, out var mode)) return;
        await SetModeAsync(mode);
    }

    private async void ShelfTitle_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: CatalogShelf shelf }) return;
        await OpenShelfAsync(shelf);
    }

    private async void ShelfCount_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: CatalogShelf shelf }) return;
        await OpenShelfAsync(shelf);
    }

    private async Task OpenShelfAsync(CatalogShelf shelf)
    {
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        LaunchDiagnostics.Write($"event=catalog.browse.open mode={_activeMode} type={shelf.Type} scope={(shelf.GroupId is null ? "shelf" : "category")}");
        if (_account is { } account)
        {
            RecordShelfVisit(account, shelf.Type, shelf.Id);
            // Only the shelf-LIST view (OpenShelfId empty) bakes HasNewItems into its cached
            // CatalogShelf records, so only that needs invalidating to clear this shelf's NEW
            // badge promptly. A blanket InvalidateCatalogSnapshots() here would wipe every other
            // shelf/mode's cache too, forcing a full DB refetch on every single shelf-open.
            foreach (var key in _snapshotCache.Keys.Where(k => k.AccountId == account.AccountId && k.Mode == _activeMode && k.OpenShelfId.Length == 0).ToArray())
                _snapshotCache.Remove(key);
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
        if (sender is not GridView gridView || !_wiredShelfGrids.Add(gridView))
            return;

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

    private void SpotlightAction_Click(object sender, RoutedEventArgs args)
    {
        if (_spotlightCard?.Channel is { } channel)
        {
            var siblings = _renderedSnapshot?.SpotlightShelves
                .FirstOrDefault(shelf => shelf.Cards.Any(card => card.Id == _spotlightCard.Id))?
                .Cards.Where(card => card.Channel is not null).Select(card => card.Channel!).ToArray();
            RaiseChannelSelected(channel, siblings);
        }
    }

    private void SpotlightArtwork_Tapped(object sender, TappedRoutedEventArgs args) =>
        SpotlightAction_Click(sender, new RoutedEventArgs());

    private async void SearchBox_TextChanged(object sender, TextChangedEventArgs args)
    {
        if (_suppressSearchChanged || !_isReadyForInteraction) return;
        BrowseContextChanging?.Invoke(this, EventArgs.Empty);
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        if (string.IsNullOrWhiteSpace(SearchBox.Text) && _openShelfId is null)
            _selectedGroupId = null;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        ResetBrowseScroll();
    }

    private async void ClearSearch_Click(object sender, RoutedEventArgs args)
    {
        if (!_isReadyForInteraction) return;
        _suppressSearchChanged = true;
        SearchBox.Text = string.Empty;
        _suppressSearchChanged = false;
        _selectedGroupId = null;
        ClearSearchButton.Visibility = Visibility.Collapsed;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        SearchBox.Focus(FocusState.Programmatic);
    }

    private void UpdateSearchCategoryChips(CatalogSnapshot snapshot)
    {
        SearchCategoryChips.Children.Clear();
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
            Background = selected
                ? (Brush)Application.Current.Resources["AppAccentBrush"]
                : (Brush)Application.Current.Resources["AppPanelSurfaceBrush"],
            Foreground = selected
                ? (Brush)Application.Current.Resources["AppDeepBrush"]
                : (Brush)Application.Current.Resources["AppTextBrush"],
            BorderBrush = selected
                ? (Brush)Application.Current.Resources["AppAccentBrush"]
                : (Brush)Application.Current.Resources["AppLineBrush"],
        };
        AutomationProperties.SetName(button, label);
        button.Click += SearchCategoryChip_Click;
        SearchCategoryChips.Children.Add(button);
    }

    private async void SearchCategoryChip_Click(object sender, RoutedEventArgs args)
    {
        if (sender is not Button { Tag: string groupId } || !_isReadyForInteraction) return;
        _selectedGroupId = string.IsNullOrEmpty(groupId) ? null : groupId;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    public DateTimeOffset? LastRefreshAt
    {
        get
        {
            if (_account is not { } account) return null;
            var times = _lastRefreshAt.Where(entry => entry.Key.AccountId == account.AccountId).Select(entry => entry.Value).ToArray();
            return times.Length == 0 ? null : times.Max();
        }
    }

    public Task RefreshNowAsync() => _account is { } account
        ? LoadAsync(account, forceRefresh: true)
        : LoadSavedAsync();

    public async void SetCategorySort(string? tag)
    {
        if (!_isReadyForInteraction || !Enum.TryParse<CategorySort>(tag, out var sort) || sort == _categorySort) return;
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
                cached = new CachedCatalogSnapshot(read);
            }
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            await ApplySnapshotAsync(cached.Snapshot, () => IsCurrentPageRequest(requestGeneration, account));
        }
        catch (Exception exception)
        {
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            RefreshInfoBar.Message = $"Couldn't update this page. Showing previously loaded channels. {FormatRefreshError(exception)}";
            RefreshInfoBar.Severity = InfoBarSeverity.Warning;
            RefreshRetryButton.Visibility = Visibility.Visible;
            RefreshInfoBar.IsOpen = true;
        }
    }

    private bool IsCurrentPageRequest(long requestGeneration, ProviderAccount account) =>
        requestGeneration == Volatile.Read(ref _pageQueryGeneration) &&
        _account?.AccountId == account.AccountId;

    private void ShowRefreshingStatus()
    {
        RefreshInfoBar.Message = "Refreshing catalog...";
        RefreshInfoBar.Severity = InfoBarSeverity.Informational;
        RefreshRetryButton.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = true;
    }

    private void ShowRefreshFailure(Exception exception)
    {
        RefreshInfoBar.Message = $"Couldn't refresh. Showing saved channels. {FormatRefreshError(exception)}";
        RefreshInfoBar.Severity = InfoBarSeverity.Warning;
        RefreshRetryButton.Visibility = Visibility.Visible;
        RefreshInfoBar.IsOpen = true;
        SetState(content: true);
    }

    private static string FormatRefreshError(Exception exception)
    {
        var message = exception.Message;
        // Provider request URLs can contain credentials. Keep actionable error text without logging URLs.
        message = System.Text.RegularExpressions.Regex.Replace(message, @"https?://\S+", "[provider URL]");
        return $"{exception.GetType().Name}: {message}";
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
            var columns = Math.Max(1, (int)Math.Floor((gridView.ActualWidth + 18) / (190 + 18)));
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
            ? (Brush)Application.Current.Resources["AppDeepBrush"]
            : (Brush)Application.Current.Resources["AppTextBrush"];
        button.Background = selected
            ? (Brush)Application.Current.Resources["AppAccentBrush"]
            : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

#if DEBUG
    private static Channel CreateLocalFixtureChannel() => new(
        "local-fixture", "gate-9-local-fixture", null, "Gate 9 local fixture", "Gate 9 local fixture",
        null, null, int.MaxValue,
        new StreamSource("gate-9-local-fixture", StreamKind.Movie, DirectUri: new Uri(LocalFixturePath!)),
        new Dictionary<string, string>());

    private static string? FindFixturePath()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var fixturePath = Path.Combine(directory.FullName, "windows-spike", "tests", "Gate9", "thirtyfive-second-h264.mp4");
            if (File.Exists(fixturePath)) return fixturePath;
        }
        return null;
    }
#endif

#if DEBUG
    private void LocalFixtureButton_Click(object sender, RoutedEventArgs args) =>
        RaiseChannelSelected(CreateLocalFixtureChannel());
#endif

    private async void Retry_Click(object sender, RoutedEventArgs args)
    {
        if (_account is not null) await LoadAsync(_account, forceRefresh: true);
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
        FirstLoadTakeover.Visibility = Visibility.Collapsed;
        FirstLoadTakeover.Opacity = 1;
        RefreshInfoBar.IsOpen = false;
        ErrorMessage.Text = message;
        SetState(error: true);
    }

    private void SetState(bool loading = false, bool empty = false, bool error = false, bool content = false)
    {
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
            FirstLoadTakeover.Visibility = Visibility.Collapsed;
            FirstLoadTakeover.Opacity = 1;
        };
        storyboard.Begin();
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
    private sealed record CachedCatalogSnapshot(CatalogSnapshot Snapshot);
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
    }
    private sealed record CachedArtworkBitmap(string CacheKey, BitmapImage Bitmap, long DecodedBytes);
    private sealed record ModeInteractionState(string? OpenShelfId, CatalogItemType? OpenShelfType, string? SelectedGroupId, string Filter, int Offset);

    private sealed record BrowsePosition(double ScrollOffset, string? FocusedCardId);

    private sealed record CatalogShelf(
        string Id,
        string Title,
        string CountLabel,
        IReadOnlyList<CatalogCard> Cards,
        CatalogItemType Type,
        string? GroupId,
        bool OpensPagedGrid,
        DateTimeOffset? LatestAddedAt,
        bool HasNewItems)
    {
        public Visibility NewSinceLastVisitVisibility => HasNewItems ? Visibility.Visible : Visibility.Collapsed;
    }

    private sealed class CatalogCard(string id, string title, string subtitle, string? artworkUrl,
        double width, double height, Channel? channel, string? qualityBadge) : INotifyPropertyChanged
    {
        private EpgNowNext? _nowNext;

        public string Id { get; } = id;
        public string Title { get; } = title;
        public string Subtitle { get; } = subtitle;
        public string? ArtworkUrl { get; } = artworkUrl;
        public double Width { get; } = width;
        public double Height { get; } = height;
        public Channel? Channel { get; } = channel;
        public string? QualityBadge { get; } = qualityBadge;
        public Visibility QualityBadgeVisibility => string.IsNullOrWhiteSpace(QualityBadge) ? Visibility.Collapsed : Visibility.Visible;
        public string NowText => _nowNext?.Now is { } now ? $"Now: {now.Title}" : string.Empty;
        public Visibility NowVisibility => _nowNext?.Now is null ? Visibility.Collapsed : Visibility.Visible;
        public double ProgressValue => _nowNext?.Now is { } now
            ? Math.Clamp((DateTimeOffset.UtcNow - now.StartUtc).TotalSeconds /
                Math.Max(1, (now.EndUtc - now.StartUtc).TotalSeconds) * 100, 0, 100)
            : 0;
        public Visibility ProgressVisibility => _nowNext?.Now is null ? Visibility.Collapsed : Visibility.Visible;
        public string NextText => _nowNext?.Next is { } next
            ? $"Next: {next.Title} · {FormatLocalTime(next.StartUtc)}"
            : string.Empty;
        public Visibility NextVisibility => _nowNext?.Next is null ? Visibility.Collapsed : Visibility.Visible;
        public Visibility EpgVisibility => _nowNext?.Now is null && _nowNext?.Next is null
            ? Visibility.Collapsed
            : Visibility.Visible;
        public string EpgAutomationName
        {
            get
            {
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
            Channel.Metadata.TryGetValue("rating", out var rating) && RatingDisplayFormatter.Format(rating) is { } formattedRating ? $"★ {formattedRating}" : null,
            Channel.Metadata.GetValueOrDefault("genre"),
        }.Where(value => !string.IsNullOrWhiteSpace(value)));
        public string HoverPreviewText
        {
            get
            {
                var details = Channel?.Metadata;
                return string.Join(Environment.NewLine + Environment.NewLine, new[]
                {
                    Title,
                    DetailsLine,
                    details?.GetValueOrDefault("plot"),
                    details is not null && !string.IsNullOrWhiteSpace(details.GetValueOrDefault("cast"))
                        ? $"Cast: {details.GetValueOrDefault("cast")}"
                        : null,
                }.Where(value => !string.IsNullOrWhiteSpace(value)));
            }
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        public void ApplyNowNext(EpgNowNext? nowNext)
        {
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
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HoverPreviewText)));
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
