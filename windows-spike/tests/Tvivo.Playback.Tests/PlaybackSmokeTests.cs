using Tvivo.Core;
using Tvivo.Playback;
using System.Collections.Concurrent;
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
    public void Late_failure_signal_is_handled_once_for_its_ready_generation()
    {
        var policy = new PlaybackFailurePolicy();
        var handled = new List<string>();

        if (policy.TryHandle(callbackGeneration: 4, currentGeneration: 4, activeGeneration: 4, ready: true))
            handled.Add("failure:g4");
        if (policy.TryHandle(callbackGeneration: 4, currentGeneration: 4, activeGeneration: 4, ready: true))
            handled.Add("failure:g4-duplicate");
        if (policy.TryHandle(callbackGeneration: 4, currentGeneration: 5, activeGeneration: 5, ready: true))
            handled.Add("failure:g4-stale");

        Assert.Equal(new[] { "failure:g4" }, handled);
        Assert.True(policy.HasFailed(4));
        Assert.False(policy.HasFailed(5));
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
    public async Task PlaybackHandoffStopsBeforeOpeningAndSerializesConcurrentStarts()
    {
        var handoff = new PlaybackHandoff(TimeSpan.Zero);
        var activeStreams = 0;
        var openOrder = new ConcurrentQueue<string>();
        var firstStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseFirstStart = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var first = handoff.RunAsync(
            _ => { activeStreams = 0; openOrder.Enqueue("stop-1"); return Task.CompletedTask; },
            async _ =>
            {
                Assert.Equal(0, activeStreams);
                activeStreams++;
                openOrder.Enqueue("start-1");
                firstStarted.SetResult();
                await releaseFirstStart.Task;
                return PlaybackAttemptResult.FirstFrame;
            });
        await firstStarted.Task;

        var second = handoff.RunAsync(
            _ => { activeStreams = 0; openOrder.Enqueue("stop-2"); return Task.CompletedTask; },
            _ =>
            {
                Assert.Equal(0, activeStreams);
                activeStreams++;
                openOrder.Enqueue("start-2");
                return Task.FromResult(PlaybackAttemptResult.FirstFrame);
            });
        await Task.Yield();
        Assert.DoesNotContain("start-2", openOrder);
        releaseFirstStart.SetResult();

        Assert.Equal(PlaybackAttemptResult.FirstFrame, await first);
        Assert.Equal(PlaybackAttemptResult.FirstFrame, await second);
        Assert.Equal(new[] { "stop-1", "start-1", "stop-2", "start-2" }, openOrder.ToArray());
        Assert.Equal(1, activeStreams);
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
        public bool IsPlaying => false;
        public long Time => 0;
        public long Length => 0;
        public int Volume { get; set; } = 100;
        public bool IsMuted { get; set; }
        public void TogglePause() { }
        public void Seek(long timeMilliseconds) { }
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
        public bool IsPlaying => false;
        public long Time => 0;
        public long Length => 0;
        public int Volume { get; set; } = 100;
        public bool IsMuted { get; set; }
        public void TogglePause() { }
        public void Seek(long timeMilliseconds) { }
        public StreamSource? StartedSource { get; private set; }

        public Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default)
        {
            StartedSource = source;
            return Task.FromResult(PlaybackAttemptResult.Started);
        }

        public Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
