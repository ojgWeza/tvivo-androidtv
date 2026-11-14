using System.Globalization;
using System.Text.RegularExpressions;

namespace Tvivo.Core;

public static class GenreTextFormatter
{
    public const int DefaultMaxLength = 28;

    // Some providers send non-Latin text as literal escape runs whose backslashes were lost, e.g.
    // "u062fu0631u0627u0645u0627" for an Arabic genre. Two or more consecutive u+4 hex runs (backslash optional)
    // are decoded; a lone "u1234" inside an ordinary word is left alone.
    private static readonly Regex EscapedRun = new(@"(?:\\?u[0-9a-fA-F]{4}){2,}", RegexOptions.Compiled);
    private static readonly Regex EscapedUnit = new(@"\\?u([0-9a-fA-F]{4})", RegexOptions.Compiled);

    public static string? DecodeEscapes(string? text) =>
        string.IsNullOrEmpty(text) ? text : EscapedRun.Replace(text, run => EscapedUnit.Replace(run.Value,
            unit => ((char)int.Parse(unit.Groups[1].Value, NumberStyles.HexNumber, CultureInfo.InvariantCulture)).ToString()));

    // A long genre list must never push the file size out of the details line. Cut at a whole genre when
    // possible ("Action, Adventure, Sci-Fi, ..." -> "Action, Adventure…"), else at a word, else at the limit.
    public static string? Truncate(string? genre, int maxLength = DefaultMaxLength)
    {
        if (string.IsNullOrWhiteSpace(genre)) return genre;
        var text = DecodeEscapes(genre)!.Trim();
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
