using System.Net;
using System.Text;
using Tvivo.Core;
using Tvivo.App.Pages;
using Tvivo.App;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class AppSmokeTests
{
    [Fact]
    public void Tvivo_data_root_uses_override_and_preserves_default_path_shape()
    {
        Assert.Equal(Path.GetFullPath("D:/Scratch/msi-slice3/data"),
            TvivoDataPaths.ResolveRoot("D:/Scratch/msi-slice3/data", "C:/Users/test/AppData/Local"));
        Assert.Equal(Path.Combine("C:/Users/test/AppData/Local", "Tvivo"),
            TvivoDataPaths.ResolveRoot(null, "C:/Users/test/AppData/Local"));
        Assert.Equal(Path.Combine("C:/Users/test/AppData/Local", "Tvivo"),
            TvivoDataPaths.ResolveRoot("  ", "C:/Users/test/AppData/Local"));
    }

    [Fact]
    public void Connection_error_copy_does_not_display_exception_details()
    {
        var sensitive = "https://private.example/account C:\\Users\\Someone\\secret.txt";
        var exception = new InvalidOperationException(sensitive);
        var messages = new[]
        {
            ConnectionErrorText.SavedConnection(exception),
            ConnectionErrorText.Authentication(exception),
            ConnectionErrorText.SaveConnection(exception),
            ConnectionErrorText.SignOut(exception),
        };

        Assert.Equal(new[]
        {
            "Could not load your saved connection. Try signing in again.",
            "Couldn't connect. Check the server address and your account details, then try again.",
            "Couldn't save your connection. Please try again.",
            "Couldn't sign out cleanly. Try again, or restart the app.",
        }, messages);
        Assert.All(messages, message =>
        {
            Assert.DoesNotContain("private.example", message, StringComparison.Ordinal);
            Assert.DoesNotContain("secret.txt", message, StringComparison.Ordinal);
        });
    }

    [Fact]
    public void AppTestProjectCanUseCoreContracts()
    {
        var token = new PlaybackSessionToken(1, Guid.NewGuid());
        Assert.Equal(1, token.Generation);
    }

    [Fact]
    public async Task Epg_coordinator_default_does_not_call_provider_or_read_epg_rows()
    {
        var provider = new NoCallEpgProvider();
        using var coordinator = new EpgCoordinator(provider);
        var account = new ProviderAccount("account", new ProviderEndpoint("http", "example.invalid", 80), "user");
        var connection = new ProviderConnection(new ProviderEndpoint("http", "example.invalid", 80), "user", "password");

        await coordinator.StartIfDueAsync(account, connection);

        Assert.Empty(coordinator.GetNowNext(account, new[] { "epg-channel" }));
        Assert.Equal(0, provider.Calls);
    }

    [Fact]
    public void Epg_off_hides_stale_now_next_rows_from_live_ui()
    {
        var staleRows = new EpgNowNext(
            new EpgProgramme("old-now", DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddMinutes(25), "Old programme"),
            new EpgProgramme("old-next", DateTimeOffset.UtcNow.AddMinutes(25), DateTimeOffset.UtcNow.AddMinutes(55), "Next programme"));

        Assert.Null(CatalogLandingPage.FilterEpgForUi(new EpgFeature().Enabled, staleRows));
    }

    private sealed class NoCallEpgProvider : IEpgProvider
    {
        public int Calls { get; private set; }

        public Task<EpgProbeResult> ProbeAsync(ProviderAccount account, ProviderConnection connection, string streamId,
            CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult(new EpgProbeResult(EpgCapability.Empty));
        }

        public Task<Stream> OpenProgrammeStreamAsync(ProviderAccount account, ProviderConnection connection,
            CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult<Stream>(Stream.Null);
        }

        public Task<IReadOnlyList<EpgChannelMap>> GetEpgChannelMapAsync(ProviderAccount account,
            CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult<IReadOnlyList<EpgChannelMap>>(Array.Empty<EpgChannelMap>());
        }
    }

    [Fact]
    public void Unverified_end_keeps_app_progress_unfinished()
    {
        const long duration = 20 * 60_000;
        var earlyEnd = duration - 35_000;

        Assert.False(PlaybackEndPolicy.IsGenuineEnd(earlyEnd, duration, ended: true));
        Assert.False(EpisodeCompletion.IsFinished(earlyEnd, duration, ended: true));
        Assert.False(MovieCompletion.IsFinished(earlyEnd, duration, ended: true));
        Assert.True(PlaybackEndPolicy.IsGenuineEnd(duration - 30_000, duration, ended: true));
        Assert.False(PlaybackEndPolicy.IsGenuineEnd(0, null, ended: true));
    }

    [Fact]
    public void Failed_playback_generation_keeps_retry_and_blocks_completion_advance()
    {
        var policy = new PlaybackFailurePolicy();

        Assert.True(policy.TryHandle(callbackGeneration: 8, currentGeneration: 8, activeGeneration: 8, ready: true));
        Assert.False(policy.TryHandle(callbackGeneration: 8, currentGeneration: 8, activeGeneration: 8, ready: true));
        Assert.False(policy.TryHandle(callbackGeneration: 8, currentGeneration: 9, activeGeneration: 9, ready: true));
        Assert.False(policy.ShouldAutoAdvance(8));
        Assert.True(policy.ShouldAutoAdvance(9));
        Assert.False(policy.TryHandle(callbackGeneration: 10, currentGeneration: 10, activeGeneration: 9, ready: false));
    }

    [Fact]
    public void Artwork_reuse_requires_the_same_item_url_and_current_source()
    {
        Assert.True(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/item-1", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-2", "https://art/item-1", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/new", true));
        Assert.False(ArtworkReusePolicy.ShouldReuse("item-1", "https://art/item-1", "item-1", "https://art/item-1", false));
    }

    [Fact]
    public void Artwork_budget_counts_decoded_pixels_and_evicts_only_when_over_limit()
    {
        const long limit = 96L * 1024 * 1024;
        Assert.Equal(760_000, ArtworkMemoryBudget.EstimateDecodedBytes(380, 500));
        Assert.False(ArtworkMemoryBudget.ShouldEvict(limit, limit, 2, 128));
        Assert.True(ArtworkMemoryBudget.ShouldEvict(limit + 1, limit, 2, 128));
        Assert.True(ArtworkMemoryBudget.ShouldEvict(limit, limit, 129, 128));
        Assert.False(ArtworkMemoryBudget.ShouldEvict(limit + 1, limit, 1, 128));
    }

    [Fact]
    public async Task Artwork_bytes_survive_view_cancellation_and_restart_for_two_decode_sizes()
    {
        var root = Path.Combine("D:/Scratch", $"tvivo-app-artwork-{Guid.NewGuid():N}", "artwork-cache");
        Directory.CreateDirectory(root);
        try
        {
            var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var gets = 0;
            using var client = new HttpClient(new ArtworkHandler(async () =>
            {
                Interlocked.Increment(ref gets);
                started.TrySetResult();
                await release.Task;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(Encoding.UTF8.GetBytes("original image bytes"))
                };
            }));
            var url = new Uri("https://images.example.test/shared-poster");
            var firstView = new ArtworkDiskCache(client, root);
            using var canceledView = new CancellationTokenSource();
            var canceled = firstView.GetAsync(url, canceledView.Token);
            var surviving = firstView.GetAsync(url);
            await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
            canceledView.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => canceled);
            release.SetResult();

            var decodedSizes = new List<(int Width, int Height)>();
            void Decode(byte[]? bytes, int width, int height)
            {
                Assert.Equal("original image bytes", Encoding.UTF8.GetString(Assert.IsType<byte[]>(bytes)));
                decodedSizes.Add((width, height));
            }
            Decode(await surviving, 380, 500);
            Decode(await firstView.GetAsync(url), 600, 336);
            var laterView = new ArtworkDiskCache(client, root);
            Decode(await laterView.GetAsync(url), 320, 450);

            Assert.Equal(1, gets);
            Assert.Equal(new[] { (380, 500), (600, 336), (320, 450) }, decodedSizes);
        }
        finally
        {
            Directory.Delete(Path.GetDirectoryName(root)!, recursive: true);
        }
    }

    [Fact]
    public async Task Spotlight_warm_set_rotates_offline_and_clear_refetches_lazily()
    {
        var root = Path.Combine("D:/Scratch", $"tvivo-spotlight-artwork-{Guid.NewGuid():N}", "artwork-cache");
        Directory.CreateDirectory(root);
        try
        {
            var onlineGets = 0;
            using var onlineClient = new HttpClient(new ArtworkHandler(() =>
            {
                Interlocked.Increment(ref onlineGets);
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(Encoding.UTF8.GetBytes("spotlight image bytes"))
                });
            }));
            var cache = new ArtworkDiskCache(onlineClient, root);
            var urls = Enumerable.Range(0, 10)
                .Select(index => new Uri($"https://images.example.test/spotlight-{index}"))
                .ToArray();
            var warmCount = CatalogLandingPage.SpotlightWarmCount(urls.Length);

            Assert.Equal(6, warmCount);
            Assert.Equal(6, CatalogLandingPage.NextSpotlightIndex(10, 5, 1, automatic: false));
            for (var index = 0; index < warmCount; index++)
                Assert.NotNull(await cache.GetAsync(urls[index]));
            Assert.Equal(warmCount, onlineGets);

            var selected = 0;
            for (var rotation = 0; rotation < warmCount * 2; rotation++)
            {
                selected = CatalogLandingPage.NextSpotlightIndex(urls.Length, selected, 1, automatic: true);
                Assert.InRange(selected, 0, warmCount - 1);
                Assert.NotNull(await cache.GetAsync(urls[selected]));
            }
            Assert.Equal(warmCount, onlineGets);

            var manualIndex = CatalogLandingPage.NextSpotlightIndex(urls.Length, warmCount - 1, 1, automatic: false);
            Assert.Equal(warmCount, manualIndex);
            Assert.NotNull(await cache.GetAsync(urls[manualIndex]));
            Assert.Equal(warmCount + 1, onlineGets);

            var offlineGets = 0;
            using var offlineClient = new HttpClient(new ArtworkHandler(() =>
            {
                Interlocked.Increment(ref offlineGets);
                return Task.FromException<HttpResponseMessage>(new HttpRequestException("offline"));
            }));
            var restartedCache = new ArtworkDiskCache(offlineClient, root);
            for (var index = 0; index < warmCount; index++)
                Assert.NotNull(await restartedCache.GetAsync(urls[index]));
            Assert.Equal(0, offlineGets);

            cache.Clear();
            Assert.Equal(0, cache.TotalBytes);
            Assert.NotNull(await cache.GetAsync(urls[0]));
            Assert.Equal(warmCount + 2, onlineGets);
        }
        finally
        {
            var directory = Path.GetDirectoryName(root)!;
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }

    private sealed class ArtworkHandler(Func<Task<HttpResponseMessage>> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            respond();
    }

    [Fact]
    public void My_Tvivo_shelves_are_type_specific_and_ordered_by_concept_then_type()
    {
        Assert.Equal(new[]
        {
            "Continue watching Movies",
            "Continue watching Series",
            "Recently added Movies",
            "Recently added Series",
            "Recently added Live TV",
            "Recently played Movies",
            "Recently played Series",
            "Recently played Live TV",
            "Favorite Movies",
            "Favorite Series",
            "Favorite Live TV",
        }, MyTvivoShelfDefinitions.All.Select(shelf => shelf.Title));
        Assert.Equal(new[]
        {
            CatalogItemType.Movie, CatalogItemType.Series,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
            CatalogItemType.Movie, CatalogItemType.Series, CatalogItemType.Live,
        }, MyTvivoShelfDefinitions.All.Select(shelf => shelf.Type));
    }

    [Fact]
    public void Empty_shelves_are_hidden_and_folder_pages_end_on_a_complete_nine_column_row()
    {
        Assert.False(ShelfVisibilityPolicy.HasItems(0));
        Assert.True(ShelfVisibilityPolicy.HasItems(1));
        Assert.Equal(99, CatalogGridPaging.PageSize);
        Assert.Equal(0, CatalogGridPaging.PageSize % 9);
    }

    [Fact]
    public void Catalog_snapshot_transition_reveals_first_content_and_fades_mode_changes()
    {
        Assert.True(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: false, modeChanged: false));
        Assert.True(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: true, modeChanged: true));
        Assert.False(CatalogTransitionPolicy.ShouldAnimateIncomingContent(
            hasRenderedSnapshot: true, modeChanged: false));
    }

    [Fact]
    public void Home_snapshot_is_reused_only_after_a_successful_load_for_same_account_and_data_version()
    {
        Assert.True(CatalogReloadPolicy.ShouldReuseHome(hasSuccessfulLoad: true, sameAccount: true, dataVersionMatches: true));
        Assert.False(CatalogReloadPolicy.ShouldReuseHome(hasSuccessfulLoad: false, sameAccount: true, dataVersionMatches: true));
        Assert.False(CatalogReloadPolicy.ShouldReuseHome(hasSuccessfulLoad: true, sameAccount: false, dataVersionMatches: true));
        Assert.False(CatalogReloadPolicy.ShouldReuseHome(hasSuccessfulLoad: true, sameAccount: true, dataVersionMatches: false));
    }

    [Fact]
    public async Task Catalog_refresh_routes_by_type_and_deduplicates_account_type_tasks()
    {
        var account = new ProviderAccount("account-a", new ProviderEndpoint("https", "example.invalid", 0), "user");
        var other = account with { AccountId = "account-b" };
        var gate = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = new List<(string AccountId, CatalogItemType Type)>();
        var sync = new object();
        var tasks = new CatalogRefreshTasks(async (requested, type) =>
        {
            lock (sync) calls.Add((requested.AccountId, type));
            await gate.Task;
            return true;
        });

        var movie = tasks.RefreshTypeAsync(account, CatalogLandingPage.TypesForMode(CatalogLandingPage.CatalogMode.Movies)[0]);
        var sameMovie = tasks.RefreshTypeAsync(account, CatalogItemType.Movie);
        var otherMovie = tasks.RefreshTypeAsync(other, CatalogItemType.Movie);
        var global = CatalogLandingPage.TypesForMode(CatalogLandingPage.CatalogMode.MyTvivo)
            .Select(type => tasks.RefreshTypeAsync(account, type)).ToArray();
        Assert.Same(movie, sameMovie);
        Assert.Same(movie, global[1]);
        gate.SetResult(true);
        Assert.All(await Task.WhenAll(global.Append(otherMovie)), result => Assert.True(result));
        lock (sync)
            Assert.Equal(new[]
            {
                ("account-a", CatalogItemType.Live),
                ("account-a", CatalogItemType.Movie),
                ("account-a", CatalogItemType.Series),
                ("account-b", CatalogItemType.Movie),
            }, calls.OrderBy(call => call.AccountId).ThenBy(call => call.Type).ToArray());
    }

    [Fact]
    public void Catalog_refresh_policy_survives_reopen_and_updated_copy_uses_persisted_stamp()
    {
        var path = Path.Combine(Path.GetTempPath(), "tvivo-refresh-" + Guid.NewGuid().ToString("N"), "catalog-options.json");
        try
        {
            foreach (var policy in Enum.GetValues<CatalogRefreshPolicy>())
            {
                new CatalogRefreshPolicyStore(path).Write(policy);
                Assert.Equal(policy, new CatalogRefreshPolicyStore(path).Read());
            }
            Assert.Equal("Updated never", CatalogLandingPage.FormatUpdated(null, DateTimeOffset.UtcNow));
            var now = new DateTimeOffset(2026, 10, 7, 12, 0, 0, TimeSpan.Zero);
            Assert.Equal("Updated 2 days ago", CatalogLandingPage.FormatUpdated(now.AddDays(-2), now));
            Assert.False(CatalogLandingPage.ShouldAutoRefresh(CatalogRefreshPolicy.OnDemandOnly,
                null, now, refreshedThisStart: false, canAttempt: true));
            Assert.True(CatalogLandingPage.ShouldAutoRefresh(CatalogRefreshPolicy.OlderThanOneDay,
                now.AddDays(-2), now, refreshedThisStart: false, canAttempt: true));
            Assert.False(CatalogLandingPage.ShouldAutoRefresh(CatalogRefreshPolicy.OlderThanSevenDays,
                now.AddDays(-2), now, refreshedThisStart: false, canAttempt: true));
            Assert.False(CatalogLandingPage.ShouldAutoRefresh(CatalogRefreshPolicy.EveryStart,
                now.AddDays(-2), now, refreshedThisStart: true, canAttempt: true));
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            var directory = Path.GetDirectoryName(path)!;
            if (Directory.Exists(directory)) Directory.Delete(directory);
        }
    }

    [Fact]
    public void Spotlight_indicator_window_stays_small_and_keeps_the_selected_item_visible()
    {
        var (start, count) = SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 40, selectedIndex: 23);

        Assert.Equal(7, count);
        Assert.InRange(23, start, start + count - 1);
        Assert.Equal((0, 0), SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 0, selectedIndex: 0));
        Assert.Equal((33, 7), SpotlightIndicatorPolicy.GetVisibleWindow(itemCount: 40, selectedIndex: 39));
    }

    [Fact]
    public void Series_episode_favorite_routes_to_the_parent_and_has_no_per_episode_star()
    {
        var episode = new Channel("account", "episode-1", "series-1", "Episode", "Episode", null, null, null,
            new StreamSource("episode-1", StreamKind.Episode),
            new Dictionary<string, string> { ["seriesId"] = "series-1" });
        var movie = new Channel("account", "movie-1", null, "Movie", "Movie", null, null, null,
            new StreamSource("movie-1", StreamKind.Movie), new Dictionary<string, string>());
        var live = new Channel("account", "live-1", null, "Live", "Live", null, null, null,
            new StreamSource("live-1", StreamKind.Live), new Dictionary<string, string>());

        Assert.Equal((CatalogItemType.Series, "series-1"), FavoriteTargetResolver.Resolve(episode));
        Assert.False(FavoriteTargetResolver.ShowsPerItemFavorite(episode));
        Assert.True(FavoriteTargetResolver.ShowsPerItemFavorite(movie));
        Assert.True(FavoriteTargetResolver.ShowsPerItemFavorite(live));
    }
}
