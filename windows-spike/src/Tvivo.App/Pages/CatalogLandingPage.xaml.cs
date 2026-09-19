using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;

namespace Tvivo.App.Pages;

public sealed partial class CatalogLandingPage : UserControl
{
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private ProviderAccount? _account;
    public ProviderAccount? Account => _account;

    public CatalogLandingPage() => InitializeComponent();

    public async Task LoadAsync(ProviderAccount account)
    {
        _account = account;
        await LoadCatalogAsync(account);
    }

    public async Task LoadSavedAsync()
    {
        SetState(loading: true);
        try
        {
            var connection = await _credentialStore.LoadAsync();
            if (connection is null)
            {
                ShowError("No saved provider connection. Choose Set up provider from Home and connect an account.");
                return;
            }
            var result = await _provider.AuthenticateAsync(connection);
            if (!result.Success || result.Account is null)
            {
                ShowError($"Authentication failed ({result.FailureReason}). Return to Home and check your provider details.");
                return;
            }
            _account = result.Account;
            await LoadCatalogAsync(result.Account);
        }
        catch (Exception exception)
        {
            ShowError($"Could not connect to the provider: {exception.Message}");
        }
    }

    private async Task LoadCatalogAsync(ProviderAccount account)
    {
        SetState(loading: true);
        try
        {
            var groups = await _provider.GetChannelGroupsAsync(account);
            var channels = await _provider.GetChannelsAsync(account);
            if (groups.Count == 0 && channels.Count == 0)
            {
                SetState(empty: true);
                return;
            }
            GroupsList.ItemsSource = groups.Select(group => group.DisplayName).ToArray();
            ChannelsList.ItemsSource = channels.Select(channel => channel.DisplayName).ToArray();
            SetState(content: true);
        }
        catch (Exception exception)
        {
            ShowError($"Could not load channel groups or channels: {exception.Message}");
        }
    }

    private async void Retry_Click(object sender, RoutedEventArgs args)
    {
        if (_account is not null) await LoadCatalogAsync(_account);
        else await LoadSavedAsync();
    }

    private void ShowError(string message)
    {
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
}
