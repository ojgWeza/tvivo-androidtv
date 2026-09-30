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
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(30);

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
    private Task _abandonedCleanupTask = Task.CompletedTask;
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
    public event Action<string>? LifecycleEvent;

    public async Task<PlaybackAttemptResult> StartAsync(
        StreamSource source,
        PlaybackSessionToken session,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            await _abandonedCleanupTask.WaitAsync(cancellationToken).ConfigureAwait(true);
            _abandonedCleanupTask = Task.CompletedTask;
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
                Log($"Timed out waiting for LibVLC readiness after {StartupDeadline.TotalSeconds:0.###} seconds. {exception.GetType().Name}");
                return PlaybackAttemptResult.Timeout;
            }
            catch (Exception exception)
            {
                Log($"LibVLC readiness failed before playback could start. {exception.GetType().Name}");
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
                // StartAsync is entered and resumed on the window dispatcher. LibVLC's
                // MediaPlayer is apartment-bound here, so invoke Play on that same
                // dispatcher instead of moving the call to a pool thread.
                using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                using var cancellationRegistration = linkedCancellation.Token.Register(
                    () => handlers.Completion.TrySetResult(PlaybackAttemptResult.Cancelled));
                var timeoutTask = Task.Delay(StartupDeadline, linkedCancellation.Token);
                var playTask = Task.FromResult(player.Play());
                var completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask, playTask)
                    .ConfigureAwait(true);

                if (completed == playTask && !await playTask.ConfigureAwait(true))
                {
                    PublishState(session, VlcPlaybackState.Failed);
                    handlers.Completion.TrySetResult(PlaybackAttemptResult.HostFailure);
                }

                if (completed == playTask && !handlers.Completion.Task.IsCompleted)
                    completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask).ConfigureAwait(true);

                if (completed == timeoutTask && !handlers.Completion.Task.IsCompleted && player.VoutCount > 0)
                    handlers.Completion.TrySetResult(PlaybackAttemptResult.FirstFrame);

                // The Vout/Playing callback may have completed as the deadline
                // continuation was queued. Its session result takes precedence.
                var result = handlers.Completion.Task.IsCompleted
                    ? await handlers.Completion.Task.ConfigureAwait(true)
                    : completed == timeoutTask
                    ? cancellationToken.IsCancellationRequested
                        ? PlaybackAttemptResult.Cancelled
                        : PlaybackAttemptResult.Timeout
                    : await handlers.Completion.Task.ConfigureAwait(true);

                if (result != PlaybackAttemptResult.FirstFrame)
                {
                    if (result == PlaybackAttemptResult.Timeout)
                        PublishState(session, VlcPlaybackState.Failed);
                    if (!playTask.IsCompleted)
                    {
                        // Do not let a second potentially blocking native call prevent
                        // timeout/cancellation recovery. Detach now, and gate the next
                        // open on native Play returning and its media being disposed.
                        AbandonTimedOutStart(session, player, media, handlers, playTask,
                            result == PlaybackAttemptResult.Cancelled ? "user-cancelled" : result == PlaybackAttemptResult.Timeout ? "timeout" : "error");
                    }
                    else
                    {
                        StopCore(session, result == PlaybackAttemptResult.Cancelled ? "user-cancelled" : result == PlaybackAttemptResult.Timeout ? "timeout" : "error");
                    }
                }
                return result;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                StopCore(session, "user-cancelled");
                return PlaybackAttemptResult.Cancelled;
            }
            catch (Exception exception)
            {
                Log($"Playback startup failed: {exception.GetType().Name} (HRESULT 0x{exception.HResult:X8}).");
                StopCore(session, "error");
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
            await _abandonedCleanupTask.WaitAsync(cancellationToken).ConfigureAwait(true);
            _abandonedCleanupTask = Task.CompletedTask;
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
            _abandonedCleanupTask.GetAwaiter().GetResult();
            lock (_lifecycleLock)
            {
                _libVlc?.Dispose();
                _libVlc = null;
                _disposed = true;
            }
            LifecycleEvent?.Invoke("event=playback.engine.dispose engine=LibVLC context=released");
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
            await _abandonedCleanupTask.ConfigureAwait(true);
            _abandonedCleanupTask = Task.CompletedTask;
            lock (_lifecycleLock)
            {
                _libVlc?.Dispose();
                _libVlc = null;
                _disposed = true;
            }
            LifecycleEvent?.Invoke("event=playback.engine.dispose engine=LibVLC context=released");
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
                Log($"LibVLC construction prevented readiness completion. {exception.GetType().Name}");
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
        Task<bool> playTask,
        string reason)
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

        _abandonedCleanupTask = playTask.ContinueWith(_ =>
        {
            try { player.Stop(); }
            catch (Exception exception) { Log($"Timed-out player stop failed during deferred cleanup. {exception.GetType().Name}"); }
            finally
            {
                try { player.Dispose(); }
                catch (Exception exception) { Log($"Timed-out player dispose failed during deferred cleanup. {exception.GetType().Name}"); }
                try { media.Dispose(); }
                catch (Exception exception) { Log($"Timed-out media dispose failed during deferred cleanup. {exception.GetType().Name}"); }
                LifecycleEvent?.Invoke($"event=playback.engine.release engine=LibVLC reason={reason} path=deferred callbacks=detached surface=detach-requested player=dispose-attempted media=dispose-attempted");
            }
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    private void DetachPlayerFromView()
    {
        var view = _view;
        if (view is null)
            return;

        if (view.DispatcherQueue?.HasThreadAccess == true)
        {
            view.MediaPlayer = null;
            LifecycleEvent?.Invoke("event=playback.engine.surface engine=LibVLC surface=detached");
        }
        else
            view.DispatcherQueue?.TryEnqueue(() =>
            {
                view.MediaPlayer = null;
                LifecycleEvent?.Invoke("event=playback.engine.surface engine=LibVLC surface=detached");
            });
    }

    private static readonly string LogPath = Path.Combine(
        Path.GetTempPath(),
        "tvivo-playback-engine.log");
    private static readonly object LogSync = new();

    private static void Log(string message)
    {
        var safeMessage = System.Text.RegularExpressions.Regex.Replace(
            message, @"(?i)\b(?:https?|rtsp)://[^\s\]\)\}""']+", "[redacted-url]");
        safeMessage = System.Text.RegularExpressions.Regex.Replace(
            safeMessage, @"(?i)\b(?:username|password|accountid|account_id)\s*[=:]\s*[^\s;,]+", "[redacted-field]");
        if (safeMessage.Length > 4096) safeMessage = safeMessage[..4096] + "[truncated]";
        var line = $"[{DateTimeOffset.Now:O}] [VlcPlaybackEngine] {safeMessage}{Environment.NewLine}";
        Console.WriteLine(line.TrimEnd());
        try
        {
            lock (LogSync)
            {
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length + System.Text.Encoding.UTF8.GetByteCount(line) > 1024 * 1024)
                    File.Move(LogPath, LogPath + ".1", overwrite: true);
                File.AppendAllText(LogPath, line);
            }
        }
        catch (Exception exception)
        {
            Console.WriteLine($"[{DateTimeOffset.Now:O}] [VlcPlaybackEngine] Unable to append diagnostic log: {exception.GetType().Name}");
        }
    }

    private void StopCore(PlaybackSessionToken? session, string? reason = null)
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
        reason ??= player.State == VLCState.Ended ? "completed" : "user-cancelled";

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
        LifecycleEvent?.Invoke($"event=playback.engine.release engine=LibVLC reason={reason} path=immediate callbacks=detached surface=detach-requested player=released media=released");
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
