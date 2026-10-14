using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class CatalogHoverTextTests
{
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("  \t\r\n ")]
    public void MissingMoviePlotProducesNoSummary(string? plot) =>
        Assert.Equal(string.Empty, CatalogHoverTextFormatter.Summary(CatalogItemType.Movie, plot));

    [Fact]
    public void PlotWhitespaceIsNormalizedWithoutDiscardingTheSummary()
    {
        Assert.Equal("A story about second chances.",
            CatalogHoverTextFormatter.Summary(CatalogItemType.Series, " A   story about\n\nsecond chances. "));
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
    public void LiveHasTooltipOnlyWhenEpgTextExists()
    {
        Assert.False(CatalogHoverTextFormatter.HasSummary(CatalogItemType.Live, null));
        Assert.False(CatalogHoverTextFormatter.HasSummary(CatalogItemType.Live, null, " ", null));
        Assert.True(CatalogHoverTextFormatter.HasSummary(CatalogItemType.Live, null, "Now: News", null));
        Assert.True(CatalogHoverTextFormatter.HasSummary(CatalogItemType.Live, null, null, "Next: News"));
        Assert.Equal(string.Empty, CatalogHoverTextFormatter.Summary(CatalogItemType.Live, null));
    }

    [Theory]
    [InlineData("0", null)]
    [InlineData("0.0", null)]
    [InlineData("0.00", null)]
    [InlineData("", null)]
    [InlineData("  ", null)]
    [InlineData(null, null)]
    [InlineData("7.5", "7.5")]
    [InlineData("5.98766", "6")]
    [InlineData("7.373", "7.4")]
    [InlineData("4.0", "4")]
    [InlineData("4.5", "4.5")]
    [InlineData("0.04", null)]
    [InlineData("abc", null)]
    [InlineData("-1", null)]
    [InlineData("10", "10")]
    public void RatingIsShownOnlyForPositiveNumericValues(string? rating, string? expected) =>
        Assert.Equal(expected, CatalogRatingFormatter.Format(rating));
}
