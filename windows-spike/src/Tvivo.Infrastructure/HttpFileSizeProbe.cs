using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public interface IFileSizeProbe
{
    Task<long?> ProbeAsync(Uri mediaUri, CancellationToken cancellationToken = default, long attemptId = 0,
        Action<string, string>? outcome = null);
}

public sealed class HttpFileSizeProbe : IFileSizeProbe, IDisposable
{
    private static readonly SemaphoreSlim GlobalGate = new(1, 1);
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(2);
    private readonly HttpClient _client;
    private readonly Action<string>? _log;

    public HttpFileSizeProbe(HttpMessageHandler? handler = null, Action<string>? log = null)
    {
        _client = new HttpClient(handler ?? new HttpClientHandler(), disposeHandler: true)
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
        _client.DefaultRequestHeaders.UserAgent.ParseAdd("Tvivo/1.0");
        _log = log;
    }

    public async Task<long?> ProbeAsync(Uri mediaUri, CancellationToken cancellationToken = default, long attemptId = 0,
        Action<string, string>? outcome = null)
    {
        var started = Stopwatch.GetTimestamp();
        var result = "none";
        var reason = "invalid_header";
        var entered = false;
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(ProbeTimeout);
            await GlobalGate.WaitAsync(timeout.Token).ConfigureAwait(false);
            entered = true;
            try
            {
                using var head = new HttpRequestMessage(HttpMethod.Head, mediaUri);
                using var response = await _client.SendAsync(head, HttpCompletionOption.ResponseHeadersRead, timeout.Token)
                    .ConfigureAwait(false);
                var size = FileSizeProbePolicy.ParseTotalBytes(response.Headers, response.Content.Headers, response.StatusCode);
                if (size is not null)
                {
                    result = "found";
                    reason = "head_length";
                    return size;
                }
            }
            catch (HttpRequestException) { /* A rejected HEAD can still allow a ranged GET. */ }
            using var request = new HttpRequestMessage(HttpMethod.Get, mediaUri);
            request.Headers.Range = new RangeHeaderValue(0, 0);
            using var ranged = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token)
                .ConfigureAwait(false);
            var rangedSize = FileSizeProbePolicy.ParseTotalBytes(ranged.Headers, ranged.Content.Headers, ranged.StatusCode);
            result = rangedSize.HasValue ? "found" : ranged.StatusCode is HttpStatusCode.OK or HttpStatusCode.PartialContent ? "none" : "failed";
            reason = rangedSize.HasValue ? "range_total" : result == "none" ? "invalid_header" : "http_status";
            return rangedSize;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            result = "failed";
            reason = "timeout";
            return null;
        }
        catch (OperationCanceledException)
        {
            result = "failed";
            reason = "cancelled";
            return null;
        }
        catch (HttpRequestException)
        {
            result = "failed";
            reason = "network";
            return null;
        }
        catch (Exception)
        {
            result = "failed";
            reason = "network";
            return null;
        }
        finally
        {
            if (entered) GlobalGate.Release();
            outcome?.Invoke(result, reason);
            try
            {
                _log?.Invoke($"event=filesize.http attempt={attemptId} result={result} reasonCategory={reason} ms={Stopwatch.GetElapsedTime(started).TotalMilliseconds:0}");
            }
            catch { /* Diagnostics must not turn an optional probe into a playback failure. */ }
        }
    }

    public void Dispose() => _client.Dispose();
}
