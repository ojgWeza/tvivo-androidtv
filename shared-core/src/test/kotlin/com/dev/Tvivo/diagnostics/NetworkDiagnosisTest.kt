package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosisTest {
    private val wifi = NetworkSnapshot(1, route = NetworkSnapshot.Route.WIFI)

    @Test fun weakSignalUsesStrictlyBelowMinus70Boundary() {
        assertFalse(ids(wifi.copy(rssiDbm = -70)).contains("weak_wifi_signal"))
        assertTrue(ids(wifi.copy(rssiDbm = -71)).contains("weak_wifi_signal"))
    }

    @Test fun fiveMinuteClockBoundaryIsStrict() {
        assertFalse(ids(wifi.copy(clockSkewMs = 300_000)).contains("clock_skew"))
        assertTrue(ids(wifi.copy(clockSkewMs = -300_001)).contains("clock_skew"))
    }

    @Test fun systemDnsComparisonUsesPublicResolverTiming() {
        assertFalse(ids(wifi, ProbeResults(systemDnsMedianMs = 500, publicDnsMs = 200)).contains("slow_or_failing_dns"))
        assertTrue(ids(wifi, ProbeResults(systemDnsMedianMs = 501, publicDnsMs = 200)).contains("slow_or_failing_dns"))
    }

    @Test fun routeAndRouterFailureAreDistinctFromUnvalidatedInternet() {
        assertTrue(ids(wifi.copy(route = NetworkSnapshot.Route.NONE)).contains("no_route"))
        val routerFailure = NetworkDiagnosis.diagnose(
            wifi.copy(wifiAssociated = true, ipAssigned = true, routerReachable = false)
        )
        assertTrue(routerFailure.any { it.id == "wifi_router_unreachable" })
        assertFalse(routerFailure.any { it.id == "internet_unvalidated" })
        assertFalse(ids(wifi.copy(wifiAssociated = true, ipAssigned = true, routerReachable = false),
            ProbeResults(internet = CheckState.PASS, providerControl = CheckState.PASS))
            .contains("wifi_router_unreachable"))

        val internetFailure = NetworkDiagnosis.diagnose(
            wifi.copy(routerReachable = true, validated = false)
        )
        assertTrue(internetFailure.any { it.id == "internet_unvalidated" })
        assertFalse(internetFailure.any { it.id == "wifi_router_unreachable" })
    }

    @Test fun validationAloneDoesNotClaimRouterOrInternetFailure() {
        val results = NetworkDiagnosis.diagnose(wifi.copy(validated = false))
        assertFalse(results.any { it.id == "no_route" || it.id == "internet_unvalidated" || it.id == "wifi_router_unreachable" })
    }

    @Test fun twoPointFourGhzIsInformationalUnlessOtherInstabilityExists() {
        val calm = NetworkDiagnosis.diagnose(wifi.copy(wifiBand = NetworkSnapshot.WifiBand.GHZ_2_4))
            .single { it.id == "wifi_2_4ghz" }
        assertEquals(FindingSeverity.INFO, calm.severity)
        val unstable = NetworkDiagnosis.diagnose(
            wifi.copy(wifiBand = NetworkSnapshot.WifiBand.GHZ_2_4),
            recent = RecentEventSummary(timeoutCount = 1)
        ).single { it.id == "wifi_2_4ghz" }
        assertEquals(FindingSeverity.WARNING, unstable.severity)
    }

    @Test fun repeatedDropsNeedComparisonEvidenceForRadioInference() {
        val weak = RecentEventSummary(dropPattern = DropPattern(dropsIn24Hours = 3, recoveredAfterReconnect = 3))
        assertTrue(NetworkDiagnosis.diagnose(wifi, recent = weak).any { it.id == "repeated_drops_unknown_cause" })
        val strong = weak.copy(dropPattern = weak.dropPattern.copy(
            networksAffected = 2, otherDevicesHealthy = true
        ))
        val finding = NetworkDiagnosis.diagnose(wifi, recent = strong).single { it.id == "wifi_radio_firmware_pattern" }
        assertTrue(finding.title.contains("look like"))
        assertTrue(finding.limitation.contains("cannot prove"))
        assertEquals("try_ethernet", finding.nextSteps.first())
    }

    @Test fun http403And429RemainAmbiguousAndOfferDifferentAdvice() {
        val denied = NetworkDiagnosis.diagnose(wifi, recent = RecentEventSummary(http403Count = 1))
            .single { it.id == "provider_403" }
        assertTrue(denied.limitation.contains("does not prove"))
        val limited = NetworkDiagnosis.diagnose(wifi, recent = RecentEventSummary(http429Count = 2))
            .single { it.id == "provider_429" }
        assertTrue(limited.title.contains("slow down"))
        assertEquals("wait_before_refresh", limited.nextSteps.first())
        assertTrue(NetworkDiagnosis.diagnose(wifi, ProbeResults(providerStatus = 403)).any { it.id == "provider_403" })
        assertTrue(NetworkDiagnosis.diagnose(wifi, ProbeResults(providerStatus = 429)).any { it.id == "provider_429" })
    }

    @Test fun serverErrorsOnlySuggestProviderPathWhenNeutralInternetPassed() {
        val events = RecentEventSummary(serverErrorCount = 2)
        assertTrue(ids(wifi, recent = events).contains("provider_server_errors"))
        val compared = NetworkDiagnosis.diagnose(wifi,
            probes = ProbeResults(neutralHttps = CheckState.PASS), recent = events)
            .single { it.id == "provider_path_possible" }
        assertTrue(compared.limitation.contains("cannot distinguish"))
    }

    @Test fun configurationPresenceDoesNotClaimCause() {
        val result = NetworkDiagnosis.diagnose(
            wifi.copy(vpnPresent = true, proxyPresent = true, privateDns = NetworkSnapshot.PrivateDns.STRICT,
                staticIp = true),
            probes = ProbeResults(providerControl = CheckState.FAIL)
        ).single { it.id == "network_configuration_present" }
        assertTrue(result.limitation.contains("alone does not show"))
        assertTrue(result.evidence.contains("VPN"))
        assertTrue(result.evidence.contains("static IP"))
    }

    @Test fun skippedChecksNeverProduceFalseAllClear() {
        val result = NetworkDiagnosis.diagnose(wifi, probes = ProbeResults(internet = CheckState.SKIPPED))
        assertFalse(result.any { it.id == "checks_look_ok" })
        assertTrue(result.any { it.id == "checks_incomplete" })
    }

    @Test fun positiveSummaryRequiresEveryMandatoryCheckAndNoProblems() {
        val allPass = ProbeResults(internet = CheckState.PASS, systemDns = CheckState.PASS,
            neutralHttps = CheckState.PASS, providerControl = CheckState.PASS)
        assertTrue(ids(wifi, allPass).contains("checks_look_ok"))
        assertFalse(ids(wifi, allPass.copy(providerControl = CheckState.UNKNOWN)).contains("checks_look_ok"))
        assertFalse(ids(wifi, allPass, RecentEventSummary(stallCount = 1)).contains("checks_look_ok"))
    }

    @Test fun strongestFindingsComeFirst() {
        val findings = NetworkDiagnosis.diagnose(wifi.copy(route = NetworkSnapshot.Route.NONE, rssiDbm = -80))
        assertEquals(FindingSeverity.CRITICAL, findings.first().severity)
    }

    @Test fun dnsThresholdAndPublicResolverComparisonAreQualified() {
        assertFalse(ids(wifi, ProbeResults(systemDnsMedianMs = 500)).contains("slow_or_failing_dns"))
        assertTrue(ids(wifi, ProbeResults(systemDnsMedianMs = 501)).contains("slow_or_failing_dns"))
        assertTrue(ids(wifi, ProbeResults(systemDns = CheckState.FAIL, publicDnsSucceeded = true))
            .contains("slow_or_failing_dns"))
        assertFalse(ids(wifi, ProbeResults(systemDns = CheckState.PASS, publicDnsSucceeded = true))
            .contains("slow_or_failing_dns"))
    }

    @Test fun ipv6NeedsRepeatedFailureWhileIpv4Works() {
        assertFalse(ids(wifi, ProbeResults(ipv4HttpsSucceeded = true, ipv6FailureCount = 1)).contains("ipv6_path_may_fail"))
        assertTrue(ids(wifi, ProbeResults(ipv4HttpsSucceeded = true, ipv6FailureCount = 2)).contains("ipv6_path_may_fail"))
        assertFalse(ids(wifi, ProbeResults(ipv4HttpsSucceeded = false, ipv6FailureCount = 2)).contains("ipv6_path_may_fail"))
    }

    @Test fun latencyAndFailedSampleThresholdsAreNotPacketLossClaims() {
        assertFalse(ids(wifi, ProbeResults(medianLatencyMs = 150, latencySpreadMs = 100, failedSamples = 1))
            .contains("request_samples_unstable"))
        val finding = NetworkDiagnosis.diagnose(wifi, ProbeResults(failedSamples = 2))
            .single { it.id == "request_samples_unstable" }
        assertTrue(finding.limitation.contains("not IP packet-loss"))
        assertTrue(ids(wifi, ProbeResults(medianLatencyMs = 151)).contains("request_samples_unstable"))
        assertTrue(ids(wifi, ProbeResults(latencySpreadMs = 101)).contains("request_samples_unstable"))
    }

    @Test fun throughputRequiresKnownBitrateAndUsesOnePointFiveRatio() {
        assertFalse(ids(wifi, ProbeResults(throughputMbps = 15.0, selectedTrackBitrateMbps = 10.0))
            .contains("throughput_below_track"))
        assertTrue(ids(wifi, ProbeResults(throughputMbps = 14.99, selectedTrackBitrateMbps = 10.0))
            .contains("throughput_below_track"))
        assertFalse(ids(wifi, ProbeResults(throughputMbps = 1.0)).contains("throughput_below_track"))
    }

    @Test fun deepStreamFindingsCompareOnlyKnownBitrateAndReportStallsAndServerErrors() {
        val unknown = ids(wifi, ProbeResults(streamThroughputMbps = 0.2))
        assertFalse(unknown.contains("stream_throughput_below_bitrate"))
        val measured = NetworkDiagnosis.diagnose(wifi, ProbeResults(streamThroughputMbps = 1.0,
            streamBitrateMbps = 1.0, streamStalls = 2, providerStatus = 503)).map { it.id }
        assertTrue(measured.contains("stream_throughput_below_bitrate"))
        assertTrue(measured.contains("stream_stalls"))
        assertTrue(measured.contains("provider_server_errors"))
    }

    @Test fun captivePortalSuppressesRelatedDnsSymptomAndRanksFirst() {
        val findings = NetworkDiagnosis.diagnose(wifi.copy(captivePortal = true),
            recent = RecentEventSummary(dnsFailureCount = 1), probes = ProbeResults(failedSamples = 2))
        assertTrue(findings.any { it.id == "captive_portal" })
        assertFalse(findings.any { it.id == "dns_failures" })
        assertEquals("captive_portal", findings.first().id)
    }

    @Test fun disabledAutomaticTimeIsEvidenceEvenWithoutClockProbe() {
        assertTrue(ids(wifi.copy(automaticTimeEnabled = false)).contains("clock_skew"))
        assertFalse(ids(wifi.copy(automaticTimeEnabled = true)).contains("clock_skew"))
        assertTrue(ids(wifi, ProbeResults(certificateValidityTimeError = true)).contains("clock_skew"))
    }

    private fun ids(snapshot: NetworkSnapshot, probes: ProbeResults = ProbeResults(),
                    recent: RecentEventSummary = RecentEventSummary()) =
        NetworkDiagnosis.diagnose(snapshot, probes, recent).map { it.id }
}
