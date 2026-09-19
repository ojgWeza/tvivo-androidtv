using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;

namespace Tvivo.App.Pages;

public sealed partial class ProviderSetupPage : UserControl
{
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();

    public ProviderSetupPage()
    {
        InitializeComponent();
        _ = LoadSavedConnectionAsync();
    }

    public event EventHandler<ProviderConnectedEventArgs>? ConnectionSaved;

    private async Task LoadSavedConnectionAsync()
    {
        try
        {
            var saved = await _credentialStore.LoadAsync();
            if (saved is null) return;
            HostBox.Text = saved.Endpoint.Host;
            PortBox.Text = saved.Endpoint.Port.ToString();
            SchemeBox.SelectedIndex = saved.Endpoint.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
            UsernameBox.Text = saved.Username;
            PasswordBox.Password = saved.Password;
        }
        catch (Exception exception)
        {
            ShowError($"Could not load saved credentials: {exception.Message}");
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
        if (!int.TryParse(PortBox.Text, out var port) || port is < 1 or > 65535)
        {
            ShowError("Enter a port from 1 to 65535.");
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
                ShowError($"Authentication failed. Check the server address and account details. {exception.Message}");
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
                ShowError($"Could not save credentials. Your previously saved connection has been kept. {exception.Message}");
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
