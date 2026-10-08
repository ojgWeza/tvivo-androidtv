using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class ArtworkDiskCache
{
    public const long DefaultMaxBytes = 200L * 1024 * 1024;
    public const long DefaultMaxImageBytes = 20L * 1024 * 1024;

    private readonly HttpClient _client;
    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly TimeSpan _ttl;
    private readonly long _maxBytes;
    private readonly long _maxImageBytes;
    private readonly SemaphoreSlim _diskReads;
    private readonly SemaphoreSlim _requests;
    private readonly Func<string, Task<byte[]>> _readBlob;
    private readonly SemaphoreSlim _mutations = new(1);
    private readonly object _flightGate = new();
    private readonly Dictionary<string, Task<byte[]?>> _flights = new(StringComparer.Ordinal);
    private int _generation;

    public ArtworkDiskCache(
        HttpClient client,
        string? cacheRoot = null,
        TimeProvider? clock = null,
        TimeSpan? ttl = null,
        long maxBytes = DefaultMaxBytes,
        long maxImageBytes = DefaultMaxImageBytes,
        int maxConcurrentDiskReads = 4,
        int maxConcurrentRequests = 6,
        Func<string, Task<byte[]>>? readBlob = null)
    {
        ArgumentNullException.ThrowIfNull(client);
        if (maxBytes <= 0 || maxImageBytes <= 0 || maxImageBytes > maxBytes)
            throw new ArgumentOutOfRangeException(nameof(maxBytes));
        if (maxConcurrentDiskReads <= 0 || maxConcurrentRequests <= 0)
            throw new ArgumentOutOfRangeException(nameof(maxConcurrentDiskReads));
        _ttl = ttl ?? TimeSpan.FromDays(30);
        if (_ttl <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(ttl));
        _client = client;
        _root = Path.GetFullPath(cacheRoot ?? TvivoDataPaths.For("artwork-cache"));
        if (!string.Equals(Path.GetFileName(_root), "artwork-cache", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("The cache root must be an artwork-cache directory.", nameof(cacheRoot));
        _clock = clock ?? TimeProvider.System;
        _maxBytes = maxBytes;
        _maxImageBytes = maxImageBytes;
        _diskReads = new SemaphoreSlim(maxConcurrentDiskReads);
        _requests = new SemaphoreSlim(maxConcurrentRequests);
        _readBlob = readBlob ?? (path => File.ReadAllBytesAsync(path));
        EnsureRoot();
    }

    public long TotalBytes
    {
        get
        {
            _mutations.Wait();
            try { return ManagedBlobs().Sum(path => new FileInfo(path).Length); }
            finally { _mutations.Release(); }
        }
    }

    public Task<byte[]?> GetAsync(Uri imageUrl, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(imageUrl);
        if (!imageUrl.IsAbsoluteUri || imageUrl.Scheme is not ("http" or "https"))
            throw new ArgumentException("An absolute HTTP artwork URL is required.", nameof(imageUrl));
        cancellationToken.ThrowIfCancellationRequested();
        var key = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(imageUrl.AbsoluteUri)));
        Task<byte[]?> task;
        TaskCompletionSource<byte[]?>? completion = null;
        lock (_flightGate)
        {
            if (!_flights.TryGetValue(key, out task!))
            {
                completion = new TaskCompletionSource<byte[]?>(TaskCreationOptions.RunContinuationsAsynchronously);
                task = completion.Task;
                _flights.Add(key, task);
            }
        }
        if (completion is not null) _ = RunAsync(key, imageUrl, completion);
        return task.WaitAsync(cancellationToken);
    }

    public void Clear()
    {
        _mutations.Wait();
        try
        {
            EnsureRoot();
            Interlocked.Increment(ref _generation);
            foreach (var path in Directory.EnumerateFiles(_root, "*", SearchOption.TopDirectoryOnly))
            {
                if (!IsManagedFile(Path.GetFileName(path))) continue;
                if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidOperationException("Artwork cache contains a linked file.");
                File.Delete(path);
            }
        }
        finally { _mutations.Release(); }
    }

    private async Task RunAsync(string key, Uri url, TaskCompletionSource<byte[]?> completion)
    {
        try { completion.SetResult(await LoadOrFetchAsync(key, url)); }
        catch (Exception error) { completion.SetException(error); }
        finally
        {
            lock (_flightGate) _flights.Remove(key);
        }
    }

    private async Task<byte[]?> LoadOrFetchAsync(string key, Uri url)
    {
        var generation = Volatile.Read(ref _generation);
        var cached = await ReadAsync(key);
        var now = _clock.GetUtcNow();
        if (cached is not null && cached.Value.Entry.ExpiresAt > now)
        {
            await TouchAsync(key, cached.Value.Entry, now, generation);
            return cached.Value.Bytes;
        }

        await _requests.WaitAsync();
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            if (cached is { } stale)
            {
                if (EntityTagHeaderValue.TryParse(stale.Entry.ETag, out var tag))
                    request.Headers.IfNoneMatch.Add(tag);
                if (stale.Entry.LastModified is { } modified)
                    request.Headers.IfModifiedSince = modified;
            }
            using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
            if (response.StatusCode == HttpStatusCode.NotModified && cached is { } unchanged)
            {
                unchanged.Entry.FetchedAt = now;
                unchanged.Entry.ExpiresAt = now + _ttl;
                unchanged.Entry.LastAccessAt = now;
                await SaveMetadataAsync(key, unchanged.Entry, generation);
                return unchanged.Bytes;
            }
            if (!response.IsSuccessStatusCode) return cached?.Bytes;
            var bytes = await ReadLimitedAsync(response.Content);
            if (bytes is null) return cached?.Bytes;
            var entry = new Entry
            {
                Revision = Guid.NewGuid().ToString("N"),
                Length = bytes.Length,
                Sha256 = Convert.ToHexStringLower(SHA256.HashData(bytes)),
                FetchedAt = now,
                ExpiresAt = now + _ttl,
                LastAccessAt = now,
                ETag = response.Headers.ETag?.ToString(),
                LastModified = response.Content.Headers.LastModified
            };
            await StoreAsync(key, entry, bytes, generation);
            return bytes;
        }
        catch (HttpRequestException) { return cached?.Bytes; }
        catch (TaskCanceledException) { return cached?.Bytes; }
        catch (IOException) { return cached?.Bytes; }
        finally { _requests.Release(); }
    }

    private async Task<byte[]?> ReadLimitedAsync(HttpContent content)
    {
        if (content.Headers.ContentLength > _maxImageBytes) return null;
        await using var source = await content.ReadAsStreamAsync();
        using var destination = new MemoryStream();
        var buffer = new byte[81920];
        int count;
        while ((count = await source.ReadAsync(buffer)) != 0)
        {
            if (destination.Length + count > _maxImageBytes) return null;
            destination.Write(buffer, 0, count);
        }
        return destination.Length == 0 ? null : destination.ToArray();
    }

    private async Task<(Entry Entry, byte[] Bytes)?> ReadAsync(string key)
    {
        await _diskReads.WaitAsync();
        try
        {
            var metadataPath = MetadataPath(key);
            if (!File.Exists(metadataPath)) return null;
            Entry? entry;
            try
            {
                if ((File.GetAttributes(metadataPath) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidDataException();
                if (new FileInfo(metadataPath).Length > 4096) throw new InvalidDataException();
                entry = JsonSerializer.Deserialize<Entry>(await File.ReadAllTextAsync(metadataPath));
                if (entry is null || !Guid.TryParseExact(entry.Revision, "N", out _) ||
                    entry.Length <= 0 || entry.Length > _maxImageBytes ||
                    !IsHexHash(entry.Sha256)) throw new InvalidDataException();
                var blobPath = BlobPath(key, entry.Revision);
                if ((File.GetAttributes(blobPath) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidDataException();
                if (new FileInfo(blobPath).Length != entry.Length) throw new InvalidDataException();
                var bytes = await _readBlob(blobPath);
                if (!SHA256.HashData(bytes).SequenceEqual(Convert.FromHexString(entry.Sha256)))
                    throw new InvalidDataException();
                return (entry, bytes);
            }
            catch (Exception error) when (error is IOException or JsonException or InvalidDataException or FormatException)
            {
                await DeleteCorruptAsync(key);
                return null;
            }
        }
        finally { _diskReads.Release(); }
    }

    private async Task DeleteCorruptAsync(string key)
    {
        await _mutations.WaitAsync();
        try
        {
            File.Delete(MetadataPath(key));
            foreach (var path in ManagedBlobs().Where(path => Path.GetFileName(path).StartsWith(key + ".", StringComparison.Ordinal)))
                File.Delete(path);
        }
        finally { _mutations.Release(); }
    }

    private async Task TouchAsync(string key, Entry entry, DateTimeOffset now, int generation)
    {
        entry.LastAccessAt = now;
        await SaveMetadataAsync(key, entry, generation);
    }

    private async Task SaveMetadataAsync(string key, Entry entry, int generation)
    {
        await _mutations.WaitAsync();
        try
        {
            if (generation != _generation || !File.Exists(BlobPath(key, entry.Revision))) return;
            WriteMetadata(key, entry);
        }
        finally { _mutations.Release(); }
    }

    private async Task StoreAsync(string key, Entry entry, byte[] bytes, int generation)
    {
        await _mutations.WaitAsync();
        try
        {
            if (generation != _generation) return;
            EnsureRoot();
            var temporary = Path.Combine(_root, key + "." + Guid.NewGuid().ToString("N") + ".tmp");
            var blob = BlobPath(key, entry.Revision);
            try
            {
                await File.WriteAllBytesAsync(temporary, bytes);
                File.Move(temporary, blob);
                WriteMetadata(key, entry);
                Trim();
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }
        finally { _mutations.Release(); }
    }

    private void WriteMetadata(string key, Entry entry)
    {
        var temporary = Path.Combine(_root, key + "." + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(entry));
            File.Move(temporary, MetadataPath(key), true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private void Trim()
    {
        var entries = new List<(string Key, Entry Value)>();
        foreach (var metadata in Directory.EnumerateFiles(_root, "*.json", SearchOption.TopDirectoryOnly))
        {
            var key = Path.GetFileNameWithoutExtension(metadata);
            if (!IsHexHash(key)) continue;
            try
            {
                if ((File.GetAttributes(metadata) & FileAttributes.ReparsePoint) != 0) continue;
                var entry = JsonSerializer.Deserialize<Entry>(File.ReadAllText(metadata));
                if (entry is not null && Guid.TryParseExact(entry.Revision, "N", out _))
                    entries.Add((key, entry));
            }
            catch (JsonException) { }
        }
        var live = entries.Select(item => BlobPath(item.Key, item.Value.Revision)).ToHashSet(StringComparer.Ordinal);
        foreach (var orphan in ManagedBlobs().Where(path => !live.Contains(path))) File.Delete(orphan);
        var total = ManagedBlobs().Sum(path => new FileInfo(path).Length);
        if (total <= _maxBytes) return;
        var target = _maxBytes * 9 / 10;
        foreach (var (key, entry) in entries.OrderBy(item => item.Value.LastAccessAt))
        {
            var blob = BlobPath(key, entry.Revision);
            if (File.Exists(blob))
            {
                total -= new FileInfo(blob).Length;
                File.Delete(blob);
            }
            File.Delete(MetadataPath(key));
            if (total <= target) break;
        }
    }

    private void EnsureRoot()
    {
        Directory.CreateDirectory(_root);
        if ((File.GetAttributes(_root) & FileAttributes.ReparsePoint) != 0)
            throw new InvalidOperationException("Artwork cache root cannot be a linked directory.");
    }

    private IEnumerable<string> ManagedBlobs() => Directory.EnumerateFiles(_root, "*.blob", SearchOption.TopDirectoryOnly)
        .Where(path => IsManagedFile(Path.GetFileName(path)));

    private string MetadataPath(string key) => Path.Combine(_root, key + ".json");
    private string BlobPath(string key, string revision) => Path.Combine(_root, key + "." + revision + ".blob");

    private static bool IsManagedFile(string name)
    {
        var parts = name.Split('.');
        return parts.Length switch
        {
            2 => IsHexHash(parts[0]) && parts[1] == "json",
            3 => IsHexHash(parts[0]) && Guid.TryParseExact(parts[1], "N", out _) &&
                (parts[2] == "blob" || parts[2] == "tmp"),
            _ => false
        };
    }

    private static bool IsHexHash(string value) => value.Length == 64 &&
        value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private sealed class Entry
    {
        public string Revision { get; set; } = "";
        public long Length { get; set; }
        public string Sha256 { get; set; } = "";
        public DateTimeOffset FetchedAt { get; set; }
        public DateTimeOffset ExpiresAt { get; set; }
        public DateTimeOffset LastAccessAt { get; set; }
        public string? ETag { get; set; }
        public DateTimeOffset? LastModified { get; set; }
    }
}
