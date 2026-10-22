using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class EscapeKeyPolicyTests
{
    [Theory]
    [InlineData(PlayerPresentation.Cinema, CinemaEscapeSetting.NormalPlayer, EscapeKeyAction.ExitCinemaToPlayer)]
    [InlineData(PlayerPresentation.Cinema, CinemaEscapeSetting.MiniPlayer, EscapeKeyAction.ExitCinemaToMiniPlayer)]
    [InlineData(PlayerPresentation.Full, CinemaEscapeSetting.NormalPlayer, EscapeKeyAction.None)]
    [InlineData(PlayerPresentation.Compact, CinemaEscapeSetting.MiniPlayer, EscapeKeyAction.None)]
    public void Maps_escape_only_for_cinema(PlayerPresentation presentation, CinemaEscapeSetting setting, EscapeKeyAction expected) =>
        Assert.Equal(expected, EscapeKeyPolicy.GetAction(presentation, setting));
}
