namespace Tvivo.Core;

public static class SpotlightFocusPolicy
{
    public static bool ShowRing(bool pageActive, bool keyPressedSinceActivation, bool keyboardFocus) =>
        pageActive && keyPressedSinceActivation && keyboardFocus;
}
