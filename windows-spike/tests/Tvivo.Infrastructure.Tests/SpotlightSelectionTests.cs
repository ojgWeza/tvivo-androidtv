using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class SpotlightSelectionTests
{
    [Fact]
    public void Selection_is_stable_within_the_utc_day_and_rotates_on_the_next_day()
    {
        var today = new DateOnly(2026, 10, 14);

        var first = SpotlightSelection.Index("account-a", "Movie", today, 12);

        Assert.Equal(first, SpotlightSelection.Index("account-a", "Movie", today, 12));
        Assert.NotEqual(first, SpotlightSelection.Index("account-a", "Movie", today.AddDays(1), 12));
    }

    [Fact]
    public void Selection_isolated_by_account_and_content_type_and_sparse_results_use_fallback()
    {
        var today = new DateOnly(2026, 10, 14);
        var account = SpotlightSelection.Index("account-a", "Movie", today, 19);

        Assert.NotEqual(account, SpotlightSelection.Index("account-b", "Movie", today, 19));
        Assert.NotEqual(account, SpotlightSelection.Index("account-a", "Series", today, 19));
        Assert.False(SpotlightSelection.ShouldUseDiscovery(2));
        Assert.True(SpotlightSelection.ShouldUseDiscovery(3));
        Assert.Equal(-1, SpotlightSelection.Index("account-a", "Movie", today, 0));
    }
}
