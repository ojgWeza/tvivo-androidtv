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
    private long _dataVersion;
    private long _loadedDataVersion = -1;
    private string? _loadedAccountId;

    public void MarkDataChanged() => Interlocked.Increment(ref _dataVersion);

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
        var version = Volatile.Read(ref _dataVersion);
        var sameAccount = _loadedAccountId == account?.AccountId;
        if (CatalogReloadPolicy.ShouldReuseHome(_loadedDataVersion >= 0, sameAccount,
                _loadedDataVersion == version))
        {
            LaunchDiagnostics.Write("event=home.load reason=navigation reused=true");
            return;
        }
        LaunchDiagnostics.Write($"event=home.load reason={(sameAccount ? "data-changed" : "account-changed")} reused=false");
        var generation = Interlocked.Increment(ref _loadGeneration);
        SetupPanel.Visibility = account is null ? Visibility.Visible : Visibility.Collapsed;
        ShelvesPanel.Visibility = account is null ? Visibility.Collapsed : Visibility.Visible;
        if (account is null)
        {
            _suggestionsAccountId = null;
            _suggestions = Array.Empty<Channel>();
            _loadedAccountId = null;
            _loadedDataVersion = version;
            ContinueGrid.ItemsSource = null;
            SuggestionsGrid.ItemsSource = null;
            return;
        }

        try
        {
            var (continueWatching, suggestions, progress) = await Task.Run(() =>
            {
                IReadOnlyList<Channel> continuing = repository.GetContinueWatching(account, connection, 40)
                    .Concat(repository.GetRecentlyPlayed(account, CatalogItemType.Live, connection, 10))
                    .Take(50).ToArray();
                var movies = repository.GetPlaybackProgress(account, "movie", continuing.Where(item => item.Source.Kind == StreamKind.Movie).Select(item => item.Id).ToArray());
                var series = repository.GetPlaybackProgress(account, "series", continuing.Where(item => item.Source.Kind == StreamKind.Series).Select(item => item.Id).ToArray());
                var playback = movies.Concat(series).ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
                if (_suggestionsAccountId == account.AccountId)
                    return (continuing, _suggestions, playback);

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
                return (continuing, sampled, playback);
            });
            if (generation != Volatile.Read(ref _loadGeneration)) return;
            _suggestionsAccountId = account.AccountId;
            _suggestions = suggestions;
            _loadedAccountId = account.AccountId;
            _loadedDataVersion = version;
            ContinueGrid.ItemsSource = continueWatching.Select(channel => ToCard(channel, isContinueWatching: true,
                progress.TryGetValue(channel.Id, out var itemProgress) ? itemProgress : null)).ToArray();
            ContinueSection.Visibility = continueWatching.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            SuggestionsGrid.ItemsSource = suggestions.Select(channel => ToCard(channel, isContinueWatching: false)).ToArray();
        }
        catch (Exception)
        {
            if (generation == Volatile.Read(ref _loadGeneration))
            {
                LaunchDiagnostics.Write("event=home.load outcome=failure keptPrevious=true");
            }
        }
    }

    private static HomeShelfCard ToCard(Channel channel, bool isContinueWatching, PlaybackProgress? progress = null)
    {
        var resume = isContinueWatching
            ? ContinueWatchingProgress.For(channel, progress)
            : new ContinueWatchingProgress(false, 0, null);
        var spoken = resume.HasMeasuredProgress
            ? $"{Math.Round(resume.Percent, MidpointRounding.AwayFromZero)} percent watched" + (resume.MinutesLeft is { } minutes ? $", {minutes} minute{(minutes == 1 ? "" : "s")} left" : string.Empty)
            : string.Empty;
        return new(channel, channel.DisplayName, channel.LogoUri?.ToString(), channel.Source.Kind switch
        {
            StreamKind.Movie => "Movie",
            StreamKind.Series => isContinueWatching ? "Series · resume episode" : "Series",
            _ => "Live TV",
        }, resume.TimeLeft, resume.HasMeasuredProgress,
            resume.Percent, Math.Clamp(160 * resume.Percent / 100, 0, 160), spoken,
            resume.HasMeasuredProgress ? Visibility.Visible : Visibility.Collapsed);
    }

    private void Shelf_ItemClick(object sender, ItemClickEventArgs args)
    {
        if (args.ClickedItem is not HomeShelfCard card) return;
        var siblings = (sender as GridView)?.ItemsSource is IEnumerable<HomeShelfCard> cards
            ? cards.Select(item => item.Channel).ToArray() : Array.Empty<Channel>();
        ChannelSelected?.Invoke(this, new ChannelSelectedEventArgs(card.Channel, siblings));
    }

    private void SetupProvider_Click(object sender, RoutedEventArgs args) =>
        ProviderSetupRequested?.Invoke(this, EventArgs.Empty);

    public sealed record HomeShelfCard(Channel Channel, string Title, string? ArtworkUrl, string Subtitle,
        string TimeLeft, bool HasMeasuredProgress, double ProgressPercent, double ProgressWidth,
        string ProgressAutomationName, Visibility ProgressTrackVisibility);
}
