using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class CatalogRecoveryTests
{
    private static readonly ProviderConnection Connection = new(new("https", "fixture.invalid", 0), "fixture", "fixture");
    private const string Auth = """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}""";

    [Theory]
    [InlineData("""{"info":{"name":12},"episodes":{"1":[{"id":42,"title":null,"duration":"00:42:00"}]}}""", 1)]
    [InlineData("""{"episodes":[{"id":"ep-a","episode_num":"2"}]}""", 1)]
    [InlineData("""{"episodes":{"1":{"first":{"id":"ep-b"}}}}""", 1)]
    [InlineData("""{"episodes":[[{"id":"ep-c"}]]}""", 1)]
    [InlineData("""{"episodes":[]}""", 0)]
    [InlineData("""{"episodes":"unexpected"}""", 0)]
    [InlineData("""{"info":null}""", 0)]
    public async Task Series_info_accepts_nullable_numeric_and_episode_shapes(string json, int episodes)
    {
        var provider = Provider((request, _) => request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
            ? Json(json) : Json(Auth));
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        var details = await provider.GetSeriesInfoAsync(account, "series");
        Assert.Equal(episodes, details.Seasons.Sum(season => season.Episodes.Count));
    }

    [Fact]
    public async Task Empty_episode_response_preserves_previous_valid_details()
    {
        var calls = 0;
        var provider = Provider((request, _) => !request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
            ? Json(Auth) : Json(++calls == 1 ? """{"episodes":{"1":[{"id":"saved"}]}}""" : """{"episodes":[]}"""));
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        Assert.Single((await provider.GetSeriesInfoAsync(account, "series")).Seasons[0].Episodes);
        Assert.Equal("saved", (await provider.GetSeriesInfoAsync(account, "series")).Seasons[0].Episodes[0].Id);
    }

    [Fact]
    public async Task Series_list_and_vod_info_accept_wrapped_and_oddly_typed_fields()
    {
        var provider = Provider((request, _) =>
        {
            var query = request.RequestUri!.Query;
            if (!query.Contains("action=", StringComparison.Ordinal)) return Json(Auth);
            if (query.Contains("get_series", StringComparison.Ordinal))
                return Json("""{"data":[{"series_id":123,"name":null,"last_modified":"999999999999999999"}]}""");
            return Json("""{"info":{"year":2024,"rating":null,"plot":["unexpected"]}}""");
        });
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        Assert.Equal("123", Assert.Single(await provider.GetChannelsAsync(account, CatalogItemType.Series)).Id);
        Assert.Equal("2024", (await provider.GetMovieInfoAsync(account, "movie")).Metadata.Year);
    }

    [Fact]
    public async Task Invalid_catalog_root_is_protocol_not_empty_valid()
    {
        var provider = Provider((request, _) => request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
            ? Json("\"unexpected\"") : Json(Auth));
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        var failure = await Assert.ThrowsAsync<CatalogFailureException>(() => provider.GetChannelsAsync(account, CatalogItemType.Series));
        Assert.Equal(CatalogFailureKind.Protocol, failure.Failure.Kind);
    }

    [Fact]
    public async Task Forbidden_is_not_retried_and_trace_matches_log()
    {
        var calls = 0;
        var logs = new List<string>();
        var provider = Provider((request, _) => !request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
            ? Json(Auth) : Failure(HttpStatusCode.Forbidden, ref calls), log: logs.Add);
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        var failure = await Assert.ThrowsAsync<CatalogFailureException>(() => provider.GetChannelsAsync(account, CatalogItemType.Series));
        Assert.Equal(1, calls);
        Assert.Equal(CatalogFailureKind.Forbidden, failure.Failure.Kind);
        Assert.Contains(logs, line => line.Contains("trace=" + failure.Failure.TraceId, StringComparison.Ordinal));
    }

    [Fact]
    public async Task Too_many_requests_uses_retry_after_and_stops_after_three_attempts()
    {
        var calls = 0;
        var delays = new List<TimeSpan>();
        var provider = Provider((request, _) =>
        {
            if (!request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)) return Json(Auth);
            calls++;
            var response = new HttpResponseMessage(HttpStatusCode.TooManyRequests);
            response.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.FromSeconds(7));
            return response;
        }, delay: (wait, _) => { delays.Add(wait); return Task.CompletedTask; });
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        var failure = await Assert.ThrowsAsync<CatalogFailureException>(() => provider.GetChannelsAsync(account, CatalogItemType.Series));
        Assert.Equal(CatalogFailureKind.ProviderBusy, failure.Failure.Kind);
        Assert.Equal(3, calls);
        var retryDelays = delays.Where(wait => wait >= TimeSpan.FromSeconds(1)).ToArray();
        Assert.Equal(2, retryDelays.Length);
        Assert.All(retryDelays, wait => Assert.InRange(wait.TotalSeconds, 6.9, 7.1));
    }

    [Fact]
    public async Task Cancellation_stops_retry_delay()
    {
        using var cancel = new CancellationTokenSource();
        var calls = 0;
        var provider = Provider((request, _) => !request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
            ? Json(Auth) : Failure(HttpStatusCode.TooManyRequests, ref calls),
            delay: (wait, token) =>
            {
                if (wait < TimeSpan.FromSeconds(1)) return Task.CompletedTask;
                cancel.Cancel();
                return Task.Delay(10, token);
            });
        var account = (await provider.AuthenticateAsync(Connection)).Account!;
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => provider.GetChannelsAsync(account, CatalogItemType.Series, cancellationToken: cancel.Token));
        Assert.Equal(1, calls);
    }

    [Fact]
    public async Task Failed_refresh_task_allows_fresh_manual_attempt()
    {
        var account = new ProviderAccount("account", Connection.Endpoint, "fixture");
        var calls = 0;
        var tasks = new CatalogRefreshTasks((_, _, _) => ++calls == 1
            ? Task.FromException<bool>(new IOException()) : Task.FromResult(true));
        await Assert.ThrowsAsync<IOException>(() => tasks.RefreshTypeAsync(account, CatalogItemType.Series));
        Assert.True(await tasks.RefreshTypeAsync(account, CatalogItemType.Series));
        Assert.Equal(2, calls);
    }

    [Fact]
    public void Diagnostics_zip_has_bounded_redacted_entries()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        var desktop = Path.Combine(root, "Desktop");
        Directory.CreateDirectory(root);
        try
        {
            File.WriteAllText(Path.Combine(root, "tvivo-launch.log.1"),
                "2026-10-19T12:00:00Z trace=old op=series-list outcome=Network secret=https://hidden.invalid/series/u/p/1.ts\n");
            File.WriteAllText(Path.Combine(root, "tvivo-launch.log"),
                "2026-10-19T12:00:01Z trace=new op=series-info outcome=ok title=عنوان\n");
            var path = SupportDiagnostics.SaveToDesktop("1.2.3", desktop, root);
            Assert.True(new FileInfo(path).Length < 2_000_000);
            using var zip = ZipFile.OpenRead(path);
            Assert.Equal(3, zip.Entries.Count);
            var log = Read(zip, "app-log.txt");
            Assert.Contains("trace=old", log);
            Assert.Contains("trace=new", log);
            Assert.DoesNotContain("hidden", log);
            Assert.DoesNotContain("عنوان", log);
            Assert.Contains("trace=new", Read(zip, "catalog-requests.txt"));
            Assert.Contains("appVersion=1.2.3", Read(zip, "summary.txt"));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void Request_tail_is_independent_of_general_log_tail()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var log = Path.Combine(root, "tvivo-launch.log");
            File.WriteAllText(log + ".1", "2026-10-19T12:00:00Z trace=old op=series-list outcome=ok\n");
            File.WriteAllLines(log, Enumerable.Repeat("2026-10-19T12:00:01Z event=idle", 600));
            Assert.DoesNotContain(SupportDiagnostics.ReadLogTail(log, 500), line => line.Contains("trace=old", StringComparison.Ordinal));
            Assert.Contains(SupportDiagnostics.ReadRequestTail(log, 100), line => line.Contains("trace=old", StringComparison.Ordinal));
        }
        finally { Directory.Delete(root, true); }
    }

    private static string Read(ZipArchive zip, string name)
    {
        using var reader = new StreamReader(zip.GetEntry(name)!.Open());
        return reader.ReadToEnd();
    }

    private static HttpResponseMessage Failure(HttpStatusCode status, ref int calls)
    {
        calls++;
        return new(status);
    }
    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json")
    };
    private static XtreamCatalogProvider Provider(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> reply,
        Action<string>? log = null, Func<TimeSpan, CancellationToken, Task>? delay = null) =>
        new(new HttpClient(new Handler(reply)), epgLog: log, delay: delay ?? ((_, _) => Task.CompletedTask));
    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> reply) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(reply(request, cancellationToken));
    }
}
