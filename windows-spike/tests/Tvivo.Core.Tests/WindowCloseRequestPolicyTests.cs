using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class WindowCloseRequestPolicyTests
{
    [Theory]
    [InlineData(true, true, "alt-f4")]
    [InlineData(true, false, "taskbar")]
    [InlineData(false, true, "unknown")]
    [InlineData(false, false, "unknown")]
    public void Native_close_source_is_classified_only_when_system_command_is_seen(
        bool systemCloseCommand, bool altHeld, string expected) =>
        Assert.Equal(expected, WindowCloseRequestPolicy.ClassifyNative(systemCloseCommand, altHeld));
}
