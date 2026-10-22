using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SleepTimerItemChangePolicyTests
{
    [Theory]
    [InlineData(SleepTimerMode.Duration, SleepTimerItemChangeSetting.Clear, true)]
    [InlineData(SleepTimerMode.Duration, SleepTimerItemChangeSetting.Keep, false)]
    [InlineData(SleepTimerMode.EndOfCurrentItem, SleepTimerItemChangeSetting.Keep, true)]
    [InlineData(null, SleepTimerItemChangeSetting.Keep, false)]
    public void Clears_only_when_configured_or_timer_targets_current_item(
        SleepTimerMode? mode, SleepTimerItemChangeSetting setting, bool expected) =>
        Assert.Equal(expected, SleepTimerItemChangePolicy.ShouldClear(mode, setting));
}
