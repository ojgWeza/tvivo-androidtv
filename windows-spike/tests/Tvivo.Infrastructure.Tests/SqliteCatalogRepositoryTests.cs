using Tvivo.Core;
using Tvivo.Infrastructure;
using System.Net;
using System.Text;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class SqliteCatalogRepositoryTests
{
    private static ProviderAccount Account(string id = "account-a") => new(id, new("http", "fixture.invalid", 8080), "fixture");
    private static ChannelGroup Group(ProviderAccount a, string id, string name) => new(a.AccountId, id, name, name);
    private static Channel ChannelFor(ProviderAccount a, string id, string group, string title) => new(a.AccountId, id, group, title, title, null, null, null, new(id, StreamKind.Live, "ts"), new Dictionary<string,string>());
    private static Channel ChannelAdded(ProviderAccount a, CatalogItemType type, string id, DateTimeOffset added) =>
        new(a.AccountId, id, null, id, id, null, added, null,
            new(id, type == CatalogItemType.Movie ? StreamKind.Movie : type == CatalogItemType.Series ? StreamKind.Series : StreamKind.Live,
                type == CatalogItemType.Live ? "ts" : "mkv"), new Dictionary<string, string>());
    private static SqliteCatalogRepository Create(out string directory)
    {
        directory = Path.Combine(Path.GetTempPath(), "tvivo-catalog-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        return new(Path.Combine(directory, "catalog.sqlite"));
    }

    [Fact]
    public void Refresh_replaces_snapshot_and_reads_pages_and_filters()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"1","g","Alpha"), ChannelFor(account,"2","g","Beta"), ChannelFor(account,"3","g","Bravo") });
            Assert.Equal(3, repo.GetChannels(account, CatalogItemType.Live, "g", limit: 2).TotalCount);
            Assert.Equal(new[] { "1", "2" }, repo.GetChannels(account, CatalogItemType.Live, "g", limit: 2).Items.Select(x => x.Id));
            Assert.Equal(new[] { "3" }, repo.GetChannels(account, CatalogItemType.Live, "g", "rav").Items.Select(x => x.Id));
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Grouped_shelf_query_filters_rows_with_literal_escape_character()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, new[] { Group(account, "g", "Movies") }, new[]
            {
                ChannelFor(account, "match", "g", "Ramadan Nights"),
                ChannelFor(account, "miss", "g", "Winter Story"),
            });

            var shelves = repo.GetChannelsGroupedByCategory(account, null, CatalogItemType.Movie, "Ramadan", limit: 12);
            Assert.Equal("match", Assert.Single(shelves["g"].Items).Id);
            Assert.Equal(1, shelves["g"].TotalCount);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Catalog_queries_are_isolated_by_item_type()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account, "live-group", "Live") }, new[] { ChannelFor(account, "shared-id", "live-group", "Live item") });
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, new[] { Group(account, "movie-group", "Movies") }, new[] { ChannelFor(account, "shared-id", "movie-group", "Movie item") });
            repo.ReplaceSnapshot(account, CatalogItemType.Series, new[] { Group(account, "series-group", "Series") }, new[] { ChannelFor(account, "series-id", "series-group", "Series item") });

            Assert.Equal("Live item", Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).DisplayName);
            Assert.Equal(StreamKind.Live, Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).Source.Kind);
            Assert.Equal("Movie item", Assert.Single(repo.GetChannels(account, CatalogItemType.Movie).Items).DisplayName);
            Assert.Equal(StreamKind.Movie, Assert.Single(repo.GetChannels(account, CatalogItemType.Movie).Items).Source.Kind);
            Assert.Equal("Series item", Assert.Single(repo.GetChannels(account, CatalogItemType.Series).Items).DisplayName);
            Assert.Equal(StreamKind.Series, Assert.Single(repo.GetChannels(account, CatalogItemType.Series).Items).Source.Kind);
            Assert.Equal("Movies", Assert.Single(repo.GetGroups(account, CatalogItemType.Movie)).DisplayName);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Refresh_cleans_empty_bracket_title_artifacts_for_every_catalog_type()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            foreach (var type in new[] { CatalogItemType.Live, CatalogItemType.Movie, CatalogItemType.Series })
            {
                repo.ReplaceSnapshot(account, type, new[] { Group(account, type.ToString(), type.ToString()) }, new[]
                {
                    ChannelFor(account, type + "-empty", type.ToString(), "Film ( ) HD"),
                    ChannelFor(account, type + "-only", type.ToString(), "( )"),
                    ChannelFor(account, type + "-real", type.ToString(), "Show (2024)")
                });
                var titles = repo.GetChannels(account, type).Items.ToDictionary(item => item.Id, item => item.DisplayName);
                Assert.Equal("Film HD", titles[type + "-empty"]);
                Assert.Equal("Untitled", titles[type + "-only"]);
                Assert.Equal("Show (2024)", titles[type + "-real"]);
            }
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Artwork_uri_survives_catalog_snapshot_write_and_browse_projection()
    {
        using var repo = Create(out var dir); var account = Account();
        var artworkUri = new Uri("https://cdn.example.test/posters/movie-42.jpg");
        var movie = new Channel(account.AccountId, "movie-42", "movies", "A Movie", "A Movie", artworkUri,
            null, null, new("movie-42", StreamKind.Movie, "mkv"), new Dictionary<string, string>());
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Movie,
                new[] { Group(account, "movies", "Movies") }, new[] { movie });

            var page = Assert.Single(repo.GetChannels(account, CatalogItemType.Movie).Items);
            Assert.Equal(artworkUri, page.LogoUri);

            var shelfPage = Assert.Single(repo.GetChannelsGroupedByCategory(account, null, CatalogItemType.Movie)["movies"].Items);
            Assert.Equal(artworkUri, shelfPage.LogoUri);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Catalog_metadata_round_trips_and_survives_snapshot_refresh()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var movie = new Channel(account.AccountId, "movie-1", "g", "Movie", "Movie", null, null, null,
                new("movie-1", StreamKind.Movie, "mkv"), new Dictionary<string, string>());
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, new[] { Group(account, "g", "Movies") }, new[] { movie });
            var metadata = new CatalogMetadata("2024", "8.2", "Drama", "A cached plot.", "Actor One");
            repo.SaveMetadata(account, CatalogItemType.Movie, movie.Id, metadata);

            repo.ReplaceSnapshot(account, CatalogItemType.Movie, new[] { Group(account, "g", "Movies") }, new[] { movie });

            Assert.Equal(metadata, repo.GetMetadata(account, CatalogItemType.Movie, movie.Id));
            var cachedChannel = Assert.Single(repo.GetChannels(account, CatalogItemType.Movie).Items);
            Assert.Equal("A cached plot.", cachedChannel.Metadata["plot"]);
            Assert.Equal("2024", cachedChannel.Metadata["year"]);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void My_Tvivo_order_uses_visit_count_then_letters_digits_symbols_fallback()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, Array.Empty<ChannelGroup>(), new[]
            {
                ChannelFor(account, "symbol", "", "! Symbol"),
                ChannelFor(account, "number", "", "42 Number"),
                ChannelFor(account, "latin", "", "Zulu"),
                ChannelFor(account, "arabic", "", "العربية"),
            });

            Assert.Equal(new[] { "latin", "arabic", "number", "symbol" },
                repo.GetChannels(account, CatalogItemType.Movie, limit: 4, mostVisited: true).Items.Select(item => item.Id));
            repo.RecordVisit(account, CatalogItemType.Movie, "symbol");
            repo.RecordVisit(account, CatalogItemType.Movie, "latin");
            repo.RecordVisit(account, CatalogItemType.Movie, "latin");
            Assert.Equal(new[] { "latin", "symbol", "arabic", "number" },
                repo.GetChannels(account, CatalogItemType.Movie, limit: 4, mostVisited: true).Items.Select(item => item.Id));
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, Array.Empty<ChannelGroup>(), new[]
            {
                ChannelFor(account, "symbol", "", "! Symbol"),
                ChannelFor(account, "number", "", "42 Number"),
                ChannelFor(account, "latin", "", "Zulu"),
                ChannelFor(account, "arabic", "", "العربية"),
            });
            Assert.Equal(new[] { "latin", "symbol", "arabic", "number" },
                repo.GetChannels(account, CatalogItemType.Movie, limit: 4, mostVisited: true).Items.Select(item => item.Id));
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Recently_added_is_provider_timestamp_ordered_across_catalog_types()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var older = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
            var newer = DateTimeOffset.Parse("2026-03-01T00:00:00Z");
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, Array.Empty<ChannelGroup>(), new[] { ChannelAdded(account, CatalogItemType.Movie, "movie-old", older) });
            repo.ReplaceSnapshot(account, CatalogItemType.Series, Array.Empty<ChannelGroup>(), new[] { ChannelAdded(account, CatalogItemType.Series, "series-new", newer) });
            repo.ReplaceSnapshot(account, CatalogItemType.Live, Array.Empty<ChannelGroup>(), new[] { ChannelAdded(account, CatalogItemType.Live, "live-mid", older.AddDays(10)) });

            Assert.Equal(new[] { "series-new", "live-mid", "movie-old" },
                repo.GetRecentlyAdded(account, limit: 10).Select(item => item.Id));
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Type_filtered_activity_shelves_keep_order_and_apply_the_title_filter()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var earlier = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
            var later = DateTimeOffset.Parse("2026-03-01T00:00:00Z");
            repo.ReplaceSnapshot(account, CatalogItemType.Series, Array.Empty<ChannelGroup>(), new[]
            {
                ChannelAdded(account, CatalogItemType.Series, "series-added", later.AddDays(2)),
                ChannelFor(account, "series-played", "", "Series played"),
                ChannelFor(account, "series-favorite", "", "Series favorite"),
            });
            repo.ReplaceSnapshot(account, CatalogItemType.Live, Array.Empty<ChannelGroup>(), new[]
            {
                ChannelAdded(account, CatalogItemType.Live, "live-added", later.AddDays(3)),
                ChannelFor(account, "live-played", "", "Live played"),
                ChannelFor(account, "live-favorite", "", "Live favorite"),
            });
            repo.ReplaceSnapshot(account, CatalogItemType.Movie, Array.Empty<ChannelGroup>(), new[]
            {
                ChannelAdded(account, CatalogItemType.Movie, "movie-added-old", earlier) with { Name = "Needle old", DisplayName = "Needle old" },
                ChannelAdded(account, CatalogItemType.Movie, "movie-added-new", later) with { Name = "Needle new", DisplayName = "Needle new" },
                ChannelAdded(account, CatalogItemType.Movie, "movie-added-filtered", later.AddDays(1)) with { Name = "Other title", DisplayName = "Other title" },
                ChannelFor(account, "movie-played-old", "", "Movie played old"),
                ChannelFor(account, "movie-played-new", "", "Movie played new"),
                ChannelFor(account, "movie-favorite-old", "", "Movie favorite old"),
                ChannelFor(account, "movie-favorite-new", "", "Movie favorite new"),
            });

            repo.RecordVisit(account, CatalogItemType.Movie, "movie-played-old");
            Thread.Sleep(10);
            repo.RecordVisit(account, CatalogItemType.Movie, "movie-played-new");
            repo.RecordVisit(account, CatalogItemType.Series, "series-played");
            repo.RecordVisit(account, CatalogItemType.Live, "live-played");
            repo.AddFavorite(account, CatalogItemType.Movie, "movie-favorite-old");
            Thread.Sleep(10);
            repo.AddFavorite(account, CatalogItemType.Movie, "movie-favorite-new");
            repo.AddFavorite(account, CatalogItemType.Series, "series-favorite");
            repo.AddFavorite(account, CatalogItemType.Live, "live-favorite");

            Assert.Equal(new[] { "movie-added-new", "movie-added-old" },
                repo.GetRecentlyAdded(account, CatalogItemType.Movie, limit: 10, filter: "Needle").Select(item => item.Id));
            Assert.Equal(new[] { "series-added" },
                repo.GetRecentlyAdded(account, CatalogItemType.Series).Select(item => item.Id));
            Assert.Equal(new[] { "live-added" },
                repo.GetRecentlyAdded(account, CatalogItemType.Live).Select(item => item.Id));
            Assert.Equal(new[] { "movie-played-new", "movie-played-old" },
                repo.GetRecentlyPlayed(account, CatalogItemType.Movie).Select(item => item.Id));
            Assert.Equal(new[] { "series-played" },
                repo.GetRecentlyPlayed(account, CatalogItemType.Series).Select(item => item.Id));
            Assert.Equal(new[] { "live-played" },
                repo.GetRecentlyPlayed(account, CatalogItemType.Live).Select(item => item.Id));
            Assert.Equal(new[] { "movie-favorite-new", "movie-favorite-old" },
                repo.GetFavorites(account, CatalogItemType.Movie).Select(item => item.Id));
            Assert.Equal(new[] { "series-favorite" },
                repo.GetFavorites(account, CatalogItemType.Series).Select(item => item.Id));
            Assert.Equal(new[] { "live-favorite" },
                repo.GetFavorites(account, CatalogItemType.Live).Select(item => item.Id));
            Assert.All(repo.GetRecentlyAdded(account, CatalogItemType.Series), item => Assert.Equal(StreamKind.Series, item.Source.Kind));
            Assert.All(repo.GetRecentlyPlayed(account, CatalogItemType.Live), item => Assert.Equal(StreamKind.Live, item.Source.Kind));
            Assert.All(repo.GetFavorites(account, CatalogItemType.Series), item => Assert.Equal(StreamKind.Series, item.Source.Kind));
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Favorite_toggle_persists_and_added_played_favorite_shelves_are_distinct()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-catalog-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "catalog.sqlite");
        var account = Account();
        try
        {
            using (var repo = new SqliteCatalogRepository(path))
            {
                repo.ReplaceSnapshot(account, CatalogItemType.Movie, Array.Empty<ChannelGroup>(), new[]
                {
                    ChannelAdded(account, CatalogItemType.Movie, "added-only", DateTimeOffset.Parse("2026-04-01T00:00:00Z")),
                    ChannelFor(account, "played-only", "", "played-only"),
                    ChannelFor(account, "favorite-only", "", "favorite-only"),
                });
                repo.RecordVisit(account, CatalogItemType.Movie, "played-only");
                Assert.True(repo.ToggleFavorite(account, CatalogItemType.Movie, "favorite-only"));
                Assert.True(repo.IsFavorite(account, CatalogItemType.Movie, "favorite-only"));
            }

            using (var repo = new SqliteCatalogRepository(path))
            {
                Assert.Equal(new[] { "added-only" }, repo.GetRecentlyAdded(account).Select(item => item.Id));
                Assert.Equal(new[] { "played-only" }, repo.GetRecentlyPlayed(account).Select(item => item.Id));
                Assert.Equal(new[] { "favorite-only" }, repo.GetFavorites(account).Select(item => item.Id));
                Assert.False(repo.ToggleFavorite(account, CatalogItemType.Movie, "favorite-only"));
                Assert.False(repo.IsFavorite(account, CatalogItemType.Movie, "favorite-only"));
                Assert.Empty(repo.GetFavorites(account));
            }
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public void Series_playback_keeps_the_last_opened_episode_and_finished_state()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var missing = repo.GetSeriesPlayback(account, "show-1");
            Assert.Null(missing.EpisodeId);
            Assert.False(missing.Finished);
            repo.UpdateSeriesPlayback(account, "show-1", "episode-opaque-42", finished: false);
            var opened = repo.GetSeriesPlayback(account, "show-1");
            Assert.Equal("episode-opaque-42", opened.EpisodeId);
            Assert.False(opened.Finished);
            repo.UpdateSeriesPlayback(account, "show-1", "episode-opaque-42", finished: true);
            var finished = repo.GetSeriesPlayback(account, "show-1");
            Assert.Equal("episode-opaque-42", finished.EpisodeId);
            Assert.True(finished.Finished);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Opening_schema_v8_cleans_cached_titles_once_and_advances_schema()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-catalog-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "catalog.sqlite");
        try
        {
            using (var connection = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False"))
            {
                connection.Open();
                using var command = connection.CreateCommand();
                command.CommandText = "CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,favourite INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account_id,type,id)); INSERT INTO items(account_id,type,id,title) VALUES('a','Movie','1','Film ( ) HD'),('a','Series','2','( )'),('a','Live','3','Show (2024)'); PRAGMA user_version=8;";
                command.ExecuteNonQuery();
            }

            using (var repository = new SqliteCatalogRepository(path)) { }

            using (var migrated = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False"))
            {
                migrated.Open();
                using var verify = migrated.CreateCommand();
                verify.CommandText = "SELECT title FROM items ORDER BY id;";
                using (var reader = verify.ExecuteReader())
                {
                    Assert.True(reader.Read()); Assert.Equal("Film HD", reader.GetString(0));
                    Assert.True(reader.Read()); Assert.Equal("Untitled", reader.GetString(0));
                    Assert.True(reader.Read()); Assert.Equal("Show (2024)", reader.GetString(0));
                    Assert.False(reader.Read());
                }
                verify.CommandText = "PRAGMA user_version;";
                Assert.Equal(15, Convert.ToInt32(verify.ExecuteScalar()));
            }
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            Directory.Delete(directory, true);
        }
    }

    [Fact]
    public void Opening_schema_v14_migrates_existing_favorites_to_the_persisted_list()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-catalog-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "catalog.sqlite");
        try
        {
            using (var connection = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False"))
            {
                connection.Open();
                using var command = connection.CreateCommand();
                command.CommandText = "CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,favourite INTEGER NOT NULL,favourite_added_at INTEGER,PRIMARY KEY(account_id,type,id)); INSERT INTO items VALUES('account-a','Movie','old-favorite',1,1234); PRAGMA user_version=14;";
                command.ExecuteNonQuery();
            }

            using (var repo = new SqliteCatalogRepository(path))
                Assert.True(repo.IsFavorite(Account(), CatalogItemType.Movie, "old-favorite"));

            using var migrated = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False");
            migrated.Open();
            using var verify = migrated.CreateCommand();
            verify.CommandText = "PRAGMA user_version;";
            Assert.Equal(15, Convert.ToInt32(verify.ExecuteScalar()));
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task Failed_provider_refresh_preserves_previous_snapshot()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            var service = new CatalogRefreshService(new ThrowingProvider(), repo);
            await Assert.ThrowsAsync<InvalidOperationException>(() => service.RefreshAsync(account));
            Assert.Equal("old", Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).Id);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Refresh_error_identifies_failed_stage_and_a_new_attempt_runs_again()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var provider = new FailFirstRefreshProvider();
            var service = new CatalogRefreshService(provider, repo);
            var error = await Assert.ThrowsAsync<InvalidOperationException>(() => service.RefreshAsync(account));
            Assert.Contains("Refreshing Live categories failed", error.Message);

            await service.RefreshAsync(account);
            Assert.Equal(4, provider.GroupCalls);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Failed_snapshot_transaction_rolls_back_deletions_and_inserts()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            Assert.Throws<Microsoft.Data.Sqlite.SqliteException>(() => repo.ReplaceSnapshot(account, CatalogItemType.Live,
                new[] { Group(account,"new","New") }, new[] { ChannelFor(account,"dup","new","First"), ChannelFor(account,"dup","new","Duplicate") }));
            Assert.Equal("News", Assert.Single(repo.GetGroups(account, CatalogItemType.Live)).Name);
            Assert.Equal("old", Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).Id);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public void Account_keys_are_isolated()
    {
        using var repo = Create(out var dir); var a = Account(); var b = Account("account-b");
        try
        {
            repo.ReplaceSnapshot(a, CatalogItemType.Live, new[] { Group(a,"g","A") }, new[] { ChannelFor(a,"same","g","A channel") });
            repo.ReplaceSnapshot(b, CatalogItemType.Live, new[] { Group(b,"g","B") }, new[] { ChannelFor(b,"same","g","B channel") });
            Assert.Equal("A", Assert.Single(repo.GetGroups(a, CatalogItemType.Live)).Name);
            Assert.Equal("A channel", Assert.Single(repo.GetChannels(a, CatalogItemType.Live).Items).DisplayName);
            Assert.Equal("B", Assert.Single(repo.GetGroups(b, CatalogItemType.Live)).Name);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Zero_groups_and_channels_remain_empty_after_cache_backed_refresh()
    {
        using var repo = Create(out var dir); var account = Account();
        try
        {
            var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
            {
                var fixture = request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal) ? "empty.json" : "auth_success.json";
                var path = Path.Combine(AppContext.BaseDirectory, "Fixtures", fixture);
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(File.ReadAllText(path), Encoding.UTF8, "application/json") };
            })));
            var auth = await provider.AuthenticateAsync(new ProviderConnection(new("http", "panel.example.com", 8080), "fixture-user", "fixture-password"));
            account = Assert.IsType<ProviderAccount>(auth.Account);
            await new CatalogRefreshService(provider, repo).RefreshAsync(account);
            Assert.Empty(repo.GetGroups(account, CatalogItemType.Live));
            Assert.Empty(repo.GetChannels(account, CatalogItemType.Live).Items);
            Assert.Equal(0, repo.GetChannels(account, CatalogItemType.Live).TotalCount);
            // CatalogLandingPage selects EmptyState when both these cache-backed reads are empty.
            Assert.True(repo.GetGroups(account, CatalogItemType.Live).Count == 0 && repo.GetChannels(account, CatalogItemType.Live).TotalCount == 0);
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Authenticated_provider_fixtures_refresh_cache_and_failure_keeps_snapshot()
    {
        using var repo = Create(out var dir);
        try
        {
            var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
            {
                var query = request.RequestUri!.Query;
                var fixture = !query.Contains("action=", StringComparison.Ordinal) ? "auth_success.json" :
                    query.Contains("get_live_categories", StringComparison.Ordinal) ? "live_categories.json" :
                    query.Contains("get_live_streams", StringComparison.Ordinal) ? "live_streams.json" : "empty.json";
                var path = Path.Combine(AppContext.BaseDirectory, "Fixtures", fixture);
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(File.ReadAllText(path), Encoding.UTF8, "application/json") };
            })));
            var auth = await provider.AuthenticateAsync(new ProviderConnection(new("http", "panel.example.com", 8080), "fixture-user", "fixture-password"));
            var account = Assert.IsType<ProviderAccount>(auth.Account);
            await new CatalogRefreshService(provider, repo).RefreshAsync(account);
            Assert.Equal(new[] { "10", "11" }, repo.GetGroups(account, CatalogItemType.Live).Select(x => x.Id));
            Assert.Equal(new[] { "opaque-42", "123" }, repo.GetChannels(account, CatalogItemType.Live).Items.Select(x => x.Id));

            await Assert.ThrowsAsync<InvalidOperationException>(() => new CatalogRefreshService(new ThrowingProvider(), repo).RefreshAsync(account));
            Assert.Equal(new[] { "opaque-42", "123" }, repo.GetChannels(account, CatalogItemType.Live).Items.Select(x => x.Id));
        }
        finally { repo.Dispose(); Directory.Delete(dir, true); }
    }

    private abstract class ProviderBase : ICatalogProvider
    {
        public Task<AuthenticationResult> AuthenticateAsync(ProviderConnection c, CancellationToken t = default) => Task.FromResult(AuthenticationResult.Succeeded(Account()));
        public abstract Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, CancellationToken t = default);
        public abstract Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken t = default);
    }
    private sealed class ThrowingProvider : ProviderBase
    {
        public override Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, CancellationToken t = default) => Task.FromResult<IReadOnlyList<ChannelGroup>>(new[] { Group(a,"new","New") });
        public override Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken t = default) => throw new InvalidOperationException("fixture failure");
    }
    private sealed class FailFirstRefreshProvider : ProviderBase
    {
        public int GroupCalls { get; private set; }
        public override Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, CancellationToken t = default)
        {
            GroupCalls++;
            if (GroupCalls == 1) throw new InvalidOperationException("fixture category failure");
            return Task.FromResult<IReadOnlyList<ChannelGroup>>(Array.Empty<ChannelGroup>());
        }
        public override Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken t = default) =>
            Task.FromResult<IReadOnlyList<Channel>>(Array.Empty<Channel>());
    }
    private sealed class EmptyProvider : ProviderBase
    {
        public override Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, CancellationToken t = default) => Task.FromResult<IReadOnlyList<ChannelGroup>>(Array.Empty<ChannelGroup>());
        public override Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken t = default) => Task.FromResult<IReadOnlyList<Channel>>(Array.Empty<Channel>());
    }
    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(responder(request, cancellationToken));
    }
}
