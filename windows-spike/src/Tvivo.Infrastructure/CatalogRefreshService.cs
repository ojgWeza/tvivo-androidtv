using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed class CatalogRefreshService(ICatalogProvider provider, SqliteCatalogRepository repository)
{
    public const int SeriesParserVersion = 2;

    public Task RefreshAsync(ProviderAccount account, CancellationToken cancellationToken = default) =>
        RefreshAllAsync(account, cancellationToken);

    public async Task RefreshAllAsync(ProviderAccount account, CancellationToken cancellationToken = default)
    {
        foreach (var type in new[] { CatalogItemType.Live, CatalogItemType.Movie, CatalogItemType.Series })
            await RefreshTypeAsync(account, type, cancellationToken);
    }

    public async Task<bool> RefreshTypeAsync(ProviderAccount account, CatalogItemType type, CancellationToken cancellationToken = default)
    {
        var artworkOnlyReingest = type == CatalogItemType.Series &&
            repository.GetParserVersion(account, type) < SeriesParserVersion && repository.HasItems(account, type);
        IReadOnlyList<ChannelGroup> groups;
        try
        {
            using var phase = PerfScope.BeginPhase("providerFetch");
            groups = await provider.GetChannelGroupsAsync(account, type, cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new CatalogFailureException(CatalogFailureClassifier.Classify(null, exception, $"{type}.categories"), exception);
        }

        IReadOnlyList<Channel> channels;
        try
        {
            using var phase = PerfScope.BeginPhase("providerFetch");
            channels = await provider.GetChannelsAsync(account, type, cancellationToken: cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new CatalogFailureException(CatalogFailureClassifier.Classify(null, exception, $"{type}.items"), exception);
        }

        try
        {
            if (artworkOnlyReingest)
                return repository.RefreshSeriesArtwork(account, channels, SeriesParserVersion) > 0;
            var replaced = repository.ReplaceSnapshot(account, type, groups, channels);
            if (type == CatalogItemType.Series && replaced)
                repository.SetParserVersion(account, type, SeriesParserVersion);
            return replaced;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            throw new CatalogFailureException(new(CatalogFailureKind.Database, $"{type}.database.save", null,
                Guid.NewGuid().ToString("N")[..12]), exception);
        }
    }
}
