using Tvivo.Core;
using Tvivo.Playback;
using Xunit;

namespace Tvivo.Playback.Tests;

public sealed class PlaybackSmokeTests
{
    [Fact]
    public void PlaybackEnginesImplementEngineContract()
    {
        Assert.IsAssignableFrom<IPlaybackEngine>(new WindowsPlaybackEngine());
        Assert.IsAssignableFrom<IPlaybackEngine>(new VlcPlaybackEngine());
    }

    [Theory]
    [InlineData(Windows.Media.Playback.MediaPlayerError.NetworkError, PlaybackAttemptResult.NetworkFailure)]
    [InlineData(Windows.Media.Playback.MediaPlayerError.DecodingError, PlaybackAttemptResult.DecodeFailure)]
    [InlineData(Windows.Media.Playback.MediaPlayerError.SourceNotSupported, PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(Windows.Media.Playback.MediaPlayerError.Unknown, PlaybackAttemptResult.UnknownFailure)]
    public void MediaFailureMappingRemainsCoarse(Windows.Media.Playback.MediaPlayerError error, PlaybackAttemptResult expected)
    {
        Assert.Equal(expected, WindowsPlaybackEngine.MapMediaFailure(error, "diagnostic", new object()));
    }

    [Fact]
    public async Task PlaybackServiceStopsThePreviousGenerationBeforeStartingTheNext()
    {
        var engine = new RecordingEngine();
        var service = new PlaybackService(engine);
        var source = new StreamSource("local-file", StreamKind.Movie);

        Assert.Equal(PlaybackAttemptResult.FirstFrame, await service.PlayAsync(source));
        Assert.Equal(PlaybackAttemptResult.FirstFrame, await service.PlayAsync(source));

        Assert.True(engine.Started.Count == 2);
        Assert.Single(engine.Stopped);
        Assert.True(engine.Started[1].Generation > engine.Started[0].Generation);
        Assert.Equal(engine.Started[0], engine.Stopped[0]);
    }

    private sealed class RecordingEngine : IPlaybackEngine
    {
        public List<PlaybackSessionToken> Started { get; } = new();
        public List<PlaybackSessionToken> Stopped { get; } = new();

        public Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default)
        {
            Started.Add(session);
            return Task.FromResult(PlaybackAttemptResult.FirstFrame);
        }

        public Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default)
        {
            Stopped.Add(session);
            return Task.CompletedTask;
        }
    }
}
