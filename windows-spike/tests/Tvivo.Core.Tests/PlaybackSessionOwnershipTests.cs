using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackSessionOwnershipTests
{
    [Theory]
    [InlineData(8, 8, true, true)]
    [InlineData(8, 9, true, false)]
    [InlineData(8, 8, false, false)]
    public void Sleep_timer_cleanup_requires_the_ended_session(long ended, long current, bool timerEnded, bool expected) =>
        Assert.Equal(expected, PlaybackSessionOwnership.CanCompleteSleepTimerStop(ended, current, timerEnded));

    [Theory]
    [InlineData(8, 8, true)]
    [InlineData(9, 8, false)]
    [InlineData(-1, 8, false)]
    public void Progress_stop_requires_its_owner(long owner, long completing, bool expected) =>
        Assert.Equal(expected, PlaybackSessionOwnership.CanStopProgress(owner, completing));
}
