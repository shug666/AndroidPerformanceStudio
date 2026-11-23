//! Tunnel server: listening socket, per-client tunnel state machines.
//!
//! Ported from `relay/{TunnelServer,Client,RelayListener,CloseListener,
//! SelectionHandler}.kt` (spec §1.1, §2.2) plus the fork's observation contract
//! (`RelayListener`, spec §2.9). The tunnel speaks the gnirehtet wire protocol:
//! the host first sends a 4-byte big-endian client id (per-process counter from
//! 0), then raw IPv4 packets flow both ways with no framing.
//!
//! Listening socket: the fork sets `reuseAddress = true` ("sessions restart
//! frequently; allow immediate rebinding") and binds loopback only. This port
//! binds with `mio::net::TcpListener::bind`, which sets SO_REUSEADDR on Unix
//! platforms itself (mio-1.2.2 listener.rs sets it before bind on non-Windows),
//! so the fork's behavior is preserved without libc. std's TcpListener would
//! NOT set it — that is why the mio-native bind is used instead of a std bind +
//! `from_std` conversion.
//!
//! Event dispatch mirrors the Kotlin selection handlers: client key order is
//! writable→send, readable→receive, updateInterests; connection key order is
//! connect→read→write, updateInterests.

use std::io::Write;
use std::net::Shutdown;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;

use mio::event::Event;
use mio::net::{TcpListener, TcpStream};
use mio::{Interest, Registry, Token};

use crate::conn::{ClientCtx, Connection};
use crate::packet::{IPv4Packet, MAX_PACKET_LENGTH};
use crate::packetizer::IPv4PacketBuffer;
use crate::router::{Router, TokenAllocator};

/// Tunnel-level observation hooks (RelayListener.kt). Implementations MUST NOT
/// block: emit into bounded queues only (spec §2.9). Default bodies provide the
/// `RelayListener.NONE` no-op set; `on_connection_bytes` is this port's fork
/// addition for the periodic Bytes event.
pub trait RelayListener: Send + Sync {
    fn on_client_connected(&self, _client_id: i32) {}
    fn on_client_disconnected(&self, _client_id: i32) {}
    fn on_connection_opened(&self, _connection: &dyn Connection, _id: &crate::conn::ConnectionId) {}
    fn on_connection_closed(&self, _connection: &dyn Connection, _id: &crate::conn::ConnectionId) {}
    fn on_packet_dropped(&self, _packet: &IPv4Packet) {}
    fn on_connection_bytes(&self, _connection: &dyn Connection, _id: &crate::conn::ConnectionId) {}
}

/// RelayListener.NONE equivalent.
pub struct NoopListener;

impl RelayListener for NoopListener {}

/// Per-process client id counter, starting at 0, never persisted (Client.kt).
static NEXT_CLIENT_ID: AtomicI32 = AtomicI32::new(0);

pub(crate) const TOKEN_LISTENER: Token = Token(0);
pub(crate) const TOKEN_WAKER: Token = Token(1);

/// Handle the connections from the clients (TunnelServer.kt).
pub struct TunnelServer {
    listener_socket: TcpListener,
    clients: Vec<Client>,
    tokens: Arc<TokenAllocator>,
    listener: Arc<dyn RelayListener>,
    dns_interceptor: Option<Arc<crate::dns::DnsInterceptor>>,
}

impl TunnelServer {
    /// Bind `127.0.0.1:port` only — never 0.0.0.0 (TunnelServer.kt, spec §1.1).
    pub fn bind(
        port: u16,
        registry: &Registry,
        tokens: Arc<TokenAllocator>,
        listener: Arc<dyn RelayListener>,
        dns_interceptor: Option<Arc<crate::dns::DnsInterceptor>>,
    ) -> std::io::Result<Self> {
        // mio TcpListener::bind sets SO_REUSEADDR on Unix (fork parity), binds and
        // listens in one call with a 128 backlog, and registers non-blocking.
        let mut listener_socket = TcpListener::bind(std::net::SocketAddr::from((
            std::net::Ipv4Addr::LOCALHOST,
            port,
        )))?;
        registry.register(&mut listener_socket, TOKEN_LISTENER, Interest::READABLE)?;
        Ok(Self { listener_socket, clients: Vec::new(), tokens, listener, dns_interceptor })
    }

    pub fn local_addr(&self) -> std::io::Result<std::net::SocketAddr> {
        self.listener_socket.local_addr()
    }

    /// OP_ACCEPT readiness: accept every pending connection (Kotlin handler).
    pub fn handle_accept(&mut self, registry: &Registry) {
        loop {
            match self.listener_socket.accept() {
                Ok((stream, _addr)) => {
                    let id = NEXT_CLIENT_ID.fetch_add(1, Ordering::Relaxed);
                    match Client::new(
                        id,
                        stream,
                        registry,
                        self.tokens.clone(),
                        self.listener.clone(),
                        self.dns_interceptor.clone(),
                    ) {
                        Ok(client) => {
                            self.clients.push(client);
                            self.listener.on_client_connected(id);
                        }
                        // register failure: cannot accept this client; the
                        // stream was consumed by the failed constructor.
                        Err(_) => {}
                    }
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                // cannot accept client (TunnelServer.kt swallows IOException)
                Err(_) => break,
            }
        }
    }

    /// Route one readiness event to the owning client (tunnel socket or one of
    /// its connection sockets); tokens are globally unique per registration.
    pub fn handle_event(&mut self, event: &Event, registry: &Registry) {
        let token = event.token();
        for index in 0..self.clients.len() {
            if self.clients[index].closed {
                continue;
            }
            if self.clients[index].tunnel_token == token {
                self.clients[index].handle_tunnel_event(event, registry);
            } else if let Some(connection_index) = self.clients[index].router.index_of_token(token) {
                self.clients[index].handle_connection_event(connection_index, event, registry);
            }
        }
        self.sweep_closed_clients();
    }

    /// Periodic maintenance (RelayEngine runLoop): emit the throttled Bytes
    /// event per live connection, then expire idle UDP connections.
    pub fn clean_up(&mut self, now_ms: u64) {
        let listener = self.listener.clone();
        for client in &mut self.clients {
            for index in 0..client.router.connection_count() {
                let connection = &client.router.connections[index];
                listener.on_connection_bytes(connection.as_ref(), connection.id());
            }
            client.router.clean_expired_connections(now_ms);
        }
    }

    /// Close the listening socket and disconnect every client (TunnelServer.close):
    /// each client fires onClientDisconnected; connection teardown stays silent
    /// (Router.clear semantics).
    pub fn close(&mut self) {
        let listener = self.listener.clone();
        while let Some(mut client) = self.clients.pop() {
            client.close();
            listener.on_client_disconnected(client.id);
        }
        // Drop closes the listening fd; accept errors swallowed upstream too.
    }

    fn sweep_closed_clients(&mut self) {
        let listener = self.listener.clone();
        let mut index = 0;
        while index < self.clients.len() {
            if self.clients[index].closed {
                let mut client = self.clients.remove(index);
                client.close(); // idempotent
                listener.on_client_disconnected(client.id);
            } else {
                index += 1;
            }
        }
    }
}

/// One device-side tunnel connection (Client.kt): id handshake first, then raw
/// IPv4 packet relay between the tunnel socket and the router's connections.
pub struct Client {
    pub(crate) id: i32,
    pub(crate) tunnel: TcpStream,
    pub(crate) tunnel_token: Token,
    /// Device → network, single 64 KiB buffer (IPv4PacketBuffer, spec §1.3).
    pub(crate) client_to_network: IPv4PacketBuffer,
    /// Network → device, 16 × 65536 = 1 MiB ring (StreamBuffer, spec §9).
    pub(crate) network_to_client: crate::buffer::StreamBuffer,
    /// Remaining bytes of the client id to send before relaying anything.
    pub(crate) pending_id: Option<([u8; 4], usize)>,
    /// Downstream sources parked because the tunnel buffer was full.
    pub(crate) pending_sources: Vec<Token>,
    pub(crate) router: Router,
    pub(crate) closed: bool,
    interests: Interest,
}

impl Client {
    /// On start we are interested only in writing: the id must go out first
    /// (Client.kt registers OP_WRITE).
    pub(crate) fn new(
        id: i32,
        mut tunnel: TcpStream,
        registry: &Registry,
        tokens: Arc<TokenAllocator>,
        listener: Arc<dyn RelayListener>,
        dns_interceptor: Option<Arc<crate::dns::DnsInterceptor>>,
    ) -> std::io::Result<Self> {
        let tunnel_token = tokens.next_token();
        registry.register(&mut tunnel, tunnel_token, Interest::WRITABLE)?;
        let router = Router::new(registry.try_clone()?, tokens, dns_interceptor, listener);
        Ok(Self {
            id,
            tunnel,
            tunnel_token,
            client_to_network: IPv4PacketBuffer::new(),
            network_to_client: crate::buffer::StreamBuffer::new(16 * MAX_PACKET_LENGTH),
            pending_id: Some((id.to_be_bytes(), 0)),
            pending_sources: Vec::new(),
            router,
            closed: false,
            interests: Interest::WRITABLE,
        })
    }

    pub(crate) fn close(&mut self) {
        if self.closed {
            return;
        }
        self.closed = true;
        let _ = self.tunnel.shutdown(Shutdown::Both);
        self.router.clear();
        // The tunnel socket drops when the server sweeps this client.
    }

    pub(crate) fn handle_tunnel_event(&mut self, event: &Event, registry: &Registry) {
        if self.closed {
            return;
        }
        // Client selection handler order: writable → send, readable → receive.
        if event.is_writable() || event.is_write_closed() {
            self.process_send();
            if self.closed {
                return;
            }
        }
        if event.is_readable() || event.is_read_closed() {
            self.process_receive();
            if self.closed {
                return;
            }
        }
        self.update_interests(registry);
    }

    pub(crate) fn handle_connection_event(&mut self, index: usize, event: &Event, registry: &Registry) {
        if self.closed {
            return;
        }
        let mut ctx = ClientCtx {
            tunnel: &mut self.tunnel,
            network_to_client: &mut self.network_to_client,
            pending: &mut self.pending_sources,
            registry,
        };
        self.router.connections[index].handle_event(event, &mut ctx);
        drop(ctx);
        self.router.sweep_closed();
        // Kotlin: Client.sendToClient → updateInterests — a connection event may
        // have queued response bytes for the tunnel, so re-evaluate the tunnel
        // interests here (the tunnel socket itself had no event).
        if !self.closed {
            self.update_interests(registry);
        }
    }

    fn process_receive(&mut self) {
        let outcome = self.client_to_network.read_from_stream(&mut self.tunnel);
        match outcome {
            Ok(None) => {
                self.close(); // end of stream
                return;
            }
            Err(_) => {
                self.close(); // IOException → close (Client.read)
                return;
            }
            Ok(Some(_)) => {}
        }
        self.push_to_network();
    }

    fn push_to_network(&mut self) {
        // asIPv4Packet → sendToNetwork → next, until the buffer is drained.
        while let Some(packet) = self.client_to_network.as_ipv4_packet() {
            let registry = self.router.registry.try_clone().expect("registry try_clone");
            let mut ctx = ClientCtx {
                tunnel: &mut self.tunnel,
                network_to_client: &mut self.network_to_client,
                pending: &mut self.pending_sources,
                registry: &registry,
            };
            self.router.send_to_network(packet, &mut ctx);
            drop(ctx);
            self.client_to_network.next();
            self.router.sweep_closed();
            if self.closed {
                return;
            }
        }
    }

    fn process_send(&mut self) {
        // Priority 1: finish the 4-byte client id (Client.sendId).
        if let Some((bytes, offset)) = self.pending_id.as_mut() {
            match self.tunnel.write(&bytes[*offset..]) {
                Ok(0) => return, // would-block: retry on the next writable event
                Ok(n) => {
                    *offset += n;
                    if *offset == bytes.len() {
                        self.pending_id = None;
                        return;
                    }
                    return; // partially written; finish before relaying
                }
                Err(_) => {
                    self.close();
                    return;
                }
            }
        }
        // Priority 2: flush the tunnel buffer (Client.write; -1/EOF → close,
        // would-block surfaces as Ok(0) and simply retries later).
        match self.network_to_client.write_to(&mut self.tunnel) {
            Ok(_) => {}
            Err(_) => {
                self.close();
                return;
            }
        }
        // Priority 3: resume parked packet sources (Client.processPending).
        self.process_pending();
    }

    fn process_pending(&mut self) {
        let registry = self.router.registry.try_clone().expect("registry try_clone");
        // Kotlin iterated the pending list removing delivered entries; every
        // removal shifts the next source into the front slot, so the front
        // element is the only candidate this port needs to look at.
        while let Some(&token) = self.pending_sources.first() {
            let Some(connection_index) = self.router.index_of_token(token) else {
                // The connection died while parked.
                self.pending_sources.remove(0);
                continue;
            };
            let packet = self.router.connections[connection_index].source_get();
            match packet {
                Some(packet) if self.try_send_to_client(&packet) => {
                    self.router.connections[connection_index].source_next(&registry);
                    self.pending_sources.remove(0);
                }
                _ => break, // client buffer full again
            }
        }
    }

    /// Inlined `Client.sendToClient` buffer check (borrow-splitting: the router
    /// is borrowed simultaneously in process_pending).
    fn try_send_to_client(&mut self, packet: &IPv4Packet) -> bool {
        if self.network_to_client.remaining() < packet.raw_len() {
            return false;
        }
        self.network_to_client.read_from(packet.raw_bytes());
        true
    }

    fn update_interests(&mut self, registry: &Registry) {
        // We always want to read; add write while the id or the tunnel buffer is
        // pending. (The Kotlin updateInterests dropped WRITABLE mid-id-write; a
        // 4-byte write always completed on adb tunnels, but this port keeps
        // WRITABLE until the id is delivered to avoid that latent stall.)
        let mut interest = Interest::READABLE;
        if self.pending_id.is_some() || !self.network_to_client.is_empty() {
            interest = interest.add(Interest::WRITABLE);
        }
        if interest != self.interests {
            self.interests = interest;
            let _ = registry.reregister(&mut self.tunnel, self.tunnel_token, interest);
        }
    }
}
