using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SpotlightSnapshotRevisionPolicyTests
{
    [Theory]
    [InlineData(0, 0, true)]
    [InlineData(1, 1, true)]
    [InlineData(0, 1, false)]
    [InlineData(2, 3, false)]
    public void Cached_or_in_flight_snapshot_is_usable_only_at_current_revision(
        long snapshot, long settings, bool expected) =>
        Assert.Equal(expected, SpotlightSnapshotRevisionPolicy.IsCurrent(snapshot, settings));
}
