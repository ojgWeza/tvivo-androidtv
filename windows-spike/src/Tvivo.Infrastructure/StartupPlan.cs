namespace Tvivo.Infrastructure;

public enum StartupRoute
{
    Setup,
    NetworkFirst,
    CachedFirst,
}

public static class StartupPlan
{
    public static StartupRoute Decide(bool hasSavedAccount, bool hasCachedCatalog) =>
        !hasSavedAccount
            ? StartupRoute.Setup
            : hasCachedCatalog
                ? StartupRoute.CachedFirst
                : StartupRoute.NetworkFirst;
}
