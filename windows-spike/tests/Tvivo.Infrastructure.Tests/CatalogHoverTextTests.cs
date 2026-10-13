using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class CatalogHoverTextTests
{
    [Theory]
    [InlineData(null, false, false, "No summary available")]
    [InlineData("  \t\r\n ", false, false, "No summary available")]
    [InlineData(null, true, false, "Loading summary…")]
    [InlineData(null, false, true, "Summary unavailable")]
    public void MissingMoviePlotUsesAppropriateState(string? plot, bool loading, bool failed, string expected) =>
        Assert.Equal(expected, CatalogHoverTextFormatter.Summary(CatalogItemType.Movie, plot, loading, failed));

    [Fact]
    public void PlotWhitespaceIsNormalizedWithoutDiscardingTheSummary()
    {
        Assert.Equal("A story about second chances.",
            CatalogHoverTextFormatter.Summary(CatalogItemType.Series, " A   story about\n\nsecond chances. ", false, false));
    }

    [Fact]
    public void CastIsOneLineAndBoundedWithEllipsis()
    {
        var formatted = CatalogHoverTextFormatter.Cast("  " + new string('A', 200) + "  ");

        Assert.StartsWith("Cast: ", formatted);
        Assert.EndsWith("…", formatted);
        Assert.Equal(160, formatted.Length);
    }

    [Fact]
    public void LiveUsesEpgOrAnExplicitFallback()
    {
        Assert.Equal("No programme summary available",
            CatalogHoverTextFormatter.Summary(CatalogItemType.Live, null, false, false));
        Assert.Equal(string.Empty,
            CatalogHoverTextFormatter.Summary(CatalogItemType.Live, null, false, false, "Now: News", null));
    }
}
