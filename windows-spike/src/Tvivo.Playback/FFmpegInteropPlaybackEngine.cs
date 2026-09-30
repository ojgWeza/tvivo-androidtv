using FFmpegInteropX;
using Tvivo.Core;
using Windows.Media.Playback;

namespace Tvivo.Playback;

public sealed class FFmpegInteropPlaybackEngine : IPlaybackEngine, IDisposable
{
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(30);
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly MediaPlayer _player = new();
    private FFmpegMediaSource? _source;
    private PlaybackSessionToken? _currentSession;
    private TaskCompletionSource<PlaybackAttemptResult>? _startup;
    private bool _disposed;
    private volatile bool _ended;
    public event Action<string>? LifecycleEvent;

    public MediaPlayer Player => _player;
    public bool IsPlaying => _player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;
    public bool IsBuffering => _player.PlaybackSession.PlaybackState == MediaPlaybackState.Buffering;
    public bool IsPaused => _player.PlaybackSession.PlaybackState == MediaPlaybackState.Paused;
    public bool IsEnded => _ended;
    public long Time => (long)_player.PlaybackSession.Position.TotalMilliseconds;
    public long Length => _player.PlaybackSession.NaturalDuration == TimeSpan.Zero
        ? 0
        : (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds;
    public PlaybackTimeline Timeline => new(Time, Length > 0 ? Length : null);
    public int Volume
    {
        get => (int)Math.Round(_player.Volume * 100);
        set => _player.Volume = Math.Clamp(value, 0, 100) / 100d;
    }

    public FFmpegInteropPlaybackEngine()
    {
        _player.MediaOpened += OnMediaOpened;
        _player.MediaFailed += OnMediaFailed;
        _player.MediaEnded += OnMediaEnded;
    }

    public void TogglePause()
    {
        if (IsPlaying || IsBuffering) _player.Pause();
        else if (IsPaused) _player.Play();
    }

    public void Seek(long timeMilliseconds)
    {
        if (Timeline.ClampSeekTarget(timeMilliseconds) is { } target)
            _player.PlaybackSession.Position = TimeSpan.FromMilliseconds(target);
    }

    public async Task<PlaybackAttemptResult> StartAsync(
        StreamSource source,
        PlaybackSessionToken session,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            StopCore();
            if (source.DirectUri is null)
                return PlaybackAttemptResult.HostFailure;

            _currentSession = session;
            _ended = false;
            _startup = new TaskCompletionSource<PlaybackAttemptResult>(TaskCreationOptions.RunContinuationsAsynchronously);
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            using var registration = linked.Token.Register(() => _startup.TrySetResult(PlaybackAttemptResult.Cancelled));

            try
            {
                _source = await FFmpegMediaSource.CreateFromUriAsync(source.DirectUri.AbsoluteUri);
                cancellationToken.ThrowIfCancellationRequested();
                _player.Source = _source.CreateMediaPlaybackItem();
                _player.Play();
                var timeout = Task.Delay(StartupDeadline, linked.Token);
                var completed = await Task.WhenAny(_startup.Task, timeout).ConfigureAwait(true);
                // A MediaOpened callback can win the race just after WhenAny
                // schedules this continuation. Prefer readiness already recorded
                // by that callback before treating the deadline as a failure.
                if (completed == timeout && !_startup.Task.IsCompleted && IsPlaying)
                {
                    _startup.TrySetResult(PlaybackAttemptResult.FirstFrame);
                    return PlaybackAttemptResult.FirstFrame;
                }

                if (completed == timeout && !_startup.Task.IsCompleted)
                {
                    StopCore(cancellationToken.IsCancellationRequested ? "user-cancelled" : "timeout");
                    return cancellationToken.IsCancellationRequested
                        ? PlaybackAttemptResult.Cancelled
                        : PlaybackAttemptResult.Timeout;
                }

                var result = await _startup.Task.ConfigureAwait(true);
                if (result != PlaybackAttemptResult.FirstFrame)
                    StopCore(result == PlaybackAttemptResult.Cancelled ? "user-cancelled" : "error");
                return result;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                StopCore("user-cancelled");
                return PlaybackAttemptResult.Cancelled;
            }
            catch (Exception)
            {
                StopCore("error");
                return PlaybackAttemptResult.DecodeFailure;
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_currentSession == session)
                StopCore();
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        StopCore();
        _player.MediaOpened -= OnMediaOpened;
        _player.MediaFailed -= OnMediaFailed;
        _player.MediaEnded -= OnMediaEnded;
        _player.Dispose();
        LifecycleEvent?.Invoke("event=playback.engine.dispose engine=Native callbacks=detached player=released");
        _disposed = true;
        _gate.Dispose();
    }

    private void OnMediaOpened(MediaPlayer sender, object args) =>
        _startup?.TrySetResult(PlaybackAttemptResult.FirstFrame);

    private void OnMediaFailed(MediaPlayer sender, MediaPlayerFailedEventArgs args) =>
        _startup?.TrySetResult(PlaybackAttemptResult.DecodeFailure);

    private void OnMediaEnded(MediaPlayer sender, object args) => _ended = true;

    private void StopCore(string? reason = null)
    {
        var hadSource = _source is not null || _player.Source is not null;
        reason ??= _ended ? "completed" : "user-cancelled";
        _startup?.TrySetResult(PlaybackAttemptResult.Cancelled);
        _startup = null;
        _currentSession = null;
        _ended = false;
        _player.Pause();
        _player.Source = null;
        _source?.Dispose();
        _source = null;
        if (hadSource)
            LifecycleEvent?.Invoke($"event=playback.engine.release engine=Native reason={reason} source=released playerSource=cleared callbacks=retained-until-dispose");
    }
}
