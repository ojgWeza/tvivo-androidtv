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
    public void Redaction_removes_credentials_from_urls_and_messages()
    {
        var text = "failure: http://panel.example.com:8080/live/user/pass/1.ts";
        var redacted = StreamUrlBuilder.Redact(text);
        Assert.Equal("failure: http://panel.example.com:8080/live/***/***/1.ts", redacted);
        Assert.DoesNotContain("user", redacted);
        Assert.DoesNotContain("pass", redacted);
    }

    [Fact]
    public void AccountIdentity_ignores_password_and_normalizes_host_and_whitespace()
    {
        var first = AccountIdentity.For(new("http", " PANEL.Example.com ", 8080), "  user ");
        var second = AccountIdentity.For(new("http", "panel.example.com", 8080), "user");
        Assert.Equal(first, second);
        Assert.Equal(32, first.Length);
        Assert.Equal("32c7df0c46909a329d615c4f926a7e12", first);
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
        var channels = await provider.GetChannelsAsync(account, "10");
        Assert.Equal("opaque-42", channels[0].Id);
        Assert.Equal("mkv", channels[0].Source.ContainerExtension);
        Assert.Equal("https://cdn.example/logo.png", channels[0].LogoUri!.ToString());
        Assert.Equal("10", channels[0].GroupId);
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
        var channels = await provider.GetChannelsAsync(account, "10");
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

    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request, cancellationToken));
    }
}
