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
    private bool _playbackActiveOnShow;

    public AccountPage()
    {
        InitializeComponent();
    }

    public event EventHandler? SignOutCompleted;
    public event EventHandler? ChangeUserRequested;
    public event EventHandler? ExitRequested;

    public void ShowAccount(ProviderAccount account, bool playbackActive)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        _playbackActiveOnShow = playbackActive;

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

    private async void ExitButton_Click(object sender, RoutedEventArgs args)
    {
        if (!_playbackActiveOnShow)
        {
            ExitRequested?.Invoke(this, EventArgs.Empty);
            return;
        }

        // Navigating here already stopped playback (MainWindow.ShowPage stops any active
        // playback when leaving the Player page). Progress is saved via resume-position
        // tracking, so this just confirms stopping playback, not a loss-of-progress warning.
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Exit Tvivo?",
            Content = "You're currently watching something. Exiting will stop playback here, but your progress is saved and picks up where you left off next time. Exit anyway?",
            PrimaryButtonText = "Exit",
            CloseButtonText = "Stay",
            DefaultButton = ContentDialogButton.Close
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
            ExitRequested?.Invoke(this, EventArgs.Empty);
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }
}
