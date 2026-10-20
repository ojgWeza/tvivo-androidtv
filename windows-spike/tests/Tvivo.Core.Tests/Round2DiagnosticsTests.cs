using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class Round2DiagnosticsTests
{
    [Fact]
    public void Export_redactor_accepts_only_structured_values_under_adversarial_inputs()
    {
        var attacks = new[]
        {
            "event=account.private", "stage=private.invalid", "op=private.invalid",
            "trace=private", "session=private", "outcome=private", "exception=private",
            "code=private", "http=private", "elapsedMs=private", "attempt=private",
            "https://person:secret@private.invalid/path?token=secret",
            "http://[2001:db8::1]/?token=secret",
            "user%40private.invalid", "secret%2Ftoken", "عنوان",
        };
        foreach (var attack in attacks)
        {
            var safe = DiagnosticRedactor.RedactLine("2026-10-19T12:00:00Z " + attack);
            Assert.Equal("2026-10-19T12:00:00Z", safe);
        }
        var structured = DiagnosticRedactor.RedactLine(
            "2026-10-19T12:00:00Z session=abcdef123456abcdef123456abcdef12 trace=abcdef123456 " +
            "op=series-info stage=http attempt=2 outcome=Network http=503 elapsedMs=1.5");
        Assert.Contains("trace=abcdef123456", structured);
        Assert.Contains("op=series-info", structured);
        Assert.DoesNotContain("event=", structured);
        Assert.DoesNotContain("exception=", structured);
    }

    [Fact]
    public void Long_line_and_malformed_timestamp_remain_bounded()
    {
        var line = "2026-10-19T12:00:00Z " + new string('x', 30_000) + " trace=abcdef123456";
        Assert.True(DiagnosticRedactor.RedactLine(line).Length < 100);
        Assert.Empty(DiagnosticRedactor.RedactLine("2026-10-19T12:00:00Zprivate"));
        Assert.Empty(DiagnosticRedactor.RedactLine(new string('界', 40_000)));
    }

    [Fact]
    public void Rotation_rejects_oversized_write_and_repairs_old_oversized_files()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "app.log");
        try
        {
            Assert.Throws<ArgumentOutOfRangeException>(() => DiagnosticLogRotation.RotateIfNeeded(path, 101, 100));
            File.WriteAllText(path, new string('x', 101));
            File.WriteAllText(path + ".1", new string('x', 101));
            File.WriteAllText(path + ".2", new string('x', 101));
            DiagnosticLogRotation.RotateIfNeeded(path, 10, 100);
            Assert.False(File.Exists(path));
            Assert.False(File.Exists(path + ".1"));
            Assert.False(File.Exists(path + ".2"));
        }
        finally { Directory.Delete(root, true); }
    }
}
