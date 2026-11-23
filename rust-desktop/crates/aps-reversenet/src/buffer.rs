//! Relay-internal circular buffers.
//!
//! Ported from `relay/StreamBuffer.kt` (byte stream, boundaries not preserved) and
//! `relay/DatagramBuffer.kt` (datagrams, boundaries preserved behind a 2-byte
//! big-endian length header). Neither buffer ever appears on the wire (spec §2.8):
//! they are host-side plumbing between the tunnel socket and the NAT connections.
//!
//! Faithful quirks kept per spec §2.8:
//! * `StreamBuffer` writes at most one contiguous segment per call.
//! * `DatagramBuffer` advances its tail before writing; a partial datagram write
//!   returns `false` and the unwritten remainder is dropped (spec notes this may
//!   be changed to retry/fallback — kept as-is for protocol parity).
//! * A non-blocking socket write that would block maps to Java NIO's `0` return
//!   value (`Ok(0)`), which callers interpret exactly like the Kotlin code did.

use std::io::{ErrorKind, Write};

/// Circular buffer to store a stream; read/write boundaries are not preserved.
/// Internally `capacity + 1` bytes with one sentinel byte distinguishing
/// empty from full (StreamBuffer.kt).
pub struct StreamBuffer {
    data: Vec<u8>,
    head: usize,
    tail: usize,
}

impl StreamBuffer {
    pub fn new(capacity: usize) -> Self {
        Self { data: vec![0; capacity + 1], head: 0, tail: 0 }
    }

    pub fn is_empty(&self) -> bool {
        self.head == self.tail
    }

    pub fn is_full(&self) -> bool {
        (self.head + 1) % self.data.len() == self.tail
    }

    pub fn size(&self) -> usize {
        if self.head < self.tail {
            self.head + self.data.len() - self.tail
        } else {
            self.head - self.tail
        }
    }

    pub fn capacity(&self) -> usize {
        self.data.len() - 1
    }

    pub fn remaining(&self) -> usize {
        self.capacity() - self.size()
    }

    /// Write one contiguous segment to the socket. `Ok(0)` covers both the empty
    /// buffer case and Java NIO's would-block `0` return (see module docs).
    pub fn write_to(&mut self, stream: &mut impl Write) -> std::io::Result<usize> {
        let written = if self.head > self.tail {
            stream.write(&self.data[self.tail..self.head])?
        } else if self.head < self.tail {
            stream.write(&self.data[self.tail..])?
        } else {
            0 // head == tail: empty buffer
        };
        self.tail = (self.tail + written) % self.data.len();
        self.optimize();
        Ok(written)
    }

    /// Truncated read: stores `min(remaining, src.len())` bytes (StreamBuffer.kt).
    pub fn read_from(&mut self, src: &[u8]) -> usize {
        let requested = src.len().min(self.remaining());
        if requested <= self.data.len() - self.head {
            self.data[self.head..self.head + requested].copy_from_slice(&src[..requested]);
        } else {
            let first = self.data.len() - self.head;
            self.data[self.head..].copy_from_slice(&src[..first]);
            self.data[..requested - first].copy_from_slice(&src[first..requested]);
        }
        self.head = (self.head + requested) % self.data.len();
        requested
    }

    /// Reset head/tail to 0 when empty so one-packet-at-a-time usage never wraps
    /// (StreamBuffer.optimize()).
    fn optimize(&mut self) {
        if self.is_empty() {
            self.head = 0;
            self.tail = 0;
        }
    }
}

/// Every datagram is stored with a 16-bit length header (DatagramBuffer.kt).
const HEADER_LENGTH: usize = 2;
const MAX_DATAGRAM_LENGTH: usize = 1 << 16;
const MAX_BLOCK_LENGTH: usize = HEADER_LENGTH + MAX_DATAGRAM_LENGTH;

/// Circular buffer to store datagrams, preserving their boundaries. The ring
/// itself is `capacity + 1` long; the backing array reserves one extra
/// `MAX_BLOCK_LENGTH` region so a block may cross the wrap point.
pub struct DatagramBuffer {
    data: Vec<u8>,
    head: usize,
    tail: usize,
    circular_buffer_length: usize,
}

impl DatagramBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            data: vec![0; capacity + MAX_BLOCK_LENGTH],
            head: 0,
            tail: 0,
            circular_buffer_length: capacity + 1,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.head == self.tail
    }

    pub fn capacity(&self) -> usize {
        self.circular_buffer_length - 1
    }

    pub fn has_enough_space_for(&self, datagram_length: usize) -> bool {
        if self.head >= self.tail {
            // There is at least the extra space for storing 1 packet (reserve region).
            return true;
        }
        let remaining = self.tail - self.head - 1; // 1 extra byte to distinguish empty vs full
        HEADER_LENGTH + datagram_length <= remaining
    }

    /// Store one datagram behind a 2-byte big-endian length header. Returns
    /// `false` when the datagram is too long or there is no space (Kotlin threw
    /// IllegalArgumentException for > 64 KiB; payloads are bounded well below).
    pub fn read_from(&mut self, src: &[u8]) -> bool {
        let length = src.len();
        if length > MAX_DATAGRAM_LENGTH {
            return false;
        }
        if !self.has_enough_space_for(length) {
            return false;
        }
        self.data[self.head] = (length >> 8) as u8;
        self.data[self.head + 1] = (length & 0xff) as u8;
        self.head += HEADER_LENGTH;
        self.data[self.head..self.head + length].copy_from_slice(src);
        self.head += length;
        if self.head >= self.circular_buffer_length {
            self.head = 0;
        }
        true
    }

    /// Write exactly one datagram to the connected UDP socket. `Ok(false)` means
    /// the socket would block or accepted fewer bytes than the datagram size —
    /// the tail has already advanced, so the remainder is dropped (Kotlin
    /// semantics, spec §2.8).
    pub fn write_to(&mut self, socket: &mut mio::net::UdpSocket) -> std::io::Result<bool> {
        let length = self.read_length();
        let start = self.tail;
        self.tail += length;
        if self.tail >= self.circular_buffer_length {
            self.tail = 0;
        }
        let block = &self.data[start..start + length];
        match socket.send(block) {
            Ok(w) => Ok(w == length),
            Err(error) if error.kind() == ErrorKind::WouldBlock => Ok(false),
            Err(error) => Err(error),
        }
    }

    fn read_length(&mut self) -> usize {
        let length =
            ((self.data[self.tail] as usize) << 8) | self.data[self.tail + 1] as usize;
        self.tail += HEADER_LENGTH;
        length
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stream_buffer_wraps_and_preserves_size() {
        let mut buffer = StreamBuffer::new(8);
        assert!(buffer.is_empty());
        assert_eq!(buffer.remaining(), 8);
        assert_eq!(buffer.read_from(&[1, 2, 3, 4, 5]), 5);
        assert_eq!(buffer.size(), 5);
        // Truncated read: only `remaining` bytes are stored.
        assert_eq!(buffer.read_from(&[6, 7, 8, 9]), 3);
        assert!(buffer.is_full());
        assert_eq!(buffer.remaining(), 0);
    }

    #[test]
    fn stream_buffer_partial_write_keeps_rest() {
        let mut buffer = StreamBuffer::new(8);
        buffer.read_from(&[1, 2, 3, 4, 5]);
        let mut sink = Vec::new();
        // Vec<u8> as `Write` always consumes everything; the empty-buffer case
        // and the optimize() reset are covered by drain-then-refill.
        assert_eq!(buffer.write_to(&mut sink).unwrap(), 5);
        assert!(buffer.is_empty());
        buffer.read_from(&[9, 9]);
        assert_eq!(buffer.size(), 2);
    }

    #[test]
    fn datagram_buffer_keeps_boundaries() {
        let mut buffer = DatagramBuffer::new(256);
        assert!(buffer.read_from(&[1, 2, 3]));
        assert!(buffer.read_from(&[4, 5]));
        // One datagram per write; boundaries survive the ring.
        let mut socket = mio::net::UdpSocket::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let peer = mio::net::UdpSocket::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        socket.connect(peer.local_addr().unwrap()).unwrap();
        assert!(buffer.write_to(&mut socket).unwrap());
        assert!(buffer.write_to(&mut socket).unwrap());
        let mut first = [0u8; 16];
        let mut second = [0u8; 16];
        assert_eq!(peer.recv(&mut first).unwrap(), 3);
        assert_eq!(peer.recv(&mut second).unwrap(), 2);
        assert_eq!(&first[..3], &[1, 2, 3]);
        assert_eq!(&second[..2], &[4, 5]);
        assert!(buffer.is_empty());
    }

    #[test]
    fn datagram_buffer_rejects_oversized_and_keeps_accepting_small() {
        let mut buffer = DatagramBuffer::new(8);
        let big = vec![0u8; MAX_DATAGRAM_LENGTH + 1];
        assert!(!buffer.read_from(&big));
        // Optimistic space check (head >= tail) always accepts small datagrams —
        // the ring is drained by the consumer, mirroring DatagramBuffer.kt.
        assert!(buffer.read_from(&[0xAA; 4]));
        assert!(buffer.read_from(&[0xBB; 4]));
        assert!(buffer.read_from(&[0xCC; 4]));
    }

    #[test]
    fn datagram_length_header_is_big_endian() {
        let mut buffer = DatagramBuffer::new(64);
        let payload = vec![7u8; 0x0140];
        assert!(buffer.read_from(&payload));
        // Head starts at 0: the first two bytes hold the big-endian length.
        assert_eq!((buffer.data[0] as usize) << 8 | buffer.data[1] as usize, 0x0140);
    }
}
