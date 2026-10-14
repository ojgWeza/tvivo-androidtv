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
    private readonly DispatcherTimer _artworkCacheStatusTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private bool _expiryExpired;
    private bool _expiryHasValue;

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
        if (tab == "ForYou") RenderForYouLayout();
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
