using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CatalogSnapshotApplyPolicyTests
{
    [Fact]
    public void ApplyingGuardedPageResult_DoesNotInvalidateItsOwnGeneration() =>
        Assert.False(CatalogSnapshotApplyPolicy.ShouldInvalidatePageRequests(hasCurrentRequestGuard: true));

    [Fact]
    public void ApplyingExternalSnapshot_InvalidatesOutstandingPageRequests() =>
        Assert.True(CatalogSnapshotApplyPolicy.ShouldInvalidatePageRequests(hasCurrentRequestGuard: false));
}
