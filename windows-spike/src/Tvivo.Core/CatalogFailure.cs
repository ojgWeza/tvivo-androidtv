using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace Tvivo.Core;

public enum CatalogFailureKind { Auth, ProviderBusy, Forbidden, Timeout, Network, Protocol, Database, EmptyValid, Cancelled }

public sealed record CatalogFailure(CatalogFailureKind Kind, string Stage, int? HttpStatus, string TraceId)
{
    public bool Retryable => Kind is CatalogFailureKind.ProviderBusy or CatalogFailureKind.Timeout or CatalogFailureKind.Network;
    public string Reason => Kind switch
    {
        CatalogFailureKind.Auth or CatalogFailureKind.Forbidden => "Account needs attention",
        CatalogFailureKind.ProviderBusy => "Provider is busy; trying again",
        CatalogFailureKind.Timeout => "Connection timed out",
        CatalogFailureKind.Network => "Connection unavailable",
        CatalogFailureKind.Protocol => "Provider sent unexpected data",
        CatalogFailureKind.Database => "Saved catalog needs repair",
        CatalogFailureKind.EmptyValid => "Provider returned an empty result",
        _ => "Request cancelled",
    };
    public string Why => $"Why: {Stage}; code {(HttpStatus?.ToString() ?? Kind.ToString())}; trace {TraceId}";
}

public sealed class CatalogFailureException(CatalogFailure failure, Exception? inner = null)
    : Exception(failure.Reason, inner)
{
    public CatalogFailure Failure { get; } = failure;
}

public sealed class ProviderSessionException() : InvalidOperationException("Provider session is not authenticated.")
{
}

public static class CatalogFailureClassifier
{
    public static bool IsRetryableAuthentication(AuthenticationResult result)
    {
        if (result.Success || result.FailureReason != AuthFailureReason.NetworkFailure) return false;
        if (result.DiagnosticCode is "network_error" or "network_timeout") return true;
        return result.DiagnosticCode?.StartsWith("http_", StringComparison.Ordinal) == true &&
            int.TryParse(result.DiagnosticCode.AsSpan(5), out var status) &&
            Classify(status, null, "auth").Retryable;
    }

    public static CatalogFailure Classify(int? httpStatus, Exception? exception, string stage, string? traceId = null,
        bool cancellationRequested = false)
    {
        if (exception is CatalogFailureException known) return known.Failure;
        while (exception?.InnerException is { } inner && exception is not HttpRequestException)
            exception = inner;
        var status = httpStatus ?? ((exception as HttpRequestException)?.StatusCode is { } code ? (int?)code : null);
        var kind = cancellationRequested ? CatalogFailureKind.Cancelled : status switch
        {
            401 => CatalogFailureKind.Auth,
            403 => CatalogFailureKind.Forbidden,
            408 or 429 or >= 500 => CatalogFailureKind.ProviderBusy,
            _ when exception is ProviderSessionException => CatalogFailureKind.Auth,
            _ when exception is TimeoutException or TaskCanceledException => CatalogFailureKind.Timeout,
            _ when exception is JsonException or FormatException => CatalogFailureKind.Protocol,
            _ when exception?.GetType().Name.Contains("Sqlite", StringComparison.Ordinal) == true => CatalogFailureKind.Database,
            _ when exception is HttpRequestException or SocketException or WebException => CatalogFailureKind.Network,
            _ when exception is OperationCanceledException => CatalogFailureKind.Cancelled,
            _ => CatalogFailureKind.Protocol,
        };
        return new(kind, stage, status, traceId ?? Guid.NewGuid().ToString("N")[..12]);
    }
}
