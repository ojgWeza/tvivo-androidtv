using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class WindowCloseRequestPolicyTests
{
    [Theory]
    [InlineData(0, 3000)]
    [InlineData(2000, 3000)]
    [InlineData(4000, 1000)]
    [InlineData(4999, 1)]
    public void Stage_timeout_uses_one_total_budget(int elapsedMs, int expectedMs) =>
        Assert.Equal(TimeSpan.FromMilliseconds(expectedMs),
            WindowCloseRequestPolicy.StageTimeout(TimeSpan.FromMilliseconds(elapsedMs)));

    [Fact]
    public void Stage_is_skipped_when_total_budget_is_spent() =>
        Assert.Null(WindowCloseRequestPolicy.StageTimeout(TimeSpan.FromSeconds(5)));

    [Theory]
    [InlineData(true, true, "alt-f4")]
    [InlineData(true, false, "taskbar")]
    [InlineData(false, true, "unknown")]
    [InlineData(false, false, "unknown")]
    public void Native_close_source_is_classified_only_when_system_command_is_seen(
        bool systemCloseCommand, bool altHeld, string expected) =>
        Assert.Equal(expected, WindowCloseRequestPolicy.ClassifyNative(systemCloseCommand, altHeld));
}
