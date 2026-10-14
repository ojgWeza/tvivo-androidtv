namespace Tvivo.Core;

/// <summary>Decides when cinema playback chrome can be hidden after pointer inactivity.</summary>
public static class CinemaIdlePolicy
{
    public static readonly TimeSpan HideDelay = TimeSpan.FromSeconds(3);

    public static bool ShouldHideControls(
        TimeSpan idleFor,
        bool isPlaying,
        bool flyoutOpen,
        bool dragging,
        bool keyboardInteractionActive) =>
        isPlaying && idleFor >= HideDelay && !flyoutOpen && !dragging && !keyboardInteractionActive;
}
