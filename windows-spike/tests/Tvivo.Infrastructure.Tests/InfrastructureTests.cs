using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class InfrastructureTests
{
    private static ProviderEndpoint Endpoint(string host = "panel.example.com", int port = 8080, string scheme = "http") => new(scheme, host, port);

    [Fact]
    public void LiveUrl_encodes_credentials_and_defaults_extension()
    {
        var uri = StreamUrlBuilder.Live(Endpoint(), "user name", "p@ss/word", "opaque/id", "");
        Assert.Equal("http://panel.example.com:8080/live/user%20name/p%40ss%2Fword/opaque/id.ts", uri.ToString());
    }

    [Fact]
    public void LiveUrl_supports_https_nonstandard_port_and_ipv6()
    {
        var uri = StreamUrlBuilder.Live(new("https", "2001:db8::1", 9443), "u", "p", "1", ".m3u8");
        Assert.Equal("https://[2001:db8::1]:9443/live/u/p/1.m3u8", uri.ToString());
    }

    [Fact]
    public void SeriesUrl_uses_episode_id_and_container_extension()
    {
        var uri = StreamUrlBuilder.Series(Endpoint(), "u", "p", "episode-opaque-42", ".mkv");
        Assert.Equal("http://panel.example.com:8080/series/u/p/episode-opaque-42.mkv", uri.ToString());
    }

    [Fact]
    public void Redaction_removes_credentials_from_urls_and_messages()
    {
        var text = "failure: http://panel.example.com:8080/live/user/pass/1.ts";
        var redacted = StreamUrlBuilder.Redact(text);
        Assert.Equal("failure: http://panel.example.com:8080/live/***/***/1.ts", redacted);
        Assert.DoesNotContain("user", redacted);
        Assert.DoesNotContain("pass", redacted);
    }

    [Fact]
    public void AccountIdentity_normalizes_scheme_host_and_username_whitespace_but_separates_http_and_https()
    {
        var first = AccountIdentity.For(new(" HTTP ", " PANEL.Example.com ", 8080), "  user ");
        var second = AccountIdentity.For(new("http", "panel.example.com", 8080), "user");
        var secure = AccountIdentity.For(new("https", "panel.example.com", 8080), "user");
        Assert.Equal(first, second);
        Assert.NotEqual(first, secure);
        Assert.Equal(32, first.Length);
        Assert.Equal("6cd2d4c5cd3b4ecc8969029ebf4a09e8", first);
    }

    [Fact]
    public async Task Provider_keeps_http_and_https_connections_under_separate_account_ids()
    {
        var requestedSchemes = new List<string>();
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
        {
            if (!request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal))
                return JsonResponse("auth_success.json");
            requestedSchemes.Add(request.RequestUri.Scheme);
            return JsonBody("[]");
        })));
        var http = Assert.IsType<ProviderAccount>((await provider.AuthenticateAsync(
            new ProviderConnection(Endpoint(scheme: "http"), "user", "first"))).Account);
        var https = Assert.IsType<ProviderAccount>((await provider.AuthenticateAsync(
            new ProviderConnection(Endpoint(scheme: "https"), "user", "second"))).Account);

        Assert.NotEqual(http.AccountId, https.AccountId);
        await provider.GetChannelGroupsAsync(http);
        await provider.GetChannelGroupsAsync(https);
        Assert.Equal(new[] { "http", "https" }, requestedSchemes);
    }

    [Theory]
    [InlineData("panel.example.com:8080", "panel.example.com", 8080, false)]
    [InlineData("http://panel.example.com:8080", "panel.example.com", 8080, false)]
    [InlineData("https://panel.example.com:8443", "panel.example.com", 8443, true)]
    [InlineData("[2001:db8::1]:8080", "2001:db8::1", 8080, false)]
    [InlineData("2001:db8::1", "2001:db8::1", null, false)]
    public void ServerAddress_parses_supported_shapes(string input, string host, int? port, bool https)
    {
        var result = ServerAddress.Parse(input);
        Assert.NotNull(result);
        Assert.Equal(host, result!.Host);
        Assert.Equal(port, result.Port);
        Assert.Equal(https, result.UseHttps);
    }

    [Fact]
    public void ServerAddress_uses_reported_port_for_bare_host_and_rejects_bad_ports()
    {
        Assert.Equal(2095, ServerAddress.Parse("panel.example.com", 2095)!.Port);
        Assert.Null(ServerAddress.Parse("panel.example.com:abc"));
        Assert.Null(ServerAddress.Parse("panel.example.com:70000"));
        Assert.Null(ServerAddress.Parse("   "));
    }

    [Fact]
    public async Task Provider_maps_auth_groups_and_live_channels()
    {
        var handler = new FixtureHandler((request, _) => request.RequestUri!.Query switch
        {
            var query when !query.Contains("action=", StringComparison.Ordinal) => JsonResponse("auth_success.json"),
            var query when query.Contains("action=get_live_categories", StringComparison.Ordinal) => JsonResponse("live_categories.json"),
            _ => JsonResponse("live_streams.json")
        });
        var provider = new XtreamCatalogProvider(new HttpClient(handler));
        var connection = new ProviderConnection(Endpoint(), " user ", "secret");
        var authentication = await provider.AuthenticateAsync(connection);
        Assert.True(authentication.Success);
        var account = Assert.IsType<ProviderAccount>(authentication.Account);
        Assert.NotEmpty(account.AccountId);
        Assert.Equal(8080, account.Endpoint.Port);
        Assert.Equal(1, account.MaxConnections);
        Assert.Equal(8443, provider.GetHttpsPort(account));

        var groups = await provider.GetChannelGroupsAsync(account);
        Assert.Equal(new[] { "10", "11" }, groups.Select(x => x.Id));
        var channels = await provider.GetChannelsAsync(account, groupId: "10");
        Assert.Equal("opaque-42", channels[0].Id);
        Assert.Equal("mkv", channels[0].Source.ContainerExtension);
        Assert.Equal("https://cdn.example/logo.png", channels[0].LogoUri!.ToString());
        Assert.Equal("10", channels[0].GroupId);
    }

    [Fact]
    public async Task Provider_maps_movie_stream_icon_and_series_cover_to_channel_artwork()
    {
        var handler = new FixtureHandler((request, _) =>
        {
            var query = request.RequestUri!.Query;
            if (!query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            if (query.Contains("action=get_vod_categories", StringComparison.Ordinal) ||
                query.Contains("action=get_series_categories", StringComparison.Ordinal))
                return JsonBody("[]");
            if (query.Contains("action=get_vod_streams", StringComparison.Ordinal))
                return JsonBody("[{\"stream_id\":\"movie-42\",\"name\":\"Movie\",\"stream_icon\":\"https://cdn.example/movie.jpg\"}]");
            return JsonBody("[{\"series_id\":\"series-7\",\"name\":\"Series\",\"cover\":\"https://cdn.example/series.jpg\"}]");
        });
        var provider = new XtreamCatalogProvider(new HttpClient(handler));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);

        var movie = Assert.Single(await provider.GetChannelsAsync(account, CatalogItemType.Movie));
        var series = Assert.Single(await provider.GetChannelsAsync(account, CatalogItemType.Series));

        Assert.Equal("https://cdn.example/movie.jpg", movie.LogoUri!.ToString());
        Assert.Equal("https://cdn.example/series.jpg", series.LogoUri!.ToString());
    }

    [Fact]
    public async Task Provider_uses_last_modified_as_the_series_date_and_added_for_movies()
    {
        var handler = new FixtureHandler((request, _) =>
        {
            var query = request.RequestUri!.Query;
            if (!query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            if (query.Contains("action=get_vod_categories", StringComparison.Ordinal) ||
                query.Contains("action=get_series_categories", StringComparison.Ordinal))
                return JsonBody("[]");
            if (query.Contains("action=get_vod_streams", StringComparison.Ordinal))
                return JsonBody("[{\"stream_id\":\"movie-1\",\"name\":\"Movie\",\"added\":\"1790000000\"}]");
            return JsonBody("[{\"series_id\":\"series-1\",\"name\":\"Series\",\"last_modified\":\"1790864164\"}]");
        });
        var provider = new XtreamCatalogProvider(new HttpClient(handler));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);

        var movie = Assert.Single(await provider.GetChannelsAsync(account, CatalogItemType.Movie));
        var series = Assert.Single(await provider.GetChannelsAsync(account, CatalogItemType.Series));

        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(1790000000), movie.AddedAt);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(1790864164), series.AddedAt);
    }

    [Fact]
    public async Task Provider_maps_movie_and_series_metadata_from_info_objects()
    {
        string? movieQuery = null;
        string? seriesQuery = null;
        var handler = new FixtureHandler((request, _) =>
        {
            var query = request.RequestUri!.Query;
            if (!query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            if (query.Contains("action=get_vod_info", StringComparison.Ordinal))
            {
                movieQuery = query;
                return JsonBody("""{"info":{"plot":"Movie plot","description":"Fallback","rating":"8.1","genre":"Drama","year":2024,"cast":"Actor One"}}""");
            }
            seriesQuery = query;
            return JsonBody("""{"info":{"name":"Series title","plot":"Series plot","rating":"7.4","genre":"Comedy","year":"2022","actors":"Actor Two"},"episodes":{"1":[{"id":"episode-opaque-42","title":"Episode","container_extension":"mkv"}]}}""");
        });
        var provider = new XtreamCatalogProvider(new HttpClient(handler));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);

        var movie = await provider.GetMovieInfoAsync(account, "movie-42");
        var series = await provider.GetSeriesInfoAsync(account, "series-7");

        Assert.Contains("action=get_vod_info", movieQuery ?? string.Empty, StringComparison.Ordinal);
        Assert.Contains("vod_id=movie-42", movieQuery ?? string.Empty, StringComparison.Ordinal);
        Assert.Contains("action=get_series_info", seriesQuery ?? string.Empty, StringComparison.Ordinal);
        Assert.Contains("series_id=series-7", seriesQuery ?? string.Empty, StringComparison.Ordinal);
        Assert.Equal(new CatalogMetadata("2024", "8.1", "Drama", "Movie plot", "Actor One"), movie.Metadata);
        Assert.Equal(new CatalogMetadata("2022", "7.4", "Comedy", "Series plot", "Actor Two"), series.Metadata);
        var episode = Assert.Single(Assert.Single(series.Seasons).Episodes);
        Assert.Equal("episode-opaque-42", episode.Id);
        Assert.Equal("mkv", episode.Source.ContainerExtension);
        Assert.Equal("http://panel.example.com:8080/series/u/p/episode-opaque-42.mkv", episode.Source.DirectUri!.ToString());
    }

    [Fact]
    public async Task Provider_handles_auth_zero_and_inactive_as_neutral_failure()
    {
        foreach (var fixture in new[] { "auth_zero.json", "auth_inactive.json" })
        {
            var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((_, _) => JsonResponse(fixture))));
            var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
            Assert.False(authentication.Success);
            Assert.Null(authentication.Account);
            Assert.Equal(fixture == "auth_zero.json" ? AuthFailureReason.InvalidCredentials : AuthFailureReason.AccountExpired, authentication.FailureReason);
        }
    }

    [Fact]
    public async Task Provider_returns_empty_for_successful_empty_catalog_responses()
    {
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) => request.RequestUri!.Query switch
        {
            var query when !query.Contains("action=", StringComparison.Ordinal) => JsonResponse("auth_success.json"),
            _ => JsonResponse("empty.json")
        })));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        Assert.True(authentication.Success);
        var account = Assert.IsType<ProviderAccount>(authentication.Account);
        Assert.Empty(await provider.GetChannelGroupsAsync(account));
        Assert.Empty(await provider.GetChannelsAsync(account));
    }

    [Fact]
    public async Task Provider_propagates_malformed_or_non_array_catalog_responses()
    {
        foreach (var fixture in new[] { "malformed.json", "object.json" })
        {
            var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) => request.RequestUri!.Query switch
            {
                var query when !query.Contains("action=", StringComparison.Ordinal) => JsonResponse("auth_success.json"),
                _ => JsonResponse(fixture)
            })));
            var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
            var account = Assert.IsType<ProviderAccount>(authentication.Account);
            await Assert.ThrowsAnyAsync<System.Text.Json.JsonException>(() => provider.GetChannelGroupsAsync(account));
        }
    }

    [Fact]
    public async Task Provider_propagates_catalog_http_and_transport_failures()
    {
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
            request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
                ? new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)
                : JsonResponse("auth_success.json"))));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);
        await Assert.ThrowsAsync<HttpRequestException>(() => provider.GetChannelsAsync(account));

        var transportProvider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
            request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)
                ? throw new HttpRequestException("fixture transport failure")
                : JsonResponse("auth_success.json"))));
        var transportAuthentication = await transportProvider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var transportAccount = Assert.IsType<ProviderAccount>(transportAuthentication.Account);
        await Assert.ThrowsAsync<HttpRequestException>(() => transportProvider.GetChannelGroupsAsync(transportAccount));
    }

    [Fact]
    public async Task Provider_retries_throttled_catalog_requests_and_honours_retry_after()
    {
        var calls = 0;
        var waits = new List<TimeSpan>();
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
        {
            if (!request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            if (++calls <= 2)
            {
                var throttled = new HttpResponseMessage(HttpStatusCode.TooManyRequests);
                if (calls == 1) throttled.Headers.RetryAfter = new System.Net.Http.Headers.RetryConditionHeaderValue(TimeSpan.FromSeconds(7));
                return throttled;
            }
            return JsonResponse("live_streams.json");
        })), delay: (wait, _) => { waits.Add(wait); return Task.CompletedTask; });
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);

        var channels = await provider.GetChannelsAsync(account);

        Assert.Equal(2, channels.Count);
        Assert.Equal(3, calls);
        Assert.Contains(TimeSpan.FromSeconds(7), waits);
    }

    [Fact]
    public async Task Provider_gives_up_after_repeated_throttling()
    {
        var calls = 0;
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
        {
            if (!request.RequestUri!.Query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            calls++;
            return new HttpResponseMessage(HttpStatusCode.TooManyRequests);
        })), delay: (_, _) => Task.CompletedTask);
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);

        var failure = await Assert.ThrowsAsync<HttpRequestException>(() => provider.GetChannelsAsync(account));

        Assert.Equal(HttpStatusCode.TooManyRequests, failure.StatusCode);
        Assert.Equal(4, calls);
    }

    [Fact]
    public async Task Provider_requests_selected_group_channels()
    {
        string? catalogQuery = null;
        var provider = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((request, _) =>
        {
            var query = request.RequestUri!.Query;
            if (!query.Contains("action=", StringComparison.Ordinal)) return JsonResponse("auth_success.json");
            catalogQuery = query;
            return query.Contains("category_id=10", StringComparison.Ordinal)
                ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("[{\"stream_id\":\"opaque-42\",\"name\":\"Channel 42\",\"category_id\":10,\"ext\":\"mkv\"}]", Encoding.UTF8, "application/json") }
                : JsonResponse("live_streams.json");
        })));
        var authentication = await provider.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        var account = Assert.IsType<ProviderAccount>(authentication.Account);
        var allChannels = await provider.GetChannelsAsync(account);
        var channels = await provider.GetChannelsAsync(account, groupId: "10");
        Assert.Equal(2, allChannels.Count);
        Assert.Single(channels);
        Assert.Equal("10", channels[0].GroupId);
        Assert.Contains("category_id=10", catalogQuery, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Authentication_maps_http_and_transport_failures_without_provider_details()
    {
        var httpFailure = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((_, _) =>
            new HttpResponseMessage(HttpStatusCode.ServiceUnavailable))));
        var httpResult = await httpFailure.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        Assert.False(httpResult.Success);
        Assert.Equal(AuthFailureReason.NetworkFailure, httpResult.FailureReason);
        Assert.Equal("http_failure", httpResult.DiagnosticCode);

        var transportFailure = new XtreamCatalogProvider(new HttpClient(new FixtureHandler((_, _) =>
            throw new HttpRequestException("private transport detail"))));
        var transportResult = await transportFailure.AuthenticateAsync(new ProviderConnection(Endpoint(), "u", "p"));
        Assert.False(transportResult.Success);
        Assert.Equal(AuthFailureReason.NetworkFailure, transportResult.FailureReason);
        Assert.Equal("network_error", transportResult.DiagnosticCode);
    }

    private static HttpResponseMessage JsonResponse(string fixture)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Fixtures", fixture);
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(File.ReadAllText(path), Encoding.UTF8, "application/json") };
    }

    private static HttpResponseMessage JsonBody(string json) =>
        new(HttpStatusCode.OK) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request, cancellationToken));
    }
}
