package com.dev.Tvivo.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventRecorder
import com.dev.Tvivo.diagnostics.NetworkEventSource

/** A progressive extractor may request another range before disposing the previous HTTP source. */
internal class SingleStreamConnectionGate {
    private var activeOwner: Any? = null
    private var closeActive: (() -> Unit)? = null
    private var lastCloseNanos = 0L

    @Synchronized
    fun <T> open(owner: Any, close: () -> Unit, action: (Boolean) -> T): T {
        val previousClose = closeActive
        activeOwner = null
        closeActive = null
        previousClose?.invoke()
        if (previousClose != null) lastCloseNanos = System.nanoTime()
        val recentlyClosed = lastCloseNanos != 0L &&
            System.nanoTime() - lastCloseNanos in 0L..RECENT_CLOSE_NANOS
        val result = action(recentlyClosed)
        activeOwner = owner
        closeActive = close
        return result
    }

    @Synchronized
    fun close(owner: Any) {
        if (activeOwner !== owner) return
        val action = closeActive
        activeOwner = null
        closeActive = null
        action?.invoke()
        if (action != null) lastCloseNanos = System.nanoTime()
    }

    private companion object { const val RECENT_CLOSE_NANOS = 1_000_000_000L }
}

/** A 403 immediately after closing a range may arrive before the server sees the socket close. */
internal fun <T> openAfterRecentClose(
    recentlyClosed: Boolean,
    open: () -> T,
    cleanup: () -> Unit,
    isConnectionLimit: (Exception) -> Boolean,
): T = openAfterRecentClose(recentlyClosed, open, cleanup, null, isConnectionLimit)

internal fun <T> openAfterRecentClose(
    recentlyClosed: Boolean,
    open: () -> T,
    cleanup: () -> Unit,
    accountPartition: String?,
    isConnectionLimit: (Exception) -> Boolean,
): T {
    var retries = 0
    while (true) {
        try {
            return open()
        } catch (failure: Exception) {
            cleanup()
            if (failure is HttpDataSource.InvalidResponseCodeException && failure.responseCode == 403 && recentlyClosed) {
                NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, accountPartition,
                    NetworkEventKind.HANDOFF_COOLDOWN_403, "recent stream close")
            }
            if (!recentlyClosed || !isConnectionLimit(failure) || retries == 2) throw failure
            retries++
            Thread.sleep(25L * retries)
        }
    }
}

/** The playback service shares one gate across every Media3 data source it creates. */
internal class SingleStreamDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val accountPartition: () -> String? = { null },
) : DataSource.Factory {
    // Service replacement can create a new factory before the previous service is gone.
    // Keep the release-before-open rule across both factories, not only within one player.
    private val gate = sharedGate

    private companion object {
        val sharedGate = SingleStreamConnectionGate()
    }

    override fun createDataSource(): DataSource = object : DataSource {
        private val delegate = upstream.createDataSource()

        override fun open(dataSpec: DataSpec): Long = gate.open(this, { delegate.close() }) { recentlyClosed ->
            openAfterRecentClose(
                recentlyClosed,
                { delegate.open(dataSpec) },
                { delegate.close() },
                accountPartition(),
                { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 403 }
            )
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun addTransferListener(transferListener: TransferListener) =
            delegate.addTransferListener(transferListener)

        override fun close() = gate.close(this)
    }
}
