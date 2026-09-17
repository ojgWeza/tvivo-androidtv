namespace Tvivo.Core;

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
            if (_currentSession is { } session)
            {
                _currentSession = null;
                await _engine.StopAsync(session, cancellationToken).ConfigureAwait(false);
            }
        }
        finally
        {
            _gate.Release();
        }
    }
}
