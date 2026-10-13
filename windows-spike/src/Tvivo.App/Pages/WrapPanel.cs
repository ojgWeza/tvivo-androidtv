using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace Tvivo.App.Pages;

/// <summary>
/// Lays children out left to right and wraps them onto new rows, so a long list of chips stays readable
/// without a sideways scrollbar. <see cref="MaxRows"/> (0 = no limit) hides whatever would fall below that row.
/// </summary>
public sealed class WrapPanel : Panel
{
    private const double Spacing = 8;
    private readonly List<Rect?> _slots = new();

    public static readonly DependencyProperty MaxRowsProperty = DependencyProperty.Register(
        nameof(MaxRows), typeof(int), typeof(WrapPanel),
        new PropertyMetadata(0, (d, _) => ((WrapPanel)d).InvalidateMeasure()));

    public int MaxRows
    {
        get => (int)GetValue(MaxRowsProperty);
        set => SetValue(MaxRowsProperty, value);
    }

    /// <summary>How many children did not fit within <see cref="MaxRows"/> on the last layout.</summary>
    public int HiddenCount { get; private set; }

    public event EventHandler? HiddenCountChanged;

    protected override Size MeasureOverride(Size availableSize)
    {
        _slots.Clear();
        var limit = double.IsInfinity(availableSize.Width) ? double.PositiveInfinity : availableSize.Width;
        double x = 0, y = 0, rowHeight = 0, widest = 0;
        var row = 1;
        var hidden = 0;
        foreach (var child in Children)
        {
            child.Measure(new Size(limit, double.PositiveInfinity));
            var size = child.DesiredSize;
            if (x > 0 && x + size.Width > limit)
            {
                y += rowHeight + Spacing;
                x = 0;
                rowHeight = 0;
                row++;
            }
            if (MaxRows > 0 && row > MaxRows)
            {
                _slots.Add(null);
                hidden++;
                continue;
            }
            _slots.Add(new Rect(x, y, size.Width, size.Height));
            x += size.Width + Spacing;
            rowHeight = Math.Max(rowHeight, size.Height);
            widest = Math.Max(widest, x - Spacing);
        }
        if (hidden != HiddenCount)
        {
            HiddenCount = hidden;
            // Raised after layout: changing other controls while measuring would re-enter layout.
            DispatcherQueue.TryEnqueue(() => HiddenCountChanged?.Invoke(this, EventArgs.Empty));
        }
        return new Size(double.IsInfinity(limit) ? widest : Math.Min(limit, widest), y + rowHeight);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        for (var index = 0; index < Children.Count; index++)
        {
            var slot = index < _slots.Count ? _slots[index] : null;
            // Hidden chips get an empty rect (not Visibility.Collapsed, which would change their measured size
            // and make the layout oscillate).
            Children[index].Arrange(slot ?? new Rect(0, 0, 0, 0));
        }
        return finalSize;
    }
}
