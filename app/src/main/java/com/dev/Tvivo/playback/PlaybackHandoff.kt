package com.dev.Tvivo.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All stream opens and closes pass through this gate. The generation also invalidates late work. */
internal class PlaybackHandoff(
    private val port: Port,
) {
    interface Port {
        suspend fun save(item: Item, ended: Boolean, allowCompletion: Boolean)
        fun close()
        fun open(item: Item, generation: Long)
    }

    data class Item(
        val requestId: String,
        val url: String,
        val title: String,
        val accountId: String?,
        val contentType: String?,
        val itemId: String?,
        val isLive: Boolean,
        val resumeFromMs: Long,
        val playlist: List<QueueItem> = emptyList(),
        val playlistIndex: Int = -1,
    )

    data class QueueItem(val id: String, val url: String, val title: String)

    private val mutex = Mutex()
    private var issued = 0L
    private var active: Item? = null
    private var activeGeneration = 0L

    val current: Item? get() = active
    val generation: Long get() = activeGeneration

    suspend fun play(item: Item, previousEnded: Boolean = false) {
        if (active?.requestId == item.requestId) return
        val ticket = ++issued
        mutex.withLock {
            if (ticket != issued || active?.requestId == item.requestId) return@withLock
            val previous = active
            try {
                if (previous != null) port.save(previous, ended = previousEnded, allowCompletion = true)
            } finally {
                port.close()
                active = null
                activeGeneration = 0L
            }
            if (ticket != issued) return@withLock
            try {
                port.open(item, ticket)
            } catch (failure: Exception) {
                port.close()
                throw failure
            }
            active = item
            activeGeneration = ticket
        }
    }

    suspend fun stop(ended: Boolean = false, allowCompletion: Boolean = true) {
        ++issued
        mutex.withLock {
            val previous = active
            try {
                if (previous != null) port.save(previous, ended, allowCompletion)
            } finally {
                port.close()
                active = null
                activeGeneration = 0L
            }
        }
    }

    /** Service teardown must run even if saving the last position fails. */
    suspend fun stopAndFinish(ended: Boolean = false, allowCompletion: Boolean = true, finish: () -> Unit) {
        try {
            stop(ended, allowCompletion)
        } finally {
            finish()
        }
    }

    /** Reopens the same item only after closing its old connection; every callback gets a fresh generation. */
    suspend fun reopenCurrent(positionMs: Long? = null): Long? = mutex.withLock {
        val item = active ?: return@withLock null
        val ticket = ++issued
        port.close()
        activeGeneration = 0L
        val resumed = item.copy(resumeFromMs = if (item.isLive) 0L else (positionMs ?: item.resumeFromMs).coerceAtLeast(0L))
        port.open(resumed, ticket)
        active = resumed
        activeGeneration = ticket
        ticket
    }

    suspend fun checkpoint(allowCompletion: Boolean = true) = mutex.withLock {
        active?.let { port.save(it, ended = false, allowCompletion = allowCompletion) }
    }

    fun isCurrent(generation: Long): Boolean =
        generation != 0L && generation == activeGeneration && generation == issued
}
