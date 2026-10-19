using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class ProviderAuthenticationGateTests
{
    [Fact]
    public async Task Lookup_waits_for_sign_in_then_runs_after_success()
    {
        var gate = new ProviderAuthenticationGate();
        var attempt = gate.Begin();
        var lookup = gate.WaitAsync();
        Assert.False(lookup.IsCompleted);
        attempt.SetResult(true);
        Assert.True(await lookup);
    }

    [Fact]
    public async Task Failed_sign_in_does_not_block_a_later_retry()
    {
        var gate = new ProviderAuthenticationGate();
        gate.Begin().SetResult(false);
        Assert.False(await gate.WaitAsync());
        var retry = gate.Begin();
        var lookup = gate.WaitAsync();
        Assert.False(lookup.IsCompleted);
        retry.SetResult(true);
        Assert.True(await lookup);
    }

    [Fact]
    public async Task Replaced_or_cancelled_sign_in_releases_waiters()
    {
        var gate = new ProviderAuthenticationGate();
        gate.Begin();
        var old = gate.WaitAsync();
        gate.Begin();
        Assert.False(await old);
        var current = gate.WaitAsync();
        gate.Cancel();
        Assert.False(await current);
        Assert.True(await gate.WaitAsync());
    }
}
