package com.dev.Tvivo.download

import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.diagnostics.NetworkEvent
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventSource
import com.dev.Tvivo.diagnostics.NetworkEventStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadNoLeakTest {
    private val source = "http://host/movie/USER/PASS/123.mp4?token=SECRET"
    private val sensitive = listOf(source, "USER", "PASS", "SECRET")

    @Test fun workRequestContainsNoSourceOrCredentials() {
        val data = DownloadController.buildRequest().workSpec.input
        assertTrue(data.keyValueMap.isEmpty())
        assertNoSensitiveValues(data.toString())
    }

    @Test fun roomDownloadRowHasNoSourceUrlField() {
        assertFalse(DownloadEntity::class.java.declaredFields.any { it.name.contains("url", ignoreCase = true) })
        val row = DownloadEntity("account", "movie", 0, "123", "mp4", "QUEUED",
            relativePath = "movie/123/media.mp4", createdAt = 0, updatedAt = 0)
        assertNoSensitiveValues(row.toString())
    }

    @Test fun diagnosticFailureEventCannotStoreUrlOrCredentials() {
        val encoded = NetworkEventStore.encode(listOf(NetworkEvent(1, NetworkEventSource.CATALOG,
            NetworkEventKind.NETWORK_FAILURE, phase = "network")))
        assertNoSensitiveValues(encoded)
    }

    private fun assertNoSensitiveValues(value: String) {
        sensitive.forEach { assertFalse("unexpected sensitive value", value.contains(it)) }
    }
}
