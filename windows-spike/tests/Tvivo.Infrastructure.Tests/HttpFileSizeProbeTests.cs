using System.Net;
using System.Net.Http.Headers;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class HttpFileSizeProbeTests
{
    [Fact]
    public async Task Range_total_is_preferred_and_body_is_not_read()
    {
        var methods = new List<HttpMethod>();
        var content = new ProbeContent("bytes 0-0/1500000", null);
        var handler = new ProbeHandler((request, _) =>
        {
            methods.Add(request.Method);
            if (request.Method == HttpMethod.Head)
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.MethodNotAllowed));
            Assert.Equal("bytes=0-0", request.Headers.Range!.ToString());
            var response = new HttpResponseMessage(HttpStatusCode.PartialContent)
            {
                Content = content,
            };
            return Task.FromResult(response);
        });
        using var probe = new HttpFileSizeProbe(handler);

        Assert.Equal(1_500_000, await probe.ProbeAsync(new Uri("http://localhost/media")));
        Assert.Equal(new[] { HttpMethod.Head, HttpMethod.Get }, methods);
        Assert.True(content.IsDisposed);
    }

    [Fact]
    public async Task Content_length_is_used_for_full_ok_response()
    {
        using var probe = new HttpFileSizeProbe(new ProbeHandler((request, _) =>
        {
            Assert.Equal(HttpMethod.Head, request.Method);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ProbeContent(null, 2_000_000) });
        }));
        Assert.Equal(2_000_000, await probe.ProbeAsync(new Uri("http://localhost/media")));
    }

    [Theory]
    [InlineData(HttpStatusCode.OK, null)]
    [InlineData(HttpStatusCode.RequestedRangeNotSatisfiable, "bytes */5000000")]
    [InlineData(HttpStatusCode.OK, "bytes 0-0/300000000000")]
    public async Task Missing_rejected_or_absurd_headers_return_none(HttpStatusCode status, string? range)
    {
        using var probe = new HttpFileSizeProbe(new ProbeHandler((_, _) => Task.FromResult(
            new HttpResponseMessage(status) { Content = new ProbeContent(range, null) })));
        Assert.Null(await probe.ProbeAsync(new Uri("http://localhost/media")));
    }

    [Fact]
    public async Task Timeout_and_cancellation_are_returned_as_no_size()
    {
        using var probe = new HttpFileSizeProbe(new ProbeHandler(async (_, token) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            return new HttpResponseMessage(HttpStatusCode.OK);
        }));
        Assert.Null(await probe.ProbeAsync(new Uri("http://localhost/media")));

        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        Assert.Null(await probe.ProbeAsync(new Uri("http://localhost/media"), cancellation.Token));
    }

    [Fact]
    public async Task Chunked_and_conflicting_range_headers_are_rejected()
    {
        var requests = 0;
        using var probe = new HttpFileSizeProbe(new ProbeHandler((request, _) =>
        {
            requests++;
            var response = new HttpResponseMessage(request.Method == HttpMethod.Head
                ? HttpStatusCode.OK : HttpStatusCode.PartialContent)
            {
                Content = request.Method == HttpMethod.Head
                    ? new ProbeContent(null, null)
                    : new ProbeContent("bytes 0-0/1500000", 2),
            };
            if (request.Method == HttpMethod.Head) response.Headers.TransferEncodingChunked = true;
            return Task.FromResult(response);
        }));
        Assert.Null(await probe.ProbeAsync(new Uri("http://localhost/media")));
        Assert.Equal(2, requests);
    }

    private sealed class ProbeHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            send(request, cancellationToken);
    }

    private sealed class ProbeContent : HttpContent
    {
        private readonly long? _contentLength;
        public bool IsDisposed { get; private set; }

        public ProbeContent(string? contentRange, long? contentLength)
        {
            if (contentRange is not null) Headers.TryAddWithoutValidation("Content-Range", contentRange);
            if (contentLength.HasValue) Headers.ContentLength = contentLength;
            _contentLength = contentLength;
        }

        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            throw new InvalidOperationException("Probe must not read the response body.");

        protected override bool TryComputeLength(out long length)
        {
            length = _contentLength ?? 0;
            return _contentLength.HasValue;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) IsDisposed = true;
            base.Dispose(disposing);
        }

    }
}
