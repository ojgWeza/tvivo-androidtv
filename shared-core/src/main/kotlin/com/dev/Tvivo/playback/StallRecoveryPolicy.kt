package com.dev.Tvivo.playback

enum class StallDecision { None, Recover, GiveUp }

class StallRecoveryPolicy(private val timeoutMilliseconds: Long) {
    private var sessionGeneration = 0L
    private var hasSession = false
    private var hasBaseline = false
    private var lastPositionMs = 0L
    private var lastChangeAtMs = 0L
    private var hasAdvanced = false
    private var gaveUp = false
    var recoveryAttempted = false
        private set

    fun beginSession(generation: Long, isRecovery: Boolean) {
        if (!isRecovery) recoveryAttempted = false
        sessionGeneration = generation
        hasSession = true
        hasBaseline = false
        hasAdvanced = false
        gaveUp = false
    }

    fun observe(
        currentGeneration: Long,
        sessionReady: Boolean,
        eligible: Boolean,
        positionMs: Long,
        nowMilliseconds: Long,
    ): StallDecision {
        if (!hasSession || currentGeneration != sessionGeneration || !sessionReady || !eligible) {
            hasBaseline = false
            return StallDecision.None
        }
        if (!hasBaseline) {
            setBaseline(positionMs, nowMilliseconds)
            return StallDecision.None
        }
        if (positionMs != lastPositionMs) {
            val delta = positionMs - lastPositionMs
            val elapsed = (nowMilliseconds - lastChangeAtMs).coerceAtLeast(0)
            if (delta > 0 && delta.toDouble() <= elapsed.toDouble() * 2 + 1_000) hasAdvanced = true
            setBaseline(positionMs, nowMilliseconds)
            return StallDecision.None
        }
        if ((nowMilliseconds - lastChangeAtMs).toDouble() < timeoutMilliseconds.toDouble()) return StallDecision.None
        lastChangeAtMs = nowMilliseconds
        if (gaveUp) return StallDecision.None
        if (hasAdvanced && !recoveryAttempted) {
            recoveryAttempted = true
            return StallDecision.Recover
        }
        gaveUp = true
        return StallDecision.GiveUp
    }

    private fun setBaseline(positionMs: Long, nowMilliseconds: Long) {
        hasBaseline = true
        lastPositionMs = positionMs
        lastChangeAtMs = nowMilliseconds
    }
}
