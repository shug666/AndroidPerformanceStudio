//! Packet extraction from the tunnel stream and L5→L3 response packetization.
//!
//! Ported from `relay/IPv4PacketBuffer.kt` and `relay/Packetizer.kt` (spec §1.3,
//! §2.6). The tunnel carries raw IPv4 packets back to back with no framing: the
//! device→host direction is split by the 16-bit big-endian total length at offset
//! 2..3, and any TCP segmentation/concatenation of that stream is handled
//! correctly. The host→device direction rebuilds packets from a copied header
//! template (request IPv4 + transport headers with source/destination swapped,
//! TCP options shrunk to a 20-byte header).

use std::io::{ErrorKind, Read};

use crate::packet::{IPv4Packet, MAX_PACKET_LENGTH};

/// Accumulates tunnel bytes and extracts whole IPv4 packets
/// (IPv4PacketBuffer.kt, single 64 KiB buffer upstream).
pub struct IPv4PacketBuffer {
    buffer: Vec<u8>,
}

impl IPv4PacketBuffer {
    pub fn new() -> Self {
        Self { buffer: Vec::with_capacity(MAX_PACKET_LENGTH) }
    }

    /// Read more bytes from the tunnel. `Ok(None)` is end-of-stream (Java read
    /// returning -1); `Ok(Some(0))` covers would-block (Java returning 0).
    pub fn read_from_stream(&mut self, stream: &mut impl Read) -> std::io::Result<Option<usize>> {
        if self.buffer.len() >= MAX_PACKET_LENGTH {
            return Ok(Some(0)); // buffer full: single packets never exceed 64 KiB
        }
        let spare = MAX_PACKET_LENGTH - self.buffer.len();
        let mut chunk = vec![0u8; spare];
        match stream.read(&mut chunk) {
            Ok(0) => Ok(None),
            Ok(n) => {
                self.buffer.extend_from_slice(&chunk[..n]);
                Ok(Some(n))
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => Ok(Some(0)),
            Err(error) => Err(error),
        }
    }

    /// Front packet length: the big-endian total length at offset 2..3 once at
    /// least that many bytes are buffered and the whole packet has arrived.
    /// Lengths below the 20-byte IPv4 header never yield a packet (upstream
    /// sliced such garbage into a malformed packet; this port skips it).
    fn front_length(&self) -> Option<usize> {
        let total = crate::packet::read_total_length(&self.buffer)?;
        if total < 20 || total > self.buffer.len() {
            return None;
        }
        Some(total)
    }

    /// The next complete packet at the front of the buffer, if any.
    /// `next()` must be called after consuming it.
    pub fn as_ipv4_packet(&self) -> Option<IPv4Packet> {
        IPv4Packet::parse(self.buffer[..self.front_length()?].to_vec())
    }

    /// Consume the front packet, compacting the remainder (Kotlin `compact()`).
    pub fn next(&mut self) {
        if let Some(length) = self.front_length() {
            self.buffer.drain(..length);
        }
    }
}

impl Default for IPv4PacketBuffer {
    fn default() -> Self {
        Self::new()
    }
}

/// Converts level 5 to level 3 by appending the correct IPv4 and transport
/// headers (Packetizer.kt): the request headers are copied as the response
/// template, sources/destinations swapped by the caller's connection setup.
pub struct Packetizer {
    template: IPv4Packet,
}

impl Packetizer {
    /// Build the response template from the first request packet. With
    /// `shrink_tcp` (TCP only) the TCP header is copied 20 bytes long with the
    /// data offset reset to 5, i.e. responses never carry TCP options
    /// (TCPConnection.shrinkOptions / Packetizer.init).
    pub fn new_response_template(packet: &IPv4Packet, shrink_tcp: bool) -> Packetizer {
        let ip_length = packet.header_length();
        let transport_length = match packet.protocol() {
            crate::packet::Protocol::Tcp if shrink_tcp => 20,
            _ => packet.transport_header_length(),
        };
        let mut raw = Vec::with_capacity(ip_length + transport_length);
        raw.extend_from_slice(&packet.raw_bytes()[..ip_length]);
        raw.extend_from_slice(&packet.raw_bytes()[ip_length..ip_length + transport_length]);
        if shrink_tcp {
            // setDataOffset(5): keep the low 12 bits, set the high nibble to 5.
            let offset = ip_length + 12;
            let current = crate::packet::be16(&raw, offset);
            raw[offset] = (((current & 0x0fff) | (5 << 12)) >> 8) as u8;
            raw[offset + 1] = ((current & 0x0fff) | (5 << 12)) as u8;
        }
        let mut template = IPv4Packet::parse(raw).expect("response template is a valid IPv4 packet");
        template.swap_source_and_destination();
        Self { template }
    }

    /// Patch the response TCP header (flags/seq/ack) before packetizing
    /// (TCPConnection.updateHeaders).
    pub fn set_tcp_header(&mut self, flags: u16, sequence_number: i32, acknowledgement_number: i32) {
        self.template.tcp_set_flags(flags);
        self.template.tcp_set_sequence_number(sequence_number);
        self.template.tcp_set_acknowledgement_number(acknowledgement_number);
    }

    /// Build a packet over an empty payload (TCPConnection empty ACK/SYN/FIN/RST).
    pub fn packetize_empty_payload(&mut self) -> IPv4Packet {
        self.inflate(&[])
    }

    /// Read at most `max_chunk_size` payload bytes from the stream and packetize
    /// them. `Ok(None)` is end-of-stream (Java read -1); a would-block read maps
    /// to Java's `0` return and yields an empty-payload packet.
    pub fn packetize_from_stream(
        &mut self,
        stream: &mut impl Read,
        max_chunk_size: usize,
    ) -> std::io::Result<Option<IPv4Packet>> {
        let mut payload = vec![0u8; max_chunk_size];
        match stream.read(&mut payload) {
            Ok(0) => Ok(None),
            Ok(n) => {
                payload.truncate(n);
                Ok(Some(self.inflate(&payload)))
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => Ok(Some(self.inflate(&[]))),
            Err(error) => Err(error),
        }
    }

    /// Read exactly one datagram from the connected UDP socket (no-arg
    /// `packetize(channel)` overload, full 64 KiB capacity).
    pub fn packetize_datagram(
        &mut self,
        socket: &mut mio::net::UdpSocket,
    ) -> std::io::Result<Option<IPv4Packet>> {
        let mut payload = vec![0u8; MAX_PACKET_LENGTH];
        match socket.recv(&mut payload) {
            Ok(n) => {
                payload.truncate(n);
                Ok(Some(self.inflate(&payload)))
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => Ok(Some(self.inflate(&[]))),
            Err(error) => Err(error),
        }
    }

    /// `inflate()`: header template + payload, IPv4 total length and UDP length
    /// written back, checksums recomputed (UDP checksum stays 0).
    pub fn inflate(&mut self, payload: &[u8]) -> IPv4Packet {
        let mut raw = self.template.raw_bytes().to_vec();
        raw.extend_from_slice(payload);
        let mut packet = IPv4Packet::parse(raw).expect("packetized buffer is a valid IPv4 packet");
        let total_length = packet.header_length() + packet.transport_header_length() + payload.len();
        packet.set_total_length(total_length);
        if matches!(packet.protocol(), crate::packet::Protocol::Udp) {
            packet.udp_set_payload_length(payload.len());
        }
        packet.compute_checksums();
        packet
    }
}

/// Rebuild a UDP packet whose payload was replaced (UDPConnection.resizePacket):
/// copy the old IPv4 + UDP headers and the new payload, update the IPv4 total
/// length and UDP length, recompute checksums.
pub fn resize_packet(packet: &IPv4Packet, new_payload: &[u8]) -> IPv4Packet {
    let ip_length = packet.header_length();
    let udp_length = packet.transport_header_length();
    let mut raw = Vec::with_capacity(ip_length + udp_length + new_payload.len());
    raw.extend_from_slice(&packet.raw_bytes()[..ip_length]);
    raw.extend_from_slice(&packet.raw_bytes()[ip_length..ip_length + udp_length]);
    raw.extend_from_slice(new_payload);
    let mut rebuilt = IPv4Packet::parse(raw).expect("rebuilt packet is a valid IPv4 packet");
    rebuilt.set_total_length(ip_length + udp_length + new_payload.len());
    rebuilt.udp_set_payload_length(new_payload.len());
    rebuilt.compute_checksums();
    rebuilt
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::{FLAG_ACK, FLAG_PSH};

    /// Reuse the loopback packet builder shape for template tests.
    fn tcp_request(payload: &[u8]) -> Vec<u8> {
        let total_length = 20 + 20 + payload.len();
        let mut buf = Vec::new();
        buf.push(0x45);
        buf.push(0);
        buf.extend_from_slice(&(total_length as u16).to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0x4000u16.to_be_bytes());
        buf.push(64);
        buf.push(6);
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&[10, 0, 0, 2]); // 10.0.0.2 (device)
        buf.extend_from_slice(&[10, 0, 2, 2]); // 10.0.2.2 (relay alias)
        buf.extend_from_slice(&12345u16.to_be_bytes());
        buf.extend_from_slice(&443u16.to_be_bytes());
        buf.extend_from_slice(&1000u32.to_be_bytes());
        buf.extend_from_slice(&2000u32.to_be_bytes());
        buf.extend_from_slice(&(((5u16) << 12) | FLAG_ACK | FLAG_PSH).to_be_bytes());
        buf.extend_from_slice(&8192u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(payload);
        buf
    }

    #[test]
    fn packet_buffer_handles_split_and_concatenated_packets() {
        // Spec §1.3: arbitrary TCP segmentation/concatenation is handled.
        let mut buffer = IPv4PacketBuffer::new();
        let first = tcp_request(&[]);
        let second = tcp_request(b"xy");
        let mut both = first.clone();
        both.extend_from_slice(&second);
        // Feed one byte at a time: no packet until the length field + body arrive.
        for byte in &both[..10] {
            let mut cursor = std::io::Cursor::new(vec![*byte]);
            buffer.read_from_stream(&mut cursor).unwrap();
            assert!(buffer.as_ipv4_packet().is_none());
            buffer.next();
        }
        let mut cursor = std::io::Cursor::new(both[10..].to_vec());
        buffer.read_from_stream(&mut cursor).unwrap();
        let packet = buffer.as_ipv4_packet().expect("first packet complete");
        assert_eq!(packet.raw_len(), 40);
        buffer.next();
        let packet2 = buffer.as_ipv4_packet().expect("second packet complete");
        assert_eq!(packet2.payload(), b"xy");
        buffer.next();
        assert!(buffer.as_ipv4_packet().is_none());
    }

    #[test]
    fn response_template_swaps_addresses_and_shrinks_options() {
        let request = IPv4Packet::parse(tcp_request(&[])).unwrap();
        let mut packetizer = Packetizer::new_response_template(&request, true);
        packetizer.set_tcp_header(FLAG_ACK | FLAG_PSH, 5000, 6000);
        let response = packetizer.packetize_empty_payload();
        // Response direction: relay alias port 443 -> device port 12345.
        assert_eq!(crate::packet::dotted_ip(response.source()), "10.0.2.2");
        assert_eq!(crate::packet::dotted_ip(response.destination()), "10.0.0.2");
        let tcp = response.tcp().unwrap();
        assert_eq!(tcp.source_port, 443);
        assert_eq!(tcp.destination_port, 12345);
        assert_eq!(tcp.sequence_number, 5000);
        assert_eq!(tcp.acknowledgement_number, 6000);
        assert_eq!(tcp.header_length, 20, "responses carry no TCP options");
        assert_eq!(response.raw_len(), 40);
        assert_eq!(response.total_length(), 40);
    }

    #[test]
    fn inflate_sets_lengths_and_udp_payload_length() {
        // UDP template: 8-byte header, payload length written back at inflate.
        let mut buf = Vec::new();
        buf.push(0x45);
        buf.push(0);
        buf.extend_from_slice(&28u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.push(64);
        buf.push(17);
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&[10, 0, 0, 2]);
        buf.extend_from_slice(&[10, 0, 2, 2]);
        buf.extend_from_slice(&53u16.to_be_bytes());
        buf.extend_from_slice(&4242u16.to_be_bytes());
        buf.extend_from_slice(&8u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        let request = IPv4Packet::parse(buf).unwrap();
        let mut packetizer = Packetizer::new_response_template(&request, false);
        let response = packetizer.inflate(&[1, 2, 3, 4, 5]);
        assert_eq!(response.raw_len(), 20 + 8 + 5);
        assert_eq!(response.total_length(), 33);
        assert_eq!(crate::packet::be16(response.raw_bytes(), 20 + 4), 8 + 5);
        assert_eq!(response.payload(), &[1, 2, 3, 4, 5]);
    }
}
