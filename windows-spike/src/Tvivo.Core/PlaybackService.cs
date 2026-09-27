namespace Tvivo.Core;

public sealed class PlaybackHandoff(TimeSpan closeDelay)
{
    private readonly SemaphoreSlim _gate = new(1, 1);

    public async Task<T> RunAsync<T>(Func<CancellationToken, Task> stopAll,
        Func<CancellationToken, Task<T>> startNext, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await stopAll(CancellationToken.None).ConfigureAwait(false);
            if (closeDelay > TimeSpan.Zero)
                await Task.Delay(closeDelay, cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            return await startNext(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }
}

public sealed class PlaybackService
{
    private readonly IPlaybackEngine _engine;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private long _generation;
    private PlaybackSessionToken? _currentSession;

    public PlaybackService(IPlaybackEngine engine) => _engine = engine;

    public async Task<PlaybackAttemptResult> PlayAsync(StreamSource source, CancellationToken cancellationToken = default)
    {
        // Playback engines own apartment-bound WinUI/WinRT objects. Keep the
        // caller's dispatcher context across the service gate and engine call.
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            if (_currentSession is { } previous)
                await _engine.StopAsync(previous, CancellationToken.None).ConfigureAwait(true);

            var session = new PlaybackSessionToken(++_generation, Guid.NewGuid());
            _currentSession = session;
            var result = await _engine.StartAsync(source, session, cancellationToken).ConfigureAwait(true);
            if (_currentSession == session && result != PlaybackAttemptResult.FirstFrame)
                _currentSession = null;
            return result;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            var session = _currentSession ?? new PlaybackSessionToken(++_generation, Guid.NewGuid());
            _currentSession = null;
            await _engine.StopAsync(session, cancellationToken).ConfigureAwait(true);
        }
        finally
        {
            _gate.Release();
        }
    }
}
