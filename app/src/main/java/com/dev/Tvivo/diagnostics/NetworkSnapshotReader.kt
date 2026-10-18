package com.dev.Tvivo.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import com.dev.Tvivo.diagnostics.NetworkSnapshot.PrivateDns
import com.dev.Tvivo.diagnostics.NetworkSnapshot.Route
import com.dev.Tvivo.diagnostics.NetworkSnapshot.WifiBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Best-effort passive reader. It never requests location or retains network identifiers. */
class NetworkSnapshotReader(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableSnapshot = MutableStateFlow(readNow())
    val snapshot = mutableSnapshot.asStateFlow()
    private var lostAtMs: Long? = null
    private var lastChangeAtMs = 0L
    private var lastFingerprint: String? = null

    fun start() {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { NetworkEventStore.append(NetworkEvent(System.currentTimeMillis(), NetworkEventSource.CONNECTIVITY,
                NetworkEventKind.LINK_CHANGED, phase = "callback unavailable")) }
        connectivity.activeNetwork?.let { network ->
            update(network, connectivity.getNetworkCapabilities(network), connectivity.getLinkProperties(network), "initial")
        }
    }

    fun refresh(): com.dev.Tvivo.diagnostics.NetworkSnapshot {
        val value = readNow()
        mutableSnapshot.value = value
        return value
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            val downDuration = lostAtMs?.let { (now - it).coerceAtLeast(0L) }
            lostAtMs = null
            update(network, connectivity.getNetworkCapabilities(network), connectivity.getLinkProperties(network), "available", downDuration)
        }

        override fun onLost(network: Network) {
            val now = System.currentTimeMillis()
            lostAtMs = now
            val previous = mutableSnapshot.value
            mutableSnapshot.value = previous.copy(capturedAtMs = now, route = Route.NONE)
            appendDebounced(NetworkEvent(now, NetworkEventSource.CONNECTIVITY, NetworkEventKind.LOST,
                route = routeName(previous.route), validated = previous.validated, rssiBand = rssiBand(previous.rssiDbm),
                ipAssigned = previous.ipAssigned, proxyPresent = previous.proxyPresent,
                privateDnsMode = previous.privateDns.name.lowercase(), vpnPresent = previous.vpnPresent,
                metered = previous.metered, dnsServerCount = previous.dnsServerCount,
                hasIpv4 = previous.hasIpv4, hasIpv6 = previous.hasIpv6, phase = "default network lost"))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            update(network, caps, connectivity.getLinkProperties(network), "capabilities")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            update(network, connectivity.getNetworkCapabilities(network), properties, "link properties")
        }
    }

    private fun update(network: Network, caps: NetworkCapabilities?, links: LinkProperties?, phase: String,
                       downDurationMs: Long? = null) {
        val now = System.currentTimeMillis()
        val value = read(network, caps, links, now)
        val previous = mutableSnapshot.value
        mutableSnapshot.value = value
        val kind = when {
            phase == "available" || phase == "initial" -> NetworkEventKind.AVAILABLE
            previous.validated != value.validated -> NetworkEventKind.VALIDATION_CHANGED
            previous != value -> NetworkEventKind.NETWORK_CHANGED
            else -> NetworkEventKind.LINK_CHANGED
        }
        val fingerprint = "${value.route}:${value.validated}:${value.rssiDbm?.let(::rssiBand)}:${value.ipAssigned}:${value.wifiBand}:" +
            "${value.proxyPresent}:${value.privateDns}:${value.vpnPresent}:${value.metered}:${value.dnsServerCount}:${value.hasIpv4}:${value.hasIpv6}"
        if (fingerprint != lastFingerprint || phase == "available") {
            val actualKind = if (previous != value && previous.route == value.route && previous.validated == value.validated)
                NetworkEventKind.LINK_CHANGED else kind
            appendDebounced(NetworkEvent(now, NetworkEventSource.CONNECTIVITY, actualKind,
                durationMs = downDurationMs, route = routeName(value.route), validated = value.validated,
                rssiBand = rssiBand(value.rssiDbm), ipAssigned = value.ipAssigned,
                proxyPresent = value.proxyPresent, privateDnsMode = value.privateDns.name.lowercase(),
                vpnPresent = value.vpnPresent, metered = value.metered, dnsServerCount = value.dnsServerCount,
                hasIpv4 = value.hasIpv4, hasIpv6 = value.hasIpv6,
                phase = phase.takeIf { it == "available" || it == "initial" }))
            lastFingerprint = fingerprint
        }
    }

    private fun appendDebounced(event: NetworkEvent) {
        val now = event.timestampMs
        if (event.kind != NetworkEventKind.LOST && now - lastChangeAtMs < 2_000L) return
        lastChangeAtMs = now
        NetworkEventStore.append(event)
    }

    private fun readNow(): com.dev.Tvivo.diagnostics.NetworkSnapshot {
        val network = connectivity.activeNetwork ?: return com.dev.Tvivo.diagnostics.NetworkSnapshot(
            System.currentTimeMillis(), route = Route.NONE, metered = null,
            backgroundRestricted = restricted(), wifiAssociated = false, ipAssigned = false
        )
        return read(network, connectivity.getNetworkCapabilities(network), connectivity.getLinkProperties(network), System.currentTimeMillis())
    }

    private fun read(network: Network, caps: NetworkCapabilities?, links: LinkProperties?, now: Long) =
        com.dev.Tvivo.diagnostics.NetworkSnapshot(
            capturedAtMs = now,
            route = route(caps),
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            wifiAssociated = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ipAssigned = links?.linkAddresses?.isNotEmpty(),
            routerReachable = null,
            rssiDbm = wifiInfo(caps)?.rssi?.takeIf { it in -100..-1 },
            wifiBand = band(wifiInfo(caps)?.frequency),
            receiveLinkMbps = if (Build.VERSION.SDK_INT >= 29) wifiInfo(caps)?.rxLinkSpeedMbps?.takeIf { it > 0 } else null,
            proxyPresent = links?.httpProxy != null,
            privateDns = privateDns(links),
            vpnPresent = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            staticIp = null,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not(),
            backgroundRestricted = restricted(),
            dnsServerCount = links?.dnsServers?.size,
            hasIpv4 = links?.linkAddresses?.any { it.address.address.size == 4 },
            hasIpv6 = links?.linkAddresses?.any { it.address.address.size == 16 },
            clockSkewMs = null,
            automaticTimeEnabled = automaticTimeEnabled()
        )

    private fun automaticTimeEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
    }.getOrNull()

    private fun restricted(): Boolean? = runCatching {
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrNull()

    private fun wifiInfo(caps: NetworkCapabilities?): WifiInfo? = if (Build.VERSION.SDK_INT >= 29) {
        runCatching { caps?.transportInfo as? WifiInfo }.getOrNull()
    } else null

    private fun route(caps: NetworkCapabilities?): Route {
        if (caps == null) return Route.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Route.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Route.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Route.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Route.CELLULAR
            else -> Route.OTHER
        }
    }

    private fun band(frequency: Int?): WifiBand = when {
        frequency == null -> WifiBand.UNKNOWN
        frequency in 2400..2500 -> WifiBand.GHZ_2_4
        frequency in 4900..5900 -> WifiBand.GHZ_5
        frequency in 5925..7125 -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    private fun privateDns(links: LinkProperties?): PrivateDns = when {
        Build.VERSION.SDK_INT < 28 -> PrivateDns.UNKNOWN
        links == null -> PrivateDns.UNKNOWN
        !links.isPrivateDnsActive -> PrivateDns.OFF
        !links.privateDnsServerName.isNullOrBlank() -> PrivateDns.STRICT
        else -> PrivateDns.AUTOMATIC
    }

    private fun routeName(route: Route) = when (route) {
        Route.NONE -> "none"; Route.WIFI -> "wifi"; Route.ETHERNET -> "ethernet"
        Route.CELLULAR -> "cellular"; Route.VPN -> "vpn"; Route.OTHER -> "other"; Route.UNKNOWN -> "unknown"
    }

    private fun rssiBand(rssi: Int?) = when {
        rssi == null -> "unknown"; rssi >= -60 -> "strong"; rssi >= -70 -> "medium"; else -> "weak"
    }
}

/** Process-scoped callback; no location permission, polling, or active network traffic. */
object NetworkDiagnosticsRuntime {
    @Volatile private var reader: NetworkSnapshotReader? = null
    @Synchronized fun start(context: Context): NetworkSnapshotReader {
        NetworkEventStore.initialize(context.applicationContext)
        return reader ?: NetworkSnapshotReader(context.applicationContext).also { it.start(); reader = it }
    }
}
