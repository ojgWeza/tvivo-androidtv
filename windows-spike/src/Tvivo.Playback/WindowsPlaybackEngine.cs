using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;
using Windows.Foundation;
using Windows.Media.Core;
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
                player.Source = MediaSource.CreateFromUri(source.DirectUri);
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

public sealed class VlcPlaybackEngine : IPlaybackEngine
{
    public Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default) => throw new NotImplementedException();
    public Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default) => throw new NotImplementedException();
}
