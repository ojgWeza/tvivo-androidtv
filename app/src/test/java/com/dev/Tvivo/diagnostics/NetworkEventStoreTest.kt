package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
class NetworkEventStoreTest {
    @Test fun `typed events survive serialization without unapproved fields`() {
        val event = NetworkEvent(10_000, NetworkEventSource.PLAYBACK, NetworkEventKind.HTTP_403,
            accountPartition = "aabbccddeeff00112233445566778899", statusCode = 403, phase = "stream source")
        val json = NetworkEventStore.encode(listOf(event))
        val file = File.createTempFile("network-events", ".json")
        try {
            file.writeText(json)
            assertEquals(listOf(event), NetworkEventStore.read(file, 10_100))
            listOf("url", "host", "ssid", "password", "ipAddress", "title").forEach {
                assertFalse(json.contains("\"$it\""))
            }
        } finally { file.delete() }
    }

    @Test fun `store enforces retention age and capacity on restore`() {
        val now = TimeUnit.DAYS.toMillis(10)
        val rows = (0..110).map { index -> NetworkEvent(now - index * 1_000L,
            NetworkEventSource.CONNECTIVITY, NetworkEventKind.LINK_CHANGED) }
        val file = File.createTempFile("network-events", ".json")
        try {
            file.writeText(NetworkEventStore.encode(rows))
            assertEquals(100, NetworkEventStore.read(file, now).size)
            val old = NetworkEvent(now - TimeUnit.DAYS.toMillis(8), NetworkEventSource.CONNECTIVITY,
                NetworkEventKind.LOST)
            file.writeText(NetworkEventStore.encode(listOf(old)))
            assertTrue(NetworkEventStore.read(file, now).isEmpty())
        } finally { file.delete() }
    }

    @Test fun `account partition filter retains device timeline and isolates errors`() {
        NetworkEventStore.append(NetworkEvent(1, NetworkEventSource.CONNECTIVITY, NetworkEventKind.LOST))
        NetworkEventStore.append(NetworkEvent(2, NetworkEventSource.PLAYBACK, NetworkEventKind.HTTP_403,
            accountPartition = "aabbccddeeff00112233445566778899"))
        assertTrue(NetworkEventStore.forAccount("accounttwo").any { it.source == NetworkEventSource.CONNECTIVITY })
        assertFalse(NetworkEventStore.forAccount("00112233445566778899aabbccddeeff").any { it.kind == NetworkEventKind.HTTP_403 })
    }

    @Test fun `recorder classifies HTTP DNS timeout and TLS failures without exception text`() {
        val account = "00112233445566778899aabbccddeeff"
        NetworkEventRecorder.http(NetworkEventSource.CATALOG, account, 429, "catalog sync")
        NetworkEventRecorder.failure(NetworkEventSource.PLAYBACK, account,
            UnknownHostException("provider.example.invalid credential=secret"), "stream source")
        NetworkEventRecorder.failure(NetworkEventSource.PLAYBACK, account,
            SocketTimeoutException("https://user:pass@example.invalid"), "stream source")
        NetworkEventRecorder.failure(NetworkEventSource.PLAYBACK, account,
            SSLHandshakeException("certificate for secret.example.invalid"), "stream source")
        val recorded = NetworkEventStore.forAccount(account)
        assertTrue(recorded.any { it.kind == NetworkEventKind.HTTP_429 && it.statusCode == 429 })
        assertTrue(recorded.any { it.kind == NetworkEventKind.DNS_FAILURE })
        assertTrue(recorded.any { it.kind == NetworkEventKind.TIMEOUT })
        assertTrue(recorded.any { it.kind == NetworkEventKind.TLS_FAILURE })
        assertFalse(NetworkEventStore.encode(recorded).contains("provider.example.invalid"))
        assertFalse(NetworkEventStore.encode(recorded).contains("secret"))
    }
}
