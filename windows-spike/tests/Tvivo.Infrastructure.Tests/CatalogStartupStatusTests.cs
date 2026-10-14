using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class CatalogStartupStatusTests
{
    [Fact]
    public void Startup_status_reflects_authentication_fetch_and_persisted_refresh_time()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);

        Assert.Equal("Checking account…", CatalogStartupStatus.Resolve(true, false, now, now));
        Assert.Equal("Updating...", CatalogStartupStatus.Resolve(false, true, now, now));
        Assert.Equal("Updated just now", CatalogStartupStatus.Resolve(false, false, now.AddSeconds(-30), now));
        Assert.Equal("Updated 2 days ago", CatalogStartupStatus.Resolve(false, false, now.AddDays(-2), now));
        Assert.Equal("Updated never", CatalogStartupStatus.Resolve(false, false, null, now));
    }

    [Fact]
    public void Cached_startup_with_fresh_stamp_keeps_persisted_status_and_skips_provider_fetch()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var stamp = now.AddHours(-2);
        var providerCalls = 0;
        var due = CatalogRefreshPolicyStore.IsDue(CatalogRefreshPolicy.OlderThanOneDay, stamp, now);
        if (due) providerCalls++;

        Assert.Equal(0, providerCalls);
        Assert.Equal("Updated 2 hr ago", CatalogStartupStatus.Resolve(false, false, stamp, now));
    }

    [Fact]
    public void Stale_missing_and_every_start_stamps_keep_their_refresh_policy_semantics()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);

        Assert.True(CatalogRefreshPolicyStore.IsDue(CatalogRefreshPolicy.OlderThanOneDay, now.AddDays(-2), now));
        Assert.True(CatalogRefreshPolicyStore.IsDue(CatalogRefreshPolicy.OlderThanOneDay, null, now));
        Assert.True(CatalogRefreshPolicyStore.IsDue(CatalogRefreshPolicy.EveryStart, now, now));
        Assert.False(CatalogRefreshPolicyStore.IsDue(CatalogRefreshPolicy.EveryStart, now, now, refreshedThisStart: true));
    }
}
