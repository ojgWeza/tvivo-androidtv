using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackFailurePolicyTests
{
    [Fact]
    public void Final_failure_stays_on_the_current_item_and_suppresses_autoplay_navigation()
    {
        var policy = new PlaybackFailurePolicy();
        const long generation = 42;

        policy.MarkFinalFailure(generation);

        Assert.True(policy.HasFailed(generation));
        Assert.False(policy.ShouldAutoAdvance(generation));
    }
}
