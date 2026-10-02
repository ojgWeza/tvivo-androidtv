namespace Tvivo.Core;

public enum EpgCapability
{
    Usable,
    Empty,
    Unavailable,
    Failed,
}

public sealed record EpgProgramme(
    string ChannelId,
    DateTimeOffset StartUtc,
    DateTimeOffset EndUtc,
    string Title,
    string? Description = null);

public sealed record EpgNowNext(EpgProgramme? Now, EpgProgramme? Next);

public sealed record EpgChannelMap(string StreamId, string EpgChannelId);

public sealed record EpgProbeResult(EpgCapability Capability, int? HttpStatus = null, int ProgrammeCount = 0);

public interface IEpgProvider
{
    Task<EpgProbeResult> ProbeAsync(
        ProviderAccount account,
        ProviderConnection connection,
        string streamId,
        CancellationToken cancellationToken = default);

    Task<Stream> OpenProgrammeStreamAsync(
        ProviderAccount account,
        ProviderConnection connection,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyList<EpgChannelMap>> GetEpgChannelMapAsync(
        ProviderAccount account,
        CancellationToken cancellationToken = default);
}
