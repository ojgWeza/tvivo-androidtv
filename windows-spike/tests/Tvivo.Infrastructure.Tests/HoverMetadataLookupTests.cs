using System.Collections.Concurrent;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class HoverMetadataLookupTests
{
    private static readonly ProviderAccount Account = new("account-a", new ProviderEndpoint("http", "example.invalid", 0), "user");

    [Fact]
    public async Task CachedMetadataIncludingEmptyMetadataMakesNoProviderCall()
    {
        var store = new MetadataStore();
        var expected = new CatalogMetadata();
        store.Save(Account, CatalogItemType.Movie, "movie-1", expected);
        var provider = new FakeProvider();
        var lookup = store.Create(provider);

        var actual = await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1");

        Assert.Equal(expected, actual);
        Assert.Equal(0, provider.MovieCalls);
    }

    [Fact]
    public async Task NearSimultaneousRequestsForOneKeyMakeOneProviderCall()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider { MovieResult = new CatalogMetadata(Plot: "summary") };
        provider.MovieDelay = async token => await Task.Delay(50, token);
        var lookup = store.Create(provider);

        var results = await Task.WhenAll(
            lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"),
            lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"));

        Assert.All(results, result => Assert.Equal("summary", result?.Plot));
        Assert.Equal(1, provider.MovieCalls);
    }

    [Fact]
    public async Task RequestsForDifferentKeysNeverOverlap()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider { MovieResult = new CatalogMetadata(Plot: "summary") };
        provider.MovieDelay = async token => await Task.Delay(40, token);
        var lookup = store.Create(provider);

        await Task.WhenAll(
            lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"),
            lookup.GetAsync(Account, CatalogItemType.Movie, "movie-2"));

        Assert.Equal(1, provider.MaximumConcurrentMovieCalls);
    }

    [Fact]
    public async Task CancellationWhileWaitingForGatePreventsProviderCall()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider { MovieResult = new CatalogMetadata(Plot: "summary") };
        var providerStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseProvider = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        provider.MovieDelay = async token =>
        {
            providerStarted.TrySetResult();
            await releaseProvider.Task.WaitAsync(token);
        };
        var secondRead = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        store.ReadObserver = readNumber =>
        {
            if (readNumber == 2) secondRead.TrySetResult();
        };
        var lookup = store.Create(provider);

        var first = lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1");
        await providerStarted.Task;
        using var cancellation = new CancellationTokenSource();
        var waiting = lookup.GetAsync(Account, CatalogItemType.Movie, "movie-2", cancellation.Token);
        await secondRead.Task;
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiting);
        releaseProvider.TrySetResult();
        await first;

        Assert.Equal(1, provider.MovieCalls);
    }

    [Fact]
    public async Task SuccessfulEmptyMetadataIsPersisted()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider { MovieResult = new CatalogMetadata() };
        var lookup = store.Create(provider);

        var result = await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1");

        Assert.Equal(new CatalogMetadata(), result);
        Assert.Equal(result, store.Get(Account, CatalogItemType.Movie, "movie-1"));
    }

    [Fact]
    public async Task SeriesLookupUsesSeriesMetadataAndCachesIt()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider();
        var lookup = store.Create(provider);

        var result = await lookup.GetAsync(Account, CatalogItemType.Series, "series-1");

        Assert.Equal("series summary", result?.Plot);
        Assert.Equal(1, provider.SeriesCalls);
        Assert.Equal(result, store.Get(Account, CatalogItemType.Series, "series-1"));
    }

    [Fact]
    public async Task CacheAndCooldownKeysIncludeAccountId()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider { MovieResult = new CatalogMetadata(Plot: "summary") };
        var lookup = store.Create(provider);
        var otherAccount = Account with { AccountId = "account-b" };

        await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1");
        await lookup.GetAsync(otherAccount, CatalogItemType.Movie, "movie-1");

        Assert.Equal(2, provider.MovieCalls);
    }

    [Fact]
    public async Task FailureCanRetryAfterCooldown()
    {
        var store = new MetadataStore();
        var clock = new ManualTimeProvider(DateTimeOffset.Parse("2026-01-01T00:00:00Z"));
        var provider = new FakeProvider { MovieResult = new CatalogMetadata(Plot: "recovered"), FailMovieCalls = 1 };
        var lookup = store.Create(provider, clock);

        Assert.Null(await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"));
        Assert.Null(await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"));
        Assert.Equal(1, provider.MovieCalls);

        clock.Advance(TimeSpan.FromSeconds(30));
        Assert.Equal("recovered", (await lookup.GetAsync(Account, CatalogItemType.Movie, "movie-1"))?.Plot);
        Assert.Equal(2, provider.MovieCalls);
    }

    [Fact]
    public async Task LiveMakesNoProviderOrRepositoryCalls()
    {
        var store = new MetadataStore();
        var provider = new FakeProvider();
        var lookup = store.Create(provider);

        Assert.Null(await lookup.GetAsync(Account, CatalogItemType.Live, "channel-1"));

        Assert.Equal(0, store.Reads);
        Assert.Equal(0, provider.MovieCalls);
        Assert.Equal(0, provider.SeriesCalls);
    }

    private sealed class MetadataStore
    {
        private readonly ConcurrentDictionary<(string AccountId, CatalogItemType Type, string ItemId), CatalogMetadata> _items = new();
        private int _reads;
        public int Reads => Volatile.Read(ref _reads);
        public Action<int>? ReadObserver { get; set; }

        public CatalogMetadata? Get(ProviderAccount account, CatalogItemType type, string itemId)
        {
            var number = Interlocked.Increment(ref _reads);
            ReadObserver?.Invoke(number);
            return _items.GetValueOrDefault((account.AccountId, type, itemId));
        }

        public void Save(ProviderAccount account, CatalogItemType type, string itemId, CatalogMetadata metadata) =>
            _items[(account.AccountId, type, itemId)] = metadata;

        public HoverMetadataLookup Create(FakeProvider provider, TimeProvider? clock = null) =>
            new(provider, Get, Save, clock);
    }

    private sealed class FakeProvider : ICatalogProvider, IMovieInfoProvider, ISeriesCatalogProvider
    {
        private int _movieCalls;
        private int _seriesCalls;
        private int _activeMovieCalls;
        private int _maximumConcurrentMovieCalls;
        public int MovieCalls => Volatile.Read(ref _movieCalls);
        public int SeriesCalls => Volatile.Read(ref _seriesCalls);
        public int MaximumConcurrentMovieCalls => Volatile.Read(ref _maximumConcurrentMovieCalls);
        public CatalogMetadata? MovieResult { get; init; } = new CatalogMetadata();
        public int FailMovieCalls { get; init; }
        public Func<CancellationToken, Task>? MovieDelay { get; set; }

        public Task<AuthenticationResult> AuthenticateAsync(ProviderConnection connection, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public async Task<MovieDetails> GetMovieInfoAsync(ProviderAccount account, string movieId, CancellationToken cancellationToken = default)
        {
            var call = Interlocked.Increment(ref _movieCalls);
            var active = Interlocked.Increment(ref _activeMovieCalls);
            UpdateMaximum(active);
            try
            {
                if (call <= FailMovieCalls) throw new InvalidOperationException("fake failure");
                if (MovieDelay is not null) await MovieDelay(cancellationToken);
                return new MovieDetails(movieId, MovieResult ?? new CatalogMetadata());
            }
            finally
            {
                Interlocked.Decrement(ref _activeMovieCalls);
            }
        }

        public Task<SeriesDetails> GetSeriesInfoAsync(ProviderAccount account, string seriesId, CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref _seriesCalls);
            return Task.FromResult(new SeriesDetails(seriesId, "Series", Array.Empty<SeriesSeason>(), new CatalogMetadata(Plot: "series summary")));
        }

        private void UpdateMaximum(int active)
        {
            int current;
            while (active > (current = Volatile.Read(ref _maximumConcurrentMovieCalls)) &&
                   Interlocked.CompareExchange(ref _maximumConcurrentMovieCalls, active, current) != current) { }
        }
    }

    private sealed class ManualTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan amount) => _now += amount;
    }
}
