using System.Net;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class XtreamCatalogProvider : ICatalogProvider
{
    private readonly HttpClient _http;
    private readonly Dictionary<string, ProviderConnection> _connections = new(StringComparer.Ordinal);
    private readonly Dictionary<string, int?> _httpsPorts = new(StringComparer.Ordinal);

    public XtreamCatalogProvider(HttpClient? httpClient = null)
    {
        _http = httpClient ?? new HttpClient();
    }

    public async Task<AuthenticationResult> AuthenticateAsync(ProviderConnection connection, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await _http.GetAsync(XtreamRequest.Uri(connection, "player_api.php"), cancellationToken);
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

    public int? GetHttpsPort(ProviderAccount account) =>
        _httpsPorts.TryGetValue(account.AccountId, out var port) ? port : null;

    public Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CancellationToken cancellationToken = default) =>
        GetArrayAsync(account, "get_live_categories", null, MapGroup, cancellationToken);

    public Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, string? groupId = null, CancellationToken cancellationToken = default) =>
        GetArrayAsync(account, "get_live_streams", groupId, MapChannel, cancellationToken);

    private async Task<IReadOnlyList<T>> GetArrayAsync<T>(ProviderAccount account, string action, string? groupId,
        Func<JsonElement, string, T?> map, CancellationToken cancellationToken) where T : class
    {
        if (!_connections.TryGetValue(account.AccountId, out var connection))
            throw new InvalidOperationException("The provider account has not been authenticated by this catalog provider.");
        using var response = await _http.GetAsync(XtreamRequest.Uri(connection, "player_api.php", action, groupId), cancellationToken);
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

    private static Channel? MapChannel(JsonElement value, string accountId)
    {
        var id = JsonValue.String(value, "stream_id");
        if (string.IsNullOrWhiteSpace(id)) return null;
        var name = JsonValue.String(value, "name") ?? id;
        var icon = JsonValue.Uri(value, "stream_icon");
        var added = JsonValue.UnixTime(value, "added");
        var order = JsonValue.Int(value, "num");
        var groupId = JsonValue.String(value, "category_id");
        var extension = JsonValue.String(value, "ext")?.Trim().TrimStart('.');
        return new Channel(accountId, id, groupId, name, name.Trim(), icon, added, order,
            new StreamSource(id, StreamKind.Live, string.IsNullOrEmpty(extension) ? null : extension),
            new Dictionary<string, string>());
    }

}

public static class AccountIdentity
{
    public static string For(ProviderEndpoint endpoint, string username)
    {
        var canonical = $"{endpoint.Host.Trim().ToLowerInvariant()}|{endpoint.Port}|{username.Trim()}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant()[..32];
    }
}

public static class StreamUrlBuilder
{
    public static string Live(ProviderEndpoint endpoint, string username, string password, string streamId, string? extension) =>
        Build(endpoint, username, password, "live", streamId, extension);

    public static string Redact(string value) => System.Text.RegularExpressions.Regex.Replace(
        value, @"/(live|movie|series)/([^/]+)/([^/]+)/", "/$1/***/***/", System.Text.RegularExpressions.RegexOptions.IgnoreCase);

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
        _filePath = filePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Tvivo",
            "provider-connection.bin");
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
