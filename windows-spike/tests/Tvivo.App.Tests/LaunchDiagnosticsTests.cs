using Tvivo.App;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class LaunchDiagnosticsTests : IDisposable
{
    private readonly string _originalLogPath = LaunchDiagnostics.LogPath;
    private readonly string _testDirectory = Path.Combine(Path.GetTempPath(), "Tvivo.LaunchDiagnostics.Tests", Guid.NewGuid().ToString("N"));

    public LaunchDiagnosticsTests()
    {
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
    public void WriteException_preserves_message_and_stack_trace_after_redaction()
    {
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
        Assert.Contains(nameof(ThrowDiagnosticFailure), log, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", log, StringComparison.Ordinal);
    }

    [Fact]
    public void WriteExceptionDetails_preserves_message_and_stack_trace_after_redaction()
    {
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
        Assert.Contains(nameof(ThrowDiagnosticFailure), log, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", log, StringComparison.Ordinal);
    }

    private static void ThrowDiagnosticFailure() =>
        throw new InvalidOperationException("Decoder failed at https://private.example/watch?password=secret");

    public void Dispose()
    {
        LaunchDiagnostics.LogPath = _originalLogPath;
        if (Directory.Exists(_testDirectory)) Directory.Delete(_testDirectory, recursive: true);
    }
}
