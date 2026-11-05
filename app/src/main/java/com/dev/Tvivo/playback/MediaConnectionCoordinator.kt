package com.dev.Tvivo.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** One process-wide reservation for provider media endpoints. Labels must never contain URLs. */
class MediaConnectionCoordinator(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = Math::random,
    val quietGapMillis: Long = 6_000L,
    private val playbackAckTimeoutMillis: Long = 2_000L,
    private val maxConnectAttempts: Int = 4,
) {
    init {
        require(quietGapMillis >= 0 && playbackAckTimeoutMillis > 0 && maxConnectAttempts in 1..10)
    }

    enum class Kind { PLAYBACK, DOWNLOAD, PROBE }
    data class Owner(val kind: Kind, val label: String, val accountId: String? = null)
    data class Snapshot(
        val owner: Owner?,
        val playbackSessionActive: Boolean,
        val downloadsQueued: Int,
        val probesQueued: Int,
    )
    class Preempted : CancellationException("Media slot preempted by playback")

    private class Waiter {
        val signal = Channel<Unit>(Channel.CONFLATED)
    }
    private class Lease(val owner: Owner, val job: Job) {
        @Volatile var preempted = false
    }

    private val lock = Any()
    private val downloads = ArrayDeque<Waiter>()
    private val probes = ArrayDeque<Waiter>()
    private var lease: Lease? = null
    private var playbackToken: Any? = null
    private var playbackLabel = "playback"
    private var playbackAccountId: String? = null
    private var playbackOpens = 0
    private var playbackEnding = false
    private var lastCloseAt: Long? = null
    private val mutableState = MutableStateFlow(Snapshot(null, false, 0, 0))
    val state: StateFlow<Snapshot> = mutableState

    /** Called before setMediaItem/prepare. A later service may replace an older service's token. */
    fun beginPlaybackSession(token: Any, label: String = "playback", accountId: String? = null) {
        require("://" !in label && '?' !in label) { "Media label must not be a URL" }
        synchronized(lock) {
            if (playbackToken !== token) playbackOpens = 0
            playbackToken = token
            playbackLabel = label
            playbackAccountId = accountId
            playbackEnding = false
            lease?.also { current ->
                current.preempted = true
                current.job.cancel(Preempted())
            }
            changed()
        }
    }

    /** Call only after player.stop/clear/release has closed the source. Stale tokens cannot release a new session. */
    fun endPlaybackSession(token: Any) {
        synchronized(lock) {
            if (playbackToken !== token) return
            playbackEnding = true
            if (playbackOpens == 0) finishPlayback()
        }
    }

    /** Media3 loader-thread bridge; never blocks the main thread. */
    @Throws(IOException::class)
    fun awaitPlaybackReadyBlocking(token: Any) {
        synchronized(lock) {
            if (playbackToken !== token) throw IOException("Playback session is no longer active")
            if (lease == null) {
                playbackOpens++
                return
            }
        }
        try {
            runBlocking {
                withTimeout(playbackAckTimeoutMillis) {
                    while (true) {
                        val ready = synchronized(lock) {
                            if (playbackToken !== token) throw IOException("Playback session is no longer active")
                            (lease == null).also { if (it) playbackOpens++ }
                        }
                        if (ready) break
                        waitMillis(10L)
                    }
                }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("Previous media holder did not close in time", failure)
        }
    }

    /** Paired with a successful bridge entry, including a subsequent failed open. */
    fun playbackSourceClosed(token: Any) {
        synchronized(lock) {
            if (playbackToken !== token) return
            check(playbackOpens > 0)
            playbackOpens--
            if (playbackOpens == 0 && playbackEnding) finishPlayback()
        }
    }

    private fun finishPlayback() {
        playbackToken = null
        playbackEnding = false
        lastCloseAt = clockMillis()
        changed()
    }

    /** The block must close its socket in finally. On a connection failure it may run again. */
    suspend fun <T> withSlot(kind: Kind, label: String, accountId: String? = null, block: suspend () -> T): T {
        require(kind != Kind.PLAYBACK) { "Playback uses a service session" }
        require("://" !in label && '?' !in label) { "Media label must not be a URL" }
        var attempt = 0
        while (true) {
            attempt++
            try {
                return withBackgroundSlot(kind, label, accountId, block)
            } catch (failure: IOException) {
                if (!isConnectFailure(failure) || attempt >= maxConnectAttempts) throw failure
                val backoff = 1_000L shl (attempt - 1)
                waitMillis(backoff + (backoff * jitter().coerceIn(0.0, 1.0) / 4).toLong())
            }
        }
    }

    private suspend fun <T> withBackgroundSlot(kind: Kind, label: String, accountId: String?, block: suspend () -> T): T {
        val waiter = Waiter()
        synchronized(lock) {
            if (kind == Kind.DOWNLOAD) downloads.addLast(waiter) else probes.addLast(waiter)
            changed()
        }
        var acquired: Lease? = null
        try {
            while (acquired == null) {
                val gap = synchronized(lock) {
                    val head = if (kind == Kind.DOWNLOAD) downloads.firstOrNull() === waiter
                        else downloads.isEmpty() && probes.firstOrNull() === waiter
                    if (head && playbackToken == null && lease == null) {
                        val remaining = lastCloseAt?.let { quietGapMillis - (clockMillis() - it) } ?: 0L
                        if (remaining <= 0L) {
                            if (kind == Kind.DOWNLOAD) downloads.removeFirst() else probes.removeFirst()
                            acquired = Lease(Owner(kind, label, accountId), coroutineContext[Job] ?: error("Missing job"))
                            lease = acquired
                            changed()
                            0L
                        } else remaining
                    } else -1L
                }
                if (acquired != null) break
                if (gap > 0L) withTimeoutOrNull(gap) { waiter.signal.receive() }
                else waiter.signal.receive()
            }
            val result = block()
            if (acquired?.preempted == true) throw Preempted()
            coroutineContext.ensureActive()
            return result
        } catch (failure: CancellationException) {
            if (acquired?.preempted == true) throw Preempted()
            throw failure
        } finally {
            synchronized(lock) {
                downloads.remove(waiter)
                probes.remove(waiter)
                if (lease === acquired && acquired != null) {
                    lease = null
                    lastCloseAt = clockMillis()
                }
                changed()
            }
            waiter.signal.close()
        }
    }

    private fun changed() {
        mutableState.value = Snapshot(
            lease?.owner ?: playbackToken?.let { Owner(Kind.PLAYBACK, playbackLabel, playbackAccountId) },
            playbackToken != null, downloads.size, probes.size,
        )
        downloads.forEach { it.signal.trySend(Unit) }
        probes.forEach { it.signal.trySend(Unit) }
    }

    private fun isConnectFailure(failure: IOException): Boolean =
        failure is ConnectException || failure is SocketTimeoutException

    companion object {
        val shared = MediaConnectionCoordinator()
    }
}
