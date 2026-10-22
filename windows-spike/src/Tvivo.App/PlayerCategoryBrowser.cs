using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Windows.System;

namespace Tvivo.App;

public sealed partial class MainWindow
{
    private const int PlayerCategoryPageSize = 75;
    private readonly ObservableCollection<PlayerCategoryRow> _categoryRows = new();
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

    public sealed class PlayerCategoryRow
    {
        public PlayerCategoryRow(string Label, string? CategoryId = null, Channel? Channel = null,
            bool IsStatus = false)
        {
            this.Label = Label;
            this.CategoryId = CategoryId;
            this.Channel = Channel;
            this.IsStatus = IsStatus;
        }
        public string Label { get; }
        public string? CategoryId { get; }
        public Channel? Channel { get; }
        public bool IsStatus { get; }
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
            PlayerCategoryNowPlayingLabel.Text = "Now playing: " + (_nowPlayingChannel?.DisplayName ?? "");
            AutomationProperties.SetName(PlayerCategoryNowPlayingLabel, PlayerCategoryNowPlayingLabel.Text);
            if (_categoryState.ViewMode == PlayerCategoryViewMode.SeriesEpisodes) RenderPlayerCategoryRows();
            else RebuildCategoryRows();
            return;
        }
        _categoryBrowser = new PlayerCategoryBrowserService(_catalogRepository, _catalogLandingPage.Connection);
        _categorySeriesDetails = null;
        _categorySelectedSeries = null;
        PlayerCategoryList.Visibility = Visibility.Visible;
        PlayerCategoryNowPlayingLabel.Visibility = Visibility.Visible;
        PlayerCategoryNowPlayingLabel.Text = "Now playing: " + (_nowPlayingChannel?.DisplayName ?? "");
        AutomationProperties.SetName(PlayerCategoryNowPlayingLabel, PlayerCategoryNowPlayingLabel.Text);
        PlayerRelatedList.Visibility = Visibility.Collapsed;
        PlayerSideTitle.Text = type == CatalogItemType.Movie ? "Movies" : "Series";
        _categoryFolders = Array.Empty<CatalogCategoryShelfDescriptor>();
        _categoryFoldersLoaded = false;
        RenderPlayerCategoryRows();
        _categoryRows.Add(new PlayerCategoryRow("Loading categories…", IsStatus: true));
        var generation = _categoryState.PageGeneration;
        _ = LoadPlayerCategoryFoldersAsync(account, type, generation);
    }

    private async Task LoadPlayerCategoryFoldersAsync(ProviderAccount account, CatalogItemType type, long generation)
    {
        try
        {
            var browser = _categoryBrowser!;
            var folders = await Task.Run(() => browser.GetFolders(account, type));
            if (_categoryState?.PageGeneration != generation || _categoryState.ContentType != type ||
                _catalogLandingPage.Account?.AccountId != account.AccountId || _currentPage != ShellPage.Player) return;
            _categoryFolders = folders;
            _categoryFoldersLoaded = true;
            RebuildCategoryRows();
        }
        catch
        {
            if (_categoryState?.PageGeneration != generation) return;
            _categoryRows.Clear();
            _categoryRows.Add(new PlayerCategoryRow("Categories could not be loaded. Reopen the playlist to retry.", IsStatus: true));
        }
    }

    private void RenderPlayerCategoryRows()
    {
        if (_categoryState is null) return;
        if (_categoryState.ViewMode != PlayerCategoryViewMode.SeriesEpisodes)
        {
            RebuildCategoryRows();
            return;
        }
        _categoryRows.Clear();
        _categoryRows.Add(new PlayerCategoryRow("Back to categories"));
        if (_categorySeriesDetails is { } details && _categorySelectedSeries is { } series)
        {
            var season = PlayerSeasonComboBox.SelectedItem as SeriesSeason ?? details.Seasons.FirstOrDefault();
            if (season is not null && _catalogLandingPage.Account is { } account)
                foreach (var episode in season.Episodes)
                    _categoryRows.Add(new PlayerCategoryRow(episode.Title,
                        Channel: ToEpisodeChannel(account, series.Id, episode)));
        }
        if (_categoryRows.Count == 1) _categoryRows.Add(new PlayerCategoryRow("No episodes available", IsStatus: true));
    }

    private void RebuildCategoryRows(string? status = null)
    {
        if (_categoryState is null) return;
        var retained = _categoryRows.Where(row => row.Channel is not null &&
            _categoryState.ViewMode == PlayerCategoryViewMode.Categories).Select(row => row.Channel!).ToArray();
        _categoryRows.Clear();
        if (_categoryFoldersLoaded && _categoryFolders.Count == 0)
            _categoryRows.Add(new PlayerCategoryRow("No categories available", IsStatus: true));
        foreach (var folder in _categoryFolders)
        {
            var expanded = folder.Group.Id == _categoryState.ExpandedCategoryId;
            _categoryRows.Add(new PlayerCategoryRow($"{(expanded ? "▾" : "▸")} {folder.Group.DisplayName} · {folder.TotalCount}", folder.Group.Id));
            if (expanded)
            {
                foreach (var channel in retained) _categoryRows.Add(new PlayerCategoryRow(channel.DisplayName, Channel: channel));
                if (status is not null) _categoryRows.Add(new PlayerCategoryRow(status, IsStatus: true));
            }
        }
    }

    private void TogglePlayerCategory(string id)
    {
        if (_categoryState is null) return;
        _categoryState.Toggle(id);
        _categoryPendingFocusId = null;
        _categoryLoaded = 0;
        _categoryTotal = 0;
        _categoryLoading = false;
        _categoryRows.Clear();
        RebuildCategoryRows(_categoryState.ExpandedCategoryId is null ? null : "Loading…");
        FocusPlayerCategoryHeader(id);
        if (_categoryState.ExpandedCategoryId is not null) _ = LoadNextPlayerCategoryPageAsync();
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
        try
        {
            var browser = _categoryBrowser;
            var page = await Task.Run(() => browser.GetFolderPage(account, type, id, offset, PlayerCategoryPageSize));
            if (_categoryState?.TryAcceptPage(generation, id, offset, page.Items) != true) return;
            _categoryLoaded += page.Items.Count;
            _categoryTotal = page.TotalCount;
            // Keep at most three pages of row objects. Earlier rows can be loaded again by reopening the folder.
            if (_categoryRows.Count(row => row.Channel is not null) + page.Items.Count >
                PlayerCategoryPageSize * PlayerCategoryAccordionState.PageCapacity)
            {
                var first = _categoryRows.ToList().FindIndex(row => row.Channel is not null);
                if (first >= 0)
                    for (var removed = 0; removed < PlayerCategoryPageSize && first < _categoryRows.Count && _categoryRows[first].Channel is not null; removed++)
                        _categoryRows.RemoveAt(first);
            }
            RebuildCategoryRows(page.Items.Count == 0 && offset == 0 ? "This category is empty" : null);
            // Rebuild above retains the old page rows; insert the accepted page after the open header.
            var header = _categoryRows.ToList().FindIndex(row => row.CategoryId == id);
            var insert = header + 1;
            while (insert < _categoryRows.Count && _categoryRows[insert].Channel is not null) insert++;
            foreach (var channel in page.Items) _categoryRows.Insert(insert++, new PlayerCategoryRow(channel.DisplayName, Channel: channel));
            if (_categoryPendingFocusId is { } pending && page.Items.Any(item => item.Id == pending))
            {
                _categoryPendingFocusId = null;
                FocusPlayerCategoryItem(pending);
            }
            else if (offset == 0 && page.Items.Count > 0) FocusPlayerCategoryItem(page.Items[0].Id);
        }
        catch
        {
            if (_categoryState?.PageGeneration == generation) RebuildCategoryRows("Could not load items. Activate this row to retry.");
        }
        finally
        {
            if (_categoryState?.PageGeneration == generation) _categoryLoading = false;
        }
    }

    private void PlayerCategoryList_ContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.InRecycleQueue || args.Item is not PlayerCategoryRow row) return;
        var folder = row.IsHeader ? _categoryFolders.FirstOrDefault(folder => folder.Group.Id == row.CategoryId) : null;
        AutomationProperties.SetName(args.ItemContainer, folder is not null && _categoryState is not null
            ? $"{folder.Group.DisplayName}, {folder.TotalCount} items, {(_categoryState.ExpandedCategoryId == row.CategoryId ? "expanded" : "collapsed")}" : row.Label);
        var lastItem = _categoryRows.ToList().FindLastIndex(item => item.Channel is not null);
        if (row.Channel is not null && _categoryRows.IndexOf(row) >= lastItem - 8)
            _ = LoadNextPlayerCategoryPageAsync();
    }

    private void PlayerCategoryList_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is PlayerCategoryRow row) ActivatePlayerCategoryRow(row);
    }

    private void ActivatePlayerCategoryRow(PlayerCategoryRow row)
    {
        if (row.IsHeader) { TogglePlayerCategory(row.CategoryId!); return; }
        if (_categoryState?.ViewMode == PlayerCategoryViewMode.SeriesEpisodes && row.Label == "Back to categories")
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
            RebuildCategoryRows();
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
        RebuildCategoryRows("Loading episodes…");
        await OpenSeriesAsync(series, Volatile.Read(ref _itemSelectionGeneration), browseOnly: true,
            categoryGeneration: generation);
        if (_categoryState?.PageGeneration == generation && _categoryState.SelectedItemId == series.Id)
            RebuildCategoryRows("Could not load episodes. Select the series to retry.");
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
        _categoryRows.Clear();
        RebuildCategoryRows("Loading…");
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
            var ids = await Task.Run(() => _catalogRepository.GetCategoryChannelIds(account, CatalogItemType.Series, categoryId));
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
    private void FocusPlayerCategoryItem(string id) => FocusPlayerCategoryIndex(_categoryRows.ToList().FindIndex(row => row.Channel?.Id == id));
    private void FocusPlayerCategoryIndex(int index)
    {
        if (index < 0 || index >= _categoryRows.Count) return;
        PlayerCategoryList.ScrollIntoView(_categoryRows[index]);
        PlayerCategoryList.UpdateLayout();
        if (PlayerCategoryList.ContainerFromItem(_categoryRows[index]) is ListViewItem item)
            item.Focus(FocusState.Programmatic);
    }
}
