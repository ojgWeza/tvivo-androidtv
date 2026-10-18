package com.dev.Tvivo.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

enum class NetworkEventSource { CONNECTIVITY, PLAYBACK, CATALOG, CHECK }
enum class NetworkEventKind {
    PROCESS_GAP, AVAILABLE, LOST, NETWORK_CHANGED, VALIDATION_CHANGED, LINK_CHANGED,
    HTTP_403, HTTP_429, HTTP_5XX, DNS_FAILURE, TIMEOUT, TLS_FAILURE, NETWORK_FAILURE, STALL, RETRY, HANDOFF_COOLDOWN_403
}

/** Strict allowlist. This model has no fields for URLs, IPs, SSIDs, titles, or exception text. */
data class NetworkEvent(
    val timestampMs: Long,
    val source: NetworkEventSource,
    val kind: NetworkEventKind,
    val accountPartition: String? = null,
    val statusCode: Int? = null,
    val phase: String? = null,
    val durationMs: Long? = null,
    val route: String? = null,
    val validated: Boolean? = null,
    val rssiBand: String? = null,
    val ipAssigned: Boolean? = null,
    val proxyPresent: Boolean? = null,
    val privateDnsMode: String? = null,
    val vpnPresent: Boolean? = null,
    val metered: Boolean? = null,
    val dnsServerCount: Int? = null,
    val hasIpv4: Boolean? = null,
    val hasIpv6: Boolean? = null
)

object NetworkEventStore {
    private const val FILE_NAME = "network-events.json"
    private const val CAPACITY = 100
    private val maxAgeMs = TimeUnit.DAYS.toMillis(7)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tvivo-network-events").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var file: File? = null
    private val mutableEvents = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun initialize(context: Context, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, FILE_NAME)
            mutableEvents.value = read(file!!, nowMs)
            enqueueWrite(file!!, mutableEvents.value)
            val previousStart = file!!.parentFile?.let { File(it, "network-events-session") }
                ?.takeIf(File::isFile)?.let { runCatching { it.readText().toLongOrNull() }.getOrNull() }
            if (previousStart != null && nowMs > previousStart) {
                append(NetworkEvent(nowMs, NetworkEventSource.CONNECTIVITY, NetworkEventKind.PROCESS_GAP,
                    phase = "gap while app was not running is unknown"))
            }
            file!!.parentFile?.let { File(it, "network-events-session") }?.let { marker ->
                runCatching { marker.writeText(nowMs.toString()) }
            }
        }
    }

    fun append(event: NetworkEvent) {
        val safe = sanitize(event)
        synchronized(lock) {
            val next =
            (listOf(safe) + mutableEvents.value)
                .filter { event.timestampMs - it.timestampMs in 0..maxAgeMs }
                .take(CAPACITY)
                .also { mutableEvents.value = it }
            val target = file ?: return
            enqueueWrite(target, next)
        }
    }

    private fun enqueueWrite(target: File, snapshot: List<NetworkEvent>) {
        writer.execute {
            runCatching {
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeText(encode(snapshot), Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    target.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    temp.delete()
                }
            }.onFailure { Log.w("AppDiagnostics", "Could not persist network events", it) }
        }
    }

    fun forAccount(accountPartition: String): List<NetworkEvent> = events.value.filter {
        it.source == NetworkEventSource.CONNECTIVITY || it.accountPartition == accountPartition
    }

    fun encode(events: List<NetworkEvent>): String = JSONArray().apply {
        events.take(CAPACITY).forEach { event ->
            val safe = sanitize(event)
            put(JSONObject().apply {
                put("at", safe.timestampMs)
                put("source", safe.source.name)
                put("kind", safe.kind.name)
                safe.accountPartition?.let { put("account", it.take(64)) }
                safe.statusCode?.let { put("status", it) }
                safe.phase?.let { put("phase", it) }
                safe.durationMs?.let { put("duration", it.coerceIn(0, TimeUnit.DAYS.toMillis(7))) }
                safe.route?.let { put("route", it) }
                safe.validated?.let { put("validated", it) }
                safe.rssiBand?.let { put("rssiBand", it) }
                safe.ipAssigned?.let { put("ipAssigned", it) }
                safe.proxyPresent?.let { put("proxyPresent", it) }
                safe.privateDnsMode?.let { put("privateDnsMode", it) }
                safe.vpnPresent?.let { put("vpnPresent", it) }
                safe.metered?.let { put("metered", it) }
                safe.dnsServerCount?.let { put("dnsServerCount", it.coerceIn(0, 16)) }
                safe.hasIpv4?.let { put("hasIpv4", it) }
                safe.hasIpv6?.let { put("hasIpv6", it) }
            })
        }
    }.toString()

    fun read(file: File, nowMs: Long = System.currentTimeMillis()): List<NetworkEvent> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val data = JSONArray(file.readText(Charsets.UTF_8))
        (0 until data.length()).mapNotNull { index ->
            runCatching {
                val row = data.getJSONObject(index)
                NetworkEvent(
                    timestampMs = row.getLong("at"),
                    source = NetworkEventSource.valueOf(row.getString("source")),
                    kind = NetworkEventKind.valueOf(row.getString("kind")),
                    accountPartition = row.optString("account").takeUnless { it.isBlank() || it == "null" },
                    statusCode = row.optInt("status").takeIf { row.has("status") },
                    phase = row.optString("phase").takeUnless { it.isBlank() || it == "null" },
                    durationMs = row.optLong("duration").takeIf { row.has("duration") },
                    route = row.optString("route").takeUnless { it.isBlank() || it == "null" },
                    validated = row.optBoolean("validated").takeIf { row.has("validated") },
                    rssiBand = row.optString("rssiBand").takeUnless { it.isBlank() || it == "null" },
                    ipAssigned = row.optBoolean("ipAssigned").takeIf { row.has("ipAssigned") },
                    proxyPresent = row.optBoolean("proxyPresent").takeIf { row.has("proxyPresent") },
                    privateDnsMode = row.optString("privateDnsMode").takeUnless { it.isBlank() || it == "null" },
                    vpnPresent = row.optBoolean("vpnPresent").takeIf { row.has("vpnPresent") },
                    metered = row.optBoolean("metered").takeIf { row.has("metered") },
                    dnsServerCount = row.optInt("dnsServerCount").takeIf { row.has("dnsServerCount") },
                    hasIpv4 = row.optBoolean("hasIpv4").takeIf { row.has("hasIpv4") },
                    hasIpv6 = row.optBoolean("hasIpv6").takeIf { row.has("hasIpv6") }
                ).let(::sanitize)
            }.getOrNull()
        }.filter { nowMs - it.timestampMs in 0..maxAgeMs }
            .sortedByDescending(NetworkEvent::timestampMs).take(CAPACITY)
    }.getOrDefault(emptyList())

    private fun sanitize(event: NetworkEvent) = event.copy(
        accountPartition = event.accountPartition?.takeIf { it.length == 32 && it.all { char -> char in '0'..'9' || char.lowercaseChar() in 'a'..'f' } }
            ?.lowercase(),
        statusCode = event.statusCode?.takeIf { it in 100..599 },
        phase = event.phase?.takeIf { it in ALLOWED_PHASES },
        durationMs = event.durationMs?.coerceIn(0, TimeUnit.DAYS.toMillis(7)),
        route = event.route?.takeIf { it in setOf("wifi", "ethernet", "cellular", "vpn", "other", "unknown", "none") },
        rssiBand = event.rssiBand?.takeIf { it in setOf("strong", "medium", "weak", "unknown") },
        privateDnsMode = event.privateDnsMode?.takeIf { it in setOf("off", "automatic", "strict", "unknown") },
        dnsServerCount = event.dnsServerCount?.coerceIn(0, 16)
    )

    private val ALLOWED_PHASES = setOf(
        "observations unavailable between app sessions", "gap while app was not running is unknown",
        "default network lost", "callback unavailable", "initial", "available", "capabilities", "link properties",
        "catalog sync", "vod", "live", "series", "vod details", "vod categories", "vod streams",
        "live categories", "live streams", "series categories", "series list", "series info", "series_info",
        "stream source", "source error", "stall recovery", "stream open", "stream open timeout", "recent stream close"
    )
}

object NetworkEventRecorder {
    fun http(source: NetworkEventSource, accountPartition: String?, code: Int, phase: String? = null) {
        val kind = when (code) {
            403 -> NetworkEventKind.HTTP_403
            429 -> NetworkEventKind.HTTP_429
            408 -> NetworkEventKind.TIMEOUT
            in 500..599 -> NetworkEventKind.HTTP_5XX
            else -> return
        }
        NetworkEventStore.append(NetworkEvent(System.currentTimeMillis(), source, kind,
            accountPartition = accountPartition, statusCode = code, phase = phase))
    }

    fun failure(source: NetworkEventSource, accountPartition: String?, failure: Throwable, phase: String? = null) {
        val causes = generateSequence(failure) { it.cause }.take(12).toList()
        val kind = when {
            causes.any { it is UnknownHostException } -> NetworkEventKind.DNS_FAILURE
            causes.any { it is SocketTimeoutException } -> NetworkEventKind.TIMEOUT
            causes.any { it is SSLException } -> NetworkEventKind.TLS_FAILURE
            causes.any { it is java.net.ConnectException } -> NetworkEventKind.NETWORK_FAILURE
            causes.any { it is java.io.IOException } -> NetworkEventKind.NETWORK_FAILURE
            else -> return
        }
        NetworkEventStore.append(NetworkEvent(System.currentTimeMillis(), source, kind,
            accountPartition = accountPartition, phase = phase ?: failure.javaClass.simpleName))
    }

    fun occurrence(source: NetworkEventSource, accountPartition: String?, kind: NetworkEventKind, phase: String? = null) {
        NetworkEventStore.append(NetworkEvent(System.currentTimeMillis(), source, kind,
            accountPartition = accountPartition, phase = phase))
    }
}
