using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App.Pages;

public sealed partial class HomePage : UserControl
{
    private string? _suggestionsAccountId;
    private IReadOnlyList<Channel> _suggestions = Array.Empty<Channel>();
    private long _loadGeneration;
    private CatalogLandingPage? _artworkOwner;

    public HomePage() => InitializeComponent();

    public event EventHandler? ProviderSetupRequested;
    public event EventHandler<ChannelSelectedEventArgs>? ChannelSelected;

    public void UseArtworkPipeline(CatalogLandingPage artworkOwner) => _artworkOwner = artworkOwner;

    private void Artwork_Loaded(object sender, RoutedEventArgs args)
    {
        if (sender is Image image) _artworkOwner?.LoadHomeArtwork(image);
    }

    private void Artwork_Unloaded(object sender, RoutedEventArgs args)
    {
        if (sender is Image image) _artworkOwner?.UnloadHomeArtwork(image);
    }

    private void Artwork_DataContextChanged(FrameworkElement sender, DataContextChangedEventArgs args)
    {
        if (sender is Image image && image.IsLoaded) _artworkOwner?.LoadHomeArtwork(image);
    }

    public async Task LoadAsync(ProviderAccount? account, ProviderConnection? connection,
        SqliteCatalogRepository repository)
    {
        var generation = Interlocked.Increment(ref _loadGeneration);
        SetupPanel.Visibility = account is null ? Visibility.Visible : Visibility.Collapsed;
        ShelvesPanel.Visibility = account is null ? Visibility.Collapsed : Visibility.Visible;
        if (account is null)
        {
            _suggestionsAccountId = null;
            _suggestions = Array.Empty<Channel>();
            ContinueGrid.ItemsSource = null;
            SuggestionsGrid.ItemsSource = null;
            return;
        }

        try
        {
            var (continueWatching, suggestions) = await Task.Run(() =>
            {
                IReadOnlyList<Channel> continuing = repository.GetContinueWatching(account, connection, 40)
                    .Concat(repository.GetRecentlyPlayed(account, CatalogItemType.Live, connection, 10))
                    .Take(50).ToArray();
                if (_suggestionsAccountId == account.AccountId)
                    return (continuing, _suggestions);

                var excluded = new HashSet<(CatalogItemType Type, string Id)>();
                foreach (var type in Enum.GetValues<CatalogItemType>())
                    foreach (var item in repository.GetRecentlyAdded(account, type, connection, 50))
                        excluded.Add((type, item.Id));
                foreach (var item in continuing)
                    excluded.Add((item.Source.Kind switch
                    {
                        StreamKind.Series => CatalogItemType.Series,
                        StreamKind.Live => CatalogItemType.Live,
                        _ => CatalogItemType.Movie,
                    }, item.Id));
                var sampled = repository.GetSuggestions(account, connection, excluded);
                if (sampled.Count == 0)
                    sampled = repository.GetSuggestions(account, connection,
                        new HashSet<(CatalogItemType Type, string Id)>());
                return (continuing, sampled);
            });
            if (generation != Volatile.Read(ref _loadGeneration)) return;
            _suggestionsAccountId = account.AccountId;
            _suggestions = suggestions;
            ContinueGrid.ItemsSource = continueWatching.Select(channel => ToCard(channel, isContinueWatching: true)).ToArray();
            ContinueSection.Visibility = continueWatching.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            SuggestionsGrid.ItemsSource = suggestions.Select(channel => ToCard(channel, isContinueWatching: false)).ToArray();
        }
        catch (Exception)
        {
            if (generation == Volatile.Read(ref _loadGeneration))
            {
                ContinueGrid.ItemsSource = null;
                SuggestionsGrid.ItemsSource = null;
                ContinueSection.Visibility = Visibility.Collapsed;
            }
        }
    }

    private static HomeShelfCard ToCard(Channel channel, bool isContinueWatching) => new(channel,
        channel.DisplayName, channel.LogoUri?.ToString(), channel.Source.Kind switch
        {
            StreamKind.Movie => "Movie",
            StreamKind.Series => isContinueWatching ? "Series · resume episode" : "Series",
            _ => "Live TV",
        });

    private void Shelf_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is not HomeShelfCard card) return;
        var siblings = (sender as GridView)?.ItemsSource is IEnumerable<HomeShelfCard> cards
            ? cards.Select(item => item.Channel).ToArray() : Array.Empty<Channel>();
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(card.Channel, siblings));
    }

    private void SetupProvider_Click(object sender, RoutedEventArgs args) =>
        ProviderSetupRequested?.Invoke(this, EventArgs.Empty);

    public sealed record HomeShelfCard(Channel Channel, string Title, string? ArtworkUrl, string Subtitle);
}
