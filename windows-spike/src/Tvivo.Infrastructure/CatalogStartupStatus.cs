namespace Tvivo.Infrastructure;

public static class CatalogStartupStatus
{
    public static string Resolve(bool checkingAccount, bool fetching, DateTimeOffset? lastRefresh, DateTimeOffset now)
    {
        if (checkingAccount) return "Checking account…";
        if (fetching) return "Updating...";
        return FormatUpdated(lastRefresh, now);
    }

    public static string FormatUpdated(DateTimeOffset? refreshed, DateTimeOffset now)
    {
        if (refreshed is null) return "Updated never";
        var age = now - refreshed.Value;
        if (age < TimeSpan.FromMinutes(1)) return "Updated just now";
        if (age < TimeSpan.FromHours(1)) return $"Updated {(int)age.TotalMinutes} min ago";
        if (age < TimeSpan.FromDays(1)) return $"Updated {(int)age.TotalHours} hr ago";
        return $"Updated {(int)age.TotalDays} days ago";
    }
}
