package com.dev.Tvivo.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleStreamConnectionGateTest {
    @Test fun overlappingRangeOpenClosesPreviousConnectionFirst() {
        val gate = SingleStreamConnectionGate()
        val events = mutableListOf<String>()
        var active = 0
        var peak = 0
        val first = Any()
        val second = Any()
        gate.open(first, { events += "close first"; active-- }) {
            events += "open first"; active++; peak = maxOf(peak, active)
        }
        gate.open(second, { events += "close second"; active-- }) {
            events += "open second"; active++; peak = maxOf(peak, active)
        }
        gate.close(first)
        gate.close(second)
        assertEquals(listOf("open first", "close first", "open second", "close second"), events)
        assertEquals(1, peak)
        assertEquals(0, active)
    }

    @Test fun firstOpenFromReplacementFactoryReleasesOldConnectionBeforeRequest() {
        var active = 0
        var peak = 0
        val events = mutableListOf<String>()
        val upstream = object : DataSource.Factory {
            override fun createDataSource(): DataSource = object : DataSource {
                private var opened = false
                override fun open(dataSpec: DataSpec): Long {
                    if (active != 0) error("mock provider rejected a second connection")
                    active++
                    peak = maxOf(peak, active)
                    opened = true
                    events += "open"
                    return 1L
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
                override fun getUri(): Uri? = null
                override fun close() {
                    if (opened) {
                        opened = false
                        active--
                        events += "close"
                    }
                }
                override fun addTransferListener(transferListener: TransferListener) = Unit
            }
        }
        val spec = DataSpec(Uri.parse("https://example.invalid/movie.mp4"))
        val previous = SingleStreamDataSourceFactory(upstream).createDataSource()
        val replacement = SingleStreamDataSourceFactory(upstream).createDataSource()
        previous.open(spec)
        assertEquals(1L, replacement.open(spec))
        previous.close() // A late old-service close must not close the new stream.
        assertEquals(1, active)
        replacement.close()
        assertEquals(listOf("open", "close", "open", "close"), events)
        assertEquals(1, peak)
        assertTrue(active == 0)
    }

    @Test fun rangeOpenAbsorbsConnectionLimitWhilePriorCloseReachesProvider() {
        val gate = SingleStreamConnectionGate()
        var active = 0
        var peak = 0
        var attempts = 0
        val first = Any()
        val second = Any()
        gate.open(first, { active-- }) { active++; peak = maxOf(peak, active) }
        gate.open(second, { active-- }) { recentlyClosed ->
            openAfterRecentClose(recentlyClosed, {
                attempts++
                if (attempts == 1) throw IOException("connection limit 403 pending close")
                if (active != 0) error("second connection")
                active++
                peak = maxOf(peak, active)
            }, {}, { it is IOException && it.message?.contains("403") == true })
        }
        assertEquals(2, attempts)
        assertEquals(1, peak)
        assertEquals(1, active)
        gate.close(second)
        assertEquals(0, active)
    }

    @Test fun unrelatedForbiddenResponseIsNotRetried() {
        var attempts = 0
        val failure = runCatching {
            openAfterRecentClose(false, {
                attempts++
                throw IOException("403")
            }, {}, { it is IOException })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, attempts)
    }
}
