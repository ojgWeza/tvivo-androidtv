namespace Tvivo.Core;

public readonly record struct CatalogPageRevision(
    long Catalog, long Spotlight, long Selection, long Activity, long Favorites);

/// <summary>Retains at most three rendered modes for one account. All calls are made on the UI thread.</summary>
public sealed class CatalogPageCachePolicy<TPage> where TPage : class
{
    public const int Capacity = 3;

    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly LinkedList<string> _lru = new();
    private string? _accountId;
    private CatalogPageRevision? _revision;
    private long _generation;

    public int Count => _entries.Count;

    public void SetAccount(string? accountId)
    {
        if (string.Equals(_accountId, accountId, StringComparison.Ordinal)) return;
        Clear();
        _accountId = accountId;
    }

    public void Clear()
    {
        _generation++;
        _entries.Clear();
        _lru.Clear();
        _accountId = null;
        _revision = null;
    }

    public void Invalidate() { _generation++; _entries.Clear(); _lru.Clear(); }

    public long BeginRevalidation() => ++_generation;

    public bool IsCurrent(long generation, string accountId, CatalogPageRevision revision) =>
        generation == _generation && string.Equals(_accountId, accountId, StringComparison.Ordinal) &&
        _entries.Values.All(entry => entry.Revision == revision);

    public bool TryGet(string accountId, string mode, CatalogPageRevision revision, out TPage? page)
    {
        page = null;
        if (!string.Equals(_accountId, accountId, StringComparison.Ordinal)) return false;
        if (!_entries.TryGetValue(mode, out var entry)) return false;
        if (entry.Revision != revision)
        {
            if (!IsOlder(revision, entry.Revision)) Invalidate();
            return false;
        }
        _lru.Remove(entry.Node);
        _lru.AddLast(entry.Node);
        page = entry.Page;
        return true;
    }

    public bool Store(string accountId, string mode, CatalogPageRevision revision, TPage page,
        long? revalidationGeneration = null)
    {
        if (!string.Equals(_accountId, accountId, StringComparison.Ordinal) ||
            revalidationGeneration is { } generation && generation != _generation)
            return false;
        if (_revision is { } current && current != revision)
        {
            if (IsOlder(revision, current)) return false;
            Invalidate();
        }
        _revision = revision;
        if (_entries.Remove(mode, out var old)) _lru.Remove(old.Node);
        var node = _lru.AddLast(mode);
        _entries.Add(mode, new Entry(revision, page, node));
        if (_entries.Count > Capacity)
        {
            var oldest = _lru.First!;
            _entries.Remove(oldest.Value);
            _lru.RemoveFirst();
        }
        return true;
    }

    private sealed record Entry(CatalogPageRevision Revision, TPage Page, LinkedListNode<string> Node);

    private static bool IsOlder(CatalogPageRevision candidate, CatalogPageRevision current) =>
        candidate.Catalog < current.Catalog || candidate.Spotlight < current.Spotlight ||
        candidate.Selection < current.Selection || candidate.Activity < current.Activity ||
        candidate.Favorites < current.Favorites;
}
