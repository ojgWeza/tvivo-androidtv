using System.Runtime.InteropServices;
using Xunit;
using System.Net.Sockets;
using Tvivo.Core;

namespace Tvivo.Core.Tests;

public sealed class PlaybackFailureClassifierTests
{
    [Theory]
    [InlineData("no suitable decoder module for fourcc `hev1'. VLC probably does not support this sound or video format.")]
    [InlineData("No suitable decoder module: VLC does not support the audio or video format \"mp4a\".")]
    [InlineData("VLC probably does not support this sound or video format")]
    public void Missing_decoder_log_lines_are_recognised(string message) =>
        Assert.True(PlaybackFailureClassifier.IsMissingDecoderLog(message));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("connection reset by peer")]
    [InlineData("buffer deadlock prevented")]
    public void Unrelated_log_lines_are_not_decoder_failures(string? message) =>
        Assert.False(PlaybackFailureClassifier.IsMissingDecoderLog(message));

    [Theory]
    [InlineData(2, 0, PlaybackAttemptResult.NetworkFailure)]
    [InlineData(4, 0, PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(3, 0, PlaybackAttemptResult.DecodeFailure)]
    [InlineData(0, 0, PlaybackAttemptResult.DecodeFailure)]
    public void Media_player_errors_map_to_the_specific_result(int error, int hresult, PlaybackAttemptResult expected) =>
        Assert.Equal(expected, PlaybackFailureClassifier.FromMediaPlayerError(error, hresult));

    [Theory]
    [InlineData(unchecked((int)0xC00D36C4))]
    [InlineData(unchecked((int)0xC00D5212))]
    [InlineData(unchecked((int)0xC00D36B4))]
    public void Codec_or_container_hresults_are_unsupported_media_even_when_reported_as_decoding_errors(int hresult)
    {
        Assert.Equal(PlaybackAttemptResult.UnsupportedMedia, PlaybackFailureClassifier.FromMediaPlayerError(3, hresult));
        Assert.Equal(PlaybackAttemptResult.UnsupportedMedia, PlaybackFailureClassifier.FromOpenException(new COMException("codec", hresult)));
    }

    [Fact]
    public void Open_exceptions_distinguish_network_from_decode()
    {
        Assert.Equal(PlaybackAttemptResult.NetworkFailure, PlaybackFailureClassifier.FromOpenException(new HttpRequestException("down")));
        Assert.Equal(PlaybackAttemptResult.NetworkFailure, PlaybackFailureClassifier.FromOpenException(new SocketException()));
        Assert.Equal(PlaybackAttemptResult.NetworkFailure, PlaybackFailureClassifier.FromOpenException(new TimeoutException()));
        Assert.Equal(PlaybackAttemptResult.DecodeFailure, PlaybackFailureClassifier.FromOpenException(new InvalidOperationException("bad")));
    }

    [Theory]
    [InlineData(PlaybackAttemptResult.UnknownFailure, true, PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(PlaybackAttemptResult.Timeout, true, PlaybackAttemptResult.UnsupportedMedia)]
    [InlineData(PlaybackAttemptResult.UnknownFailure, false, PlaybackAttemptResult.UnknownFailure)]
    [InlineData(PlaybackAttemptResult.Timeout, false, PlaybackAttemptResult.Timeout)]
    [InlineData(PlaybackAttemptResult.FirstFrame, true, PlaybackAttemptResult.FirstFrame)]
    [InlineData(PlaybackAttemptResult.Cancelled, true, PlaybackAttemptResult.Cancelled)]
    public void LibVlc_failures_are_refined_only_when_a_missing_decoder_was_logged(
        PlaybackAttemptResult result, bool decoderLogged, PlaybackAttemptResult expected) =>
        Assert.Equal(expected, PlaybackFailureClassifier.RefineLibVlcFailure(result, decoderLogged));

    [Theory]
    [InlineData(PlaybackAttemptResult.UnsupportedMedia, false, true)]
    [InlineData(PlaybackAttemptResult.DecodeFailure, false, true)]
    [InlineData(PlaybackAttemptResult.UnknownFailure, false, true)]
    [InlineData(PlaybackAttemptResult.UnsupportedMedia, true, false)]
    [InlineData(PlaybackAttemptResult.NetworkFailure, false, false)]
    [InlineData(PlaybackAttemptResult.Timeout, false, false)]
    [InlineData(PlaybackAttemptResult.Cancelled, false, false)]
    [InlineData(PlaybackAttemptResult.FirstFrame, false, false)]
    public void Fallback_is_offered_once_and_only_for_format_problems(
        PlaybackAttemptResult result, bool alreadyTried, bool expected) =>
        Assert.Equal(expected, EngineFallbackPolicy.ShouldFallback(result, alreadyTried));

    [Fact]
    public void No_video_grace_applies_to_vod_but_not_to_live_radio()
    {
        Assert.True(EngineFallbackPolicy.AppliesNoVideoGrace(StreamKind.Movie));
        Assert.True(EngineFallbackPolicy.AppliesNoVideoGrace(StreamKind.Episode));
        Assert.False(EngineFallbackPolicy.AppliesNoVideoGrace(StreamKind.Live));
        Assert.True(EngineFallbackPolicy.NoVideoGrace < TimeSpan.FromSeconds(30));
    }
}
