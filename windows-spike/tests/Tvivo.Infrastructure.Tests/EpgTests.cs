using System.Net;
using System.Text;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class EpgTests
{
    private static readonly DateTimeOffset WindowNow = new(2026, 10, 2, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Xmltv_parser_ignores_doctype_missing_offsets_and_out_of_window_programmes()
    {
        const string xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv>
            <tv>
              <programme channel="epg-1" start="20261002050000 +0000" stop="20261002060000 +0000"><title>Too old</title></programme>
              <programme channel="epg-1" start="20261002110000 +0000" stop="20261002130000 +0000"><title>Current</title><desc>Details</desc></programme>
              <programme channel="epg-1" start="20261004130000 +0000" stop="20261004140000 +0000"><title>Too late</title></programme>
              <programme channel="epg-1" start="20261002130000" stop="20261002140000 +0000"><title>Ambiguous</title></programme>
            </tv>
            """;

        var result = await EpgXmltvParser.ParseAsync(UTF8(xml), WindowNow);

        var programme = Assert.Single(result.Programmes);
        Assert.Equal("Current", programme.Title);
        Assert.Equal(WindowNow.AddHours(-1), programme.StartUtc);
        Assert.Equal("Details", programme.Description);
    }

    [Fact]
    public async Task Xtream_json_parser_decodes_base64_text_and_accepts_unix_timestamps()
    {
        var start = WindowNow.ToUnixTimeSeconds();
        var json = $"{{\"epg_listings\":[{{\"epg_id\":\"epg-1\",\"start_timestamp\":\"{start}\",\"stop_timestamp\":\"{start + 3600}\",\"title\":\"VGl0bGU=\",\"description\":\"RGV0YWlscw==\"}}]}}";

        var result = await XtreamEpgJsonParser.ParseAsync(UTF8(json));

        var programme = Assert.Single(result);
        Assert.Equal("Title", programme.Title);
        Assert.Equal("Details", programme.Description);
        Assert.Equal(WindowNow, programme.StartUtc);
    }

    [Fact]
    public void Redaction_hides_path_and_query_credentials()
    {
        var text = "http://panel.example/player_api.php?username=alice&password=secret&action=get_short_epg";
        var redacted = StreamUrlBuilder.Redact(text);

        Assert.Equal("http://panel.example/player_api.php?username=***&password=***&action=get_short_epg", redacted);
        Assert.DoesNotContain("alice", redacted);
        Assert.DoesNotContain("secret", redacted);
    }

    [Fact]
    public void Repository_creates_epg_schema_v1_and_uses_its_own_file()
    {
        using var fixture = RepositoryFixture.Create();
        Assert.Equal(1, fixture.UserVersion());
        Assert.Contains("winui-epg.sqlite", fixture.Path, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Repository_isolates_accounts_and_returns_now_next_in_one_batch()
    {
        using var fixture = RepositoryFixture.Create();
        var first = Account("account-a");
        var second = Account("account-b");
        var current = new EpgProgramme("epg-1", WindowNow.AddMinutes(-10), WindowNow.AddMinutes(20), "Now");
        var next = new EpgProgramme("epg-1", WindowNow.AddMinutes(20), WindowNow.AddHours(1), "Next");
        fixture.Repository.Import(first, new[] { new EpgChannelMap("stream-1", "epg-1") }, new[] { current, next }, WindowNow);
        fixture.Repository.Import(second, new[] { new EpgChannelMap("stream-2", "epg-1") }, new[] { current with { Title = "Other account" } }, WindowNow);

        var nowNext = fixture.Repository.GetNowNext(first, new[] { "epg-1" }, WindowNow)["epg-1"];

        Assert.Equal("Now", nowNext.Now!.Title);
        Assert.Equal("Next", nowNext.Next!.Title);
        Assert.Equal("Other account", fixture.Repository.GetProgrammes(second, "epg-1", WindowNow.AddHours(-1), WindowNow.AddHours(2)).Single().Title);
        Assert.Empty(fixture.Repository.GetProgrammes(first, "missing", WindowNow.AddHours(-1), WindowNow.AddHours(2)));
    }

    [Fact]
    public void Failed_empty_import_keeps_prior_data_and_many_streams_share_one_epg_id()
    {
        using var fixture = RepositoryFixture.Create();
        var account = Account();
        var programme = new EpgProgramme("epg-shared", WindowNow, WindowNow.AddHours(1), "Prior");
        fixture.Repository.Import(account,
            new[] { new EpgChannelMap("stream-1", "epg-shared"), new EpgChannelMap("stream-2", "epg-shared") },
            new[] { programme }, WindowNow);

        Assert.Throws<InvalidDataException>(() => fixture.Repository.Import(account, Array.Empty<EpgChannelMap>(), Array.Empty<EpgProgramme>(), WindowNow.AddHours(1)));

        Assert.Equal(2, fixture.Repository.GetChannelMap(account).Count);
        Assert.Equal("Prior", fixture.Repository.GetProgrammes(account, "epg-shared", WindowNow.AddHours(-1), WindowNow.AddHours(2)).Single().Title);
    }

    [Fact]
    public async Task Refresh_service_skips_busy_playback_without_fetching()
    {
        using var fixture = RepositoryFixture.Create();
        var provider = new CountingEpgProvider();
        var service = new EpgRefreshService(provider, fixture.Repository, () => true);

        var result = await service.RefreshIfDueAsync(Account(), new ProviderConnection(new("http", "fixture.invalid", 80), "u", "p"));

        Assert.Equal(EpgRefreshResult.PlaybackBusy, result);
        Assert.Equal(0, provider.OpenCalls);
    }

    [Fact]
    public async Task Provider_captures_trimmed_live_epg_ids_and_allows_many_streams_to_one_id()
    {
        var handler = new FixtureHandler((request, _) => request.RequestUri!.Query.Contains("action=get_live_streams", StringComparison.Ordinal)
            ? Json("[{\"stream_id\":\"stream-1\",\"name\":\"One\",\"epg_channel_id\":\" epg-shared \"},{\"stream_id\":\"stream-2\",\"name\":\"Two\",\"epg_channel_id\":\"epg-shared\"}]")
            : Json("{\"user_info\":{\"auth\":1,\"status\":\"Active\"},\"server_info\":{\"port\":8080}}"));
        var provider = new XtreamCatalogProvider(new HttpClient(handler), new HttpClient(handler));
        var auth = await provider.AuthenticateAsync(new ProviderConnection(new("http", "fixture.invalid", 8080), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(auth.Account);

        var channels = await provider.GetChannelsAsync(account);
        var maps = await provider.GetEpgChannelMapAsync(account);

        Assert.Equal("epg-shared", channels[0].Metadata["epg_channel_id"]);
        Assert.Equal(2, maps.Count);
        Assert.All(maps, map => Assert.Equal("epg-shared", map.EpgChannelId));
    }

    private static ProviderAccount Account(string id = "account-a") => new(id, new("http", "fixture.invalid", 8080), "fixture");
    private static MemoryStream UTF8(string value) => new(Encoding.UTF8.GetBytes(value));
    private static HttpResponseMessage Json(string value) => new(HttpStatusCode.OK) { Content = new StringContent(value, Encoding.UTF8, "application/json") };

    private sealed class RepositoryFixture : IDisposable
    {
        private readonly string _directory;
        public string Path { get; }
        public EpgRepository Repository { get; }

        private RepositoryFixture(string directory, string path)
        {
            _directory = directory;
            Path = path;
            Repository = new EpgRepository(path);
        }

        public static RepositoryFixture Create()
        {
            var directory = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "tvivo-epg-tests", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(directory);
            return new RepositoryFixture(directory, System.IO.Path.Combine(directory, "winui-epg.sqlite"));
        }

        public int UserVersion()
        {
            using var connection = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={Path};Pooling=False");
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA user_version;";
            return Convert.ToInt32(command.ExecuteScalar());
        }

        public void Dispose()
        {
            Repository.Dispose();
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
        }
    }

    private sealed class CountingEpgProvider : IEpgProvider
    {
        public int OpenCalls { get; private set; }
        public Task<EpgProbeResult> ProbeAsync(ProviderAccount account, ProviderConnection connection, string streamId, CancellationToken cancellationToken = default) =>
            Task.FromResult(new EpgProbeResult(EpgCapability.Empty));
        public Task<Stream> OpenProgrammeStreamAsync(ProviderAccount account, ProviderConnection connection, CancellationToken cancellationToken = default)
        {
            OpenCalls++;
            return Task.FromResult<Stream>(new MemoryStream());
        }
        public Task<IReadOnlyList<EpgChannelMap>> GetEpgChannelMapAsync(ProviderAccount account, CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<EpgChannelMap>>(Array.Empty<EpgChannelMap>());
    }

    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request, cancellationToken));
    }
}
