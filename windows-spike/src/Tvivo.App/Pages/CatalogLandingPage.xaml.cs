using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;
using System.ComponentModel;
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
    private ProviderAccount? _account;
    private ProviderConnection? _connection;
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private string? _selectedGroupId;
    private string? _openShelfId;
    private CatalogItemType? _openShelfType;
    private CatalogMode _activeMode = CatalogMode.MyTvivo;
    private CategorySort _categorySort = CategorySort.Visited;
    private int _offset;
    private long _loadGeneration;
    private long _pageQueryGeneration;
    private bool _suppressSearchChanged;
    private bool _isReadyForInteraction;
    private bool _firstCatalogLoadCompleted;
    private bool _showModeLoadingFrame;
    private CatalogCard? _spotlightCard;
    private readonly Dictionary<CatalogSnapshotKey, CachedCatalogSnapshot> _snapshotCache = new();
    private readonly Queue<CatalogSnapshotKey> _snapshotCacheOrder = new();
    private readonly Dictionary<(string AccountId, CatalogMode Mode), DateTimeOffset> _lastRefreshAt = new();
    private readonly Dictionary<CatalogMode, ModeInteractionState> _modeStates = new();
    private readonly HashSet<string> _recentSpotlightIds = new(StringComparer.Ordinal);
    private readonly Dictionary<CatalogMode, CatalogCard> _spotlightByMode = new();
    private static readonly string SpotlightSessionId = Guid.NewGuid().ToString("N");
    private const int SnapshotCacheCapacity = 16;
    private static readonly TimeSpan SnapshotFreshness = TimeSpan.FromMinutes(15);
    private readonly Dictionary<string, Task> _activeRefreshTasks = new(StringComparer.Ordinal);
    private readonly HashSet<GridView> _wiredShelfGrids = new();
    private readonly SemaphoreSlim _artworkSlots = new(6);
    private static readonly HttpClient ArtworkClient = new();
    private static int _firstArtworkFailureLogged;
    private readonly Dictionary<Image, ArtworkLoad> _artworkLoads = new();
    private readonly DispatcherTimer _spotlightTimer = new() { Interval = TimeSpan.FromSeconds(8) };
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
    private const int PageSize = 100;
    private const int ShelfPreviewSize = 12;
    private const string GenericLoadError = "Couldn't load the catalog. Check your connection and provider details, then retry.";
    public ProviderAccount? Account => _account;
    public CatalogMode ActiveMode => _activeMode;
    public bool IsReadyForInteraction => _isReadyForInteraction;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;
    public event EventHandler? InteractionReadinessChanged;

    public CatalogLandingPage()
    {
        InitializeComponent();
        _spotlightTimer.Tick += SpotlightTimer_Tick;
        UpdateModeChrome();
#if DEBUG
        if (LocalFixturePath is not null)
        {
            var fixtureButton = new Button
            {
                Content = "Debug: Gate 9 fixture",
                HorizontalAlignment = HorizontalAlignment.Left,
                Style = (Style)Application.Current.Resources["AppQuietButtonStyle"],
            };
            fixtureButton.Click += LocalFixtureButton_Click;
            ModeButtonStackPanel.Children.Add(fixtureButton);
        }
#endif
    }

    public void SetActive(bool active)
    {
        if (active) _spotlightTimer.Start();
        else _spotlightTimer.Stop();
    }

    public void InvalidateCatalogSnapshots()
    {
        _snapshotCache.Clear();
        _snapshotCacheOrder.Clear();
        _renderedSnapshot = null;
        _renderedOpenShelfId = null;
    }

    public void Shutdown()
    {
        _spotlightTimer.Stop();
        _spotlightTimer.Tick -= SpotlightTimer_Tick;
        CancelArtworkLoads();
    }

    public void PrepareMode(CatalogMode mode)
    {
        if (_activeMode == mode)
        {
            // Same-mode navigation still starts a fresh cache-first load. Keep a
            // ready snapshot interactive, but don't leave an empty/error surface
            // looking ready while that load is about to retry.
            if (ContentState.Visibility != Visibility.Visible && LoadingState.Visibility != Visibility.Visible)
                SetState(loading: true);
            return;
        }

        // Invalidate any load tied to the previous mode before replacing its UI.
        Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        SaveModeState(_activeMode);
        _activeMode = mode;
        _showModeLoadingFrame = true;
        _spotlightCard = null;
        _openShelfType = null;
        if (_modeStates.TryGetValue(mode, out var state))
        {
            _openShelfId = state.OpenShelfId;
            _openShelfType = state.OpenShelfType;
            _selectedGroupId = state.SelectedGroupId;
            _offset = state.Offset;
            SetSearchTextWithoutReload(state.Filter);
            SetSort(state.Sort);
        }
        else
        {
            _openShelfId = null;
            _openShelfType = null;
            _selectedGroupId = null;
            _offset = 0;
            SetSearchTextWithoutReload(string.Empty);
        }
        ShelvesItems.ItemsSource = null;
        _renderedSnapshot = null;
        CancelArtworkLoads();
        OpenShelfGrid.ItemsSource = null;
        SpotlightPanel.Visibility = Visibility.Collapsed;
        RefreshInfoBar.IsOpen = false;
        UpdateModeChrome();
        UpdateEmptyCopy();
        SetState(loading: true);
    }

    public async Task SetModeAsync(CatalogMode mode)
    {
        if (_activeMode == mode)
            return;

        PrepareMode(mode);
        await Task.Delay(32);
        await ShowCachedPageAsync();
    }

    public void SetSearchText(string text)
    {
        if (!_isReadyForInteraction)
            return;
        if (SearchBox.Text == text)
            return;

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
            if (!IsCurrentLoad(generation)) return;
            if (cached is null) return;

            if (HasCatalogData(cached))
            {
                ApplySnapshot(cached);
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
                RefreshInfoBar.IsOpen = false;
                DismissFirstLoadTakeover();
                return;
            }

            await RefreshAccountAsync(account);
            if (!IsCurrentLoad(generation)) return;
            InvalidateAccountSnapshots(account.AccountId);
            var refreshedAt = DateTimeOffset.UtcNow;
            foreach (var mode in Enum.GetValues<CatalogMode>())
                _lastRefreshAt[(account.AccountId, mode)] = refreshedAt;

            var refreshed = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (!IsCurrentLoad(generation)) return;
            if (refreshed is null) return;

            CacheSnapshot(account, refreshed);
            ApplySnapshot(refreshed);
            _firstCatalogLoadCompleted = true;
            DismissFirstLoadTakeover();
            RefreshInfoBar.IsOpen = false;
        }
        catch (Exception exception)
        {
            if (!IsCurrentLoad(generation)) return;
            LaunchDiagnostics.Write($"Catalog load failed: {FormatRefreshError(exception)}");
            if (ContentState.Visibility == Visibility.Visible)
                ShowRefreshFailure(exception);
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
        return await Task.Run(() =>
        {
            if (mode == CatalogMode.MyTvivo)
            {
                var myShelves = BuildShelves(account, Array.Empty<ChannelGroup>(), filter, mode, sort);
                if (openShelfId is not null && openShelfType is { } shelfType)
                {
                    var first = _repository.GetChannels(account, _connection, shelfType, filter: filter, offset: 0, limit: PageSize, mostVisited: true);
                    var lastOffset = first.TotalCount == 0 ? 0 : ((first.TotalCount - 1) / PageSize) * PageSize;
                    var shelfOffset = Math.Clamp(requestedOffset, 0, lastOffset);
                    var shelfPage = shelfOffset == 0 ? first : _repository.GetChannels(account, _connection, shelfType, filter: filter, offset: shelfOffset, limit: PageSize, mostVisited: true);
                    return new CatalogSnapshot(mode, Array.Empty<ChannelGroup>(), null, shelfOffset, shelfPage, myShelves);
                }
                return new CatalogSnapshot(mode, Array.Empty<ChannelGroup>(), null, 0, new CatalogPage(Array.Empty<Channel>(), 0), myShelves);
            }

            var type = TypeForMode(mode);
            var groups = _repository.GetGroups(account, type);
            var groupId = requestedGroupId is not null && groups.Any(group => group.Id == requestedGroupId)
                ? requestedGroupId
                : null;
            var firstPage = _repository.GetChannels(account, _connection, type, groupId, filter, 0, PageSize);
            var lastValidOffset = firstPage.TotalCount == 0
                ? 0
                : ((firstPage.TotalCount - 1) / PageSize) * PageSize;
            var offset = Math.Clamp(requestedOffset, 0, lastValidOffset);
            var page = offset == 0
                ? firstPage
                : _repository.GetChannels(account, _connection, type, groupId, filter, offset, PageSize);
            var shelves = BuildShelves(account, groups, filter, mode, sort);
            return new CatalogSnapshot(mode, groups, groupId, offset, page, shelves);
        });
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
        var allChannels = _repository.GetChannels(account, _connection, type, filter: filter, offset: 0, limit: ShelfPreviewSize);
        if (allChannels.Items.Count > 0)
        {
            shelves.Add(new CatalogShelf(
                "__recent",
                "Recently added",
                CountLabel(allChannels.TotalCount, noun),
                allChannels.Items.Select(channel => ToCard(channel, type)).ToArray(),
                type,
                null,
                false));
        }

        var pages = _repository.GetChannelsGroupedByCategory(account, _connection, type, filter, ShelfPreviewSize);
        foreach (var group in SortGroups(groups, sort))
        {
            if (!pages.TryGetValue(group.Id, out var page)) continue;
            shelves.Add(new CatalogShelf(
                group.Id,
                group.DisplayName,
                CountLabel(page.TotalCount, noun),
                page.Items.Select(channel => ToCard(channel, type)).ToArray(),
                type,
                group.Id,
                true));
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

    private IReadOnlyList<CatalogShelf> BuildMyTvivoShelves(ProviderAccount account, string? filter)
    {
        var shelves = new List<CatalogShelf>();
        foreach (var (type, title) in new[]
        {
            (CatalogItemType.Movie, "Recent movies"),
            (CatalogItemType.Series, "Recent series"),
            (CatalogItemType.Live, "Recent live TV"),
        })
        {
            var page = _repository.GetChannels(account, _connection, type, filter: filter, offset: 0, limit: ShelfPreviewSize,
                mostVisited: true);
            if (page.TotalCount == 0) continue;
            shelves.Add(new CatalogShelf(
                $"__my_{type}",
                title,
                CountLabel(page.TotalCount, NounFor(type)),
                page.Items.Select(channel => ToCard(channel, type)).ToArray(),
                type,
                null,
                false));
        }

        return shelves;
    }

    private static CatalogCard ToCard(Channel channel, CatalogItemType type) => new(
        $"{channel.ProviderAccountId}:{channel.Source.Kind}:{channel.Id}",
        channel.DisplayName,
        SubtitleFor(type),
        channel.LogoUri?.ToString(),
        190,
        type == CatalogItemType.Live ? 138 : 250,
        channel);

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

    private static string CountLabel(int count, string noun) =>
        count == 1 ? $"1 {noun.TrimEnd('s')}" : $"{count} {noun}";

    private static bool HasCatalogData(CatalogSnapshot snapshot) =>
        snapshot.Groups.Count > 0 || snapshot.Page.TotalCount > 0 || snapshot.Shelves.Count > 0;

    private void ApplySnapshot(CatalogSnapshot snapshot)
    {
        Interlocked.Increment(ref _pageQueryGeneration);
        _activeMode = snapshot.Mode;
        _groups = snapshot.Groups;
        _selectedGroupId = snapshot.SelectedGroupId;
        _offset = snapshot.Offset;
        SaveModeState(snapshot.Mode);

        UpdateModeChrome();
        UpdateEmptyCopy();
        var snapshotChanged = !ReferenceEquals(_renderedSnapshot, snapshot);
        if (snapshotChanged || _renderedOpenShelfId != _openShelfId)
        {
            if (snapshotChanged) RenderSpotlight(snapshot.Shelves);
            RenderShelfSurface(snapshot);
            _renderedSnapshot = snapshot;
            _renderedOpenShelfId = _openShelfId;
        }
        UpdatePaging(snapshot.Page.TotalCount);

        if (snapshot.Shelves.Count == 0)
            SetState(empty: true);
        else
            SetState(content: true);
    }

    private void RenderSpotlight(IReadOnlyList<CatalogShelf> shelves)
    {
        var candidates = shelves.SelectMany(shelf => shelf.Cards)
            .Where(card => card.Channel is not null)
            .GroupBy(card => card.Id, StringComparer.Ordinal)
            .Select(group => group.First())
            .OrderBy(card => card.Id, StringComparer.Ordinal)
            .ToArray();
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
        _spotlightCard = card;
        SpotlightPanel.Visibility = card is null ? Visibility.Collapsed : Visibility.Visible;
        if (card is null) return;

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

    private void SpotlightTimer_Tick(object? sender, object args)
    {
        if (_openShelfId is not null || _renderedSnapshot is not { } snapshot ||
            ContentState.Visibility != Visibility.Visible) return;
        var candidates = snapshot.Shelves.SelectMany(shelf => shelf.Cards)
            .Where(card => card.Channel is not null)
            .GroupBy(card => card.Id, StringComparer.Ordinal)
            .Select(group => group.First())
            .OrderBy(card => card.Id, StringComparer.Ordinal)
            .ToArray();
        if (candidates.Length < 2) return;
        var index = Array.FindIndex(candidates, card => card.Id == _spotlightCard?.Id);
        _spotlightByMode[_activeMode] = candidates[(index + 1 + candidates.Length) % candidates.Length];
        RenderSpotlight(snapshot.Shelves);
        StartArtwork(SpotlightArtwork, _spotlightCard?.ArtworkUrl);
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
        foreach (var key in _lastRefreshAt.Keys.Where(key => key.AccountId == accountId).ToArray())
            _lastRefreshAt.Remove(key);
    }

    private void SaveModeState(CatalogMode mode) =>
        _modeStates[mode] = new ModeInteractionState(_openShelfId, _openShelfType, _selectedGroupId, SearchBox.Text ?? string.Empty, _offset, _categorySort);

    private void SetSearchTextWithoutReload(string value)
    {
        _suppressSearchChanged = true;
        SearchBox.Text = value;
        _suppressSearchChanged = false;
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(value) ? Visibility.Collapsed : Visibility.Visible;
    }

    private void SetSort(CategorySort sort)
    {
        _categorySort = sort;
        foreach (var item in CategorySortBox.Items.OfType<ComboBoxItem>())
            item.IsSelected = string.Equals(item.Tag?.ToString(), sort.ToString(), StringComparison.Ordinal);
    }

    private void ArtworkImage_Opened(object sender, RoutedEventArgs args)
    {
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap)) return;
        ReleaseArtworkSlot(image, load);
        load.Cancellation.Cancel();
        image.Opacity = 1;
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Collapsed;
    }

    private void ArtworkImage_Failed(object sender, ExceptionRoutedEventArgs args)
    {
        if (sender is not Image image || !_artworkLoads.TryGetValue(image, out var load) ||
            !ReferenceEquals(image.Source, load.Bitmap)) return;
        LogFirstArtworkFailure("ImageFailed", args.ErrorMessage ?? "No image error details were supplied.");
        ReleaseArtworkSlot(image, load);
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

    private async void StartArtwork(Image image, string? url)
    {
        StopArtwork(image);
        if (FindArtworkFallback(image) is { } fallback) fallback.Visibility = Visibility.Visible;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https")) return;
        var load = new ArtworkLoad();
        _artworkLoads[image] = load;
        try
        {
            await _artworkSlots.WaitAsync(load.Cancellation.Token);
            load.SlotAcquired = true;
            if (load.Cancellation.IsCancellationRequested || !_artworkLoads.TryGetValue(image, out var current) ||
                !ReferenceEquals(current, load)) return;

            var spotlight = ReferenceEquals(image, SpotlightArtwork);
            var card = image.DataContext as CatalogCard;
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Tvivo/1.0");
            request.Headers.Accept.ParseAdd("image/jpeg,image/png,image/gif,image/*;q=0.8");
            using var response = await ArtworkClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, load.Cancellation.Token);
            response.EnsureSuccessStatusCode();
            var bytes = await response.Content.ReadAsByteArrayAsync(load.Cancellation.Token);
            if (bytes.Length == 0 || bytes.Length > 20 * 1024 * 1024)
                throw new InvalidDataException("Artwork image was empty or exceeded the decode limit.");
            if (load.Cancellation.IsCancellationRequested || !_artworkLoads.TryGetValue(image, out var currentLoad) ||
                !ReferenceEquals(currentLoad, load)) return;

            var bitmap = new BitmapImage
            {
                DecodePixelWidth = spotlight ? 600 : 380,
                DecodePixelHeight = spotlight ? 336 : card?.Height == 138 ? 276 : 500,
            };
            using var imageStream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(imageStream))
            {
                writer.WriteBytes(bytes);
                await writer.StoreAsync();
                writer.DetachStream();
            }
            imageStream.Seek(0);
            await bitmap.SetSourceAsync(imageStream);
            if (load.Cancellation.IsCancellationRequested || !_artworkLoads.TryGetValue(image, out currentLoad) ||
                !ReferenceEquals(currentLoad, load)) return;
            load.Bitmap = bitmap;
            image.Source = bitmap;
            _ = ExpireArtworkAsync(image, load);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            var status = exception is HttpRequestException requestException
                ? requestException.StatusCode?.ToString() ?? "none"
                : "not-http";
            LogFirstArtworkFailure(exception.GetType().Name,
                $"HTTP status={status}; HRESULT=0x{exception.HResult:X8}");
            ReleaseArtworkSlot(image, load);
            load.Cancellation.Cancel();
            if (_artworkLoads.TryGetValue(image, out var activeLoad) && ReferenceEquals(activeLoad, load))
            {
                if (FindArtworkFallback(image) is { } errorFallback) errorFallback.Visibility = Visibility.Visible;
                image.Opacity = 0;
            }
        }
        finally
        {
            if (load.Cancellation.IsCancellationRequested) ReleaseArtworkSlot(image, load);
        }
    }

    private static void LogFirstArtworkFailure(string category, string details)
    {
        if (Interlocked.CompareExchange(ref _firstArtworkFailureLogged, 1, 0) != 0) return;
        var safeDetails = System.Text.RegularExpressions.Regex.Replace(details, @"https?://\S+", "[image URL]");
        LaunchDiagnostics.Write($"Artwork load first failure: {category}; {safeDetails}");
    }

    private async Task ExpireArtworkAsync(Image image, ArtworkLoad load)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), load.Cancellation.Token);
            if (_artworkLoads.TryGetValue(image, out var current) && ReferenceEquals(current, load))
            {
                // The timeout is only a request-slot safeguard. Clearing Source here made
                // every successfully decoded poster disappear after ten seconds.
                _artworkLoads.Remove(image);
                ReleaseArtworkSlot(image, load);
                load.Cancellation.Dispose();
            }
        }
        catch (OperationCanceledException) { }
    }

    private void StopArtwork(Image image)
    {
        if (_artworkLoads.Remove(image, out var load))
        {
            load.Cancellation.Cancel();
            ReleaseArtworkSlot(image, load);
        }
        image.Source = null;
        image.Opacity = 0;
    }

    private void ReleaseArtworkSlot(Image image)
    {
        if (_artworkLoads.TryGetValue(image, out var load))
        {
            ReleaseArtworkSlot(image, load);
            load.Cancellation.Cancel();
        }
    }

    private void ReleaseArtworkSlot(Image image, ArtworkLoad load)
    {
        if (!load.SlotAcquired || load.SlotReleased) return;
        load.SlotReleased = true;
        _artworkSlots.Release();
    }

    private void CancelArtworkLoads()
    {
        foreach (var image in _artworkLoads.Keys.ToArray()) StopArtwork(image);
    }

    private void RenderShelfSurface(CatalogSnapshot snapshot)
    {
        var openShelf = _openShelfId is null ? null : snapshot.Shelves.FirstOrDefault(shelf => shelf.Id == _openShelfId);
        var isOpen = openShelf is not null;
        OpenShelfHeader.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfGrid.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        ShelvesItems.Visibility = isOpen ? Visibility.Collapsed : Visibility.Visible;
        SpotlightPanel.Visibility = isOpen || _spotlightCard is null ? Visibility.Collapsed : Visibility.Visible;
        if (isOpen)
            StopArtwork(SpotlightArtwork);
        else if (_spotlightCard is not null && SpotlightArtwork.Source is null)
            StartArtwork(SpotlightArtwork, _spotlightCard.ArtworkUrl);
        PagerPanel.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        SortPanel.Opacity = isOpen ? 0 : 1;
        SortPanel.IsHitTestVisible = !isOpen;
        PageSearchPanel.Visibility = Visibility.Collapsed;

        if (isOpen)
        {
            OpenShelfTitle.Text = openShelf!.Title;
            var type = openShelf!.Type;
            OpenShelfCount.Text = CountLabel(snapshot.Page.TotalCount, NounFor(type));
            if (!ReferenceEquals(_renderedSnapshot, snapshot) || _renderedOpenShelfId != _openShelfId)
                OpenShelfGrid.ItemsSource = snapshot.Page.Items.Select(channel => ToCard(channel, type)).ToArray();
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
        _openShelfId = shelf.Id;
        _openShelfType = shelf.Type;
        _selectedGroupId = shelf.GroupId;
        _offset = 0;
        await ShowCachedPageAsync();
    }

    private async void BackToShelves_Click(object sender, RoutedEventArgs args)
    {
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
            var siblings = _renderedSnapshot?.Shelves
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
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    private async void ClearSearch_Click(object sender, RoutedEventArgs args)
    {
        if (!_isReadyForInteraction) return;
        _suppressSearchChanged = true;
        SearchBox.Text = string.Empty;
        _suppressSearchChanged = false;
        ClearSearchButton.Visibility = Visibility.Collapsed;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
        SearchBox.Focus(FocusState.Programmatic);
    }

    private async void CategorySortBox_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (!_isReadyForInteraction ||
            CategorySortBox.SelectedItem is not ComboBoxItem { Tag: string tag } ||
            !Enum.TryParse<CategorySort>(tag, out var sort)) return;
        _categorySort = sort;
        if (_account is null) return;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    private async void PreviousPage_Click(object sender, RoutedEventArgs args)
    {
        _offset = Math.Max(0, _offset - PageSize);
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    private async void NextPage_Click(object sender, RoutedEventArgs args)
    {
        _offset += PageSize;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
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
            ApplySnapshot(cached.Snapshot);
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
        PageStatus.Text = total == 0 ? "0 channels" : $"{_offset + 1}-{Math.Min(_offset + PageSize, total)} of {total}";
        PreviousPageButton.IsEnabled = _offset > 0;
        NextPageButton.IsEnabled = _offset + PageSize < total;
    }

    private void UpdateModeChrome()
    {
        (PageEyebrow.Text, PageTitle.Text) = _activeMode switch
        {
            CatalogMode.Movies => ("CATALOG - MOVIES", "Movies"),
            CatalogMode.Series => ("CATALOG - SERIES", "Series"),
            CatalogMode.LiveTv => ("CHANNEL GUIDE", "Live TV"),
            _ => ("YOUR LIBRARY", "My Tvivo"),
        };

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

    private void RaiseChannelSelected(Channel channel, IReadOnlyList<Channel>? related = null) =>
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(channel, related ?? Array.Empty<Channel>()));

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
        CategorySortBox.IsEnabled = ready;
        ModeButtons.IsEnabled = ready;
        if (changed)
            InteractionReadinessChanged?.Invoke(this, EventArgs.Empty);
    }

    private sealed record CatalogSnapshot(
        CatalogMode Mode,
        IReadOnlyList<ChannelGroup> Groups,
        string? SelectedGroupId,
        int Offset,
        CatalogPage Page,
        IReadOnlyList<CatalogShelf> Shelves);

    private sealed record CatalogSnapshotKey(string AccountId, CatalogMode Mode, string OpenShelfId, string OpenShelfType, string GroupId, string Filter, CategorySort Sort, int Offset);
    private sealed record CachedCatalogSnapshot(CatalogSnapshot Snapshot);
    private sealed class ArtworkLoad
    {
        public CancellationTokenSource Cancellation { get; } = new();
        public BitmapImage? Bitmap { get; set; }
        public bool SlotAcquired { get; set; }
        public bool SlotReleased { get; set; }
    }
    private sealed record ModeInteractionState(string? OpenShelfId, CatalogItemType? OpenShelfType, string? SelectedGroupId, string Filter, int Offset, CategorySort Sort);

    private sealed record CatalogShelf(
        string Id,
        string Title,
        string CountLabel,
        IReadOnlyList<CatalogCard> Cards,
        CatalogItemType Type,
        string? GroupId,
        bool OpensPagedGrid);

    private sealed class CatalogCard(string id, string title, string subtitle, string? artworkUrl,
        double width, double height, Channel? channel) : INotifyPropertyChanged
    {
        public string Id { get; } = id;
        public string Title { get; } = title;
        public string Subtitle { get; } = subtitle;
        public string? ArtworkUrl { get; } = artworkUrl;
        public double Width { get; } = width;
        public double Height { get; } = height;
        public Channel? Channel { get; } = channel;
        public string DetailsLine => Channel is null ? string.Empty : string.Join(" · ", new[]
        {
            Channel.Metadata.GetValueOrDefault("year"),
            Channel.Metadata.TryGetValue("rating", out var rating) ? $"★ {rating}" : null,
            Channel.Metadata.GetValueOrDefault("genre"),
        }.Where(value => !string.IsNullOrWhiteSpace(value)));

        public event PropertyChangedEventHandler? PropertyChanged;
        public void NotifyMetadataChanged() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(DetailsLine)));
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
