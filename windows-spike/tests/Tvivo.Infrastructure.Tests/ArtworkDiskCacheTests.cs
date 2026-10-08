using System.Net;
using System.Net.Http.Headers;
using System.Text;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class ArtworkDiskCacheTests
{
    private static readonly Uri Url = new("https://images.example.test/poster?token=private-value");

    [Fact]
    public async Task Same_url_for_two_decode_sizes_fetches_original_bytes_once_and_hides_url_on_disk()
    {
        using var root = new CacheRoot();
        var calls = 0;
        using var client = Client((_, _) =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromResult(Ok("original image bytes"));
        });
        var cache = new ArtworkDiskCache(client, root.Cache);

        var small = await cache.GetAsync(Url);
        var large = await cache.GetAsync(Url);

        Assert.Equal(small, large);
        Assert.Equal(1, calls);
        Assert.Equal(Encoding.UTF8.GetByteCount("original image bytes"), cache.TotalBytes);
        Assert.DoesNotContain("images.example.test", string.Join(" ", Directory.GetFileSystemEntries(root.Cache)));
        Assert.DoesNotContain("private-value", string.Join(" ", Directory.GetFiles(root.Cache, "*.json").Select(File.ReadAllText)));
    }

    [Fact]
    public async Task Concurrent_same_url_calls_coalesce_to_one_get()
    {
        using var root = new CacheRoot();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        using var client = Client(async (_, _) =>
        {
            Interlocked.Increment(ref calls);
            started.TrySetResult();
            await release.Task;
            return Ok("shared");
        });
        var cache = new ArtworkDiskCache(client, root.Cache);

        var reads = Enumerable.Range(0, 24).Select(_ => cache.GetAsync(Url)).ToArray();
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(1, calls);
        release.SetResult();
        var results = await Task.WhenAll(reads);

        Assert.All(results, bytes => Assert.Equal(Encoding.UTF8.GetBytes("shared"), bytes));
        Assert.Equal(1, calls);
    }

    [Fact]
    public async Task Expired_entry_is_served_when_http_fails()
    {
        using var root = new CacheRoot();
        var clock = new TestClock();
        var calls = 0;
        using var client = Client((_, _) =>
        {
            if (Interlocked.Increment(ref calls) == 1) return Task.FromResult(Ok("stale"));
            throw new HttpRequestException("offline");
        });
        var cache = new ArtworkDiskCache(client, root.Cache, clock, TimeSpan.FromDays(30));

        Assert.Equal("stale", Text(await cache.GetAsync(Url)));
        clock.Advance(TimeSpan.FromDays(31));
        Assert.Equal("stale", Text(await cache.GetAsync(Url)));
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task Expired_entry_sends_validators_and_304_reuses_bytes()
    {
        using var root = new CacheRoot();
        var clock = new TestClock();
        var calls = 0;
        var modified = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
        using var client = Client((request, _) =>
        {
            if (Interlocked.Increment(ref calls) == 1)
            {
                var response = Ok("unchanged");
                response.Headers.ETag = new EntityTagHeaderValue("\"revision-1\"");
                response.Content.Headers.LastModified = modified;
                return Task.FromResult(response);
            }
            Assert.Equal("\"revision-1\"", Assert.Single(request.Headers.IfNoneMatch).ToString());
            Assert.Equal(modified, request.Headers.IfModifiedSince);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotModified));
        });
        var cache = new ArtworkDiskCache(client, root.Cache, clock, TimeSpan.FromDays(30));

        Assert.Equal("unchanged", Text(await cache.GetAsync(Url)));
        clock.Advance(TimeSpan.FromDays(31));
        Assert.Equal("unchanged", Text(await cache.GetAsync(Url)));
        Assert.Equal("unchanged", Text(await cache.GetAsync(Url)));
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task Truncated_blob_is_a_miss_and_refetches()
    {
        using var root = new CacheRoot();
        var calls = 0;
        using var client = Client((_, _) => Task.FromResult(Ok(Interlocked.Increment(ref calls) == 1 ? "original" : "replacement")));
        var cache = new ArtworkDiskCache(client, root.Cache);

        Assert.Equal("original", Text(await cache.GetAsync(Url)));
        File.WriteAllBytes(Assert.Single(Directory.GetFiles(root.Cache, "*.blob")), [1]);
        Assert.Equal("replacement", Text(await cache.GetAsync(Url)));
        Assert.Equal(2, calls);
        Assert.Single(Directory.GetFiles(root.Cache, "*.blob"));
    }

    [Fact]
    public async Task Malformed_metadata_cannot_escape_the_cache_root()
    {
        using var root = new CacheRoot();
        using var client = Client((_, _) => Task.FromResult(Ok("image")));
        var cache = new ArtworkDiskCache(client, root.Cache);
        await cache.GetAsync(Url);
        var metadata = Assert.Single(Directory.GetFiles(root.Cache, "*.json"));
        File.WriteAllText(metadata, "{\"Revision\":\"../../outside\",\"Length\":5,\"Sha256\":\"" + new string('a', 64) + "\"}");

        Assert.Equal("image", Text(await cache.GetAsync(Url)));
        cache.Clear();
        Assert.True(File.Exists(root.OutsideSentinel));
    }

    [Fact]
    public async Task Clear_during_fetch_does_not_repopulate_disk()
    {
        using var root = new CacheRoot();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var client = Client(async (_, _) =>
        {
            started.TrySetResult();
            await release.Task;
            return Ok("after clear");
        });
        var cache = new ArtworkDiskCache(client, root.Cache);

        var fetch = cache.GetAsync(Url);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cache.Clear();
        release.SetResult();

        Assert.Equal("after clear", Text(await fetch));
        Assert.Equal(0, cache.TotalBytes);
        Assert.Empty(Directory.GetFiles(root.Cache));
        Assert.True(File.Exists(root.OutsideSentinel));
    }

    [Fact]
    public async Task Clear_and_lru_eviction_only_delete_managed_files_inside_test_root()
    {
        using var root = new CacheRoot();
        var clock = new TestClock();
        var calls = 0;
        using var client = Client((_, _) =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromResult(Ok(new string('x', 40)));
        });
        var cache = new ArtworkDiskCache(client, root.Cache, clock, maxBytes: 100, maxImageBytes: 60);
        var urls = Enumerable.Range(0, 3).Select(index => new Uri($"https://images.example.test/{index}")).ToArray();

        foreach (var url in urls)
        {
            await cache.GetAsync(url);
            clock.Advance(TimeSpan.FromMinutes(1));
        }
        Assert.Equal(80, cache.TotalBytes);
        Assert.Equal(2, Directory.GetFiles(root.Cache, "*.blob").Length);
        Assert.Equal(3, calls);
        cache.Clear();

        Assert.Equal(0, cache.TotalBytes);
        Assert.Empty(Directory.GetFiles(root.Cache));
        Assert.True(File.Exists(root.OutsideSentinel));
    }

    [Fact]
    public async Task Distinct_urls_obey_http_and_disk_read_limits()
    {
        using var root = new CacheRoot();
        var requestRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var twoRequests = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var activeRequests = 0;
        var peakRequests = 0;
        using var client = Client(async (_, _) =>
        {
            var active = Interlocked.Increment(ref activeRequests);
            UpdatePeak(ref peakRequests, active);
            if (active == 2) twoRequests.TrySetResult();
            await requestRelease.Task;
            Interlocked.Decrement(ref activeRequests);
            return Ok("bytes");
        });
        var urls = Enumerable.Range(0, 6).Select(index => new Uri($"https://images.example.test/{index}")).ToArray();
        var fill = new ArtworkDiskCache(client, root.Cache, maxConcurrentRequests: 2);
        var downloads = urls.Select(url => fill.GetAsync(url)).ToArray();
        await twoRequests.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(2, peakRequests);
        requestRelease.SetResult();
        await Task.WhenAll(downloads);

        var readRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var twoReads = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var activeReads = 0;
        var peakReads = 0;
        var reader = new ArtworkDiskCache(client, root.Cache, maxConcurrentDiskReads: 2,
            readBlob: async path =>
            {
                var active = Interlocked.Increment(ref activeReads);
                UpdatePeak(ref peakReads, active);
                if (active == 2) twoReads.TrySetResult();
                await readRelease.Task;
                var bytes = await File.ReadAllBytesAsync(path);
                Interlocked.Decrement(ref activeReads);
                return bytes;
            });
        var cachedReads = urls.Select(url => reader.GetAsync(url)).ToArray();
        await twoReads.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(2, peakReads);
        readRelease.SetResult();
        await Task.WhenAll(cachedReads);
        Assert.Equal(2, peakReads);
    }

    private static HttpClient Client(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) =>
        new(new FixtureHandler(responder));

    private static HttpResponseMessage Ok(string text) => new(HttpStatusCode.OK)
    {
        Content = new ByteArrayContent(Encoding.UTF8.GetBytes(text))
    };

    private static string? Text(byte[]? bytes) => bytes is null ? null : Encoding.UTF8.GetString(bytes);

    private static void UpdatePeak(ref int peak, int current)
    {
        int previous;
        do
        {
            previous = Volatile.Read(ref peak);
            if (current <= previous) return;
        } while (Interlocked.CompareExchange(ref peak, current, previous) != previous);
    }

    private sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            responder(request, cancellationToken);
    }

    private sealed class TestClock : TimeProvider
    {
        private DateTimeOffset _now = new(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now += duration;
    }

    private sealed class CacheRoot : IDisposable
    {
        private readonly string _parent = Path.Combine(Path.GetTempPath(), "TvivoArtworkDiskCacheTests", Guid.NewGuid().ToString("N"));
        public string Cache => Path.Combine(_parent, "artwork-cache");
        public string OutsideSentinel => Path.Combine(_parent, "outside.txt");

        public CacheRoot()
        {
            Directory.CreateDirectory(_parent);
            if (!Path.GetFullPath(Cache).StartsWith(Path.GetFullPath(_parent) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("The test cache root is not isolated.");
            File.WriteAllText(OutsideSentinel, "outside cache");
        }

        public void Dispose() => Directory.Delete(_parent, recursive: true);
    }
}
