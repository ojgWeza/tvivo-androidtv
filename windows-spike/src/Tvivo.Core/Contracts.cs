namespace Tvivo.Core;

// Port 0 means "no port": the provider is reached on the scheme default (80 / 443) and no port is
// written into any URL.
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

public enum CatalogItemSortOrder
{
    MostVisited,
    AlphabeticalAsc,
    AlphabeticalDesc,
    RecentlyAdded,
    RecentlyUpdated,
}

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

public enum StreamKind { Live, Movie, Series, Episode }

public sealed record SeriesEpisode(string Id, string Title, string SeasonId, string SeasonName, int SeasonNumber,
    int EpisodeNumber, StreamSource Source);

public sealed record SeriesSeason(string Id, string Name, int Number, IReadOnlyList<SeriesEpisode> Episodes);

public sealed record CatalogMetadata(string? Year = null, string? Rating = null, string? Genre = null,
    string? Plot = null, string? Cast = null);

public static class RatingDisplayFormatter
{
    public static string? Format(string? rating)
    {
        if (string.IsNullOrWhiteSpace(rating)) return null;
        if (!double.TryParse(rating, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var value) ||
            !double.IsFinite(value))
            return rating.Trim();

        var rounded = Math.Round(value * 2, MidpointRounding.AwayFromZero) / 2;
        return rounded.ToString(rounded % 1 == 0 ? "0" : "0.0", System.Globalization.CultureInfo.InvariantCulture);
    }
}

public sealed record MovieDetails(string Id, CatalogMetadata Metadata);

public sealed record SeriesDetails(string Id, string Title, IReadOnlyList<SeriesSeason> Seasons,
    CatalogMetadata? Metadata = null);

public static class SeriesEpisodeResolver
{
    public static SeriesEpisode? Resolve(SeriesDetails details, string? lastOpenedEpisodeId, bool lastEpisodeFinished)
    {
        var episodes = details.Seasons
            .OrderBy(season => season.Number)
            .SelectMany(season => season.Episodes.OrderBy(episode => episode.EpisodeNumber))
            .ToArray();
        if (episodes.Length == 0) return null;
        if (string.IsNullOrWhiteSpace(lastOpenedEpisodeId)) return episodes[0];
        var index = Array.FindIndex(episodes, episode => episode.Id == lastOpenedEpisodeId);
        if (index < 0) return episodes[0];
        return lastEpisodeFinished ? episodes[Math.Min(index + 1, episodes.Length - 1)] : episodes[index];
    }

    public static SeriesEpisode? Resolve(SeriesDetails details, IReadOnlySet<string> finishedEpisodeIds, string? lastInProgressEpisodeId = null)
    {
        var episodes = details.Seasons
            .OrderBy(season => season.Number)
            .SelectMany(season => season.Episodes.OrderBy(episode => episode.EpisodeNumber))
            .ToArray();
        if (episodes.Length == 0) return null;
        if (!string.IsNullOrEmpty(lastInProgressEpisodeId) &&
            episodes.FirstOrDefault(episode => episode.Id == lastInProgressEpisodeId) is { } inProgress)
            return inProgress;
        return episodes.FirstOrDefault(episode => !finishedEpisodeIds.Contains(episode.Id)) ?? episodes[^1];
    }
}

public static class PlayerSideTitleResolver
{
    public static string ForEpisodes(string? seriesTitle) =>
        string.IsNullOrWhiteSpace(seriesTitle) ? "Episodes" : seriesTitle.Trim();

    public static string ForMovie(string? genre) =>
        string.IsNullOrWhiteSpace(genre) ? "More like this" : genre.Trim();
}

public enum CatalogItemType { Live, Movie, Series }

public interface ICatalogProvider
{
    Task<AuthenticationResult> AuthenticateAsync(ProviderConnection connection, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ChannelGroup>> GetChannelGroupsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<Channel>> GetChannelsAsync(ProviderAccount account, CatalogItemType type = CatalogItemType.Live, string? groupId = null, CancellationToken cancellationToken = default);
}

public interface ISeriesCatalogProvider
{
    Task<SeriesDetails> GetSeriesInfoAsync(ProviderAccount account, string seriesId, CancellationToken cancellationToken = default);
}

public interface IMovieInfoProvider
{
    Task<MovieDetails> GetMovieInfoAsync(ProviderAccount account, string movieId, CancellationToken cancellationToken = default);
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
    bool IsStopped => false;
    long Time { get; }
    long Length { get; }
    PlaybackTimeline Timeline => new(Time, Length > 0 ? Length : null);
    int Volume { get; set; }
    bool IsMuted { get; set; }
    void TogglePause();
    void Seek(long timeMilliseconds);
    Task<PlaybackAttemptResult> StartAsync(StreamSource source, PlaybackSessionToken session, CancellationToken cancellationToken = default);
    Task StopAsync(PlaybackSessionToken session, CancellationToken cancellationToken = default);
}

public interface ITrackSelectingEngine
{
    PlaybackTrackSnapshot GetTracks();
    bool SelectAudio(string key);
    bool SelectSubtitle(string? keyOrNullForOff);
    event EventHandler? TracksChanged;
    void ApplyDefaultSubtitle(string? preferredLanguage, bool explicitOff, string? uiCultureTwoLetterCode);
}

public interface ICredentialStore
{
    Task<ProviderConnection?> LoadAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(ProviderConnection connection, CancellationToken cancellationToken = default);
    Task DeleteAsync(CancellationToken cancellationToken = default);
}
