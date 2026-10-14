using System.Text.RegularExpressions;

namespace Tvivo.Infrastructure;

/// <summary>Removes provider decoration from a title: stray double quotes and a leading hashtag mark.</summary>
public static class CatalogTitleFormatter
{
    private static readonly Regex QuoteMarks = new("[\"“”„‟«»]", RegexOptions.Compiled);
    // "#Alive" -> "Alive", but "#1 Happy Family" keeps its number.
    private static readonly Regex LeadingHash = new(@"^#+\s*(?=\p{L})", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    public static string Clean(string? title)
    {
        var text = QuoteMarks.Replace(title ?? string.Empty, string.Empty);
        text = LeadingHash.Replace(text.Trim(), string.Empty);
        return Whitespace.Replace(text, " ").Trim();
    }
}
