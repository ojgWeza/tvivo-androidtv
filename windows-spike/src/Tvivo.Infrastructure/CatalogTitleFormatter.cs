using System.Text.RegularExpressions;

namespace Tvivo.Infrastructure;

/// <summary>Removes provider decoration from a title: stray double quotes and a leading hashtag mark.</summary>
public static class CatalogTitleFormatter
{
    private static readonly Regex QuoteMarks = new("[\"“”„‟«»]", RegexOptions.Compiled);
    // "#Alive" -> "Alive", but "#1 Happy Family" keeps its number.
    private static readonly Regex LeadingHash = new(@"^#+\s*(?=\p{L})", RegexOptions.Compiled);
    private static readonly Regex EmptyBrackets = new(@"[\(\[]\s*[\)\]]", RegexOptions.Compiled);
    private static readonly Regex LeadingDelimiters = new(@"^\s*(?:[:\-|–—.]\s*)+(?=\s+\S)\s+", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    public static string Clean(string? title)
    {
        var text = QuoteMarks.Replace(title ?? string.Empty, string.Empty);
        text = LeadingHash.Replace(text.Trim(), string.Empty);
        text = EmptyBrackets.Replace(text, " ");
        text = Whitespace.Replace(text, " ").Trim();
        text = StripLeadingDelimiters(text);
        text = EmptyBrackets.Replace(text, " ");
        return Whitespace.Replace(text, " ").Trim();
    }

    /// <summary>Removes only standalone leading title delimiters, preserving punctuation that belongs to a title.</summary>
    public static string StripLeadingDelimiters(string? title) =>
        LeadingDelimiters.Replace(title ?? string.Empty, string.Empty);
}
