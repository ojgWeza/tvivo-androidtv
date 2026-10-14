namespace Tvivo.Core;

public enum PlayerLoadingArtState
{
    Loading,
    Retrying,
    FirstFrame,
    FinalFailure,
    KeepFrameRecovery,
}

public enum PlayerResetEntryKind
{
    FreshStart,
    Retry,
    EngineFallback,
    NewItem,
    AutoplayNext,
    SameMediaRecovery,
    FinalFailure,
}

public static class PlayerResetPolicy
{
    public static bool MustShowHostUnderCurtainBeforeOpen(PlayerResetEntryKind entryKind) =>
        RequiresReset(entryKind);

    public static bool ShowsLoadingArt(PlayerLoadingArtState state) => state is
        PlayerLoadingArtState.Loading or
        PlayerLoadingArtState.Retrying;

    public static bool RequiresReset(PlayerResetEntryKind entryKind) => entryKind is
        PlayerResetEntryKind.FreshStart or
        PlayerResetEntryKind.Retry or
        PlayerResetEntryKind.EngineFallback or
        PlayerResetEntryKind.NewItem or
        PlayerResetEntryKind.AutoplayNext;

    public static bool ShouldRevealAfterFinalFailure() => false;
}
