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
