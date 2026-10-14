using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class PerfTogglesTests
{
    [Fact]
    public void ParseDisabled_RecognizesKnownNamesAndIgnoresUnknownNames()
    {
        var disabled = PerfToggles.ParseDisabled(" spotlight, EPG,unknown,SPOTLIGHT ");

        Assert.Equal(new[] { "spotlight", "epg" },
            new[] { "spotlight", "epg", "xamltrace", "playbacktimer", "perftimers", "spotlightart", "clips", "lens", "shelfclip" }
                .Where(disabled.Contains));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData(" , , ")]
    [InlineData("unknown")]
    public void ParseDisabled_ReturnsEmptyWhenNoKnownToggleIsListed(string? value)
    {
        Assert.Empty(PerfToggles.ParseDisabled(value));
    }
}
