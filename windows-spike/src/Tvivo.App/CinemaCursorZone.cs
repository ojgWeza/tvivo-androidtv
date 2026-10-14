using Microsoft.UI.Input;
using Microsoft.UI.Xaml.Controls;

namespace Tvivo.App;

/// <summary>Owns the pointer cursor over the video island while cinema controls are hidden.</summary>
public sealed class CinemaCursorZone : Grid
{
    private static readonly InputDesktopResourceCursor TransparentCursor =
        InputDesktopResourceCursor.CreateFromModule(Path.Combine(AppContext.BaseDirectory, "Assets", "TvivoCursors.dll"), 101);
    private InputCursor? _previousCursor;
    private bool _savedCursor;

    public void SetCinemaCursorHidden(bool hidden)
    {
        if (hidden)
        {
            if (!_savedCursor)
            {
                _previousCursor = ProtectedCursor;
                _savedCursor = true;
            }
            ProtectedCursor = TransparentCursor;
            IsHitTestVisible = true;
            return;
        }

        if (_savedCursor)
            ProtectedCursor = _previousCursor;
        _previousCursor = null;
        _savedCursor = false;
        IsHitTestVisible = false;
    }
}
