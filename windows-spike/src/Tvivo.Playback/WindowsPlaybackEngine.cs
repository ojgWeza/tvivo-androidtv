using LibVLCSharp.Shared;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;

namespace Tvivo.Playback;

public enum VlcPlaybackState
{
    Preparing,
    Playing,
    Failed,
    Completed,
    Stopping,
}

public sealed class VlcPlaybackStateChangedEventArgs : EventArgs
{
    public VlcPlaybackStateChangedEventArgs(PlaybackSessionToken session, VlcPlaybackState state)
    {
        Session = session;
        State = state;
    }

    public PlaybackSessionToken Session { get; }
    public VlcPlaybackState State { get; }
}

public sealed class VlcPlaybackEngine : IPlaybackEngine, IDisposable, IAsyncDisposable
{
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(10);

    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _lifecycleLock = new();
    private readonly TaskCompletionSource<LibVLC> _libVlcReady =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private VideoView? _view;
    private LibVLC? _libVlc;
    private Media? _media;
    private MediaPlayer? _player;
    private PlaybackSessionToken? _currentSession;
    private EventHandlers? _handlers;
    private bool _disposed;

    public VideoView View => _view ?? throw new InvalidOperationException("The XAML VideoView has not initialized.");

    public bool HasPlayer => _player is not null;
    public bool IsPlaying => _player?.IsPlaying == true;
    public bool IsBuffering => _player?.State == VLCState.Buffering;
    public bool IsPaused => _player?.State == VLCState.Paused;
    public bool IsEnded => _player?.State == VLCState.Ended;
    public long Time => _player?.Time ?? 0;
    public long Length => _player?.Length ?? 0;
    public PlaybackTimeline Timeline => new(Time, Length > 0 ? Length : null);
    public int Volume
    {
        get => _player?.Volume ?? 100;
        set
        {
            if (_player is { } player)
                player.Volume = Math.Clamp(value, 0, 100);
        }
    }

    public void TogglePause()
    {
        if (IsPlaying || IsBuffering || IsPaused)
            _player?.Pause();
    }

    public void Seek(long timeMilliseconds)
    {
        if (_player is { } player && Timeline.ClampSeekTarget(timeMilliseconds) is { } target)
            player.Time = target;
    }

    public event EventHandler<VlcPlaybackStateChangedEventArgs>? StateChanged;

    public async Task<PlaybackAttemptResult> StartAsync(
        StreamSource source,
        PlaybackSessionToken session,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            StopCore(_currentSession);
            cancellationToken.ThrowIfCancellationRequested();

            LibVLC libVlc;
            try
            {
                libVlc = await _libVlcReady.Task
                    .WaitAsync(StartupDeadline, cancellationToken)
                    .ConfigureAwait(true);
            }
            catch (TimeoutException exception)
            {
                Log($"Timed out waiting for LibVLC readiness after {StartupDeadline.TotalSeconds:0.###} seconds. {exception}");
                return PlaybackAttemptResult.Timeout;
            }
            catch (Exception exception)
            {
                Log($"LibVLC readiness failed before playback could start. {exception}");
                return PlaybackAttemptResult.HostFailure;
            }

            if (source.DirectUri is null)
                return PlaybackAttemptResult.HostFailure;

            var media = new Media(libVlc, source.DirectUri, Array.Empty<string>());
            var player = new MediaPlayer(libVlc)
            {
                Media = media,
            };
            var handlers = new EventHandlers(
                player,
                state => PublishState(session, state));

            _media = media;
            _player = player;
            _handlers = handlers;
            _currentSession = session;
            View.MediaPlayer = player;

            player.Playing += handlers.Playing;
            player.Vout += handlers.Vout;
            player.EncounteredError += handlers.EncounteredError;
            player.EndReached += handlers.EndReached;
            PublishState(session, VlcPlaybackState.Preparing);

            try
            {
                // Start the watchdog before invoking native playback. Play can block
                // inside LibVLC, so it must never run on the UI thread.
                using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                using var cancellationRegistration = linkedCancellation.Token.Register(
                    () => handlers.Completion.TrySetResult(PlaybackAttemptResult.Cancelled));
                var timeoutTask = Task.Delay(StartupDeadline, linkedCancellation.Token);
                var playTask = Task.Run(() => player.Play());
                var completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask, playTask)
                    .ConfigureAwait(false);

                if (completed == playTask && !await playTask.ConfigureAwait(false))
                {
                    PublishState(session, VlcPlaybackState.Failed);
                    handlers.Completion.TrySetResult(PlaybackAttemptResult.HostFailure);
                }

                if (completed == playTask && !handlers.Completion.Task.IsCompleted)
                    completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask).ConfigureAwait(false);

                if (completed == timeoutTask && !handlers.Completion.Task.IsCompleted && player.VoutCount > 0)
                    handlers.Completion.TrySetResult(PlaybackAttemptResult.FirstFrame);

                // The Vout/Playing callback may have completed as the deadline
                // continuation was queued. Its session result takes precedence.
                var result = handlers.Completion.Task.IsCompleted
                    ? await handlers.Completion.Task.ConfigureAwait(false)
                    : completed == timeoutTask
                    ? cancellationToken.IsCancellationRequested
                        ? PlaybackAttemptResult.Cancelled
                        : PlaybackAttemptResult.Timeout
                    : await handlers.Completion.Task.ConfigureAwait(false);

                if (result != PlaybackAttemptResult.FirstFrame)
                {
                    if (result == PlaybackAttemptResult.Timeout)
                        PublishState(session, VlcPlaybackState.Failed);
                    if (result == PlaybackAttemptResult.Timeout && !playTask.IsCompleted)
                    {
                        // Do not let a second potentially blocking native call prevent
                        // timeout recovery. Detach XAML now; finish native cleanup if Play returns.
                        AbandonTimedOutStart(session, player, media, handlers, playTask);
                    }
                    else
                    {
                        StopCore(session);
                    }
                }
                return result;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                StopCore(session);
                return PlaybackAttemptResult.Cancelled;
            }
            catch
            {
                StopCore(session);
                return PlaybackAttemptResult.HostFailure;
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
            cancellationToken.ThrowIfCancellationRequested();
            StopCore(session);
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        if (_disposed)
            return;

        // Synchronous disposal is retained for non-UI callers and must never wait
        // behind an async StartAsync/StopAsync continuation.
        if (!_gate.Wait(0))
            throw new InvalidOperationException("DisposeAsync must be used while playback is in flight.");

        try
        {
            if (_disposed)
                return;

            StopCore(_currentSession);
            lock (_lifecycleLock)
            {
                _libVlc?.Dispose();
                _libVlc = null;
                _disposed = true;
            }
        }
        finally
        {
            _gate.Release();
            _gate.Dispose();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(true);
        try
        {
            if (_disposed)
                return;

            StopCore(_currentSession);
            lock (_lifecycleLock)
            {
                _libVlc?.Dispose();
                _libVlc = null;
                _disposed = true;
            }
        }
        finally
        {
            _gate.Release();
            _gate.Dispose();
        }
    }

    public void InitializeView(VideoView view, InitializedEventArgs e)
    {
        Log("Entered OnViewInitialized.");
        string[] swapChainOptions;
        lock (_lifecycleLock)
        {
            if (_disposed)
                return;

            _view = view;
            swapChainOptions = e.SwapChainOptions;
        }

        Log($"Read VideoView swap-chain options: length={swapChainOptions.Length}, values=[{string.Join(", ", swapChainOptions)}].");
        Log("Constructing LibVLC on a worker thread.");
        _ = Task.Run(() => new LibVLC(swapChainOptions)).ContinueWith(task =>
        {
            if (task.IsFaulted)
            {
                var exception = task.Exception?.GetBaseException() ?? new InvalidOperationException("LibVLC construction failed.");
                Log($"LibVLC construction prevented readiness completion. {exception}");
                _libVlcReady.TrySetException(exception);
                return;
            }

            var libVlc = task.Result;
            lock (_lifecycleLock)
            {
                if (_disposed)
                {
                    libVlc.Dispose();
                    _libVlcReady.TrySetException(new ObjectDisposedException(nameof(VlcPlaybackEngine)));
                    return;
                }

                _libVlc = libVlc;
            }

            Log("LibVLC construction succeeded.");
            _libVlcReady.TrySetResult(libVlc);
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    private void AbandonTimedOutStart(
        PlaybackSessionToken session,
        MediaPlayer player,
        Media media,
        EventHandlers handlers,
        Task<bool> playTask)
    {
        if (_currentSession != session)
            return;

        _player = null;
        _media = null;
        _handlers = null;
        _currentSession = null;
        handlers.Invalidate();
        player.Playing -= handlers.Playing;
        player.Vout -= handlers.Vout;
        player.EncounteredError -= handlers.EncounteredError;
        player.EndReached -= handlers.EndReached;
        DetachPlayerFromView();

        _ = playTask.ContinueWith(_ =>
        {
            player.Stop();
            player.Dispose();
            media.Dispose();
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    private void DetachPlayerFromView()
    {
        var view = _view;
        if (view is null)
            return;

        if (view.DispatcherQueue?.HasThreadAccess == true)
            view.MediaPlayer = null;
        else
            view.DispatcherQueue?.TryEnqueue(() => view.MediaPlayer = null);
    }

    private static readonly string LogPath = Path.Combine(
        Path.GetTempPath(),
        "tvivo-playback-engine.log");

    private static void Log(string message)
    {
        var line = $"[{DateTimeOffset.Now:O}] [VlcPlaybackEngine] {message}{Environment.NewLine}";
        Console.WriteLine(line.TrimEnd());
        try
        {
            File.AppendAllText(LogPath, line);
        }
        catch (Exception exception)
        {
            Console.WriteLine($"[{DateTimeOffset.Now:O}] [VlcPlaybackEngine] Unable to append diagnostic log: {exception}");
        }
    }

    private void StopCore(PlaybackSessionToken? session)
    {
        if (session is not null && _currentSession != session)
            return;

        var player = _player;
        var media = _media;
        var handlers = _handlers;
        var currentSession = _currentSession;
        _player = null;
        _media = null;
        _handlers = null;
        _currentSession = null;

        if (player is null)
            return;

        if (currentSession is { } token)
            PublishState(token, VlcPlaybackState.Stopping);

        if (handlers is not null)
        {
            handlers.Invalidate();
            player.Playing -= handlers.Playing;
            player.Vout -= handlers.Vout;
            player.EncounteredError -= handlers.EncounteredError;
            player.EndReached -= handlers.EndReached;
        }

        DetachPlayerFromView();
        player.Stop();
        player.Dispose();
        media?.Dispose();
    }

    private void PublishState(PlaybackSessionToken session, VlcPlaybackState state)
    {
        _view?.DispatcherQueue?.TryEnqueue(() =>
        {
            if (_currentSession == session || state is VlcPlaybackState.Stopping or VlcPlaybackState.Completed or VlcPlaybackState.Failed)
                StateChanged?.Invoke(this, new VlcPlaybackStateChangedEventArgs(session, state));
        });
    }

    private sealed class EventHandlers
    {
        private readonly Action<VlcPlaybackState> _publishState;
        private int _invalidated;
        private int _firstFrameReported;

        public EventHandlers(
            MediaPlayer player,
            Action<VlcPlaybackState> publishState)
        {
            _publishState = publishState;
            Playing = (_, _) =>
            {
                if (IsCurrent)
                    _publishState(VlcPlaybackState.Playing);
            };
            Vout = (_, _) =>
            {
                if (IsCurrent && player.VoutCount > 0 && Interlocked.Exchange(ref _firstFrameReported, 1) == 0)
                    Completion.TrySetResult(PlaybackAttemptResult.FirstFrame);
            };
            EncounteredError = (_, _) =>
            {
                if (IsCurrent)
                {
                    _publishState(VlcPlaybackState.Failed);
                    Completion.TrySetResult(PlaybackAttemptResult.UnknownFailure);
                }
            };
            EndReached = (_, _) =>
            {
                if (IsCurrent)
                {
                    _publishState(VlcPlaybackState.Completed);
                    Completion.TrySetResult(PlaybackAttemptResult.UnknownFailure);
                }
            };
        }

        public TaskCompletionSource<PlaybackAttemptResult> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public EventHandler<EventArgs> Playing { get; }
        public EventHandler<MediaPlayerVoutEventArgs> Vout { get; }
        public EventHandler<EventArgs> EncounteredError { get; }
        public EventHandler<EventArgs> EndReached { get; }

        private bool IsCurrent => Volatile.Read(ref _invalidated) == 0;

        public void Invalidate() => Interlocked.Exchange(ref _invalidated, 1);
    }
}
