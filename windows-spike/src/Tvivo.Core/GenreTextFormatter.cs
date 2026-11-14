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

    // Details lines mix provider text (often Arabic) with English "File size: 1.4 GB". Without isolation the
    // bidi algorithm lets the Arabic run reorder its neighbours and the line reads as gibberish.
    public static string? IsolateAuto(string? text) =>
        string.IsNullOrWhiteSpace(text) ? text : "\u2068" + text + "\u2069";

    public static string? IsolateLtr(string? text) =>
        string.IsNullOrWhiteSpace(text) ? text : "\u2066" + text + "\u2069";
}
