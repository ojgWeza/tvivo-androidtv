using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public sealed partial class CatalogLandingPage : UserControl
{
#if DEBUG
    private static readonly string? LocalFixturePath = FindFixturePath();
#endif
    private readonly ICatalogProvider _provider = App.Services.GetRequiredService<ICatalogProvider>();
    private readonly SqliteCatalogRepository _repository = App.Services.GetRequiredService<SqliteCatalogRepository>();
    private readonly CatalogRefreshService _refresh = App.Services.GetRequiredService<CatalogRefreshService>();
    private readonly ICredentialStore _credentialStore = App.Services.GetRequiredService<ICredentialStore>();
    private ProviderAccount? _account;
    private IReadOnlyList<ChannelGroup> _groups = Array.Empty<ChannelGroup>();
    private bool _bindingGroups;
    private string? _selectedGroupId;
    private int _offset;
    private const int PageSize = 100;
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
            await _refresh.RefreshAsync(account);
            _groups = _repository.GetGroups(account);
            var firstPage = _repository.GetChannels(account, limit: PageSize);
            if (_groups.Count == 0 && firstPage.TotalCount == 0)
            {
                SetState(empty: true);
                return;
            }
            _bindingGroups = true;
            GroupsList.ItemsSource = new[] { "All channels" }.Concat(_groups.Select(group => group.DisplayName)).ToArray();
            GroupsList.SelectedIndex = 0;
            _bindingGroups = false;
            _selectedGroupId = null;
            _offset = 0;
            ChannelsList.ItemsSource = firstPage.Items;
            UpdatePaging(firstPage.TotalCount);
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
        _selectedGroupId = GroupsList.SelectedIndex == 0 ? null : _groups[GroupsList.SelectedIndex - 1].Id;
        try
        {
            _offset = 0;
            ShowCachedPage();
        }
        catch (Exception exception)
        {
            ShowError($"Could not load channels: {exception.Message}");
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs args) { _offset = 0; ShowCachedPage(); }
    private void PreviousPage_Click(object sender, RoutedEventArgs args) { _offset = Math.Max(0, _offset - PageSize); ShowCachedPage(); }
    private void NextPage_Click(object sender, RoutedEventArgs args) { _offset += PageSize; ShowCachedPage(); }

    private void ShowCachedPage()
    {
        if (_account is null) return;
        var page = _repository.GetChannels(_account, _selectedGroupId, SearchBox.Text, _offset, PageSize);
        ChannelsList.ItemsSource = page.Items;
        UpdatePaging(page.TotalCount);
        SetState(content: true);
    }

    private void UpdatePaging(int total)
    {
        PageStatus.Text = total == 0 ? "0 channels" : $"{_offset + 1}–{Math.Min(_offset + PageSize, total)} of {total}";
        PreviousPageButton.IsEnabled = _offset > 0;
        NextPageButton.IsEnabled = _offset + PageSize < total;
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
