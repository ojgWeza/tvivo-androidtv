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

    [Fact]
    public void Same_day_selection_restores_by_id_after_candidate_refresh_or_reorder()
    {
        var refreshed = new[] { "c", "a", "d", "b" };
        Assert.Equal(3, SpotlightSelection.RestoreIndex(refreshed, "b", savedIndex: 1));
        Assert.Equal(1, SpotlightSelection.RestoreIndex(refreshed, "missing", savedIndex: 5));
    }

    [Fact]
    public void Removed_selection_uses_a_stable_index_fallback_and_empty_candidates_have_no_selection()
    {
        Assert.Equal(1, SpotlightSelection.RestoreIndex(new[] { "a", "c" }, "b", savedIndex: 3));
        Assert.Equal(-1, SpotlightSelection.RestoreIndex(Array.Empty<string>(), "b", savedIndex: 3));
    }

    [Fact]
    public void Ordered_candidate_hash_changes_when_visit_exclusions_change_the_daily_snapshot()
    {
        var beforeExclusion = SpotlightSelection.OrderedIdsHash(new[] { "a", "b", "c" });
        var afterExclusion = SpotlightSelection.OrderedIdsHash(new[] { "a", "c" });
        Assert.NotEqual(beforeExclusion, afterExclusion);
        Assert.Equal(beforeExclusion, SpotlightSelection.OrderedIdsHash(new[] { "a", "b", "c" }));
    }

    [Fact]
    public void Utc_day_rollover_produces_a_new_daily_index()
    {
        var lastUtcDay = new DateOnly(2026, 10, 14);
        Assert.NotEqual(
            SpotlightSelection.Index("account-a", "Movie", lastUtcDay, 17),
            SpotlightSelection.Index("account-a", "Movie", lastUtcDay.AddDays(1), 17));
    }
}
