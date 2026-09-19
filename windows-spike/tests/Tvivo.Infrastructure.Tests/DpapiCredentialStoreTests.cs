using System.Security.Cryptography;
using System.Runtime.Versioning;
using System.Text;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

[SupportedOSPlatform("windows")]
public sealed class DpapiCredentialStoreTests
{
    private static ProviderConnection Connection(string host, string username, string password) =>
        new(new ProviderEndpoint("https", host, 9443), username, password);

    [Fact]
    public async Task Load_returns_null_when_nothing_is_stored()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);

        Assert.Null(await store.LoadAsync());
    }

    [Fact]
    public async Task Save_and_load_round_trip_connection_values()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);
        var expected = Connection("panel.example.com", "user name", "p@ssword");

        await store.SaveAsync(expected);

        Assert.Equal(expected, await store.LoadAsync());
    }

    [Fact]
    public async Task Save_overwrites_previous_connection()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);
        await store.SaveAsync(Connection("first.example.com", "first", "one"));
        var latest = Connection("second.example.com", "second", "two");

        await store.SaveAsync(latest);

        Assert.Equal(latest, await store.LoadAsync());
    }

    [Fact]
    public async Task Delete_removes_stored_connection_and_is_idempotent()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);
        await store.SaveAsync(Connection("panel.example.com", "user", "secret"));

        await store.DeleteAsync();
        await store.DeleteAsync();

        Assert.Null(await store.LoadAsync());
    }

    [Fact]
    public async Task Load_throws_invalid_data_for_corrupt_ciphertext_or_json()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);

        await File.WriteAllBytesAsync(temp.FilePath, Encoding.UTF8.GetBytes("not DPAPI data"));
        await Assert.ThrowsAsync<InvalidDataException>(() => store.LoadAsync());

        var invalidJson = Encoding.UTF8.GetBytes("{ definitely not JSON");
        var protectedJson = ProtectedData.Protect(invalidJson, Encoding.UTF8.GetBytes("Tvivo.ProviderConnection.v1"), DataProtectionScope.CurrentUser);
        await File.WriteAllBytesAsync(temp.FilePath, protectedJson);
        await Assert.ThrowsAsync<InvalidDataException>(() => store.LoadAsync());
    }

    [Fact]
    public async Task Failed_replace_preserves_previous_connection()
    {
        using var temp = new TemporaryDirectory();
        var store = new DpapiCredentialStore(temp.FilePath);
        var original = Connection("original.example.com", "original", "preserved");
        await store.SaveAsync(original);

        Exception? saveFailure;
        using (new FileStream(temp.FilePath, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            saveFailure = await Record.ExceptionAsync(() => store.SaveAsync(Connection("new.example.com", "new", "replacement")));
        }

        Assert.NotNull(saveFailure);
        Assert.Equal(original, await store.LoadAsync());
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        private readonly string _path = Path.Combine(Path.GetTempPath(), $"TvivoCredentialStoreTests-{Guid.NewGuid():N}");
        public string FilePath => Path.Combine(_path, "provider-connection.bin");

        public TemporaryDirectory() => Directory.CreateDirectory(_path);

        public void Dispose()
        {
            if (Directory.Exists(_path))
                Directory.Delete(_path, recursive: true);
        }
    }
}
