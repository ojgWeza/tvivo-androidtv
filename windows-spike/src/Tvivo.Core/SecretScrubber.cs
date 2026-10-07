using System.Net;
using System.Text.RegularExpressions;

namespace Tvivo.Core;

/// <summary>Redacts the active connection's values without retaining a connection or reading storage.</summary>
public sealed class SecretScrubber
{
    private sealed record Secret(string Value, bool IgnoreCase, bool PathOnly);

    private Secret[] _secrets = [];

    public bool HasActiveSecrets => Volatile.Read(ref _secrets).Length > 0;

    public void SetActiveSecrets(string? host, string? username, string? password)
    {
        var secrets = new List<Secret>();
        Add(host, ignoreCase: true);
        Add(username, ignoreCase: false);
        Add(password, ignoreCase: false);
        Volatile.Write(ref _secrets, secrets.OrderByDescending(secret => secret.Value.Length).ToArray());

        void Add(string? value, bool ignoreCase)
        {
            if (string.IsNullOrEmpty(value)) return;
            foreach (var form in new[] { value, Uri.EscapeDataString(value), WebUtility.UrlEncode(value) }
                         .Distinct(StringComparer.Ordinal))
                secrets.Add(new Secret(form, ignoreCase, form.Length < 3));
        }
    }

    public void Clear() => Volatile.Write(ref _secrets, []);

    public string Scrub(string text)
    {
        foreach (var secret in Volatile.Read(ref _secrets))
        {
            if (secret.PathOnly)
            {
                text = Regex.Replace(text, $@"(?<=/){Regex.Escape(secret.Value)}(?=/|[?#.])",
                    "[redacted-secret]", secret.IgnoreCase ? RegexOptions.IgnoreCase : RegexOptions.None);
            }
            else
            {
                text = text.Replace(secret.Value, "[redacted-secret]",
                    secret.IgnoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
            }
        }
        return text;
    }
}
