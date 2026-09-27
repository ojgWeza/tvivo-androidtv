namespace Tvivo.Core;

public sealed record ProviderEndpoint(string Scheme, string Host, int Port);

public sealed record ProviderAccount(
    string AccountId,
    ProviderEndpoint Endpoint,
    string Username,
    string? DisplayName = null,
    int? MaxConnections = null,
    DateTimeOffset? ExpiresAt = null);

public sealed record ProviderConnection(ProviderEndpoint Endpoint, string Username, string Password);

public enum AuthFailureReason
{
    InvalidCredentials,
    AccountInactive,
    AccountExpired,
    NetworkFailure,
    ProtocolError,
    Unknown
}

public sealed record AuthenticationResult
{
    private AuthenticationResult(bool success, ProviderAccount? account, AuthFailureReason? failureReason, string? diagnosticCode)
    {
        Success = success;
        Account = account;
        FailureReason = failureReason;
        DiagnosticCode = diagnosticCode;
    }

    public bool Success { get; }
    public ProviderAccount? Account { get; }
    public AuthFailureReason? FailureReason { get; }
    public string? DiagnosticCode { get; }

    public static AuthenticationResult Succeeded(ProviderAccount account) => new(true, account, null, null);
    public static AuthenticationResult Failed(AuthFailureReason reason, string diagnosticCode) => new(false, null, reason, diagnosticCode);
}

public sealed record ChannelGroup(
    string ProviderAccountId,
    string Id,
    string Name,
    string DisplayName,
    int? SortOrder = null);

public sealed record Channel(
    string ProviderAccountId,
    string Id,
    string? GroupId,
    string Name,
    string DisplayName,
    Uri? LogoUri,
    DateTimeOffset? AddedAt,
    int? SortOrder,
    StreamSource Source,
    IReadOnlyDictionary<string, string> Metadata);

public sealed record StreamSource(
    string StreamId,
    StreamKind Kind,
    string? ContainerExtension = null,
    Uri? DirectUri = null,
    TimeSpan? Duration = null,
    string? AudioHint = null,
    string? VideoHint = null);

public enum StreamKind { Live, Movie, Episode }

public enum CatalogItemType { Live, Movie, Series }

public interface ICatalogProvider
{
    Task<AuthenticationResult> AuthenticateAsync(ProviderConnection connection, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken cancellationToken = default);
}

public enum PlaybackAttemptResult
{
    Started, FirstFrame, Cancelled, NetworkFailure, UnsupportedMedia, DecodeFailure,
    Timeout, HostFailure, UnknownFailure
}

public readonly record struct PlaybackSessionToken(long Generation, Guid SessionId);

public readonly record struct PlaybackTimeline(long PositionMilliseconds, long? DurationMilliseconds)
{
    public bool CanSeek => DurationMilliseconds is > 0;

    public long? ClampSeekTarget(long targetPositionMilliseconds) => DurationMilliseconds is > 0 and var duration
        ? Math.Clamp(targetPositionMilliseconds, 0, duration)
        : null;
}

public interface IPlaybackEngine
{
    bool IsPlaying { get; }
    bool IsBuffering => false;
    bool IsPaused => false;
    bool IsEnded => false;
    long Time { get; }
    long Length { get; }
    PlaybackTimeline Timeline => new(Time, Length > 0 ? Length : null);
    int Volume { get; set; }
    void TogglePause();
    void Seek(long timeMilliseconds);
    Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default);
    Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default);
}

public interface ICredentialStore
{
    Task<ProviderConnection?> LoadAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(ProviderConnection connection, CancellationToken cancellationToken = default);
    Task DeleteAsync(CancellationToken cancellationToken = default);
}
