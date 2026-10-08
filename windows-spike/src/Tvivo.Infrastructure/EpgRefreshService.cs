using Tvivo.Core;

namespace Tvivo.Infrastructure;

public enum EpgRefreshResult
{
    Disabled,
    Refreshed,
    NotDue,
    PlaybackBusy,
    Empty,
    Unavailable,
    Failed,
    Cancelled,
}

public sealed class EpgRefreshService
{
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromHours(6);
    private static readonly TimeSpan TotalTimeout = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(30);
    private readonly IEpgProvider _provider;
    private readonly EpgRepository _repository;
    private readonly Func<bool> _isPlaybackBusy;
    private readonly EpgFeature _feature;

    public EpgRefreshService(IEpgProvider provider, EpgRepository repository, Func<bool>? isPlaybackBusy = null, EpgFeature? feature = null)
    {
        _provider = provider;
        _repository = repository;
        _isPlaybackBusy = isPlaybackBusy ?? (() => false);
        _feature = feature ?? new EpgFeature();
    }

    public Task<EpgRefreshResult> StartAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken = default) =>
        RefreshIfDueAsync(account, connection, cancellationToken);

    public async Task<EpgRefreshResult> RefreshIfDueAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken = default)
    {
        if (!_feature.Enabled) return EpgRefreshResult.Disabled;

        try
        {
            if (_isPlaybackBusy()) return EpgRefreshResult.PlaybackBusy;
            var state = _repository.GetSyncState(account);
            if (state is not null && DateTimeOffset.UtcNow - state.FetchedAt < RefreshInterval)
                return EpgRefreshResult.NotDue;

            using var totalCts = new CancellationTokenSource(TotalTimeout);
            using var idleCts = new CancellationTokenSource(IdleTimeout);
            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, totalCts.Token, idleCts.Token);
            var maps = await _provider.GetEpgChannelMapAsync(account, linkedCts.Token).ConfigureAwait(false);
            await using var source = new IdleTimeoutStream(
                await _provider.OpenProgrammeStreamAsync(account, connection, linkedCts.Token).ConfigureAwait(false), idleCts);
            await _repository.ImportXmltvAsync(account, maps, source, DateTimeOffset.UtcNow, linkedCts.Token).ConfigureAwait(false);
            return EpgRefreshResult.Refreshed;
        }
        catch (OperationCanceledException)
        {
            return EpgRefreshResult.Cancelled;
        }
        catch (EpgProviderHttpException exception) when (exception.StatusCode is 404 or 405 or 501)
        {
            return EpgRefreshResult.Unavailable;
        }
        catch (EpgImportLimitExceededException)
        {
            return EpgRefreshResult.Failed;
        }
        catch (InvalidDataException)
        {
            return EpgRefreshResult.Empty;
        }
        catch
        {
            return EpgRefreshResult.Failed;
        }
    }

    private sealed class IdleTimeoutStream(Stream inner, CancellationTokenSource idleCts) : Stream
    {
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count)
        {
            var read = inner.Read(buffer, offset, count);
            Reset(read);
            return read;
        }
        public override int Read(Span<byte> buffer)
        {
            var read = inner.Read(buffer);
            Reset(read);
            return read;
        }
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsyncCore(buffer.AsMemory(offset, count), cancellationToken).AsTask();
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            ReadAsyncCore(buffer, cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        private async ValueTask<int> ReadAsyncCore(Memory<byte> buffer, CancellationToken cancellationToken)
        {
            var read = await inner.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            Reset(read);
            return read;
        }

        private void Reset(int read)
        {
            if (read > 0) idleCts.CancelAfter(IdleTimeout);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
    }
}
