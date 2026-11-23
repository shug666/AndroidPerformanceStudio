//! IPv4/TCP/UDP packet parsing, header accessors and checksum computation.
//!
//! Ported from `relay/{IPv4Packet,IPv4Header,TCPHeader,UDPHeader,Net,Binary}.kt`
//! (spec §2.7). Field offsets, flag bits and the checksum algorithms are part of
//! the gnirehtet wire contract and stay byte-identical. Inbound packets are never
//! validated (the relay recomputes checksums on responses only — RelayLoopbackTest
//! contract); the UDP checksum is always written as 0 (legal for IPv4).
//!
//! Kotlin models headers as views over shared ByteBuffers; this port parses the
//! header fields into cached values with write-through into an owned `Vec<u8>`
//! raw packet, trading the zero-copy preference for ownership safety (the copies
//! are bounded by the 64 KiB IP length field).

use std::net::Ipv4Addr;

/// `IPv4Packet.MAX_PACKET_LENGTH = 1 shl 16`: the length field is 16 bits.
pub const MAX_PACKET_LENGTH: usize = 1 << 16;

/// `10.0.2.2` is the device-side alias for the relay host and is rewritten to
/// loopback (AbstractConnection.LOCALHOST_FORWARD, spec §9).
pub const LOCALHOST_FORWARD: u32 = 0x0a000202;

/// TCP flag bits, low 9 bits of bytes 12..13 (TCPHeader.kt; 9 bits incl. NS/ECE/CWR).
pub const FLAG_FIN: u16 = 1 << 0;
pub const FLAG_SYN: u16 = 1 << 1;
pub const FLAG_RST: u16 = 1 << 2;
pub const FLAG_PSH: u16 = 1 << 3;
pub const FLAG_ACK: u16 = 1 << 4;
pub const FLAG_URG: u16 = 1 << 5;

/// `IPv4Header.Protocol`: TCP(6), UDP(17), OTHER(-1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Protocol {
    Tcp,
    Udp,
    Other,
}

impl Protocol {
    pub fn from_number(number: u8) -> Self {
        match number {
            6 => Self::Tcp,
            17 => Self::Udp,
            _ => Self::Other,
        }
    }

    pub fn number(self) -> u8 {
        match self {
            Self::Tcp => 6,
            Self::Udp => 17,
            Self::Other => 0xff,
        }
    }
}

pub fn be16(buf: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes([buf[offset], buf[offset + 1]])
}

pub fn be32(buf: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([buf[offset], buf[offset + 1], buf[offset + 2], buf[offset + 3]])
}

fn put_be16(buf: &mut [u8], offset: usize, value: u16) {
    buf[offset..offset + 2].copy_from_slice(&value.to_be_bytes());
}

fn put_be32(buf: &mut [u8], offset: usize, value: u32) {
    buf[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
}

/// Dotted quad for an IPv4 address held as a 32-bit host-order int (Net.kt).
pub fn dotted_ip(ip: u32) -> String {
    Ipv4Addr::from(ip).to_string()
}

/// `IPv4Header.readLength`: the 16-bit big-endian total length at offset 2..3,
/// or `None` when fewer than 4 bytes are buffered (IPv4PacketBuffer contract).
pub fn read_total_length(buf: &[u8]) -> Option<usize> {
    if buf.len() < 4 {
        return None;
    }
    Some(be16(buf, 2) as usize)
}

/// Sum of 16-bit big-endian words; an odd trailing byte is shifted left by 8
/// (Kotlin checksum loops, TCPHeader.kt payload handling).
fn sum_words(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for pair in &mut chunks {
        sum += u16::from_be_bytes([pair[0], pair[1]]) as u32;
    }
    if let [odd] = chunks.remainder() {
        sum += ((*odd as u16) << 8) as u32;
    }
    sum
}

/// Fold high 16-bit carries and take the one's complement (Kotlin fold loop).
fn fold_invert(sum: u32) -> u16 {
    let mut sum = sum;
    while sum & 0xffff_0000 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

/// Cached TCP header fields (TCPHeader.kt init block).
#[derive(Debug, Clone, Copy)]
pub struct TcpInfo {
    pub source_port: u16,
    pub destination_port: u16,
    /// `(dataOffset and 0xf000) shr 10`, i.e. dataOffset << 2.
    pub header_length: usize,
    pub sequence_number: i32,
    pub acknowledgement_number: i32,
    /// 9 flag bits (`dataOffsetAndFlags and 0x1ff`).
    pub flags: u16,
    pub window: u16,
}

/// Cached UDP header fields (UDPHeader.kt: 8 bytes fixed).
#[derive(Debug, Clone, Copy)]
pub struct UdpInfo {
    pub source_port: u16,
    pub destination_port: u16,
}

#[derive(Debug, Clone, Copy)]
pub enum TransportInfo {
    Tcp(TcpInfo),
    Udp(UdpInfo),
}

impl TransportInfo {
    pub fn header_length(&self) -> usize {
        match self {
            Self::Tcp(info) => info.header_length,
            Self::Udp(_) => 8, // UDP_HEADER_LENGTH
        }
    }

    pub fn source_port(&self) -> u16 {
        match self {
            Self::Tcp(info) => info.source_port,
            Self::Udp(info) => info.source_port,
        }
    }

    pub fn destination_port(&self) -> u16 {
        match self {
            Self::Tcp(info) => info.destination_port,
            Self::Udp(info) => info.destination_port,
        }
    }
}

/// An IPv4 packet owning its raw bytes, with parsed header values. Mirrors
/// `IPv4Packet.kt`: `is_valid()` is `transportHeader != null` (version 4 and
/// TCP/UDP only); inbound checksums are not verified.
#[derive(Debug, Clone)]
pub struct IPv4Packet {
    raw: Vec<u8>,
    header_length: usize,
    total_length: usize,
    protocol: Protocol,
    source: u32,
    destination: u32,
    transport: Option<TransportInfo>,
}

impl IPv4Packet {
    /// Parse an owned packet buffer. Returns `None` when the buffer is too short
    /// for an IPv4 header (the JVM assert "at least 20 bytes" upstream).
    pub fn parse(raw: Vec<u8>) -> Option<Self> {
        if raw.len() < 20 {
            return None;
        }
        let version = raw[0] >> 4;
        let ihl = (raw[0] & 0x0f) as usize;
        // Non-IPv4 packets (e.g. IPv6 with IHL 0) are clamped so the reads below
        // never overflow; unsupported packets are rejected via is_supported.
        let header_length = (ihl << 2).max(20);
        let total_length = be16(&raw, 2) as usize;
        let protocol = Protocol::from_number(raw[9]);
        let source = be32(&raw, 12);
        let destination = be32(&raw, 16);
        // Kotlin: raw.limit(totalLength) then transport parsed from the slice.
        // A packet is only usable when the transport header fits the buffer.
        let transport = if version == 4 && protocol != Protocol::Other {
            let offset = header_length;
            match protocol {
                Protocol::Tcp if offset + 20 <= raw.len() => {
                    let data_offset_and_flags = be16(&raw, offset + 12);
                    Some(TransportInfo::Tcp(TcpInfo {
                        source_port: be16(&raw, offset),
                        destination_port: be16(&raw, offset + 2),
                        header_length: ((data_offset_and_flags & 0xf000) >> 10) as usize,
                        sequence_number: be32(&raw, offset + 4) as i32,
                        acknowledgement_number: be32(&raw, offset + 8) as i32,
                        flags: data_offset_and_flags & 0x1ff,
                        window: be16(&raw, offset + 14),
                    }))
                }
                Protocol::Udp if offset + 8 <= raw.len() => Some(TransportInfo::Udp(UdpInfo {
                    source_port: be16(&raw, offset),
                    destination_port: be16(&raw, offset + 2),
                })),
                _ => None,
            }
        } else {
            None
        };
        Some(Self { raw, header_length, total_length, protocol, source, destination, transport })
    }

    pub fn raw_bytes(&self) -> &[u8] {
        &self.raw
    }

    pub fn into_raw(self) -> Vec<u8> {
        self.raw
    }

    /// `getRawLength()`: the packet length (raw covers exactly one packet here).
    pub fn raw_len(&self) -> usize {
        self.raw.len()
    }

    pub fn header_length(&self) -> usize {
        self.header_length
    }

    pub fn total_length(&self) -> usize {
        self.total_length
    }

    pub fn protocol(&self) -> Protocol {
        self.protocol
    }

    pub fn source(&self) -> u32 {
        self.source
    }

    pub fn destination(&self) -> u32 {
        self.destination
    }

    pub fn transport(&self) -> Option<TransportInfo> {
        self.transport
    }

    pub fn is_valid(&self) -> bool {
        self.transport.is_some()
    }

    fn transport_offset(&self) -> usize {
        self.header_length
    }

    pub fn transport_header_length(&self) -> usize {
        self.transport.map(|t| t.header_length()).unwrap_or(0)
    }

    /// Payload offset = IPv4 header length + transport header length.
    fn payload_offset(&self) -> usize {
        self.header_length + self.transport_header_length()
    }

    pub fn payload(&self) -> &[u8] {
        let start = self.payload_offset().min(self.raw.len());
        &self.raw[start..]
    }

    /// `getPayloadLength()`: raw limit minus header lengths.
    pub fn payload_len(&self) -> usize {
        self.raw_len().saturating_sub(self.payload_offset())
    }

    pub fn set_source(&mut self, source: u32) {
        self.source = source;
        put_be32(&mut self.raw, 12, source);
    }

    pub fn set_destination(&mut self, destination: u32) {
        self.destination = destination;
        put_be32(&mut self.raw, 16, destination);
    }

    pub fn swap_source_and_destination(&mut self) {
        let tmp = self.source;
        self.set_source(self.destination);
        self.set_destination(tmp);
        let transport_offset = self.transport_offset();
        if let Some(info) = self.transport.as_mut() {
            match info {
                TransportInfo::Tcp(tcp) => {
                    let port = tcp.source_port;
                    tcp.source_port = tcp.destination_port;
                    tcp.destination_port = port;
                    put_be16(&mut self.raw, transport_offset, tcp.source_port);
                    put_be16(&mut self.raw, transport_offset + 2, tcp.destination_port);
                }
                TransportInfo::Udp(udp) => {
                    let port = udp.source_port;
                    udp.source_port = udp.destination_port;
                    udp.destination_port = port;
                    put_be16(&mut self.raw, transport_offset, udp.source_port);
                    put_be16(&mut self.raw, transport_offset + 2, udp.destination_port);
                }
            }
        }
    }

    /// Write the 16-bit total length back at offset 2..3 (IPv4Header.totalLength setter).
    pub fn set_total_length(&mut self, total_length: usize) {
        self.total_length = total_length;
        put_be16(&mut self.raw, 2, total_length as u16);
    }

    // ---- TCP accessors / mutators (TCPHeader.kt) ----

    fn tcp_info(&self) -> Option<&TcpInfo> {
        match self.transport.as_ref()? {
            TransportInfo::Tcp(info) => Some(info),
            _ => None,
        }
    }

    pub fn tcp(&self) -> Option<TcpInfo> {
        self.tcp_info().copied()
    }

    pub fn tcp_flags(&self) -> u16 {
        self.tcp_info().map(|t| t.flags).unwrap_or(0)
    }

    pub fn tcp_is_fin(&self) -> bool {
        self.tcp_flags() & FLAG_FIN != 0
    }
    pub fn tcp_is_syn(&self) -> bool {
        self.tcp_flags() & FLAG_SYN != 0
    }
    pub fn tcp_is_rst(&self) -> bool {
        self.tcp_flags() & FLAG_RST != 0
    }
    pub fn tcp_is_ack(&self) -> bool {
        self.tcp_flags() & FLAG_ACK != 0
    }

    pub fn tcp_sequence_number(&self) -> i32 {
        self.tcp_info().map(|t| t.sequence_number).unwrap_or(0)
    }

    pub fn tcp_acknowledgement_number(&self) -> i32 {
        self.tcp_info().map(|t| t.acknowledgement_number).unwrap_or(0)
    }

    pub fn tcp_window(&self) -> u16 {
        self.tcp_info().map(|t| t.window).unwrap_or(0)
    }

    pub fn tcp_set_sequence_number(&mut self, value: i32) {
        let offset = self.transport_offset() + 4;
        if let Some(TransportInfo::Tcp(tcp)) = self.transport.as_mut() {
            tcp.sequence_number = value;
            put_be32(&mut self.raw, offset, value as u32);
        }
    }

    pub fn tcp_set_acknowledgement_number(&mut self, value: i32) {
        let offset = self.transport_offset() + 8;
        if let Some(TransportInfo::Tcp(tcp)) = self.transport.as_mut() {
            tcp.acknowledgement_number = value;
            put_be32(&mut self.raw, offset, value as u32);
        }
    }

    /// `setFlags`: keep the data-offset high bits, replace the 9 flag bits.
    pub fn tcp_set_flags(&mut self, flags: u16) {
        let offset = self.transport_offset() + 12;
        if let Some(TransportInfo::Tcp(tcp)) = self.transport.as_mut() {
            tcp.flags = flags & 0x1ff;
            let current = be16(&self.raw, offset);
            put_be16(&mut self.raw, offset, (current & 0xfe00) | (flags & 0x1ff));
        }
    }

    // ---- UDP accessors / mutators (UDPHeader.kt) ----

    pub fn udp_source_port(&self) -> u16 {
        match self.transport {
            Some(TransportInfo::Udp(info)) => info.source_port,
            _ => 0,
        }
    }

    pub fn udp_destination_port(&self) -> u16 {
        match self.transport {
            Some(TransportInfo::Udp(info)) => info.destination_port,
            _ => 0,
        }
    }

    /// `setPayloadLength`: write `8 + len` at UDP offset 4.
    pub fn udp_set_payload_length(&mut self, payload_len: usize) {
        let offset = self.transport_offset() + 4;
        if matches!(self.transport, Some(TransportInfo::Udp(_))) {
            put_be16(&mut self.raw, offset, (8 + payload_len) as u16);
        }
    }

    /// `computeChecksums()`: IPv4 header checksum plus transport checksum.
    pub fn compute_checksums(&mut self) {
        self.compute_ip_checksum();
        if self.transport.is_some() {
            match self.protocol {
                Protocol::Tcp => self.compute_tcp_checksum(),
                Protocol::Udp => {
                    // UDP over IPv4: a zero checksum means "not validated" (legal).
                    let offset = self.transport_offset() + 6;
                    put_be16(&mut self.raw, offset, 0);
                }
                Protocol::Other => {}
            }
        }
    }

    /// IPv4Header.computeChecksum: zero the field, sum headerLength/2 words,
    /// fold and invert, write at offset 10..11.
    fn compute_ip_checksum(&mut self) {
        put_be16(&mut self.raw, 10, 0);
        let end = self.header_length.min(self.raw.len());
        let sum = sum_words(&self.raw[..end]);
        put_be16(&mut self.raw, 10, fold_invert(sum));
    }

    /// TCPHeader.computeChecksum: RFC793 pseudo-header (src, dst split into 16-bit
    /// words, protocol 6, TCP total length) + header + payload; odd payload byte
    /// is shifted left by 8. The TCP total length is `ip.total - ip.headerLen`.
    fn compute_tcp_checksum(&mut self) {
        let tcp_offset = self.transport_offset();
        // Kotlin reads the cached ip.totalLength minus ip.headerLength.
        let length = self.total_length.saturating_sub(self.header_length);
        let mut sum = (self.source >> 16) as u32
            + (self.source & 0xffff) as u32
            + (self.destination >> 16) as u32
            + (self.destination & 0xffff) as u32
            + Protocol::Tcp.number() as u32
            + length as u32;
        put_be16(&mut self.raw, tcp_offset + 16, 0); // reset checksum field
        let end = (tcp_offset + length).min(self.raw.len());
        sum += sum_words(&self.raw[tcp_offset..end]);
        put_be16(&mut self.raw, tcp_offset + 16, fold_invert(sum));
    }

    /// Test-side validation: recompute the RFC793 sum including the stored
    /// checksum field; a correct packet folds to the 0xffff complement.
    pub fn tcp_checksum_valid(&self) -> bool {
        if !matches!(self.transport, Some(TransportInfo::Tcp(_))) {
            return false;
        }
        let length = self.total_length.saturating_sub(self.header_length);
        let pseudo = (self.source >> 16) as u32
            + (self.source & 0xffff) as u32
            + (self.destination >> 16) as u32
            + (self.destination & 0xffff) as u32
            + Protocol::Tcp.number() as u32
            + length as u32;
        let end = (self.transport_offset() + length).min(self.raw.len());
        fold_invert(sum_words(&self.raw[self.transport_offset()..end]) + pseudo) == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Byte-for-byte the RelayLoopbackTest packet builder (spec §8.1): inbound
    /// checksums are zero and must still be accepted by the relay.
    pub(super) fn build_tcp_packet(
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
        buf.push(0x40 | 5); // version 4, IHL 5
        buf.push(0); // DSCP
        buf.extend_from_slice(&(total_length as u16).to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes()); // id
        buf.extend_from_slice(&0x4000u16.to_be_bytes()); // DF
        buf.push(64); // TTL
        buf.push(6); // TCP
        buf.extend_from_slice(&0u16.to_be_bytes()); // checksum: inbound is not validated
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

    /// Verify an IPv4 header checksum: re-summing the header with the checksum
    /// included must fold to the 0xffff complement.
    fn verify_ip_checksum(bytes: &[u8]) -> bool {
        fold_invert(sum_words(bytes)) == 0
    }

    /// Verify a TCP checksum by re-computing the RFC793 pseudo-header sum plus
    /// the raw packet sum (which now contains the written checksum).
    fn verify_tcp_checksum(packet: &IPv4Packet) -> bool {
        let length = packet.total_length().saturating_sub(packet.header_length()) as u32;
        let pseudo = (packet.source() >> 16) as u32
            + (packet.source() & 0xffff) as u32
            + (packet.destination() >> 16) as u32
            + (packet.destination() & 0xffff) as u32
            + 6
            + length;
        fold_invert(sum_words(packet.raw_bytes()) + pseudo) == 0
    }

    #[test]
    fn non_ipv4_packet_is_unsupported() {
        // IPv6 version nibble, 40-byte packet (IPv4PacketTest §8.2).
        let mut buf = vec![0u8; 40];
        buf[0] = 0x60;
        let packet = IPv4Packet::parse(buf).unwrap();
        assert!(!packet.is_valid());
    }

    #[test]
    fn unsupported_protocol_is_marked_invalid() {
        // IPv4 + ICMP (protocol 1) → transportHeader == null (§8.2).
        let mut buf = Vec::new();
        buf.push(0x45);
        buf.push(0);
        buf.extend_from_slice(&20u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.push(64);
        buf.push(1); // ICMP
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0x0a000002u32.to_be_bytes());
        buf.extend_from_slice(&0x08080808u32.to_be_bytes());
        let packet = IPv4Packet::parse(buf).unwrap();
        assert!(!packet.is_valid());
    }

    #[test]
    fn tcp_packet_exposes_headers() {
        // Exact field assertions from IPv4PacketTest §8.2.
        let raw = build_tcp_packet(0x0a000002, 12345, 0x08080808, 443, 1000, 2000, FLAG_SYN, &[]);
        let packet = IPv4Packet::parse(raw).unwrap();
        assert!(packet.is_valid());
        assert_eq!(packet.header_length(), 20);
        assert_eq!(packet.source(), 0x0a000002);
        assert_eq!(packet.destination(), 0x08080808);
        let tcp = packet.tcp().unwrap();
        assert_eq!(tcp.flags & FLAG_SYN, FLAG_SYN);
        assert_eq!(tcp.source_port, 12345);
        assert_eq!(tcp.destination_port, 443);
        assert_eq!(tcp.sequence_number, 1000);
        assert_eq!(tcp.acknowledgement_number, 2000);
        assert_eq!(tcp.window, 8192);
        assert_eq!(packet.payload_len(), 0);
    }

    #[test]
    fn tcp_checksum_round_trip() {
        // Compute, then verify the RFC793 sum including the written checksum.
        let raw = build_tcp_packet(0x0a000002, 12345, 0x08080808, 443, 1000, 2000, FLAG_SYN, &[]);
        let mut packet = IPv4Packet::parse(raw).unwrap();
        packet.compute_checksums();
        let ip_ok = verify_ip_checksum(&packet.raw_bytes()[..packet.header_length()]);
        assert!(ip_ok, "ip checksum must verify");
        assert!(verify_tcp_checksum(&packet), "tcp checksum must verify");
        // Mutating the acknowledgement number invalidates the checksum again.
        packet.tcp_set_acknowledgement_number(3000);
        assert!(!verify_tcp_checksum(&packet));
    }

    #[test]
    fn odd_payload_checksum_round_trip() {
        // Payload with an odd trailing byte exercises the <<8 padding branch.
        let payload = b"hello relay";
        let raw = build_tcp_packet(
            0x0a000002, 12345, 0x08080808, 443, 1000, 2000,
            FLAG_ACK | FLAG_PSH, payload,
        );
        let mut packet = IPv4Packet::parse(raw).unwrap();
        packet.compute_checksums();
        assert!(verify_tcp_checksum(&packet));
        assert_eq!(packet.payload(), payload);
        assert_eq!(packet.payload_len(), payload.len());
    }

    #[test]
    fn udp_checksum_is_written_zero() {
        // IPv4Header + UDP header + 4 payload bytes.
        let mut buf = Vec::new();
        buf.push(0x45);
        buf.push(0);
        buf.extend_from_slice(&32u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.push(64);
        buf.push(17);
        buf.extend_from_slice(&0u16.to_be_bytes());
        buf.extend_from_slice(&0x0a000002u32.to_be_bytes());
        buf.extend_from_slice(&0x08080808u32.to_be_bytes());
        buf.extend_from_slice(&53u16.to_be_bytes()); // src port
        buf.extend_from_slice(&4242u16.to_be_bytes()); // dst port
        buf.extend_from_slice(&12u16.to_be_bytes()); // length
        buf.extend_from_slice(&0u16.to_be_bytes()); // checksum
        buf.extend_from_slice(&[1, 2, 3, 4]);
        let mut packet = IPv4Packet::parse(buf).unwrap();
        assert!(packet.is_valid());
        packet.compute_checksums();
        assert_eq!(be16(packet.raw_bytes(), 20 + 6), 0);
        assert!(verify_ip_checksum(&packet.raw_bytes()[..packet.header_length()]));
        assert_eq!(packet.payload(), &[1, 2, 3, 4]);
        assert_eq!(packet.udp_source_port(), 53);
    }

    #[test]
    fn tcp_set_flags_keeps_data_offset_bits() {
        let raw = build_tcp_packet(1, 1, 2, 2, 0, 0, FLAG_SYN, &[]);
        let mut packet = IPv4Packet::parse(raw).unwrap();
        packet.tcp_set_flags(FLAG_ACK | FLAG_PSH);
        let tcp = packet.tcp().unwrap();
        assert_eq!(tcp.flags, FLAG_ACK | FLAG_PSH);
        assert_eq!(tcp.header_length, 20);
        assert_eq!(be16(packet.raw_bytes(), 20 + 12) >> 12, 5);
    }

    #[test]
    fn read_total_length_follows_offset_two() {
        assert_eq!(read_total_length(&[0x45, 0, 0x00, 0x28]), Some(40));
        assert_eq!(read_total_length(&[0x45, 0, 0x00]), None);
    }

    #[test]
    fn dotted_ip_matches_net_to_string() {
        assert_eq!(dotted_ip(0x0a000202), "10.0.2.2");
        assert_eq!(dotted_ip(0x08080808), "8.8.8.8");
    }
}
