using System.Diagnostics;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class PerfScopeTests
{
    [Fact]
    public void Overlapping_scopes_are_returned_longest_first_and_limited()
    {
        var prefix = $"PerfScopeTest-{Guid.NewGuid():N}";
        var startedAt = Stopwatch.GetTimestamp();
        using (PerfScope.Begin($"{prefix}-outer"))
        {
            Thread.Sleep(15);
            using (PerfScope.Begin($"{prefix}-inner"))
                Thread.Sleep(30);
        }
        var endedAt = Stopwatch.GetTimestamp();

        var matches = PerfScope.GetOverlapping(startedAt, endedAt, maxCount: 2)
            .Where(operation => operation.Name.StartsWith(prefix, StringComparison.Ordinal))
            .ToArray();

        Assert.Equal(2, matches.Length);
        Assert.Equal($"{prefix}-outer", matches[0].Name);
        Assert.Equal($"{prefix}-inner", matches[1].Name);
        Assert.True(matches[0].DurationTicks >= matches[1].DurationTicks);
    }

    [Fact]
    public void Overlap_selection_excludes_operations_outside_the_window()
    {
        var prefix = $"PerfScopeTest-{Guid.NewGuid():N}";
        using (PerfScope.Begin($"{prefix}-before"))
            Thread.Sleep(10);

        var startedAt = Stopwatch.GetTimestamp();
        using (PerfScope.Begin($"{prefix}-inside"))
            Thread.Sleep(10);
        var endedAt = Stopwatch.GetTimestamp();

        using (PerfScope.Begin($"{prefix}-after"))
            Thread.Sleep(10);

        var matches = PerfScope.GetOverlapping(startedAt, endedAt, maxCount: 64)
            .Where(operation => operation.Name.StartsWith(prefix, StringComparison.Ordinal))
            .Select(operation => operation.Name)
            .ToArray();

        Assert.Equal(new[] { $"{prefix}-inside" }, matches);
    }
}
