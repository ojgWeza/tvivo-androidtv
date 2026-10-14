using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class StartupPlanTests
{
    [Theory]
    [InlineData(false, false, StartupRoute.Setup)]
    [InlineData(false, true, StartupRoute.Setup)]
    [InlineData(true, false, StartupRoute.NetworkFirst)]
    [InlineData(true, true, StartupRoute.CachedFirst)]
    public void Decide_uses_saved_account_and_cache_presence(
        bool hasSavedAccount, bool hasCachedCatalog, StartupRoute expected)
    {
        Assert.Equal(expected, StartupPlan.Decide(hasSavedAccount, hasCachedCatalog));
    }
}
