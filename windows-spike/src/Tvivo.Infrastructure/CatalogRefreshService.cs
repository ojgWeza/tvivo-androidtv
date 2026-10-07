using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshService(ICatalogProvider provider, SqliteCatalogRepository repository)
{
    public Task RefreshAsync(ProviderAccount account, CancellationToken cancellationToken = default) =>
        RefreshAllAsync(account, cancellationToken);

    public async Task RefreshAllAsync(ProviderAccount account, CancellationToken cancellationToken = default)
    {
        foreach (var type in new[] { CatalogItemType.Live, CatalogItemType.Movie, CatalogItemType.Series })
            await RefreshTypeAsync(account, type, cancellationToken);
    }

    public async Task<bool> RefreshTypeAsync(ProviderAccount account, CatalogItemType type, CancellationToken cancellationToken = default)
    {
        IReadOnlyList<ChannelGroup> groups;
        try
        {
            groups = await provider.GetChannelGroupsAsync(account, type, cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new InvalidOperationException($"Refreshing {type} categories failed: {exception.Message}", exception);
        }

        IReadOnlyList<Channel> channels;
        try
        {
            channels = await provider.GetChannelsAsync(account, type, cancellationToken: cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new InvalidOperationException($"Refreshing {type} items failed: {exception.Message}", exception);
        }

        try
        {
            return repository.ReplaceSnapshot(account, type, groups, channels);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new InvalidOperationException($"Saving refreshed {type} catalog failed: {exception.Message}", exception);
        }
    }
}
