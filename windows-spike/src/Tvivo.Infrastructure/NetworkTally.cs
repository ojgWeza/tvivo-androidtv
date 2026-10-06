using System.Net;
using System.Net.Sockets;

namespace Tvivo.Infrastructure;

/// <summary>
/// Counts the real bytes each HTTP client puts on the wire (before TLS, so headers and handshakes are
/// included). Always cheap; it only logs when <see cref="Log"/> is set, which the app does when the
/// TVIVO_NET_LOG environment variable is "1". Never logs a host, path or credential: only the client
/// category, the Xtream "action" name, status and byte counts.
/// </summary>
public static class NetworkTally
{
    private static readonly Dictionary<string, Counter> Counters = new(StringComparer.Ordinal);
    private static Timer? _timer;

    /// <summary>Set to enable periodic and per-request byte logging.</summary>
    public static Action<string>? Log { get; set; }

    public static void StartPeriodicLog(TimeSpan interval)
    {
        _timer?.Dispose();
        _timer = new Timer(_ => Flush(), null, interval, interval);
    }

    public static void Flush()
    {
        var log = Log;
        if (log is null) return;
        lock (Counters)
        {
            foreach (var (category, counter) in Counters)
                log($"event=net.total category={category} wireIn={counter.In} wireOut={counter.Out} connections={counter.Connections}");
        }
    }

    public static HttpClient CreateClient(string category, TimeSpan timeout)
    {
        var sockets = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            ConnectCallback = async (context, cancellationToken) =>
            {
                var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                try
                {
                    await socket.ConnectAsync(context.DnsEndPoint, cancellationToken).ConfigureAwait(false);
                    var counter = CounterFor(category);
                    Interlocked.Increment(ref counter.Connections);
                    return new CountingStream(new NetworkStream(socket, ownsSocket: true), counter);
                }
                catch
                {
                    socket.Dispose();
                    throw;
                }
            },
        };
        return new HttpClient(new RequestLogHandler(category, sockets)) { Timeout = timeout };
    }

    private static Counter CounterFor(string category)
    {
        lock (Counters)
        {
            if (!Counters.TryGetValue(category, out var counter))
                Counters[category] = counter = new Counter();
            return counter;
        }
    }

    private sealed class Counter
    {
        public long In;
        public long Out;
        public long Connections;
    }

    // Per-request wire bytes are only attributable when requests of a category run one at a time, as the
    // catalog calls do (paced, sequential). Artwork runs concurrently and is reported in totals only.
    private sealed class RequestLogHandler(string category, HttpMessageHandler inner) : DelegatingHandler(inner)
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var log = Log;
            if (log is null || category == "artwork")
                return await base.SendAsync(request, cancellationToken).ConfigureAwait(false);

            var counter = CounterFor(category);
            var before = Interlocked.Read(ref counter.In);
            var started = Environment.TickCount64;
            var response = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
            // Headers only so far; read the body to the end so the byte delta covers it.
            await response.Content.LoadIntoBufferAsync(cancellationToken).ConfigureAwait(false);
            var action = ActionOf(request.RequestUri);
            log($"event=net.request category={category} action={action} status={(int)response.StatusCode} " +
                $"wireIn={Interlocked.Read(ref counter.In) - before} decodedBytes={response.Content.Headers.ContentLength ?? -1} " +
                $"durationMs={Environment.TickCount64 - started}");
            return response;
        }

        private static string ActionOf(Uri? uri)
        {
            var query = uri?.Query ?? string.Empty;
            foreach (var part in query.TrimStart('?').Split('&'))
                if (part.StartsWith("action=", StringComparison.Ordinal))
                    return part["action=".Length..];
            return "auth";
        }
    }

    private sealed class CountingStream(Stream inner, Counter counter) : Stream
    {
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => false;
        public override bool CanWrite => inner.CanWrite;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();

        public override int Read(byte[] buffer, int offset, int count) => Count(inner.Read(buffer, offset, count), in_: true);
        public override int Read(Span<byte> buffer) => Count(inner.Read(buffer), in_: true);

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            Count(await inner.ReadAsync(buffer, cancellationToken).ConfigureAwait(false), in_: true);

        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            Count(await inner.ReadAsync(buffer.AsMemory(offset, count), cancellationToken).ConfigureAwait(false), in_: true);

        public override void Write(byte[] buffer, int offset, int count)
        {
            inner.Write(buffer, offset, count);
            Count(count, in_: false);
        }

        public override void Write(ReadOnlySpan<byte> buffer)
        {
            inner.Write(buffer);
            Count(buffer.Length, in_: false);
        }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            await inner.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
            Count(buffer.Length, in_: false);
        }

        public override async Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            await inner.WriteAsync(buffer.AsMemory(offset, count), cancellationToken).ConfigureAwait(false);
            Count(count, in_: false);
        }

        private int Count(int bytes, bool in_)
        {
            if (bytes > 0)
            {
                if (in_) Interlocked.Add(ref counter.In, bytes);
                else Interlocked.Add(ref counter.Out, bytes);
            }
            return bytes;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }

        public override ValueTask DisposeAsync() => inner.DisposeAsync();
    }
}
