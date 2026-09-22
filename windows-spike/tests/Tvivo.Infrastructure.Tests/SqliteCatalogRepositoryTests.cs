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
    private static SqliteCatalogRepository Create(out string directory)
    {
        directory = Path.Combine(Path.GetTempPath(), "tvivo-catalog-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        return new(Path.Combine(directory, "catalog.sqlite"));
    }

    [Fact]
    public void Refresh_replaces_snapshot_and_reads_pages_and_filters()
    {
        var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"1","g","Alpha"), ChannelFor(account,"2","g","Beta"), ChannelFor(account,"3","g","Bravo") });
            Assert.Equal(3, repo.GetChannels(account, CatalogItemType.Live, "g", limit: 2).TotalCount);
            Assert.Equal(new[] { "1", "2" }, repo.GetChannels(account, CatalogItemType.Live, "g", limit: 2).Items.Select(x => x.Id));
            Assert.Equal(new[] { "3" }, repo.GetChannels(account, CatalogItemType.Live, "g", "rav").Items.Select(x => x.Id));
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Catalog_queries_are_isolated_by_item_type()
    {
        var repo = Create(out var dir); var account = Account();
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
            Assert.Equal(StreamKind.Episode, Assert.Single(repo.GetChannels(account, CatalogItemType.Series).Items).Source.Kind);
            Assert.Equal("Movies", Assert.Single(repo.GetGroups(account, CatalogItemType.Movie)).DisplayName);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Refresh_cleans_empty_bracket_title_artifacts_for_every_catalog_type()
    {
        var repo = Create(out var dir); var account = Account();
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
        finally { Directory.Delete(dir, true); }
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
                command.CommandText = "CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,PRIMARY KEY(account_id,type,id)); INSERT INTO items VALUES('a','Movie','1','Film ( ) HD'),('a','Series','2','( )'),('a','Live','3','Show (2024)'); PRAGMA user_version=8;";
                command.ExecuteNonQuery();
            }

            _ = new SqliteCatalogRepository(path);

            using var migrated = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={path};Pooling=False");
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
            Assert.Equal(9, Convert.ToInt32(verify.ExecuteScalar()));
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task Failed_provider_refresh_preserves_previous_snapshot()
    {
        var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            var service = new CatalogRefreshService(new ThrowingProvider(), repo);
            await Assert.ThrowsAsync<InvalidOperationException>(() => service.RefreshAsync(account));
            Assert.Equal("old", Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).Id);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Failed_snapshot_transaction_rolls_back_deletions_and_inserts()
    {
        var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceSnapshot(account, CatalogItemType.Live, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            Assert.Throws<Microsoft.Data.Sqlite.SqliteException>(() => repo.ReplaceSnapshot(account, CatalogItemType.Live,
                new[] { Group(account,"new","New") }, new[] { ChannelFor(account,"dup","new","First"), ChannelFor(account,"dup","new","Duplicate") }));
            Assert.Equal("News", Assert.Single(repo.GetGroups(account, CatalogItemType.Live)).Name);
            Assert.Equal("old", Assert.Single(repo.GetChannels(account, CatalogItemType.Live).Items).Id);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Account_keys_are_isolated()
    {
        var repo = Create(out var dir); var a = Account(); var b = Account("account-b");
        try
        {
            repo.ReplaceSnapshot(a, CatalogItemType.Live, new[] { Group(a,"g","A") }, new[] { ChannelFor(a,"same","g","A channel") });
            repo.ReplaceSnapshot(b, CatalogItemType.Live, new[] { Group(b,"g","B") }, new[] { ChannelFor(b,"same","g","B channel") });
            Assert.Equal("A", Assert.Single(repo.GetGroups(a, CatalogItemType.Live)).Name);
            Assert.Equal("A channel", Assert.Single(repo.GetChannels(a, CatalogItemType.Live).Items).DisplayName);
            Assert.Equal("B", Assert.Single(repo.GetGroups(b, CatalogItemType.Live)).Name);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Zero_groups_and_channels_remain_empty_after_cache_backed_refresh()
    {
        var repo = Create(out var dir); var account = Account();
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
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Authenticated_provider_fixtures_refresh_cache_and_failure_keeps_snapshot()
    {
        var repo = Create(out var dir);
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
        finally { Directory.Delete(dir, true); }
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
