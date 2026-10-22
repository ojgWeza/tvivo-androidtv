using System.Collections.ObjectModel;
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Windows.System;
using Windows.UI.Text;

namespace Tvivo.App;

public sealed partial class MainWindow
{
    private const int PlayerCategoryPageSize = 75;
    private readonly ObservableCollection<PlayerCategoryRow> _categoryRows = new();
    private readonly Dictionary<string, PlayerCategoryRow> _categoryHeaders = new(StringComparer.Ordinal);
    private PlayerCategoryAccordionState? _categoryState;
    private PlayerCategoryBrowserService? _categoryBrowser;
    private IReadOnlyList<CatalogCategoryShelfDescriptor> _categoryFolders = Array.Empty<CatalogCategoryShelfDescriptor>();
    private bool _categoryFoldersLoaded;
    private string? _categoryPendingFocusId;
    private int _categoryLoaded;
    private int _categoryTotal;
    private bool _categoryLoading;
    private SeriesDetails? _categorySeriesDetails;
    private Channel? _categorySelectedSeries;

    public sealed class PlayerCategoryRow : INotifyPropertyChanged
    {
        public PlayerCategoryRow(string Label, string? CategoryId = null, Channel? Channel = null,
            bool IsStatus = false, bool IsPinned = false, bool IsBack = false)
        {
            this.Label = Label;
            this.CategoryId = CategoryId;
            this.Channel = Channel;
            this.IsStatus = IsStatus;
            this.IsPinned = IsPinned;
            this.IsBack = IsBack;
        }
        private string _label = string.Empty;
        private string _chevron = string.Empty;
        private Brush? _background;
        private Thickness _borderThickness;
        private FontWeight _weight = Microsoft.UI.Text.FontWeights.Normal;
        public event PropertyChangedEventHandler? PropertyChanged;
        public string Label { get => _label; set { if (_label == value) return; _label = value; PropertyChanged?.Invoke(this, new(nameof(Label))); } }
        public string Chevron { get => _chevron; set { if (_chevron == value) return; _chevron = value; PropertyChanged?.Invoke(this, new(nameof(Chevron))); } }
        public Brush? Background { get => _background; set { if (_background == value) return; _background = value; PropertyChanged?.Invoke(this, new(nameof(Background))); } }
        public Thickness BorderThickness { get => _borderThickness; set { if (_borderThickness.Equals(value)) return; _borderThickness = value; PropertyChanged?.Invoke(this, new(nameof(BorderThickness))); } }
        public FontWeight Weight { get => _weight; set { if (_weight.Equals(value)) return; _weight = value; PropertyChanged?.Invoke(this, new(nameof(Weight))); } }
        public string? CategoryId { get; }
        public Channel? Channel { get; }
        public bool IsStatus { get; }
        public bool IsPinned { get; }
        public bool IsBack { get; }
        public bool IsHeader => CategoryId is not null && Channel is null;
    }

    private void StartPlayerCategoryBrowser(StreamKind kind)
    {
        if (kind is not (StreamKind.Movie or StreamKind.Episode) || _catalogLandingPage.Account is not { } account)
        {
            _categoryState?.Exit();
            _categoryRows.Clear();
            PlayerCategoryList.Visibility = Visibility.Collapsed;
            PlayerCategoryNowPlayingLabel.Visibility = Visibility.Collapsed;
            PlayerRelatedList.Visibility = Visibility.Visible;
            return;
        }

        var type = kind == StreamKind.Movie ? CatalogItemType.Movie : CatalogItemType.Series;
        _categoryState ??= new PlayerCategoryAccordionState(account.AccountId, type);
        var oldGeneration = _categoryState.PageGeneration;
        _categoryState.SetContext(account.AccountId, type);
        if (oldGeneration == _categoryState.PageGeneration && _categoryFolders.Count > 0)
        {
            ShowCategoryBrowser();
            SyncCategoryRows();
            _ = OpenPlayingCategoryAsync(account, type, _categoryState.PageGeneration);
            return;
        }
        _categoryBrowser = new PlayerCategoryBrowserService(_catalogRepository, _catalogLandingPage.Connection);
        _categorySeriesDetails = null;
        _categorySelectedSeries = null;
        ShowCategoryBrowser();
        PlayerSideTitle.Text = type == CatalogItemType.Movie ? "Movies" : "Series";
        _categoryFolders = Array.Empty<CatalogCategoryShelfDescriptor>();
        _categoryFoldersLoaded = false;
        _categoryHeaders.Clear();
        _categoryRows.Clear();
        _categoryRows.Add(new PlayerCategoryRow("Now playing: " + (_nowPlayingChannel?.DisplayName ?? ""),
            Channel: _nowPlayingChannel, IsPinned: true));
        _categoryRows.Add(new PlayerCategoryRow("Loading categories…", IsStatus: true));
        var generation = _categoryState.PageGeneration;
        _ = LoadPlayerCategoryFoldersAsync(account, type, generation);
    }

    private void ShowCategoryBrowser()
    {
        PlayerCategoryList.Visibility = Visibility.Visible;
        PlayerCategoryNowPlayingLabel.Visibility = Visibility.Collapsed;
        PlayerRelatedList.Visibility = Visibility.Collapsed;
    }

    private async Task LoadPlayerCategoryFoldersAsync(ProviderAccount account, CatalogItemType type, long generation)
    {
        try
        {
            var browser = _categoryBrowser!;
            var folders = await Task.Run(() => browser.GetFolders(account, type));
            if (_categoryState?.PageGeneration != generation || _categoryState.ContentType != type ||
                _catalogLandingPage.Account?.AccountId != account.AccountId) return;
            _categoryFolders = folders;
            _categoryFoldersLoaded = true;
            await OpenPlayingCategoryAsync(account, type, generation);
        }
        catch
        {
            if (_categoryState?.PageGeneration != generation) return;
            ReplaceCategoryStatus("Categories could not be loaded. Reopen the playlist to retry.");
        }
    }

    private async Task OpenPlayingCategoryAsync(ProviderAccount account, CatalogItemType type, long generation)
    {
        if (_categoryState?.PageGeneration != generation || !_categoryFoldersLoaded) return;
        var playing = _nowPlayingChannel;
        var playingId = type == CatalogItemType.Series ? _currentSeriesId ?? playing?.GroupId : playing?.Id;
        var groupId = type == CatalogItemType.Series ? null : playing?.GroupId;
        if (groupId is null && playingId is not null)
        {
            var found = await Task.Run(() => _catalogRepository.GetChannel(account,
                _catalogLandingPage.Connection, type, playingId));
            if (_categoryState?.PageGeneration != generation || _nowPlayingChannel?.Id != playing?.Id) return;
            groupId = found?.GroupId;
        }
        if (!_categoryFolders.Any(folder => folder.Group.Id == groupId)) groupId = null;
        var oldOpen = _categoryState.ExpandedCategoryId;
        var changed = oldOpen != groupId;
        _categoryState.OpenForPlaying(groupId, playingId);
        if (changed)
        {
            _categoryLoaded = 0;
            _categoryTotal = 0;
            _categoryLoading = false;
            RemoveOpenCategoryItems(oldOpen);
        }
        _categoryPendingFocusId = type == CatalogItemType.Movie ? playingId : null;
        IReadOnlyList<Channel>? cached = null;
        var hasCache = groupId is not null && _categoryState.TryGetPage(groupId, 0, out cached);
        SyncCategoryRows(groupId is not null && !hasCache && _categoryLoaded == 0 ? "Loading…" : null);
        if (groupId is null) { FocusPlayerCategoryIndex(0); return; }
        FocusPlayerCategoryHeader(groupId);
        if (hasCache && cached is not null && changed) InsertCategoryPage(groupId, 0, cached);
        _ = RestorePlayerCategorySelectionAsync(groupId, _categoryPendingFocusId, _categoryState.PageGeneration);
    }

    private void RenderPlayerCategoryRows()
    {
        SyncCategoryRows();
    }

    private void ReplaceCategoryStatus(string message)
    {
        var headerIndex = _categoryState?.ExpandedCategoryId is { } open && _categoryHeaders.TryGetValue(open, out var header)
            ? _categoryRows.IndexOf(header) : -1;
        var old = headerIndex >= 0 && headerIndex + 1 < _categoryRows.Count && _categoryRows[headerIndex + 1].IsStatus
            ? _categoryRows[headerIndex + 1]
            : headerIndex < 0 ? _categoryRows.FirstOrDefault(row => row.IsStatus) : null;
        if (old is not null) old.Label = message;
        else
        {
            var insert = headerIndex >= 0 ? headerIndex + 1 : _categoryRows.Count;
            _categoryRows.Insert(insert, new PlayerCategoryRow(message, IsStatus: true));
        }
    }

    private void SyncCategoryRows(string? status = null)
    {
        if (_categoryState is null) return;
        var now = _nowPlayingChannel;
        var pinned = _categoryRows.FirstOrDefault(row => row.IsPinned);
        if (pinned is null || pinned.Channel?.Id != now?.Id)
        {
            if (pinned is not null) _categoryRows.Remove(pinned);
            _categoryRows.Insert(0, new PlayerCategoryRow("Now playing: " + (now?.DisplayName ?? ""),
                Channel: now, IsPinned: true));
        }
        var index = 1;
        if (_categoryState.ViewMode == PlayerCategoryViewMode.SeriesEpisodes)
        {
            var back = _categoryRows.FirstOrDefault(row => row.IsBack) ?? new PlayerCategoryRow("Back to categories", IsBack: true);
            PutCategoryRow(index++, back);
        }
        if (_categoryState.ContentType == CatalogItemType.Series && _nowPlayingChannel?.Source.Kind == StreamKind.Episode ||
            _categoryState.ViewMode == PlayerCategoryViewMode.SeriesEpisodes)
        {
            var seasons = _categorySeriesDetails?.Seasons ?? _playerSeriesSeasons;
            var seriesId = _categorySelectedSeries?.Id ?? _currentSeriesId;
            var season = PlayerSeasonComboBox.SelectedItem as SeriesSeason ?? seasons.FirstOrDefault();
            if (season is not null && seriesId is not null && _catalogLandingPage.Account is { } account)
            {
                foreach (var episode in season.Episodes)
                {
                    var existing = _categoryRows.FirstOrDefault(row => !row.IsPinned && row.Channel?.Id == episode.Id);
                    if (existing is null)
                    {
                        var channel = ToEpisodeChannel(account, seriesId, episode,
                            _categorySeriesDetails?.Title, seasons);
                        existing = CreateCategoryChannelRow(channel);
                    }
                    PutCategoryRow(index++, existing);
                }
            }
            else PutCategoryRow(index++, new PlayerCategoryRow("No episodes available", IsStatus: true));
        }
        if (_categoryFoldersLoaded && _categoryFolders.Count == 0)
            PutCategoryRow(index++, new PlayerCategoryRow("No categories available", IsStatus: true));
        var foldersById = _categoryFolders.ToDictionary(folder => folder.Group.Id, StringComparer.Ordinal);
        foreach (var id in _categoryState.OrderedCategoryIds(_categoryFolders.Select(folder => folder.Group.Id)))
        {
            var folder = foldersById[id];
            if (!_categoryHeaders.TryGetValue(id, out var header))
                _categoryHeaders[id] = header = new PlayerCategoryRow($"{folder.Group.DisplayName} · {folder.TotalCount}", id);
            var open = id == _categoryState.ExpandedCategoryId;
            header.Chevron = open ? "▾" : "▸";
            header.Weight = open ? Microsoft.UI.Text.FontWeights.Bold : Microsoft.UI.Text.FontWeights.Normal;
            header.Background = open ? PlayerCategoryOpenBrushSource.Background : null;
            header.BorderThickness = open ? new Thickness(4, 0, 0, 0) : new Thickness(0);
            PutCategoryRow(index++, header);
            if (PlayerCategoryList.ContainerFromItem(header) is ListViewItem container)
            {
                AutomationProperties.SetName(container,
                    $"{folder.Group.DisplayName}, {folder.TotalCount} items, {(open ? "expanded" : "collapsed")}");
                AutomationProperties.SetItemStatus(container, open ? "Expanded" : "Collapsed");
                AutomationProperties.SetHelpText(container, open ? "Expanded folder" : "Collapsed folder");
            }
            if (!open) continue;
            while (index < _categoryRows.Count && _categoryRows[index].Channel is { } channel && !_categoryRows[index].IsPinned &&
                   channel.GroupId == id) index++;
            if (status is not null) PutCategoryRow(index++, new PlayerCategoryRow(status, IsStatus: true));
        }
        while (_categoryRows.Count > index) _categoryRows.RemoveAt(index);
        foreach (var row in _categoryRows.Where(row => !row.IsPinned && row.Channel is not null))
        {
            var playing = row.Channel!.Id == now?.Id;
            row.Label = playing ? "Now playing · " + row.Channel.DisplayName : row.Channel.DisplayName;
            row.Weight = playing ? Microsoft.UI.Text.FontWeights.Bold : Microsoft.UI.Text.FontWeights.Normal;
            row.Background = playing ? PlayerCategoryOpenBrushSource.Background : null;
            row.BorderThickness = playing ? new Thickness(4, 0, 0, 0) : new Thickness(0);
        }
    }

    private void PutCategoryRow(int index, PlayerCategoryRow row)
    {
        var old = _categoryRows.IndexOf(row);
        if (old == index) return;
        if (old >= 0) _categoryRows.Move(old, index);
        else _categoryRows.Insert(index, row);
    }

    private void TogglePlayerCategory(string id)
    {
        if (_categoryState is null) return;
        var oldOpen = _categoryState.ExpandedCategoryId;
        var wasOpen = oldOpen == id;
        _categoryState.Toggle(id);
        _categoryPendingFocusId = null;
        _categoryLoaded = 0;
        _categoryTotal = 0;
        _categoryLoading = false;
        RemoveOpenCategoryItems(oldOpen);
        IReadOnlyList<Channel>? page = null;
        var cached = !wasOpen && _categoryState.TryGetPage(id, 0, out page);
        SyncCategoryRows(!wasOpen && !cached ? "Loading…" : null);
        FocusPlayerCategoryHeader(id);
        if (!wasOpen)
        {
            if (cached && page is not null) InsertCategoryPage(id, 0, page);
            else _ = LoadNextPlayerCategoryPageAsync();
        }
    }

    private void RemoveOpenCategoryItems(string? id)
    {
        if (id is null || !_categoryHeaders.TryGetValue(id, out var header)) return;
        var index = _categoryRows.IndexOf(header) + 1;
        while (index > 0 && index < _categoryRows.Count && !_categoryRows[index].IsHeader)
            _categoryRows.RemoveAt(index);
    }

    private async Task LoadNextPlayerCategoryPageAsync()
    {
        if (_categoryLoading || _categoryState?.ExpandedCategoryId is not { } id ||
            (_categoryTotal > 0 && _categoryLoaded >= _categoryTotal) ||
            _catalogLandingPage.Account is not { } account || _categoryBrowser is null) return;
        _categoryLoading = true;
        var generation = _categoryState.PageGeneration;
        var offset = _categoryLoaded;
        var type = _categoryState.ContentType;
        if (_categoryState.TryGetPage(id, offset, out var cached) && cached is not null)
        {
            InsertCategoryPage(id, offset, cached);
            _categoryLoading = false;
            if (_categoryPendingFocusId is { } target && cached.Any(item => item.Id == target))
            {
                _categoryPendingFocusId = null;
                FocusPlayerCategoryItem(target);
            }
            return;
        }
        try
        {
            var browser = _categoryBrowser;
            var page = await Task.Run(() => browser.GetFolderPage(account, type, id, offset, PlayerCategoryPageSize));
            if (_categoryState?.TryAcceptPage(generation, id, offset, page.Items) != true) return;
            _categoryLoaded += page.Items.Count;
            _categoryTotal = page.TotalCount;
            InsertCategoryPage(id, offset, page.Items);
            if (_categoryPendingFocusId is { } pending && page.Items.Any(item => item.Id == pending))
            {
                _categoryPendingFocusId = null;
                FocusPlayerCategoryItem(pending);
            }
        }
        catch
        {
            if (_categoryState?.PageGeneration == generation) ReplaceCategoryStatus("Could not load items. Activate this row to retry.");
        }
        finally
        {
            if (_categoryState?.PageGeneration == generation) _categoryLoading = false;
        }
    }

    private void InsertCategoryPage(string id, int offset, IReadOnlyList<Channel> channels)
    {
        var header = _categoryHeaders[id];
        var insert = _categoryRows.IndexOf(header) + 1;
        if (insert < _categoryRows.Count && _categoryRows[insert].IsStatus)
            _categoryRows.RemoveAt(insert);
        while (insert < _categoryRows.Count && _categoryRows[insert].Channel is not null && !_categoryRows[insert].IsPinned)
            insert++;
        foreach (var channel in channels)
            if (!_categoryRows.Any(row => !row.IsPinned && row.Channel?.Id == channel.Id))
                _categoryRows.Insert(insert++, CreateCategoryChannelRow(channel));
        if (offset == 0 && channels.Count == 0)
            _categoryRows.Insert(insert, new PlayerCategoryRow("This category is empty", IsStatus: true));
        while (_categoryRows.Count(row => row.Channel is not null && !row.IsPinned) >
               PlayerCategoryPageSize * PlayerCategoryAccordionState.PageCapacity)
            _categoryRows.RemoveAt(_categoryRows.IndexOf(header) + 1);
        _categoryLoaded = Math.Max(_categoryLoaded, offset + channels.Count);
    }

    private PlayerCategoryRow CreateCategoryChannelRow(Channel channel)
    {
        var playing = channel.Id == _nowPlayingChannel?.Id;
        return new PlayerCategoryRow(playing ? "Now playing · " + channel.DisplayName : channel.DisplayName,
            Channel: channel)
        {
            Weight = playing ? Microsoft.UI.Text.FontWeights.Bold : Microsoft.UI.Text.FontWeights.Normal,
            Background = playing ? PlayerCategoryOpenBrushSource.Background : null,
            BorderThickness = playing ? new Thickness(4, 0, 0, 0) : new Thickness(0),
        };
    }

    private void PlayerCategoryList_ContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.InRecycleQueue || args.Item is not PlayerCategoryRow row) return;
        var folder = row.IsHeader ? _categoryFolders.FirstOrDefault(folder => folder.Group.Id == row.CategoryId) : null;
        AutomationProperties.SetName(args.ItemContainer, folder is not null && _categoryState is not null
            ? $"{folder.Group.DisplayName}, {folder.TotalCount} items, {(_categoryState.ExpandedCategoryId == row.CategoryId ? "expanded" : "collapsed")}" : row.Label);
        if (folder is not null)
        {
            AutomationProperties.SetItemStatus(args.ItemContainer,
                _categoryState?.ExpandedCategoryId == row.CategoryId ? "Expanded" : "Collapsed");
            AutomationProperties.SetHelpText(args.ItemContainer,
                _categoryState?.ExpandedCategoryId == row.CategoryId ? "Expanded folder" : "Collapsed folder");
        }
        var lastItem = _categoryRows.ToList().FindLastIndex(item => item.Channel is not null && !item.IsPinned);
        if (row.Channel is not null && !row.IsPinned && _categoryRows.IndexOf(row) >= lastItem - 8)
            _ = LoadNextPlayerCategoryPageAsync();
    }

    private void PlayerCategoryList_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is not PlayerCategoryRow row) return;
        ActivatePlayerCategoryRow(row);
        if (row.IsHeader)
            _ = PlayerCategoryList.DispatcherQueue.TryEnqueue(() => FocusPlayerCategoryHeader(row.CategoryId!));
    }

    private void ActivatePlayerCategoryRow(PlayerCategoryRow row)
    {
        if (row.IsHeader) { TogglePlayerCategory(row.CategoryId!); return; }
        if (row.IsBack)
        { ReturnToPlayerCategories(); return; }
        if (row.IsStatus)
        {
            if (_categoryState?.ViewMode == PlayerCategoryViewMode.SeriesEpisodes) return;
            if (_categoryState?.ExpandedCategoryId is not null) _ = LoadNextPlayerCategoryPageAsync();
            else if (_categoryState is not null && _catalogLandingPage.Account is { } account)
                _ = LoadPlayerCategoryFoldersAsync(account, _categoryState.ContentType, _categoryState.PageGeneration);
            return;
        }
        if (row.Channel is not { } channel || _categoryState is null) return;
        if (channel.Source.Kind == StreamKind.Movie)
        {
            _categoryState.SelectItem(channel.Id);
            PlayRelatedChannel(channel);
            SyncCategoryRows();
        }
        else if (channel.Source.Kind == StreamKind.Series)
        {
            _categoryState.SelectItem(channel.Id);
            _ = OpenPlayerCategorySeriesAsync(channel);
        }
        else if (channel.Source.Kind == StreamKind.Episode && _categorySelectedSeries is { } series && _categorySeriesDetails is { } details)
            _ = PlayPlayerCategoryEpisodeAsync(series, details, channel);
    }

    private async Task PlayPlayerCategoryEpisodeAsync(Channel series, SeriesDetails details, Channel channel)
    {
        if (_categoryState is null || _catalogLandingPage.Account is not { } account || channel.Source.DirectUri is null) return;
        var generation = _categoryState.PageGeneration;
        var progress = await Task.Run(() => _catalogRepository.GetEpisodeProgressForSeries(account, series.Id));
        if (_categoryState?.PageGeneration != generation || _categoryState.ViewMode != PlayerCategoryViewMode.SeriesEpisodes ||
            _catalogLandingPage.Account?.AccountId != account.AccountId) return;
        _seriesEpisodeProgress = progress;
        _currentSeriesId = series.Id;
        _currentSeriesTitle = details.Title;
        _currentSeriesArtwork = series.LogoUri;
        _currentSeriesAccount = account;
        _currentSeriesMetadata = details.Metadata;
        _playerSeriesSeasons = details.Seasons;
        _playerSiblings = details.Seasons.SelectMany(season => season.Episodes)
            .Select(episode => ToEpisodeChannel(account, series.Id, episode)).ToArray();
        UpdateSeriesFavoriteButton(_catalogRepository.IsFavorite(account, CatalogItemType.Series, series.Id));
        QueueVisit(account, CatalogItemType.Series, series.Id);
        OpenSeriesEpisode(channel);
    }

    private async Task OpenPlayerCategorySeriesAsync(Channel series)
    {
        if (_categoryState is null) return;
        var generation = _categoryState.PageGeneration;
        ReplaceCategoryStatus("Loading episodes…");
        await OpenSeriesAsync(series, Volatile.Read(ref _itemSelectionGeneration), browseOnly: true,
            categoryGeneration: generation);
        if (_categoryState?.PageGeneration == generation && _categoryState.SelectedItemId == series.Id)
            ReplaceCategoryStatus("Could not load episodes. Select the series to retry.");
    }

    private void ReturnToPlayerCategories()
    {
        if (_categoryState is null) return;
        var selectedId = _categoryState.SelectedItemId;
        var header = _categoryState.ReturnToCategories().HeaderToFocus;
        _categoryLoading = false;
        _categorySeriesDetails = null;
        _categorySelectedSeries = null;
        PlayerSeasonComboBox.ItemsSource = null;
        PlayerSeasonComboBox.Visibility = Visibility.Collapsed;
        PlayerSeasonLabel.Visibility = Visibility.Collapsed;
        PlayerSeasonWatchedCount.Visibility = Visibility.Collapsed;
        _categoryLoaded = 0;
        _categoryTotal = 0;
        for (var i = _categoryRows.Count - 1; i >= 0; i--)
            if (!_categoryRows[i].IsPinned && !_categoryRows[i].IsHeader) _categoryRows.RemoveAt(i);
        SyncCategoryRows("Loading…");
        if (header is not null)
        {
            FocusPlayerCategoryHeader(header);
            _categoryPendingFocusId = selectedId;
            _ = RestorePlayerCategorySelectionAsync(header, selectedId, _categoryState.PageGeneration);
        }
    }

    private async Task RestorePlayerCategorySelectionAsync(string categoryId, string? itemId, long generation)
    {
        if (_catalogLandingPage.Account is not { } account || _categoryState is null) return;
        if (itemId is not null)
        {
            var ids = await Task.Run(() => _catalogRepository.GetCategoryChannelIds(account, _categoryState.ContentType, categoryId));
            if (_categoryState?.PageGeneration != generation) return;
            var index = ids.ToList().FindIndex(id => id == itemId);
            if (index >= 0) _categoryLoaded = index / PlayerCategoryPageSize * PlayerCategoryPageSize;
        }
        await LoadNextPlayerCategoryPageAsync();
    }

    private void PlayerCategoryList_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (_categoryRows.Count == 0) return;
        var focused = FocusManager.GetFocusedElement(WindowRoot.XamlRoot);
        var focusedContainer = focused as ListViewItem;
        var index = focusedContainer is null ? 0 : _categoryRows.IndexOf((PlayerCategoryRow)PlayerCategoryList.ItemFromContainer(focusedContainer));
        if (args.Key == VirtualKey.Escape) { PlaylistToggleButton.Focus(FocusState.Programmatic); args.Handled = true; return; }
        if (args.Key == VirtualKey.Up || args.Key == VirtualKey.Down)
        { FocusPlayerCategoryIndex(Math.Clamp(index + (args.Key == VirtualKey.Down ? 1 : -1), 0, _categoryRows.Count - 1)); args.Handled = true; return; }
        if (index < 0 || index >= _categoryRows.Count) return;
        var row = _categoryRows[index];
        if (args.Key == VirtualKey.Right && row.IsHeader && _categoryState?.ExpandedCategoryId != row.CategoryId)
        { TogglePlayerCategory(row.CategoryId!); args.Handled = true; }
        else if (args.Key == VirtualKey.Left && _categoryState?.ExpandedCategoryId is { } open)
        { if (_categoryState.ViewMode == PlayerCategoryViewMode.SeriesEpisodes) ReturnToPlayerCategories(); else TogglePlayerCategory(open); args.Handled = true; }
        else if (args.Key is VirtualKey.Enter or VirtualKey.Space)
        { ActivatePlayerCategoryRow(row); args.Handled = true; }
    }

    private void FocusPlayerCategoryHeader(string id) => FocusPlayerCategoryIndex(_categoryRows.ToList().FindIndex(row => row.CategoryId == id));
    private void FocusPlayerCategoryItem(string id) => FocusPlayerCategoryIndex(_categoryRows.ToList().FindIndex(row => !row.IsPinned && row.Channel?.Id == id));
    private void FocusPlayerCategoryIndex(int index)
    {
        if (index < 0 || index >= _categoryRows.Count) return;
        var row = _categoryRows[index];
        PlayerCategoryList.ScrollIntoView(row, ScrollIntoViewAlignment.Leading);
        PlayerCategoryList.UpdateLayout();
        if (PlayerCategoryList.ContainerFromItem(row) is ListViewItem item)
            item.Focus(FocusState.Programmatic);
        else _ = PlayerCategoryList.DispatcherQueue.TryEnqueue(() =>
        {
            if (_categoryRows.Contains(row) && PlayerCategoryList.ContainerFromItem(row) is ListViewItem realized)
                realized.Focus(FocusState.Programmatic);
        });
    }
}
