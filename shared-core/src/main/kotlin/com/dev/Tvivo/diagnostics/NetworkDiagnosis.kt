package com.dev.Tvivo.diagnostics

/** Platform-free observations. Null values mean Android could not provide the detail. */
data class NetworkSnapshot(
    val capturedAtMs: Long,
    val route: Route = Route.UNKNOWN,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val wifiAssociated: Boolean? = null,
    val ipAssigned: Boolean? = null,
    val routerReachable: Boolean? = null,
    val rssiDbm: Int? = null,
    val wifiBand: WifiBand = WifiBand.UNKNOWN,
    val receiveLinkMbps: Int? = null,
    val proxyPresent: Boolean? = null,
    val privateDns: PrivateDns = PrivateDns.UNKNOWN,
    val vpnPresent: Boolean? = null,
    val staticIp: Boolean? = null,
    val metered: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    val dnsServerCount: Int? = null,
    val hasIpv4: Boolean? = null,
    val hasIpv6: Boolean? = null,
    val clockSkewMs: Long? = null,
    val automaticTimeEnabled: Boolean? = null
) {
    enum class Route { NONE, WIFI, ETHERNET, CELLULAR, VPN, OTHER, UNKNOWN }
    enum class WifiBand { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }
    enum class PrivateDns { OFF, AUTOMATIC, STRICT, UNKNOWN }
}

enum class CheckState { PASS, FAIL, SKIPPED, UNKNOWN }

/** Results are supplied by a later user-started check; this slice never performs probes. */
data class ProbeResults(
    val internet: CheckState = CheckState.UNKNOWN,
    val systemDns: CheckState = CheckState.UNKNOWN,
    val neutralHttps: CheckState = CheckState.UNKNOWN,
    val providerControl: CheckState = CheckState.UNKNOWN,
    val routerReachable: CheckState = CheckState.UNKNOWN,
    val clockHeader: CheckState = CheckState.UNKNOWN,
    val captiveRedirect: Boolean? = null,
    val systemDnsMedianMs: Long? = null,
    val publicDnsMs: Long? = null,
    val publicDnsSucceeded: Boolean? = null,
    val ipv4HttpsSucceeded: Boolean? = null,
    val ipv6FailureCount: Int? = null,
    val clockDateOffsetMs: Long? = null,
    val certificateValidityTimeError: Boolean? = null,
    val failedSamples: Int? = null,
    val medianLatencyMs: Long? = null,
    val latencySpreadMs: Long? = null,
    val providerStatus: Int? = null,
    val throughputMbps: Double? = null,
    val selectedTrackBitrateMbps: Double? = null,
    val streamThroughputMbps: Double? = null,
    val streamBitrateMbps: Double? = null,
    val streamStalls: Int? = null,
    val streamRetries: Int? = null
)

data class DropPattern(
    val dropsIn24Hours: Int = 0,
    val longestDropMs: Long? = null,
    val averageGapMs: Long? = null,
    val recoveredAfterReconnect: Int = 0,
    val networksAffected: Int? = null,
    val otherDevicesHealthy: Boolean? = null
)

data class RecentEventSummary(
    val http403Count: Int = 0,
    val http429Count: Int = 0,
    val serverErrorCount: Int = 0,
    val timeoutCount: Int = 0,
    val dnsFailureCount: Int = 0,
    val stallCount: Int = 0,
    val retryCount: Int = 0,
    val handoffCooldown403Count: Int = 0,
    val dropPattern: DropPattern = DropPattern()
)

enum class FindingSeverity { CRITICAL, WARNING, INFO }

data class Finding(
    val id: String,
    val severity: FindingSeverity,
    val title: String,
    val evidence: String,
    val nextSteps: List<String>,
    val limitation: String
)

/** Deterministic diagnosis only: no Android dependencies, clock reads, or network access. */
object NetworkDiagnosis {
    const val WEAK_RSSI_DBM = -70
    const val CLOCK_SKEW_MS = 5 * 60 * 1000L
    const val REPEATED_DROPS_24H = 3

    fun diagnose(
        snapshot: NetworkSnapshot,
        probes: ProbeResults = ProbeResults(),
        recent: RecentEventSummary = RecentEventSummary()
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        fun add(id: String, severity: FindingSeverity, title: String, evidence: String,
                steps: List<String>, limitation: String) {
            findings += Finding(id, severity, title, evidence, steps, limitation)
        }

        if (snapshot.route == NetworkSnapshot.Route.NONE) {
            add("no_route", FindingSeverity.CRITICAL, "No active network route is available",
                "Android reports no active network route.",
                listOf("check_network_settings", "reconnect"),
                "This describes the device route; it does not identify why it is unavailable.")
        }

        val webReachable = probes.internet == CheckState.PASS || probes.neutralHttps == CheckState.PASS ||
            probes.providerControl == CheckState.PASS
        if (snapshot.wifiAssociated == true && snapshot.ipAssigned == true && !webReachable &&
            (snapshot.routerReachable == false || probes.routerReachable == CheckState.FAIL)) {
            add("wifi_router_unreachable", FindingSeverity.CRITICAL,
                "The device is associated with Wi-Fi but cannot reach the router",
                "Wi-Fi association and an assigned IP are present, while router reachability failed.",
                listOf("reconnect", "router_if_others_fail", "try_ethernet"),
                "A router reachability check is required; Android's internet validation alone cannot prove this.")
        } else if (snapshot.captivePortal == true || probes.captiveRedirect == true) {
            add("captive_portal", FindingSeverity.WARNING,
                "This network may need a sign-in", "Android reports a captive-portal condition.",
                listOf("complete_wifi_sign_in"),
                "Portal status can be temporary and does not show whether the panel is reachable.")
        } else if (snapshot.routerReachable == true && snapshot.validated == false) {
            add("internet_unvalidated", FindingSeverity.WARNING,
                "The router is reachable, but internet access is not validated",
                "Router reachability succeeded; Android has not validated internet access.",
                listOf("check_router_internet", "retry_later"),
                "Android validation is not a test of the panel or a media stream.")
        }

        if (snapshot.route == NetworkSnapshot.Route.WIFI && snapshot.rssiDbm != null &&
            snapshot.rssiDbm < WEAK_RSSI_DBM) {
            add("weak_wifi_signal", FindingSeverity.WARNING, "Wi-Fi signal is weak",
                "Reported signal is ${snapshot.rssiDbm} dBm (below −70 dBm).",
                listOf("move_tv_closer", "reduce_obstructions", "try_ethernet"),
                "Signal strength does not measure internet speed or prove the cause of a failure.")
        }

        if (snapshot.route == NetworkSnapshot.Route.WIFI && snapshot.wifiBand == NetworkSnapshot.WifiBand.GHZ_2_4) {
            val unstable = recent.dnsFailureCount > 0 || recent.timeoutCount > 0 || recent.stallCount > 0 ||
                recent.dropPattern.dropsIn24Hours > 0 || probes.internet == CheckState.FAIL
            add("wifi_2_4ghz", if (unstable) FindingSeverity.WARNING else FindingSeverity.INFO,
                if (unstable) "2.4 GHz Wi-Fi is active while connection problems are recorded" else "Connected on 2.4 GHz Wi-Fi",
                "The active Wi-Fi band is 2.4 GHz.",
                listOf("compare_5ghz", "compare_ethernet"),
                "2.4 GHz by itself is not a fault and does not prove the cause of instability.")
        }

        val drops = recent.dropPattern
        if (drops.dropsIn24Hours >= REPEATED_DROPS_24H) {
            val hasComparison = snapshot.route == NetworkSnapshot.Route.WIFI &&
                drops.recoveredAfterReconnect >= REPEATED_DROPS_24H &&
                drops.networksAffected != null && drops.networksAffected >= 2 && drops.otherDevicesHealthy == true
            if (hasComparison) {
                add("wifi_radio_firmware_pattern", FindingSeverity.WARNING,
                    "Repeated drops look like a TV Wi-Fi radio or firmware issue",
                    "${drops.dropsIn24Hours} drops in 24 hours; recovery followed reconnects across ${drops.networksAffected} networks while other devices stayed healthy.",
                    listOf("try_ethernet", "try_streaming_device", "forget_rejoin_wifi", "update_tv_firmware", "compare_router_compatibility", "check_wifi_power_saving", "reduce_2_4ghz_load", "factory_reset_last", "contact_tv_maker"),
                    "This pattern points toward the TV but cannot prove a hardware or firmware fault.")
            } else {
                add("repeated_drops_unknown_cause", FindingSeverity.WARNING,
                    "The connection has dropped repeatedly",
                    "${drops.dropsIn24Hours} drops recorded in 24 hours${drops.longestDropMs?.let { "; longest ${it / 60000} minutes"} ?: ""}.",
                    listOf("check_other_devices", "try_ethernet", "reconnect"),
                    "There is not enough comparison evidence to identify whether the TV, router, or provider is responsible.")
            }
        }

        val providerFailures = recent.http403Count > 0 || recent.handoffCooldown403Count > 0 || recent.http429Count > 0 ||
            recent.serverErrorCount > 0 || recent.timeoutCount > 0 ||
            probes.providerControl == CheckState.FAIL || probes.providerStatus in 500..599
        if (recent.http403Count > 0 || recent.handoffCooldown403Count > 0 || probes.providerStatus == 403) {
            val handoff = recent.handoffCooldown403Count > 0
            add("provider_403", FindingSeverity.WARNING,
                "The server returned HTTP 403", "${recent.http403Count.coerceAtLeast(1)} HTTP 403 response(s) were recorded.",
                if (handoff) listOf("wait_60_seconds", "retry_once", "check_account_access")
                else listOf("check_account_access", "wait_briefly_retry"),
                "A 403 can mean an access rule, authentication policy, or a recently closed connection; it does not prove another device is using the account.")
        }
        if (recent.http429Count > 0 || probes.providerStatus == 429) {
            add("provider_429", FindingSeverity.WARNING, "The server asked the app to slow down",
                "${recent.http429Count.coerceAtLeast(1)} HTTP 429 response(s) were recorded.",
                listOf("wait_before_refresh", "retry_later"),
                "This indicates rate limiting at the responding server; it does not identify why the limit was reached.")
        }
        if (providerFailures && (probes.neutralHttps == CheckState.PASS || probes.internet == CheckState.PASS)) {
            add("provider_path_possible", FindingSeverity.WARNING,
                "The provider path or provider may be having trouble",
                "A provider failure was recorded while a neutral internet check succeeded.",
                listOf("retry_later", "compare_another_network", "share_redacted_report"),
                "This cannot distinguish provider load, routing or peering, VPN, account limits, or ISP throttling.")
        } else if (recent.serverErrorCount > 0 || probes.providerStatus in 500..599) {
            add("provider_server_errors", FindingSeverity.WARNING, "The server returned errors",
                if (probes.providerStatus in 500..599) "The stream check received HTTP ${probes.providerStatus}."
                else "${recent.serverErrorCount} HTTP 5xx response(s) were recorded.",
                listOf("retry_later"),
                "A server response does not establish whether the server, its upstream route, or another component caused the problem.")
        }

        if (recent.dnsFailureCount > 0 && snapshot.captivePortal != true && probes.captiveRedirect != true) add("dns_failures", FindingSeverity.WARNING,
            "Name lookup failures were recorded", "${recent.dnsFailureCount} DNS-related failure(s) were recorded.",
            listOf("reconnect", "private_dns_automatic"),
            "Without a controlled comparison, the resolver, VPN, network policy, or temporary outage may be involved.")
        if ((probes.systemDnsMedianMs?.let { it > 500L } == true) ||
            (probes.systemDnsMedianMs != null && probes.publicDnsMs != null &&
                probes.systemDnsMedianMs - probes.publicDnsMs > 300L) ||
            (probes.systemDns == CheckState.FAIL && probes.publicDnsSucceeded == true)) {
            add("slow_or_failing_dns", FindingSeverity.WARNING, "System DNS may be slow or failing",
                probes.systemDnsMedianMs?.let { "System DNS median was ${it} ms." }
                    ?: "System DNS failed while the public resolver comparison succeeded.",
                listOf("private_dns_automatic", "restart_router", "consider_router_dns"),
                "Resolver caches, VPNs, and different valid answers can affect comparisons; this is a clue, not proof.")
        }
        if (probes.ipv4HttpsSucceeded == true && (probes.ipv6FailureCount ?: 0) >= 2) {
            add("ipv6_path_may_fail", FindingSeverity.WARNING, "The IPv6 path may be failing",
                "IPv4 succeeded while IPv6 failed at least twice.",
                listOf("restart_router", "check_ipv6_router", "ask_isp_ipv6"),
                "This is based on bounded connection attempts to a dual-stack test host, not a guarantee about every destination.")
        }
        val clockSkew = probes.clockDateOffsetMs ?: snapshot.clockSkewMs
        if (clockSkew?.let { kotlin.math.abs(it) > CLOCK_SKEW_MS } == true ||
            probes.certificateValidityTimeError == true || snapshot.automaticTimeEnabled == false) {
            val evidence = when {
                probes.certificateValidityTimeError == true -> "A TLS certificate validity-time error was observed."
                clockSkew != null && kotlin.math.abs(clockSkew) > CLOCK_SKEW_MS -> "The measured clock difference is more than 5 minutes."
                else -> "Automatic date and time is disabled in Android settings."
            }
            add("clock_skew", FindingSeverity.WARNING, "The device clock may be incorrect",
                evidence,
                listOf("enable_automatic_time"),
                "A server Date header is approximate; clock skew can affect certificate validity checks.")
        }

        if (probes.medianLatencyMs?.let { it > 150L } == true ||
            probes.latencySpreadMs?.let { it > 100L } == true || (probes.failedSamples ?: 0) >= 2) {
            add("request_samples_unstable", FindingSeverity.WARNING, "Some internet request samples were slow or failed",
                "Median ${probes.medianLatencyMs ?: -1} ms; spread ${probes.latencySpreadMs ?: -1} ms; failed samples ${probes.failedSamples ?: 0}.",
                listOf("pause_downloads", "try_ethernet", "compare_another_network"),
                "These are HTTP request samples, not IP packet-loss measurements or a line-quality guarantee.")
        }
        val bitrate = probes.selectedTrackBitrateMbps
        val measured = probes.throughputMbps
        if (measured != null && bitrate != null && bitrate > 0.0 && measured < bitrate * 1.5) {
            val measuredText = String.format(java.util.Locale.ROOT, "%.2f", measured)
            val bitrateText = String.format(java.util.Locale.ROOT, "%.2f", bitrate)
            add("throughput_below_track", FindingSeverity.WARNING, "Measured download speed is below the selected track estimate",
                "$measuredText Mbps measured versus 1.5× $bitrateText Mbps selected-track bitrate.",
                listOf("stop_downloads", "try_ethernet", "choose_lower_bitrate"),
                "A neutral download does not guarantee provider throughput; the bitrate is only as reliable as Media3's selected-track estimate.")
        }
        val streamRate = probes.streamThroughputMbps
        val streamBitrate = probes.streamBitrateMbps
        if (streamRate != null && streamBitrate != null && streamBitrate > 0.0 && streamRate < streamBitrate * 1.5) {
            add("stream_throughput_below_bitrate", FindingSeverity.WARNING,
                "This stream received less data than its bitrate needs",
                "The measured stream rate was %.2f Mbps; the selected track was %.2f Mbps."
                    .format(java.util.Locale.ROOT, streamRate, streamBitrate),
                listOf("pause_downloads", "try_ethernet", "compare_another_network"),
                "A short sample can vary, and a stall can also come from the source or device.")
        }
        if ((probes.streamStalls ?: 0) > 0) {
            add("stream_stalls", FindingSeverity.WARNING, "The test stream paused while loading",
                "${probes.streamStalls} playback stall(s) were measured during the brief stream check.",
                listOf("pause_downloads", "try_ethernet", "retry_once"),
                "One short test cannot identify whether the network, source, or device caused the stall.")
        }

        if (providerFailures) {
            val modifiers = buildList {
                if (snapshot.vpnPresent == true) add("VPN is present")
                if (snapshot.proxyPresent == true) add("a proxy is present")
                if (snapshot.privateDns == NetworkSnapshot.PrivateDns.STRICT) add("strict Private DNS is configured")
                if (snapshot.staticIp == true) add("static IP configuration is reported")
            }
            if (modifiers.isNotEmpty()) add("network_configuration_present", FindingSeverity.INFO,
                "Additional network settings are active", modifiers.joinToString(", ") + ".",
                listOf("compare_network_setting"),
                "These settings may affect traffic but their presence alone does not show that they caused the failure.")
        }
        if (snapshot.metered == true && snapshot.backgroundRestricted == true) {
            add("metered_restricted", FindingSeverity.INFO, "Background network use is restricted",
                "The active network is metered and background data is restricted.",
                listOf("allow_background_data"),
                "This does not affect whether foreground internet access works.")
        }

        val checksPassed = probes.internet == CheckState.PASS && probes.systemDns == CheckState.PASS &&
            probes.neutralHttps == CheckState.PASS && probes.providerControl == CheckState.PASS
        val relevantSkip = listOf(probes.internet, probes.systemDns, probes.neutralHttps, probes.providerControl)
            .any { it == CheckState.SKIPPED || it == CheckState.UNKNOWN }
        val importantEvents = recent.http403Count + recent.http429Count + recent.serverErrorCount +
            recent.timeoutCount + recent.dnsFailureCount + recent.stallCount + drops.dropsIn24Hours
        if (findings.isEmpty() && checksPassed && !relevantSkip && importantEvents == 0) {
            add("checks_look_ok", FindingSeverity.INFO, "Your connection looks OK in these checks",
                "Required internet, DNS, neutral HTTPS, and provider control checks passed.", emptyList(),
                "A control check cannot guarantee that every media stream will work.")
        } else if (relevantSkip && findings.isEmpty()) {
            add("checks_incomplete", FindingSeverity.INFO, "Some causes have not been checked",
                "One or more checks were skipped or unavailable.", emptyList(),
                "No conclusion about those skipped checks is possible.")
        }
        return findings.sortedWith(compareBy<Finding> { it.severity.ordinal }
            .thenByDescending { priority(it.id) }.thenBy { it.id })
    }

    private fun priority(id: String): Int = when (id) {
        "no_route" -> 100
        "wifi_router_unreachable" -> 95
        "captive_portal" -> 90
        "internet_unvalidated" -> 85
        "wifi_radio_firmware_pattern" -> 80
        "repeated_drops_unknown_cause" -> 75
        "clock_skew" -> 70
        // A provider response observed in the current check or recent requests is more
        // actionable than an old drop pattern retained in the 24-hour timeline.
        "provider_403", "provider_429" -> 82
        "provider_path_possible", "provider_server_errors" -> 78
        "slow_or_failing_dns", "dns_failures", "ipv6_path_may_fail" -> 55
        "request_samples_unstable", "throughput_below_track" -> 50
        "weak_wifi_signal" -> 45
        "wifi_2_4ghz" -> 30
        else -> 10
    }
}
