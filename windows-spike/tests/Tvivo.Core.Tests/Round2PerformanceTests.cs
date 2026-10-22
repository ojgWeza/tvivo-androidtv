using System.Diagnostics;
using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class Round2PerformanceTests
{
    [Fact]
    [Trait("Category", "Perf")]
    public void Spotlight_selection_over_100k_candidates_stays_bounded()
    {
        var items = Enumerable.Range(0, 100_000).Select(index => new SpotlightCandidate(
            CatalogItemType.Movie, index.ToString(), "folder-" + index % 8, "TITLE " + index,
            "2025", DateTimeOffset.UnixEpoch.AddMinutes(index))).ToArray();
        var settings = new SpotlightSettings(
            new SpotlightContentSettings(SpotlightSourceMode.SpecificFolders,
                Enumerable.Range(0, 8).Select(index => "folder-" + index).ToArray()),
            SpotlightSettings.Default.Series);
        SpotlightSelector.Select(SpotlightPageKind.Movies, "opaque", new DateOnly(2026, 10, 20),
            settings, items.Take(8).ToArray(), Array.Empty<SpotlightCandidate>());

        var before = GC.GetAllocatedBytesForCurrentThread();
        var watch = Stopwatch.StartNew();
        var result = SpotlightSelector.Select(SpotlightPageKind.Movies, "opaque", new DateOnly(2026, 10, 20),
            settings, items, Array.Empty<SpotlightCandidate>());
        watch.Stop();
        Assert.Equal(SpotlightSelector.SelectionSize, result.Items.Count);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"Spotlight selection took {watch.Elapsed}.");
        Assert.InRange(GC.GetAllocatedBytesForCurrentThread() - before, 0, 64 * 1024 * 1024);
    }

    [Fact]
    [Trait("Category", "Perf")]
    public void Redactor_processes_one_megabyte_with_bounded_time_and_allocations()
    {
        var line = "2026-10-19T12:00:00Z trace=abcdef123456 op=series-info stage=http outcome=ok " +
            new string('x', 64);
        var count = (1024 * 1024 + line.Length - 1) / line.Length;
        DiagnosticRedactor.RedactLine(line);
        var before = GC.GetAllocatedBytesForCurrentThread();
        var watch = Stopwatch.StartNew();
        var accepted = 0;
        for (var index = 0; index < count; index++)
            if (DiagnosticRedactor.RedactLine(line).Contains("trace=abcdef123456", StringComparison.Ordinal)) accepted++;
        watch.Stop();
        Assert.Equal(count, accepted);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"Redaction took {watch.Elapsed}.");
        Assert.InRange(GC.GetAllocatedBytesForCurrentThread() - before, 0, 128 * 1024 * 1024);
    }
}
