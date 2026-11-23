//! Pseudo TCP endpoint and stateless UDP NAT connections.
//!
//! Ported from `relay/{Connection,AbstractConnection,ConnectionId,TCPConnection,
//! UDPConnection,PacketSource}.kt` (spec §2.4/§2.5). The state machine, sequence
//! arithmetic (32-bit wrapping, like Kotlin's Int overflow) and the "drop
//! out-of-order packets silently, retransmission is managed by both ends" rule
//! are wire-visible behavior and must not change.
//!
//! mio mapping: Java NIO's OP_CONNECT becomes `Interest::WRITABLE` (a mio
//! non-blocking connect completes when the socket becomes writable, verified via
//! `take_error`); a zero Java interest set becomes a deregistration, since mio
//! requires at least one interest bit. Neither is observable on the wire.

use std::net::Shutdown;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::SystemTime;

use mio::event::Event;
use mio::net::TcpStream;
use mio::{Interest, Registry, Token};

use crate::buffer::{DatagramBuffer, StreamBuffer};
use crate::dns::{DnsInterceptor, DNS_PORT};
use crate::packet::{IPv4Packet, Protocol, FLAG_ACK, FLAG_FIN, FLAG_PSH, FLAG_RST, FLAG_SYN, LOCALHOST_FORWARD};
use crate::packetizer::{resize_packet, Packetizer};

/// Same value as GnirehtetService.MTU in the client (TCPConnection.kt).
pub const MTU: usize = 0x4000;
/// 20 bytes of IP header + 20 bytes of TCP header.
pub const MAX_PAYLOAD_SIZE: usize = MTU - 20 - 20;
/// UDPConnection.IDLE_TIMEOUT = 2 * 60 * 1000 ms (spec §9).
pub const UDP_IDLE_TIMEOUT_MS: u64 = 2 * 60 * 1000;

/// Wall-clock milliseconds for idle accounting (see lib.rs `now_ms`).
fn now_ms() -> u64 {
    crate::now_ms()
}

/// `Random.nextInt()` replacement: the dependency set is restricted to aps-adb +
/// mio, so the initial sequence number mixes wall-clock nanoseconds, the process
/// id and a counter. Only used to seed the relay-side sequence space.
fn random_i32() -> i32 {
    static COUNTER: AtomicU32 = AtomicU32::new(0x9e3779b9);
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.subsec_nanos() ^ d.as_secs() as u32)
        .unwrap_or(0);
    (nanos ^ std::process::id() ^ COUNTER.fetch_add(0x9e3779b9, Ordering::Relaxed)) as i32
}

/// Connection table key over the five-tuple (ConnectionId.kt). It never appears
/// on the wire; the fork-only event `connectionId` is [`ConnectionId::hash_code`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnectionId {
    pub protocol: Protocol,
    pub source_ip: u32,
    pub source_port: u16,
    pub dest_ip: u32,
    pub dest_port: u16,
}

impl ConnectionId {
    pub fn from_packet(packet: &IPv4Packet) -> Self {
        let (source_port, dest_port) = packet
            .transport()
            .map(|t| (t.source_port(), t.destination_port()))
            .unwrap_or((0, 0));
        Self {
            protocol: packet.protocol(),
            source_ip: packet.source(),
            source_port,
            dest_ip: packet.destination(),
            dest_port,
        }
    }

    /// ConnectionId.hashCode(): the documented 31-chained mix. Ports are mixed
    /// sign-extended to 32 bits (Kotlin `Short.toInt()`); the protocol term uses
    /// stable constants because the JVM enum hash was identity-based.
    pub fn hash_code(&self) -> i64 {
        let protocol_hash = match self.protocol {
            Protocol::Tcp => 0,
            Protocol::Udp => 1,
            Protocol::Other => 2,
        } as i32;
        let mut result = protocol_hash;
        result = result.wrapping_mul(31).wrapping_add(self.source_ip as i32);
        result = result.wrapping_mul(31).wrapping_add((self.source_port as i16) as i32);
        result = result.wrapping_mul(31).wrapping_add(self.dest_ip as i32);
        result = result.wrapping_mul(31).wrapping_add((self.dest_port as i16) as i32);
        result as i64
    }
}

impl std::fmt::Display for ConnectionId {
    /// `protocol + " " + Net.toString(src, srcPort) + " -> " + Net.toString(dst, dstPort)`.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{:?} {}:{} -> {}:{}",
            self.protocol,
            crate::packet::dotted_ip(self.source_ip),
            self.source_port,
            crate::packet::dotted_ip(self.dest_ip),
            self.dest_port
        )
    }
}

/// Shared per-connection byte statistics (AbstractConnection.kt fork addition).
pub struct ConnectionBase {
    pub id: ConnectionId,
    pub token: Token,
    bytes_sent: u64,
    bytes_received: u64,
    pub closed: bool,
}

impl ConnectionBase {
    pub fn new(id: ConnectionId, token: Token) -> Self {
        Self { id, token, bytes_sent: 0, bytes_received: 0, closed: false }
    }

    /// Record bytes forwarded from device to network (processSend path).
    pub fn add_sent(&mut self, bytes: u64) {
        if bytes > 0 {
            self.bytes_sent += bytes;
        }
    }

    /// Record bytes forwarded from network to device (processReceive path).
    pub fn add_received(&mut self, bytes: u64) {
        if bytes > 0 {
            self.bytes_received += bytes;
        }
    }

    pub fn bytes_sent_to_network(&self) -> u64 {
        self.bytes_sent
    }

    pub fn bytes_received_from_network(&self) -> u64 {
        self.bytes_received
    }
}

/// The slice of the owning tunnel client that connections may touch. Ownership
/// lives in `tunnel::Client`; connections only ever see these disjoint parts.
pub struct ClientCtx<'a> {
    pub tunnel: &'a mut TcpStream,
    pub network_to_client: &'a mut StreamBuffer,
    /// Packet sources waiting for the client's 1 MiB buffer to drain (Token of
    /// the owning connection; resolved by the client, see tunnel.rs).
    pub pending: &'a mut Vec<Token>,
    pub registry: &'a Registry,
}

impl ClientCtx<'_> {
    /// `Client.sendToClient`: `false` when the 1 MiB tunnel buffer cannot hold
    /// the packet (the caller decides whether to drop or pend).
    pub fn send_to_client(&mut self, packet: &IPv4Packet) -> bool {
        if self.network_to_client.remaining() < packet.raw_len() {
            return false;
        }
        self.network_to_client.read_from(packet.raw_bytes());
        true
    }

    /// `Client.consume` fallback: park the source for retry after the next send.
    pub fn add_pending(&mut self, token: Token) {
        if !self.pending.contains(&token) {
            self.pending.push(token);
        }
    }
}

/// `Connection` + `PacketSource` (Kotlin interfaces) over the mio event loop.
pub trait Connection: Send {
    fn id(&self) -> &ConnectionId;
    fn token(&self) -> Token;
    /// Device → real network packet (`sendToNetwork`).
    fn send_to_network(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>);
    /// mio readiness for the connection's own socket (Java selection handler).
    fn handle_event(&mut self, event: &Event, ctx: &mut ClientCtx<'_>);
    fn is_expired(&self, now_ms: u64) -> bool;
    fn bytes_sent_to_network(&self) -> u64;
    fn bytes_received_from_network(&self) -> u64;
    fn is_closed(&self) -> bool;
    /// Close the socket and mark closed; removal + Closed event is orchestrated
    /// by the router sweep (mirrors AbstractConnection.close → router.remove).
    fn disconnect(&mut self);
    /// PacketSource::get — only TCPConnection pends packets for the client.
    fn source_get(&mut self) -> Option<IPv4Packet> {
        None
    }
    /// PacketSource::next — the registry is required to refresh interests.
    fn source_next(&mut self, registry: &Registry) {
        let _ = registry;
    }
    /// Re-evaluate readiness interests (TCPConnection.updateInterests).
    fn refresh_interests(&mut self, registry: &Registry);
}

/// Simplified RFC793 state machine (TCPConnection.State).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcpState {
    SynSent,
    SynReceived,
    Established,
    CloseWait,
    LastAck,
    Closing,
    FinWait1,
    FinWait2,
}

impl TcpState {
    pub fn is_connected(self) -> bool {
        self != Self::SynSent && self != Self::SynReceived
    }

    pub fn is_closed(self) -> bool {
        matches!(self, Self::FinWait1 | Self::FinWait2 | Self::Closing | Self::LastAck)
    }
}

/// Pseudo TCP endpoint between the device and the real network (TCPConnection.kt).
pub struct TCPConnection {
    base: ConnectionBase,
    stream: TcpStream,
    /// Device → real network, 4 × 65536 = 256 KiB (spec §9).
    client_to_network: StreamBuffer,
    packetizer: Packetizer,
    /// Single pending packet for the client (shared buffer upstream; owned copy
    /// here, patched by `source_get` exactly like the Kotlin in-place patch).
    packet_for_client: Option<IPv4Packet>,
    /// `null` means "no first packet seen yet".
    state: Option<TcpState>,
    syn_sequence_number: i32,
    /// Our → device direction sequence number.
    sequence_number: i32,
    /// Our ack of the device direction.
    acknowledgement_number: i32,
    their_acknowledgement_number: i32,
    /// `None` = no FIN sent yet.
    fin_sequence_number: Option<i32>,
    fin_received: bool,
    client_window: i32,
    connect_pending: bool,
    registered: bool,
    interests: Option<Interest>,
}

impl TCPConnection {
    /// Opens a non-blocking socket to the (rewritten) destination and registers
    /// it for the connect completion (Kotlin: OP_CONNECT).
    pub fn new(
        id: ConnectionId,
        token: Token,
        packet: &IPv4Packet,
        registry: &Registry,
    ) -> std::io::Result<Self> {
        let packetizer = Packetizer::new_response_template(packet, true); // no TCP options
        let mut stream = TcpStream::connect(rewritten_destination(&id))?;
        registry.register(&mut stream, token, Interest::WRITABLE)?;
        Ok(Self {
            base: ConnectionBase::new(id, token),
            stream,
            client_to_network: StreamBuffer::new(4 * crate::packet::MAX_PACKET_LENGTH),
            packetizer,
            packet_for_client: None,
            state: None,
            syn_sequence_number: 0,
            sequence_number: 0,
            acknowledgement_number: 0,
            their_acknowledgement_number: 0,
            fin_sequence_number: None,
            fin_received: false,
            client_window: 0,
            connect_pending: true,
            registered: true,
            interests: Some(Interest::WRITABLE),
        })
    }

    fn remaining_client_window(&self) -> i32 {
        // Kotlin signed overflow wraps (well-defined); mirror with wrapping ops.
        let remaining = self
            .their_acknowledgement_number
            .wrapping_add(self.client_window)
            .wrapping_sub(self.sequence_number);
        if remaining < 0 || remaining > self.client_window {
            // our sequence number is outside their window
            return 0;
        }
        remaining
    }

    fn update_headers(&mut self, flags: u16) {
        self.packetizer.set_tcp_header(flags, self.sequence_number, self.acknowledgement_number);
    }

    fn send_empty_packet(&mut self, ctx: &mut ClientCtx<'_>, flags: u16) {
        self.update_headers(flags);
        let packet = self.packetizer.packetize_empty_payload();
        // dropped when the client buffer is full (AbstractConnection.sendToClient)
        let _ = ctx.send_to_client(&packet);
    }

    fn close(&mut self) {
        if self.base.closed {
            return;
        }
        self.base.closed = true;
        let _ = self.stream.shutdown(Shutdown::Both);
        self.packet_for_client = None;
    }

    fn reset_connection(&mut self, ctx: &mut ClientCtx<'_>) {
        self.state = None;
        self.send_empty_packet(ctx, FLAG_RST);
        self.close();
    }

    fn handle_packet(&mut self, packet: &IPv4Packet, ctx: &mut ClientCtx<'_>) {
        if self.state.is_none() {
            self.handle_first_packet(packet, ctx);
            return;
        }
        if packet.tcp_is_syn() {
            // the client always initiates the connection; any later SYN is a duplicate
            self.handle_duplicate_syn(packet, ctx);
            return;
        }
        let packet_sequence_number = packet.tcp_sequence_number();
        let expected = self
            .acknowledgement_number
            .wrapping_add(self.client_to_network.size() as i32);
        if packet_sequence_number != expected {
            // ignore already-received or out-of-order packets: retransmission is
            // managed by both ends — dropped silently, no response (spec §2.4)
            return;
        }
        // window/ack are updated before the RST check (TCPConnection.kt order)
        self.client_window = packet.tcp_window() as i32;
        self.their_acknowledgement_number = packet.tcp_acknowledgement_number();
        if packet.tcp_is_rst() {
            self.close();
            return;
        }
        if packet.tcp_is_ack() {
            self.handle_ack(packet);
        }
        if packet.tcp_is_fin() {
            self.handle_fin(ctx);
        }
        if let Some(fin_seq) = self.fin_sequence_number {
            if packet.tcp_acknowledgement_number() == fin_seq.wrapping_add(1) {
                self.handle_fin_ack();
            }
        }
    }

    fn handle_first_packet(&mut self, packet: &IPv4Packet, ctx: &mut ClientCtx<'_>) {
        if !packet.tcp_is_syn() {
            // make a RST in the window client (sequence = their ack)
            self.sequence_number = packet.tcp_acknowledgement_number();
            self.reset_connection(ctx);
            return;
        }
        let their_sequence_number = packet.tcp_sequence_number();
        self.acknowledgement_number = their_sequence_number.wrapping_add(1);
        self.syn_sequence_number = their_sequence_number;
        self.sequence_number = random_i32();
        self.client_window = packet.tcp_window() as i32;
        self.state = Some(TcpState::SynSent);
    }

    fn handle_duplicate_syn(&mut self, packet: &IPv4Packet, ctx: &mut ClientCtx<'_>) {
        let their_sequence_number = packet.tcp_sequence_number();
        if self.state == Some(TcpState::SynSent) {
            // finishConnect() not called yet: accept as if it were the first SYN
            self.syn_sequence_number = their_sequence_number;
            self.acknowledgement_number = their_sequence_number.wrapping_add(1);
        } else if their_sequence_number != self.syn_sequence_number {
            // duplicate SYN with a different sequence number
            self.reset_connection(ctx);
        }
    }

    fn handle_ack(&mut self, packet: &IPv4Packet) {
        if self.state == Some(TcpState::SynReceived) {
            self.state = Some(TcpState::Established);
            return;
        }
        let payload_length = packet.payload_len();
        if payload_length == 0 {
            return; // no data to transmit
        }
        if self.client_to_network.remaining() < payload_length {
            return; // not enough space, dropping packet (no NACK)
        }
        // data will be ACKed once written to the network socket
        self.client_to_network.read_from(packet.payload());
    }

    fn handle_fin(&mut self, ctx: &mut ClientCtx<'_>) {
        self.fin_received = true;
        if self.client_to_network.is_empty() {
            self.do_handle_fin(ctx);
        }
        // otherwise the FIN is processed once clientToNetwork drains (processSend)
    }

    fn do_handle_fin(&mut self, ctx: &mut ClientCtx<'_>) {
        // received FIN counts for 1 byte
        self.acknowledgement_number = self.acknowledgement_number.wrapping_add(1);
        match self.state {
            Some(TcpState::Established) => {
                self.send_empty_packet(ctx, FLAG_FIN | FLAG_ACK);
                self.fin_sequence_number = Some(self.sequence_number);
                self.sequence_number = self.sequence_number.wrapping_add(1);
                // do not wait for the real network connection: switch immediately
                // to LAST_ACK, bypassing CLOSE_WAIT (TCPConnection.kt)
                self.state = Some(TcpState::LastAck);
            }
            Some(TcpState::FinWait1) => {
                self.send_empty_packet(ctx, FLAG_ACK);
                self.state = Some(TcpState::Closing);
            }
            Some(TcpState::FinWait2) => {
                self.send_empty_packet(ctx, FLAG_ACK);
                self.close();
            }
            _ => {} // received FIN while in an unexpected state
        }
    }

    fn handle_fin_ack(&mut self) {
        match self.state {
            Some(TcpState::LastAck) | Some(TcpState::Closing) => self.close(),
            Some(TcpState::FinWait1) => self.state = Some(TcpState::FinWait2),
            _ => {} // received FIN ACK while in an unexpected state
        }
    }

    fn process_connect(&mut self, ctx: &mut ClientCtx<'_>) {
        if !self.finish_connect() {
            self.close();
            return;
        }
        self.state = Some(TcpState::SynReceived);
        self.send_empty_packet(ctx, FLAG_SYN | FLAG_ACK);
        // SYN counts for 1 byte
        self.sequence_number = self.sequence_number.wrapping_add(1);
    }

    /// mio has no `finishConnect`; a completed connect shows `take_error == None`
    /// and a usable `peer_addr` (mio TcpStream::connect contract).
    fn finish_connect(&mut self) -> bool {
        match self.stream.take_error() {
            Ok(None) => self.stream.peer_addr().is_ok(),
            _ => false,
        }
    }

    fn process_receive(&mut self, ctx: &mut ClientCtx<'_>) {
        let remaining_client_window = self.remaining_client_window();
        if remaining_client_window <= 0 || self.packet_for_client.is_some() {
            // mayRead() gate; upstream asserts this cannot happen
            return;
        }
        let max_payload_size = remaining_client_window.min(MAX_PAYLOAD_SIZE as i32) as usize;
        self.update_headers(FLAG_ACK | FLAG_PSH);
        match self.packetizer.packetize_from_stream(&mut self.stream, max_payload_size) {
            Ok(None) => self.eof(ctx),
            Ok(Some(packet)) => {
                self.base.add_received(packet.payload_len() as u64);
                self.packet_for_client = Some(packet);
                self.consume_self(ctx);
            }
            Err(_) => self.reset_connection(ctx),
        }
    }

    fn process_send(&mut self, ctx: &mut ClientCtx<'_>) {
        // Kotlin: clientToNetwork.writeTo(channel) — the connection's OWN socket
        // to the real network (ctx.tunnel is the device-side tunnel, only used
        // for empty control packets via sendToClient).
        // Java NIO: would-block surfaces as a 0 write (StreamBuffer maps it), and
        // w == 0 closes the connection — same as the Kotlin `else { close() }`.
        let written = match self.client_to_network.write_to(&mut self.stream) {
            Ok(w) => w,
            Err(_) => {
                self.reset_connection(ctx);
                return;
            }
        };
        if written > 0 {
            self.base.add_sent(written as u64);
            self.acknowledgement_number = self.acknowledgement_number.wrapping_add(written as i32);
            if self.fin_received && self.client_to_network.is_empty() {
                self.do_handle_fin(ctx);
            } else {
                self.send_empty_packet(ctx, FLAG_ACK);
            }
        } else {
            self.close();
        }
    }

    fn eof(&mut self, ctx: &mut ClientCtx<'_>) {
        self.send_empty_packet(ctx, FLAG_FIN | FLAG_ACK);
        self.fin_sequence_number = Some(self.sequence_number);
        // FIN counts for 1 byte
        self.sequence_number = self.sequence_number.wrapping_add(1);
        self.state = Some(match self.state.unwrap_or(TcpState::Established) {
            TcpState::CloseWait => TcpState::LastAck,
            _ => TcpState::FinWait1,
        });
    }

    fn consume_self(&mut self, ctx: &mut ClientCtx<'_>) {
        // Client.consume(this): deliver now or park until the buffer drains.
        match self.source_get() {
            Some(packet) if ctx.send_to_client(&packet) => self.source_next(ctx.registry),
            _ => ctx.add_pending(self.base.token),
        }
    }

    fn may_read(&self) -> bool {
        let Some(state) = self.state else { return false };
        if !state.is_connected() || state.is_closed() {
            return false;
        }
        if self.packet_for_client.is_some() {
            return false; // a packet is already pending
        }
        self.remaining_client_window() > 0
    }

    fn may_write(&self) -> bool {
        !self.client_to_network.is_empty()
    }

    fn may_connect(&self) -> bool {
        self.state == Some(TcpState::SynSent)
    }

    fn update_interests(&mut self, registry: &Registry) {
        if self.base.closed {
            return;
        }
        let mut interest: Option<Interest> = None;
        if self.may_read() {
            interest = Some(Interest::READABLE);
        }
        if self.may_write() {
            interest = Some(interest.map_or(Interest::WRITABLE, |i| i.add(Interest::WRITABLE)));
        }
        // Java OP_CONNECT ≙ mio WRITABLE while the connect is in flight.
        if self.connect_pending && self.may_connect() {
            interest = Some(interest.map_or(Interest::WRITABLE, |i| i.add(Interest::WRITABLE)));
        }
        if interest != self.interests {
            self.interests = interest;
            let _result = match (interest, self.registered) {
                (Some(i), true) => registry.reregister(&mut self.stream, self.base.token, i),
                (Some(i), false) => {
                    self.registered = true;
                    registry.register(&mut self.stream, self.base.token, i)
                }
                // A zero Java interest set keeps the key silent; mio requires at
                // least one bit, so deregister instead (no events wanted).
                (None, true) => {
                    self.registered = false;
                    registry.deregister(&mut self.stream)
                }
                (None, false) => Ok(()),
            };
        }
    }
}

impl Connection for TCPConnection {
    fn id(&self) -> &ConnectionId {
        &self.base.id
    }

    fn token(&self) -> Token {
        self.base.token
    }

    fn send_to_network(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>) {
        self.handle_packet(&packet, ctx);
        self.update_interests(ctx.registry);
    }

    fn handle_event(&mut self, event: &Event, ctx: &mut ClientCtx<'_>) {
        if self.base.closed {
            return;
        }
        let was_connecting = self.connect_pending;
        if was_connecting && (event.is_writable() || event.is_write_closed()) {
            self.connect_pending = false;
            self.process_connect(ctx);
            if self.base.closed {
                return;
            }
            // Kotlin's OP_CONNECT-only key delivered no read/write on this event.
            self.update_interests(ctx.registry);
            return;
        }
        if event.is_readable() || event.is_read_closed() {
            self.process_receive(ctx);
            if self.base.closed {
                return;
            }
        }
        if event.is_writable() || event.is_write_closed() {
            self.process_send(ctx);
            if self.base.closed {
                return;
            }
        }
        self.update_interests(ctx.registry);
    }

    fn is_expired(&self, _now_ms: u64) -> bool {
        false // no external timeout expiration for TCP
    }

    fn bytes_sent_to_network(&self) -> u64 {
        self.base.bytes_sent_to_network()
    }

    fn bytes_received_from_network(&self) -> u64 {
        self.base.bytes_received_from_network()
    }

    fn is_closed(&self) -> bool {
        self.base.closed
    }

    fn disconnect(&mut self) {
        self.close();
    }

    /// PacketSource::get: clone the pending packet with the TCP ack field patched
    /// to the latest acknowledgement number and checksums recomputed
    /// (TCPConnection.get / updateAcknowledgementNumber).
    fn source_get(&mut self) -> Option<IPv4Packet> {
        if self.packet_for_client.is_none() {
            return None;
        }
        let mut packet = self.packet_for_client.clone().unwrap();
        packet.tcp_set_acknowledgement_number(self.acknowledgement_number);
        packet.compute_checksums();
        Some(packet)
    }

    fn source_next(&mut self, registry: &Registry) {
        let Some(packet) = self.packet_for_client.take() else { return };
        self.sequence_number = self.sequence_number.wrapping_add(packet.payload_len() as i32);
        self.update_interests(registry);
    }

    fn refresh_interests(&mut self, registry: &Registry) {
        self.update_interests(registry);
    }
}

/// Stateless UDP NAT endpoint (UDPConnection.kt). DNS responses on port 53 flow
/// through the interceptor when one is wired (spec §3.2 — the fork's dormant
/// hook, connected in this port).
pub struct UDPConnection {
    base: ConnectionBase,
    stream: mio::net::UdpSocket,
    /// Device → real network, 4 × 65536 = 256 KiB (spec §9).
    client_to_network: DatagramBuffer,
    packetizer: Packetizer,
    idle_since_ms: u64,
    registered: bool,
    interests: Option<Interest>,
    dns_interceptor: Option<Arc<DnsInterceptor>>,
}

impl UDPConnection {
    pub fn new(
        id: ConnectionId,
        token: Token,
        packet: &IPv4Packet,
        registry: &Registry,
        dns_interceptor: Option<Arc<DnsInterceptor>>,
    ) -> std::io::Result<Self> {
        let packetizer = Packetizer::new_response_template(packet, false);
        let mut stream = mio::net::UdpSocket::bind(SocketAddr::new(
            Ipv4Addr::UNSPECIFIED.into(),
            0,
        ))?;
        stream.connect(rewritten_destination(&id))?;
        registry.register(&mut stream, token, Interest::READABLE)?;
        Ok(Self {
            base: ConnectionBase::new(id, token),
            stream,
            client_to_network: DatagramBuffer::new(4 * crate::packet::MAX_PACKET_LENGTH),
            packetizer,
            idle_since_ms: now_ms(), // touch()
            registered: true,
            interests: Some(Interest::READABLE),
            dns_interceptor,
        })
    }

    fn close(&mut self) {
        if self.base.closed {
            return;
        }
        self.base.closed = true;
        // UDP sockets have no shutdown(); the fd closes when the router drops it.
    }

    fn touch(&mut self) {
        self.idle_since_ms = now_ms();
    }

    fn push_to_client(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>) {
        let Some(interceptor) = self.dns_interceptor.clone() else {
            self.send_raw(packet, ctx);
            return;
        };
        if self.base.id.dest_port != DNS_PORT {
            self.send_raw(packet, ctx);
            return;
        }
        let payload = packet.payload().to_vec();
        if !interceptor.is_response(&payload) {
            self.send_raw(packet, ctx);
            return;
        }
        match interceptor.on_response(&payload) {
            None => self.send_raw(packet, ctx),
            // DNS payload was rewritten (AAAA stripped): rebuild the packet.
            Some(rewritten) => {
                let rebuilt = resize_packet(&packet, &rewritten);
                self.send_raw(rebuilt, ctx);
            }
        }
    }

    fn send_raw(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>) {
        let _ = ctx.send_to_client(&packet); // cannot send to client, dropping packet
    }

    fn update_interests(&mut self, registry: &Registry) {
        if self.base.closed {
            return;
        }
        let mut interest = Interest::READABLE; // always want to read
        if !self.client_to_network.is_empty() {
            interest = interest.add(Interest::WRITABLE);
        }
        if Some(interest) != self.interests {
            self.interests = Some(interest);
            let result = if self.registered {
                registry.reregister(&mut self.stream, self.base.token, interest)
            } else {
                self.registered = true;
                registry.register(&mut self.stream, self.base.token, interest)
            };
            let _ = result;
        }
    }
}

impl Connection for UDPConnection {
    fn id(&self) -> &ConnectionId {
        &self.base.id
    }

    fn token(&self) -> Token {
        self.base.token
    }

    fn send_to_network(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>) {
        self.base.add_sent(packet.payload_len() as u64);
        if !self.client_to_network.read_from(packet.payload()) {
            // cannot send to network, dropping packet
            return;
        }
        self.update_interests(ctx.registry);
    }

    fn handle_event(&mut self, event: &Event, ctx: &mut ClientCtx<'_>) {
        if self.base.closed {
            return;
        }
        self.touch(); // refreshed on every selector callback (UDPConnection.kt)
        if event.is_readable() || event.is_read_closed() {
            match self.packetizer.packetize_datagram(&mut self.stream) {
                Ok(Some(packet)) => {
                    self.base.add_received(packet.payload_len() as u64);
                    self.push_to_client(packet, ctx);
                }
                // UDP has no EOF; a would-block-with-no-datagram race would have
                // produced an empty payload packet upstream — treat as nothing.
                _ => {
                    self.close();
                    return;
                }
            }
            if self.base.closed {
                return;
            }
        }
        if event.is_writable() || event.is_write_closed() {
            match self.client_to_network.write_to(&mut self.stream) {
                Ok(true) => {}
                // partial write: tail already advanced, remainder dropped (spec §2.8)
                Ok(false) => self.close(),
                Err(_) => self.close(),
            }
            if self.base.closed {
                return;
            }
        }
        self.update_interests(ctx.registry);
    }

    fn is_expired(&self, now_ms: u64) -> bool {
        now_ms >= self.idle_since_ms + UDP_IDLE_TIMEOUT_MS
    }

    fn bytes_sent_to_network(&self) -> u64 {
        self.base.bytes_sent_to_network()
    }

    fn bytes_received_from_network(&self) -> u64 {
        self.base.bytes_received_from_network()
    }

    fn is_closed(&self) -> bool {
        self.base.closed
    }

    fn disconnect(&mut self) {
        self.close();
    }

    fn refresh_interests(&mut self, registry: &Registry) {
        self.update_interests(registry);
    }
}

/// `AbstractConnection.getRewrittenDestination`: `10.0.2.2` is forwarded to
/// localhost, everything else keeps its address; the port is unchanged.
fn rewritten_destination(id: &ConnectionId) -> SocketAddr {
    let ip = if id.dest_ip == LOCALHOST_FORWARD {
        Ipv4Addr::LOCALHOST
    } else {
        Ipv4Addr::from(id.dest_ip)
    };
    SocketAddr::new(ip.into(), id.dest_port)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::FLAG_SYN;

    fn syn_packet() -> IPv4Packet {
        let mut raw = Vec::new();
        raw.push(0x45);
        raw.push(0);
        raw.extend_from_slice(&40u16.to_be_bytes());
        raw.extend_from_slice(&0u16.to_be_bytes());
        raw.extend_from_slice(&0x4000u16.to_be_bytes());
        raw.push(64);
        raw.push(6);
        raw.extend_from_slice(&0u16.to_be_bytes());
        raw.extend_from_slice(&[10, 0, 0, 2]);
        raw.extend_from_slice(&[10, 0, 2, 2]);
        raw.extend_from_slice(&12345u16.to_be_bytes());
        raw.extend_from_slice(&8443u16.to_be_bytes());
        raw.extend_from_slice(&1000u32.to_be_bytes());
        raw.extend_from_slice(&0u32.to_be_bytes());
        raw.extend_from_slice(&(((5u16) << 12) | FLAG_SYN).to_be_bytes());
        raw.extend_from_slice(&8192u16.to_be_bytes());
        raw.extend_from_slice(&0u16.to_be_bytes());
        raw.extend_from_slice(&0u16.to_be_bytes());
        IPv4Packet::parse(raw).unwrap()
    }

    #[test]
    fn connection_id_matches_documented_hash_chain() {
        let id = ConnectionId {
            protocol: Protocol::Tcp,
            source_ip: 0x0a000002,
            source_port: 12345,
            dest_ip: 0x08080808,
            dest_port: 443,
        };
        let expected: i32 = 0i32
            .wrapping_mul(31)
            .wrapping_add(0x0a000002i32)
            .wrapping_mul(31)
            .wrapping_add(12345i32)
            .wrapping_mul(31)
            .wrapping_add(0x08080808i32)
            .wrapping_mul(31)
            .wrapping_add(443i32);
        assert_eq!(id.hash_code(), expected as i64);
    }

    #[test]
    fn rewritten_destination_maps_alias_to_loopback() {
        let id = ConnectionId {
            protocol: Protocol::Tcp,
            source_ip: 0x0a000002,
            source_port: 12345,
            dest_ip: LOCALHOST_FORWARD,
            dest_port: 8443,
        };
        assert_eq!(rewritten_destination(&id).ip().to_string(), "127.0.0.1");
        assert_eq!(rewritten_destination(&id).port(), 8443);
    }

    #[test]
    fn tcp_state_helpers_match_kotlin() {
        assert!(!TcpState::SynSent.is_connected());
        assert!(!TcpState::SynReceived.is_connected());
        assert!(TcpState::Established.is_connected());
        assert!(TcpState::LastAck.is_closed());
        assert!(TcpState::FinWait2.is_closed());
        assert!(!TcpState::Established.is_closed());
        let _ = syn_packet();
    }
}
