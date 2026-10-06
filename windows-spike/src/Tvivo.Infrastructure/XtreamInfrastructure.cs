using System.Net;
using System.IO.Compression;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Globalization;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class XtreamCatalogProvider : ICatalogProvider, ISeriesCatalogProvider, IMovieInfoProvider, IEpgProvider
{
    private readonly HttpClient _http;
    private readonly HttpClient _epgHttp;
    private readonly Dictionary<string, ProviderConnection> _connections = new(StringComparer.Ordinal);
    private readonly Dictionary<string, int?> _httpsPorts = new(StringComparer.Ordinal);
    private readonly Dictionary<string, IReadOnlyList<EpgChannelMap>> _epgMaps = new(StringComparer.Ordinal);
    private readonly Action<string>? _epgLog;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly SemaphoreSlim _paceGate = new(1, 1);
    private long _lastApiRequestTicks;

    // Providers rate-limit bursts with HTTP 429. Space API calls out and retry a throttled call a few
    // times, honouring Retry-After, before surfacing a failure.
    private static readonly TimeSpan MinApiRequestSpacing = TimeSpan.FromMilliseconds(300);
    private static readonly TimeSpan MaxRetryAfter = TimeSpan.FromSeconds(20);
    private const int MaxThrottleRetries = 3;

    public XtreamCatalogProvider(HttpClient? httpClient = null, HttpClient? epgHttpClient = null, Action<string>? epgLog = null,
        Func<TimeSpan, CancellationToken, Task>? delay = null)
    {
        _delay = delay ?? Task.Delay;
        // Catalog dumps are tens of MB of JSON; the default client asks for gzip/brotli/deflate.
        _http = httpClient ?? NetworkTally.CreateClient("catalog", TimeSpan.FromMinutes(5));
        _epgHttp = epgHttpClient ?? new HttpClient(new HttpClientHandler { AutomaticDecompression = DecompressionMethods.All })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
        _epgLog = epgLog;
    }

    public async Task<AuthenticationResult> AuthenticateAsync(ProviderConnection connection, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await SendApiAsync(XtreamRequest.Uri(connection, "player_api.php"), cancellationToken);
            if (!response.IsSuccessStatusCode)
                return AuthenticationResult.Failed(AuthFailureReason.NetworkFailure, "http_failure");
            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
            if (document.RootElement.ValueKind != JsonValueKind.Object)
                return AuthenticationResult.Failed(AuthFailureReason.ProtocolError, "unexpected_response");

            var root = document.RootElement;
            if (!root.TryGetProperty("user_info", out var user) || user.ValueKind != JsonValueKind.Object ||
                !JsonValue.TryBoolean(user, "auth", out var auth))
                return AuthenticationResult.Failed(AuthFailureReason.ProtocolError, "missing_authentication_fields");
            if (!auth)
                return AuthenticationResult.Failed(AuthFailureReason.InvalidCredentials, "invalid_credentials");
            var status = JsonValue.String(user, "status");
            if (!string.Equals(status, "Active", StringComparison.OrdinalIgnoreCase))
            {
                var expired = string.Equals(status, "Expired", StringComparison.OrdinalIgnoreCase);
                return AuthenticationResult.Failed(expired ? AuthFailureReason.AccountExpired : AuthFailureReason.AccountInactive,
                    expired ? "account_expired" : "account_inactive");
            }

            var server = root.TryGetProperty("server_info", out var serverInfo) && serverInfo.ValueKind == JsonValueKind.Object
                ? serverInfo : default;
            var port = JsonValue.Int(server, "port") ?? connection.Endpoint.Port;
            var endpoint = connection.Endpoint with { Port = port };
            var accountId = AccountIdentity.For(endpoint, connection.Username);
            var account = new ProviderAccount(
                accountId,
                endpoint,
                connection.Username.Trim(),
                JsonValue.String(user, "username"),
                JsonValue.Int(user, "max_connections") ?? JsonValue.Int(server, "max_connections"),
                JsonValue.Expiry(user, "exp_date"));
            _connections[accountId] = connection with { Endpoint = endpoint };
            _httpsPorts[accountId] = JsonValue.Int(server, "https_port");
            return AuthenticationResult.Succeeded(account);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (HttpRequestException)
        {
            return AuthenticationResult.Failed(AuthFailureReason.NetworkFailure, "network_error");
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return AuthenticationResult.Failed(AuthFailureReason.NetworkFailure, "network_timeout");
        }
        catch (JsonException)
        {
            return AuthenticationResult.Failed(AuthFailureReason.ProtocolError, "malformed_json");
        }
        catch
        {
            return AuthenticationResult.Failed(AuthFailureReason.Unknown, "unexpected_error");
        }
    }

    private async Task<HttpResponseMessage> SendApiAsync(Uri uri, CancellationToken cancellationToken)
    {
        for (var attempt = 0; ; attempt++)
        {
            await WaitForApiSlotAsync(cancellationToken).ConfigureAwait(false);
            var response = await _http.GetAsync(uri, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.TooManyRequests || attempt >= MaxThrottleRetries)
                return response;

            var wait = response.Headers.RetryAfter switch
            {
                { Delta: { } delta } => delta,
                { Date: { } date } => date - DateTimeOffset.UtcNow,
                _ => TimeSpan.FromSeconds(2 << attempt) + TimeSpan.FromMilliseconds(Random.Shared.Next(0, 500)),
            };
            response.Dispose();
            if (wait < TimeSpan.Zero) wait = TimeSpan.Zero;
            if (wait > MaxRetryAfter) wait = MaxRetryAfter;
            await _delay(wait, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task WaitForApiSlotAsync(CancellationToken cancellationToken)
    {
        await _paceGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var last = Volatile.Read(ref _lastApiRequestTicks);
            if (last != 0)
            {
                var remaining = MinApiRequestSpacing - TimeSpan.FromTicks(Environment.TickCount64 * TimeSpan.TicksPerMillisecond - last);
                if (remaining > TimeSpan.Zero)
                    await _delay(remaining, cancellationToken).ConfigureAwait(false);
            }
            Volatile.Write(ref _lastApiRequestTicks, Environment.TickCount64 * TimeSpan.TicksPerMillisecond);
        }
        finally
        {
            _paceGate.Release();
        }
    }

    public int? GetHttpsPort(ProviderAccount account) =>
        _httpsPorts.TryGetValue(account.AccountId, out var port) ? port : null;

    public async Task<MovieDetails> GetMovieInfoAsync(ProviderAccount account, string movieId, CancellationToken cancellationToken = default)
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw new InvalidOperationException("The provider account has not been authenticated by this catalog provider.");
        using var response = await SendApiAsync(XtreamRequest.VodInfoUri(connection, movieId), cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        var info = GetInfoObject(document.RootElement);
        return new MovieDetails(movieId, MapMetadata(info));
    }

    public async Task<SeriesDetails> GetSeriesInfoAsync(ProviderAccount account, string seriesId, CancellationToken cancellationToken = default)
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw new InvalidOperationException("The provider account has not been authenticated by this catalog provider.");
        var uri = XtreamRequest.SeriesInfoUri(connection, seriesId);
        using var response = await SendApiAsync(uri, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        var root = document.RootElement;
        var info = GetInfoObject(root);
        var title = JsonValue.String(info, "name");
        var seasons = new List<SeriesSeason>();
        if (root.TryGetProperty("episodes", out var episodeGroups) && episodeGroups.ValueKind == JsonValueKind.Object)
        {
            foreach (var group in episodeGroups.EnumerateObject())
            {
                var seasonId = group.Name;
                var seasonNumber = int.TryParse(seasonId, out var parsedSeason) ? parsedSeason : seasons.Count + 1;
                var seasonName = $"Season {seasonNumber}";
                var episodes = new List<SeriesEpisode>();
                if (group.Value.ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in group.Value.EnumerateArray())
                    {
                        var id = JsonValue.String(item, "id");
                        if (string.IsNullOrWhiteSpace(id)) continue;
                        var episodeNumber = JsonValue.Int(item, "episode_num") ?? episodes.Count + 1;
                        var episodeTitle = JsonValue.String(item, "title") ?? $"Episode {episodeNumber}";
                        var extension = JsonValue.String(item, "container_extension")?.Trim().TrimStart('.');
                        var durationValue = JsonValue.String(item, "duration");
                        TimeSpan? duration = long.TryParse(durationValue, NumberStyles.Integer, CultureInfo.InvariantCulture, out var durationSeconds)
                            ? TimeSpan.FromSeconds(durationSeconds)
                            : TimeSpan.TryParse(durationValue, CultureInfo.InvariantCulture, out var parsedDuration) ? parsedDuration : null;
                        var directUri = new Uri(StreamUrlBuilder.Series(connection.Endpoint, connection.Username, connection.Password, id, extension));
                        episodes.Add(new SeriesEpisode(id, episodeTitle, seasonId, seasonName, seasonNumber, episodeNumber,
                            new StreamSource(id, StreamKind.Episode, extension, directUri, duration)));
                    }
                }
                seasons.Add(new SeriesSeason(seasonId, seasonName, seasonNumber,
                    episodes.OrderBy(episode => episode.EpisodeNumber).ToArray()));
            }
        }
        return new SeriesDetails(seriesId, title ?? seriesId, seasons.OrderBy(season => season.Number).ToArray(), MapMetadata(info));
    }

    private static JsonElement GetInfoObject(JsonElement root) =>
        root.ValueKind == JsonValueKind.Object && root.TryGetProperty("info", out var info) && info.ValueKind == JsonValueKind.Object
            ? info
            : root;

    private static CatalogMetadata MapMetadata(JsonElement info) => new(
        JsonValue.String(info, "year"), JsonValue.String(info, "rating"), JsonValue.String(info, "genre"),
        FirstNonEmpty(JsonValue.String(info, "plot"), JsonValue.String(info, "description")),
        FirstNonEmpty(JsonValue.String(info, "cast"), JsonValue.String(info, "actors")));

    private static string? FirstNonEmpty(params string?[] values) =>
        values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))?.Trim();

    public Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, CancellationToken cancellationToken = default) =>
        GetArrayAsync(account, CategoryAction(type), null, MapGroup, cancellationToken);

    public async Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken cancellationToken = default)
    {
        var channels = await GetArrayAsync(account, ItemAction(type), groupId, (value, accountId) => MapChannel(value, accountId, type), cancellationToken).ConfigureAwait(false);
        if (type == CatalogItemType.Live)
        {
            var incoming = channels
                .Select(channel => channel.Metadata.TryGetValue("epg_channel_id", out var epgId)
                    ? new EpgChannelMap(channel.Source.StreamId, epgId)
                    : null)
                .Where(map => map is not null)
                .Cast<EpgChannelMap>()
                .ToArray();
            if (groupId is null)
                _epgMaps[account.AccountId] = incoming;
            else
            {
                var merged = _epgMaps.TryGetValue(account.AccountId, out var existing)
                    ? existing.ToDictionary(map => map.StreamId, StringComparer.Ordinal)
                    : new Dictionary<string, EpgChannelMap>(StringComparer.Ordinal);
                foreach (var map in incoming) merged[map.StreamId] = map;
                _epgMaps[account.AccountId] = merged.Values.ToArray();
            }
        }
        return channels;
    }

    private async Task<IReadOnlyList<T>> GetArrayAsync<T>(ProviderAccount account, string action, string? groupId,
        Func<JsonElement, string, T?> map, CancellationToken cancellationToken) where T : class
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw new InvalidOperationException("The provider account has not been authenticated by this catalog provider.");
        using var response = await SendApiAsync(XtreamRequest.Uri(connection, "player_api.php", action, groupId), cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        if (document.RootElement.ValueKind != JsonValueKind.Array)
            throw new JsonException("The catalog response was not a JSON array.");
        var result = new List<T>();
        foreach (var item in document.RootElement.EnumerateArray())
        {
            var mapped = item.ValueKind == JsonValueKind.Object ? map(item, account.AccountId) : null;
            if (mapped is not null) result.Add(mapped);
        }
        return result;
    }

    private static ChannelGroup? MapGroup(JsonElement value, string accountId)
    {
        var id = JsonValue.String(value, "category_id");
        if (string.IsNullOrWhiteSpace(id)) return null;
        var name = JsonValue.String(value, "category_name") ?? id;
        return new ChannelGroup(accountId, id, name, name.Trim());
    }

    private static Channel? MapChannel(JsonElement value, string accountId, CatalogItemType type)
    {
        var id = JsonValue.String(value, type == CatalogItemType.Series ? "series_id" : "stream_id");
        if (string.IsNullOrWhiteSpace(id)) return null;
        var name = JsonValue.String(value, "name") ?? id;
        var icon = JsonValue.Uri(value, type == CatalogItemType.Series ? "cover" : "stream_icon");
        // Xtream movies and live channels carry "added"; series only carry "last_modified".
        var added = JsonValue.UnixTime(value, "added") ??
            (type == CatalogItemType.Series ? JsonValue.UnixTime(value, "last_modified") : null);
        var order = JsonValue.Int(value, "num");
        var groupId = JsonValue.String(value, "category_id");
        var extension = type == CatalogItemType.Live
            ? JsonValue.String(value, "ext")?.Trim().TrimStart('.')
            : JsonValue.String(value, "container_extension")?.Trim().TrimStart('.');
        var metadata = new Dictionary<string, string>();
        if (type == CatalogItemType.Live && JsonValue.String(value, "epg_channel_id")?.Trim() is { Length: > 0 } epgChannelId)
            metadata["epg_channel_id"] = epgChannelId;
        return new Channel(accountId, id, groupId, name, name.Trim(), icon, added, order,
            new StreamSource(id, StreamKindFor(type), string.IsNullOrEmpty(extension) ? null : extension),
            metadata);
    }

    private static string CategoryAction(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "get_vod_categories",
        CatalogItemType.Series => "get_series_categories",
        _ => "get_live_categories",
    };

    private static string ItemAction(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => "get_vod_streams",
        CatalogItemType.Series => "get_series",
        _ => "get_live_streams",
    };

    private static StreamKind StreamKindFor(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => StreamKind.Movie,
        CatalogItemType.Series => StreamKind.Series,
        _ => StreamKind.Live,
    };

    public Task<IReadOnlyList<EpgChannelMap>> GetEpgChannelMapAsync(ProviderAccount account, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(_epgMaps.TryGetValue(account.AccountId, out var maps)
            ? maps
            : (IReadOnlyList<EpgChannelMap>)Array.Empty<EpgChannelMap>());
    }

    public async Task<EpgProbeResult> ProbeAsync(
        ProviderAccount account,
        ProviderConnection connection,
        string streamId,
        CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await _epgHttp.GetAsync(XtreamRequest.ShortEpgUri(connection, streamId), HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            LogEpg("player_api.php", "get_short_epg", (int)response.StatusCode);
            if (!response.IsSuccessStatusCode)
                return new EpgProbeResult(response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.MethodNotAllowed or HttpStatusCode.NotImplemented
                    ? EpgCapability.Unavailable : EpgCapability.Failed, (int)response.StatusCode);
            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            var programmes = await XtreamEpgJsonParser.ParseAsync(stream, streamId, cancellationToken).ConfigureAwait(false);
            return new EpgProbeResult(programmes.Count == 0 ? EpgCapability.Empty : EpgCapability.Usable,
                (int)response.StatusCode, programmes.Count);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            LogEpg("player_api.php", "get_short_epg", null);
            return new EpgProbeResult(EpgCapability.Failed);
        }
    }

    public async Task<Stream> OpenProgrammeStreamAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken = default)
    {
        HttpResponseMessage? response = null;
        try
        {
            response = await _epgHttp.GetAsync(XtreamRequest.XmltvUri(connection), HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            LogEpg("xmltv.php", "bulk", (int)response.StatusCode);
            if (!response.IsSuccessStatusCode)
            {
                var status = (int)response.StatusCode;
                response.Dispose();
                throw new EpgProviderHttpException(status);
            }
            var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            return new ResponseContentStream(response, stream);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (EpgProviderHttpException)
        {
            throw;
        }
        catch
        {
            response?.Dispose();
            LogEpg("xmltv.php", "bulk", null);
            throw new EpgProviderHttpException(null);
        }
    }

    private void LogEpg(string endpoint, string action, int? status)
    {
        _epgLog?.Invoke($"EPG endpoint={endpoint} action={action} status={(status?.ToString(CultureInfo.InvariantCulture) ?? "transport_failure")}");
    }

    private sealed class ResponseContentStream(HttpResponseMessage response, Stream inner) : Stream
    {
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
        public override int Read(Span<byte> buffer) => inner.Read(buffer);
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) => inner.ReadAsync(buffer, offset, count, cancellationToken);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => inner.ReadAsync(buffer, cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                inner.Dispose();
                response.Dispose();
            }
            base.Dispose(disposing);
        }
    }

}

public sealed class EpgProviderHttpException(int? statusCode) : IOException("The EPG request failed.")
{
    public int? StatusCode { get; } = statusCode;
}

public static class AccountIdentity
{
    public static string For(ProviderEndpoint endpoint, string username)
    {
        var canonical = $"{endpoint.Scheme.Trim().ToLowerInvariant()}|{endpoint.Host.Trim().ToLowerInvariant()}|{endpoint.Port}|{username.Trim()}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant()[..32];
    }
}

public static class StreamUrlBuilder
{
    public static string Live(ProviderEndpoint endpoint, string username, string password, string streamId, string? extension) =>
        Build(endpoint, username, password, "live", streamId, extension);

    public static string Vod(ProviderEndpoint endpoint, string username, string password, string streamId, string? extension) =>
        Build(endpoint, username, password, "movie", streamId, extension);

    public static string Series(ProviderEndpoint endpoint, string username, string password, string streamId, string? extension) =>
        Build(endpoint, username, password, "series", streamId, extension);

    public static string Redact(string value)
    {
        var redacted = System.Text.RegularExpressions.Regex.Replace(
            value, @"/(live|movie|series)/([^/]+)/([^/]+)/", "/$1/***/***/", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        return System.Text.RegularExpressions.Regex.Replace(
            redacted, @"([?&](?:username|password)=)[^&]*", "$1***", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
    }

    private static string Build(ProviderEndpoint endpoint, string username, string password, string type, string id, string? extension)
    {
        var host = endpoint.Host.Contains(':', StringComparison.Ordinal) && !endpoint.Host.StartsWith('[') ? $"[{endpoint.Host}]" : endpoint.Host;
        var port = endpoint.Port > 0 ? $":{endpoint.Port}" : string.Empty;
        var ext = string.IsNullOrWhiteSpace(extension) ? "ts" : extension.Trim().TrimStart('.');
        return $"{endpoint.Scheme}://{host}{port}/{type}/{Uri.EscapeDataString(username)}/{Uri.EscapeDataString(password)}/{id}.{ext}";
    }
}

public sealed record ServerAddress(string Host, int? Port, bool UseHttps)
{
    public static ServerAddress? Parse(string input, int? reportedPort = null)
    {
        if (string.IsNullOrWhiteSpace(input)) return null;
        var text = input.Trim();
        var https = text.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
        if (text.Contains("://", StringComparison.Ordinal))
        {
            if (!Uri.TryCreate(text, UriKind.Absolute, out var uri) || string.IsNullOrWhiteSpace(uri.Host)) return null;
            return PortValue(uri.Host, uri.Port, https, reportedPort);
        }
        var slash = text.IndexOfAny(new[] { '/', '?', '#' });
        if (slash >= 0) text = text[..slash];
        if (text.StartsWith('['))
        {
            var close = text.IndexOf(']');
            if (close < 0) return null;
            var host = text[1..close];
            if (close == text.Length - 1) return new(host, reportedPort, https);
            if (text[close + 1] != ':' || !TryPort(text[(close + 2)..], out var port)) return null;
            return new(host, port, https);
        }
        var first = text.IndexOf(':');
        if (first >= 0 && first == text.LastIndexOf(':'))
        {
            if (!TryPort(text[(first + 1)..], out var port)) return null;
            return new(text[..first], port, https);
        }
        return new(text, reportedPort, https);
    }

    private static ServerAddress? PortValue(string host, int uriPort, bool https, int? reported) =>
        new(host, uriPort > 0 ? uriPort : reported, https);
    private static bool TryPort(string value, out int port) => int.TryParse(value, out port) && port is > 0 and <= 65535;
}

internal static class XtreamRequest
{
    public static Uri Uri(ProviderConnection connection, string path, string? action = null, string? groupId = null)
    {
        var builder = new UriBuilder(connection.Endpoint.Scheme, connection.Endpoint.Host, connection.Endpoint.Port, path);
        var query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}";
        if (action is not null) query += $"&action={WebUtility.UrlEncode(action)}";
        if (groupId is not null) query += $"&category_id={WebUtility.UrlEncode(groupId)}";
        builder.Query = query;
        return builder.Uri;
    }

    public static Uri SeriesInfoUri(ProviderConnection connection, string seriesId)
    {
        var builder = new UriBuilder(connection.Endpoint.Scheme, connection.Endpoint.Host, connection.Endpoint.Port, "player_api.php");
        builder.Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_series_info&series_id={WebUtility.UrlEncode(seriesId)}";
        return builder.Uri;
    }

    public static Uri VodInfoUri(ProviderConnection connection, string movieId)
    {
        var builder = new UriBuilder($"{connection.Endpoint.Scheme}://{connection.Endpoint.Host}:{connection.Endpoint.Port}/player_api.php")
        {
            Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_vod_info&vod_id={WebUtility.UrlEncode(movieId)}"
        };
        return builder.Uri;
    }

    public static Uri ShortEpgUri(ProviderConnection connection, string streamId)
    {
        var builder = new UriBuilder(connection.Endpoint.Scheme, connection.Endpoint.Host, connection.Endpoint.Port, "player_api.php");
        builder.Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_short_epg&stream_id={WebUtility.UrlEncode(streamId)}&limit=20";
        return builder.Uri;
    }

    public static Uri XmltvUri(ProviderConnection connection)
    {
        var builder = new UriBuilder(connection.Endpoint.Scheme, connection.Endpoint.Host, connection.Endpoint.Port, "xmltv.php");
        builder.Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}";
        return builder.Uri;
    }
}

public static class XtreamEpgJsonParser
{
    public static Task<IReadOnlyList<EpgProgramme>> ParseAsync(Stream source, CancellationToken cancellationToken = default) =>
        ParseAsync(source, string.Empty, cancellationToken);

    public static async Task<IReadOnlyList<EpgProgramme>> ParseAsync(Stream source, string fallbackChannelId, CancellationToken cancellationToken = default)
    {
        using var document = await JsonDocument.ParseAsync(source, cancellationToken: cancellationToken).ConfigureAwait(false);
        var root = document.RootElement;
        var array = root.ValueKind == JsonValueKind.Array
            ? root
            : root.ValueKind == JsonValueKind.Object && root.TryGetProperty("epg_listings", out var listings) && listings.ValueKind == JsonValueKind.Array
                ? listings
                : root.ValueKind == JsonValueKind.Object && root.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Array
                    ? data
                    : default;
        if (array.ValueKind != JsonValueKind.Array) return Array.Empty<EpgProgramme>();

        var result = new List<EpgProgramme>();
        foreach (var item in array.EnumerateArray())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (item.ValueKind != JsonValueKind.Object) continue;
            var channelId = FirstText(item, "epg_id", "channel_id", "channel")?.Trim();
            if (string.IsNullOrWhiteSpace(channelId)) channelId = fallbackChannelId.Trim();
            if (string.IsNullOrWhiteSpace(channelId)) continue;
            if (!TryTime(item, "start_timestamp", "start", out var start) ||
                !TryTime(item, "stop_timestamp", "stop", out var end) || end <= start) continue;
            var title = DecodeProviderText(FirstText(item, "title", "name"));
            if (string.IsNullOrWhiteSpace(title)) continue;
            var description = DecodeProviderText(FirstText(item, "description", "desc"));
            result.Add(new EpgProgramme(channelId, start, end, title.Trim(), string.IsNullOrWhiteSpace(description) ? null : description.Trim()));
        }
        return result;
    }

    public static string? DecodeProviderText(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var text = value.Trim();
        try
        {
            var bytes = Convert.FromBase64String(text);
            var decoded = Encoding.UTF8.GetString(bytes);
            if (bytes.Length > 0 && !decoded.Contains('\uFFFD') &&
                decoded.All(character => !char.IsControl(character) || character is '\r' or '\n' or '\t'))
                return decoded;
        }
        catch (FormatException)
        {
        }
        return text;
    }

    private static string? FirstText(JsonElement item, params string[] names)
    {
        foreach (var name in names)
        {
            var text = JsonValue.String(item, name);
            if (!string.IsNullOrWhiteSpace(text)) return text;
        }
        return null;
    }

    private static bool TryTime(JsonElement item, string unixName, string textName, out DateTimeOffset value)
    {
        value = default;
        var unix = JsonValue.String(item, unixName);
        if (long.TryParse(unix, NumberStyles.Integer, CultureInfo.InvariantCulture, out var seconds))
        {
            value = DateTimeOffset.FromUnixTimeSeconds(seconds);
            return true;
        }
        var text = JsonValue.String(item, textName)?.Trim();
        if (string.IsNullOrWhiteSpace(text) || !System.Text.RegularExpressions.Regex.IsMatch(text, @"(?:Z|[+-]\d{2}:?\d{2})$", System.Text.RegularExpressions.RegexOptions.CultureInvariant))
            return false;
        return DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out value);
    }
}

internal static class JsonValue
{
    public static string? String(JsonElement parent, string name)
    {
        if (parent.ValueKind != JsonValueKind.Object || !parent.TryGetProperty(name, out var value)) return null;
        return value.ValueKind switch { JsonValueKind.String => value.GetString(), JsonValueKind.Number => value.GetRawText(), _ => null };
    }
    public static int? Int(JsonElement parent, string name) => int.TryParse(String(parent, name), out var value) ? value : null;
    public static bool TryBoolean(JsonElement parent, string name, out bool value)
    {
        value = false;
        if (!parent.TryGetProperty(name, out var item)) return false;
        if (item.ValueKind == JsonValueKind.True || item.ValueKind == JsonValueKind.False) { value = item.GetBoolean(); return true; }
        var text = String(parent, name);
        if (text is "1" or "true" or "True") { value = true; return true; }
        if (text is "0" or "false" or "False") return true;
        return false;
    }
    public static Uri? Uri(JsonElement parent, string name) => System.Uri.TryCreate(String(parent, name), UriKind.Absolute, out var uri) ? uri : null;
    public static DateTimeOffset? UnixTime(JsonElement parent, string name) => long.TryParse(String(parent, name), out var value) ? DateTimeOffset.FromUnixTimeSeconds(value) : null;
    public static DateTimeOffset? Expiry(JsonElement parent, string name) => long.TryParse(String(parent, name), out var value) && value > 0 ? DateTimeOffset.FromUnixTimeSeconds(value) : null;
}

[SupportedOSPlatform("windows")]
public sealed class DpapiCredentialStore : ICredentialStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Tvivo.ProviderConnection.v1");
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = false };
    private readonly string _filePath;

    public DpapiCredentialStore(string? filePath = null)
    {
        _filePath = filePath ?? TvivoDataPaths.For("provider-connection.bin");
    }

    public async Task<ProviderConnection?> LoadAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        byte[] protectedBytes;
        try
        {
            protectedBytes = await File.ReadAllBytesAsync(_filePath, cancellationToken);
        }
        catch (FileNotFoundException)
        {
            return null;
        }
        catch (DirectoryNotFoundException)
        {
            return null;
        }

        cancellationToken.ThrowIfCancellationRequested();
        try
        {
            var json = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            cancellationToken.ThrowIfCancellationRequested();
            var connection = JsonSerializer.Deserialize<ProviderConnection>(json, JsonOptions);
            if (connection?.Endpoint is null || connection.Username is null || connection.Password is null)
                throw new InvalidDataException("Stored provider connection is incomplete.");
            return connection;
        }
        catch (CryptographicException exception)
        {
            throw new InvalidDataException("Stored provider connection could not be decrypted.", exception);
        }
        catch (JsonException exception)
        {
            throw new InvalidDataException("Stored provider connection is not valid JSON.", exception);
        }
    }

    public async Task SaveAsync(ProviderConnection connection, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        cancellationToken.ThrowIfCancellationRequested();
        var json = JsonSerializer.SerializeToUtf8Bytes(connection, JsonOptions);
        var protectedBytes = ProtectedData.Protect(json, Entropy, DataProtectionScope.CurrentUser);
        cancellationToken.ThrowIfCancellationRequested();

        var directory = Path.GetDirectoryName(_filePath)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(_filePath)}.{Guid.NewGuid():N}.tmp");
        try
        {
            await using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await stream.WriteAsync(protectedBytes, cancellationToken);
                await stream.FlushAsync(cancellationToken);
                stream.Flush(flushToDisk: true);
            }

            cancellationToken.ThrowIfCancellationRequested();
            File.Move(temporaryPath, _filePath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public Task DeleteAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        File.Delete(_filePath);
        return Task.CompletedTask;
    }
}
