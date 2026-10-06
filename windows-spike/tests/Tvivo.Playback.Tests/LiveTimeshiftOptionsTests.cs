using Tvivo.Core;
using Tvivo.Playback;
using Xunit;

namespace Tvivo.Playback.Tests;

public sealed class LiveTimeshiftOptionsTests
{
    [Fact]
    public void OptionsAreOptInAndLiveOnly()
    {
        const string path = @"D:\Scratch\tvivo-timeshift-test";
        Assert.Empty(LiveTimeshiftOptions.ForMedia(StreamKind.Live, false, path));
        Assert.Empty(LiveTimeshiftOptions.ForMedia(StreamKind.Live, true, null));
        foreach (var kind in Enum.GetValues<StreamKind>().Where(kind => kind != StreamKind.Live))
            Assert.Empty(LiveTimeshiftOptions.ForMedia(kind, true, path));

        Assert.Equal(
            new[]
            {
                $":input-timeshift-path={path}",
                $":input-timeshift-granularity={LiveTimeshiftOptions.GranularityBytes}",
            },
            LiveTimeshiftOptions.ForMedia(StreamKind.Live, true, path));
    }

    [Fact]
    public void TimeshiftDirectoryStaysUnderLocalAppData()
    {
        const string localAppData = @"D:\Users\Test\AppData\Local";
        Assert.Equal(
            Path.Combine(localAppData, "Tvivo", "timeshift"),
            LiveTimeshiftOptions.DirectoryPath(localAppData));
    }

    [Fact]
    public void CleanupDeletesOnlyDirectVlcTemporaryFiles()
    {
        var root = Path.Combine(Path.GetTempPath(), "tvivo-timeshift-tests", Guid.NewGuid().ToString("N"));
        var owned = Path.Combine(root, "timeshift");
        var nested = Path.Combine(owned, "nested");
        Directory.CreateDirectory(nested);
        var tempFile = Path.Combine(owned, "vlc-timeshift.A1b2C3");
        var unrelated = Path.Combine(owned, "keep.txt");
        var similar = Path.Combine(owned, "vlc-timeshift.extra-long");
        var nestedFile = Path.Combine(nested, "vlc-timeshift.D4e5F6");
        var outsideFile = Path.Combine(root, "vlc-timeshift.G7h8I9");
        try
        {
            foreach (var file in new[] { tempFile, unrelated, similar, nestedFile, outsideFile })
                File.WriteAllText(file, "test");

            Assert.Equal(1, LiveTimeshiftOptions.CleanupStaleFiles(owned));
            Assert.False(File.Exists(tempFile));
            Assert.True(File.Exists(unrelated));
            Assert.True(File.Exists(similar));
            Assert.True(File.Exists(nestedFile));
            Assert.True(File.Exists(outsideFile));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void CleanupLimitsWorkPerCall()
    {
        var owned = Path.Combine(Path.GetTempPath(), "tvivo-timeshift-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(owned);
        try
        {
            for (var index = 0; index <= LiveTimeshiftOptions.MaxCleanupFiles; index++)
                File.WriteAllText(Path.Combine(owned, $"vlc-timeshift.{index:D6}"), "test");

            Assert.Equal(LiveTimeshiftOptions.MaxCleanupFiles, LiveTimeshiftOptions.CleanupStaleFiles(owned));
            Assert.Single(Directory.EnumerateFiles(owned));
        }
        finally
        {
            Directory.Delete(owned, recursive: true);
        }
    }
}
