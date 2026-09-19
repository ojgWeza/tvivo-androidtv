using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshService(ICatalogProvider provider, SqliteCatalogRepository repository)
{
    public async Task RefreshAsync(ProviderAccount account, CancellationToken cancellationToken = default)
    {
        var groups = await provider.GetChannelGroupsAsync(account, cancellationToken);
        var channels = await provider.GetChannelsAsync(account, cancellationToken: cancellationToken);
        repository.ReplaceLiveSnapshot(account, groups, channels);
    }
}
