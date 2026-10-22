namespace Tvivo.Core;

public enum PlayerCategoryViewMode { Categories, SeriesEpisodes }

public readonly record struct PlayerCategoryTransition(string? HeaderToFocus);

/// <summary>Pure player sidebar state. The caller applies focus and starts page requests.</summary>
public sealed class PlayerCategoryAccordionState
{
    public const int PageCapacity = 3;
    private readonly Dictionary<int, IReadOnlyList<Channel>> _pages = new();
    private readonly LinkedList<int> _pageOrder = new();
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
        InvalidatePages();
        return new(categoryId);
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
        InvalidatePages();
    }

    public PlayerCategoryTransition ReturnToCategories()
    {
        ViewMode = PlayerCategoryViewMode.Categories;
        InvalidatePages();
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
        if (generation != PageGeneration || ViewMode != PlayerCategoryViewMode.Categories ||
            !string.Equals(ExpandedCategoryId, categoryId, StringComparison.Ordinal)) return false;
        ArgumentNullException.ThrowIfNull(items);
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (items.Count > 100) throw new ArgumentOutOfRangeException(nameof(items));
        if (_pages.ContainsKey(offset)) _pageOrder.Remove(offset);
        _pages[offset] = items.ToArray();
        _pageOrder.AddLast(offset);
        if (_pages.Count > PageCapacity)
        {
            var oldest = _pageOrder.First!.Value;
            _pageOrder.RemoveFirst();
            _pages.Remove(oldest);
        }
        return true;
    }

    public bool TryGetPage(int offset, out IReadOnlyList<Channel>? items)
    {
        if (!_pages.TryGetValue(offset, out items)) return false;
        _pageOrder.Remove(offset);
        _pageOrder.AddLast(offset);
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
