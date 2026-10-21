package com.dev.Tvivo.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Process-wide exclusion so every playback entry point can fail closed during the probe. */
object DeepStreamCheckGate {
    private val held = AtomicBoolean(false)
    @Volatile private var lastStopElapsedMs: Long? = null
    val isHeld: Boolean get() = held.get()

    fun tryAcquire(): Boolean = held.compareAndSet(false, true)
    fun release() { held.set(false) }
    fun recordStreamStopped(elapsedMs: Long = android.os.SystemClock.elapsedRealtime()) {
        lastStopElapsedMs = elapsedMs
    }
    fun cooldownRemainingMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val stoppedAt = lastStopElapsedMs ?: return 0L
        return (60_000L - (nowElapsedMs - stoppedAt)).coerceAtLeast(0L)
    }
}

data class DeepStreamMeasurement(
    val httpStatus: Int? = null,
    val connectMs: Long? = null,
    val firstByteMs: Long? = null,
    val durationMs: Long = 0,
    val bytes: Long = 0,
    val contentLengthBytes: Long? = null,
    val streamBitrateMbps: Double? = null,
    val stalls: Int = 0,
    val stallDurationMs: Long? = null,
    val bytesAtStall: Long? = null,
    val retries: Int = 0,
    val failure: DeepStreamFailure? = null
) {
    val throughputMbps: Double? get() = if (durationMs <= 0 || bytes <= 0) null else bytes * 8.0 / durationMs / 1000.0
}

data class DeepStreamItem(val id: Int, val extension: String, val isLive: Boolean, val durationMinutes: Int? = null)

enum class DeepStreamFailure { CONNECT_TIMEOUT, READ_TIMEOUT, NETWORK, HARD_TIMEOUT }
enum class DeepStreamPhase { STOPPING, COOLDOWN, CONNECTING, MEASURING, COMPLETE, FAILED, CANCELLED }
data class DeepStreamProgress(val phase: DeepStreamPhase, val remainingMs: Long = 0, val measurement: DeepStreamMeasurement? = null)

/** The coordinator is fakeable; automated tests inject the stream reader and never contact a provider. */
class DeepStreamCheckRunner(
    private val stopPlayback: suspend () -> Boolean,
    private val readStream: suspend (String, suspend (Long) -> Unit) -> DeepStreamMeasurement = ::readDeepStream,
    private val nowElapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val hardTimeoutMs: Long = HARD_TIMEOUT_MS
) {
    suspend fun run(url: String, onProgress: (DeepStreamProgress) -> Unit = {}): DeepStreamMeasurement {
        check(DeepStreamCheckGate.tryAcquire()) { "Network check in progress" }
        try {
            return withTimeout(hardTimeoutMs) {
                onProgress(DeepStreamProgress(DeepStreamPhase.STOPPING))
                if (stopPlayback()) DeepStreamCheckGate.recordStreamStopped(nowElapsedMs())
                while (DeepStreamCheckGate.cooldownRemainingMs(nowElapsedMs()) > 0) {
                    val remaining = DeepStreamCheckGate.cooldownRemainingMs(nowElapsedMs())
                    onProgress(DeepStreamProgress(DeepStreamPhase.COOLDOWN, remaining))
                    wait(minOf(1000L, remaining))
                }
                onProgress(DeepStreamProgress(DeepStreamPhase.CONNECTING))
                val result = readStream(url) { bytes ->
                    onProgress(DeepStreamProgress(DeepStreamPhase.MEASURING, measurement = DeepStreamMeasurement(bytes = bytes)))
                }
                onProgress(DeepStreamProgress(if (result.failure == null && result.httpStatus in 200..299)
                    DeepStreamPhase.COMPLETE else DeepStreamPhase.FAILED, measurement = result))
                result
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val result = DeepStreamMeasurement(failure = DeepStreamFailure.HARD_TIMEOUT)
            onProgress(DeepStreamProgress(DeepStreamPhase.FAILED, measurement = result))
            return result
        } catch (cancelled: CancellationException) {
            onProgress(DeepStreamProgress(DeepStreamPhase.CANCELLED))
            throw cancelled
        } finally {
            DeepStreamCheckGate.release()
        }
    }

    companion object {
        const val SAMPLE_MS = 18_000L
        const val HARD_TIMEOUT_MS = 105_000L
    }
}

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private suspend fun readDeepStream(url: String, onBytes: suspend (Long) -> Unit): DeepStreamMeasurement = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    val start = System.nanoTime()
    val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true) { cause ->
        if (cause is CancellationException) conn.disconnect()
    }
    var connectedAt: Long? = null
    var input: java.io.InputStream? = null
    var status: Int? = null
    var contentLength: Long? = null
    try {
        conn.connectTimeout = 8_000
        conn.readTimeout = 2_500
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Tvivo network diagnostics")
        conn.connect()
        val connected = System.nanoTime()
        connectedAt = connected
        val responseStatus = conn.responseCode
        status = responseStatus
        contentLength = conn.contentLengthLong.takeIf { it > 0L }
        if (responseStatus !in 200..299) return@withContext DeepStreamMeasurement(
            httpStatus = responseStatus, connectMs = (connected - start) / 1_000_000,
            contentLengthBytes = contentLength, failure = null
        )
        val stream = conn.inputStream
        input = stream
        measureDeepStreamBody(responseStatus, start, connected, contentLength, stream, onBytes)
    } catch (failure: java.net.SocketTimeoutException) {
        val connected = connectedAt
        val timeoutDurationMs = connected?.let { max(1L, (System.nanoTime() - it) / 1_000_000) }
        DeepStreamMeasurement(httpStatus = status,
            connectMs = connected?.let { (it - start) / 1_000_000 }, durationMs = timeoutDurationMs ?: 0L,
            stalls = if (connected != null) 1 else 0, stallDurationMs = timeoutDurationMs,
            bytesAtStall = if (connected != null) 0L else null,
            failure = if (connected != null) DeepStreamFailure.READ_TIMEOUT else DeepStreamFailure.CONNECT_TIMEOUT)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        DeepStreamMeasurement(httpStatus = status,
            connectMs = connectedAt?.let { (it - start) / 1_000_000 },
            failure = DeepStreamFailure.NETWORK)
    } finally {
        cancellation.dispose()
        runCatching { input?.close() }
        conn.disconnect()
    }
}

internal suspend fun measureDeepStreamBody(
    status: Int,
    startedAtNanos: Long,
    connectedAtNanos: Long,
    contentLengthBytes: Long?,
    stream: java.io.InputStream,
    onBytes: suspend (Long) -> Unit
): DeepStreamMeasurement {
    var bytes = 0L
    var stalls = 0
    var stallDurationMs: Long? = null
    var bytesAtStall: Long? = null
    var firstByteMs: Long? = null
    var lastProgress = System.nanoTime()
    var failure: DeepStreamFailure? = null
    val deadline = connectedAtNanos + DeepStreamCheckRunner.SAMPLE_MS * 1_000_000
    try {
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val count = try { stream.read(ByteArray(32 * 1024)) } catch (timeout: java.net.SocketTimeoutException) {
                stalls++
                stallDurationMs = (System.nanoTime() - lastProgress) / 1_000_000
                bytesAtStall = bytes
                failure = DeepStreamFailure.READ_TIMEOUT
                break
            }
            if (count < 0) break
            if (count > 0) {
                val now = System.nanoTime()
                if (firstByteMs == null) firstByteMs = (now - startedAtNanos) / 1_000_000
                if (now - lastProgress > 1_000_000_000L) {
                    stalls++
                    stallDurationMs = (now - lastProgress) / 1_000_000
                    bytesAtStall = bytes
                }
                lastProgress = now
                bytes += count
                onBytes(bytes)
            }
        }
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        val elapsedSinceProgressMs = (System.nanoTime() - lastProgress) / 1_000_000
        if (bytes > 0L && stalls == 0) {
            stalls++
            stallDurationMs = elapsedSinceProgressMs
            bytesAtStall = bytes
        }
        failure = DeepStreamFailure.NETWORK
    }
    val now = System.nanoTime()
    return DeepStreamMeasurement(httpStatus = status, connectMs = (connectedAtNanos - startedAtNanos) / 1_000_000,
        firstByteMs = firstByteMs, durationMs = max(1L, (now - connectedAtNanos) / 1_000_000),
        bytes = bytes, contentLengthBytes = contentLengthBytes, stalls = stalls,
        stallDurationMs = stallDurationMs, bytesAtStall = bytesAtStall, failure = failure)
}
