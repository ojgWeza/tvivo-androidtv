using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SearchActivationPolicyTests
{
    [Theory]
    [InlineData(true, "pointer", "none", true, 50, true)]
    [InlineData(true, "keyboard", "Enter", true, 50, true)]
    [InlineData(true, "keyboard", "Space", true, 50, false)]
    [InlineData(true, "keyboard", "A", true, 50, false)]
    [InlineData(true, "unknown", "none", true, 50, false)]
    [InlineData(true, "pointer", "none", false, 50, false)]
    [InlineData(true, "pointer", "none", true, 2001, false)]
    [InlineData(false, "unknown", "none", false, 2001, true)]
    public void Allows_only_recent_explicit_activation_on_same_search_item(
        bool searchActive, string input, string key, bool sameItem, long elapsedMilliseconds, bool expected) =>
        Assert.Equal(expected, SearchActivationPolicy.Allows(searchActive, input, key, sameItem, elapsedMilliseconds));
}
