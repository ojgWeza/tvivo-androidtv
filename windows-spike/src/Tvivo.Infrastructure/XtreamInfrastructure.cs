using System.Net;
using System.IO.Compression;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Globalization;
using System.Collections.Concurrent;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class XtreamCatalogProvider : ICatalogProvider, ISeriesCatalogProvider, IMovieInfoProvider, IEpgProvider, IProviderSessionStatus
{
    private readonly HttpClient _http;
    private readonly HttpClient _epgHttp;
    private readonly ConcurrentDictionary<string, ProviderConnection> _connections = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<(string AccountId, string SeriesId), SeriesDetails> _seriesDetails = new();
    private readonly ConcurrentDictionary<string, int> _httpsPorts = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, IReadOnlyList<EpgChannelMap>> _epgMaps = new(StringComparer.Ordinal);
    private readonly Action<string>? _epgLog;
    private readonly EpgFeature _epgFeature;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly SemaphoreSlim _paceGate = new(1, 1);
    private long _lastApiRequestTicks;
    private long _lastMissingSessionLogTicks;

    // Providers rate-limit bursts with HTTP 429. Space API calls out and retry a throttled call a few
    // times, honouring Retry-After, before surfacing a failure.
    private static readonly TimeSpan MinApiRequestSpacing = TimeSpan.FromMilliseconds(300);
    private static readonly TimeSpan MaxRetryAfter = TimeSpan.FromSeconds(20);
    private const int MaxThrottleRetries = CatalogRetryPolicy.MaxAttempts;

    public XtreamCatalogProvider(HttpClient? httpClient = null, HttpClient? epgHttpClient = null, Action<string>? epgLog = null,
        Func<TimeSpan, CancellationToken, Task>? delay = null, EpgFeature? epgFeature = null)
    {
        _delay = delay ?? Task.Delay;
        _epgFeature = epgFeature ?? new EpgFeature();
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
                return AuthenticationResult.Failed(AuthFailureReason.NetworkFailure, $"http_{(int)response.StatusCode}");
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
            // An empty port means the provider is reached on its default port; the port the panel reports
            // for itself (often an internal one) must not replace that.
            var port = connection.Endpoint.Port > 0
                ? JsonValue.Int(server, "port") ?? connection.Endpoint.Port
                : 0;
            var endpoint = connection.Endpoint with { Port = port };
            var accountId = connection.AccountId ?? AccountIdentity.For(connection.Endpoint, connection.Username);
            var adjustedId = AccountIdentity.For(endpoint, connection.Username);
            var account = new ProviderAccount(
                accountId,
                endpoint,
                connection.Username.Trim(),
                JsonValue.String(user, "username"),
                JsonValue.Int(user, "max_connections") ?? JsonValue.Int(server, "max_connections"),
                JsonValue.Expiry(user, "exp_date"));
            _connections[accountId] = connection with { Endpoint = endpoint };
            _connections[adjustedId] = connection with { Endpoint = endpoint };
            var httpsPort = JsonValue.Int(server, "https_port") ?? 0;
            _httpsPorts[accountId] = httpsPort;
            _httpsPorts[adjustedId] = httpsPort;
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
        catch (CatalogFailureException failure)
        {
            return AuthenticationResult.Failed(AuthFailureReason.NetworkFailure,
                failure.Failure.Kind == CatalogFailureKind.Timeout ? "network_timeout" : "network_error");
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

    private async Task<HttpResponseMessage> SendApiAsync(Uri uri, CancellationToken cancellationToken, string operation = "auth")
    {
        var trace = Guid.NewGuid().ToString("N")[..12];
        for (var attempt = 1; attempt <= MaxThrottleRetries; attempt++)
        {
            var started = System.Diagnostics.Stopwatch.GetTimestamp();
            try
            {
                await WaitForApiSlotAsync(cancellationToken).ConfigureAwait(false);
                var response = await _http.GetAsync(uri, cancellationToken).ConfigureAwait(false);
                var status = (int)response.StatusCode;
                var failure = CatalogFailureClassifier.Classify(status, null, "http", trace);
                if (response.IsSuccessStatusCode || !failure.Retryable || attempt == MaxThrottleRetries)
                {
                    response.Headers.TryAddWithoutValidation("X-Tvivo-Trace", trace);
                    Trace(trace, operation, "http", attempt, response.IsSuccessStatusCode ? "ok" : failure.Kind.ToString(), status,
                        started, "none", null);
                    if (!response.IsSuccessStatusCode && operation != "auth")
                    {
                        response.Dispose();
                        throw new CatalogFailureException(failure);
                    }
                    return response;
                }
                var retryAfter = response.Headers.RetryAfter switch
                {
                    { Delta: { } delta } => DateTimeOffset.UtcNow + delta,
                    { Date: { } date } => date,
                    _ => (DateTimeOffset?)null,
                };
                var wait = CatalogRetryPolicy.Delay(attempt, DateTimeOffset.UtcNow, retryAfter, Random.Shared.NextDouble());
                response.Dispose();
                Trace(trace, operation, "http", attempt, failure.Kind.ToString(), status, started, "none", DateTimeOffset.UtcNow + wait);
                await _delay(wait, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception exception) when ((exception is HttpRequestException or TaskCanceledException) && !cancellationToken.IsCancellationRequested)
            {
                var failure = CatalogFailureClassifier.Classify(null, exception, "http", trace);
                if (!failure.Retryable || attempt == MaxThrottleRetries)
                {
                    Trace(trace, operation, "http", attempt, failure.Kind.ToString(), failure.HttpStatus, started, exception.GetType().Name, null);
                    throw new CatalogFailureException(failure, exception);
                }
                var wait = CatalogRetryPolicy.Delay(attempt, DateTimeOffset.UtcNow, null, Random.Shared.NextDouble());
                Trace(trace, operation, "http", attempt, failure.Kind.ToString(), failure.HttpStatus, started, exception.GetType().Name, DateTimeOffset.UtcNow + wait);
                await _delay(wait, cancellationToken).ConfigureAwait(false);
            }
        }
        throw new InvalidOperationException("Catalog retry exhausted.");
    }

    private void Trace(string trace, string op, string stage, int attempt, string outcome, int? http,
        long started, string exception, DateTimeOffset? retryAt) => _epgLog?.Invoke(
        $"utc={DateTimeOffset.UtcNow:O} session=app trace={trace} op={op} stage={stage} attempt={attempt} outcome={outcome} http={http?.ToString() ?? "none"} elapsedMs={System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds:0} exception={exception} retryAt={retryAt?.ToString("O") ?? "none"}");

    private static string TraceId(HttpResponseMessage response) =>
        response.Headers.TryGetValues("X-Tvivo-Trace", out var values) ? values.First() : Guid.NewGuid().ToString("N")[..12];

    private async Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response, string operation, CancellationToken cancellationToken)
    {
        try
        {
            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            return await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        }
        catch (JsonException exception)
        {
            var failure = new CatalogFailure(CatalogFailureKind.Protocol, "json.parse", (int)response.StatusCode, TraceId(response));
            _epgLog?.Invoke($"utc={DateTimeOffset.UtcNow:O} session=app trace={failure.TraceId} op={operation} stage=json.parse attempt=1 outcome=Protocol http={(int)response.StatusCode} elapsedMs=0 exception=JsonException retryAt=none");
            throw new CatalogFailureException(failure, exception);
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
        _httpsPorts.TryGetValue(account.AccountId, out var port) && port > 0 ? port : null;

    public bool HasSession(string accountId) => _connections.ContainsKey(accountId);

    private ProviderSessionException MissingSession(string operation)
    {
        var now = Environment.TickCount64;
        var last = Interlocked.Read(ref _lastMissingSessionLogTicks);
        if (now - last >= 60_000 && Interlocked.CompareExchange(ref _lastMissingSessionLogTicks, now, last) == last)
            _epgLog?.Invoke($"event=provider.not-authenticated op={operation} knownConnections={_connections.Count}");
        return new ProviderSessionException();
    }

    public async Task<MovieDetails> GetMovieInfoAsync(ProviderAccount account, string movieId, CancellationToken cancellationToken = default)
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw MissingSession("vodInfo");
        using var response = await SendApiAsync(XtreamRequest.VodInfoUri(connection, movieId), cancellationToken, "vod-info");
        response.EnsureSuccessStatusCode();
        using var document = await ReadJsonAsync(response, "vod-info", cancellationToken);
        if (document.RootElement.ValueKind != JsonValueKind.Object)
            throw new CatalogFailureException(new(CatalogFailureKind.Protocol, "vod-info.shape", (int)response.StatusCode, TraceId(response)));
        var info = GetInfoObject(document.RootElement);
        return new MovieDetails(movieId, MapMetadata(info, document.RootElement));
    }

    public async Task<SeriesDetails> GetSeriesInfoAsync(ProviderAccount account, string seriesId, CancellationToken cancellationToken = default)
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw MissingSession("seriesInfo");
        var uri = XtreamRequest.SeriesInfoUri(connection, seriesId);
        using var response = await SendApiAsync(uri, cancellationToken, "series-info");
        response.EnsureSuccessStatusCode();
        using var document = await ReadJsonAsync(response, "series-info", cancellationToken);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            throw new CatalogFailureException(new(CatalogFailureKind.Protocol, "series-info.shape", (int)response.StatusCode, TraceId(response)));
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
                var episodes = MapEpisodes(group.Value, connection, seasonId, seasonName, seasonNumber);
                seasons.Add(new SeriesSeason(seasonId, seasonName, seasonNumber,
                    episodes.OrderBy(episode => episode.EpisodeNumber).ToArray()));
            }
        }
        else if (root.TryGetProperty("episodes", out episodeGroups) && episodeGroups.ValueKind == JsonValueKind.Array)
        {
            var nested = episodeGroups.EnumerateArray().ToArray();
            if (nested.Any(item => item.ValueKind == JsonValueKind.Array))
            {
                for (var index = 0; index < nested.Length; index++)
                {
                    var seasonId = (index + 1).ToString(CultureInfo.InvariantCulture);
                    seasons.Add(new SeriesSeason(seasonId, $"Season {seasonId}", index + 1,
                        MapEpisodes(nested[index], connection, seasonId, $"Season {seasonId}", index + 1)));
                }
            }
            else
                seasons.Add(new SeriesSeason("1", "Season 1", 1,
                    MapEpisodes(episodeGroups, connection, "1", "Season 1", 1)));
        }
        // A malformed optional episodes field behaves like an empty result; keep the last usable details.
        var details = new SeriesDetails(seriesId, title ?? seriesId, seasons.OrderBy(season => season.Number).ToArray(), MapMetadata(info, root));
        if (details.Seasons.Sum(season => season.Episodes.Count) == 0 &&
            _seriesDetails.TryGetValue((account.AccountId, seriesId), out var saved))
            return saved;
        if (details.Seasons.Sum(season => season.Episodes.Count) > 0)
            _seriesDetails[(account.AccountId, seriesId)] = details;
        return details;
    }

    private static IReadOnlyList<SeriesEpisode> MapEpisodes(JsonElement value, ProviderConnection connection,
        string seasonId, string seasonName, int seasonNumber)
    {
        var entries = value.ValueKind switch
        {
            JsonValueKind.Array => value.EnumerateArray().ToArray(),
            JsonValueKind.Object => value.EnumerateObject().Select(property => property.Value).ToArray(),
            _ => Array.Empty<JsonElement>(),
        };
        var episodes = new List<SeriesEpisode>();
        foreach (var item in entries)
        {
            var id = JsonValue.String(item, "id");
            if (string.IsNullOrWhiteSpace(id)) continue;
            var number = JsonValue.Int(item, "episode_num") ?? episodes.Count + 1;
            var title = JsonValue.String(item, "title") ?? $"Episode {number}";
            var extension = JsonValue.String(item, "container_extension")?.Trim().TrimStart('.');
            var durationValue = JsonValue.String(item, "duration");
            TimeSpan? duration = long.TryParse(durationValue, NumberStyles.Integer, CultureInfo.InvariantCulture, out var seconds) &&
                seconds >= 0 && seconds <= TimeSpan.MaxValue.TotalSeconds
                ? TimeSpan.FromSeconds(seconds)
                : TimeSpan.TryParse(durationValue, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
            var directUri = new Uri(StreamUrlBuilder.Series(connection.Endpoint, connection.Username, connection.Password, id, extension));
            episodes.Add(new SeriesEpisode(id, title, seasonId, seasonName, seasonNumber, number,
                new StreamSource(id, StreamKind.Episode, extension, directUri, duration),
                ReadSizeBytes(item, GetInfoObject(item))));
        }
        return episodes;
    }

    private static JsonElement GetInfoObject(JsonElement root) =>
        root.ValueKind == JsonValueKind.Object && root.TryGetProperty("info", out var info) && info.ValueKind == JsonValueKind.Object
            ? info
            : root;

    private static CatalogMetadata MapMetadata(JsonElement info, JsonElement root) => new(
        JsonValue.String(info, "year"), JsonValue.String(info, "rating"), JsonValue.String(info, "genre"),
        FirstNonEmpty(JsonValue.String(info, "plot"), JsonValue.String(info, "description")),
        FirstNonEmpty(JsonValue.String(info, "cast"), JsonValue.String(info, "actors")), ReadSizeBytes(info, root));

    private static long? ReadSizeBytes(params JsonElement[] nodes)
    {
        foreach (var node in nodes)
            foreach (var field in new[] { "size", "filesize", "file_size", "size_bytes" })
                if (FileSizeFormatter.ParseBytes(JsonValue.String(node, field)) is { } bytes) return bytes;
        return null;
    }

    private static Uri? ArtworkUri(JsonElement item)
    {
        foreach (var field in new[] { "cover", "cover_big", "backdrop_path", "stream_icon", "movie_image" })
        {
            if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty(field, out var value)) continue;
            if (value.ValueKind == JsonValueKind.Array)
            {
                foreach (var entry in value.EnumerateArray())
                    if (entry.ValueKind == JsonValueKind.String &&
                        Uri.TryCreate(entry.GetString(), UriKind.Absolute, out var arrayUri) &&
                        arrayUri.Scheme is "http" or "https") return arrayUri;
            }
            else if (value.ValueKind == JsonValueKind.String &&
                     Uri.TryCreate(value.GetString(), UriKind.Absolute, out var uri) &&
                     uri.Scheme is "http" or "https") return uri;
        }
        return null;
    }

    private static string? FirstNonEmpty(params string?[] values) =>
        values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))?.Trim();

    public Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, CancellationToken cancellationToken = default) =>
        GetArrayAsync(account, CategoryAction(type), null, MapGroup, cancellationToken);

    public async Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken cancellationToken = default)
    {
        var channels = await GetArrayAsync(account, ItemAction(type), groupId, (value, accountId) => MapChannel(value, accountId, type), cancellationToken).ConfigureAwait(false);
        if (type == CatalogItemType.Live && _epgFeature.Enabled)
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
                _epgMaps.AddOrUpdate(account.AccountId, incoming, (_, existing) =>
                {
                    var merged = existing.ToDictionary(map => map.StreamId, StringComparer.Ordinal);
                    foreach (var map in incoming) merged[map.StreamId] = map;
                    return merged.Values.ToArray();
                });
            }
        }
        return channels;
    }

    private async Task<IReadOnlyList<T>> GetArrayAsync<T>(ProviderAccount account, string action, string? groupId,
        Func<JsonElement, string, T?> map, CancellationToken cancellationToken) where T : class
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw MissingSession(action.Contains("categories", StringComparison.Ordinal) ? "categories" : "items");
        using var response = await SendApiAsync(XtreamRequest.Uri(connection, "player_api.php", action, groupId), cancellationToken,
            action == "get_series" ? "series-list" : action.Contains("categories", StringComparison.Ordinal) ? "categories" : "items");
        response.EnsureSuccessStatusCode();
        using var phase = PerfScope.BeginPhase("jsonParse");
        using var document = await ReadJsonAsync(response, action == "get_series" ? "series-list" : "items", cancellationToken);
        var root = document.RootElement;
        if (root.ValueKind == JsonValueKind.Null) return Array.Empty<T>();
        var array = root;
        if (array.ValueKind == JsonValueKind.Object && array.TryGetProperty("data", out var data)) array = data;
        var entries = array.ValueKind switch
        {
            JsonValueKind.Array => array.EnumerateArray().ToArray(),
            JsonValueKind.Object => array.EnumerateObject().Select(property => property.Value).ToArray(),
            _ => Array.Empty<JsonElement>(),
        };
        if (array.ValueKind == JsonValueKind.Null) return Array.Empty<T>();
        if (array.ValueKind is not (JsonValueKind.Array or JsonValueKind.Object))
            throw new CatalogFailureException(new(CatalogFailureKind.Protocol, "catalog.shape", (int)response.StatusCode, TraceId(response)));
        var result = new List<T>();
        foreach (var item in entries)
        {
            var mapped = item.ValueKind == JsonValueKind.Object ? map(item, account.AccountId) : null;
            if (mapped is not null) result.Add(mapped);
        }
        if (entries.Length > 0 && result.Count == 0)
            throw new CatalogFailureException(new(CatalogFailureKind.Protocol, "catalog.items", (int)response.StatusCode, TraceId(response)));
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
        var icon = ArtworkUri(value);
        // Xtream movies and live channels carry "added"; series only carry "last_modified".
        var added = JsonValue.UnixTime(value, "added") ??
            (type == CatalogItemType.Series ? JsonValue.UnixTime(value, "last_modified") : null);
        var order = JsonValue.Int(value, "num");
        var groupId = JsonValue.String(value, "category_id");
        var extension = type == CatalogItemType.Live
            ? JsonValue.String(value, "ext")?.Trim().TrimStart('.')
            : JsonValue.String(value, "container_extension")?.Trim().TrimStart('.');
        var metadata = new Dictionary<string, string>();
        if (ReadSizeBytes(value) is { } sizeBytes)
            metadata["size_bytes"] = sizeBytes.ToString(CultureInfo.InvariantCulture);
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
        if (!_epgFeature.Enabled) return Task.FromResult<IReadOnlyList<EpgChannelMap>>(Array.Empty<EpgChannelMap>());
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
        if (!_epgFeature.Enabled) return new EpgProbeResult(EpgCapability.Empty);

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
        cancellationToken.ThrowIfCancellationRequested();
        if (!_epgFeature.Enabled) return Stream.Null;

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
    // UriBuilder treats -1 as "scheme default port"; 0 would produce ":0".
    private static UriBuilder Builder(ProviderConnection connection, string path) =>
        new(connection.Endpoint.Scheme, connection.Endpoint.Host, connection.Endpoint.Port > 0 ? connection.Endpoint.Port : -1, path);

    public static Uri Uri(ProviderConnection connection, string path, string? action = null, string? groupId = null)
    {
        var builder = Builder(connection, path);
        var query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}";
        if (action is not null) query += $"&action={WebUtility.UrlEncode(action)}";
        if (groupId is not null) query += $"&category_id={WebUtility.UrlEncode(groupId)}";
        builder.Query = query;
        return builder.Uri;
    }

    public static Uri SeriesInfoUri(ProviderConnection connection, string seriesId)
    {
        var builder = Builder(connection, "player_api.php");
        builder.Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_series_info&series_id={WebUtility.UrlEncode(seriesId)}";
        return builder.Uri;
    }

    public static Uri VodInfoUri(ProviderConnection connection, string movieId)
    {
        var builder = new UriBuilder(Builder(connection, "player_api.php").Uri)
        {
            Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_vod_info&vod_id={WebUtility.UrlEncode(movieId)}"
        };
        return builder.Uri;
    }

    public static Uri ShortEpgUri(ProviderConnection connection, string streamId)
    {
        var builder = Builder(connection, "player_api.php");
        builder.Query = $"username={WebUtility.UrlEncode(connection.Username)}&password={WebUtility.UrlEncode(connection.Password)}&action=get_short_epg&stream_id={WebUtility.UrlEncode(streamId)}&limit=20";
        return builder.Uri;
    }

    public static Uri XmltvUri(ProviderConnection connection)
    {
        var builder = Builder(connection, "xmltv.php");
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
    public static DateTimeOffset? UnixTime(JsonElement parent, string name) =>
        long.TryParse(String(parent, name), out var value) && value >= -62135596800 && value <= 253402300799
            ? DateTimeOffset.FromUnixTimeSeconds(value) : null;
    public static DateTimeOffset? Expiry(JsonElement parent, string name) =>
        long.TryParse(String(parent, name), out var value) && value > 0 && value <= 253402300799
            ? DateTimeOffset.FromUnixTimeSeconds(value) : null;
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
