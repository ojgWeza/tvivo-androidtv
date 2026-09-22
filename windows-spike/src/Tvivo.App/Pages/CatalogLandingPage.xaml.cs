using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
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
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private string? _selectedGroupId;
    private string? _openShelfId;
    private CatalogMode _activeMode = CatalogMode.MyTvivo;
    private CategorySort _categorySort = CategorySort.Visited;
    private int _offset;
    private long _loadGeneration;
    private long _pageQueryGeneration;
    private bool _suppressSearchChanged;
    private CatalogCard? _spotlightCard;
    private readonly Dictionary<string, Task> _activeRefreshTasks = new(StringComparer.Ordinal);
    private const int PageSize = 100;
    private const int ShelfPreviewSize = 12;
    private const string GenericLoadError = "Couldn't load the catalog. Check your connection and provider details, then retry.";
    public ProviderAccount? Account => _account;
    public CatalogMode ActiveMode => _activeMode;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;

    public CatalogLandingPage()
    {
        InitializeComponent();
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
            ModeButtons.Children.Add(fixtureButton);
        }
#endif
    }

    public void PrepareMode(CatalogMode mode)
    {
        if (_activeMode == mode)
            return;

        Interlocked.Increment(ref _pageQueryGeneration);
        _activeMode = mode;
        _openShelfId = null;
        _selectedGroupId = null;
        _offset = 0;
        _spotlightCard = null;
        ShelvesItems.ItemsSource = null;
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
        await ShowCachedPageAsync(mode, SearchBox.Text, null, 0);
    }

    public void SetSearchText(string text)
    {
        if (SearchBox.Text == text)
            return;

        _suppressSearchChanged = true;
        SearchBox.Text = text;
        _suppressSearchChanged = false;
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(text) ? Visibility.Collapsed : Visibility.Visible;
        _offset = 0;
        _ = ShowCachedPageAsync(_activeMode, text, _selectedGroupId, 0);
    }

    public async Task LoadAsync(ProviderAccount account)
    {
        var generation = Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        var accountChanged = _account?.AccountId != account.AccountId;
        _account = account;
        if (accountChanged)
        {
            _selectedGroupId = null;
            _openShelfId = null;
            _offset = 0;
            RefreshInfoBar.IsOpen = false;
            SetState(loading: true);
        }
        await LoadCatalogAsync(account, generation);
    }

    public async Task LoadSavedAsync()
    {
        var generation = Interlocked.Increment(ref _loadGeneration);
        Interlocked.Increment(ref _pageQueryGeneration);
        _account = null;
        _selectedGroupId = null;
        _openShelfId = null;
        _offset = 0;
        RefreshInfoBar.IsOpen = false;
        SetState(loading: true);
        try
        {
            var connection = await _credentialStore.LoadAsync();
            if (!IsCurrentLoad(generation)) return;
            if (connection is null)
            {
                ShowError("No saved provider connection. Choose Set up provider from Home and connect an account.");
                return;
            }
            var result = await _provider.AuthenticateAsync(connection);
            if (!IsCurrentLoad(generation)) return;
            if (!result.Success || result.Account is null)
            {
                ShowError(GenericLoadError);
                return;
            }
            _account = result.Account;
            await LoadCatalogAsync(result.Account, generation);
        }
        catch
        {
            if (IsCurrentLoad(generation)) ShowError(GenericLoadError);
        }
    }

    private async Task LoadCatalogAsync(ProviderAccount account, long generation)
    {
        try
        {
            var cached = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (!IsCurrentLoad(generation)) return;
            if (cached is null) return;

            if (HasCatalogData(cached))
            {
                ApplySnapshot(cached);
                ShowRefreshingStatus();
            }
            else
            {
                SetState(loading: true);
            }

            await RefreshAccountAsync(account);
            if (!IsCurrentLoad(generation)) return;

            var refreshed = await ReadCurrentCatalogSnapshotAsync(account, generation);
            if (!IsCurrentLoad(generation)) return;
            if (refreshed is null) return;

            ApplySnapshot(refreshed);
            RefreshInfoBar.IsOpen = false;
        }
        catch
        {
            if (!IsCurrentLoad(generation)) return;
            if (ContentState.Visibility == Visibility.Visible)
                ShowRefreshFailure();
            else
                ShowError(GenericLoadError);
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
        return await Task.Run(() =>
        {
            if (mode == CatalogMode.MyTvivo)
            {
                var myShelves = BuildShelves(account, Array.Empty<ChannelGroup>(), filter, mode, sort);
                return new CatalogSnapshot(mode, Array.Empty<ChannelGroup>(), null, 0, new CatalogPage(Array.Empty<Channel>(), 0), myShelves);
            }

            var type = TypeForMode(mode);
            var groups = _repository.GetGroups(account, type);
            var groupId = requestedGroupId is not null && groups.Any(group => group.Id == requestedGroupId)
                ? requestedGroupId
                : null;
            var firstPage = _repository.GetChannels(account, type, groupId, filter, 0, PageSize);
            var lastValidOffset = firstPage.TotalCount == 0
                ? 0
                : ((firstPage.TotalCount - 1) / PageSize) * PageSize;
            var offset = Math.Clamp(requestedOffset, 0, lastValidOffset);
            var page = offset == 0
                ? firstPage
                : _repository.GetChannels(account, type, groupId, filter, offset, PageSize);
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
                return snapshot;
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
        var allChannels = _repository.GetChannels(account, type, filter: filter, offset: 0, limit: ShelfPreviewSize);
        if (allChannels.Items.Count > 0)
        {
            shelves.Add(new CatalogShelf(
                "__recent",
                "Recently added",
                CountLabel(allChannels.TotalCount, noun),
                allChannels.Items.Select(channel => ToCard(channel, type)).ToArray(),
                null,
                false));
        }

        foreach (var group in SortGroups(groups, sort))
        {
            var page = _repository.GetChannels(account, type, group.Id, filter, 0, ShelfPreviewSize);
            if (page.TotalCount == 0) continue;
            shelves.Add(new CatalogShelf(
                group.Id,
                group.DisplayName,
                CountLabel(page.TotalCount, noun),
                page.Items.Select(channel => ToCard(channel, type)).ToArray(),
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
            var page = _repository.GetChannels(account, type, filter: filter, offset: 0, limit: ShelfPreviewSize);
            if (page.TotalCount == 0) continue;
            shelves.Add(new CatalogShelf(
                $"__my_{type}",
                title,
                CountLabel(page.TotalCount, NounFor(type)),
                page.Items.Select(channel => ToCard(channel, type)).ToArray(),
                null,
                false));
        }

        return shelves;
    }

    private static CatalogCard ToCard(Channel channel, CatalogItemType type) => new(
        channel.DisplayName,
        SubtitleFor(type),
        Initials(channel.DisplayName),
        channel.LogoUri?.ToString(),
        190,
        type == CatalogItemType.Live ? 138 : 250,
        channel);

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

    private static string NounFor(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "movies",
        CatalogItemType.Series => "series",
        _ => "channels",
    };

    private static string Initials(string value)
    {
        var words = value.Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (words.Length == 0) return "TV";
        return string.Concat(words.Take(2).Select(word => char.ToUpperInvariant(word[0])));
    }

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

        UpdateModeChrome();
        UpdateEmptyCopy();
        RenderSpotlight(snapshot.Shelves);
        RenderShelfSurface(snapshot);
        UpdatePaging(snapshot.Page.TotalCount);

        if (snapshot.Shelves.Count == 0)
            SetState(empty: true);
        else
            SetState(content: true);
    }

    private void RenderSpotlight(IReadOnlyList<CatalogShelf> shelves)
    {
        var card = shelves.SelectMany(shelf => shelf.Cards).FirstOrDefault();
        _spotlightCard = card;
        SpotlightPanel.Visibility = card is null ? Visibility.Collapsed : Visibility.Visible;
        if (card is null) return;

        SpotlightInitials.Text = card.Initials;
        SpotlightTitle.Text = card.Title;
        SpotlightSubtitle.Text = card.Subtitle;
        SpotlightEyebrow.Text = _activeMode switch
        {
            CatalogMode.Movies => "TVIVO SPOTLIGHT - MOVIE",
            CatalogMode.Series => "TVIVO SPOTLIGHT - SERIES",
            _ => "TVIVO SPOTLIGHT - LIVE TV",
        };
        SpotlightAction.Content = card.Channel is null ? "Open shelf" : "Open channel";
    }

    private void RenderShelfSurface(CatalogSnapshot snapshot)
    {
        var openShelf = _openShelfId is null ? null : snapshot.Shelves.FirstOrDefault(shelf => shelf.Id == _openShelfId);
        var isOpen = openShelf is not null;
        OpenShelfHeader.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        OpenShelfGrid.Visibility = isOpen ? Visibility.Visible : Visibility.Collapsed;
        ShelvesScroll.Visibility = isOpen ? Visibility.Collapsed : Visibility.Visible;
        SpotlightPanel.Visibility = isOpen ? Visibility.Collapsed : SpotlightPanel.Visibility;
        PagerPanel.Visibility = isOpen && openShelf!.GroupId is not null ? Visibility.Visible : Visibility.Collapsed;
        SortPanel.Visibility = isOpen ? Visibility.Collapsed : Visibility.Visible;
        PageSearchPanel.Visibility = Visibility.Collapsed;

        if (isOpen)
        {
            OpenShelfTitle.Text = openShelf!.Title;
            var type = TypeForMode(_activeMode);
            OpenShelfCount.Text = CountLabel(snapshot.Page.TotalCount, NounFor(type));
            OpenShelfGrid.ItemsSource = snapshot.Page.Items.Select(channel => ToCard(channel, type)).ToArray();
        }
        else
        {
            OpenShelfGrid.ItemsSource = null;
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
        _openShelfId = shelf.Id;
        _selectedGroupId = shelf.GroupId;
        _offset = 0;
        await ShowCachedPageAsync();
    }

    private async void BackToShelves_Click(object sender, RoutedEventArgs args)
    {
        _openShelfId = null;
        _selectedGroupId = null;
        _offset = 0;
        await ShowCachedPageAsync();
    }

    private void ShelfCards_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is CatalogCard { Channel: { } channel })
            RaiseChannelSelected(channel);
    }

    private void SpotlightAction_Click(object sender, RoutedEventArgs args)
    {
        if (_spotlightCard?.Channel is { } channel)
            RaiseChannelSelected(channel);
    }

    private async void SearchBox_TextChanged(object sender, TextChangedEventArgs args)
    {
        if (_suppressSearchChanged) return;
        ClearSearchButton.Visibility = string.IsNullOrWhiteSpace(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        _offset = 0;
        await ShowCachedPageAsync(_activeMode, SearchBox.Text, _selectedGroupId, _offset);
    }

    private async void ClearSearch_Click(object sender, RoutedEventArgs args)
    {
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
        if (CategorySortBox.SelectedItem is not ComboBoxItem { Tag: string tag } ||
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
            var snapshot = await ReadCatalogSnapshotAsync(account, groupId, filter, requestedOffset, mode, sort);
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            ApplySnapshot(snapshot);
        }
        catch
        {
            if (!IsCurrentPageRequest(requestGeneration, account)) return;
            RefreshInfoBar.Message = "Couldn't update this page. Showing previously loaded channels.";
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

    private void ShowRefreshFailure()
    {
        RefreshInfoBar.Message = "Couldn't refresh. Showing saved channels.";
        RefreshInfoBar.Severity = InfoBarSeverity.Warning;
        RefreshRetryButton.Visibility = Visibility.Visible;
        RefreshInfoBar.IsOpen = true;
        SetState(content: true);
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
        if (_account is not null) await LoadAsync(_account);
        else await LoadSavedAsync();
    }

    private void RaiseChannelSelected(Channel channel) =>
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(channel.Source));

    private void ShowError(string message)
    {
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
    }

    private sealed record CatalogSnapshot(
        CatalogMode Mode,
        IReadOnlyList<ChannelGroup> Groups,
        string? SelectedGroupId,
        int Offset,
        CatalogPage Page,
        IReadOnlyList<CatalogShelf> Shelves);

    private sealed record CatalogShelf(
        string Id,
        string Title,
        string CountLabel,
        IReadOnlyList<CatalogCard> Cards,
        string? GroupId,
        bool OpensPagedGrid);

    private sealed record CatalogCard(
        string Title,
        string Subtitle,
        string Initials,
        string? ArtworkUrl,
        double Width,
        double Height,
        Channel? Channel);

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

public sealed class ChannelSelectedEventArgs(StreamSource source) : EventArgs
{
    public StreamSource Source { get; } = source;
}
