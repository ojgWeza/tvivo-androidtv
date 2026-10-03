using Microsoft.UI.Input;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Tvivo.Core;

namespace Tvivo.App;

public enum CompactResizeCursor
{
    SizeWestEast,
    SizeNorthSouth,
    SizeNorthwestSoutheast,
    SizeNortheastSouthwest,
}

public sealed class CompactResizeZone : Grid
{
    public CompactResizeEdges Edges { get; set; }
    public CompactResizeCursor CursorShape { get; set; }

    public CompactResizeZone()
    {
        Loaded += (_, _) =>
        {
            var cursorShape = CursorShape switch
            {
                CompactResizeCursor.SizeWestEast => InputSystemCursorShape.SizeWestEast,
                CompactResizeCursor.SizeNorthSouth => InputSystemCursorShape.SizeNorthSouth,
                CompactResizeCursor.SizeNortheastSouthwest => InputSystemCursorShape.SizeNortheastSouthwest,
                _ => InputSystemCursorShape.SizeNorthwestSoutheast,
            };
            ProtectedCursor = InputSystemCursor.Create(cursorShape);
        };
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new CompactResizeZoneAutomationPeer(this);

    private sealed class CompactResizeZoneAutomationPeer(CompactResizeZone owner) : FrameworkElementAutomationPeer(owner)
    {
        protected override string GetClassNameCore() => nameof(CompactResizeZone);
        protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Thumb;
    }
}
