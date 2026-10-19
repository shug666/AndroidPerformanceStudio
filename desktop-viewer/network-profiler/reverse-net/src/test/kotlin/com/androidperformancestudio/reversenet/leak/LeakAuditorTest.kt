package com.androidperformancestudio.reversenet.leak

import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeProbe(
    private val responses: Map<String, String?>,
) : DeviceProbe {
    override suspend fun shell(serial: String, command: List<String>): String? = responses[command.joinToString(" ")]
}

public class LeakAuditorTest {
    private val eventBus = ReverseNetEventBus()

    @Test
    public fun `leak detected when ping6 succeeds`() = runBlocking {
        val probe = FakeProbe(
            mapOf(
                "dumpsys connectivity" to "Active default network: 101",
                "ping6 -c 1 -W 2 2001:4860:4860::8888" to "1 packets transmitted, 1 received, 0% packet loss",
            ),
        )
        val auditor = LeakAuditor(eventBus, probe)
        // LEAK outcome implies a V6_LEAK alert was emitted on the bus
        assertEquals(AuditOutcome.LEAK, auditor.auditOnce("S"))
    }

    @Test
    public fun `no alert when device has no v6 exit`() = runBlocking {
        val probe = FakeProbe(
            mapOf(
                "dumpsys connectivity" to "Active default network: 101",
                "ping6 -c 1 -W 2 2001:4860:4860::8888" to "100% packet loss",
                "ip -6 route get 2001:4860:4860::8888" to "unreachable",
            ),
        )
        val auditor = LeakAuditor(eventBus, probe)
        assertEquals(AuditOutcome.NO_V6_EXIT, auditor.auditOnce("S"))
        assertEquals(0, auditor.probeFailures())
    }

    @Test
    public fun `audit skipped when no non-tunnel network`() = runBlocking {
        val probe = FakeProbe(mapOf("dumpsys connectivity" to "Active default network: -1"))
        val auditor = LeakAuditor(eventBus, probe)
        assertEquals(AuditOutcome.NO_NON_TUNNEL_NETWORK, auditor.auditOnce("S"))
    }

    @Test
    public fun `probe failure degrades silently`() = runBlocking {
        val probe = FakeProbe(emptyMap())
        val auditor = LeakAuditor(eventBus, probe)
        assertEquals(AuditOutcome.PROBE_DEGRADED, auditor.auditOnce("S"))
        assertEquals(1, auditor.probeFailures())
    }

    @Test
    public fun `route fallback detects leak`() = runBlocking {
        val probe = FakeProbe(
            mapOf(
                "dumpsys connectivity" to "Active default network: 101",
                "ping6 -c 1 -W 2 2001:4860:4860::8888" to null,
                "ip -6 route get 2001:4860:4860::8888" to "2001:db8:: from :: via fe80::1 dev wlan0",
            ),
        )
        val auditor = LeakAuditor(eventBus, probe)
        assertEquals(AuditOutcome.LEAK, auditor.auditOnce("S"))
    }

    @Test
    public fun `hasNonTunnelNetwork parses dumpsys`() {
        assertFalse(LeakAuditor.hasNonTunnelNetwork("Active default network: -1"))
        assertTrue(LeakAuditor.hasNonTunnelNetwork("Active default network: 101"))
        assertFalse(LeakAuditor.hasNonTunnelNetwork("no output"))
    }

    @Test
    public fun `private dns hostname mode alerts`() = runBlocking {
        val detector = PrivateDnsDetector(eventBus, FakeProbe(mapOf("settings get global private_dns_mode" to "hostname")))
        // True return implies a PRIVATE_DNS_DEGRADED alert was emitted on the bus
        assertTrue(detector.checkAndAlert("S"))
    }

    @Test
    public fun `private dns off mode is silent`() = runBlocking {
        val detector = PrivateDnsDetector(eventBus, FakeProbe(mapOf("settings get global private_dns_mode" to "off")))
        assertFalse(detector.checkAndAlert("S"))
    }
}
