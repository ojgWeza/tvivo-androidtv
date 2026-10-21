using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public interface IFileSizeProbe
{
    Task<long?> ProbeAsync(Uri mediaUri, CancellationToken cancellationToken = default);
}

public sealed class HttpFileSizeProbe : IFileSizeProbe, IDisposable
{
    private static readonly SemaphoreSlim GlobalGate = new(1, 1);
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(6);
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

    public async Task<long?> ProbeAsync(Uri mediaUri, CancellationToken cancellationToken = default)
    {
        var started = Stopwatch.GetTimestamp();
        var result = "none";
        var reason = "no_size";
        var entered = false;
        try
        {
            await GlobalGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            entered = true;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(ProbeTimeout);
            using var request = new HttpRequestMessage(HttpMethod.Get, mediaUri);
            request.Headers.Range = new RangeHeaderValue(0, 0);
            using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token)
                .ConfigureAwait(false);
            var size = FileSizeProbePolicy.ParseTotalBytes(response.Headers, response.Content.Headers, response.StatusCode);
            result = size.HasValue ? "found" : "none";
            reason = size.HasValue ? "headers" : response.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable ? "range_rejected" : "no_size";
            return size;
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
            reason = "unexpected";
            return null;
        }
        finally
        {
            if (entered) GlobalGate.Release();
            try
            {
                _log?.Invoke($"event=filesize.probe result={result} reasonCategory={reason} ms={Stopwatch.GetElapsedTime(started).TotalMilliseconds:0}");
            }
            catch { /* Diagnostics must not turn an optional probe into a playback failure. */ }
        }
    }

    public void Dispose() => _client.Dispose();
}
