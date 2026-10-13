using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class HoverRequestSchedulerTests
{
    [Fact]
    public async Task CancelBeforeDelayCompletesPreventsAction()
    {
        var delayStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var scheduler = new HoverRequestScheduler(async (duration, cancellationToken) =>
        {
            Assert.Equal(TimeSpan.FromMilliseconds(800), duration);
            delayStarted.TrySetResult();
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        });
        var actionCalls = 0;
        var lease = scheduler.Begin("movie-1");

        var scheduled = scheduler.RunAfterDelayAsync(lease, TimeSpan.FromMilliseconds(800), _ =>
        {
            Interlocked.Increment(ref actionCalls);
            return Task.CompletedTask;
        });
        await delayStarted.Task;
        scheduler.Cancel(lease);
        await scheduled;

        Assert.Equal(0, actionCalls);
        Assert.False(scheduler.IsCurrent(lease));
    }

    [Fact]
    public async Task NewHoverInvalidatesOldGenerationAndOnlyRunsLatestAction()
    {
        var delaysStarted = 0;
        var scheduler = new HoverRequestScheduler(async (duration, cancellationToken) =>
        {
            Interlocked.Increment(ref delaysStarted);
            if (duration == TimeSpan.Zero) return;
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        });
        var oldCalls = 0;
        var newCalls = 0;
        var oldLease = scheduler.Begin("movie-1");
        var oldScheduled = scheduler.RunAfterDelayAsync(oldLease, TimeSpan.FromMilliseconds(800), _ =>
        {
            Interlocked.Increment(ref oldCalls);
            return Task.CompletedTask;
        });
        var newLease = scheduler.Begin("movie-2");
        var newScheduled = scheduler.RunAfterDelayAsync(newLease, TimeSpan.Zero, _ =>
        {
            Interlocked.Increment(ref newCalls);
            return Task.CompletedTask;
        });
        await Task.WhenAll(oldScheduled, newScheduled);

        Assert.Equal(2, delaysStarted);
        Assert.Equal(0, oldCalls);
        Assert.Equal(1, newCalls);
    }
}
