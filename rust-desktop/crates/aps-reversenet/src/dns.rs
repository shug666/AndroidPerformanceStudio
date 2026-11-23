//! DNS tap: response parsing and the AAAA-stripping interceptor.
//!
//! Ported from `dns/{DnsMessage,DnsInterceptor}.kt` (spec §3). The module never
//! generates DNS answers — it only taps responses, emits domain→IP events and,
//! in isolated mode, rewrites the payload to drop AAAA records so the device
//! never learns IPv6 addresses.
//!
//! Wiring (spec §0/§3.2 — dormant in the Kotlin fork, connected here):
//! `SessionConfig.isolation_mode → DnsInterceptor(mode) → Router → UDPConnection
//! → pushToClient` (dstPort == 53 and a DNS response → parse → event → rebuild
//! the packet when records were stripped).

use std::sync::Arc;

use crate::events::{now_ns, ReverseNetEvent, ReverseNetEventBus};

/// DnsMessage.HEADER_LENGTH.
const HEADER_LENGTH: usize = 12;
const TYPE_A: u16 = 1;
const TYPE_AAAA: u16 = 28;
const TYPE_CNAME: u16 = 5;
/// Compression pointer mask (DnsMessage.kt).
const POINTER_MASK: u8 = 0xc0;

/// Isolation mode deciding whether AAAA records are stripped from DNS responses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsFilterMode {
    Relaxed,
    Isolated,
}

/// DnsInterceptor.DNS_PORT.
pub const DNS_PORT: u16 = 53;

/// A parsed DNS response relevant to the relay tap (DnsResponse.kt).
#[derive(Debug, Clone)]
pub struct DnsResponse {
    pub question_name: String,
    /// A records as 32-bit host-order ints (DnsARecord.addressInt).
    pub a_records: Vec<u32>,
    /// AAAA records as 16 raw bytes (DnsAaaaRecord).
    pub aaaa_records: Vec<[u8; 16]>,
    /// Re-encode of the response with AAAA records removed; None if nothing was stripped.
    pub stripped_payload: Option<Vec<u8>>,
    /// True when at least one AAAA record was removed.
    pub aaaa_stripped: bool,
}

/// True if the payload looks like a DNS response (QR bit set, DnsMessage.isResponse).
pub fn is_response(payload: &[u8]) -> bool {
    if payload.len() < HEADER_LENGTH {
        return false;
    }
    crate::packet::be16(payload, 2) & 0x8000 != 0
}

/// Parse a DNS response and optionally strip AAAA records (DnsMessage.parse):
/// the window bytes are copied out and handed to [`parse_bytes`].
pub fn parse(payload: &[u8], strip_aaaa: bool) -> Option<DnsResponse> {
    if payload.len() < HEADER_LENGTH {
        return None;
    }
    parse_bytes(payload, 0, payload.len(), strip_aaaa)
}

/// DnsMessage.parseBytes: skip questions (capturing the first question name),
/// collect answers, classify A/AAAA (CNAME parsed but ignored) and rebuild the
/// payload with AAAA records removed when `strip_aaaa` is set.
pub fn parse_bytes(bytes: &[u8], offset: usize, length: usize, strip_aaaa: bool) -> Option<DnsResponse> {
    if length < HEADER_LENGTH {
        return None;
    }
    let end = offset + length;
    let qd_count = crate::packet::be16(bytes, offset + 4) as usize;
    let an_count = crate::packet::be16(bytes, offset + 6) as usize;
    let mut pos = offset + HEADER_LENGTH;

    // Skip questions; capture the first question name.
    let mut question_name = String::new();
    for q in 0..qd_count {
        let (name, next) = read_name(bytes, pos, offset);
        pos = next;
        if q == 0 {
            question_name = name;
        }
        pos += 4; // qtype + qclass
    }

    struct Answer {
        record_type: u16,
        record_start: usize,
        record_length: usize,
        rdata_start: usize,
        rd_length: usize,
    }

    let mut a_records = Vec::new();
    let mut aaaa_records = Vec::new();
    let mut answers = Vec::new();
    for _ in 0..an_count {
        if pos + 10 > end {
            return None;
        }
        let name_start = pos;
        let (_name, next) = read_name(bytes, pos, offset);
        pos = next;
        if pos + 10 > end {
            return None;
        }
        let record_type = crate::packet::be16(bytes, pos);
        let rd_length = crate::packet::be16(bytes, pos + 8) as usize;
        let record_start = name_start;
        pos += 10;
        if pos + rd_length > end {
            return None;
        }
        let record_length = pos + rd_length - record_start;
        answers.push(Answer { record_type, record_start, record_length, rdata_start: pos, rd_length });
        pos += rd_length;
    }

    for answer in &answers {
        match answer.record_type {
            TYPE_A if answer.rd_length == 4 => {
                // big-endian int: b0<<24 | b1<<16 | b2<<8 | b3
                a_records.push(crate::packet::be32(bytes, answer.rdata_start));
            }
            TYPE_AAAA if answer.rd_length == 16 => {
                let mut raw = [0u8; 16];
                raw.copy_from_slice(&bytes[answer.rdata_start..answer.rdata_start + 16]);
                aaaa_records.push(raw);
            }
            TYPE_CNAME => {} // parsed but ignored
            _ => {}
        }
    }

    let mut stripped_payload = None;
    let mut aaaa_stripped = false;
    if strip_aaaa && !aaaa_records.is_empty() {
        let kept: Vec<&Answer> = answers.iter().filter(|a| a.record_type != TYPE_AAAA).collect();
        let mut out = Vec::with_capacity(length);
        // Header: copy bytes 0..6 (ID through QDCOUNT), rewrite ANCOUNT, copy the
        // rest of the header.
        out.extend_from_slice(&bytes[offset..offset + 6]);
        out.push(((kept.len() >> 8) & 0xff) as u8);
        out.push((kept.len() & 0xff) as u8);
        out.extend_from_slice(&bytes[offset + 8..offset + HEADER_LENGTH]);
        // Questions: everything from end of header to the first answer record.
        let questions_end = answers.first().map(|a| a.record_start).unwrap_or(end);
        out.extend_from_slice(&bytes[offset + HEADER_LENGTH..questions_end]);
        // Kept answer records verbatim. Compression pointers inside record names
        // point into the header/question region, copied unchanged, so the kept
        // records stay decodable (DnsMessage.kt comment).
        for answer in &kept {
            out.extend_from_slice(&bytes[answer.record_start..answer.record_start + answer.record_length]);
        }
        stripped_payload = Some(out);
        aaaa_stripped = true;
    }

    Some(DnsResponse { question_name, a_records, aaaa_records, stripped_payload, aaaa_stripped })
}

/// Read a possibly-compressed domain name; returns (name, position after name)
/// with the 64-jump cap of DnsMessage.readName.
fn read_name(bytes: &[u8], start: usize, _message_offset: usize) -> (String, usize) {
    let mut labels: Vec<String> = Vec::new();
    let mut pos = start;
    let mut jumped = false;
    let mut end_pos: Option<usize> = None;
    let mut jumps = 0;
    loop {
        if pos >= bytes.len() {
            return (String::new(), end_pos.unwrap_or(pos));
        }
        let len = bytes[pos];
        if len == 0 {
            if !jumped {
                end_pos = Some(pos + 1);
            }
            break;
        }
        if (len & POINTER_MASK) == POINTER_MASK {
            if pos + 1 >= bytes.len() {
                break;
            }
            let target = (((len & 0x3f) as usize) << 8) | bytes[pos + 1] as usize;
            if !jumped {
                end_pos = Some(pos + 2);
            }
            jumped = true;
            pos = target;
            jumps += 1;
            if jumps > 64 {
                break;
            }
            continue;
        }
        if pos + 1 + len as usize > bytes.len() {
            break;
        }
        // US_ASCII in Kotlin; lossy UTF-8 keeps the same bytes for label names.
        labels.push(String::from_utf8_lossy(&bytes[pos + 1..pos + 1 + len as usize]).into_owned());
        pos += 1 + len as usize;
    }
    (
        if labels.is_empty() { String::new() } else { labels.join(".") },
        end_pos.unwrap_or(pos),
    )
}

/// Parses UDP:53 traffic flowing through the relay (DnsInterceptor.kt). On
/// responses it emits domain-to-IP events and, in isolated mode, rewrites the
/// payload to drop AAAA records.
pub struct DnsInterceptor {
    bus: Arc<ReverseNetEventBus>,
    mode: DnsFilterMode,
}

impl DnsInterceptor {
    pub fn new(bus: Arc<ReverseNetEventBus>, mode: DnsFilterMode) -> Self {
        Self { bus, mode }
    }

    pub fn is_dns_port(port: u16) -> bool {
        port == DNS_PORT
    }

    pub fn is_response(&self, payload: &[u8]) -> bool {
        is_response(payload)
    }

    /// Handle a DNS response flowing from the network towards the device.
    /// Returns the stripped payload in isolated mode when AAAA records were
    /// present, otherwise `None` meaning "forward the original payload".
    pub fn on_response(&self, payload: &[u8]) -> Option<Vec<u8>> {
        let response = match parse(payload, self.mode == DnsFilterMode::Isolated) {
            Some(response) => response,
            None => return None,
        };
        self.bus.emit(ReverseNetEvent::DnsResolved {
            domain: response.question_name,
            addresses: response.a_records.iter().map(|ip| crate::packet::dotted_ip(*ip)).collect(),
            aaaa_stripped: response.aaaa_stripped,
            monotonic_ns: now_ns(),
            sequence: 0,
        });
        response.stripped_payload
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a DNS response with one A record and one AAAA record for
    /// example.test (DnsMessageTest.buildResponse, spec §8.3).
    fn build_response(a_address: [u8; 4], aaaa_address: [u8; 16]) -> Vec<u8> {
        let mut out = Vec::new();
        // header: id, flags (response+recursion), qd=1, an=2, ns=0, ar=0
        out.extend_from_slice(&[0x12, 0x34, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0]);
        // question: example.test A IN
        out.push(7);
        out.extend_from_slice(b"example");
        out.push(4);
        out.extend_from_slice(b"test");
        out.push(0); // root
        out.extend_from_slice(&[0, 1, 0, 1]); // type A, class IN
        // answer 1: pointer to question name (0xc00c), A, IN, ttl, rdlen 4
        out.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4]);
        out.extend_from_slice(&a_address);
        // answer 2: pointer, AAAA, IN, ttl, rdlen 16
        out.extend_from_slice(&[0xc0, 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16]);
        out.extend_from_slice(&aaaa_address);
        out
    }

    #[test]
    fn parses_a_records_from_response() {
        let bytes = build_response([1, 2, 3, 4], [0; 16]);
        let response = parse_bytes(&bytes, 0, bytes.len(), false).unwrap();
        assert_eq!(response.question_name, "example.test");
        assert_eq!(response.a_records.len(), 1);
        assert_eq!(response.a_records[0], (1 << 24) | (2 << 16) | (3 << 8) | 4);
        assert_eq!(response.aaaa_records.len(), 1);
        assert!(!response.aaaa_stripped);
        assert!(response.stripped_payload.is_none());
    }

    #[test]
    fn isolated_mode_strips_aaaa_and_rewrites_ancount() {
        let mut aaaa = [0u8; 16];
        for (i, byte) in aaaa.iter_mut().enumerate() {
            *byte = i as u8;
        }
        let bytes = build_response([1, 2, 3, 4], aaaa);
        let response = parse_bytes(&bytes, 0, bytes.len(), true).unwrap();
        assert!(response.aaaa_stripped);
        let stripped = response.stripped_payload.unwrap();
        // ANCOUNT (bytes 6..8) must now be 1
        assert_eq!(((stripped[6] as u16) << 8) | stripped[7] as u16, 1);
        // Stripped message must still parse cleanly with only the A record…
        let reparsed = parse_bytes(&stripped, 0, stripped.len(), false).unwrap();
        assert_eq!(reparsed.a_records.len(), 1);
        assert_eq!(reparsed.aaaa_records.len(), 0);
        // …and stripping again is idempotent (no AAAA left to remove).
        assert!(!parse_bytes(&stripped, 0, stripped.len(), true).unwrap().aaaa_stripped);
    }

    #[test]
    fn relaxed_mode_keeps_aaaa() {
        let bytes = build_response([1, 2, 3, 4], [0; 16]);
        let response = parse_bytes(&bytes, 0, bytes.len(), false).unwrap();
        assert_eq!(response.aaaa_records.len(), 1);
        assert!(response.stripped_payload.is_none());
    }

    #[test]
    fn response_detection_requires_qr_bit() {
        let mut response = build_response([1, 2, 3, 4], [0; 16]);
        assert!(is_response(&response));
        // Clear QR bit (byte[2] = 0x01).
        response[2] = 0x01;
        assert!(!is_response(&response));
        assert!(!is_response(&response[..8])); // too short
    }

    #[test]
    fn interceptor_emits_event_and_strips_in_isolated_mode() {
        let bus = Arc::new(ReverseNetEventBus::new());
        let interceptor = DnsInterceptor::new(bus.clone(), DnsFilterMode::Isolated);
        let bytes = build_response([8, 8, 4, 4], [0; 16]);
        // UDP payload carries the full DNS message including its 12-byte header.
        let rewritten = interceptor.on_response(&bytes).expect("isolated mode should rewrite when AAAA present");
        let reparsed = parse_bytes(&rewritten, 0, rewritten.len(), false).unwrap();
        assert_eq!(reparsed.aaaa_records.len(), 0);
        let events = bus.drain();
        assert_eq!(events.len(), 1);
        match &events[0] {
            ReverseNetEvent::DnsResolved { domain, addresses, aaaa_stripped, .. } => {
                assert_eq!(domain, "example.test");
                assert_eq!(addresses, &["8.8.4.4".to_owned()]);
                assert!(*aaaa_stripped);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn interceptor_passes_through_in_relaxed_mode() {
        let bus = Arc::new(ReverseNetEventBus::new());
        let interceptor = DnsInterceptor::new(bus.clone(), DnsFilterMode::Relaxed);
        let bytes = build_response([8, 8, 4, 4], [0; 16]);
        // RELAXED must never rewrite: onResponse returns None (= original payload).
        assert!(interceptor.on_response(&bytes).is_none());
        // The DnsResolved event is still emitted (observation is independent of stripping).
        assert_eq!(bus.drain().len(), 1);
    }

    #[test]
    fn dns_port_gate() {
        assert!(DnsInterceptor::is_dns_port(53));
        assert!(!DnsInterceptor::is_dns_port(5353));
    }
}
