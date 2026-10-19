using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshTasks(Func<ProviderAccount, CatalogItemType, CancellationToken, Task<bool>> refresh)
{
    private readonly object _gate = new();
    private readonly Dictionary<(string AccountId, CatalogItemType Type), Task<bool>> _active = new();

    public Task<bool> RefreshTypeAsync(ProviderAccount account, CatalogItemType type,
        CancellationToken cancellationToken = default)
    {
        var key = (account.AccountId, type);
        lock (_gate)
        {
            if (_active.TryGetValue(key, out var pending) && !pending.IsCompleted) return pending;
            var task = refresh(account, type, cancellationToken);
            _active[key] = task;
            _ = task.ContinueWith(_ =>
            {
                lock (_gate)
                    if (_active.TryGetValue(key, out var current) && ReferenceEquals(current, task))
                        _active.Remove(key);
            }, TaskScheduler.Default);
            return task;
        }
    }

    public bool TryGetActive(string accountId, CatalogItemType type, out Task? task)
    {
        lock (_gate)
        {
            var found = _active.TryGetValue((accountId, type), out var pending) && !pending.IsCompleted;
            task = found ? pending : null;
            return found;
        }
    }
}
