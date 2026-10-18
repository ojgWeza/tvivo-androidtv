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
    var connectedSuccessfully = false
    var input: java.io.InputStream? = null
    try {
        conn.connectTimeout = 8_000
        conn.readTimeout = 2_500
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Tvivo network diagnostics")
        conn.connect()
        connectedSuccessfully = true
        val connected = System.nanoTime()
        val status = conn.responseCode
        val contentLength = conn.contentLengthLong.takeIf { it > 0L }
        if (status !in 200..299) return@withContext DeepStreamMeasurement(
            httpStatus = status, connectMs = (connected - start) / 1_000_000,
            contentLengthBytes = contentLength, failure = null
        )
        var bytes = 0L
        var stalls = 0
        var firstByte: Long? = null
        var lastProgress = System.nanoTime()
        val deadline = connected + DeepStreamCheckRunner.SAMPLE_MS * 1_000_000
        val stream = conn.inputStream
        input = stream
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val count = try { stream.read(ByteArray(32 * 1024)) } catch (failure: java.net.SocketTimeoutException) {
                stalls++
                continue
            }
            if (count < 0) break
            if (count > 0) {
                val now = System.nanoTime()
                if (firstByte == null) firstByte = (now - start) / 1_000_000
                if (now - lastProgress > 1_000_000_000L) stalls++
                lastProgress = now
                bytes += count
                onBytes(bytes)
            }
        }
        val duration = max(1L, (System.nanoTime() - connected) / 1_000_000)
        DeepStreamMeasurement(httpStatus = status, connectMs = (connected - start) / 1_000_000,
            firstByteMs = firstByte, durationMs = duration, bytes = bytes, contentLengthBytes = contentLength, stalls = stalls)
    } catch (failure: java.net.SocketTimeoutException) {
        DeepStreamMeasurement(connectMs = (System.nanoTime() - start) / 1_000_000,
            failure = if (connectedSuccessfully) DeepStreamFailure.READ_TIMEOUT else DeepStreamFailure.CONNECT_TIMEOUT)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        DeepStreamMeasurement(failure = DeepStreamFailure.NETWORK)
    } finally {
        cancellation.dispose()
        runCatching { input?.close() }
        conn.disconnect()
    }
}
