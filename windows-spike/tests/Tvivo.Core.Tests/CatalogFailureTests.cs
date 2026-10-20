using System.Net;
using System.Text.Json;
using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CatalogFailureTests
{
    [Theory]
    [InlineData(401, CatalogFailureKind.Auth, false)]
    [InlineData(403, CatalogFailureKind.Forbidden, false)]
    [InlineData(408, CatalogFailureKind.ProviderBusy, true)]
    [InlineData(429, CatalogFailureKind.ProviderBusy, true)]
    [InlineData(503, CatalogFailureKind.ProviderBusy, true)]
    public void Http_status_is_classified(int status, CatalogFailureKind kind, bool retryable)
    {
        var failure = CatalogFailureClassifier.Classify(status, null, "items", "abc123");
        Assert.Equal(kind, failure.Kind);
        Assert.Equal(retryable, failure.Retryable);
        Assert.Contains("trace abc123", failure.Why);
    }

    [Fact]
    public void Exception_types_and_stages_are_classified()
    {
        Assert.Equal(CatalogFailureKind.Timeout, CatalogFailureClassifier.Classify(null, new TaskCanceledException(), "series-info").Kind);
        Assert.Equal(CatalogFailureKind.Network, CatalogFailureClassifier.Classify(null, new HttpRequestException(), "items").Kind);
        Assert.Equal(CatalogFailureKind.Protocol, CatalogFailureClassifier.Classify(null, new JsonException(), "json.parse").Kind);
        Assert.Equal(CatalogFailureKind.Auth, CatalogFailureClassifier.Classify(null, new ProviderSessionException(), "auth").Kind);
        Assert.Equal(CatalogFailureKind.Cancelled, CatalogFailureClassifier.Classify(null, new TaskCanceledException(), "items", cancellationRequested: true).Kind);
    }

    [Theory]
    [InlineData(AuthFailureReason.InvalidCredentials, "invalid_credentials", false)]
    [InlineData(AuthFailureReason.ProtocolError, "malformed_json", false)]
    [InlineData(AuthFailureReason.NetworkFailure, "http_403", false)]
    [InlineData(AuthFailureReason.NetworkFailure, "http_429", true)]
    [InlineData(AuthFailureReason.NetworkFailure, "network_timeout", true)]
    public void Authentication_retry_only_accepts_transient_failures(AuthFailureReason reason, string code, bool retry)
    {
        Assert.Equal(retry, CatalogFailureClassifier.IsRetryableAuthentication(AuthenticationResult.Failed(reason, code)));
    }

    [Fact]
    public void Retry_after_is_capped_and_jitter_bounded()
    {
        var now = DateTimeOffset.UtcNow;
        Assert.Equal(TimeSpan.FromSeconds(20), CatalogRetryPolicy.Delay(1, now, now.AddMinutes(2), 0));
        Assert.Equal(TimeSpan.FromSeconds(7), CatalogRetryPolicy.Delay(1, now, now.AddSeconds(7), 0));
        Assert.InRange(CatalogRetryPolicy.Delay(1, now, null, 0.5).TotalSeconds, 2.5, 2.5);
    }

    [Fact]
    public void Diagnostic_export_drops_urls_secrets_unicode_and_long_lines()
    {
        var line = "2026-10-19T12:00:00Z trace=abcdef123456 op=series-info stage=http attempt=1 outcome=Network http=503 " +
            "https://private.invalid/series/user/pass/4.ts عنوان " + new string('x', 30_000);
        var safe = DiagnosticRedactor.RedactLine(line);
        Assert.Contains("trace=abcdef123456", safe);
        Assert.DoesNotContain("private", safe);
        Assert.DoesNotContain("user", safe);
        Assert.DoesNotContain("عنوان", safe);
        Assert.True(safe.Length < 300);
    }

    [Fact]
    public void Log_rotation_retains_two_previous_files()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "app.log");
        try
        {
            for (var index = 0; index < 4; index++)
            {
                DiagnosticLogRotation.RotateIfNeeded(path, 4, 5);
                File.WriteAllText(path, $"{index:0000}");
            }
            Assert.Equal("0003", File.ReadAllText(path));
            Assert.Equal("0002", File.ReadAllText(path + ".1"));
            Assert.Equal("0001", File.ReadAllText(path + ".2"));
        }
        finally { Directory.Delete(directory, true); }
    }
}
