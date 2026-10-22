using System.Text.RegularExpressions;

namespace Tvivo.Core;

public static class EpisodeDisplayNamePolicy
{
    private static readonly Regex EpisodeToken = new(@"(?i)\bS\d{1,2}\s*E\d{1,3}\b", RegexOptions.Compiled);
    private static readonly Regex LeadingEpisodeNumber = new(@"(?i)^(?:E(?:pisode)?\s*0*)?(\d{1,3})(?:\s*[.\-:–—]\s*|\s+)(.*)$", RegexOptions.Compiled);

    public static string Format(string? seriesName, int seasonNumber, int episodeNumber,
        string? providerTitle, int indexWithinSeason)
    {
        var sequence = Math.Max(1, indexWithinSeason + 1);
        var title = EpisodeToken.Replace(providerTitle ?? string.Empty, " ").Trim(' ', '-', '–', '—', '.', ':', '|', '_');
        if (!string.IsNullOrWhiteSpace(seriesName))
        {
            var name = seriesName.Trim();
            while (title.StartsWith(name, StringComparison.OrdinalIgnoreCase) &&
                   (title.Length == name.Length || !char.IsLetterOrDigit(title[name.Length])))
                title = title[name.Length..].Trim(' ', '-', '–', '—', '.', ':', '|', '_');
        }
        title = Regex.Replace(title, @"\s+", " ").Trim();
        var number = Math.Max(1, episodeNumber);
        var match = LeadingEpisodeNumber.Match(title);
        if (match.Success && int.TryParse(match.Groups[1].Value, out var leading) && leading == number)
            title = match.Groups[2].Value.Trim();
        if (string.IsNullOrWhiteSpace(title) || title == number.ToString(System.Globalization.CultureInfo.InvariantCulture))
            title = $"Episode {number}";
        return $"{sequence}. {title}";
    }
}
