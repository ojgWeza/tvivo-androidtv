using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshService(ICatalogProvider provider, SqliteCatalogRepository repository)
{
    public async Task RefreshAsync(ProviderAccount account, CancellationToken cancellationToken = default)
    {
        foreach (var type in new[] { CatalogItemType.Live, CatalogItemType.Movie, CatalogItemType.Series })
        {
            var groups = await provider.GetChannelGroupsAsync(account, type, cancellationToken);
            var channels = await provider.GetChannelsAsync(account, type, cancellationToken: cancellationToken);
            repository.ReplaceSnapshot(account, type, groups, channels);
        }
    }
}
