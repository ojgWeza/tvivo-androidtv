using Tvivo.Core;

namespace Tvivo.Infrastructure;

/// <summary>Folder descriptors and bounded pages for the player category browser.</summary>
public sealed class PlayerCategoryBrowserService
{
    private readonly SqliteCatalogRepository _repository;
    private readonly ProviderConnection? _connection;

    public PlayerCategoryBrowserService(SqliteCatalogRepository repository, ProviderConnection? connection)
    {
        _repository = repository ?? throw new ArgumentNullException(nameof(repository));
        _connection = connection;
    }

    public IReadOnlyList<CatalogCategoryShelfDescriptor> GetFolders(ProviderAccount account, CatalogItemType type)
    {
        Validate(account, type);
        return _repository.GetCategoryShelfDescriptors(account, type);
    }

    public CatalogPage GetFolderPage(ProviderAccount account, CatalogItemType type, string categoryId, int offset, int limit)
    {
        Validate(account, type);
        ArgumentException.ThrowIfNullOrWhiteSpace(categoryId);
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (limit is < 50 or > 100) throw new ArgumentOutOfRangeException(nameof(limit));
        if (!GetFolders(account, type).Any(folder => string.Equals(folder.Group.Id, categoryId, StringComparison.Ordinal)))
            return new CatalogPage(Array.Empty<Channel>(), 0);
        return _repository.GetChannels(account, _connection, type, categoryId, offset: offset, limit: limit);
    }

    private void Validate(ProviderAccount account, CatalogItemType type)
    {
        ArgumentNullException.ThrowIfNull(account);
        if (type is not (CatalogItemType.Movie or CatalogItemType.Series))
            throw new ArgumentOutOfRangeException(nameof(type));
        if (_connection?.AccountId is { } id && !string.Equals(id, account.AccountId, StringComparison.Ordinal))
            throw new ArgumentException("Connection belongs to another account.", nameof(account));
    }
}
