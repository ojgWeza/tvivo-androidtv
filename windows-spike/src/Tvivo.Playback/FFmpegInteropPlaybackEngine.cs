using FFmpegInteropX;
using System.Globalization;
using Tvivo.Core;
using Windows.Foundation.Collections;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace Tvivo.Playback;

public sealed class FFmpegInteropPlaybackEngine : IPlaybackEngine, ITrackSelectingEngine, IDisposable
{
    private static readonly TimeSpan StartupDeadline = TimeSpan.FromSeconds(30);
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly MediaPlayer _player = new();
    private FFmpegMediaSource? _source;
    private MediaPlaybackItem? _playbackItem;
    private PlaybackSessionToken? _currentSession;
    private TaskCompletionSource<PlaybackAttemptResult>? _startup;
    private bool _disposed;
    private volatile bool _ended;
    private readonly object _trackSync = new();
    private PlaybackTrackSnapshot _tracks = PlaybackTrackSnapshot.Unresolved;
    private bool _defaultSubtitleApplied;
    public event Action<string>? LifecycleEvent;
    public event Action<PlaybackSessionToken>? PlaybackFailed;
    public event EventHandler? TracksChanged;

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
    public bool IsMuted
    {
        get => _player.IsMuted;
        set => _player.IsMuted = value;
    }

    public PlaybackTrackSnapshot GetTracks()
    {
        lock (_trackSync)
            return _tracks;
    }

    public bool SelectAudio(string key)
    {
        if (!int.TryParse(key, NumberStyles.None, CultureInfo.InvariantCulture, out var index))
            return false;

        MediaPlaybackItem? item;
        lock (_trackSync)
        {
            item = _playbackItem;
            if (item is null || index >= item.AudioTracks.Count)
                return false;
        }

        try
        {
            item.AudioTracks.SelectedIndex = index;
            RefreshNativeTracks(item, resolved: true);
            LifecycleEvent?.Invoke($"event=playback.tracks.select engine=Native kind=Audio key={key} success=true");
            return true;
        }
        catch (Exception exception)
        {
            LifecycleEvent?.Invoke($"event=playback.tracks.select engine=Native kind=Audio key={key} success=false error={exception.GetType().Name}");
            return false;
        }
    }

    public bool SelectSubtitle(string? keyOrNullForOff)
    {
        uint selectedIndex = 0;
        if (keyOrNullForOff is not null &&
            !uint.TryParse(keyOrNullForOff, NumberStyles.None, CultureInfo.InvariantCulture, out selectedIndex))
            return false;

        MediaPlaybackItem? item;
        lock (_trackSync)
        {
            item = _playbackItem;
            if (item is null || (keyOrNullForOff is not null && selectedIndex >= (uint)item.TimedMetadataTracks.Count))
                return false;
        }

        try
        {
            for (uint index = 0; index < (uint)item.TimedMetadataTracks.Count; index++)
            {
                var mode = keyOrNullForOff is not null && index == selectedIndex
                    ? TimedMetadataTrackPresentationMode.PlatformPresented
                    : TimedMetadataTrackPresentationMode.Disabled;
                item.TimedMetadataTracks.SetPresentationMode(index, mode);
            }
            lock (_trackSync)
                _defaultSubtitleApplied = true;
            RefreshNativeTracks(item, resolved: true);
            LifecycleEvent?.Invoke($"event=playback.tracks.select engine=Native kind=Subtitle key={keyOrNullForOff ?? "off"} success=true");
            return true;
        }
        catch (Exception exception)
        {
            LifecycleEvent?.Invoke($"event=playback.tracks.select engine=Native kind=Subtitle key={keyOrNullForOff ?? "off"} success=false error={exception.GetType().Name}");
            return false;
        }
    }

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
        if (SelectSubtitle(key))
            LifecycleEvent?.Invoke($"event=playback.tracks.default engine=Native key={key ?? "off"}");
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
                _playbackItem = _source.CreateMediaPlaybackItem();
                _playbackItem.AudioTracksChanged += OnAudioTracksChanged;
                _playbackItem.TimedMetadataTracksChanged += OnTimedMetadataTracksChanged;
                _player.Source = _playbackItem;
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

    private void OnMediaOpened(MediaPlayer sender, object args)
    {
        if (_playbackItem is { } item)
            RefreshNativeTracks(item, resolved: true);
        _startup?.TrySetResult(PlaybackAttemptResult.FirstFrame);
    }

    private void OnMediaFailed(MediaPlayer sender, MediaPlayerFailedEventArgs args)
    {
        var startup = _startup;
        var wasReady = startup is not null &&
            startup.Task.IsCompletedSuccessfully &&
            startup.Task.Result == PlaybackAttemptResult.FirstFrame;
        var startupFailureWasRecorded = startup?.TrySetResult(PlaybackAttemptResult.DecodeFailure) == true;
        if (wasReady && !startupFailureWasRecorded && _currentSession is { } session)
            PlaybackFailed?.Invoke(session);
    }

    private void OnMediaEnded(MediaPlayer sender, object args) => _ended = true;

    private void OnAudioTracksChanged(MediaPlaybackItem sender, IVectorChangedEventArgs args) =>
        RefreshNativeTracks(sender, resolved: true);

    private void OnTimedMetadataTracksChanged(MediaPlaybackItem sender, IVectorChangedEventArgs args) =>
        RefreshNativeTracks(sender, resolved: true);

    private void StopCore(string? reason = null)
    {
        var hadSource = _source is not null || _player.Source is not null;
        reason ??= _ended ? "completed" : "user-cancelled";
        _startup?.TrySetResult(PlaybackAttemptResult.Cancelled);
        _startup = null;
        _currentSession = null;
        _ended = false;
        if (_playbackItem is { } item)
        {
            item.AudioTracksChanged -= OnAudioTracksChanged;
            item.TimedMetadataTracksChanged -= OnTimedMetadataTracksChanged;
        }
        _playbackItem = null;
        lock (_trackSync)
        {
            _tracks = PlaybackTrackSnapshot.Unresolved;
            _defaultSubtitleApplied = false;
        }
        _player.Pause();
        _player.Source = null;
        _source?.Dispose();
        _source = null;
        if (hadSource)
            LifecycleEvent?.Invoke($"event=playback.engine.release engine=Native reason={reason} source=released playerSource=cleared callbacks=retained-until-dispose");
    }

    private void RefreshNativeTracks(MediaPlaybackItem item, bool resolved)
    {
        lock (_trackSync)
        {
            if (!ReferenceEquals(_playbackItem, item))
                return;
        }

        PlaybackTrackSnapshot snapshot;
        try
        {
            var audio = new List<PlaybackTrack>();
            for (var index = 0; index < item.AudioTracks.Count; index++)
            {
                var track = item.AudioTracks[index];
                var languageCode = track.Language;
                var sourceLabel = string.IsNullOrWhiteSpace(track.Label) ? track.Name : track.Label;
                audio.Add(new PlaybackTrack(
                    TrackKind.Audio,
                    index.ToString(CultureInfo.InvariantCulture),
                    TrackSelectionPolicy.GetDisplayName(TrackKind.Audio, sourceLabel, languageCode, (int)index + 1),
                    languageCode,
                    item.AudioTracks.SelectedIndex == index));
            }

            var subtitles = new List<PlaybackTrack>();
            var subtitleOrdinal = 0;
            for (var index = 0; index < item.TimedMetadataTracks.Count; index++)
            {
                var track = item.TimedMetadataTracks[index];
                if (!IsSubtitleTrack(track))
                    continue;

                var mode = item.TimedMetadataTracks.GetPresentationMode((uint)index);
                var sourceLabel = string.IsNullOrWhiteSpace(track.Label) ? track.Name : track.Label;
                subtitleOrdinal++;
                subtitles.Add(new PlaybackTrack(
                    TrackKind.Subtitle,
                    index.ToString(CultureInfo.InvariantCulture),
                    TrackSelectionPolicy.GetDisplayName(TrackKind.Subtitle, sourceLabel, track.Language, subtitleOrdinal),
                    track.Language,
                    mode != TimedMetadataTrackPresentationMode.Disabled));
            }

            snapshot = new PlaybackTrackSnapshot(audio, subtitles, resolved);
        }
        catch (Exception exception)
        {
            LifecycleEvent?.Invoke($"Track enumeration failed. engine=Native {exception.GetType().Name}");
            return;
        }

        bool changed;
        lock (_trackSync)
        {
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
        LifecycleEvent?.Invoke(
            $"event=playback.tracks engine=Native resolved={snapshot.IsResolved} " +
            $"audioCount={snapshot.Audio.Count} subtitleCount={snapshot.Subtitles.Count} " +
            $"audioLanguages=[{audioLanguages}] subtitleLanguages=[{subtitleLanguages}] " +
            $"selectedAudio={selectedAudio} selectedSubtitle={selectedSubtitle}");
        TracksChanged?.Invoke(this, EventArgs.Empty);
    }

    private static bool TrackSnapshotsEqual(PlaybackTrackSnapshot left, PlaybackTrackSnapshot right) =>
        left.IsResolved == right.IsResolved &&
        left.Audio.SequenceEqual(right.Audio) &&
        left.Subtitles.SequenceEqual(right.Subtitles);

    private static bool IsSubtitleTrack(TimedMetadataTrack track) =>
        track.TimedMetadataKind is TimedMetadataKind.Caption or TimedMetadataKind.Subtitle or TimedMetadataKind.ImageSubtitle;
}
