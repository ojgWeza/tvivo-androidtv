using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlayerResetPolicyTests
{
    [Theory]
    [InlineData(PlayerResetEntryKind.FreshStart)]
    [InlineData(PlayerResetEntryKind.Retry)]
    [InlineData(PlayerResetEntryKind.EngineFallback)]
    [InlineData(PlayerResetEntryKind.NewItem)]
    [InlineData(PlayerResetEntryKind.AutoplayNext)]
    public void Fresh_playback_entries_require_a_reset(PlayerResetEntryKind entryKind) =>
        Assert.True(PlayerResetPolicy.RequiresReset(entryKind));

    [Fact]
    public void Same_media_recovery_keeps_the_current_frame() =>
        Assert.False(PlayerResetPolicy.RequiresReset(PlayerResetEntryKind.SameMediaRecovery));

    [Fact]
    public void Final_failure_never_reveals_a_previous_picture()
    {
        Assert.False(PlayerResetPolicy.RequiresReset(PlayerResetEntryKind.FinalFailure));
        Assert.False(PlayerResetPolicy.ShouldRevealAfterFinalFailure());
    }

    [Theory]
    [InlineData(PlayerLoadingArtState.Loading, true)]
    [InlineData(PlayerLoadingArtState.Retrying, true)]
    [InlineData(PlayerLoadingArtState.FirstFrame, false)]
    [InlineData(PlayerLoadingArtState.FinalFailure, false)]
    [InlineData(PlayerLoadingArtState.KeepFrameRecovery, false)]
    public void Loading_art_is_only_shown_while_opening_new_media(
        PlayerLoadingArtState state,
        bool expected) =>
        Assert.Equal(expected, PlayerResetPolicy.ShowsLoadingArt(state));
}
