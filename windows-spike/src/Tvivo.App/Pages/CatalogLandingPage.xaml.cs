using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;

namespace Tvivo.App.Pages;

public sealed partial class CatalogLandingPage : UserControl
{
#if DEBUG
    private static readonly string? LocalFixturePath = FindFixturePath();
#endif
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private ProviderAccount? _account;
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private bool _bindingGroups;
    public ProviderAccount? Account => _account;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;

    public CatalogLandingPage()
    {
        InitializeComponent();
#if DEBUG
        if (LocalFixturePath is not null)
        {
            var fixtureButton = new Button
            {
                Content = "Debug: Gate 9 fixture",
                HorizontalAlignment = HorizontalAlignment.Left,
            };
            fixtureButton.Click += LocalFixtureButton_Click;
            CatalogStack.Children.Insert(2, fixtureButton);
        }
#endif
    }

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
            _groups = groups;
            var channels = await _provider.GetChannelsAsync(account);
#if DEBUG
            channels = WithLocalFixtureChannel(channels);
#endif
            if (groups.Count == 0 && channels.Count == 0)
            {
                SetState(empty: true);
                return;
            }
            _bindingGroups = true;
            GroupsList.ItemsSource = new[] { "All channels" }.Concat(groups.Select(group => group.DisplayName)).ToArray();
            GroupsList.SelectedIndex = 0;
            _bindingGroups = false;
            ChannelsList.ItemsSource = channels;
            SetState(content: true);
        }
        catch (Exception exception)
        {
            ShowError($"Could not load channel groups or channels: {exception.Message}");
        }
    }

    private async void GroupsList_SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_bindingGroups || _account is null || GroupsList.SelectedIndex < 0) return;
        var groupId = GroupsList.SelectedIndex == 0 ? null : _groups[GroupsList.SelectedIndex - 1].Id;
        try
        {
            SetState(loading: true);
            var channels = await _provider.GetChannelsAsync(_account, groupId);
            ChannelsList.ItemsSource = channels;
            SetState(content: true);
        }
        catch (Exception exception)
        {
            ShowError($"Could not load channels: {exception.Message}");
        }
    }

    private void ChannelsList_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs args)
    {
        if (ChannelsList.SelectedItem is Channel channel)
            RaiseChannelSelected(channel);
    }

    private void RaiseChannelSelected(Channel channel) =>
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(channel.Source));

#if DEBUG
    private static Channel CreateLocalFixtureChannel() => new(
        "local-fixture", "gate-9-local-fixture", null, "Gate 9 local fixture", "Gate 9 local fixture",
        null, null, int.MaxValue,
        new StreamSource("gate-9-local-fixture", StreamKind.Movie, DirectUri: new Uri(LocalFixturePath!)),
        new Dictionary<string, string>());

    private static IReadOnlyList<Channel> WithLocalFixtureChannel(IReadOnlyList<Channel> channels)
    {
        if (LocalFixturePath is null) return channels;
        return channels.Append(CreateLocalFixtureChannel()).ToArray();
    }

    private static string? FindFixturePath()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var fixturePath = Path.Combine(directory.FullName, "windows-spike", "tests", "Gate9", "thirtyfive-second-h264.mp4");
            if (File.Exists(fixturePath)) return fixturePath;
        }
        return null;
    }
#endif

#if DEBUG
    private void LocalFixtureButton_Click(object sender, RoutedEventArgs args) =>
        RaiseChannelSelected(CreateLocalFixtureChannel());
#endif

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

public sealed class ChannelSelectedEventArgs(StreamSource source) : EventArgs
{
    public StreamSource Source { get; } = source;
}
