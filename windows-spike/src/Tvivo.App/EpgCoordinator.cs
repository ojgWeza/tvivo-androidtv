using Microsoft.UI.Dispatching;
using Tvivo.Core;
using Tvivo.Infrastructure;

namespace Tvivo.App;

public sealed class EpgCoordinator : IDisposable
{
    private readonly EpgRepository _repository;
    private readonly EpgRefreshService _refreshService;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private readonly DispatcherQueue? _dispatcherQueue;
    private Func<bool> _playbackBusy = static () => false;

    public EpgCoordinator(IEpgProvider provider)
    {
        _repository = new EpgRepository();
        _refreshService = new EpgRefreshService(provider, _repository, () => PlaybackBusy());
        _dispatcherQueue = DispatcherQueue.GetForCurrentThread();
    }

    public Func<bool> PlaybackBusy
    {
        get => _playbackBusy;
        set => _playbackBusy = value ?? (static () => false);
    }

    public event EventHandler? EpgUpdated;

    public Task StartIfDueAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken = default) =>
        Task.Run(() => RefreshCoreAsync(account, connection, cancellationToken), CancellationToken.None);

    public IReadOnlyDictionary<string, EpgNowNext> GetNowNext(
        ProviderAccount account,
        IEnumerable<string> epgChannelIds) =>
        _repository.GetNowNext(account, epgChannelIds.ToArray(), DateTimeOffset.UtcNow);

    private async Task RefreshCoreAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken)
    {
        var acquired = false;
        try
        {
            acquired = _refreshGate.Wait(0);
            if (!acquired) return;

            var result = await _refreshService.StartAsync(account, connection, cancellationToken).ConfigureAwait(false);
            if (result == EpgRefreshResult.Refreshed)
                RaiseEpgUpdated();
        }
        catch (Exception exception)
        {
            LaunchDiagnostics.Write($"EPG refresh failed: {exception.GetType().Name}");
        }
        finally
        {
            if (acquired) _refreshGate.Release();
        }
    }

    private void RaiseEpgUpdated()
    {
        void Raise()
        {
            foreach (var handler in EpgUpdated?.GetInvocationList().OfType<EventHandler>().ToArray()
                         ?? Array.Empty<EventHandler>())
            {
                try
                {
                    handler(this, EventArgs.Empty);
                }
                catch (Exception exception)
                {
                    LaunchDiagnostics.Write($"EPG update handler failed: {exception.GetType().Name}");
                }
            }
        }

        if (_dispatcherQueue is { HasThreadAccess: false } dispatcher)
        {
            dispatcher.TryEnqueue(Raise);
            return;
        }

        Raise();
    }

    public void Dispose()
    {
        _refreshGate.Dispose();
        _repository.Dispose();
    }
}
