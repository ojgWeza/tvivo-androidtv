namespace Tvivo.Core;

/// <summary>Deterministic input and layout rules for the Spotlight catalogue strip.</summary>
public sealed record SpotlightStripState(int OpenIndex, int ItemCount)
{
    public static readonly TimeSpan DwellDuration = TimeSpan.FromMilliseconds(350);

    public static SpotlightStripState Create(int itemCount) => new(itemCount > 0 ? 0 : -1, Math.Max(0, itemCount));

    public SpotlightStripState Open(int index) => index < 0 || index >= ItemCount ? this : this with { OpenIndex = index };

    public SpotlightStripState Move(int delta) => ItemCount == 0
        ? this
        : Open(Math.Clamp((OpenIndex < 0 ? 0 : OpenIndex) + delta, 0, ItemCount - 1));

    public static bool ShouldOpenAfterDwell(int currentIndex, int targetIndex, TimeSpan elapsed,
        TimeSpan? dwell = null) => targetIndex >= 0 && targetIndex != currentIndex && elapsed >= (dwell ?? DwellDuration);

    public static double OpenWidth(double viewportWidth, int itemCount, double collapsedWidth = 64, double gap = 8)
    {
        var collapsedCount = Math.Max(0, itemCount - 1);
        return Math.Max(collapsedWidth, viewportWidth - collapsedCount * (collapsedWidth + gap));
    }

    public static double ScrollIntoViewTarget(double itemStart, double itemWidth, double viewportStart, double viewportWidth) =>
        itemStart < viewportStart ? itemStart : itemStart + itemWidth > viewportStart + viewportWidth
            ? itemStart + itemWidth - viewportWidth : viewportStart;

    public static string Announcement(string title, int index, int count) =>
        $"{title}, expanded, {index + 1} of {count}";

    public static string AutomationName(string title, bool isMovie, int index, int count, bool expanded) =>
        $"{title}, {(isMovie ? "Movie" : "Series")}, {index + 1} of {count}, {(expanded ? "expanded" : "collapsed")}";

    /// <returns>True when this tap should activate; false when it only opens.</returns>
    public bool TouchTapActivates(int index) => index == OpenIndex;

    public bool TouchTapActivates(int index, int armedTouchIndex) =>
        index == OpenIndex && index == armedTouchIndex;
}

public enum SpotlightStripInputMode { Idle, Hover, Focus, Move, Activated, Returned }

public sealed record SpotlightVisibleItem(int Index, string Id, double Width);

public sealed record SpotlightViewportLayout(double CollapsedWidth, double OpenWidth,
    double ScrollTarget, double FirstVisibleOffset, int FirstVisibleIndex, int LastVisibleIndex,
    double PredecessorPeekWidth, double NextPeekWidth, int FollowingSliverCount,
    TimeSpan SlideDuration, IReadOnlyList<SpotlightVisibleItem> ItemWidths);

/// <summary>Pure D2 viewport model. IDs remain in source order; only visible items plus one neighbor are exposed.</summary>
public sealed record SpotlightViewportState
{
    public const double Gap = 8;
    public const double MinimumCollapsedWidth = 44;
    public const double MinimumOpenWidth = 300;
    public const double PeekFraction = 0.15;
    public static readonly TimeSpan SlideDuration = TimeSpan.FromMilliseconds(200);

    public IReadOnlyList<string> ItemIds { get; private init; } = Array.Empty<string>();
    public double ViewportWidth { get; private init; }
    public bool ReducedMotion { get; private init; }
    public int SelectedIndex { get; private init; } = -1;
    public int? OpenIndex { get; private init; }
    public SpotlightStripInputMode InputMode { get; private init; }
    public string? ActivatedItemId { get; private init; }
    private double? RestoredOffset { get; init; }

    public string? SelectedItemId => SelectedIndex >= 0 ? ItemIds[SelectedIndex] : null;
    public SpotlightViewportLayout Layout => BuildLayout();

    public static SpotlightViewportState Create(IReadOnlyList<string> itemIds, double viewportWidth,
        bool reducedMotion = false)
    {
        ArgumentNullException.ThrowIfNull(itemIds);
        if (itemIds.Any(string.IsNullOrWhiteSpace) || itemIds.Distinct(StringComparer.Ordinal).Count() != itemIds.Count)
            throw new ArgumentException("Spotlight item IDs must be non-empty and unique.", nameof(itemIds));
        return new SpotlightViewportState
        {
            ItemIds = Array.AsReadOnly(itemIds.ToArray()), ViewportWidth = Math.Max(0, viewportWidth),
            ReducedMotion = reducedMotion, SelectedIndex = itemIds.Count == 0 ? -1 : 0,
        };
    }

    public SpotlightViewportState Idle() => this with
    {
        OpenIndex = null, InputMode = SpotlightStripInputMode.Idle,
        ActivatedItemId = null, RestoredOffset = null,
    };

    public SpotlightViewportState HoverAfterDwell(int index, TimeSpan elapsed) =>
        elapsed >= SpotlightStripState.DwellDuration ? Open(index, SpotlightStripInputMode.Hover) : this;

    public SpotlightViewportState Focus(int index) => Open(index, SpotlightStripInputMode.Focus);

    public SpotlightViewportState Move(int delta) => ItemIds.Count == 0 ? this :
        Open(Math.Clamp(SelectedIndex + delta, 0, ItemIds.Count - 1), SpotlightStripInputMode.Move);

    public SpotlightViewportState Activate() => SelectedIndex < 0 ? this : this with
    {
        ActivatedItemId = SelectedItemId, InputMode = SpotlightStripInputMode.Activated,
    };

    public SpotlightViewportState ReturnFromPlayer(string itemId, double scrollOffset)
    {
        if (itemId != SelectedItemId || OpenIndex != SelectedIndex) return this;
        return this with
        {
            InputMode = SpotlightStripInputMode.Returned, ActivatedItemId = null,
            RestoredOffset = Math.Max(0, scrollOffset),
        };
    }

    public SpotlightViewportState Resize(double viewportWidth, bool reducedMotion) => this with
    {
        ViewportWidth = Math.Max(0, viewportWidth), ReducedMotion = reducedMotion,
    };

    private SpotlightViewportState Open(int index, SpotlightStripInputMode mode) =>
        index < 0 || index >= ItemIds.Count ? this : index == SelectedIndex && OpenIndex == index
            ? this with { InputMode = mode } : this with
        {
            SelectedIndex = index, OpenIndex = index, InputMode = mode,
            ActivatedItemId = null, RestoredOffset = null,
        };

    private SpotlightViewportLayout BuildLayout()
    {
        var viewport = ViewportWidth;
        var fullCount = 7;
        while (fullCount > 0 && (viewport - fullCount * Gap) / (fullCount + 0.3) < MinimumCollapsedWidth)
            fullCount--;
        var collapsed = Math.Max(MinimumCollapsedWidth, (viewport - fullCount * Gap) / (fullCount + 0.3));
        var open = OpenIndex;
        var predecessor = open is > 0 ? PeekFraction * collapsed : 0;
        var followers = open is int selected ? Math.Min(3, ItemIds.Count - selected - 1) : 0;
        var next = open is int position && position + followers + 1 < ItemIds.Count;
        double OpenWidthFor(int count, bool hasNext) => viewport - predecessor - count * collapsed -
            (hasNext ? PeekFraction * collapsed : 0) - ((predecessor > 0 ? 1 : 0) + count + (hasNext ? 1 : 0)) * Gap;
        if (open is not null)
        {
            while (followers > 0 && OpenWidthFor(followers, next) < MinimumOpenWidth)
            {
                followers--;
                next = open.Value + followers + 1 < ItemIds.Count;
            }
            if (OpenWidthFor(followers, next) < MinimumOpenWidth) next = false;
        }
        var openWidth = open is null ? 0 : Math.Max(0, OpenWidthFor(followers, next));
        var nextPeek = next ? PeekFraction * collapsed : 0;
        var totalWidth = ItemIds.Count * collapsed + Math.Max(0, ItemIds.Count - 1) * Gap +
            (open is null ? 0 : openWidth - collapsed);
        var desired = open is int index ? index * (collapsed + Gap) - predecessor - (predecessor > 0 ? Gap : 0)
            : SelectedIndex * (collapsed + Gap);
        var offset = Math.Clamp(RestoredOffset ?? desired, 0, Math.Max(0, totalWidth - viewport));
        var visibleFirst = -1;
        var visibleLast = -1;
        var start = 0.0;
        for (var i = 0; i < ItemIds.Count; i++)
        {
            var width = i == open ? openWidth : collapsed;
            if (start + width > offset && start < offset + viewport)
            {
                if (visibleFirst < 0) visibleFirst = i;
                visibleLast = i;
            }
            start += width + Gap;
        }
        var items = visibleFirst < 0 ? Array.Empty<SpotlightVisibleItem>() :
            Enumerable.Range(Math.Max(0, visibleFirst - 1), Math.Min(ItemIds.Count - Math.Max(0, visibleFirst - 1),
                visibleLast - Math.Max(0, visibleFirst - 1) + 2))
                .Select(i => new SpotlightVisibleItem(i, ItemIds[i], i == open ? openWidth : collapsed)).ToArray();
        return new SpotlightViewportLayout(collapsed, openWidth, offset, offset,
            visibleFirst, visibleLast, predecessor, nextPeek, followers,
            ReducedMotion ? TimeSpan.Zero : SlideDuration, items);
    }
}

public static class SpotlightMetadataOwnership
{
    public static bool CanApply(long requestedRender, long currentRender,
        bool sameCard, bool sameChannel, bool cancellationRequested) =>
        !cancellationRequested && requestedRender == currentRender && sameCard && sameChannel;
}
