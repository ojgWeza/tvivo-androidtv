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

    public static double OpenWidth(double viewportWidth) => Math.Clamp(viewportWidth * 0.42,
        viewportWidth < 776 ? 420 : 480, 672);

    public static double ScrollIntoViewTarget(double itemStart, double itemWidth, double viewportStart, double viewportWidth) =>
        itemStart < viewportStart ? itemStart : itemStart + itemWidth > viewportStart + viewportWidth
            ? itemStart + itemWidth - viewportWidth : viewportStart;

    public static string Announcement(string title, int index, int count) =>
        $"{title}, expanded, {index + 1} of {count}";

    public static string AutomationName(string title, bool isMovie, int index, int count, bool expanded) =>
        $"{title}, {(isMovie ? "Movie" : "Series")}, {index + 1} of {count}, {(expanded ? "expanded" : "collapsed")}";

    /// <returns>True when this tap should activate; false when it only opens.</returns>
    public bool TouchTapActivates(int index) => index == OpenIndex;
}
