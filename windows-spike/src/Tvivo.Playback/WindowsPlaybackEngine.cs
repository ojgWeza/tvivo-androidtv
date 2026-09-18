using Microsoft.UI.Xaml.Controls;
using FFmpegInteropX;
using LibVLCSharp.Shared;
using LibVLCSharp.Platforms.Windows;
using Tvivo.Core;
using Windows.Foundation;
using Windows.Media.Playback;

namespace Tvivo.Playback;

public sealed class WindowsPlaybackEngine : IPlaybackEngine
{
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(10);

    private readonly SemaphoreSlim _gate = new(1, 1);
    private MediaPlayerElement? _element;
    private MediaPlayer? _player;
    private PlaybackSessionToken? _currentSession;
    private EventHandlers? _handlers;

    public MediaPlayerElement Element => _element ??= new MediaPlayerElement
    {
        AreTransportControlsEnabled = false,
        AutoPlay = false,
    };

    public async Task<PlaybackAttemptResult> StartAsync(
        StreamSource source,
        PlaybackSessionToken session,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(true);
        try
        {
            await StopCoreAsync(_currentSession, CancellationToken.None).ConfigureAwait(true);
            cancellationToken.ThrowIfCancellationRequested();

            if (source.DirectUri is null)
                return PlaybackAttemptResult.HostFailure;

            var player = new MediaPlayer();
            var handlers = new EventHandlers(session, player, () => _currentSession == session);
            _player = player;
            _currentSession = session;
            _handlers = handlers;
            Element.SetMediaPlayer(player);

            player.MediaOpened += handlers.MediaOpened;
            player.MediaFailed += handlers.MediaFailed;
            player.MediaEnded += handlers.MediaEnded;
            player.PlaybackSession.PlaybackStateChanged += handlers.PlaybackStateChanged;

            try
            {
                var ffmpegMediaSource = await FFmpegMediaSource.CreateFromUriAsync(source.DirectUri);
                player.Source = ffmpegMediaSource.CreateMediaPlaybackItem();
                player.Play();

                using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                using var cancellationRegistration = linkedCancellation.Token.Register(
                    () => handlers.Completion.TrySetResult(PlaybackAttemptResult.Cancelled));
                var timeoutTask = Task.Delay(StartupDeadline, linkedCancellation.Token);
                var completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask).ConfigureAwait(true);
                var result = completed == handlers.Completion.Task
                    ? await handlers.Completion.Task.ConfigureAwait(true)
                    : PlaybackAttemptResult.Timeout;

                if (result != PlaybackAttemptResult.FirstFrame)
                    await StopCoreAsync(session, CancellationToken.None).ConfigureAwait(true);
                return result;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                await StopCoreAsync(session, CancellationToken.None).ConfigureAwait(true);
                return PlaybackAttemptResult.Cancelled;
            }
            catch
            {
                await StopCoreAsync(session, CancellationToken.None).ConfigureAwait(true);
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
            await StopCoreAsync(session, cancellationToken).ConfigureAwait(true);
        }
        finally
        {
            _gate.Release();
        }
    }

    public static PlaybackAttemptResult MapMediaFailure(
        MediaPlayerError error,
        string? errorMessage,
        object? extendedErrorCode = null)
    {
        return error switch
        {
            MediaPlayerError.NetworkError => PlaybackAttemptResult.NetworkFailure,
            MediaPlayerError.DecodingError => PlaybackAttemptResult.DecodeFailure,
            MediaPlayerError.SourceNotSupported => PlaybackAttemptResult.UnsupportedMedia,
            _ => PlaybackAttemptResult.UnknownFailure,
        };
    }

    private async Task StopCoreAsync(PlaybackSessionToken? session, CancellationToken cancellationToken)
    {
        if (session is not null && _currentSession != session)
            return;

        cancellationToken.ThrowIfCancellationRequested();
        var player = _player;
        var handlers = _handlers;
        _player = null;
        _handlers = null;
        _currentSession = null;

        if (player is null)
            return;

        if (handlers is not null)
        {
            player.MediaOpened -= handlers.MediaOpened;
            player.MediaFailed -= handlers.MediaFailed;
            player.MediaEnded -= handlers.MediaEnded;
            player.PlaybackSession.PlaybackStateChanged -= handlers.PlaybackStateChanged;
        }

        player.Pause();
        player.Source = null;
        player.Dispose();
        Element.SetMediaPlayer(null);
        await Task.CompletedTask.ConfigureAwait(true);
    }

    private sealed class EventHandlers
    {
        private readonly PlaybackSessionToken _session;
        private readonly MediaPlayer _player;
        private readonly Func<bool> _isCurrent;
        private int _firstFrameReported;

        public EventHandlers(PlaybackSessionToken session, MediaPlayer player, Func<bool> isCurrent)
        {
            _session = session;
            _player = player;
            _isCurrent = isCurrent;
            MediaOpened = (_, _) => CheckFirstFrame();
            MediaFailed = (_, args) =>
            {
                if (_isCurrent())
                    Completion.TrySetResult(MapMediaFailure(args.Error, args.ErrorMessage, args.ExtendedErrorCode));
            };
            MediaEnded = (_, _) =>
            {
                if (_isCurrent())
                    Completion.TrySetResult(PlaybackAttemptResult.UnknownFailure);
            };
            PlaybackStateChanged = (_, _) => CheckFirstFrame();
        }

        public TaskCompletionSource<PlaybackAttemptResult> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public TypedEventHandler<MediaPlayer, object> MediaOpened { get; }
        public TypedEventHandler<MediaPlayer, MediaPlayerFailedEventArgs> MediaFailed { get; }
        public TypedEventHandler<MediaPlayer, object> MediaEnded { get; }
        public TypedEventHandler<MediaPlaybackSession, object> PlaybackStateChanged { get; }

        private void CheckFirstFrame()
        {
            if (!_isCurrent() ||
                _player.PlaybackSession.PlaybackState != MediaPlaybackState.Playing ||
                _player.PlaybackSession.NaturalVideoWidth <= 0 ||
                _player.PlaybackSession.NaturalVideoHeight <= 0 ||
                Interlocked.Exchange(ref _firstFrameReported, 1) != 0)
                return;

            Completion.TrySetResult(PlaybackAttemptResult.FirstFrame);
        }
    }
}

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
    private readonly LibVLC _libVlc = new(Array.Empty<string>());
    private readonly VideoView _view = new();
    private Media? _media;
    private MediaPlayer? _player;
    private PlaybackSessionToken? _currentSession;
    private EventHandlers? _handlers;
    private bool _disposed;

    public VideoView View => _view;

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

            if (source.DirectUri is null)
                return PlaybackAttemptResult.HostFailure;

            var media = new Media(_libVlc, source.DirectUri, Array.Empty<string>());
            var player = new MediaPlayer(_libVlc)
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
            _view.MediaPlayer = player;

            player.Playing += handlers.Playing;
            player.Vout += handlers.Vout;
            player.EncounteredError += handlers.EncounteredError;
            player.EndReached += handlers.EndReached;
            PublishState(session, VlcPlaybackState.Preparing);

            try
            {
                if (!player.Play())
                {
                    PublishState(session, VlcPlaybackState.Failed);
                    handlers.Completion.TrySetResult(PlaybackAttemptResult.HostFailure);
                }

                using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                using var cancellationRegistration = linkedCancellation.Token.Register(
                    () => handlers.Completion.TrySetResult(PlaybackAttemptResult.Cancelled));
                var timeoutTask = Task.Delay(StartupDeadline, linkedCancellation.Token);
                var completed = await Task.WhenAny(handlers.Completion.Task, timeoutTask).ConfigureAwait(true);
                var result = completed == handlers.Completion.Task
                    ? await handlers.Completion.Task.ConfigureAwait(true)
                    : PlaybackAttemptResult.Timeout;

                if (result != PlaybackAttemptResult.FirstFrame)
                {
                    if (result == PlaybackAttemptResult.Timeout)
                        PublishState(session, VlcPlaybackState.Failed);
                    StopCore(session);
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
            _libVlc.Dispose();
            _disposed = true;
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
            _libVlc.Dispose();
            _disposed = true;
        }
        finally
        {
            _gate.Release();
            _gate.Dispose();
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

        _view.MediaPlayer = null;
        player.Stop();
        player.Dispose();
        media?.Dispose();
    }

    private void PublishState(PlaybackSessionToken session, VlcPlaybackState state)
    {
        _view.DispatcherQueue?.TryEnqueue(() =>
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
