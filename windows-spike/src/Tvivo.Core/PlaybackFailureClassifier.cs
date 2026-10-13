using System.Net.Sockets;

namespace Tvivo.Core;

/// <summary>
/// Turns engine-specific failure signals into a <see cref="PlaybackAttemptResult"/> that says what
/// actually went wrong (format, decoder or network) instead of a generic failure or a 30 s timeout.
/// </summary>
public static class PlaybackFailureClassifier
{
    // Media Foundation codes that mean "this container/codec cannot be handled", not "the network failed".
    private static readonly HashSet<uint> UnsupportedMediaHResults = new()
    {
        0xC00D36C4, // MF_E_UNSUPPORTED_BYTESTREAM_TYPE
        0xC00D5212, // MF_E_TOPO_CODEC_NOT_FOUND
        0xC00D36B4, // MF_E_INVALIDMEDIATYPE
    };

    // Windows.Media.Playback.MediaPlayerError values.
    private const int MediaPlayerNetworkError = 2;
    private const int MediaPlayerDecodingError = 3;
    private const int MediaPlayerSourceNotSupported = 4;

    /// <summary>True for a LibVLC log line saying a stream's codec has no decoder.</summary>
    public static bool IsMissingDecoderLog(string? message) =>
        message is not null &&
        (message.Contains("no suitable decoder", StringComparison.OrdinalIgnoreCase) ||
         message.Contains("does not support this sound or video format", StringComparison.OrdinalIgnoreCase));

    /// <summary>True for a LibVLC log line saying the provider refused the request (HTTP 403, e.g. too many connections).</summary>
    public static bool IsForbiddenLog(string? message) =>
        message is not null &&
        (message.Contains("403", StringComparison.Ordinal) ||
         message.Contains("Forbidden", StringComparison.OrdinalIgnoreCase));

    public static PlaybackAttemptResult FromMediaPlayerError(int error, int hresult) => error switch
    {
        MediaPlayerNetworkError => PlaybackAttemptResult.NetworkFailure,
        MediaPlayerSourceNotSupported => PlaybackAttemptResult.UnsupportedMedia,
        MediaPlayerDecodingError => UnsupportedMediaHResults.Contains(unchecked((uint)hresult))
            ? PlaybackAttemptResult.UnsupportedMedia
            : PlaybackAttemptResult.DecodeFailure,
        _ => UnsupportedMediaHResults.Contains(unchecked((uint)hresult))
            ? PlaybackAttemptResult.UnsupportedMedia
            : PlaybackAttemptResult.DecodeFailure,
    };

    public static PlaybackAttemptResult FromOpenException(Exception exception)
    {
        if (UnsupportedMediaHResults.Contains(unchecked((uint)exception.HResult)))
            return PlaybackAttemptResult.UnsupportedMedia;
        return exception is HttpRequestException or SocketException or IOException or TimeoutException
            ? PlaybackAttemptResult.NetworkFailure
            : PlaybackAttemptResult.DecodeFailure;
    }

    /// <summary>
    /// A LibVLC attempt that ended without video: a logged missing decoder means the format is the problem,
    /// otherwise the failure stays generic.
    /// </summary>
    public static PlaybackAttemptResult RefineLibVlcFailure(PlaybackAttemptResult result, bool missingDecoderLogged, bool forbiddenLogged = false) =>
        forbiddenLogged && result is PlaybackAttemptResult.UnknownFailure or PlaybackAttemptResult.Timeout
            ? PlaybackAttemptResult.NetworkFailure // the provider refused it: another player would get the same 403
            : missingDecoderLogged && result is PlaybackAttemptResult.UnknownFailure or PlaybackAttemptResult.Timeout
                ? PlaybackAttemptResult.UnsupportedMedia
                : result;
}

public static class EngineFallbackPolicy
{
    /// <summary>
    /// How long a VOD stream may play without any video output before it is treated as "video format not
    /// supported" (audio can start while the video decoder is missing). Live is exempt: radio channels have no video.
    /// </summary>
    public static readonly TimeSpan NoVideoGrace = TimeSpan.FromSeconds(10);

    public static bool AppliesNoVideoGrace(StreamKind kind) => kind != StreamKind.Live;

    /// <summary>Another player can plausibly succeed only when the problem is the format, not the network.</summary>
    public static bool ShouldFallback(PlaybackAttemptResult result, bool alreadyTried) =>
        !alreadyTried &&
        result is PlaybackAttemptResult.UnsupportedMedia
            or PlaybackAttemptResult.DecodeFailure
            or PlaybackAttemptResult.UnknownFailure;
}
