//! Relay engine: the single-threaded mio event loop.
//!
//! Ported from `relay/RelayEngine.kt` (spec §2.1): one shared Poll (Selector),
//! the tunnel server bound to `127.0.0.1:<port>`, a 60 s cleaning cadence driven
//! by the poll timeout (empty event sets also trigger a clean, mirroring the
//! Kotlin `selectedKeys.isEmpty()` branch) and UDP idle expiration after 120 s.
//! `stop()` mirrors `isRunning=false; selector.wakeup(); tunnelServer.close()`:
//! it flips the flag, wakes the poll, and joins the loop thread, which closes
//! every client before exiting.
//!
//! Java OP_ACCEPT / OP_READ / OP_WRITE map to mio interests directly; OP_CONNECT
//! becomes WRITABLE for in-flight connects (see conn.rs). The engine thread is
//! non-daemon-equivalent: the process keeps the join handle and stops it
//! explicitly (Kotlin ran the loop on a named non-daemon thread
//! "aps-reverse-net-relay").

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use mio::{Events, Poll, Waker};

use crate::dns::DnsInterceptor;
use crate::tunnel::{RelayListener, TunnelServer, TOKEN_LISTENER, TOKEN_WAKER};
use crate::router::TokenAllocator;

/// RelayEngine.DEFAULT_PORT (spec §9: same as the official gnirehtet relay).
pub const DEFAULT_PORT: u16 = 31416;
/// RelayEngine.CLEANING_INTERVAL = 60 * 1000 ms.
pub const CLEANING_INTERVAL_MS: u64 = 60 * 1000;
/// Initial cleaning deadline = now + UDP idle timeout (RelayEngine.runLoop).
const INITIAL_DEADLINE_OFFSET_MS: u64 = crate::conn::UDP_IDLE_TIMEOUT_MS;

/// RelayEngine stand-in contract for SessionController injection (tests swap in
/// a fake that never binds a socket).
pub trait Relay: Send {
    fn start(
        &mut self,
        listener: Arc<dyn RelayListener>,
        dns_interceptor: Option<Arc<DnsInterceptor>>,
    ) -> Result<(), String>;
    fn stop(&mut self);
    fn is_running(&self) -> bool;
}

/// The mio-backed engine. `start()` prepares Poll + TunnelServer and spawns the
/// loop on a dedicated joinable thread (Kotlin: controller created the engine,
/// called `start()`, then ran `runLoop()` on a thread; ownership rules in Rust
/// keep the same behavior inside `start`).
pub struct MioRelayEngine {
    port: u16,
    running: Option<Arc<AtomicBool>>,
    waker: Option<Waker>,
    worker: Option<std::thread::JoinHandle<()>>,
    bound_port: Option<u16>,
}

impl MioRelayEngine {
    pub fn new(port: u16) -> Self {
        Self { port, running: None, waker: None, worker: None, bound_port: None }
    }

    /// Actual port after `start()` (useful when started with port 0).
    pub fn bound_port(&self) -> Option<u16> {
        self.bound_port
    }

    fn run_loop(running: Arc<AtomicBool>, mut poll: Poll, mut server: TunnelServer) {
        let mut events = Events::with_capacity(256);
        let mut next_cleaning_deadline = crate::now_ms() + INITIAL_DEADLINE_OFFSET_MS;
        while running.load(Ordering::Relaxed) {
            let timeout = Duration::from_millis(
                next_cleaning_deadline.saturating_sub(crate::now_ms()),
            );
            match poll.poll(&mut events, Some(timeout)) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
            let now = crate::now_ms();
            // Kotlin: `now >= deadline || selectedKeys.isEmpty()` → cleanUp and
            // reset the deadline to now + CLEANING_INTERVAL.
            if now >= next_cleaning_deadline || events.is_empty() {
                server.clean_up(now);
                next_cleaning_deadline = now + CLEANING_INTERVAL_MS;
            }
            for event in events.iter() {
                match event.token() {
                    TOKEN_WAKER => {} // stop signal only; the flag check exits
                    // SelectionHandler for the server channel: accept pending
                    // device connections (TunnelServer.kt OP_ACCEPT handler).
                    TOKEN_LISTENER => server.handle_accept(poll.registry()),
                    _ => server.handle_event(event, poll.registry()),
                }
            }
            events.clear();
        }
        // Kotlin stop(): tunnelServer.close() (disconnects every client, closes
        // the listening socket) then selector close. The loop owns both, so the
        // teardown happens here after the final wake — race-free by design.
        server.close();
    }
}

impl Relay for MioRelayEngine {
    fn start(
        &mut self,
        listener: Arc<dyn RelayListener>,
        dns_interceptor: Option<Arc<DnsInterceptor>>,
    ) -> Result<(), String> {
        if self.worker.is_some() {
            return Err("relay engine already started".to_owned());
        }
        let poll = Poll::new().map_err(|error| format!("selector open failed: {error}"))?;
        let waker =
            Waker::new(poll.registry(), TOKEN_WAKER).map_err(|error| format!("waker failed: {error}"))?;
        let tokens = Arc::new(TokenAllocator::starting_at(2));
        // Java OP_ACCEPT maps to mio READABLE on the listening socket; the
        // client/connection interests mapping lives in conn.rs/tunnel.rs.
        let server = TunnelServer::bind(
            self.port,
            poll.registry(),
            tokens,
            listener,
            dns_interceptor,
        )
        .map_err(|error| format!("relay bind failed: {error}"))?;
        self.bound_port = server.local_addr().ok().map(|addr| addr.port());
        let running = Arc::new(AtomicBool::new(true));
        let worker_running = running.clone();
        let worker = std::thread::Builder::new()
            .name("aps-reverse-net-relay".to_owned())
            .spawn(move || Self::run_loop(worker_running, poll, server))
            .map_err(|error| format!("relay thread spawn failed: {error}"))?;
        self.running = Some(running);
        self.waker = Some(waker);
        self.worker = Some(worker);
        Ok(())
    }

    fn stop(&mut self) {
        if let Some(running) = &self.running {
            running.store(false, Ordering::Relaxed);
        }
        if let Some(waker) = &self.waker {
            let _ = waker.wake();
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        self.running = None;
        self.waker = None;
        self.bound_port = None;
    }

    fn is_running(&self) -> bool {
        self.running.as_ref().map(|r| r.load(Ordering::Relaxed)).unwrap_or(false)
    }
}

impl Default for MioRelayEngine {
    fn default() -> Self {
        Self::new(DEFAULT_PORT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::conn::ConnectionId;
    use crate::packet::{
        IPv4Packet, FLAG_ACK, FLAG_FIN, FLAG_PSH, FLAG_SYN, LOCALHOST_FORWARD,
    };
    use crate::packetizer::IPv4PacketBuffer;
    use std::io::{Read, Write};
    use std::net::{Shutdown, TcpListener as StdListener, TcpStream as StdStream};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    /// §8.1 listener recorder.
    struct RecordingListener {
        opened: Mutex<Vec<String>>,
        closed: Mutex<Vec<String>>,
    }

    impl RecordingListener {
        fn new() -> Arc<Self> {
            Arc::new(Self { opened: Mutex::new(Vec::new()), closed: Mutex::new(Vec::new()) })
        }
    }

    impl RelayListener for RecordingListener {
        fn on_connection_opened(&self, _connection: &dyn crate::conn::Connection, id: &ConnectionId) {
            self.opened.lock().unwrap().push(id.to_string());
        }
        fn on_connection_closed(&self, _connection: &dyn crate::conn::Connection, id: &ConnectionId) {
            self.closed.lock().unwrap().push(id.to_string());
        }
    }

    /// Build the §8.1 SYN packet shape: 10.0.0.2:12345 → 10.0.2.2:<echoPort>,
    /// inbound checksums left at 0 (the relay recomputes on response only).
    fn build_tcp_packet(
        src_ip: u32,
        src_port: u16,
        dst_ip: u32,
        dst_port: u16,
        seq: i32,
        ack_num: i32,
        flags: u16,
        payload: &[u8],
    ) -> Vec<u8> {
        let total_length = 20 + 20 + payload.len();
        let mut buf = Vec::with_capacity(total_length);
        buf.push(0x40 | 5);
        buf.push(0); // DSCP
        buf.extend_from_slice(&(total_length as u16).to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes()); // id
        buf.extend_from_slice(&0x4000u16.to_be_bytes()); // DF
        buf.push(64); // TTL
        buf.push(6); // TCP
        buf.extend_from_slice(&0u16.to_be_bytes()); // checksum 0 (inbound not validated)
        buf.extend_from_slice(&src_ip.to_be_bytes());
        buf.extend_from_slice(&dst_ip.to_be_bytes());
        buf.extend_from_slice(&src_port.to_be_bytes());
        buf.extend_from_slice(&dst_port.to_be_bytes());
        buf.extend_from_slice(&(seq as u32).to_be_bytes());
        buf.extend_from_slice(&(ack_num as u32).to_be_bytes());
        buf.extend_from_slice(&(((5u16) << 12) | flags).to_be_bytes());
        buf.extend_from_slice(&8192u16.to_be_bytes()); // window
        buf.extend_from_slice(&0u16.to_be_bytes()); // checksum
        buf.extend_from_slice(&0u16.to_be_bytes()); // urgent
        buf.extend_from_slice(payload);
        buf
    }

    /// Device-side tunnel reader with a hard deadline (test must never hang).
    struct DeviceTunnel {
        stream: StdStream,
        buffer: IPv4PacketBuffer,
    }

    impl DeviceTunnel {
        fn read_packet(&mut self, deadline: Instant) -> Option<IPv4Packet> {
            while Instant::now() < deadline {
                let _ = self.stream.set_read_timeout(Some(Duration::from_millis(50)));
                match self.buffer.read_from_stream(&mut self.stream) {
                    Ok(Some(_)) => {}
                    Ok(None) => return None, // tunnel closed
                    Err(_) => {}
                }
                if let Some(packet) = self.buffer.as_ipv4_packet() {
                    self.buffer.next();
                    return Some(packet);
                }
                self.buffer.next();
            }
            None
        }

        fn write_raw(&mut self, bytes: &[u8]) {
            self.stream.write_all(bytes).expect("tunnel write");
        }
    }

    fn int_from_bytes(a: u32, b: u32, c: u32, d: u32) -> u32 {
        (a << 24) | (b << 16) | (c << 8) | d
    }

    /// §8.1 RelayLoopbackTest: real echo server + real RelayEngine + simulated
    /// device socket; asserts the full client-id → SYN → SYN|ACK → ACK → data →
    /// echo → FIN|ACK sequence.
    #[test]
    fn tcp_connection_round_trip_through_relay() {
        // 1. Real network target: local echo server (byte-for-byte echo).
        let echo_server = StdListener::bind("127.0.0.1:0").unwrap();
        let echo_port = echo_server.local_addr().unwrap().port();
        std::thread::spawn(move || {
            while let Ok((mut socket, _)) = echo_server.accept() {
                std::thread::spawn(move || {
                    let mut buf = [0u8; 4096];
                    while let Ok(r) = socket.read(&mut buf) {
                        if r == 0 {
                            break;
                        }
                        if socket.write_all(&buf[..r]).is_err() {
                            break;
                        }
                    }
                    let _ = socket.shutdown(Shutdown::Both);
                });
            }
        });

        // 2. Start the relay engine on a free port with the recording listener.
        let listener = RecordingListener::new();
        let mut engine = MioRelayEngine::new(0);
        engine.start(listener.clone(), None).expect("engine start");
        let relay_port = engine.bound_port().expect("bound port");
        assert_ne!(relay_port, 0);

        // 3. Simulate the device: connect to the relay (adb reverse tunnel).
        let tunnel = StdStream::connect(("127.0.0.1", relay_port)).unwrap();
        tunnel.set_nodelay(true).ok();
        let mut device = DeviceTunnel { stream: tunnel, buffer: IPv4PacketBuffer::new() };

        // 4. Read the client id: 4 bytes big-endian, first client is 0.
        let start = Instant::now();
        let mut id_bytes = [0u8; 4];
        let mut id_read = 0;
        while id_read < 4 && start.elapsed() < Duration::from_secs(3) {
            let _ = device.stream.set_read_timeout(Some(Duration::from_millis(50)));
            match device.stream.read(&mut id_bytes[id_read..]) {
                Ok(0) => break,
                Ok(n) => id_read += n,
                Err(_) => {}
            }
        }
        assert_eq!(id_read, 4, "client id handshake did not arrive");
        let client_id = i32::from_be_bytes(id_bytes);
        assert!(client_id >= 0);

        // 5. Craft the SYN: 10.0.0.2:12345 → 10.0.2.2:echoPort (loopback rewrite).
        let src_ip = int_from_bytes(10, 0, 0, 2);
        let dst_ip = LOCALHOST_FORWARD; // 10.0.2.2 alias → rewritten to loopback
        let src_port = 12345;
        let client_seq: i32 = 0x1122_3344;
        let syn = build_tcp_packet(src_ip, src_port, dst_ip, echo_port, client_seq, 0, FLAG_SYN, &[]);
        device.write_raw(&syn);

        // 6. Expect SYN|ACK within 3 s and record the server sequence.
        let syn_ack = device.read_packet(Instant::now() + Duration::from_secs(3));
        let syn_ack = syn_ack.expect("expected SYN|ACK");
        assert!(syn_ack.tcp_is_syn() && syn_ack.tcp_is_ack(), "expected SYN|ACK flags");
        assert!(syn_ack.tcp_checksum_valid(), "relay recomputes checksums on responses");
        let server_seq = syn_ack.tcp_sequence_number();
        // Response direction is swapped: relay alias port -> device port.
        assert_eq!(syn_ack.tcp().unwrap().source_port, echo_port);
        assert_eq!(syn_ack.tcp().unwrap().destination_port, src_port);

        // 7. Bare ACK completes the handshake (SYN_RECEIVED → ESTABLISHED), then
        // ACK|PSH carries the payload at the same seq/ack.
        let payload = b"hello relay";
        let bare_ack = build_tcp_packet(
            src_ip, src_port, dst_ip, echo_port, client_seq + 1, server_seq.wrapping_add(1),
            FLAG_ACK, &[],
        );
        device.write_raw(&bare_ack);
        let data_packet = build_tcp_packet(
            src_ip, src_port, dst_ip, echo_port, client_seq + 1, server_seq.wrapping_add(1),
            FLAG_ACK | FLAG_PSH, payload,
        );
        device.write_raw(&data_packet);

        // 8. Expect the echo (bare ACKs may interleave; loop until the payload).
        let mut echoed = None;
        let echo_deadline = Instant::now() + Duration::from_secs(3);
        while Instant::now() < echo_deadline {
            let Some(packet) = device.read_packet(echo_deadline) else { break };
            if packet.payload_len() > 0 {
                echoed = Some(packet.payload().to_vec());
                break;
            }
        }
        assert_eq!(echoed.as_deref(), Some(payload.as_slice()), "echo payload must round-trip");

        // 9. Close: FIN from the device side (§8.1 step: FIN|ACK).
        let fin = build_tcp_packet(
            src_ip, src_port, dst_ip, echo_port,
            client_seq + 1 + payload.len() as i32, 0,
            FLAG_FIN | FLAG_ACK, &[],
        );
        device.write_raw(&fin);

        // 10. Cleanup; the engine stop must join its thread promptly.
        let stop_start = Instant::now();
        engine.stop();
        assert!(stop_start.elapsed() < Duration::from_secs(5), "stop must join the relay thread");
        assert!(!engine.is_running());
        // One relayed TCP connection was opened through the router.
        assert_eq!(listener.opened.lock().unwrap().len(), 1);
        drop(device);
        // The echo thread blocks on accept forever (Kotlin used a daemon thread);
        // leaked test threads are reclaimed at process exit.
    }
}
