//! Rust port of the APS reverse-net relay (fork of Genymobile gnirehtet relay-java).
//!
//! Contract source: `/tmp/aps-port-spec-reversenet.md` (a per-file audit of
//! `desktop-viewer/network-profiler/reverse-net`). Module map:
//! `packet` (IPv4/TCP/UDP headers + checksums), `buffer` (StreamBuffer/DatagramBuffer),
//! `packetizer` (IPv4PacketBuffer + Packetizer), `conn` (TCP/UDP pseudo connections),
//! `router` (connection table), `tunnel` (TunnelServer/Client/RelayListener),
//! `relay` (RelayEngine, mio 1.2.2 single-thread event loop), `dns` (DnsMessage /
//! DnsInterceptor), `leak` (LeakAuditor / PrivateDnsDetector), `events` (event bus),
//! `session` (SessionController / adb operations / vendored APK paths).
//!
//! Wire protocol (spec §1): the host sends a 4-byte big-endian client id first,
//! then the tunnel carries raw IPv4 packets back to back with no framing; packets
//! are split by the IPv4 total length field (offset 2..3). The relay listens on
//! `127.0.0.1:31416` only.

pub mod buffer;
pub mod conn;
pub mod dns;
pub mod events;
pub mod leak;
pub mod packet;
pub mod packetizer;
pub mod relay;
pub mod router;
pub mod session;
pub mod tunnel;

/// Vendored gnirehtet 2.5.1 client APK embedded at build time (spec §0: 23904
/// bytes, sha256 c1ac2b86…c564b9a, unmodified / unsigned). Extracted to the data
/// directory by [`session::vendored_apk`].
pub const EMBEDDED_APK: &[u8] = include_bytes!("../assets/gnirehtet.apk");

/// Wall-clock milliseconds (`System.currentTimeMillis` in the Kotlin port); used
/// for UDP idle expiration and the relay cleaning schedule.
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
