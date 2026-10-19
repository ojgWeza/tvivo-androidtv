namespace Tvivo.Core;

public static class SpotlightSnapshotRevisionPolicy
{
    public static bool IsCurrent(long snapshotRevision, long settingsRevision) =>
        snapshotRevision == settingsRevision;
}
