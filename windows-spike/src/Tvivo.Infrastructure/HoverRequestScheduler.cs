namespace Tvivo.Infrastructure;

/// <summary>Owns one cancellable, generation-checked delayed hover action.</summary>
public sealed class HoverRequestScheduler : IDisposable
{
    private readonly object _sync = new();
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private CancellationTokenSource? _currentCancellation;
    private long _generation;
    private string? _key;
    private bool _disposed;

    public HoverRequestScheduler(Func<TimeSpan, CancellationToken, Task>? delay = null) =>
        _delay = delay ?? Task.Delay;

    public Lease Begin(string key)
    {
        lock (_sync)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            CancelCurrent();
            _key = key;
            _currentCancellation = new CancellationTokenSource();
            return new Lease(++_generation, key, _currentCancellation.Token);
        }
    }

    public bool IsCurrent(Lease lease)
    {
        lock (_sync)
            return !_disposed && lease.Generation == _generation &&
                   string.Equals(lease.Key, _key, StringComparison.Ordinal) && !lease.CancellationToken.IsCancellationRequested;
    }

    public async Task RunAfterDelayAsync(Lease lease, TimeSpan delay, Func<CancellationToken, Task> action)
    {
        try
        {
            await _delay(delay, lease.CancellationToken).ConfigureAwait(false);
            if (IsCurrent(lease)) await action(lease.CancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (lease.CancellationToken.IsCancellationRequested)
        {
        }
    }

    public void Cancel(Lease lease)
    {
        lock (_sync)
        {
            if (lease.Generation == _generation && string.Equals(lease.Key, _key, StringComparison.Ordinal))
                CancelCurrent();
        }
    }

    public void Cancel()
    {
        lock (_sync) CancelCurrent();
    }

    public void Dispose()
    {
        lock (_sync)
        {
            if (_disposed) return;
            _disposed = true;
            CancelCurrent();
        }
    }

    private void CancelCurrent()
    {
        ++_generation;
        _key = null;
        var cancellation = _currentCancellation;
        _currentCancellation = null;
        if (cancellation is null) return;
        cancellation.Cancel();
        cancellation.Dispose();
    }

    public readonly record struct Lease(long Generation, string Key, CancellationToken CancellationToken);
}
