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

    public static bool ShouldProbeOnOpen(StreamKind kind, long? exactBytes) =>
        (kind is StreamKind.Movie or StreamKind.Episode) &&
        exactBytes is not (>= MinimumBytes and <= MaximumBytes);

    public static bool ShouldProbe(StreamKind kind, long? sizeBytes, DateTimeOffset? checkedAt, DateTimeOffset now) =>
        (kind is StreamKind.Movie or StreamKind.Episode) &&
        FileSizeAvailabilityPolicy.ShouldCheck(sizeBytes, checkedAt, now);

    public static long? ParseTotalBytes(System.Net.Http.Headers.HttpResponseHeaders headers,
        System.Net.Http.Headers.HttpContentHeaders contentHeaders, System.Net.HttpStatusCode statusCode)
    {
        if (headers.TransferEncodingChunked == true ||
            (headers.TryGetValues("Transfer-Encoding", out var transfers) &&
             transfers.Any(value => value.Contains("chunked", StringComparison.OrdinalIgnoreCase)))) return null;
        if (statusCode == System.Net.HttpStatusCode.PartialContent)
        {
            var range = contentHeaders.ContentRange;
            if (range is null || !range.HasLength || range.From != 0 || range.To != 0 ||
                contentHeaders.ContentLength is > 1) return null;
            return IsPlausible(range.Length!.Value) ? range.Length : null;
        }
        if (statusCode != System.Net.HttpStatusCode.OK || contentHeaders.ContentRange is not null ||
            contentHeaders.ContentLength is not > 0)
            return null;
        return IsPlausible(contentHeaders.ContentLength.Value) ? contentHeaders.ContentLength.Value : null;
    }

    private static bool IsPlausible(long bytes) => bytes is >= MinimumBytes and <= MaximumBytes;
}
