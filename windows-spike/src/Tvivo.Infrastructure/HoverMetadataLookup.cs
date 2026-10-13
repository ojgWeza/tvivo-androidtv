using Tvivo.Core;

namespace Tvivo.Infrastructure;

/// <summary>Loads browse metadata from the account-scoped cache and serializes provider lookups.</summary>
public sealed class HoverMetadataLookup
{
    private static readonly TimeSpan FailureCooldown = TimeSpan.FromSeconds(30);
    private readonly ICatalogProvider _provider;
    private readonly Func<ProviderAccount, CatalogItemType, string, CatalogMetadata?> _getMetadata;
    private readonly Action<ProviderAccount, CatalogItemType, string, CatalogMetadata> _saveMetadata;
    private readonly TimeProvider _clock;
    private readonly SemaphoreSlim _requestGate = new(1, 1);
    private readonly object _failureLock = new();
    private readonly Dictionary<LookupKey, DateTimeOffset> _failedUntil = new();

    public HoverMetadataLookup(ICatalogProvider provider, SqliteCatalogRepository repository,
        TimeProvider? clock = null)
        : this(provider, repository.GetMetadata, repository.SaveMetadata, clock)
    {
    }

    public HoverMetadataLookup(ICatalogProvider provider,
        Func<ProviderAccount, CatalogItemType, string, CatalogMetadata?> getMetadata,
        Action<ProviderAccount, CatalogItemType, string, CatalogMetadata> saveMetadata,
        TimeProvider? clock = null)
    {
        _provider = provider;
        _getMetadata = getMetadata;
        _saveMetadata = saveMetadata;
        _clock = clock ?? TimeProvider.System;
    }

    public async Task<CatalogMetadata?> GetCachedAsync(ProviderAccount account, CatalogItemType type,
        string itemId, CancellationToken cancellationToken = default)
    {
        if (type == CatalogItemType.Live) return null;
        cancellationToken.ThrowIfCancellationRequested();
        return await Task.Run(() => _getMetadata(account, type, itemId), cancellationToken).ConfigureAwait(false);
    }

    public async Task<CatalogMetadata?> GetAsync(ProviderAccount account, CatalogItemType type,
        string itemId, CancellationToken cancellationToken = default)
    {
        if (type == CatalogItemType.Live) return null;
        cancellationToken.ThrowIfCancellationRequested();

        var key = new LookupKey(account.AccountId, type, itemId);
        CatalogMetadata? cached;
        try
        {
            cached = await GetCachedAsync(account, type, itemId, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            SetFailureCooldown(key);
            return null;
        }
        if (cached is not null) return cached;

        if (IsCoolingDown(key)) return null;

        await _requestGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cached = await GetCachedAsync(account, type, itemId, cancellationToken).ConfigureAwait(false);
            if (cached is not null) return cached;
            if (IsCoolingDown(key)) return null;

            CatalogMetadata? metadata = type switch
            {
                CatalogItemType.Movie when _provider is IMovieInfoProvider movieProvider =>
                    (await movieProvider.GetMovieInfoAsync(account, itemId, cancellationToken).ConfigureAwait(false)).Metadata,
                CatalogItemType.Series when _provider is ISeriesCatalogProvider seriesProvider =>
                    (await seriesProvider.GetSeriesInfoAsync(account, itemId, cancellationToken).ConfigureAwait(false)).Metadata,
                _ => null,
            };

            if (metadata is null)
            {
                SetFailureCooldown(key);
                return null;
            }

            await Task.Run(() => _saveMetadata(account, type, itemId, metadata), cancellationToken)
                .ConfigureAwait(false);
            ClearFailureCooldown(key);
            return metadata;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            SetFailureCooldown(key);
            return null;
        }
        finally
        {
            _requestGate.Release();
        }
    }

    private bool IsCoolingDown(LookupKey key)
    {
        lock (_failureLock)
        {
            if (!_failedUntil.TryGetValue(key, out var failedUntil)) return false;
            if (_clock.GetUtcNow() < failedUntil) return true;
            _failedUntil.Remove(key);
            return false;
        }
    }

    private void SetFailureCooldown(LookupKey key)
    {
        lock (_failureLock)
        {
            var now = _clock.GetUtcNow();
            foreach (var expired in _failedUntil.Where(pair => pair.Value <= now).Select(pair => pair.Key).ToArray())
                _failedUntil.Remove(expired);
            _failedUntil[key] = now + FailureCooldown;
        }
    }

    private void ClearFailureCooldown(LookupKey key)
    {
        lock (_failureLock) _failedUntil.Remove(key);
    }

    private readonly record struct LookupKey(string AccountId, CatalogItemType Type, string ItemId);
}
