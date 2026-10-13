using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class ThemePreferenceStoreTests
{
    [Theory]
    [InlineData(ThemePreference.Dark, "{\"theme\":\"Dark\"}")]
    [InlineData(ThemePreference.Light, "{\"theme\":\"Light\"}")]
    public void Round_trip_writes_only_the_supported_value(ThemePreference theme, string expectedJson)
    {
        using var root = new TempRoot();
        var store = new ThemePreferenceStore(root.PathFor("theme-preference.json"));

        store.Write(theme);

        Assert.Equal(expectedJson, File.ReadAllText(root.PathFor("theme-preference.json")));
        Assert.Equal(theme, store.Read());
        Assert.Empty(Directory.GetFiles(root.Path, "*.tmp"));
    }

    [Theory]
    [InlineData("{")]
    [InlineData("{\"theme\":\"light\"}")]
    [InlineData("{\"theme\":\"System\"}")]
    [InlineData("{\"theme\":4}")]
    [InlineData("{}")]
    public void Corrupt_or_unknown_preference_defaults_to_dark(string contents)
    {
        using var root = new TempRoot();
        var path = root.PathFor("theme-preference.json");
        File.WriteAllText(path, contents);

        Assert.Equal(ThemePreference.Dark, new ThemePreferenceStore(path).Read());
    }

    [Fact]
    public void Failed_replace_preserves_the_last_good_preference()
    {
        if (!OperatingSystem.IsWindows()) return;

        using var root = new TempRoot();
        var path = root.PathFor("theme-preference.json");
        var store = new ThemePreferenceStore(path);
        store.Write(ThemePreference.Dark);

        using (new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var exception = Record.Exception(() => store.Write(ThemePreference.Light));
            Assert.NotNull(exception);
            Assert.True(exception is IOException or UnauthorizedAccessException);
        }

        Assert.Equal(ThemePreference.Dark, store.Read());
        Assert.Empty(Directory.GetFiles(root.Path, "*.tmp"));
    }

    private sealed class TempRoot : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "tvivo-theme-tests-" + Guid.NewGuid().ToString("N"));
        public TempRoot() => Directory.CreateDirectory(Path);
        public string PathFor(string fileName) => System.IO.Path.Combine(Path, fileName);
        public void Dispose()
        {
            if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true);
        }
    }
}
