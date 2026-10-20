using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlayerSelectionTransitionPolicyTests
{
    [Fact]
    public void Resolution_from_previous_selection_is_ignored_after_new_selection()
    {
        Assert.False(PlayerSelectionTransitionPolicy.CanApplyResolution(4, 5, cancellationRequested: false));
        Assert.False(PlayerSelectionTransitionPolicy.CanApplyResolution(5, 5, cancellationRequested: true));
        Assert.True(PlayerSelectionTransitionPolicy.CanApplyResolution(5, 5, cancellationRequested: false));
    }

    [Fact]
    public void Resolution_failure_shows_error_only_for_current_uncancelled_selection()
    {
        Assert.True(PlayerSelectionTransitionPolicy.ShouldShowResolutionError(5, 5, false, resolutionFailed: true));
        Assert.False(PlayerSelectionTransitionPolicy.ShouldShowResolutionError(4, 5, false, resolutionFailed: true));
        Assert.False(PlayerSelectionTransitionPolicy.ShouldShowResolutionError(5, 5, true, resolutionFailed: true));
        Assert.False(PlayerSelectionTransitionPolicy.ShouldShowResolutionError(5, 5, false, resolutionFailed: false));
    }
}
