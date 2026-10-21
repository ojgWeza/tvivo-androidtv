namespace Tvivo.Core;

public enum FileSizeAvailability { NeverChecked, CheckedAbsent, Known }

public static class FileSizeDisplayPolicy
{
    public static bool ShouldShow(StreamKind? kind) => kind is StreamKind.Movie or StreamKind.Episode;
}

public static class FileSizeAvailabilityPolicy
{
    public static readonly TimeSpan RecheckInterval = TimeSpan.FromDays(7);

    public static FileSizeAvailability Classify(long? sizeBytes, DateTimeOffset? checkedAt) =>
        sizeBytes is > 0 ? FileSizeAvailability.Known :
        checkedAt is null ? FileSizeAvailability.NeverChecked : FileSizeAvailability.CheckedAbsent;

    public static bool ShouldCheck(long? sizeBytes, DateTimeOffset? checkedAt, DateTimeOffset now) =>
        Classify(sizeBytes, checkedAt) switch
        {
            FileSizeAvailability.NeverChecked => true,
            FileSizeAvailability.CheckedAbsent => now - checkedAt!.Value >= RecheckInterval,
            _ => false,
        };
}

public static class FileSizeProbePolicy
{
    public const long MinimumBytes = 1_000_000;
    public const long MaximumBytes = 200L * 1_000_000_000;

    public static bool ShouldProbe(StreamKind kind, long? sizeBytes, DateTimeOffset? checkedAt, DateTimeOffset now) =>
        kind is StreamKind.Movie or StreamKind.Episode &&
        FileSizeAvailabilityPolicy.ShouldCheck(sizeBytes, checkedAt, now);

    public static long? ParseTotalBytes(System.Net.Http.Headers.HttpResponseHeaders headers,
        System.Net.Http.Headers.HttpContentHeaders contentHeaders, System.Net.HttpStatusCode statusCode)
    {
        if (contentHeaders.TryGetValues("Content-Range", out var values) || headers.TryGetValues("Content-Range", out values))
        {
            var range = values.FirstOrDefault();
            var slash = range?.LastIndexOf('/') ?? -1;
            if (range is not null && range.StartsWith("bytes 0-0/", StringComparison.OrdinalIgnoreCase) &&
                slash >= 0 && long.TryParse(range[(slash + 1)..], out var total) && IsPlausible(total))
                return total;
        }

        if (statusCode != System.Net.HttpStatusCode.OK ||
            contentHeaders.ContentLength is not > 0 ||
            (headers.TryGetValues("Transfer-Encoding", out var transferValues) &&
             transferValues.Any(value => value.Contains("chunked", StringComparison.OrdinalIgnoreCase))))
            return null;
        return IsPlausible(contentHeaders.ContentLength.Value) ? contentHeaders.ContentLength.Value : null;
    }

    private static bool IsPlausible(long bytes) => bytes is >= MinimumBytes and <= MaximumBytes;
}
