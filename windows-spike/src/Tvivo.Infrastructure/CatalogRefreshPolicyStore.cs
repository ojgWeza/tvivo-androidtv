using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public enum CatalogRefreshPolicy { OnDemandOnly, OlderThanOneDay, OlderThanSevenDays, EveryStart }

public sealed class CatalogRefreshPolicyStore
{
    private readonly string _path;
    private readonly Action<string, string> _replace;

    public CatalogRefreshPolicyStore(string? path = null, Action<string, string>? replace = null)
    {
        _path = path ?? TvivoDataPaths.For("catalog-options.json");
        _replace = replace ?? ((temporary, destination) => File.Move(temporary, destination, true));
    }

    public CatalogRefreshPolicy Read()
    {
        if (!File.Exists(_path)) return CatalogRefreshPolicy.OlderThanOneDay;
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(_path));
            var value = document.RootElement.GetProperty("refreshPolicy").GetString();
            return Enum.TryParse<CatalogRefreshPolicy>(value, out var policy) && Enum.IsDefined(policy)
                ? policy : CatalogRefreshPolicy.OlderThanOneDay;
        }
        catch (JsonException) { return CatalogRefreshPolicy.OlderThanOneDay; }
        catch (KeyNotFoundException) { return CatalogRefreshPolicy.OlderThanOneDay; }
        catch (InvalidOperationException) { return CatalogRefreshPolicy.OlderThanOneDay; }
    }

    public void Write(CatalogRefreshPolicy policy)
    {
        if (!Enum.IsDefined(policy)) throw new ArgumentOutOfRangeException(nameof(policy));
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(new { refreshPolicy = policy.ToString() }));
            _replace(temporary, _path);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public static bool IsDue(CatalogRefreshPolicy policy, DateTimeOffset? lastSuccess, DateTimeOffset now, bool refreshedThisStart = false)
    {
        if (lastSuccess is null) return true;
        return policy switch
        {
            CatalogRefreshPolicy.OnDemandOnly => false,
            CatalogRefreshPolicy.OlderThanOneDay => now - lastSuccess.Value >= TimeSpan.FromDays(1),
            CatalogRefreshPolicy.OlderThanSevenDays => now - lastSuccess.Value >= TimeSpan.FromDays(7),
            CatalogRefreshPolicy.EveryStart => !refreshedThisStart,
            _ => false,
        };
    }
}

public sealed class CatalogRefreshRetry
{
    private readonly object _gate = new();
    private readonly Dictionary<(string AccountId, CatalogItemType Type), (int Failures, DateTimeOffset NextAttempt)> _failures = new();

    public bool CanAttempt(string accountId, CatalogItemType type, DateTimeOffset now)
    {
        lock (_gate)
            return !_failures.TryGetValue((accountId, type), out var state) || now >= state.NextAttempt;
    }

    public DateTimeOffset RecordFailure(string accountId, CatalogItemType type, DateTimeOffset now)
    {
        lock (_gate)
        {
            var key = (accountId, type);
            var failures = _failures.TryGetValue(key, out var previous) ? previous.Failures + 1 : 1;
            var delay = failures switch { 1 => TimeSpan.FromSeconds(20), 2 => TimeSpan.FromSeconds(60), _ => TimeSpan.FromHours(1) };
            var nextAttempt = now + delay;
            _failures[key] = (failures, nextAttempt);
            return nextAttempt;
        }
    }

    public void RecordSuccess(string accountId, CatalogItemType type)
    {
        lock (_gate)
            _failures.Remove((accountId, type));
    }
}
