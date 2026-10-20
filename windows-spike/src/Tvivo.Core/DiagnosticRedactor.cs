using System.Text.RegularExpressions;

namespace Tvivo.Core;

/// <summary>Exports only known diagnostic fields; free-form log text is never copied.</summary>
public static partial class DiagnosticRedactor
{
    [GeneratedRegex(@"^(?<utc>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2}))(?=\s|$)")]
    private static partial Regex Timestamp();
    [GeneratedRegex(@"(?<!\S)(?<key>session|trace|op|stage|attempt|outcome|http|elapsedMs|exception|retryAt|event|code)=(?<value>\S{1,80})(?=\s|$)")]
    private static partial Regex Field();
    [GeneratedRegex(@"\A[0-9a-fA-F]{12}(?:[0-9a-fA-F]{20})?\z")]
    private static partial Regex OpaqueId();
    [GeneratedRegex(@"\A(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,3})?\z")]
    private static partial Regex Number();

    private static readonly HashSet<string> Operations = new(StringComparer.Ordinal)
    {
        "auth", "categories", "items", "vod-info", "series-info", "series-list",
    };
    private static readonly HashSet<string> Stages = new(StringComparer.Ordinal)
    {
        "http", "json", "parse", "auth", "cache", "database",
    };
    private static readonly HashSet<string> Outcomes = new(StringComparer.Ordinal)
    {
        "ok", "success", "failure", "none", "Auth", "ProviderBusy", "Forbidden",
        "Timeout", "Network", "Protocol", "Database", "EmptyValid", "Cancelled",
    };

    public static string RedactLine(string line)
    {
        if (line.Length > 16_384) line = line[..16_384];
        var fields = Field().Matches(line).Take(32)
            .Where(match => IsSafe(match.Groups["key"].Value, match.Groups["value"].Value))
            .Take(16).Select(match => $"{match.Groups["key"].Value}={match.Groups["value"].Value}");
        var utc = Timestamp().Match(line);
        var result = new List<string>();
        if (utc.Success) result.Add(utc.Groups["utc"].Value);
        result.AddRange(fields);
        return string.Join(' ', result);
    }

    private static bool IsSafe(string key, string value) => key switch
    {
        "session" or "trace" => OpaqueId().IsMatch(value),
        "op" => Operations.Contains(value),
        "stage" => Stages.Contains(value),
        "outcome" => Outcomes.Contains(value),
        "attempt" or "elapsedMs" => Number().IsMatch(value),
        "http" => value == "none" || int.TryParse(value, out var status) && status is >= 100 and <= 599,
        // Event names and exception names are free-form in the source log. Neither can be
        // distinguished reliably from a title or an account value in an imported log.
        _ => false,
    };
}
