package com.dev.Tvivo.diagnostics

import com.dev.Tvivo.auth.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlin.math.abs

/** Public endpoints are replaceable as one unit and are disclosed in the diagnostics UI. */
data class NetworkProbeConfig(
    val reachabilityUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
    val qualityUrl: String = "https://speed.cloudflare.com/__down?bytes=3145728",
    val publicDnsUrl: String = "https://cloudflare-dns.com/dns-query?name=example.com&type=A"
) {
    val disclosedEndpoints get() = listOf(reachabilityUrl, qualityUrl, publicDnsUrl)
}

enum class ProbeId { DNS, INTERNET, GATEWAY, PANEL, CLOCK, IP_FAMILIES, REQUEST_SAMPLE, THROUGHPUT }
enum class ProbeStatus { RUNNING, PASS, FAIL, SKIPPED, CANCELLED }
data class ProbeProgress(val id: ProbeId, val status: ProbeStatus, val detail: String = "", val elapsedMs: Long? = null)
data class NetworkCheckReport(val probes: ProbeResults, val tests: List<ProbeProgress>, val gatewayMethod: String?, val throughputBytes: Long = 0)

/** A single user-started quick check. It never requests a playback URL or stream. */
class NetworkCheckRunner(
    private val config: NetworkProbeConfig = NetworkProbeConfig(),
    private val request: suspend (String, Int, Long) -> HttpResult = ::httpRequest,
    private val resolve: suspend (String) -> List<InetAddress> = { host -> withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() } },
    private val addressProbe: suspend (InetAddress, String) -> Boolean = ::tlsAddressProbe,
    private val gateway: suspend () -> Pair<Boolean?, String> = { null to "unavailable" },
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun run(snapshot: NetworkSnapshot, credentials: Credentials, playbackActive: Boolean,
                    confirmMeteredQuality: Boolean, selectedTrackBitrateMbps: Double? = null,
                    onProgress: (ProbeProgress) -> Unit = {}): NetworkCheckReport {
        val results = mutableListOf<ProbeProgress>()
        var internet = CheckState.UNKNOWN; var dns = CheckState.UNKNOWN; var neutral = CheckState.UNKNOWN
        var provider = CheckState.SKIPPED; var router = CheckState.UNKNOWN; var dateOffset: Long? = null
        var providerStatus: Int? = null; var dnsElapsed: Long? = null; var publicDnsElapsed: Long? = null
        var v4: Boolean? = null; var hasIpv6Address = false; var v6Failures = 0; var failures = 0; val latencies = mutableListOf<Long>()
        var throughput: Double? = null; var bytes = 0L
        suspend fun check(id: ProbeId, block: suspend () -> Pair<ProbeStatus, String>) {
            currentCoroutineContext().ensureActive()
            val start = clock(); onProgress(ProbeProgress(id, ProbeStatus.RUNNING));
            try {
                val (status, detail) = block()
                val item = ProbeProgress(id, status, detail, (clock() - start).coerceAtLeast(0))
                results += item; onProgress(item)
            } catch (e: CancellationException) { onProgress(ProbeProgress(id, ProbeStatus.CANCELLED)); throw e }
            catch (e: Exception) {
                val detail = when (e) {
                    is SocketTimeoutException -> "Timed out"
                    is SSLException -> "TLS failure: certificate or secure connection error"
                    is java.net.UnknownHostException -> "DNS lookup failed"
                    else -> e.javaClass.simpleName
                }
                val item = ProbeProgress(id, ProbeStatus.FAIL, detail, (clock()-start).coerceAtLeast(0)); results += item; onProgress(item)
            }
        }
        check(ProbeId.DNS) {
            val host = "example.com"; val t = clock(); val addresses = resolve(host); val elapsed = (clock()-t).coerceAtLeast(0); dnsElapsed = elapsed
            dns = if (addresses.isNotEmpty()) CheckState.PASS else CheckState.FAIL
            val public = request(config.publicDnsUrl, 3_000, 0)
            publicDnsElapsed = public.elapsedMs
            val ok = public.code in 200..299
            Pair(if (addresses.isNotEmpty() && ok) ProbeStatus.PASS else ProbeStatus.FAIL,
                "System lookup ${elapsed} ms; public DNS-over-HTTPS ${public.elapsedMs} ms (${if (ok) "reachable" else "failed"})")
        }
        check(ProbeId.INTERNET) {
            val r = request(config.reachabilityUrl, 4_000, 0)
            val redirected = r.redirected || r.code in 300..399
            internet = if (r.code == 204 && !redirected) CheckState.PASS else CheckState.FAIL
            neutral = internet
            Pair(if (internet == CheckState.PASS) ProbeStatus.PASS else ProbeStatus.FAIL,
                if (redirected) "Redirect detected; possible captive portal" else "HTTP ${r.code}")
        }
        check(ProbeId.GATEWAY) {
            val (reachable, method) = gateway(); router = when (reachable) { true -> CheckState.PASS; false -> CheckState.FAIL; null -> CheckState.UNKNOWN }
            Pair(when (router) { CheckState.PASS -> ProbeStatus.PASS; CheckState.FAIL -> ProbeStatus.FAIL; else -> ProbeStatus.SKIPPED }, "${method}: ${router.name.lowercase()}")
        }
        check(ProbeId.PANEL) {
            if (playbackActive) { provider = CheckState.SKIPPED; return@check ProbeStatus.SKIPPED to "Skipped while playback is active" }
            val url = credentials.playerApiUrl() + "?username=${encode(credentials.username)}&password=${encode(credentials.password)}"
            val r = request(url, 8_000, 0)
            providerStatus = r.code
            provider = if (r.code in 200..299) CheckState.PASS else CheckState.FAIL
            ProbeStatus.entries.firstOrNull { it.name == "PASS" && r.code in 200..299 }?.let { return@check it to "Panel returned HTTP ${r.code}" }
            ProbeStatus.FAIL to "Panel returned HTTP ${r.code}"
        }
        check(ProbeId.CLOCK) {
            if (playbackActive) return@check ProbeStatus.SKIPPED to "Skipped while playback is active"
            val r = request(config.reachabilityUrl, 4_000, 0); val server = r.dateMs
            dateOffset = server?.let { it - clock() }
            if (server == null) ProbeStatus.FAIL to "No Date header available"
            else ProbeStatus.PASS to "Server Date offset ${dateOffset} ms"
        }
        check(ProbeId.IP_FAMILIES) {
            if (playbackActive) return@check ProbeStatus.SKIPPED to "Skipped while playback is active"
            val addresses = resolve("cloudflare.com")
            val v4Address = addresses.firstOrNull { it.address.size == 4 }
            val v6Address = addresses.firstOrNull { it.address.size == 16 }
            hasIpv6Address = v6Address != null
            suspend fun attempt(address: InetAddress?): Boolean? {
                if (address == null) return null
                return try { addressProbe(address, "cloudflare.com") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            v4 = attempt(v4Address)
            val v6Attempts = if (v6Address == null) emptyList() else listOf(attempt(v6Address), attempt(v6Address))
            v6Failures = v6Attempts.count { it == false }
            val has6 = v6Address != null
            val success = v4 == true || v6Attempts.any { it == true }
            Pair(if (success) ProbeStatus.PASS else ProbeStatus.FAIL,
                "IPv4 HTTPS ${v4?.let { if (it) "passed" else "failed" } ?: "no address"}; IPv6 HTTPS attempts ${if (!has6) "no address" else v6Attempts.joinToString { if (it == true) "passed" else "failed" }}")
        }
        check(ProbeId.REQUEST_SAMPLE) {
            if (playbackActive) return@check ProbeStatus.SKIPPED to "Skipped while playback is active"
            val samples = mutableListOf<Long>()
            repeat(4) { index ->
                currentCoroutineContext().ensureActive()
                if (index > 0) kotlinx.coroutines.delay(250)
                val t = clock()
                try {
                    val r = request(config.reachabilityUrl, 3_000, 0)
                    if (r.code !in 200..299) failures++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures++ }
                samples += (clock()-t).coerceAtLeast(0)
                latencies += samples.last()
            }
            ProbeStatus.PASS to "4 HTTP samples; $failures failed"
        }
        check(ProbeId.THROUGHPUT) {
            if (snapshot.metered == true && !confirmMeteredQuality) return@check ProbeStatus.SKIPPED to "Metered network; confirmation required"
            if (playbackActive) return@check ProbeStatus.SKIPPED to "Skipped while playback is active"
            val r = request(config.qualityUrl, 8_000, 3L * 1024 * 1024)
            bytes = r.bytesRead.coerceIn(0, 3L * 1024 * 1024)
            throughput = r.elapsedMs.takeIf { it > 0 }?.let { bytes * 8.0 / it / 1000.0 }
            Pair(if (bytes > 0) ProbeStatus.PASS else ProbeStatus.FAIL, "Downloaded ${bytes} bytes in ${r.elapsedMs} ms")
        }
        val sorted = latencies.sorted(); val median = sorted.getOrNull(sorted.size / 2)
        val spread = if (sorted.size > 1) sorted.last() - sorted.first() else null
        return NetworkCheckReport(ProbeResults(internet, dns, neutral, provider, router, if (dateOffset == null) CheckState.UNKNOWN else CheckState.PASS,
            captiveRedirect = results.firstOrNull { it.id == ProbeId.INTERNET }?.detail?.contains("Redirect") == true,
            systemDnsMedianMs = dnsElapsed, publicDnsMs = publicDnsElapsed,
            publicDnsSucceeded = results.firstOrNull { it.id == ProbeId.DNS }?.status == ProbeStatus.PASS,
            ipv4HttpsSucceeded = v4, ipv6FailureCount = v6Failures.takeIf { hasIpv6Address },
            clockDateOffsetMs = dateOffset, failedSamples = failures, medianLatencyMs = median, latencySpreadMs = spread,
            providerStatus = providerStatus, throughputMbps = throughput,
            selectedTrackBitrateMbps = selectedTrackBitrateMbps), results,
            results.firstOrNull { it.id == ProbeId.GATEWAY }?.detail?.substringBefore(": "), bytes)
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
}

data class HttpResult(val code: Int, val redirected: Boolean = false, val dateMs: Long? = null, val bytesRead: Long = 0, val elapsedMs: Long = 0)

/** Best effort, process-wide signal set by the sole stream-owning MediaSessionService. */
object NetworkPlaybackState {
    @Volatile var active: Boolean = false
    @Volatile var selectedTrackBitrateMbps: Double? = null
}

fun gatewayProbe(context: Context): suspend () -> Pair<Boolean?, String> = {
    withContext(Dispatchers.IO) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val links: LinkProperties? = network?.let(cm::getLinkProperties)
        val gateway = links?.routes?.firstOrNull { it.isDefaultRoute }?.gateway
        if (gateway == null) null to "TCP gateway probe unavailable"
        else {
            val reachable = listOf(53, 80).any { port -> runCatching {
                Socket().use { socket -> socket.connect(java.net.InetSocketAddress(gateway, port), 800); true }
            }.getOrDefault(false) }
            reachable to "TCP gateway probe (ports 53/80)"
        }
    }
}

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private suspend fun httpRequest(url: String, timeoutMs: Int, maxBytes: Long): HttpResult = withContext(Dispatchers.IO) {
    val start = System.nanoTime(); val conn = URL(url).openConnection() as HttpURLConnection
    val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true) { cause ->
        if (cause is CancellationException) conn.disconnect()
    }
    try {
        conn.connectTimeout = minOf(timeoutMs, 3_000)
        conn.readTimeout = if (maxBytes > 0) minOf(250, timeoutMs) else timeoutMs
        conn.instanceFollowRedirects = false
        if (url.contains("dns-query")) conn.setRequestProperty("Accept", "application/dns-json")
        conn.setRequestProperty("Range", "bytes=0-${(maxBytes - 1).coerceAtLeast(0)}")
        val code = conn.responseCode
        var size = 0L
        if (maxBytes > 0 && code in 200..299) conn.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (size < maxBytes) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes-size).toInt())
                if (n < 0) break
                size += n
                if ((System.nanoTime() - start) / 1_000_000 >= timeoutMs - 250) break
            }
        }
        val elapsed = (System.nanoTime()-start)/1_000_000
        HttpResult(code, code in 300..399, conn.getHeaderFieldDate("Date", -1).takeIf { it > 0 }, size, elapsed)
    } finally { cancellation.dispose(); conn.disconnect() }
}

private suspend fun tlsAddressProbe(address: InetAddress, hostname: String): Boolean = withContext(Dispatchers.IO) {
    Socket().use { raw ->
        raw.connect(InetSocketAddress(address, 443), 1_500)
        raw.soTimeout = 1_500
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(raw, hostname, 443, true) as SSLSocket).use { tls ->
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            true
        }
    }
}
