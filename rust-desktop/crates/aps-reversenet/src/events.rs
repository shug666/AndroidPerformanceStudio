//! Relay observation events and the bounded event bus.
//!
//! Ported from `events/ReverseNetEvents.kt` (spec §6.5) and `relay/TapEventBridge.kt`
//! (spec §2.9). Emission never blocks: the bus is a bounded ring (`BUS_CAPACITY`,
//! DROP_OLDEST) and the dropped counter is incremented on overflow — this fixes the
//! fork's dead `droppedEvents` counter (`tryEmit` result ignored, spec §0.5). Every
//! event carries `monotonic_ns` and a globally increasing `sequence` (AtomicU64).

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

/// Bus capacity: `extraBufferCapacity = 20_000` with `DROP_OLDEST` (spec §9).
pub const BUS_CAPACITY: usize = 20_000;

/// Monotonic clock nanoseconds (`System.nanoTime` in the Kotlin port).
pub fn now_ns() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReverseNetTransport {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReverseNetAlertKind {
    V6Leak,
    PrivateDnsDegraded,
}

/// `ReverseNetEvent` sealed class, one variant per producer (spec §6.5). `Bytes`
/// gains a producer in this port: the relay emits it once per cleaning cycle
/// (60 s throttle) per live connection.
#[derive(Debug, Clone)]
pub enum ReverseNetEvent {
    Connection {
        connection_id: i64,
        transport: ReverseNetTransport,
        src_address: String,
        src_port: u16,
        dst_address: String,
        dst_port: u16,
        monotonic_ns: u64,
        sequence: u64,
    },
    State {
        connection_id: i64,
        state: String,
        monotonic_ns: u64,
        sequence: u64,
    },
    Bytes {
        connection_id: i64,
        bytes_sent: u64,
        bytes_received: u64,
        monotonic_ns: u64,
        sequence: u64,
    },
    Closed {
        connection_id: i64,
        duration_ns: u64,
        bytes_sent: u64,
        bytes_received: u64,
        monotonic_ns: u64,
        sequence: u64,
    },
    DnsResolved {
        domain: String,
        addresses: Vec<String>,
        aaaa_stripped: bool,
        monotonic_ns: u64,
        sequence: u64,
    },
    Alert {
        alert_kind: ReverseNetAlertKind,
        message: String,
        monotonic_ns: u64,
        sequence: u64,
    },
}

impl ReverseNetEvent {
    pub fn sequence(&self) -> u64 {
        match self {
            Self::Connection { sequence, .. }
            | Self::State { sequence, .. }
            | Self::Bytes { sequence, .. }
            | Self::Closed { sequence, .. }
            | Self::DnsResolved { sequence, .. }
            | Self::Alert { sequence, .. } => *sequence,
        }
    }

}

/// Bounded drop-oldest event bus. Consumers take Mutex-guarded snapshots via
/// [`ReverseNetEventBus::drain`] / [`ReverseNetEventBus::events_snapshot`]
/// (spec §6.5: UI keeps at most the newest 500).
pub struct ReverseNetEventBus {
    queue: Mutex<VecDeque<ReverseNetEvent>>,
    sequence_gen: AtomicU64,
    dropped_count: AtomicU64,
}

impl ReverseNetEventBus {
    pub fn new() -> Self {
        Self {
            queue: Mutex::new(VecDeque::with_capacity(BUS_CAPACITY)),
            sequence_gen: AtomicU64::new(0),
            dropped_count: AtomicU64::new(0),
        }
    }

    pub fn dropped_events(&self) -> u64 {
        self.dropped_count.load(Ordering::Relaxed)
    }

    /// Non-blocking emit: assigns a global sequence to `sequence == 0` events,
    /// drops the oldest event (counting it) when the ring is full.
    pub fn emit(&self, event: ReverseNetEvent) {
        let event = match event.sequence() {
            0 => assign_sequence(event, self.sequence_gen.fetch_add(1, Ordering::Relaxed) + 1),
            _ => event,
        };
        let mut queue = self.queue.lock().unwrap();
        if queue.len() >= BUS_CAPACITY {
            queue.pop_front();
            self.dropped_count.fetch_add(1, Ordering::Relaxed);
        }
        queue.push_back(event);
    }

    /// Snapshot drain: removes and returns every buffered event (oldest first).
    pub fn drain(&self) -> Vec<ReverseNetEvent> {
        let mut queue = self.queue.lock().unwrap();
        std::mem::replace(&mut *queue, VecDeque::new()).into_iter().collect()
    }

    /// Peek-style snapshot keeping the newest `limit` events (oldest first).
    pub fn events_snapshot(&self, limit: usize) -> Vec<ReverseNetEvent> {
        let queue = self.queue.lock().unwrap();
        let skip = queue.len().saturating_sub(limit);
        queue.iter().skip(skip).cloned().collect()
    }
}

impl Default for ReverseNetEventBus {
    fn default() -> Self {
        Self::new()
    }
}

fn assign_sequence(event: ReverseNetEvent, sequence: u64) -> ReverseNetEvent {
    match event {
        ReverseNetEvent::Connection { connection_id, transport, src_address, src_port, dst_address, dst_port, monotonic_ns, .. } => {
            ReverseNetEvent::Connection { connection_id, transport, src_address, src_port, dst_address, dst_port, monotonic_ns, sequence }
        }
        ReverseNetEvent::State { connection_id, state, monotonic_ns, .. } => {
            ReverseNetEvent::State { connection_id, state, monotonic_ns, sequence }
        }
        ReverseNetEvent::Bytes { connection_id, bytes_sent, bytes_received, monotonic_ns, .. } => {
            ReverseNetEvent::Bytes { connection_id, bytes_sent, bytes_received, monotonic_ns, sequence }
        }
        ReverseNetEvent::Closed { connection_id, duration_ns, bytes_sent, bytes_received, monotonic_ns, .. } => {
            ReverseNetEvent::Closed { connection_id, duration_ns, bytes_sent, bytes_received, monotonic_ns, sequence }
        }
        ReverseNetEvent::DnsResolved { domain, addresses, aaaa_stripped, monotonic_ns, .. } => {
            ReverseNetEvent::DnsResolved { domain, addresses, aaaa_stripped, monotonic_ns, sequence }
        }
        ReverseNetEvent::Alert { alert_kind, message, monotonic_ns, .. } => {
            ReverseNetEvent::Alert { alert_kind, message, monotonic_ns, sequence }
        }
    }
}

/// Bridges relay hot-path observation points to the bus (TapEventBridge.kt, fork).
/// All callbacks run on the relay selector thread; emission never blocks.
/// Client connect/disconnect are intentionally empty: tunnel-level events carry no
/// observability payload (fork comment).
pub struct TapEventBridge {
    bus: Arc<ReverseNetEventBus>,
    opened_at_ns: Mutex<std::collections::HashMap<i64, u64>>,
}

impl TapEventBridge {
    pub fn new(bus: Arc<ReverseNetEventBus>) -> Self {
        Self { bus, opened_at_ns: Mutex::new(std::collections::HashMap::new()) }
    }
}

impl crate::tunnel::RelayListener for TapEventBridge {
    fn on_client_connected(&self, _client_id: i32) {}
    fn on_client_disconnected(&self, _client_id: i32) {}

    fn on_connection_opened(&self, _connection: &dyn crate::conn::Connection, id: &crate::conn::ConnectionId) {
        let transport = match id.protocol {
            crate::packet::Protocol::Tcp => ReverseNetTransport::Tcp,
            crate::packet::Protocol::Udp => ReverseNetTransport::Udp,
            crate::packet::Protocol::Other => return,
        };
        let now = now_ns();
        // connectionId = id.hashCode(): the Kotlin port used the JVM identity-ish
        // hash of the protocol enum, which is not stable across runs; this port
        // uses a stable mixed hash with the documented 31-chained formula.
        let connection_id = id.hash_code();
        self.opened_at_ns.lock().unwrap().insert(connection_id, now);
        self.bus.emit(ReverseNetEvent::Connection {
            connection_id,
            transport,
            // substringBefore(':') — dotted IP only, no port (TapEventBridge.kt).
            src_address: crate::packet::dotted_ip(id.source_ip),
            src_port: id.source_port,
            dst_address: crate::packet::dotted_ip(id.dest_ip),
            dst_port: id.dest_port,
            monotonic_ns: now,
            sequence: 0,
        });
    }

    fn on_connection_closed(&self, connection: &dyn crate::conn::Connection, id: &crate::conn::ConnectionId) {
        let now = now_ns();
        let start = self.opened_at_ns.lock().unwrap().remove(&id.hash_code()).unwrap_or(now);
        self.bus.emit(ReverseNetEvent::Closed {
            connection_id: id.hash_code(),
            duration_ns: now.saturating_sub(start),
            bytes_sent: connection.bytes_sent_to_network(),
            bytes_received: connection.bytes_received_from_network(),
            monotonic_ns: now,
            sequence: 0,
        });
    }

    fn on_packet_dropped(&self, _packet: &crate::packet::IPv4Packet) {
        self.bus.emit(ReverseNetEvent::State {
            connection_id: -1,
            state: "PACKET_DROPPED".to_owned(),
            monotonic_ns: now_ns(),
            sequence: 0,
        });
    }

    /// Fork addition for this port: periodic `Bytes` emission (60 s cleaning cycle
    /// throttle); the Kotlin fork left this variant without a producer (spec §0.6).
    fn on_connection_bytes(&self, connection: &dyn crate::conn::Connection, id: &crate::conn::ConnectionId) {
        self.bus.emit(ReverseNetEvent::Bytes {
            connection_id: id.hash_code(),
            bytes_sent: connection.bytes_sent_to_network(),
            bytes_received: connection.bytes_received_from_network(),
            monotonic_ns: now_ns(),
            sequence: 0,
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> ReverseNetEvent {
        ReverseNetEvent::State {
            connection_id: 1,
            state: "PACKET_DROPPED".to_owned(),
            monotonic_ns: now_ns(),
            sequence: 0,
        }
    }

    #[test]
    fn sequences_are_globally_increasing() {
        let bus = ReverseNetEventBus::new();
        bus.emit(sample());
        bus.emit(sample());
        let drained = bus.drain();
        assert_eq!(drained.len(), 2);
        assert_eq!(drained[0].sequence(), 1);
        assert_eq!(drained[1].sequence(), 2);
    }

    /// Spec §0.5 fix: the dropped counter must increment when the ring overflows.
    #[test]
    fn overflow_drops_oldest_and_counts() {
        let bus = ReverseNetEventBus::new();
        for _ in 0..BUS_CAPACITY + 7 {
            bus.emit(sample());
        }
        assert_eq!(bus.dropped_events(), 7);
        let drained = bus.drain();
        assert_eq!(drained.len(), BUS_CAPACITY);
        // The 7 oldest were dropped: first survivor carries sequence 8.
        assert_eq!(drained.first().unwrap().sequence(), 8);
        assert_eq!(drained.last().unwrap().sequence(), BUS_CAPACITY as u64 + 7);
    }

    #[test]
    fn snapshot_keeps_newest_limit() {
        let bus = ReverseNetEventBus::new();
        for _ in 0..10 {
            bus.emit(sample());
        }
        let snapshot = bus.events_snapshot(3);
        assert_eq!(snapshot.len(), 3);
        assert_eq!(snapshot.first().unwrap().sequence(), 8);
        assert_eq!(snapshot.last().unwrap().sequence(), 10);
    }
}
