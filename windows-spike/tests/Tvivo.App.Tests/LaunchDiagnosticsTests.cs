using Tvivo.App;
using Tvivo.Core;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class LaunchDiagnosticsTests : IDisposable
{
    private readonly string _originalLogPath = LaunchDiagnostics.LogPath;
    private readonly string _testDirectory = Path.Combine(Path.GetTempPath(), "Tvivo.LaunchDiagnostics.Tests", Guid.NewGuid().ToString("N"));

    public LaunchDiagnosticsTests()
    {
        LaunchDiagnostics.ClearActiveSecrets();
        LaunchDiagnostics.LogPath = Path.Combine(_testDirectory, "tvivo-launch.log");
    }

    [Fact]
    public void Write_redacts_urls()
    {
        LaunchDiagnostics.Write("Opening https://private.example/watch?token=secret");

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Contains("[redacted-url]", log, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", log, StringComparison.Ordinal);
        Assert.DoesNotContain("token=secret", log, StringComparison.Ordinal);
    }

    [Fact]
    public void Write_redacts_credential_fields()
    {
        LaunchDiagnostics.Write("username=alice password:secret accountid=123 account_id:456");

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Equal(4, log.Split("[redacted-field]", StringSplitOptions.None).Length - 1);
        Assert.DoesNotContain("alice", log, StringComparison.Ordinal);
        Assert.DoesNotContain("secret", log, StringComparison.Ordinal);
        Assert.DoesNotContain("accountid=123", log, StringComparison.Ordinal);
        Assert.DoesNotContain("account_id:456", log, StringComparison.Ordinal);
    }

    [Fact]
    public void Write_truncates_long_messages()
    {
        LaunchDiagnostics.Write(new string('x', 5000));

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Contains(new string('x', 4096) + "[truncated]", log, StringComparison.Ordinal);
        Assert.DoesNotContain(new string('x', 4097), log, StringComparison.Ordinal);
    }

    [Fact]
    public void Write_rotates_at_maximum_log_size()
    {
        for (var i = 0; i < 260; i++)
            LaunchDiagnostics.Write(new string('x', 4096));

        Assert.True(File.Exists(LaunchDiagnostics.LogPath + ".1"));
        Assert.InRange(new FileInfo(LaunchDiagnostics.LogPath).Length, 1, 1024 * 1024);
        Assert.InRange(new FileInfo(LaunchDiagnostics.LogPath + ".1").Length, 1, 1024 * 1024);
    }

    [Fact]
    public void WriteException_preserves_type_and_message_without_stack_trace()
    {
        LaunchDiagnostics.SetActiveSecrets(new ProviderConnection(new ProviderEndpoint("http", "private.example", 0), "alice", "secret"));
        try
        {
            ThrowDiagnosticFailure();
        }
        catch (InvalidOperationException exception)
        {
            LaunchDiagnostics.WriteException("Playback failed", exception);
        }

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Contains("Playback failed", log, StringComparison.Ordinal);
        Assert.Contains("Decoder failed", log, StringComparison.Ordinal);
        Assert.Contains("[redacted-url]", log, StringComparison.Ordinal);
        Assert.DoesNotContain(nameof(ThrowDiagnosticFailure), log, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", log, StringComparison.Ordinal);
    }

    [Fact]
    public void WriteExceptionDetails_preserves_message_without_stack_trace()
    {
        LaunchDiagnostics.SetActiveSecrets(new ProviderConnection(new ProviderEndpoint("http", "private.example", 0), "alice", "secret"));
        try
        {
            ThrowDiagnosticFailure();
        }
        catch (InvalidOperationException exception)
        {
            LaunchDiagnostics.WriteExceptionDetails("Launch failed", exception);
        }

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Contains("Launch failed", log, StringComparison.Ordinal);
        Assert.Contains("Decoder failed", log, StringComparison.Ordinal);
        Assert.Contains("[redacted-url]", log, StringComparison.Ordinal);
        Assert.DoesNotContain(nameof(ThrowDiagnosticFailure), log, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", log, StringComparison.Ordinal);
    }

    [Fact]
    public void WriteException_omits_unregistered_exception_message()
    {
        LaunchDiagnostics.WriteException("Authentication failed", new InvalidOperationException("bare-host.example alice secret"));

        var log = File.ReadAllText(LaunchDiagnostics.LogPath);
        Assert.Contains("Exception before account registration: InvalidOperationException (message omitted)", log, StringComparison.Ordinal);
        Assert.DoesNotContain("bare-host.example", log, StringComparison.Ordinal);
        Assert.DoesNotContain("alice secret", log, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("/series/alice/p%40ss/42.mkv")]
    [InlineData("/live/alice/p%40ss/1.ts")]
    [InlineData("MRL=\"http://PRIVATE.example:8080/movie/alice/p%40ss/2.mp4\"")]
    [InlineData("Location: /series/alice/p%40ss/42.mkv")]
    [InlineData("Location: http://private.example/series/alice/p%40ss/42.mkv")]
    [InlineData("username=alice&password=p%40ss&item_id=42")]
    [InlineData("Cookie: session=alice; token=p%40ss")]
    [InlineData("Exception: private.example alice p@ss")]
    public void Write_and_export_remove_credentials_and_item_ids(string message)
    {
        LaunchDiagnostics.SetActiveSecrets(new ProviderConnection(new ProviderEndpoint("http", "private.example", 8080), "alice", "p@ss"));
        LaunchDiagnostics.Write(message);

        var disk = File.ReadAllText(LaunchDiagnostics.LogPath);
        var export = LaunchDiagnostics.ExportRecent();
        foreach (var output in new[] { disk, export })
        {
            Assert.DoesNotContain("private.example", output, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("alice", output, StringComparison.Ordinal);
            Assert.DoesNotContain("p@ss", output, StringComparison.Ordinal);
            Assert.DoesNotContain("p%40ss", output, StringComparison.Ordinal);
            Assert.DoesNotContain("42.mkv", output, StringComparison.Ordinal);
            Assert.DoesNotContain("item_id=42", output, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void Export_scrubs_existing_session_line_again()
    {
        LaunchDiagnostics.SetActiveSecrets(new ProviderConnection(new ProviderEndpoint("http", "private.example", 8080), "alice", "p@ss"));
        LaunchDiagnostics.Write("session marker");
        var marker = File.ReadAllLines(LaunchDiagnostics.LogPath)[0];
        var prefix = marker[..marker.IndexOf("session marker", StringComparison.Ordinal)];
        File.AppendAllText(LaunchDiagnostics.LogPath, prefix + "Location: /series/alice/p%40ss/42.mkv" + Environment.NewLine);

        var export = LaunchDiagnostics.ExportRecent();

        Assert.DoesNotContain("alice", export, StringComparison.Ordinal);
        Assert.DoesNotContain("p%40ss", export, StringComparison.Ordinal);
        Assert.DoesNotContain("42.mkv", export, StringComparison.Ordinal);
    }

    [Fact]
    public void Export_is_bounded_to_recent_lines_and_characters()
    {
        for (var i = 0; i < 250; i++) LaunchDiagnostics.Write($"line {i} {new string('x', 120)}");

        var export = LaunchDiagnostics.ExportRecent();

        Assert.DoesNotContain("line 0 ", export, StringComparison.Ordinal);
        Assert.Contains("line 249 ", export, StringComparison.Ordinal);
        Assert.InRange(export.Length, 1, 32768);
        Assert.InRange(export.Split(Environment.NewLine).Length, 1, 200);
    }

    [Fact]
    public void Engine_warning_callback_output_is_scrubbed_before_disk_and_copy()
    {
        LaunchDiagnostics.SetActiveSecrets(new ProviderConnection(
            new ProviderEndpoint("http", "private.example", 8080), "alice", "p@ss"));
        Action<string> engineLifecycle = LaunchDiagnostics.Write;
        engineLifecycle("event=playback.native.warning level=Warning message=Location: /series/alice/p%40ss/42.mkv");

        foreach (var output in new[] { File.ReadAllText(LaunchDiagnostics.LogPath), LaunchDiagnostics.ExportRecent() })
        {
            Assert.DoesNotContain("alice", output, StringComparison.Ordinal);
            Assert.DoesNotContain("p%40ss", output, StringComparison.Ordinal);
            Assert.DoesNotContain("42.mkv", output, StringComparison.Ordinal);
            Assert.Contains("event=playback.native.warning", output, StringComparison.Ordinal);
        }
    }

    private static void ThrowDiagnosticFailure() =>
        throw new InvalidOperationException("Decoder failed at https://private.example/watch?password=secret");

    public void Dispose()
    {
        LaunchDiagnostics.ClearActiveSecrets();
        LaunchDiagnostics.LogPath = _originalLogPath;
        if (Directory.Exists(_testDirectory)) Directory.Delete(_testDirectory, recursive: true);
    }
}
