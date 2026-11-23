//! IPv6 leak audit and Private DNS detection.
//!
//! Ported from `leak/{LeakAuditor,PrivateDnsDetector}.kt` (spec §4). While a
//! reverse-tethering session is active the tunnel only covers IPv4: if the
//! device keeps a WiFi/cellular network, IPv6 traffic bypasses the host — a
//! "leak". The audit only runs when a non-tunnel network exists, and probe
//! failures degrade silently: counted, never interpreted as a leak (design
//! principle from the Kotlin comments).
//!
//! Wiring (spec §0/§4.3 — dormant in the Kotlin fork, connected here): the
//! session controller starts the 30 s audit loop when the state reaches
//! Sharing, and queries Private DNS once at session start and once at stop.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::events::{now_ns, ReverseNetAlertKind, ReverseNetEvent, ReverseNetEventBus};

/// LeakAuditor.AUDIT_INTERVAL_MS.
pub const AUDIT_INTERVAL_MS: u64 = 30_000;
/// Google DNS used as the IPv6 probe target (LeakAuditor.PROBE_TARGET_V6).
pub const PROBE_TARGET_V6: &str = "2001:4860:4860::8888";
pub const LEAK_MESSAGE: &str =
    "设备仍可通过 IPv6 直接访问互联网（未走供网隧道）。建议关闭设备 WiFi 或开启飞行模式。";
/// PrivateDnsDetector.STRICT_MODE_HOSTNAME.
pub const STRICT_MODE_HOSTNAME: &str = "hostname";
pub const DEGRADED_MESSAGE: &str =
    "设备启用了 Private DNS，DNS 观测与 IPv6 防泄漏已降级。建议关闭 Private DNS 或改为自动模式。";

/// Shell probe executed on the device, isolated for testability (DeviceProbe.kt).
/// Returns trimmed stdout, or `None` on failure.
pub trait DeviceProbe: Send + Sync {
    fn shell(&self, serial: &str, command: &[&str]) -> Option<String>;
}

/// Audit result for one probe cycle (AuditOutcome).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuditOutcome {
    Leak,
    NoV6Exit,
    NoNonTunnelNetwork,
    ProbeDegraded,
}

/// Periodically audits whether the device can still reach the internet over
/// IPv6 while a reverse-tethering session is active (LeakAuditor.kt).
#[derive(Clone)]
pub struct LeakAuditor {
    bus: Arc<ReverseNetEventBus>,
    probe: Arc<dyn DeviceProbe>,
    interval_ms: u64,
    probe_failure_count: Arc<AtomicU64>,
}

impl LeakAuditor {
    pub fn new(bus: Arc<ReverseNetEventBus>, probe: Arc<dyn DeviceProbe>) -> Self {
        Self { bus, probe, interval_ms: AUDIT_INTERVAL_MS, probe_failure_count: Arc::new(AtomicU64::new(0)) }
    }

    pub fn probe_failures(&self) -> u64 {
        self.probe_failure_count.load(Ordering::Relaxed)
    }

    /// One audit cycle; the decision chain of LeakAuditor.auditOnce (spec §4.2).
    pub fn audit_once(&self, serial: &str) -> AuditOutcome {
        let Some(connectivity) = self.probe.shell(serial, &["dumpsys", "connectivity"]) else {
            return self.degraded();
        };
        if !has_non_tunnel_network(&connectivity) {
            return AuditOutcome::NoNonTunnelNetwork;
        }
        // Main probe: can the device ping an external IPv6 address?
        let ping = self.probe.shell(serial, &["ping6", "-c", "1", "-W", "2", PROBE_TARGET_V6]);
        if ping.as_deref().map(|out| out.to_lowercase().contains("1 received")).unwrap_or(false) {
            self.emit_leak();
            return AuditOutcome::Leak;
        }
        // Fallback probe: does a v6 route to the default gateway exist?
        let route = self.probe.shell(serial, &["ip", "-6", "route", "get", PROBE_TARGET_V6]);
        if route
            .as_deref()
            .map(|out| out.contains("via") && !out.contains("unreachable"))
            .unwrap_or(false)
        {
            self.emit_leak();
            return AuditOutcome::Leak;
        }
        if ping.is_none() && route.is_none() {
            // Both probes unavailable: degrade silently.
            return self.degraded();
        }
        AuditOutcome::NoV6Exit
    }

    fn degraded(&self) -> AuditOutcome {
        self.probe_failure_count.fetch_add(1, Ordering::Relaxed);
        AuditOutcome::ProbeDegraded
    }

    fn emit_leak(&self) {
        self.bus.emit(ReverseNetEvent::Alert {
            alert_kind: ReverseNetAlertKind::V6Leak,
            message: LEAK_MESSAGE.to_owned(),
            monotonic_ns: now_ns(),
            sequence: 0,
        });
    }

    /// Start the 30 s audit loop on a joinable thread (`LeakAuditor.start` +
    /// coroutine loop; a panic in the probe counts as a probe failure instead of
    /// killing the auditor, mirroring the Kotlin `catch (Throwable)`).
    pub fn spawn_thread(&self, serial: &str) -> AuditorRunning {
        let stop = Arc::new(AtomicBool::new(false));
        let auditor = self.clone();
        let serial = serial.to_owned();
        let handle = std::thread::Builder::new()
            .name("aps-reverse-net-auditor".to_owned())
            .spawn({
                let stop = stop.clone();
                move || {
                    while !stop.load(Ordering::Relaxed) {
                        if catch_unwind(AssertUnwindSafe(|| auditor.audit_once(&serial))).is_err() {
                            auditor.probe_failure_count.fetch_add(1, Ordering::Relaxed);
                        }
                        let interval = Duration::from_millis(auditor.interval_ms);
                        let started = Instant::now();
                        while !stop.load(Ordering::Relaxed) && started.elapsed() < interval {
                            std::thread::sleep(Duration::from_millis(100).min(
                                interval.saturating_sub(started.elapsed()).max(Duration::from_millis(1)),
                            ));
                        }
                    }
                }
            })
            .expect("spawn aps-reverse-net-auditor");
        AuditorRunning { stop, handle: Some(handle) }
    }
}

/// True when dumpsys connectivity shows an active network besides the VPN tunnel
/// (LeakAuditor.hasNonTunnelNetwork): `"Active default network:\s*(-?\d+)"` with
/// the value not `-1`. Hand-rolled scan — the dependency set excludes regex crates.
pub fn has_non_tunnel_network(dumpsys_output: &str) -> bool {
    const NEEDLE: &str = "Active default network:";
    let Some(index) = dumpsys_output.find(NEEDLE) else { return false };
    let rest = &dumpsys_output[index + NEEDLE.len()..];
    let rest = rest.trim_start();
    let bytes = rest.as_bytes();
    let mut cursor = 0;
    let mut value = String::new();
    if cursor < bytes.len() && bytes[cursor] == b'-' {
        value.push('-');
        cursor += 1;
    }
    while cursor < bytes.len() && bytes[cursor].is_ascii_digit() {
        value.push(bytes[cursor] as char);
        cursor += 1;
    }
    // The regex requires at least one digit after the optional minus.
    if value.is_empty() || value == "-" {
        return false;
    }
    value != "-1"
}

/// Joinable auditor thread handle; dropping it (or calling `stop_and_join`)
/// stops the loop and releases the thread (Kotlin `stop()` cancels the job).
pub struct AuditorRunning {
    stop: Arc<AtomicBool>,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl AuditorRunning {
    pub fn stop_and_join(mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for AuditorRunning {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

/// Detects Private DNS (DoT) on the device: when enabled, DNS flowing through
/// the tunnel disappears, degrading both DNS observability and AAAA filtering
/// (PrivateDnsDetector.kt).
pub struct PrivateDnsDetector {
    bus: Arc<ReverseNetEventBus>,
    probe: Arc<dyn DeviceProbe>,
}

impl PrivateDnsDetector {
    pub fn new(bus: Arc<ReverseNetEventBus>, probe: Arc<dyn DeviceProbe>) -> Self {
        Self { bus, probe }
    }

    /// Probe the device's private DNS setting; emits a degradation alert in
    /// strict (hostname) mode. Returns true when Private DNS degraded the session.
    pub fn check_and_alert(&self, serial: &str) -> bool {
        let Some(mode) = self.probe.shell(serial, &["settings", "get", "global", "private_dns_mode"])
        else {
            return false;
        };
        if mode.trim() != STRICT_MODE_HOSTNAME {
            return false; // off / opportunistic / unknown: silent
        }
        self.bus.emit(ReverseNetEvent::Alert {
            alert_kind: ReverseNetAlertKind::PrivateDnsDegraded,
            message: DEGRADED_MESSAGE.to_owned(),
            monotonic_ns: now_ns(),
            sequence: 0,
        });
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// FakeProbe matches the full command joined by spaces (LeakAuditorTest).
    struct FakeProbe {
        responses: std::collections::HashMap<String, String>,
    }

    impl FakeProbe {
        fn new(responses: &[(&str, &str)]) -> Arc<Self> {
            Arc::new(Self {
                responses: responses
                    .iter()
                    .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
                    .collect(),
            })
        }
    }

    impl DeviceProbe for FakeProbe {
        fn shell(&self, _serial: &str, command: &[&str]) -> Option<String> {
            self.responses.get(&command.join(" ")).cloned()
        }
    }

    const PING: &str = "ping6 -c 1 -W 2 2001:4860:4860::8888";
    const ROUTE: &str = "ip -6 route get 2001:4860:4860::8888";
    const DUMPSYS: &str = "dumpsys connectivity";
    const SETTINGS: &str = "settings get global private_dns_mode";

    #[test]
    fn leak_detected_when_ping6_succeeds() {
        // LEAK outcome implies a V6_LEAK alert was emitted on the bus (§8.4.1).
        let bus = Arc::new(ReverseNetEventBus::new());
        let probe = FakeProbe::new(&[
            (DUMPSYS, "Active default network: 101"),
            (PING, "1 packets transmitted, 1 received, 0% packet loss"),
        ]);
        let auditor = LeakAuditor::new(bus.clone(), probe);
        assert_eq!(auditor.audit_once("S"), AuditOutcome::Leak);
        let events = bus.drain();
        assert!(matches!(
            &events[..],
            [ReverseNetEvent::Alert { alert_kind: ReverseNetAlertKind::V6Leak, .. }]
        ));
    }

    #[test]
    fn no_alert_when_device_has_no_v6_exit() {
        let bus = Arc::new(ReverseNetEventBus::new());
        let probe = FakeProbe::new(&[
            (DUMPSYS, "Active default network: 101"),
            (PING, "100% packet loss"),
            (ROUTE, "unreachable"),
        ]);
        let auditor = LeakAuditor::new(bus.clone(), probe);
        assert_eq!(auditor.audit_once("S"), AuditOutcome::NoV6Exit);
        assert_eq!(auditor.probe_failures(), 0);
        assert!(bus.drain().is_empty());
    }

    #[test]
    fn audit_skipped_when_no_non_tunnel_network() {
        let probe = FakeProbe::new(&[(DUMPSYS, "Active default network: -1")]);
        let auditor = LeakAuditor::new(Arc::new(ReverseNetEventBus::new()), probe);
        assert_eq!(auditor.audit_once("S"), AuditOutcome::NoNonTunnelNetwork);
    }

    #[test]
    fn probe_failure_degrades_silently() {
        // No responses at all: both probes unavailable (§8.4.4).
        let probe: Arc<dyn DeviceProbe> = Arc::new(FakeProbe { responses: Default::default() });
        let auditor = LeakAuditor::new(Arc::new(ReverseNetEventBus::new()), probe);
        assert_eq!(auditor.audit_once("S"), AuditOutcome::ProbeDegraded);
        assert_eq!(auditor.probe_failures(), 1);
    }

    #[test]
    fn route_fallback_detects_leak() {
        let probe = FakeProbe::new(&[
            (DUMPSYS, "Active default network: 101"),
            (ROUTE, "2001:db8:: from :: via fe80::1 dev wlan0"),
        ]);
        let auditor = LeakAuditor::new(Arc::new(ReverseNetEventBus::new()), probe);
        assert_eq!(auditor.audit_once("S"), AuditOutcome::Leak);
    }

    #[test]
    fn has_non_tunnel_network_parses_dumpsys() {
        assert!(!has_non_tunnel_network("Active default network: -1"));
        assert!(has_non_tunnel_network("Active default network: 101"));
        assert!(!has_non_tunnel_network("no output"));
        // Regex-ish edge: whitespace before the value, missing digits.
        assert!(has_non_tunnel_network("x\nActive default network:   42\n"));
        assert!(!has_non_tunnel_network("Active default network: -"));
    }

    #[test]
    fn private_dns_hostname_mode_alerts() {
        // True return implies a PRIVATE_DNS_DEGRADED alert was emitted (§8.4.7).
        let bus = Arc::new(ReverseNetEventBus::new());
        let detector = PrivateDnsDetector::new(
            bus.clone(),
            FakeProbe::new(&[(SETTINGS, "hostname")]),
        );
        assert!(detector.check_and_alert("S"));
        assert!(matches!(
            &bus.drain()[..],
            [ReverseNetEvent::Alert { alert_kind: ReverseNetAlertKind::PrivateDnsDegraded, .. }]
        ));
    }

    #[test]
    fn private_dns_off_mode_is_silent() {
        let bus = Arc::new(ReverseNetEventBus::new());
        let detector = PrivateDnsDetector::new(
            bus.clone(),
            FakeProbe::new(&[(SETTINGS, "off")]),
        );
        assert!(!detector.check_and_alert("S"));
        assert!(bus.drain().is_empty());
    }
}
