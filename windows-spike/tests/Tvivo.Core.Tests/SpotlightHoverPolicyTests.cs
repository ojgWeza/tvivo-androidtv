using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SpotlightHoverPolicyTests
{
    private static SpotlightHoverPolicy.PointerPosition P(double x, double y = 10) => new(x, y);

    [Fact]
    public void Layout_rehit_and_repeated_coordinates_do_not_open_second_item()
    {
        var policy = new SpotlightHoverPolicy();
        var candidate = policy.OnPointerMoved(P(10), "B", "A", 100)!.Value;
        Assert.False(policy.CanComplete(candidate, 1, 1, "B", P(10), true, false, 449));
        Assert.True(policy.CanComplete(candidate, 1, 1, "B", P(10), true, false, 450));
        Assert.True(policy.TryConsume(candidate));
        policy.OnLayoutStart();
        Assert.Null(policy.OnPointerEntered("C"));
        Assert.Null(policy.OnPointerMoved(P(10), "C", "B", 500));
        policy.OnLayoutSettled();

        var next = policy.OnPointerMoved(P(13), "C", "B", 700)!.Value;
        Assert.True(policy.CanComplete(next, 1, 1, "C", P(13), true, false, 1050));
        Assert.True(policy.TryConsume(next));
        Assert.Equal(P(13), policy.LastOpenPointer);
    }

    [Fact]
    public void Same_card_motion_keeps_candidate_and_records_completion_pointer_exit_cancels()
    {
        var policy = new SpotlightHoverPolicy();
        var initial = policy.OnPointerMoved(P(10), "B", "A", 100)!.Value;
        var moved = policy.OnPointerMoved(P(15), "B", "A", 250)!.Value;
        Assert.Equal(initial.StartedAt, moved.StartedAt);
        Assert.True(policy.CanComplete(moved, 3, 3, "B", P(15), true, false, 450));
        Assert.True(policy.TryConsume(moved));
        Assert.Equal(P(15), policy.LastOpenPointer);
        policy.OnLayoutStart();
        policy.OnLayoutSettled();
        var candidate = policy.OnPointerMoved(P(18), "C", "B", 800)!.Value;
        policy.OnPointerExited("C");
        Assert.False(policy.CanComplete(candidate, 3, 3, "C", P(18), true, false, 1200));
    }

    [Fact]
    public void Non_hover_layout_and_stale_render_require_post_settle_real_move()
    {
        var policy = new SpotlightHoverPolicy();
        policy.OnPointerMoved(P(20), "A", "A", 0);
        policy.OnLayoutStart();
        Assert.Null(policy.OnPointerEntered("B"));
        policy.OnLayoutSettled();
        var candidate = policy.OnPointerMoved(P(23), "B", "A", 400)!.Value;
        Assert.False(policy.CanComplete(candidate, 4, 5, "B", P(23), true, false, 750));
        Assert.True(policy.CanComplete(candidate, 5, 5, "B", P(23), true, false, 750));
    }
}
