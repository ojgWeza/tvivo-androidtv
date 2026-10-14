using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class PlaybackInterruptionPolicyTests
{
    [Theory]
    [InlineData(PlaybackEngineState.Failed, PlaybackInterruption.Error)]
    [InlineData(PlaybackEngineState.Ended, PlaybackInterruption.UnexpectedEnd)]
    [InlineData(PlaybackEngineState.Stopped, PlaybackInterruption.UnexpectedEnd)]
    [InlineData(PlaybackEngineState.Paused, PlaybackInterruption.None)]
    [InlineData(PlaybackEngineState.Playing, PlaybackInterruption.Stall)]
    [InlineData(PlaybackEngineState.Buffering, PlaybackInterruption.Stall)]
    public void Classifies_engine_interruptions(PlaybackEngineState state, PlaybackInterruption expected)
    {
        var snapshot = Snapshot(state) with { Failure = state == PlaybackEngineState.Failed ? PlaybackAttemptResult.NetworkFailure : null };
        Assert.Equal(expected, PlaybackInterruptionPolicy.Classify(snapshot));
    }

    [Theory]
    [InlineData(PlaybackEngineState.Playing)]
    [InlineData(PlaybackEngineState.Buffering)]
    public void Progressing_or_recently_advanced_playback_is_healthy(PlaybackEngineState state)
    {
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(state) with { NoForwardProgressFor = TimeSpan.FromSeconds(7) }));
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(state) with { HasForwardProgress = false }));
    }

    [Fact]
    public void Paused_intent_suppresses_callbacks_even_when_the_engine_lags_or_stops()
    {
        var intent = PlaybackInterruptionPolicy.ToggleIntent(PlaybackUserIntent.Playing);
        Assert.Equal(PlaybackUserIntent.Paused, intent);
        foreach (var state in new[] { PlaybackEngineState.Playing, PlaybackEngineState.Paused,
                     PlaybackEngineState.Stopped, PlaybackEngineState.Ended, PlaybackEngineState.Failed })
        {
            Assert.Equal(PlaybackInterruption.None,
                PlaybackInterruptionPolicy.Classify(Snapshot(state) with { Intent = intent }));
        }
    }

    [Fact]
    public void Explicit_resume_suppresses_recovery_until_forward_progress_then_real_stall_recovers()
    {
        var intent = PlaybackInterruptionPolicy.ToggleIntent(PlaybackUserIntent.Paused);
        Assert.Equal(PlaybackUserIntent.Playing, intent);
        var awaitingProgress = PlaybackInterruptionPolicy.ShouldAwaitForwardProgress(intent);
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Playing) with
            {
                Intent = intent,
                AwaitingForwardProgress = awaitingProgress,
            }));

        // A confirmed position advance is the only event that releases post-action suppression.
        awaitingProgress = false;
        Assert.Equal(PlaybackInterruption.Stall,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Playing) with
            {
                Intent = intent,
                AwaitingForwardProgress = awaitingProgress,
            }));
    }

    [Fact]
    public void Seek_preserves_pause_or_resumes_playing_intent_and_waits_for_progress()
    {
        Assert.Equal(PlaybackUserIntent.Paused,
            PlaybackInterruptionPolicy.IntentAfterSeek(PlaybackUserIntent.Paused));
        var playingIntent = PlaybackInterruptionPolicy.IntentAfterSeek(PlaybackUserIntent.Playing);
        Assert.True(PlaybackInterruptionPolicy.ShouldAwaitForwardProgress(playingIntent));
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Stopped) with
            {
                Intent = PlaybackUserIntent.StoppedByUser,
            }));
    }

    [Fact]
    public void Back_and_stop_intent_are_set_before_engine_stop_callbacks()
    {
        var intent = PlaybackUserIntent.Playing;
        intent = PlaybackUserIntent.StoppedByUser; // Back/navigation writes intent before engine.Stop.
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Stopped) with { Intent = intent }));
        Assert.Equal(PlaybackInterruption.None,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Ended) with { Intent = intent }));
    }

    [Fact]
    public void Playing_intent_recovers_from_error_early_end_and_frozen_position()
    {
        Assert.Equal(PlaybackInterruption.Error,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Failed) with
            {
                Failure = PlaybackAttemptResult.NetworkFailure,
            }));
        Assert.Equal(PlaybackInterruption.UnexpectedEnd,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Ended) with
            {
                PositionMilliseconds = 10_000,
                DurationMilliseconds = 100_000,
            }));
        Assert.Equal(PlaybackInterruption.Stall,
            PlaybackInterruptionPolicy.Classify(Snapshot(PlaybackEngineState.Buffering)));
    }

    [Fact]
    public void Genuine_end_uses_the_shared_thirty_second_tolerance()
    {
        Assert.Equal(PlaybackInterruption.None, PlaybackInterruptionPolicy.Classify(
            Snapshot(PlaybackEngineState.Ended) with { PositionMilliseconds = 70_000, DurationMilliseconds = 100_000 }));
    }

    [Fact]
    public void Stale_session_generation_is_ignored()
    {
        Assert.Equal(PlaybackInterruption.None, PlaybackInterruptionPolicy.Classify(
            Snapshot(PlaybackEngineState.Failed) with { SessionGeneration = 3, CurrentGeneration = 4 }));
    }

    [Theory]
    [InlineData(403, false)]
    [InlineData(429, false)]
    [InlineData(null, true)]
    public void Forbidden_and_connection_limit_stop_without_retry(int? status, bool connectionLimit)
    {
        Assert.Equal(PlaybackRecoveryAction.Stop, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Error, StreamKind.Movie, true, 0, 5, status, connectionLimit));
    }

    [Fact]
    public void Forbidden_failure_result_stops_without_retry_even_without_status()
    {
        Assert.Equal(PlaybackRecoveryAction.Stop, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Error, StreamKind.Movie, true, 0, 5, failure: PlaybackAttemptResult.Forbidden));
    }

    [Fact]
    public void Exhausted_budget_stops_and_live_reopens_without_seeking()
    {
        Assert.Equal(PlaybackRecoveryAction.Stop, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Stall, StreamKind.Movie, true, 5, 5));
        Assert.Equal(PlaybackRecoveryAction.Reopen, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Stall, StreamKind.Live, true, 0, 5));
    }

    [Fact]
    public void VOD_resumes_in_place_when_media_exists_and_live_or_missing_media_reopens()
    {
        Assert.Equal(PlaybackRecoveryAction.ResumeSameMedia, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Stall, StreamKind.Movie, true, 0, 5));
        Assert.Equal(PlaybackRecoveryAction.ResumeSameMedia, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.UnexpectedEnd, StreamKind.Movie, true, 0, 5));
        Assert.Equal(PlaybackRecoveryAction.Reopen, PlaybackInterruptionPolicy.NextAction(
            PlaybackInterruption.Stall, StreamKind.Movie, false, 0, 5));
    }

    [Fact]
    public void Budget_exhaustion_and_thirty_seconds_of_healthy_progress_reset_attempts()
    {
        var budget = new PlaybackInterruptionBudget();
        Assert.True(budget.TryConsume(2));
        Assert.True(budget.TryConsume(2));
        Assert.False(budget.TryConsume(2));
        budget.RecordForwardProgress(TimeSpan.Zero);
        budget.RecordForwardProgress(TimeSpan.FromSeconds(29));
        Assert.Equal(2, budget.AttemptsUsed);
        budget.RecordForwardProgress(TimeSpan.FromSeconds(30));
        Assert.Equal(0, budget.AttemptsUsed);
        Assert.True(budget.TryConsume(2));
    }

    private static PlaybackInterruptionSnapshot Snapshot(PlaybackEngineState state) => new(
        state,
        Failure: null,
        NoForwardProgressFor: TimeSpan.FromSeconds(8),
        HasForwardProgress: true,
        PositionMilliseconds: 10_000,
        DurationMilliseconds: 100_000,
        Intent: PlaybackUserIntent.Playing,
        AwaitingForwardProgress: false,
        SessionGeneration: 4,
        CurrentGeneration: 4);
}
