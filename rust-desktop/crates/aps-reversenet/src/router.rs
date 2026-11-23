//! Connection table and NAT dispatch.
//!
//! Ported from `relay/Router.kt` (spec §2.3): a small linear-scanned table (few
//! connections per client make this faster than a hash map), five-tuple lookup,
//! connection creation on miss, and the fork's observability hooks. Two spec-
//! mandated corrections over the Kotlin fork are implemented here:
//! * `cleanExpiredConnections` emits `onConnectionClosed` (the fork dropped the
//!   event on that path, spec §2.3 note);
//! * UDP connections receive the wired `DnsInterceptor` (spec §0.1/§3.2).

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use mio::Registry;

use crate::conn::{ClientCtx, Connection, ConnectionId, TCPConnection, UDPConnection};
use crate::dns::DnsInterceptor;
use crate::packet::IPv4Packet;
use crate::tunnel::RelayListener;

/// Monotonic mio token allocator. Tokens 0 and 1 are reserved for the listening
/// socket and the stop waker; values are never reused, so a stale registration
/// can never collide with a live one after a connection is dropped.
pub struct TokenAllocator {
    next: AtomicUsize,
}

impl TokenAllocator {
    pub fn starting_at(start: usize) -> Self {
        Self { next: AtomicUsize::new(start) }
    }

    pub fn next_token(&self) -> mio::Token {
        mio::Token(self.next.fetch_add(1, Ordering::Relaxed))
    }
}

pub struct Router {
    pub(crate) connections: Vec<Box<dyn Connection>>,
    pub(crate) registry: Registry,
    tokens: Arc<TokenAllocator>,
    dns_interceptor: Option<Arc<DnsInterceptor>>,
    listener: Arc<dyn RelayListener>,
}

impl Router {
    pub fn new(
        registry: Registry,
        tokens: Arc<TokenAllocator>,
        dns_interceptor: Option<Arc<DnsInterceptor>>,
        listener: Arc<dyn RelayListener>,
    ) -> Self {
        Self { connections: Vec::new(), registry, tokens, dns_interceptor, listener }
    }

    pub fn connection_count(&self) -> usize {
        self.connections.len()
    }

    pub fn index_of_token(&self, token: mio::Token) -> Option<usize> {
        self.connections.iter().position(|c| c.token() == token)
    }

    /// Device → network packet: validate, look up (or create) the connection and
    /// hand the packet over (Router.sendToNetwork).
    pub fn send_to_network(&mut self, packet: IPv4Packet, ctx: &mut ClientCtx<'_>) {
        if !packet.is_valid() {
            // IPv6 / non-TCP-UDP protocol
            self.listener.on_packet_dropped(&packet);
            return;
        }
        let id = ConnectionId::from_packet(&packet);
        if let Some(index) = self.find(&id) {
            self.connections[index].send_to_network(packet, ctx);
        } else {
            match self.create_connection(&id, &packet) {
                Ok(connection) => {
                    self.listener.on_connection_opened(connection.as_ref(), connection.id());
                    let mut connection = connection;
                    connection.send_to_network(packet, ctx);
                    self.connections.push(connection);
                }
                // IOException → onPacketDropped (Router.sendToNetwork catch)
                Err(_) => self.listener.on_packet_dropped(&packet),
            }
        }
        self.sweep_closed();
    }

    fn find(&self, id: &ConnectionId) -> Option<usize> {
        self.connections.iter().position(|c| c.id() == id)
    }

    fn create_connection(
        &self,
        id: &ConnectionId,
        packet: &IPv4Packet,
    ) -> std::io::Result<Box<dyn Connection>> {
        let token = self.tokens.next_token();
        let connection: Box<dyn Connection> = match id.protocol {
            crate::packet::Protocol::Udp => Box::new(UDPConnection::new(
                id.clone(),
                token,
                packet,
                &self.registry,
                self.dns_interceptor.clone(),
            )?),
            crate::packet::Protocol::Tcp => {
                Box::new(TCPConnection::new(id.clone(), token, packet, &self.registry)?)
            }
            // unreachable behind the is_valid() gate; kept explicit
            crate::packet::Protocol::Other => {
                return Err(std::io::Error::other(format!("Unsupported protocol: {id:?}")))
            }
        };
        Ok(connection)
    }

    /// Drop connections whose sockets have closed themselves; each removal emits
    /// onConnectionClosed with the final byte counters (AbstractConnection.close
    /// → router.remove → listener).
    pub fn sweep_closed(&mut self) {
        let mut index = 0;
        while index < self.connections.len() {
            if self.connections[index].is_closed() {
                let connection = self.connections.remove(index);
                self.listener.on_connection_closed(connection.as_ref(), connection.id());
            } else {
                index += 1;
            }
        }
    }

    /// Reverse-ordered expiry sweep. **Spec correction:** this path now emits
    /// onConnectionClosed, which the Kotlin fork forgot (spec §2.3).
    pub fn clean_expired_connections(&mut self, now_ms: u64) {
        let mut expired = Vec::new();
        for (index, connection) in self.connections.iter().enumerate() {
            if connection.is_expired(now_ms) {
                expired.push(index);
            }
        }
        for index in expired.into_iter().rev() {
            let mut connection = self.connections.remove(index);
            self.listener.on_connection_closed(connection.as_ref(), connection.id());
            connection.disconnect();
        }
    }

    /// Disconnect everything when the tunnel client closes — no Closed events,
    /// matching Router.clear (spec §2.3).
    pub fn clear(&mut self) {
        for mut connection in self.connections.drain(..) {
            connection.disconnect();
        }
    }
}
