using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshTasks(Func<ProviderAccount, CatalogItemType, CancellationToken, Task<bool>> refresh)
{
    private readonly object _gate = new();
    private readonly Dictionary<(string AccountId, CatalogItemType Type), (Task<bool> Task, CancellationToken OwnerToken)> _active = new();

    public Task<bool> RefreshTypeAsync(ProviderAccount account, CatalogItemType type,
        CancellationToken cancellationToken = default)
    {
        var key = (account.AccountId, type);
        lock (_gate)
        {
            if (_active.TryGetValue(key, out var pending) && !pending.Task.IsCompleted &&
                !pending.OwnerToken.IsCancellationRequested) return pending.Task;
            var task = refresh(account, type, cancellationToken);
            _active[key] = (task, cancellationToken);
            _ = task.ContinueWith(_ =>
            {
                lock (_gate)
                    if (_active.TryGetValue(key, out var current) && ReferenceEquals(current.Task, task))
                        _active.Remove(key);
            }, TaskScheduler.Default);
            return task;
        }
    }

    public bool TryGetActive(string accountId, CatalogItemType type, out Task? task)
    {
        lock (_gate)
        {
            var found = _active.TryGetValue((accountId, type), out var pending) &&
                !pending.Task.IsCompleted && !pending.OwnerToken.IsCancellationRequested;
            task = found ? pending.Task : null;
            return found;
        }
    }
}
