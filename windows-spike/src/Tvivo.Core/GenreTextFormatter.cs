namespace Tvivo.Core;

public static class GenreTextFormatter
{
    public const int DefaultMaxLength = 28;

    // A long genre list must never push the file size out of the details line. Cut at a whole genre when
    // possible ("Action, Adventure, Sci-Fi, ..." -> "Action, Adventure…"), else at a word, else at the limit.
    public static string? Truncate(string? genre, int maxLength = DefaultMaxLength)
    {
        if (string.IsNullOrWhiteSpace(genre)) return genre;
        var text = genre.Trim();
        if (text.Length <= maxLength) return text;
        var window = text[..maxLength];
        var cut = Math.Max(window.LastIndexOf(','), window.LastIndexOf('/'));
        if (cut < maxLength / 2) cut = window.LastIndexOf(' ');
        if (cut < maxLength / 2) cut = maxLength;
        return text[..cut].TrimEnd(' ', ',', '/', '-') + "…";
    }
}
