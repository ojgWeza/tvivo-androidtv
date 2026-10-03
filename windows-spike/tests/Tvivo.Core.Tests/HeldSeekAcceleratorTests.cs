using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class HeldSeekAcceleratorTests
{
    [Theory]
    [InlineData(0, 0)]
    [InlineData(399, 0)]
    [InlineData(400, 30_000)]
    [InlineData(1_899, 30_000)]
    [InlineData(1_900, 60_000)]
    [InlineData(3_400, 120_000)]
    [InlineData(4_900, 240_000)]
    [InlineData(6_400, 300_000)]
    [InlineData(60_000, 300_000)]
    public void Step_size_accelerates_after_click_window_and_caps(long heldMilliseconds, long expected) =>
        Assert.Equal(expected, HeldSeekAccelerator.StepMilliseconds(TimeSpan.FromMilliseconds(heldMilliseconds)));
}
