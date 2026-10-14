using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class CatalogTitleFormatterTests
{
    [Theory]
    [InlineData("\"Wuthering Heights\" (2026)", "Wuthering Heights (2026)")]
    [InlineData("The Haunted Apartment: \"Miss K\"", "The Haunted Apartment: Miss K")]
    [InlineData("#Alive Multi sub", "Alive Multi sub")]
    [InlineData("# Home HD", "Home HD")]
    [InlineData("#1 Happy Family USA", "#1 Happy Family USA")]
    [InlineData("“Quoted”  title", "Quoted title")]
    [InlineData(": Title", "Title")]
    [InlineData("- | — . Title", "Title")]
    [InlineData("[] : Title", "Title")]
    [InlineData("(500) Days", "(500) Days")]
    [InlineData(".hack", ".hack")]
    [InlineData("-30-", "-30-")]
    [InlineData("---", "---")]
    [InlineData("Plain Title", "Plain Title")]
    [InlineData(null, "")]
    public void StrayQuotesAndLeadingHashAreRemoved(string? title, string expected) =>
        Assert.Equal(expected, CatalogTitleFormatter.Clean(title));
}
