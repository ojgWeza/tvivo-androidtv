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
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_currentSession is { } previous)
                await _engine.StopAsync(previous, CancellationToken.None).ConfigureAwait(false);

            var session = new PlaybackSessionToken(++_generation, Guid.NewGuid());
            _currentSession = session;
            var result = await _engine.StartAsync(source, session, cancellationToken).ConfigureAwait(false);
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
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var session = _currentSession ?? new PlaybackSessionToken(++_generation, Guid.NewGuid());
            _currentSession = null;
            await _engine.StopAsync(session, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }
}
