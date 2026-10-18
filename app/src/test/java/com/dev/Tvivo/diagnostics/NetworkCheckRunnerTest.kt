package com.dev.Tvivo.diagnostics

import com.dev.Tvivo.auth.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class NetworkCheckRunnerTest {
    private val credentials = Credentials("panel.invalid", 80, "user", "secret", false)
    private val snapshot = NetworkSnapshot(1, metered = false)
    private val config = NetworkProbeConfig("https://neutral.invalid/204", "https://neutral.invalid/download", "https://neutral.invalid/dns")

    @Test fun panelStatusesAreClassifiedAndNoStreamUrlIsRequested() = runTest {
        for (status in listOf(200, 403, 429, 503, 504)) {
            val requested = mutableListOf<String>()
            val runner = NetworkCheckRunner(config, request = { url, _, _ ->
                requested += url
                HttpResult(if (url.startsWith(credentials.baseUrl())) status else if (url == config.reachabilityUrl) 204 else 200,
                    dateMs = System.currentTimeMillis())
            }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true }, gateway = { true to "TCP gateway probe" })
            val result = runner.run(snapshot, credentials, playbackActive = false, confirmMeteredQuality = false)
            assertEquals(status, result.probes.providerStatus)
            assertEquals(if (status == 200) CheckState.PASS else CheckState.FAIL, result.probes.providerControl)
            assertTrue(requested.any { it.startsWith("${credentials.playerApiUrl()}?") })
            assertTrue(requested.none { it.contains("/movie/") || it.contains("/series/") || it.contains("/live/") })
        }
    }

    @Test fun redirectIsReportedAsPossibleCaptivePortal() = runTest {
        val runner = NetworkCheckRunner(config, request = { url, _, _ ->
            if (url == config.reachabilityUrl) HttpResult(302, redirected = true) else HttpResult(200)
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true })
        val result = runner.run(snapshot, credentials, false, false)
        assertEquals(CheckState.FAIL, result.probes.internet)
        assertEquals(true, result.probes.captiveRedirect)
    }

    @Test fun timeoutAndTlsFailuresAreNamedWithoutSurfacingRequestUrl() = runTest {
        val timed = NetworkCheckRunner(config, request = { url, _, _ ->
            if (url.startsWith(credentials.baseUrl())) throw SocketTimeoutException("contains secret URL")
            HttpResult(if (url == config.reachabilityUrl) 204 else 200, dateMs = System.currentTimeMillis())
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true })
        val timeout = timed.run(snapshot, credentials, false, false)
        val panel = timeout.tests.first { it.id == ProbeId.PANEL }
        assertEquals(ProbeStatus.FAIL, panel.status)
        assertEquals("Timed out", panel.detail)
        assertFalse(panel.detail.contains("secret"))

        val tls = NetworkCheckRunner(config, request = { url, _, _ ->
            if (url == config.reachabilityUrl) throw SSLHandshakeException("certificate detail")
            HttpResult(200)
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true })
        assertTrue(tls.run(snapshot, credentials, false, false).tests.any { it.detail.startsWith("TLS failure") })
    }

    @Test fun clockHeaderOffsetAndIpv4OnlyObservationAreReported() = runTest {
        val fixedNow = 1_800_000_000_000L
        val runner = NetworkCheckRunner(config, request = { url, _, _ ->
            HttpResult(if (url == config.reachabilityUrl) 204 else 200,
                dateMs = if (url == config.reachabilityUrl) fixedNow + 600_000 else null)
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true }, clock = { fixedNow })
        val result = runner.run(snapshot, credentials, false, false)
        assertEquals(600_000L, result.probes.clockDateOffsetMs)
        assertEquals(true, result.probes.ipv4HttpsSucceeded)
        assertTrue(result.tests.first { it.id == ProbeId.IP_FAMILIES }.detail.contains("IPv6 HTTPS attempts no address"))
    }

    @Test fun playbackSkipsProviderAndQualityProbes() = runTest {
        val runner = NetworkCheckRunner(config, request = { url, _, _ ->
            HttpResult(if (url == config.reachabilityUrl) 204 else 200, dateMs = System.currentTimeMillis())
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true })
        val result = runner.run(snapshot, credentials, playbackActive = true, confirmMeteredQuality = false)
        assertEquals(CheckState.SKIPPED, result.probes.providerControl)
        assertEquals(ProbeStatus.SKIPPED, result.tests.first { it.id == ProbeId.THROUGHPUT }.status)
    }

    @Test fun qualityProbePassesStrictThreeMiBCapAndRequiresMeteredConfirmation() = runTest {
        val limits = mutableListOf<Long>()
        val runner = NetworkCheckRunner(config, request = { url, _, maxBytes ->
            limits += maxBytes
            HttpResult(if (url == config.reachabilityUrl) 204 else 200,
                dateMs = System.currentTimeMillis(), bytesRead = 9_000_000, elapsedMs = 1000)
        }, resolve = { listOf(InetAddress.getByName("192.0.2.1")) }, addressProbe = { _, _ -> true })
        val skipped = runner.run(snapshot.copy(metered = true), credentials, false, false)
        assertEquals(ProbeStatus.SKIPPED, skipped.tests.first { it.id == ProbeId.THROUGHPUT }.status)
        val confirmed = runner.run(snapshot.copy(metered = true), credentials, false, true)
        assertEquals(3L * 1024 * 1024, confirmed.throughputBytes)
        assertTrue(limits.contains(3L * 1024 * 1024))
    }

    @Test fun cancellationPropagatesAndEmitsCancelledProgress() = runTest {
        val seen = mutableListOf<ProbeProgress>()
        val runner = NetworkCheckRunner(config, resolve = { throw CancellationException("cancel") })
        try {
            runner.run(snapshot, credentials, false, false) { seen += it }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(seen.any { it.status == ProbeStatus.CANCELLED })
    }

    @Test fun repeatedIpv6TlsFailuresComparedWithSuccessfulIpv4AreReported() = runTest {
        val addresses = listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("2001:db8::1"))
        val runner = NetworkCheckRunner(config, request = { url, _, _ ->
            HttpResult(if (url == config.reachabilityUrl) 204 else 200, dateMs = System.currentTimeMillis())
        }, resolve = { addresses }, addressProbe = { address, _ -> address.address.size == 4 })
        val result = runner.run(snapshot, credentials, false, false)
        assertEquals(true, result.probes.ipv4HttpsSucceeded)
        assertEquals(2, result.probes.ipv6FailureCount)
        assertTrue(result.tests.first { it.id == ProbeId.IP_FAMILIES }.detail.contains("IPv6 HTTPS attempts failed, failed"))
    }
}
