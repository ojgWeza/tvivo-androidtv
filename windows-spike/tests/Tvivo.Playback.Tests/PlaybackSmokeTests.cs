using Tvivo.Core;
using Tvivo.Playback;
using System.Collections.Concurrent;
using Xunit;

namespace Tvivo.Playback.Tests;

public sealed class PlaybackSmokeTests
{
    [Fact]
    public async Task Timed_out_open_returns_but_blocks_replacement_until_late_source_is_disposed()
    {
        var gate = new DeferredOpenGate<FakeSource>();
        var pending = new TaskCompletionSource<FakeSource>(TaskCreationOptions.RunContinuationsAsynchronously);
        var trace = new List<string>();
        var result = await gate.OpenAsync(pending.Task, () => trace.Add("cancel"),
            source => { source.Dispose(); trace.Add("dispose"); }, Task.Delay(20), CancellationToken.None);

        Assert.Equal(PlaybackAttemptResult.Timeout, result.Result);
        Assert.Equal(new[] { "cancel" }, trace);
        var replacement = gate.WaitForReleaseAsync();
        await Task.Delay(50);
        Assert.False(replacement.IsCompleted);
        pending.SetResult(new FakeSource());
        await replacement;
        Assert.Equal(new[] { "cancel", "dispose" }, trace);
    }

    [Fact]
    public async Task Cancelled_open_waits_for_late_disposal_before_replacement()
    {
        var gate = new DeferredOpenGate<FakeSource>();
        var pending = new TaskCompletionSource<FakeSource>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellation = new CancellationTokenSource();
        var disposed = false;
        var open = gate.OpenAsync(pending.Task, () => { }, _ => disposed = true,
            Task.Delay(TimeSpan.FromSeconds(5)), cancellation.Token);
        cancellation.Cancel();
        Assert.Equal(PlaybackAttemptResult.Cancelled, (await open).Result);
        Assert.False(gate.IsReleased);
        pending.SetResult(new FakeSource());
        await gate.WaitForReleaseAsync();
        Assert.True(disposed);
    }

    [Fact]
    public async Task Never_completing_open_keeps_replacement_gate_closed_after_budget_signal()
    {
        var gate = new DeferredOpenGate<FakeSource>();
        var neverCompletes = new TaskCompletionSource<FakeSource>(TaskCreationOptions.RunContinuationsAsynchronously);
        var deadline = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var budget = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancellationCount = 0;
        var open = gate.OpenAsync(neverCompletes.Task, () => cancellationCount++, _ => { },
            deadline.Task, CancellationToken.None);
        deadline.SetResult();
        Assert.Equal(PlaybackAttemptResult.Timeout, (await open).Result);

        var replacementGate = gate.WaitForReleaseAsync();
        budget.SetResult(); // Simulates the coordinator's later 90-second budget expiring.
        Assert.Same(budget.Task, await Task.WhenAny(replacementGate, budget.Task));
        Assert.False(replacementGate.IsCompleted);
        Assert.Equal(1, cancellationCount);
    }

    [Fact]
    public void Warning_ring_keeps_only_twenty_recent_lines()
    {
        var ring = new AttemptWarningRing();
        for (var i = 0; i < 25; i++) ring.Enqueue($"warning-{i}");
        var lines = ring.Drain();
        Assert.Equal(20, lines.Count);
        Assert.Equal("warning-5", lines[0]);
        Assert.Equal("warning-24", lines[^1]);
    }

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

    private sealed class FakeSource : IDisposable
    {
        public void Dispose() { }
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
