using System.Text.RegularExpressions;

namespace Tvivo.Core;

/// <summary>Exports only known diagnostic fields; free-form log text is never copied.</summary>
public static partial class DiagnosticRedactor
{
    [GeneratedRegex(@"^(?<utc>\d{4}-\d{2}-\d{2}T[^\s]+)")]
    private static partial Regex Timestamp();
    [GeneratedRegex(@"(?<!\S)(?<key>session|trace|op|stage|attempt|outcome|http|elapsedMs|exception|retryAt|event|code)=(?<value>[A-Za-z0-9_.:+-]{1,80})")]
    private static partial Regex Field();

    public static string RedactLine(string line)
    {
        if (line.Length > 16_384) line = line[..16_384];
        var fields = Field().Matches(line).Take(16)
            .Select(match => $"{match.Groups["key"].Value}={match.Groups["value"].Value}");
        var utc = Timestamp().Match(line);
        var result = new List<string>();
        if (utc.Success) result.Add(utc.Groups["utc"].Value);
        result.AddRange(fields);
        return string.Join(' ', result);
    }
}
