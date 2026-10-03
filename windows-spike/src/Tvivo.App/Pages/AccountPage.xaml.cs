using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Tvivo.Core;

namespace Tvivo.App.Pages;

public sealed partial class AccountPage : UserControl
{
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();

    public AccountPage()
    {
        InitializeComponent();
    }

    public event EventHandler? SignOutCompleted;
    public event EventHandler? ChangeUserRequested;
    public event EventHandler? RefreshCatalogRequested;
    public event EventHandler<string>? PlaybackEngineChanged;
    public event EventHandler<MinimizeToMiniMode>? MinimizeToMiniModeChanged;

    private bool _suppressEngineChanged;
    private bool _suppressMinimizeToMiniModeChanged;

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
        MarkTab(AccountTabButton, tab == "Account");
        MarkTab(PlayerTabButton, tab == "Player");
        MarkTab(LibraryTabButton, tab == "Library");
    }

    private static void MarkTab(Button button, bool selected)
    {
        button.Foreground = (Brush)App.Current.Resources[selected ? "AppTextBrush" : "AppMutedTextBrush"];
        button.BorderBrush = selected
            ? (Brush)App.Current.Resources["AppAccentBrush"]
            : new SolidColorBrush(Colors.Transparent);
    }

    public void ShowAccount(ProviderAccount account, DateTimeOffset? lastRefreshAt)
    {
        SetOpenTab("Library");
        ErrorText.Visibility = Visibility.Collapsed;
        RefreshCatalogButton.IsEnabled = true;
        LastRefreshText.Text = lastRefreshAt is { } refreshed
            ? refreshed.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
            : "Not refreshed in this session";

        DisplayNameText.Text = string.IsNullOrWhiteSpace(account.DisplayName) ? account.Username : account.DisplayName;
        UsernameText.Text = account.Username;
        ServerText.Text = $"{account.Endpoint.Scheme}://{account.Endpoint.Host}:{account.Endpoint.Port}";
        MaxConnectionsText.Text = account.MaxConnections is { } max ? max.ToString() : "Unknown";

        if (account.ExpiresAt is not { } expiresAt)
        {
            ExpiryText.Text = "No expiry reported";
            ExpiryText.Foreground = (Brush)App.Current.Resources["AppMutedTextBrush"];
        }
        else if (expiresAt < DateTimeOffset.Now)
        {
            ExpiryText.Text = $"Expired {expiresAt:yyyy-MM-dd}";
            ExpiryText.Foreground = new SolidColorBrush(Colors.OrangeRed);
        }
        else
        {
            ExpiryText.Text = expiresAt.ToString("yyyy-MM-dd");
            ExpiryText.Foreground = (Brush)App.Current.Resources["AppTextBrush"];
        }
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

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }
}
