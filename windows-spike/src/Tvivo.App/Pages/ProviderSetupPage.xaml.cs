using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;

namespace Tvivo.App.Pages;

public sealed partial class ProviderSetupPage : UserControl
{
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private int _credentialGeneration;

    public ProviderSetupPage()
    {
        InitializeComponent();
        _ = LoadSavedConnectionAsync(_credentialGeneration);
    }

    public event EventHandler<ProviderConnectedEventArgs>? ConnectionSaved;

    public void ClearCredentials()
    {
        // Invalidate any in-flight LoadSavedConnectionAsync from construction time so a slow
        // credential-store read can't resolve after sign-out and silently repopulate these fields.
        _credentialGeneration++;
        HostBox.Text = string.Empty;
        PortBox.Text = string.Empty;
        SchemeBox.SelectedIndex = 0;
        UsernameBox.Text = string.Empty;
        PasswordBox.Password = string.Empty;
        ErrorText.Visibility = Visibility.Collapsed;
    }

    private async Task LoadSavedConnectionAsync(int generation)
    {
        try
        {
            var saved = await _credentialStore.LoadAsync();
            if (saved is null) return;
            if (generation != _credentialGeneration) return;
            HostBox.Text = saved.Endpoint.Host;
            PortBox.Text = saved.Endpoint.Port > 0 ? saved.Endpoint.Port.ToString() : string.Empty;
            SchemeBox.SelectedIndex = saved.Endpoint.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
            UsernameBox.Text = saved.Username;
            PasswordBox.Password = saved.Password;
        }
        catch (Exception exception)
        {
            ShowError(ConnectionErrorText.SavedConnection(exception));
        }
    }

    private async void Connect_Click(object sender, RoutedEventArgs args)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        if (string.IsNullOrWhiteSpace(HostBox.Text) || string.IsNullOrWhiteSpace(UsernameBox.Text) || string.IsNullOrEmpty(PasswordBox.Password))
        {
            ShowError("Enter the server host, username, and password.");
            return;
        }
        // The port is optional: empty means the provider's default port (nothing is added to the address).
        var port = 0;
        if (!string.IsNullOrWhiteSpace(PortBox.Text) &&
            (!int.TryParse(PortBox.Text.Trim(), out port) || port is < 1 or > 65535))
        {
            ShowError("Enter a port from 1 to 65535, or leave it empty.");
            return;
        }

        var scheme = (SchemeBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "http";
        var connection = new ProviderConnection(new ProviderEndpoint(scheme, HostBox.Text.Trim(), port), UsernameBox.Text.Trim(), PasswordBox.Password);
        SetLoading(true);
        try
        {
            AuthenticationResult result;
            try
            {
                result = await _provider.AuthenticateAsync(connection);
            }
            catch (Exception exception)
            {
                ShowError(ConnectionErrorText.Authentication(exception));
                return;
            }

            if (!result.Success || result.Account is null)
            {
                ShowError($"Authentication failed ({result.FailureReason}). Check the server address and account details, then try again.");
                return;
            }

            try
            {
                await _credentialStore.SaveAsync(connection);
            }
            catch (Exception exception)
            {
                ShowError(ConnectionErrorText.SaveConnection(exception));
                return;
            }

            ConnectionSaved?.Invoke(this, new ProviderConnectedEventArgs(connection, result.Account));
        }
        finally
        {
            SetLoading(false);
        }
    }

    private void SetLoading(bool loading)
    {
        ConnectButton.IsEnabled = !loading;
        LoadingText.Visibility = loading ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }
}

public sealed record ProviderConnectedEventArgs(ProviderConnection Connection, ProviderAccount Account);

public static class ConnectionErrorText
{
    public static string SavedConnection(Exception exception)
    {
        LaunchDiagnostics.WriteException("Could not load saved credentials", exception);
        return "Could not load your saved connection. Try signing in again.";
    }

    public static string Authentication(Exception exception)
    {
        LaunchDiagnostics.WriteException("Authentication failed", exception);
        return "Couldn't connect. Check the server address and your account details, then try again.";
    }

    public static string SaveConnection(Exception exception)
    {
        LaunchDiagnostics.WriteException("Could not save credentials", exception);
        return "Couldn't save your connection. Please try again.";
    }

    public static string SignOut(Exception exception)
    {
        LaunchDiagnostics.WriteException("Could not sign out", exception);
        return "Couldn't sign out cleanly. Try again, or restart the app.";
    }
}
