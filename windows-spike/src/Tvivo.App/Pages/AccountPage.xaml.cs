using System.Globalization;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Tvivo.App;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public sealed partial class AccountPage : UserControl
{
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private readonly CatalogRefreshPolicyStore _catalogRefreshPolicyStore = new();
    private readonly SqliteCatalogRepository _catalogRepository = App.Services.GetRequiredService<SqliteCatalogRepository>();
    private readonly SpotlightSettingsService _spotlightSettingsService = App.Services.GetRequiredService<SpotlightSettingsService>();
    private readonly DispatcherTimer _artworkCacheStatusTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private bool _expiryExpired;
    private bool _expiryHasValue;
    private ProviderAccount? _spotlightAccount;
    private SpotlightSettings _spotlightDraft = SpotlightSettings.Default;
    private IReadOnlyList<SpotlightFolderOption> _movieFolders = Array.Empty<SpotlightFolderOption>();
    private IReadOnlyList<SpotlightFolderOption> _seriesFolders = Array.Empty<SpotlightFolderOption>();
    private bool _suppressSpotlightUi;
    private bool _spotlightFoldersLoaded;
    private bool _spotlightFoldersLoading;
    private int _spotlightFolderLoadGeneration;
    private readonly Dictionary<(SpotlightPageKind Page, CatalogItemType Type), SpotlightSourceControls> _spotlightSources = new();

    public AccountPage()
    {
        InitializeComponent();
        BuildSpotlightSources();
        ActualThemeChanged += AccountPage_ActualThemeChanged;
        _artworkCacheStatusTimer.Tick += ArtworkCacheStatusTimer_Tick;
    }

    public event EventHandler? SignOutCompleted;
    public event EventHandler? ChangeUserRequested;
    public event EventHandler? RefreshCatalogRequested;
    public event EventHandler<string>? PlaybackEngineChanged;
    public event EventHandler<int>? PlaybackAttemptsChanged;
    public event EventHandler<MinimizeToMiniMode>? MinimizeToMiniModeChanged;

    private bool _suppressEngineChanged;
    private bool _suppressPlaybackAttemptsChanged;
    private bool _suppressMinimizeToMiniModeChanged;
    private bool _suppressCatalogRefreshPolicyChanged;

    private sealed class SpotlightSourceControls
    {
        public required SpotlightPageKind Page { get; init; }
        public required CatalogItemType Type { get; init; }
        public required StackPanel Root { get; init; }
        public required StackPanel FolderPanel { get; init; }
        public required TextBox Search { get; init; }
        public required StackPanel List { get; init; }
        public required TextBlock Status { get; init; }
        public required TextBlock SelectedCount { get; init; }
        public required TextBlock LegacyMessage { get; init; }
        public required TextBlock Preview { get; init; }
    }

    public void SetMinimizeToMiniMode(MinimizeToMiniMode mode)
    {
        _suppressMinimizeToMiniModeChanged = true;
        MinimizeToMiniSelector.SelectedItem = MinimizeToMiniSelector.Items
            .OfType<ComboBoxItem>()
            .First(item => string.Equals(item.Tag as string, mode.ToString(), StringComparison.Ordinal));
        _suppressMinimizeToMiniModeChanged = false;
    }

    public void SetMinimizeToMiniAvailable(bool available)
    {
        MinimizeToMiniSelector.IsEnabled = available;
        MinimizeToMiniDescription.Text = available
            ? "Choose whether minimizing keeps an active LibVLC video in a small always-on-top window."
            : "Mini player needs the LibVLC engine. Switch the playback engine to LibVLC to use it.";
    }

    private void MinimizeToMiniSelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (!_suppressMinimizeToMiniModeChanged &&
            MinimizeToMiniSelector.SelectedItem is ComboBoxItem { Tag: string tag } &&
            Enum.TryParse<MinimizeToMiniMode>(tag, out var mode))
            MinimizeToMiniModeChanged?.Invoke(this, mode);
    }

    public void SetPlaybackEngine(string tag)
    {
        _suppressEngineChanged = true;
        PlaybackEngineSelector.SelectedIndex = tag == "LibVLC" ? 1 : 0;
        _suppressEngineChanged = false;
    }

    private void PlaybackEngineSelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_suppressEngineChanged || PlaybackEngineSelector.SelectedItem is not ComboBoxItem { Tag: string tag }) return;
        PlaybackEngineChanged?.Invoke(this, tag);
    }

    public void SetPlaybackAttempts(int attempts)
    {
        _suppressPlaybackAttemptsChanged = true;
        PlaybackAttemptsSelector.SelectedItem = PlaybackAttemptsSelector.Items
            .OfType<ComboBoxItem>().First(item => string.Equals(item.Tag as string,
                PlaybackRetryPolicy.ClampAttempts(attempts).ToString(CultureInfo.InvariantCulture), StringComparison.Ordinal));
        _suppressPlaybackAttemptsChanged = false;
    }

    private void PlaybackAttemptsSelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_suppressPlaybackAttemptsChanged || PlaybackAttemptsSelector.SelectedItem is not ComboBoxItem { Tag: string tag } ||
            !int.TryParse(tag, NumberStyles.None, CultureInfo.InvariantCulture, out var attempts)) return;
        PlaybackAttemptsChanged?.Invoke(this, PlaybackRetryPolicy.ClampAttempts(attempts));
    }

    private string? _openTab;

    private void SettingsTab_Click(object sender, RoutedEventArgs args)
    {
        if (sender is Button { Tag: string tab }) SetOpenTab(tab == _openTab ? null : tab);
    }

    private void SetOpenTab(string? tab)
    {
        if (tab == "ForYou") StopPendingSpotlightSearch();
        _openTab = tab;
        AccountTabPanel.Visibility = tab == "Account" ? Visibility.Visible : Visibility.Collapsed;
        PlayerTabPanel.Visibility = tab == "Player" ? Visibility.Visible : Visibility.Collapsed;
        LibraryTabPanel.Visibility = tab == "Library" ? Visibility.Visible : Visibility.Collapsed;
        ForYouTabPanel.Visibility = tab == "ForYou" ? Visibility.Visible : Visibility.Collapsed;
        MarkTab(AccountTabButton, tab == "Account");
        MarkTab(PlayerTabButton, tab == "Player");
        MarkTab(LibraryTabButton, tab == "Library");
        MarkTab(ForYouTabButton, tab == "ForYou");
        if (tab == "ForYou")
        {
            RenderForYouLayout();
            RenderSpotlightSettings();
            RenderSpotlightPreviews();
            _ = LoadSpotlightFoldersAsync();
        }
    }

    private readonly MyTvivoLayoutStore _myTvivoLayoutStore = new();

    public event EventHandler? MyTvivoLayoutChanged;

    private void RenderForYouLayout()
    {
        var entries = MyTvivoShelfDefinitions.Resolve(_myTvivoLayoutStore.Read());
        ForYouShelfList.Children.Clear();
        for (var index = 0; index < entries.Count; index++)
        {
            var (definition, visible) = entries[index];
            var position = index;
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var toggle = new CheckBox { Content = definition.Title, IsChecked = visible, FontSize = 16, MinHeight = 44 };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(toggle, $"Show {definition.Title} on For You");
            toggle.Click += (_, _) => SaveForYouLayout(entries, position, delta: 0, visible: toggle.IsChecked == true);
            row.Children.Add(toggle);

            var up = MoveButton("", "Move up", position > 0);
            up.Click += (_, _) => SaveForYouLayout(entries, position, delta: -1, visible);
            Grid.SetColumn(up, 1);
            row.Children.Add(up);

            var down = MoveButton("", "Move down", position < entries.Count - 1);
            down.Click += (_, _) => SaveForYouLayout(entries, position, delta: 1, visible);
            Grid.SetColumn(down, 2);
            row.Children.Add(down);

            ForYouShelfList.Children.Add(row);
        }
    }

    private static Button MoveButton(string glyph, string name, bool enabled)
    {
        var button = new Button
        {
            Content = glyph,
            FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"),
            Style = (Style)App.Current.Resources["AppIconButtonStyle"],
            IsEnabled = enabled,
            MinHeight = 44,
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, name);
        return button;
    }

    private void SaveForYouLayout(
        IReadOnlyList<(MyTvivoShelfDefinition Definition, bool Visible)> entries, int position, int delta, bool visible)
    {
        var list = entries.Select(entry => new MyTvivoShelfPreference(entry.Definition.Id, entry.Visible)).ToList();
        list[position] = list[position] with { Visible = visible };
        var target = position + delta;
        if (delta != 0 && target >= 0 && target < list.Count)
            (list[position], list[target]) = (list[target], list[position]);
        try
        {
            _myTvivoLayoutStore.Write(list);
            ErrorText.Visibility = Visibility.Collapsed;
            MyTvivoLayoutChanged?.Invoke(this, EventArgs.Empty);
        }
        catch
        {
            ShowError("Couldn't save the For You layout. Please try again.");
        }
        RenderForYouLayout();
    }

    private void ResetForYouLayout_Click(object sender, RoutedEventArgs args)
    {
        try
        {
            _myTvivoLayoutStore.Reset();
            MyTvivoLayoutChanged?.Invoke(this, EventArgs.Empty);
        }
        catch
        {
            ShowError("Couldn't reset the For You layout. Please try again.");
        }
        RenderForYouLayout();
    }

    private void AccountPage_ActualThemeChanged(FrameworkElement sender, object args)
    {
        MarkTab(AccountTabButton, _openTab == "Account");
        MarkTab(PlayerTabButton, _openTab == "Player");
        MarkTab(LibraryTabButton, _openTab == "Library");
        MarkTab(ForYouTabButton, _openTab == "ForYou");
        ApplyExpiryBrush();
    }

    private void SpotlightRotationSelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_suppressSpotlightUi || SpotlightRotationSelector.SelectedItem is not ComboBoxItem { Tag: string tag } ||
            !int.TryParse(tag, out var days)) return;
        _spotlightDraft = _spotlightDraft with { RotationDays = SpotlightSettings.NormalizeRotationDays(days) };
    }

    private void BuildSpotlightSources()
    {
        foreach (var (page, type, title) in new[]
        {
            (SpotlightPageKind.Movies, CatalogItemType.Movie, "Movies page"),
            (SpotlightPageKind.Series, CatalogItemType.Series, "Series page"),
            (SpotlightPageKind.ForYou, CatalogItemType.Movie, "For You page — movie source"),
            (SpotlightPageKind.ForYou, CatalogItemType.Series, "For You page — series source"),
        })
        {
            var card = new StackPanel { Spacing = 8, Margin = new Thickness(12) };
            var heading = new TextBlock { Text = title, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = AppThemeBrushResolver.For(this, "AppTextBrush"), TextWrapping = TextWrapping.Wrap };
            card.Children.Add(heading);
            var modePanel = new StackPanel { Orientation = Orientation.Vertical, Spacing = 4 };
            SpotlightSourceControls binding = null!;
            foreach (var (mode, label) in new[]
            {
                (SpotlightSourceMode.RecentlyAdded, "Recently added"),
                (SpotlightSourceMode.SpecificFolders, "Chosen folders"),
            })
            {
                var radio = new RadioButton { Content = label, Tag = mode, GroupName = $"spotlight-{page}-{type}",
                    FontSize = 16, MinHeight = 44, VerticalContentAlignment = VerticalAlignment.Center };
                Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(radio, $"{title}: {label}");
                radio.Checked += (_, _) =>
                {
                    if (_suppressSpotlightUi) return;
                    _spotlightDraft = _spotlightDraft.WithPage(page, type,
                        _spotlightDraft.ForPage(page, type) with { SourceMode = mode });
                    RenderSpotlightSource(binding);
                    RenderSpotlightPreviews();
                };
                modePanel.Children.Add(radio);
            }
            card.Children.Add(modePanel);
            var legacyMessage = new TextBlock { FontSize = 16, TextWrapping = TextWrapping.Wrap,
                Foreground = AppThemeBrushResolver.For(this, "AppMutedTextBrush"),
                Text = "This saved source is still active. Choose a new source above to replace it.",
                Visibility = Visibility.Collapsed };
            card.Children.Add(legacyMessage);
            var folderPanel = new StackPanel { Spacing = 8 };
            var search = new TextBox { FontSize = 16, MinHeight = 44, PlaceholderText = "Search folders" };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(search, $"Search {title} folders");
            var list = new StackPanel { Spacing = 2 };
            var scroll = new ScrollViewer { MaxHeight = 240, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = list };
            var actions = new StackPanel { Orientation = Orientation.Vertical, Spacing = 4 };
            var selectAll = new Button { Content = "Select all", FontSize = 16, MinHeight = 44,
                Style = (Style)App.Current.Resources["AppQuietButtonStyle"] };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(selectAll, $"Select all {title} folders");
            selectAll.Click += (_, _) => ChangeAllFolders(binding, true);
            var clear = new Button { Content = "Clear", FontSize = 16, MinHeight = 44,
                Style = (Style)App.Current.Resources["AppQuietButtonStyle"] };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(clear, $"Clear {title} folders");
            clear.Click += (_, _) => ChangeAllFolders(binding, false);
            var selectedCount = new TextBlock { FontSize = 16, VerticalAlignment = VerticalAlignment.Center,
                Foreground = AppThemeBrushResolver.For(this, "AppMutedTextBrush") };
            actions.Children.Add(selectAll); actions.Children.Add(clear); actions.Children.Add(selectedCount);
            var status = new TextBlock { FontSize = 16, TextWrapping = TextWrapping.Wrap,
                Foreground = AppThemeBrushResolver.For(this, "AppMutedTextBrush") };
            folderPanel.Children.Add(search); folderPanel.Children.Add(actions); folderPanel.Children.Add(scroll); folderPanel.Children.Add(status);
            card.Children.Add(folderPanel);
            var preview = new TextBlock { FontSize = 16, TextWrapping = TextWrapping.Wrap,
                Foreground = AppThemeBrushResolver.For(this, "AppMutedTextBrush") };
            card.Children.Add(preview);
            binding = new SpotlightSourceControls { Page = page, Type = type, Root = card, FolderPanel = folderPanel,
                Search = search, List = list, Status = status, SelectedCount = selectedCount,
                LegacyMessage = legacyMessage, Preview = preview };
            _spotlightSources.Add((page, type), binding);
            search.TextChanged += (_, _) => RenderSpotlightSource(binding);
            SpotlightSourcesPanel.Children.Add(new Border { Background = AppThemeBrushResolver.For(this, "AppPanelSurfaceBrush"),
                CornerRadius = new CornerRadius(10), Padding = new Thickness(8), Child = card });
        }
    }

    public void StopPendingSpotlightSearch() { }

    private void SpotlightSaveButton_Click(object sender, RoutedEventArgs args)
    {
        if (_spotlightAccount is null) return;
        try
        {
            _spotlightDraft = SpotlightFolderSelectionPolicy.SaveDraft(_spotlightDraft) with
            {
                Movies = _spotlightDraft.ForPage(SpotlightPageKind.Movies, CatalogItemType.Movie),
                Series = _spotlightDraft.ForPage(SpotlightPageKind.Series, CatalogItemType.Series),
            };
            _spotlightSettingsService.Save(_spotlightAccount.AccountId, _spotlightDraft);
            SpotlightSettingsService.LogSaved(_spotlightDraft);
            SpotlightSettingsStatusText.Text = "Spotlight settings saved.";
            ErrorText.Visibility = Visibility.Collapsed;
            RenderSpotlightPreviews();
        }
        catch
        {
            ShowError("Couldn't save Spotlight settings. Please try again.");
        }
    }

    private void SpotlightCancelButton_Click(object sender, RoutedEventArgs args)
    {
        if (_spotlightAccount is not null)
            _spotlightDraft = SpotlightFolderSelectionPolicy.CancelDraft(
                _spotlightSettingsService.ReadEffectiveSettings(_spotlightAccount.AccountId)).SeedPageSettings();
        SpotlightSettingsStatusText.Text = "Changes canceled.";
        RenderSpotlightSettings();
        RenderSpotlightPreviews();
    }

    private void LoadSpotlightDraft()
    {
        if (_spotlightAccount is null) return;
        _spotlightDraft = SpotlightFolderSelectionPolicy.BeginDraft(
            _spotlightSettingsService.ReadEffectiveSettings(_spotlightAccount.AccountId));
    }

    private async Task LoadSpotlightFoldersAsync()
    {
        if (_spotlightAccount is null || _spotlightFoldersLoaded || _spotlightFoldersLoading) return;
        var account = _spotlightAccount;
        _spotlightFoldersLoading = true;
        var generation = ++_spotlightFolderLoadGeneration;
        foreach (var source in _spotlightSources.Values) { source.Status.Text = "Loading folders..."; source.Status.Visibility = Visibility.Visible; }
        try
        {
            var result = await Task.Run(() => (
                Movies: _catalogRepository.GetCategoryShelfDescriptors(account, CatalogItemType.Movie, includeEmptyCategories: true)
                    .Select(item => new SpotlightFolderOption(item.Group.Id, item.Group.DisplayName, item.TotalCount)).ToArray(),
                Series: _catalogRepository.GetCategoryShelfDescriptors(account, CatalogItemType.Series, includeEmptyCategories: true)
                    .Select(item => new SpotlightFolderOption(item.Group.Id, item.Group.DisplayName, item.TotalCount)).ToArray(),
                MovieRefreshed: _catalogRepository.GetLastRefreshSuccess(account, CatalogItemType.Movie) is not null,
                SeriesRefreshed: _catalogRepository.GetLastRefreshSuccess(account, CatalogItemType.Series) is not null));
            if (generation != _spotlightFolderLoadGeneration || !ReferenceEquals(account, _spotlightAccount)) return;
            _movieFolders = result.Movies;
            _seriesFolders = result.Series;
            _spotlightFoldersLoaded = true;
            _spotlightFoldersLoading = false;
            if (result.MovieRefreshed) _spotlightDraft = DropUnavailableFolders(_spotlightDraft, CatalogItemType.Movie, _movieFolders);
            if (result.SeriesRefreshed) _spotlightDraft = DropUnavailableFolders(_spotlightDraft, CatalogItemType.Series, _seriesFolders);
            RenderSpotlightSettings(result.MovieRefreshed, result.SeriesRefreshed);
            RenderSpotlightPreviews();
        }
        catch
        {
            if (generation != _spotlightFolderLoadGeneration) return;
            _spotlightFoldersLoading = false;
            foreach (var source in _spotlightSources.Values) source.Status.Text = "Folders could not be loaded. Try refreshing the catalogue.";
        }
    }

    private static SpotlightSettings DropUnavailableFolders(SpotlightSettings settings, CatalogItemType type,
        IReadOnlyList<SpotlightFolderOption> folders)
    {
        foreach (var page in Enum.GetValues<SpotlightPageKind>())
        {
            var content = settings.ForPage(page, type);
            settings = settings.WithPage(page, type, content with
            { SelectedCategoryIds = SpotlightFolderSelectionPolicy.KeepAvailableSelection(content.SelectedCategoryIds, folders) });
        }
        return settings;
    }

    private void RenderSpotlightSettings(bool moviesRefreshed = false, bool seriesRefreshed = false)
    {
        _suppressSpotlightUi = true;
        try
        {
            foreach (var source in _spotlightSources.Values) RenderSpotlightSource(source, moviesRefreshed, seriesRefreshed);
            SpotlightRotationSelector.SelectedItem = SpotlightRotationSelector.Items.OfType<ComboBoxItem>()
                .First(item => string.Equals(item.Tag as string,
                    SpotlightSettings.NormalizeRotationDays(_spotlightDraft.RotationDays).ToString(), StringComparison.Ordinal));
        }
        finally { _suppressSpotlightUi = false; }
    }

    private void RenderSpotlightSource(SpotlightSourceControls source, bool moviesRefreshed = false, bool seriesRefreshed = false)
    {
        var type = source.Type;
        var folders = type == CatalogItemType.Movie ? _movieFolders : _seriesFolders;
        var content = _spotlightDraft.ForPage(source.Page, type);
        var radioPanel = (StackPanel)source.Root.Children[1];
        foreach (var radio in radioPanel.Children.OfType<RadioButton>())
            radio.IsChecked = radio.Tag is SpotlightSourceMode mode && mode == content.SourceMode;
        source.LegacyMessage.Visibility = content.SourceMode == SpotlightSourceMode.UnexploredFolders
            ? Visibility.Visible : Visibility.Collapsed;
        source.FolderPanel.Visibility = content.SourceMode == SpotlightSourceMode.SpecificFolders ? Visibility.Visible : Visibility.Collapsed;
        var selected = (content.SelectedCategoryIds ?? Array.Empty<string>()).ToHashSet(StringComparer.Ordinal);
        var visible = SpotlightFolderList.Filter(folders, SpotlightFolderSelectionPolicy.ShouldShowSearch(folders.Count) ? source.Search.Text : null);
        source.Search.Visibility = SpotlightFolderSelectionPolicy.ShouldShowSearch(folders.Count) ? Visibility.Visible : Visibility.Collapsed;
        source.List.Children.Clear();

        foreach (var folder in visible)
        {
            var rowContent = new Grid { ColumnSpacing = 12 };
            rowContent.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            rowContent.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var label = new TextBlock
            {
                Text = folder.Name,
                FontSize = 16,
                TextWrapping = TextWrapping.Wrap,
                VerticalAlignment = VerticalAlignment.Center,
                Foreground = AppThemeBrushResolver.For(this, "AppTextBrush"),
            };
            rowContent.Children.Add(label);
            var itemCount = new TextBlock
            {
                Text = folder.ItemCount.ToString(CultureInfo.InvariantCulture),
                FontSize = 16,
                VerticalAlignment = VerticalAlignment.Center,
                Foreground = AppThemeBrushResolver.For(this, "AppMutedTextBrush"),
            };
            Grid.SetColumn(itemCount, 1);
            rowContent.Children.Add(itemCount);
            var checkbox = new CheckBox { Content = rowContent, IsChecked = selected.Contains(folder.Id), MinHeight = 56, FontSize = 16 };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(checkbox,
                $"{source.Page} {type} folder: {folder.Name}, {folder.ItemCount.ToString(CultureInfo.InvariantCulture)} items");
            checkbox.Click += (_, _) => SetSpotlightFolderSelected(source, folder.Id, checkbox.IsChecked == true);
            source.List.Children.Add(checkbox);
        }

        source.SelectedCount.Text = $"{folders.Count(folder => selected.Contains(folder.Id))} selected";
        if (folders.Count == 0)
        {
            var refreshed = type == CatalogItemType.Movie ? moviesRefreshed : seriesRefreshed;
            source.Status.Text = SpotlightFolderSelectionPolicy.EmptyMessage(!_spotlightFoldersLoaded,
                providerHasFolders: false, catalogIsIndexing: !refreshed);
            source.Status.Visibility = Visibility.Visible;
        }
        else if (visible.Count == 0)
        {
            source.Status.Text = "No folders match this search.";
            source.Status.Visibility = Visibility.Visible;
        }
        else source.Status.Visibility = Visibility.Collapsed;
    }

    private void SetSpotlightFolderSelected(SpotlightSourceControls source, string id, bool isSelected)
    {
        var current = _spotlightDraft.ForPage(source.Page, source.Type);
        var ids = (current.SelectedCategoryIds ?? Array.Empty<string>()).ToList();
        if (isSelected && !ids.Contains(id, StringComparer.Ordinal)) ids.Add(id);
        else if (!isSelected) ids.RemoveAll(value => value == id);
        _spotlightDraft = _spotlightDraft.WithPage(source.Page, source.Type, current with { SelectedCategoryIds = ids });
        RenderSpotlightSource(source);
        RenderSpotlightPreviews();
    }

    private void ChangeAllFolders(SpotlightSourceControls source, bool select)
    {
        var current = _spotlightDraft.ForPage(source.Page, source.Type);
        var folders = source.Type == CatalogItemType.Movie ? _movieFolders : _seriesFolders;
        var ids = select ? folders.Select(folder => folder.Id).ToArray() : Array.Empty<string>();
        _spotlightDraft = _spotlightDraft.WithPage(source.Page, source.Type, current with { SelectedCategoryIds = ids });
        RenderSpotlightSource(source);
        RenderSpotlightPreviews();
    }

    private void RenderSpotlightPreviews()
    {
        if (_spotlightAccount is null)
        {
            foreach (var source in _spotlightSources.Values) source.Preview.Text = "Preview will appear when a catalog is available.";
            return;
        }
        foreach (var source in _spotlightSources.Values) source.Preview.Text = FormatSpotlightPreview(source.Page, source.Type);
    }

    private string FormatSpotlightPreview(SpotlightPageKind page, CatalogItemType type)
    {
        try
        {
            var titles = _spotlightSettingsService.PreviewTitles(_spotlightAccount!, page, type, _spotlightDraft);
            return titles.Count == 0 ? "Preview: no titles available." : "Preview: " + string.Join(" · ", titles);
        }
        catch { return "Preview unavailable."; }
    }

    private void MarkTab(Button button, bool selected)
    {
        button.Foreground = AppThemeBrushResolver.For(button, selected ? "AppTextBrush" : "AppMutedTextBrush");
        button.BorderBrush = selected
            ? AppThemeBrushResolver.For(button, "AppAccentBrush")
            : new SolidColorBrush(Colors.Transparent);
    }

    private void ApplyExpiryBrush()
    {
        var key = !_expiryHasValue ? "AppMutedTextBrush" : _expiryExpired ? "AppDangerTextBrush" : "AppTextBrush";
        ExpiryText.Foreground = AppThemeBrushResolver.For(this, key);
    }

    public void ShowAccount(ProviderAccount account, DateTimeOffset? lastRefreshAt)
    {
        StopPendingSpotlightSearch();
        _spotlightFolderLoadGeneration++;
        _spotlightFoldersLoaded = false;
        _spotlightFoldersLoading = false;
        _movieFolders = Array.Empty<SpotlightFolderOption>();
        _seriesFolders = Array.Empty<SpotlightFolderOption>();
        _spotlightAccount = account;
        LoadSpotlightDraft();
        SetOpenTab("Library");
        ErrorText.Visibility = Visibility.Collapsed;
        RefreshCatalogButton.IsEnabled = true;
        _suppressCatalogRefreshPolicyChanged = true;
        var policy = _catalogRefreshPolicyStore.Read();
        CatalogRefreshPolicySelector.SelectedItem = CatalogRefreshPolicySelector.Items
            .OfType<ComboBoxItem>().First(item => string.Equals(item.Tag as string, policy.ToString(), StringComparison.Ordinal));
        _suppressCatalogRefreshPolicyChanged = false;
        LastRefreshText.Text = lastRefreshAt is { } refreshed
            ? refreshed.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
            : "Never refreshed";

        DisplayNameText.Text = string.IsNullOrWhiteSpace(account.DisplayName) ? account.Username : account.DisplayName;
        UsernameText.Text = account.Username;
        ServerText.Text = account.Endpoint.Port > 0
            ? $"{account.Endpoint.Scheme}://{account.Endpoint.Host}:{account.Endpoint.Port}"
            : $"{account.Endpoint.Scheme}://{account.Endpoint.Host}";
        MaxConnectionsText.Text = account.MaxConnections is { } max ? max.ToString() : "Unknown";

        if (account.ExpiresAt is not { } expiresAt)
        {
            ExpiryText.Text = "No expiry reported";
            _expiryHasValue = false;
            _expiryExpired = false;
        }
        else if (expiresAt < DateTimeOffset.Now)
        {
            ExpiryText.Text = $"Expired {expiresAt:yyyy-MM-dd}";
            _expiryHasValue = true;
            _expiryExpired = true;
        }
        else
        {
            ExpiryText.Text = expiresAt.ToString("yyyy-MM-dd");
            _expiryHasValue = true;
            _expiryExpired = false;
        }
        ApplyExpiryBrush();
    }

    private async void SignOutButton_Click(object sender, RoutedEventArgs args)
    {
        try
        {
            await _credentialStore.DeleteAsync();
            SignOutCompleted?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception exception)
        {
            ShowError(ConnectionErrorText.SignOut(exception));
        }
    }

    private void ChangeUserButton_Click(object sender, RoutedEventArgs args)
    {
        // Unlike sign-out, this does not delete the saved credential: it only opens a fresh
        // setup form so a different account can be tested/logged into. If the user backs out
        // without completing setup, the previous account's saved credentials are untouched.
        ChangeUserRequested?.Invoke(this, EventArgs.Empty);
    }

    private void RefreshCatalogButton_Click(object sender, RoutedEventArgs args)
    {
        RefreshCatalogButton.IsEnabled = false;
        LastRefreshText.Text = "Refreshing... returning to your catalog";
        RefreshCatalogRequested?.Invoke(this, EventArgs.Empty);
    }

    private void ClearArtworkCacheButton_Click(object sender, RoutedEventArgs args)
    {
        try
        {
            CatalogLandingPage.ClearArtworkCache();
            ErrorText.Visibility = Visibility.Collapsed;
            ArtworkCacheStatusText.Text = "Artwork cache cleared.";
            ArtworkCacheStatusText.Visibility = Visibility.Visible;
            _artworkCacheStatusTimer.Stop();
            _artworkCacheStatusTimer.Start();
        }
        catch
        {
            ShowError("Couldn't clear the artwork cache. Please try again.");
        }
    }

    private void ArtworkCacheStatusTimer_Tick(object? sender, object args)
    {
        _artworkCacheStatusTimer.Stop();
        ArtworkCacheStatusText.Visibility = Visibility.Collapsed;
    }

    private void CatalogRefreshPolicySelector_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_suppressCatalogRefreshPolicyChanged ||
            CatalogRefreshPolicySelector.SelectedItem is not ComboBoxItem { Tag: string tag } ||
            !Enum.TryParse<CatalogRefreshPolicy>(tag, out var policy)) return;
        try
        {
            _catalogRefreshPolicyStore.Write(policy);
            ErrorText.Visibility = Visibility.Collapsed;
        }
        catch
        {
            ShowError("Couldn't save the refresh setting. Please try again.");
        }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }

    private async void SaveDiagnostics_Click(object sender, RoutedEventArgs args)
    {
        try
        {
            var version = typeof(App).Assembly.GetName().Version?.ToString() ?? "unknown";
            var path = await Task.Run(() => SupportDiagnostics.SaveToDesktop(version));
            SupportDiagnostics.Reveal(path);
            DiagnosticsStatusText.Text = "Saved to your Desktop";
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"event=diagnostics.export outcome=failure exception={exception.GetType().Name}");
            DiagnosticsStatusText.Text = "Couldn't save diagnostics. Please try again.";
        }
    }
}
