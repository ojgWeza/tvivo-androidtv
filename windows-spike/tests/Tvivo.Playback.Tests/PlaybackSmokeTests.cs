using Tvivo.Core;
using Tvivo.Playback;
using Xunit;

namespace Tvivo.Playback.Tests;

public sealed class PlaybackSmokeTests
{
    [Fact]
    public void PlaybackEnginesImplementEngineContract()
    {
        Assert.IsAssignableFrom<IPlaybackEngine>(new VlcPlaybackEngine());
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

    [Fact]
    public async Task PlaybackServicePassesSelectedChannelFixtureUriToEngineUnchanged()
    {
        var engine = new RecordingSourceEngine();
        var service = new PlaybackService(engine);
        var fixtureName = "thirtyfive-second-h264.mp4";
        string? fixturePath = null;
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory);
             directory is not null;
             directory = directory.Parent)
        {
            var candidate = Path.Combine(directory.FullName, "tests", "Gate9", fixtureName);
            if (File.Exists(candidate))
            {
                fixturePath = candidate;
                break;
            }
        }

        Assert.NotNull(fixturePath);
        var fixtureUri = new Uri(fixturePath!);
        var channelSource = new StreamSource("gate-9-local-fixture", StreamKind.Movie, DirectUri: fixtureUri);

        await service.PlayAsync(channelSource);

        Assert.Same(channelSource, engine.StartedSource);
        Assert.Equal(fixtureUri, engine.StartedSource!.DirectUri);
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

    private sealed class RecordingSourceEngine : IPlaybackEngine
    {
        public StreamSource? StartedSource { get; private set; }

        public Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default)
        {
            StartedSource = source;
            return Task.FromResult(PlaybackAttemptResult.Started);
        }

        public Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
