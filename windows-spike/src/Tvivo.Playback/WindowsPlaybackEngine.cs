using LibVLCSharp.Shared;using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using System.Globalization;
using System.Runtime.CompilerServices;

[assembly: InternalsVisibleTo("Tvivo.Playback.Tests")]

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

public sealed class VlcPlaybackEngine : IPlaybackEngine, ITrackSelectingEngine, IDisposable, IAsyncDisposable
{
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan TrackProbeDeadline = TimeSpan.FromSeconds(10);

    private readonly bool _enableLiveTimeshift;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _lifecycleLock = new();
    private readonly object _trackSync = new();
    private readonly TaskCompletionSource<LibVLC> _libVlcReady =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private VideoView? _view;
    private LibVLC? _libVlc;
    private Media? _media;
    private MediaPlayer? _player;
    private PlaybackSessionToken? _currentSession;
    private EventHandlers? _handlers;
    private CancellationTokenSource? _trackProbeCancellation;
    private PlaybackTrackSnapshot _tracks = PlaybackTrackSnapshot.Unresolved;
    private bool _defaultSubtitleApplied;
    private Task _abandonedCleanupTask = Task.CompletedTask;
    private int _volumePercent = 100;
    private bool _isMuted;
    private bool _disposed;
    private bool _liveTimeshiftConfigured;

    public VlcPlaybackEngine(bool enableLiveTimeshift = false)
    {
        _enableLiveTimeshift = enableLiveTimeshift;
    }

    public VideoView View => _view ?? throw new InvalidOperationException("The XAML VideoView has not initialized.");

    public bool HasPlayer => _player is not null;
    public bool IsPlaying => _player?.IsPlaying == true;
    public bool IsBuffering => _player?.State == VLCState.Buffering;
    public bool IsPaused => _player?.State == VLCState.Paused;
    public bool IsEnded => _player?.State == VLCState.Ended;
    public bool IsStopped => _player?.State == VLCState.Stopped;
    public long Time => _player?.Time ?? 0;
    public long Length => _player?.Length ?? 0;
    public PlaybackTimeline Timeline => new(Time, Length > 0 ? Length : null);
    public int Volume
    {
        get => _player is { } player
            ? VlcVolumeCurve.ToSliderPercent(player.Volume)
            : _volumePercent;
        set
        {
            _volumePercent = Math.Clamp(value, 0, 100);
            if (_player is { } player)
                player.Volume = VlcVolumeCurve.ToPlayerVolume(_volumePercent);
        }
    }
    public bool IsMuted
    {
        get => _player?.Mute ?? _isMuted;
        set
        {
            _isMuted = value;
            if (_player is { } player && player.Mute != value)
                player.Mute = value;
        }
    }

    public void TogglePause()
    {
        if (_player is not { } player || !(IsPlaying || IsBuffering || IsPaused))
            return;

        var phase = IsPaused ? "resume" : "pause";
        player.Pause();
        if (_liveTimeshiftConfigured)
            _ = SampleAfterTransitionAsync(player, phase);
    }

    public void Seek(long timeMilliseconds)
    {
        if (_player is { } player && Timeline.ClampSeekTarget(timeMilliseconds) is { } target)
            player.Time = target;
    }

    // Diagnostic-only escape hatch. A live input has no finite Timeline, so Seek remains gated.
    // This submits one backward seek while paused; true means submitted, not that VLC honored it.
    public Task<bool> ProbePausedLiveSeekAsync(long timeMilliseconds) => Task.Run(async () =>
    {
        MediaPlayer? player;
        lock (_trackSync)
            player = _liveTimeshiftConfigured ? _player : null;
        if (player is null)
            return false;

        try
        {
            if (!IsCurrentPlayer(player) || player.State != VLCState.Paused ||
                timeMilliseconds < 0 || timeMilliseconds >= player.Time)
                return false;

            player.Time = timeMilliseconds;
            await Task.Delay(250).ConfigureAwait(false);
            if (IsCurrentPlayer(player))
                EmitPlaybackSample("seek_back_paused", player);
            return true;
        }
        catch (Exception exception)
        {
            Log($"event=playback.timeshift.probe_seek engine=LibVLC result=error error={exception.GetType().Name}");
            return false;
        }
    });

    public event EventHandler<VlcPlaybackStateChangedEventArgs>? StateChanged;
    public event EventHandler? TracksChanged;
    public event Action<string>? LifecycleEvent;

    public PlaybackTrackSnapshot GetTracks()
    {
        lock (_trackSync)
            return _tracks;
    }

    public bool SelectAudio(string key) => TryQueueTrackSelection(TrackKind.Audio, key, markDefaultApplied: false);

    public bool SelectSubtitle(string? keyOrNullForOff) =>
        TryQueueTrackSelection(TrackKind.Subtitle, keyOrNullForOff, markDefaultApplied: true);

    public void ApplyDefaultSubtitle(
        string? preferredLanguage,
        bool explicitOff,
        string? uiCultureTwoLetterCode)
    {
        PlaybackTrackSnapshot snapshot;
        lock (_trackSync)
        {
            if (_defaultSubtitleApplied)
                return;
            snapshot = _tracks;
        }

        if (snapshot.Subtitles.Count == 0)
            return;

        var key = TrackSelectionPolicy.PickSubtitle(
            snapshot.Subtitles,
            preferredLanguage,
            explicitOff,
            uiCultureTwoLetterCode);
        if (TryQueueTrackSelection(TrackKind.Subtitle, key, markDefaultApplied: false))
        {
            lock (_trackSync)
                _defaultSubtitleApplied = true;
            Log($"event=playback.tracks.default engine=LibVLC key={key ?? "off"}");
        }
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

            var timeshiftPath = PrepareLiveTimeshift(source.Kind);
            var media = new Media(libVlc, source.DirectUri, Array.Empty<string>());
            var timeshiftOptions = LiveTimeshiftOptions.ForMedia(source.Kind, _enableLiveTimeshift && timeshiftPath is not null, timeshiftPath);
            foreach (var option in timeshiftOptions)
                media.AddOption(option);
            if (timeshiftOptions.Count > 0)
            {
                var message = $"event=playback.timeshift.config engine=LibVLC scope=media granularity_bytes={LiveTimeshiftOptions.GranularityBytes}";
                EmitTimeshiftDiagnostic(message);
            }
            var player = new MediaPlayer(libVlc)
            {
                Media = media,
                // LibVLC's own click-to-pause would toggle on each click of a double-click;
                // the window handles video clicks itself (single = pause, double = cinema).
                EnableMouseInput = false,
            };
            var handlers = new EventHandlers(
                player,
                state => PublishState(session, state),
                resolved => RefreshVlcTracks(session, player, resolved),
                () =>
                {
                    // Reapply once playback is active so the mmdevice backend
                    // writes this volume over a persisted Windows session scalar.
                    // Never call libvlc setters on the event thread: a concurrent UI-thread call
                    // (e.g. a stall restart setting Mute) deadlocks against the player lock held
                    // while the Playing callback is delivered.
                    ThreadPool.QueueUserWorkItem(_ =>
                    {
                        if (!ReferenceEquals(_player, player)) return;
                        player.Volume = VlcVolumeCurve.ToPlayerVolume(_volumePercent);
                        player.Mute = _isMuted;
                        if (_liveTimeshiftConfigured)
                            EmitPlaybackSample("playing", player);
                    });
                    RefreshVlcTracks(session, player, resolvedWhenTracksPresent: false);
                    StartTrackProbe(session, player);
                });

            lock (_trackSync)
            {
                _media = media;
                _player = player;
                _handlers = handlers;
                _currentSession = session;
                _tracks = PlaybackTrackSnapshot.Unresolved;
                _defaultSubtitleApplied = false;
                _liveTimeshiftConfigured = timeshiftPath is not null;
            }
            View.MediaPlayer = player;

            player.Playing += handlers.Playing;
            player.Vout += handlers.Vout;
            player.EncounteredError += handlers.EncounteredError;
            player.EndReached += handlers.EndReached;
            player.ESAdded += handlers.ESAdded;
            player.ESDeleted += handlers.ESDeleted;
            player.ESSelected += handlers.ESSelected;
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
        lock (_trackSync)
        {
            if (_currentSession != session)
                return;
        }

        lock (_trackSync)
        {
            _player = null;
            _media = null;
            _handlers = null;
            _currentSession = null;
        }
        CancelTrackProbe();
        lock (_trackSync)
        {
            _tracks = PlaybackTrackSnapshot.Unresolved;
            _defaultSubtitleApplied = false;
        }
        handlers.Invalidate();
        DetachPlayerHandlers(player, handlers);
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
                CleanupTimeshiftFiles();
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

    private string? PrepareLiveTimeshift(StreamKind kind)
    {
        if (!_enableLiveTimeshift || kind != StreamKind.Live)
            return null;

        try
        {
            var path = LiveTimeshiftOptions.DirectoryPath(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));
            LiveTimeshiftOptions.ThrowIfReparseDirectory(Path.GetDirectoryName(path)!);
            Directory.CreateDirectory(path);
            LiveTimeshiftOptions.ThrowIfReparseDirectory(path);
            CleanupTimeshiftFiles();
            return path;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        {
            Log($"event=playback.timeshift.setup engine=LibVLC result=error error={exception.GetType().Name}");
            return null;
        }
    }

    private void CleanupTimeshiftFiles()
    {
        if (!_enableLiveTimeshift)
            return;

        try
        {
            var path = LiveTimeshiftOptions.DirectoryPath(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));
            var removed = LiveTimeshiftOptions.CleanupStaleFiles(path);
            if (removed > 0)
                Log($"event=playback.timeshift.cleanup engine=LibVLC removed={removed}");
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        {
            Log($"event=playback.timeshift.cleanup engine=LibVLC result=error error={exception.GetType().Name}");
        }
    }

    private async Task SampleAfterTransitionAsync(MediaPlayer player, string phase)
    {
        await Task.Delay(250).ConfigureAwait(false);
        if (IsCurrentPlayer(player))
            EmitPlaybackSample(phase, player);
    }

    private bool IsCurrentPlayer(MediaPlayer player)
    {
        lock (_trackSync)
            return ReferenceEquals(_player, player) && !_disposed;
    }

    private void EmitPlaybackSample(string phase, MediaPlayer player)
    {
        try
        {
            var message = $"event=playback.timeshift.sample engine=LibVLC phase={phase} " +
                $"time_ms={player.Time} length_ms={player.Length} " +
                $"position={player.Position.ToString("R", CultureInfo.InvariantCulture)} " +
                $"is_seekable={player.IsSeekable} can_pause={player.CanPause} state={player.State}";
            EmitTimeshiftDiagnostic(message);
        }
        catch (ObjectDisposedException)
        {
            // A queued sample may race normal StopCore disposal.
        }
    }

    private void EmitTimeshiftDiagnostic(string message)
    {
        Log(message);
        try { LifecycleEvent?.Invoke(message); }
        catch (Exception exception)
        {
            Log($"event=playback.timeshift.diagnostic engine=LibVLC result=listener-error error={exception.GetType().Name}");
        }
    }

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
        if (session is not null)
        {
            lock (_trackSync)
            {
                if (_currentSession != session)
                    return;
            }
        }

        MediaPlayer? player;
        Media? media;
        EventHandlers? handlers;
        PlaybackSessionToken? currentSession;
        bool hadLiveTimeshift;
        lock (_trackSync)
        {
            player = _player;
            media = _media;
            handlers = _handlers;
            currentSession = _currentSession;
            hadLiveTimeshift = _liveTimeshiftConfigured;
            _player = null;
            _media = null;
            _handlers = null;
            _currentSession = null;
            _liveTimeshiftConfigured = false;
        }
        CancelTrackProbe();
        lock (_trackSync)
        {
            _tracks = PlaybackTrackSnapshot.Unresolved;
            _defaultSubtitleApplied = false;
        }

        if (player is null)
        {
            CleanupTimeshiftFiles();
            return;
        }
        reason ??= player.State == VLCState.Ended ? "completed" : "user-cancelled";

        if (currentSession is { } token)
            PublishState(token, VlcPlaybackState.Stopping);

        if (handlers is not null)
        {
            handlers.Invalidate();
            DetachPlayerHandlers(player, handlers);
        }

        DetachPlayerFromView();
        if (hadLiveTimeshift)
            EmitPlaybackSample("stop_before", player);
        try
        {
            player.Stop();
            if (hadLiveTimeshift)
                EmitPlaybackSample("stop", player);
        }
        finally
        {
            try { player.Dispose(); }
            finally
            {
                try { media?.Dispose(); }
                finally { CleanupTimeshiftFiles(); }
            }
        }
        LifecycleEvent?.Invoke($"event=playback.engine.release engine=LibVLC reason={reason} path=immediate callbacks=detached surface=detach-requested player=released media=released");
    }

    private static void DetachPlayerHandlers(MediaPlayer player, EventHandlers handlers)
    {
        player.Playing -= handlers.Playing;
        player.Vout -= handlers.Vout;
        player.EncounteredError -= handlers.EncounteredError;
        player.EndReached -= handlers.EndReached;
        player.ESAdded -= handlers.ESAdded;
        player.ESDeleted -= handlers.ESDeleted;
        player.ESSelected -= handlers.ESSelected;
    }

    private void StartTrackProbe(PlaybackSessionToken session, MediaPlayer player)
    {
        var cancellation = new CancellationTokenSource();
        CancelTrackProbe();
        _trackProbeCancellation = cancellation;
        _ = ProbeTracksAsync(session, player, cancellation);
    }

    private async Task ProbeTracksAsync(
        PlaybackSessionToken session,
        MediaPlayer player,
        CancellationTokenSource cancellation)
    {
        var deadline = DateTimeOffset.UtcNow + TrackProbeDeadline;
        try
        {
            while (IsCurrentPlayer(session, player))
            {
                var atDeadline = DateTimeOffset.UtcNow >= deadline;
                RefreshVlcTracks(session, player, atDeadline);
                if (atDeadline)
                    return;

                await Task.Delay(TimeSpan.FromMilliseconds(250), cancellation.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            Log($"Track probe failed. {exception.GetType().Name}");
        }
        finally
        {
            // A probe that ran to its deadline must not leave a disposed source behind for the
            // next StopCore/StartTrackProbe to Cancel (that threw ObjectDisposedException and
            // blocked every later start).
            Interlocked.CompareExchange(ref _trackProbeCancellation, null, cancellation);
            cancellation.Dispose();
        }
    }

    private void CancelTrackProbe()
    {
        var probe = Interlocked.Exchange(ref _trackProbeCancellation, null);
        try
        {
            probe?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private void RefreshVlcTracks(
        PlaybackSessionToken session,
        MediaPlayer player,
        bool resolvedWhenTracksPresent)
    {
        if (!IsCurrentPlayer(session, player))
            return;

        PlaybackTrackSnapshot snapshot;
        try
        {
            Media? media;
            lock (_trackSync)
                media = _media;
            var mediaTracks = media?.Tracks ?? Array.Empty<MediaTrack>();
            var audioLanguages = mediaTracks
                .Where(track => track.TrackType == TrackType.Audio)
                .ToDictionary(track => track.Id, track => track.Language);
            var subtitleLanguages = mediaTracks
                .Where(track => track.TrackType == TrackType.Text)
                .ToDictionary(track => track.Id, track => track.Language);

            var audio = (player.AudioTrackDescription ?? [])
                .Where(description => description.Id != -1)
                .Select((description, index) => new PlaybackTrack(
                    TrackKind.Audio,
                    description.Id.ToString(CultureInfo.InvariantCulture),
                    TrackSelectionPolicy.GetDisplayName(
                        TrackKind.Audio,
                        description.Name,
                        audioLanguages.GetValueOrDefault(description.Id),
                        index + 1),
                    audioLanguages.GetValueOrDefault(description.Id),
                    description.Id == player.AudioTrack))
                .ToArray();
            var subtitles = (player.SpuDescription ?? [])
                .Where(description => description.Id != -1)
                .Select((description, index) => new PlaybackTrack(
                    TrackKind.Subtitle,
                    description.Id.ToString(CultureInfo.InvariantCulture),
                    TrackSelectionPolicy.GetDisplayName(
                        TrackKind.Subtitle,
                        description.Name,
                        subtitleLanguages.GetValueOrDefault(description.Id),
                        index + 1),
                    subtitleLanguages.GetValueOrDefault(description.Id),
                    description.Id == player.Spu))
                .ToArray();

            snapshot = new PlaybackTrackSnapshot(
                audio,
                subtitles,
                resolvedWhenTracksPresent || audio.Length > 0 || subtitles.Length > 0);
        }
        catch (Exception exception)
        {
            Log($"Track enumeration failed. {exception.GetType().Name}");
            return;
        }

        PublishTracks(snapshot, "probe", session, player);
    }

    private void PublishTracks(
        PlaybackTrackSnapshot snapshot,
        string source,
        PlaybackSessionToken session,
        MediaPlayer player)
    {
        bool changed;
        lock (_trackSync)
        {
            if (_currentSession != session || !ReferenceEquals(_player, player))
                return;
            changed = !TrackSnapshotsEqual(_tracks, snapshot);
            if (changed)
                _tracks = snapshot;
        }

        if (!changed)
            return;

        var audioLanguages = string.Join(",", snapshot.Audio.Select(track => track.LanguageCode ?? "unknown"));
        var subtitleLanguages = string.Join(",", snapshot.Subtitles.Select(track => track.LanguageCode ?? "unknown"));
        var selectedAudio = snapshot.Audio.FirstOrDefault(track => track.IsSelected)?.Key ?? "none";
        var selectedSubtitle = snapshot.Subtitles.FirstOrDefault(track => track.IsSelected)?.Key ?? "off";
        var message = $"event=playback.tracks engine=LibVLC source={source} resolved={snapshot.IsResolved} " +
                      $"audioCount={snapshot.Audio.Count} subtitleCount={snapshot.Subtitles.Count} " +
                      $"audioLanguages=[{audioLanguages}] subtitleLanguages=[{subtitleLanguages}] " +
                      $"selectedAudio={selectedAudio} selectedSubtitle={selectedSubtitle}";
        Log(message);
        LifecycleEvent?.Invoke(message);
        TracksChanged?.Invoke(this, EventArgs.Empty);
    }

    private bool TryQueueTrackSelection(TrackKind kind, string? key, bool markDefaultApplied)
    {
        MediaPlayer? player;
        PlaybackSessionToken session;
        lock (_trackSync)
        {
            player = _player;
            if (player is null || _currentSession is not { } currentSession)
                return false;
            session = currentSession;

            if (kind == TrackKind.Audio &&
                !int.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out _))
                return false;
            if (kind == TrackKind.Subtitle && key is not null &&
                !int.TryParse(key, NumberStyles.Integer, CultureInfo.InvariantCulture, out _))
                return false;
            if (markDefaultApplied && kind == TrackKind.Subtitle)
                _defaultSubtitleApplied = true;
        }

        void ApplySelection()
        {
            if (!IsCurrentPlayer(session, player))
                return;

            try
            {
                var selected = kind == TrackKind.Audio
                    ? player.SetAudioTrack(int.Parse(key!, CultureInfo.InvariantCulture))
                    : player.SetSpu(key is null ? -1 : int.Parse(key, CultureInfo.InvariantCulture));
                Log($"event=playback.tracks.select engine=LibVLC kind={kind} key={key ?? "off"} success={selected}");
                RefreshVlcTracks(session, player, resolvedWhenTracksPresent: true);
            }
            catch (Exception exception)
            {
                Log($"Track selection failed. engine=LibVLC kind={kind} {exception.GetType().Name}");
            }
        }

        try
        {
            if (_view?.DispatcherQueue?.TryEnqueue(ApplySelection) == true)
                return true;
        }
        catch (Exception exception)
        {
            Log($"Track selection dispatcher enqueue failed. {exception.GetType().Name}");
        }

        _ = Task.Run(ApplySelection);
        return true;
    }

    private bool IsCurrentPlayer(PlaybackSessionToken session, MediaPlayer player)
    {
        lock (_trackSync)
            return _currentSession == session && ReferenceEquals(_player, player) && !_disposed;
    }

    private static bool TrackSnapshotsEqual(PlaybackTrackSnapshot left, PlaybackTrackSnapshot right) =>
        left.IsResolved == right.IsResolved &&
        left.Audio.SequenceEqual(right.Audio) &&
        left.Subtitles.SequenceEqual(right.Subtitles);

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
        private readonly Action<bool> _refreshTracks;
        private readonly Action _onPlaying;
        private int _invalidated;
        private int _firstFrameReported;

        public EventHandlers(
            MediaPlayer player,
            Action<VlcPlaybackState> publishState,
            Action<bool> refreshTracks,
            Action onPlaying)
        {
            _publishState = publishState;
            _refreshTracks = refreshTracks;
            _onPlaying = onPlaying;
            Playing = (_, _) =>
            {
                if (IsCurrent)
                {
                    _publishState(VlcPlaybackState.Playing);
                    _onPlaying();
                }
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
            ESAdded = (_, _) =>
            {
                if (IsCurrent)
                    _refreshTracks(true);
            };
            ESDeleted = (_, _) =>
            {
                if (IsCurrent)
                    _refreshTracks(true);
            };
            ESSelected = (_, _) =>
            {
                if (IsCurrent)
                    _refreshTracks(true);
            };
        }

        public TaskCompletionSource<PlaybackAttemptResult> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public EventHandler<EventArgs> Playing { get; }
        public EventHandler<MediaPlayerVoutEventArgs> Vout { get; }
        public EventHandler<EventArgs> EncounteredError { get; }
        public EventHandler<EventArgs> EndReached { get; }
        public EventHandler<MediaPlayerESAddedEventArgs> ESAdded { get; }
        public EventHandler<MediaPlayerESDeletedEventArgs> ESDeleted { get; }
        public EventHandler<MediaPlayerESSelectedEventArgs> ESSelected { get; }

        private bool IsCurrent => Volatile.Read(ref _invalidated) == 0;

        public void Invalidate() => Interlocked.Exchange(ref _invalidated, 1);
    }
}

internal static class LiveTimeshiftOptions
{
    // VLC 3 treats granularity as a per-file limit, not a cap on the whole paused backlog.
    internal const int GranularityBytes = 50 * 1024 * 1024;
    internal const int MaxCleanupFiles = 256;

    internal static string DirectoryPath(string localAppData) =>
        Path.IsPathFullyQualified(localAppData)
            ? Path.Combine(localAppData, "Tvivo", "timeshift")
            : throw new ArgumentException("Local app data path must be absolute.", nameof(localAppData));

    internal static IReadOnlyList<string> ForMedia(StreamKind kind, bool enabled, string? path) =>
        enabled && kind == StreamKind.Live && !string.IsNullOrWhiteSpace(path)
            ? [$":input-timeshift-path={path}", $":input-timeshift-granularity={GranularityBytes}"]
            : [];

    internal static int CleanupStaleFiles(string directory)
    {
        if (!Directory.Exists(directory))
            return 0;
        ThrowIfReparseDirectory(Path.GetDirectoryName(directory)!);
        ThrowIfReparseDirectory(directory);

        var removed = 0;
        foreach (var file in Directory.EnumerateFiles(directory, "vlc-timeshift.*", SearchOption.TopDirectoryOnly)
                     .Take(MaxCleanupFiles))
        {
            var name = Path.GetFileName(file);
            if (name.Length != "vlc-timeshift.".Length + 6 ||
                !name.StartsWith("vlc-timeshift.", StringComparison.Ordinal) ||
                !name["vlc-timeshift.".Length..].All(char.IsAsciiLetterOrDigit))
                continue;

            try
            {
                if ((File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0)
                    continue;
                File.Delete(file);
                removed++;
            }
            catch (IOException) { } // Active files can still be held by native VLC.
            catch (UnauthorizedAccessException) { }
        }
        return removed;
    }

    internal static void ThrowIfReparseDirectory(string directory)
    {
        if (Directory.Exists(directory) &&
            (new DirectoryInfo(directory).Attributes & FileAttributes.ReparsePoint) != 0)
            throw new InvalidOperationException("Timeshift directory is a reparse point.");
    }
}
