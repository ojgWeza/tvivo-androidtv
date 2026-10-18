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

    public AccountPage()
    {
        InitializeComponent();
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
            if (_spotlightAccount is not null)
            {
                _movieFolders = ReadSpotlightFolders(_spotlightAccount, CatalogItemType.Movie);
                _seriesFolders = ReadSpotlightFolders(_spotlightAccount, CatalogItemType.Series);
            }
            RenderForYouLayout();
            RenderSpotlightSettings();
            RenderSpotlightPreviews();
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

            var toggle = new CheckBox { Content = definition.Title, IsChecked = visible, FontSize = 16 };
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

    private void SpotlightMoviesModeSelector_SelectionChanged(object sender, SelectionChangedEventArgs args) =>
        SpotlightModeChanged(CatalogItemType.Movie, SpotlightMoviesModeSelector);

    private void SpotlightSeriesModeSelector_SelectionChanged(object sender, SelectionChangedEventArgs args) =>
        SpotlightModeChanged(CatalogItemType.Series, SpotlightSeriesModeSelector);

    private void SpotlightModeChanged(CatalogItemType type, ComboBox selector)
    {
        if (_suppressSpotlightUi || selector.SelectedItem is not ComboBoxItem { Tag: string tag } ||
            !Enum.TryParse<SpotlightSourceMode>(tag, out var mode)) return;
        SetSpotlightContent(type, CurrentSpotlightContent(type) with { SourceMode = mode });
        RenderSpotlightSettings();
        RenderSpotlightPreviews();
    }

    private void SpotlightMoviesSearchBox_TextChanged(object sender, TextChangedEventArgs args) =>
        RenderSpotlightFolderList(CatalogItemType.Movie);

    private void SpotlightSeriesSearchBox_TextChanged(object sender, TextChangedEventArgs args) =>
        RenderSpotlightFolderList(CatalogItemType.Series);

    private void SpotlightMoviesSelectAllButton_Click(object sender, RoutedEventArgs args) =>
        SelectAllSpotlightFolders(CatalogItemType.Movie);

    private void SpotlightSeriesSelectAllButton_Click(object sender, RoutedEventArgs args) =>
        SelectAllSpotlightFolders(CatalogItemType.Series);

    private void SpotlightMoviesClearButton_Click(object sender, RoutedEventArgs args) =>
        ClearSpotlightFolders(CatalogItemType.Movie);

    private void SpotlightSeriesClearButton_Click(object sender, RoutedEventArgs args) =>
        ClearSpotlightFolders(CatalogItemType.Series);

    private void SpotlightSaveButton_Click(object sender, RoutedEventArgs args)
    {
        if (_spotlightAccount is null) return;
        try
        {
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
        LoadSpotlightDraft();
        SpotlightSettingsStatusText.Text = "Changes canceled.";
        RenderSpotlightSettings();
        RenderSpotlightPreviews();
    }

    private void LoadSpotlightDraft()
    {
        if (_spotlightAccount is null) return;
        _spotlightDraft = _spotlightSettingsService.ReadEffectiveSettings(_spotlightAccount.AccountId);
        _movieFolders = ReadSpotlightFolders(_spotlightAccount, CatalogItemType.Movie);
        _seriesFolders = ReadSpotlightFolders(_spotlightAccount, CatalogItemType.Series);
    }

    private IReadOnlyList<SpotlightFolderOption> ReadSpotlightFolders(ProviderAccount account, CatalogItemType type) =>
        _catalogRepository.GetCategoryShelfDescriptors(account, type)
            .Select(item => new SpotlightFolderOption(item.Group.Id, item.Group.DisplayName, item.TotalCount)).ToArray();

    private void RenderSpotlightSettings()
    {
        _suppressSpotlightUi = true;
        try
        {
            SetSpotlightModeSelector(SpotlightMoviesModeSelector, _spotlightDraft.Movies.SourceMode);
            SetSpotlightModeSelector(SpotlightSeriesModeSelector, _spotlightDraft.Series.SourceMode);
            SpotlightMoviesFoldersPanel.Visibility = _spotlightDraft.Movies.SourceMode == SpotlightSourceMode.SpecificFolders
                ? Visibility.Visible : Visibility.Collapsed;
            SpotlightSeriesFoldersPanel.Visibility = _spotlightDraft.Series.SourceMode == SpotlightSourceMode.SpecificFolders
                ? Visibility.Visible : Visibility.Collapsed;
            RenderSpotlightFolderList(CatalogItemType.Movie);
            RenderSpotlightFolderList(CatalogItemType.Series);
        }
        finally { _suppressSpotlightUi = false; }
    }

    private static void SetSpotlightModeSelector(ComboBox selector, SpotlightSourceMode mode) =>
        selector.SelectedItem = selector.Items.OfType<ComboBoxItem>()
            .First(item => string.Equals(item.Tag as string, mode.ToString(), StringComparison.Ordinal));

    private void RenderSpotlightFolderList(CatalogItemType type)
    {
        var folders = type == CatalogItemType.Movie ? _movieFolders : _seriesFolders;
        var content = CurrentSpotlightContent(type);
        var selected = (content.SelectedCategoryIds ?? Array.Empty<string>()).ToHashSet(StringComparer.Ordinal);
        var search = type == CatalogItemType.Movie ? SpotlightMoviesSearchBox.Text : SpotlightSeriesSearchBox.Text;
        var visible = SpotlightFolderList.Filter(folders, search);
        var missing = SpotlightFolderList.FindMissing(content.SelectedCategoryIds, folders);
        var list = type == CatalogItemType.Movie ? SpotlightMoviesFolderList : SpotlightSeriesFolderList;
        var count = type == CatalogItemType.Movie ? SpotlightMoviesSelectedCountText : SpotlightSeriesSelectedCountText;
        var empty = type == CatalogItemType.Movie ? SpotlightMoviesEmptyText : SpotlightSeriesEmptyText;
        list.Children.Clear();

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
                $"{folder.Name}, {folder.ItemCount.ToString(CultureInfo.InvariantCulture)} items");
            checkbox.Click += (_, _) => SetSpotlightFolderSelected(type, folder.Id, checkbox.IsChecked == true);
            list.Children.Add(checkbox);
        }

        foreach (var id in missing)
        {
            var row = new Grid { ColumnSpacing = 8, MinHeight = 48 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var unavailable = new CheckBox
            {
                Content = "Folder no longer available",
                IsChecked = false,
                IsEnabled = false,
                FontSize = 16,
                MinHeight = 44,
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(unavailable, "Folder no longer available");
            row.Children.Add(unavailable);
            var remove = new Button { Content = "Remove", FontSize = 16, MinHeight = 44, Style = (Style)App.Current.Resources["AppQuietButtonStyle"] };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(remove, "Remove unavailable folder");
            remove.Click += (_, _) => RemoveSpotlightFolder(type, id);
            Grid.SetColumn(remove, 1);
            row.Children.Add(remove);
            list.Children.Add(row);
        }

        count.Text = $"{folders.Count(folder => selected.Contains(folder.Id))} selected";
        empty.Visibility = visible.Count == 0 && missing.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void SetSpotlightFolderSelected(CatalogItemType type, string id, bool isSelected)
    {
        var current = CurrentSpotlightContent(type);
        var ids = (current.SelectedCategoryIds ?? Array.Empty<string>()).ToList();
        if (isSelected && !ids.Contains(id, StringComparer.Ordinal)) ids.Add(id);
        else if (!isSelected) ids.RemoveAll(value => value == id);
        SetSpotlightContent(type, current with { SelectedCategoryIds = ids });
        var folders = type == CatalogItemType.Movie ? _movieFolders : _seriesFolders;
        var count = type == CatalogItemType.Movie ? SpotlightMoviesSelectedCountText : SpotlightSeriesSelectedCountText;
        count.Text = $"{folders.Count(folder => ids.Contains(folder.Id, StringComparer.Ordinal))} selected";
        RenderSpotlightPreviews();
    }

    private void SelectAllSpotlightFolders(CatalogItemType type)
    {
        var current = CurrentSpotlightContent(type);
        var ids = (current.SelectedCategoryIds ?? Array.Empty<string>()).ToList();
        var folders = type == CatalogItemType.Movie ? _movieFolders : _seriesFolders;
        foreach (var folder in folders)
            if (!ids.Contains(folder.Id, StringComparer.Ordinal)) ids.Add(folder.Id);
        SetSpotlightContent(type, current with { SelectedCategoryIds = ids });
        RenderSpotlightFolderList(type);
        RenderSpotlightPreviews();
    }

    private void ClearSpotlightFolders(CatalogItemType type)
    {
        SetSpotlightContent(type, CurrentSpotlightContent(type) with { SelectedCategoryIds = Array.Empty<string>() });
        RenderSpotlightFolderList(type);
        RenderSpotlightPreviews();
    }

    private void RemoveSpotlightFolder(CatalogItemType type, string id)
    {
        var current = CurrentSpotlightContent(type);
        SetSpotlightContent(type, current with
        {
            SelectedCategoryIds = (current.SelectedCategoryIds ?? Array.Empty<string>()).Where(value => value != id).ToArray(),
        });
        RenderSpotlightFolderList(type);
        (type == CatalogItemType.Movie ? SpotlightMoviesSearchBox : SpotlightSeriesSearchBox).Focus(FocusState.Programmatic);
        RenderSpotlightPreviews();
    }

    private SpotlightContentSettings CurrentSpotlightContent(CatalogItemType type) =>
        type == CatalogItemType.Movie ? _spotlightDraft.Movies : _spotlightDraft.Series;

    private void SetSpotlightContent(CatalogItemType type, SpotlightContentSettings content) =>
        _spotlightDraft = type == CatalogItemType.Movie
            ? _spotlightDraft with { Movies = content }
            : _spotlightDraft with { Series = content };

    private void RenderSpotlightPreviews()
    {
        if (_spotlightAccount is null)
        {
            SpotlightMoviesPreviewText.Text = "Preview will appear when a catalog is available.";
            SpotlightSeriesPreviewText.Text = "Preview will appear when a catalog is available.";
            return;
        }
        SpotlightMoviesPreviewText.Text = FormatSpotlightPreview(CatalogItemType.Movie);
        SpotlightSeriesPreviewText.Text = FormatSpotlightPreview(CatalogItemType.Series);
    }

    private string FormatSpotlightPreview(CatalogItemType type)
    {
        try
        {
            var titles = _spotlightSettingsService.PreviewTitles(_spotlightAccount!, type, _spotlightDraft);
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
}
