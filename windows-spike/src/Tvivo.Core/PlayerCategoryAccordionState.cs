namespace Tvivo.Core;

public enum PlayerCategoryViewMode { Categories, SeriesEpisodes }

public readonly record struct PlayerCategoryTransition(string? HeaderToFocus);

/// <summary>Pure player sidebar state. The caller applies focus and starts page requests.</summary>
public sealed class PlayerCategoryAccordionState
{
    public const int PageCapacity = 3;
    private readonly Dictionary<(string CategoryId, int Offset), IReadOnlyList<Channel>> _pages = new();
    private readonly LinkedList<(string CategoryId, int Offset)> _pageOrder = new();
    private string? _accountId;

    public PlayerCategoryAccordionState(string? accountId, CatalogItemType contentType)
    {
        ValidateType(contentType);
        _accountId = accountId;
        ContentType = contentType;
    }

    public CatalogItemType ContentType { get; private set; }
    public string? ExpandedCategoryId { get; private set; }
    public string? SelectedItemId { get; private set; }
    public long PageGeneration { get; private set; }
    public PlayerCategoryViewMode ViewMode { get; private set; } = PlayerCategoryViewMode.Categories;
    public int CachedPageCount => _pages.Count;

    public PlayerCategoryTransition SetContext(string? accountId, CatalogItemType contentType)
    {
        ValidateType(contentType);
        if (string.Equals(_accountId, accountId, StringComparison.Ordinal) && ContentType == contentType)
            return new(ExpandedCategoryId);
        _accountId = accountId;
        ContentType = contentType;
        ExpandedCategoryId = null;
        SelectedItemId = null;
        ViewMode = PlayerCategoryViewMode.Categories;
        InvalidatePages();
        return new(null);
    }

    public PlayerCategoryTransition Toggle(string categoryId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(categoryId);
        ExpandedCategoryId = string.Equals(ExpandedCategoryId, categoryId, StringComparison.Ordinal)
            ? null : categoryId;
        SelectedItemId = null;
        ViewMode = PlayerCategoryViewMode.Categories;
        PageGeneration++;
        return new(categoryId);
    }

    public PlayerCategoryTransition OpenForPlaying(string? categoryId, string? itemId)
    {
        if (ViewMode != PlayerCategoryViewMode.Categories)
        {
            ViewMode = PlayerCategoryViewMode.Categories;
            PageGeneration++;
        }
        if (string.IsNullOrWhiteSpace(categoryId))
        {
            if (ExpandedCategoryId is not null) PageGeneration++;
            ExpandedCategoryId = null;
            SelectedItemId = itemId;
            return new(null);
        }
        if (!string.Equals(ExpandedCategoryId, categoryId, StringComparison.Ordinal))
        {
            ExpandedCategoryId = categoryId;
            PageGeneration++;
        }
        SelectedItemId = itemId;
        return new(categoryId);
    }

    public PlayerCategoryTransition SelectCategory(string categoryId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(categoryId);
        if (!string.Equals(ExpandedCategoryId, categoryId, StringComparison.Ordinal))
            return Toggle(categoryId);
        return new(categoryId);
    }

    public IReadOnlyList<string> OrderedCategoryIds(IEnumerable<string> providerOrder)
    {
        var ids = providerOrder.ToArray();
        return ExpandedCategoryId is { } open && ids.Contains(open, StringComparer.Ordinal)
            ? new[] { open }.Concat(ids.Where(id => !string.Equals(id, open, StringComparison.Ordinal))).ToArray()
            : ids;
    }

    public void SelectItem(string itemId)
    {
        if (ExpandedCategoryId is null) throw new InvalidOperationException("Open a category before selecting an item.");
        ArgumentException.ThrowIfNullOrWhiteSpace(itemId);
        SelectedItemId = itemId;
    }

    public void EnterSeriesEpisodes()
    {
        if (ContentType != CatalogItemType.Series || SelectedItemId is null)
            throw new InvalidOperationException("Select a series before opening its episodes.");
        ViewMode = PlayerCategoryViewMode.SeriesEpisodes;
        PageGeneration++;
    }

    public PlayerCategoryTransition ReturnToCategories()
    {
        ViewMode = PlayerCategoryViewMode.Categories;
        PageGeneration++;
        return new(ExpandedCategoryId);
    }

    public PlayerCategoryTransition Exit()
    {
        ExpandedCategoryId = null;
        SelectedItemId = null;
        ViewMode = PlayerCategoryViewMode.Categories;
        InvalidatePages();
        return new(null);
    }

    public bool TryAcceptPage(long generation, string categoryId, int offset, IReadOnlyList<Channel> items)
    {
        if (generation != PageGeneration ||
            !string.Equals(ExpandedCategoryId, categoryId, StringComparison.Ordinal)) return false;
        ArgumentNullException.ThrowIfNull(items);
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (items.Count > 100) throw new ArgumentOutOfRangeException(nameof(items));
        var key = (categoryId, offset);
        if (_pages.ContainsKey(key)) _pageOrder.Remove(key);
        _pages[key] = items.ToArray();
        _pageOrder.AddLast(key);
        if (_pages.Count > PageCapacity)
        {
            var oldest = _pageOrder.First!.Value;
            _pageOrder.RemoveFirst();
            _pages.Remove(oldest);
        }
        return true;
    }

    public bool TryGetPage(string categoryId, int offset, out IReadOnlyList<Channel>? items)
    {
        var key = (categoryId, offset);
        if (!_pages.TryGetValue(key, out items)) return false;
        _pageOrder.Remove(key);
        _pageOrder.AddLast(key);
        return true;
    }

    private void InvalidatePages()
    {
        PageGeneration++;
        _pages.Clear();
        _pageOrder.Clear();
    }

    private static void ValidateType(CatalogItemType type)
    {
        if (type is not (CatalogItemType.Movie or CatalogItemType.Series))
            throw new ArgumentOutOfRangeException(nameof(type));
    }
}
