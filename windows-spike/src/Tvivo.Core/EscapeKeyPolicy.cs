namespace Tvivo.Core;

public enum CinemaEscapeSetting { NormalPlayer, MiniPlayer }
public enum EscapeKeyAction { None, ExitCinemaToPlayer, ExitCinemaToMiniPlayer }

public static class EscapeKeyPolicy
{
    public static EscapeKeyAction GetAction(PlayerPresentation presentation, CinemaEscapeSetting setting)
    {
        if (presentation != PlayerPresentation.Cinema) return EscapeKeyAction.None;
        return setting == CinemaEscapeSetting.MiniPlayer
            ? EscapeKeyAction.ExitCinemaToMiniPlayer
            : EscapeKeyAction.ExitCinemaToPlayer;
    }
}
