namespace Tvivo.Core;

public static class CatalogSnapshotApplyPolicy
{
    // Guarded page requests own the current generation; invalidating it while
    // applying their result makes their own post-await checks fail.
    public static bool ShouldInvalidatePageRequests(bool hasCurrentRequestGuard) => !hasCurrentRequestGuard;
}
