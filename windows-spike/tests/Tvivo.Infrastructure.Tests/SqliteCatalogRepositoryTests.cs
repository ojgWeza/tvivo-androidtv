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
            repo.ReplaceLiveSnapshot(account, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"1","g","Alpha"), ChannelFor(account,"2","g","Beta"), ChannelFor(account,"3","g","Bravo") });
            Assert.Equal(3, repo.GetChannels(account, "g", limit: 2).TotalCount);
            Assert.Equal(new[] { "1", "2" }, repo.GetChannels(account, "g", limit: 2).Items.Select(x => x.Id));
            Assert.Equal(new[] { "3" }, repo.GetChannels(account, "g", "rav").Items.Select(x => x.Id));
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task Failed_provider_refresh_preserves_previous_snapshot()
    {
        var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceLiveSnapshot(account, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            var service = new CatalogRefreshService(new ThrowingProvider(), repo);
            await Assert.ThrowsAsync<InvalidOperationException>(() => service.RefreshAsync(account));
            Assert.Equal("old", Assert.Single(repo.GetChannels(account).Items).Id);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Failed_snapshot_transaction_rolls_back_deletions_and_inserts()
    {
        var repo = Create(out var dir); var account = Account();
        try
        {
            repo.ReplaceLiveSnapshot(account, new[] { Group(account,"g","News") }, new[] { ChannelFor(account,"old","g","Old") });
            Assert.Throws<Microsoft.Data.Sqlite.SqliteException>(() => repo.ReplaceLiveSnapshot(account,
                new[] { Group(account,"new","New") }, new[] { ChannelFor(account,"dup","new","First"), ChannelFor(account,"dup","new","Duplicate") }));
            Assert.Equal("News", Assert.Single(repo.GetGroups(account)).Name);
            Assert.Equal("old", Assert.Single(repo.GetChannels(account).Items).Id);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Account_keys_are_isolated()
    {
        var repo = Create(out var dir); var a = Account(); var b = Account("account-b");
        try
        {
            repo.ReplaceLiveSnapshot(a, new[] { Group(a,"g","A") }, new[] { ChannelFor(a,"same","g","A channel") });
            repo.ReplaceLiveSnapshot(b, new[] { Group(b,"g","B") }, new[] { ChannelFor(b,"same","g","B channel") });
            Assert.Equal("A", Assert.Single(repo.GetGroups(a)).Name);
            Assert.Equal("A channel", Assert.Single(repo.GetChannels(a).Items).DisplayName);
            Assert.Equal("B", Assert.Single(repo.GetGroups(b)).Name);
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
            Assert.Empty(repo.GetGroups(account));
            Assert.Empty(repo.GetChannels(account).Items);
            Assert.Equal(0, repo.GetChannels(account).TotalCount);
            // CatalogLandingPage selects EmptyState when both these cache-backed reads are empty.
            Assert.True(repo.GetGroups(account).Count == 0 && repo.GetChannels(account).TotalCount == 0);
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
                    query.Contains("get_live_categories", StringComparison.Ordinal) ? "live_categories.json" : "live_streams.json";
                var path = Path.Combine(AppContext.BaseDirectory, "Fixtures", fixture);
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(File.ReadAllText(path), Encoding.UTF8, "application/json") };
            })));
            var auth = await provider.AuthenticateAsync(new ProviderConnection(new("http", "panel.example.com", 8080), "fixture-user", "fixture-password"));
            var account = Assert.IsType<ProviderAccount>(auth.Account);
            await new CatalogRefreshService(provider, repo).RefreshAsync(account);
            Assert.Equal(new[] { "10", "11" }, repo.GetGroups(account).Select(x => x.Id));
            Assert.Equal(new[] { "opaque-42", "123" }, repo.GetChannels(account).Items.Select(x => x.Id));

            await Assert.ThrowsAsync<InvalidOperationException>(() => new CatalogRefreshService(new ThrowingProvider(), repo).RefreshAsync(account));
            Assert.Equal(new[] { "opaque-42", "123" }, repo.GetChannels(account).Items.Select(x => x.Id));
        }
        finally { Directory.Delete(dir, true); }
    }

    private abstract class ProviderBase : ICatalogProvider
    {
        public Task<AuthenticationResult> AuthenticateAsync(ProviderConnection c, CancellationToken t = default) => Task.FromResult(AuthenticationResult.Succeeded(Account()));
        public abstract Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CancellationToken t = default);
        public abstract Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, string? groupId = null, CancellationToken t = default);
    }
    private sealed class ThrowingProvider : ProviderBase
    {
        public override Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CancellationToken t = default) => Task.FromResult<IReadOnlyList<ChannelGroup>>(new[] { Group(a,"new","New") });
        public override Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, string? groupId = null, CancellationToken t = default) => throw new InvalidOperationException("fixture failure");
    }
    private sealed class EmptyProvider : ProviderBase
    {
        public override Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount a, CancellationToken t = default) => Task.FromResult<IReadOnlyList<ChannelGroup>>(Array.Empty<ChannelGroup>());
        public override Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount a, string? groupId = null, CancellationToken t = default) => Task.FromResult<IReadOnlyList<Channel>>(Array.Empty<Channel>());
    }
    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(responder(request, cancellationToken));
    }
}
