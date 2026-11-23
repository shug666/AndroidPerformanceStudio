//! Device message protocol (device → host), ported from
//! `ScrcpyDeviceMessageReader` (spec §3). Read from the control socket, which
//! is full-duplex: the host writes control commands, the device answers with
//! device messages. All multi-byte fields are big-endian.
//!
//! The session drains these messages and discards them (`drain_device_messages`);
//! an `IOException` on the control socket is swallowed — a real disconnect is
//! detected by the video loop (spec §3 drain semantics).

use crate::read_exact_or_eof;
use std::io::Read;

/// Device message type bytes (spec §3).
pub const TYPE_CLIPBOARD: u8 = 0;
pub const TYPE_ACK_CLIPBOARD: u8 = 1;
pub const TYPE_UHID_OUTPUT: u8 = 2;

/// Clipboard payload length cap in the device→host direction.
///
/// The spec table pins the literal `262131` (the prose "(1<<18)-5" is
/// arithmetically inconsistent — (1<<18)-5 is 262139 — but both the spec §3
/// table and the task brief state 262131, so the literal wins). Note this is a
/// different constant from the control-direction `-14` cap (§3 warning: do not
/// share the two).
pub const CLIPBOARD_MAX_LENGTH: usize = 262_131;

/// One message received from the device (spec §3).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceMessage {
    /// TYPE_CLIPBOARD: `i32 len` + UTF-8 text.
    Clipboard(Vec<u8>),
    /// TYPE_ACK_CLIPBOARD: `u64 sequence` (APS always sends sequence=0).
    AckClipboard(u64),
    /// TYPE_UHID_OUTPUT: `u16 id` + `u16 size` + bytes (parsed but unused).
    UhidOutput { id: u16, data: Vec<u8> },
}

/// Reader over the control socket half owned by the device-message direction.
pub struct DeviceMessageReader<R: Read> {
    inner: R,
}

impl<R: Read> DeviceMessageReader<R> {
    pub fn new(inner: R) -> Self {
        Self { inner }
    }

    /// Reads one message. `Ok(None)` = clean EOF (session end signal, §3).
    /// Unknown type bytes are `InvalidData` errors, mirroring the official
    /// `ControlProtocolException` behavior.
    pub fn read_message(&mut self) -> std::io::Result<Option<DeviceMessage>> {
        let mut type_byte = [0u8; 1];
        if !read_exact_or_eof(&mut self.inner, &mut type_byte)? {
            return Ok(None);
        }
        match type_byte[0] {
            TYPE_CLIPBOARD => {
                let Some(length) = self.read_i32()? else {
                    return Ok(None);
                };
                if length < 0 || length as usize > CLIPBOARD_MAX_LENGTH {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        format!("Invalid scrcpy clipboard length: {length}"),
                    ));
                }
                let mut text = vec![0u8; length as usize];
                if !read_exact_or_eof(&mut self.inner, &mut text)? {
                    return Ok(None);
                }
                Ok(Some(DeviceMessage::Clipboard(text)))
            }
            TYPE_ACK_CLIPBOARD => {
                let mut sequence = [0u8; 8];
                if !read_exact_or_eof(&mut self.inner, &mut sequence)? {
                    return Ok(None);
                }
                Ok(Some(DeviceMessage::AckClipboard(u64::from_be_bytes(
                    sequence,
                ))))
            }
            TYPE_UHID_OUTPUT => {
                let mut header = [0u8; 4];
                if !read_exact_or_eof(&mut self.inner, &mut header)? {
                    return Ok(None);
                }
                let id = u16::from_be_bytes(header[..2].try_into().unwrap());
                let size = u16::from_be_bytes(header[2..].try_into().unwrap());
                let mut data = vec![0u8; usize::from(size)];
                if !read_exact_or_eof(&mut self.inner, &mut data)? {
                    return Ok(None);
                }
                Ok(Some(DeviceMessage::UhidOutput { id, data }))
            }
            other => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("Unknown scrcpy device message type: {other}"),
            )),
        }
    }

    fn read_i32(&mut self) -> std::io::Result<Option<i32>> {
        let mut buffer = [0u8; 4];
        if !read_exact_or_eof(&mut self.inner, &mut buffer)? {
            // EOF anywhere inside a message is a clean stream end (§3).
            return Ok(None);
        }
        Ok(Some(i32::from_be_bytes(buffer)))
    }
}

/// Spec §3 drain semantics: loop, read, discard; stop silently on EOF or
/// `IOException` — the video loop owns real disconnect detection.
pub fn drain_device_messages<R: Read>(reader: &mut DeviceMessageReader<R>) {
    loop {
        match reader.read_message() {
            Ok(Some(_message)) => continue,
            Ok(None) | Err(_) => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn read_all(bytes: &[u8]) -> std::io::Result<Vec<DeviceMessage>> {
        let mut reader = DeviceMessageReader::new(Cursor::new(bytes.to_vec()));
        let mut messages = Vec::new();
        while let Some(message) = reader.read_message()? {
            messages.push(message);
        }
        Ok(messages)
    }

    /// Spec §10 ScrcpyDeviceMessageReaderTest ①: all three types in one stream.
    #[test]
    fn parses_clipboard_ack_and_uhid_output() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&[TYPE_CLIPBOARD]);
        bytes.extend_from_slice(&3i32.to_be_bytes());
        bytes.extend_from_slice("abc".as_bytes());
        bytes.extend_from_slice(&[TYPE_ACK_CLIPBOARD]);
        bytes.extend_from_slice(&42u64.to_be_bytes());
        bytes.extend_from_slice(&[TYPE_UHID_OUTPUT]);
        bytes.extend_from_slice(&3u16.to_be_bytes());
        bytes.extend_from_slice(&2u16.to_be_bytes());
        bytes.extend_from_slice(&[5, 6]);
        assert_eq!(
            read_all(&bytes).expect("parse"),
            vec![
                DeviceMessage::Clipboard(b"abc".to_vec()),
                DeviceMessage::AckClipboard(42),
                DeviceMessage::UhidOutput {
                    id: 3,
                    data: vec![5, 6],
                },
            ],
        );
    }

    /// Spec §10 ②: EOF → null.
    #[test]
    fn eof_reads_as_none() {
        let mut reader = DeviceMessageReader::new(Cursor::new(Vec::new()));
        assert_eq!(reader.read_message().expect("eof"), None);
        // Empty clipboard (len 0) is legal, then the stream ends.
        assert_eq!(
            read_all(&[TYPE_CLIPBOARD, 0, 0, 0, 0]).expect("parse"),
            vec![DeviceMessage::Clipboard(Vec::new())],
        );
    }

    /// Spec §10 ③: unknown type 99 → error.
    #[test]
    fn unknown_type_is_an_invalid_data_error() {
        let mut reader = DeviceMessageReader::new(Cursor::new(vec![99]));
        let error = reader.read_message().expect_err("unknown type");
        assert!(
            error
                .to_string()
                .contains("Unknown scrcpy device message type: 99")
        );
    }

    /// Clipboard lengths outside `0..=262131` are rejected (spec §3).
    #[test]
    fn clipboard_length_outside_the_valid_domain_is_rejected() {
        let mut negative = DeviceMessageReader::new(Cursor::new({
            let mut bytes = vec![TYPE_CLIPBOARD];
            bytes.extend_from_slice(&(-1i32).to_be_bytes());
            bytes
        }));
        let error = negative.read_message().expect_err("negative length");
        assert!(
            error
                .to_string()
                .contains("Invalid scrcpy clipboard length: -1")
        );

        let mut too_long = DeviceMessageReader::new(Cursor::new({
            let mut bytes = vec![TYPE_CLIPBOARD];
            bytes.extend_from_slice(&((CLIPBOARD_MAX_LENGTH + 1) as i32).to_be_bytes());
            bytes
        }));
        let error = too_long.read_message().expect_err("oversized length");
        assert!(
            error
                .to_string()
                .contains("Invalid scrcpy clipboard length"),
            "got: {error}"
        );
    }

    /// Drain consumes everything and survives a malformed tail silently (§3).
    #[test]
    fn drain_discards_messages_and_stops_quietly() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&[TYPE_ACK_CLIPBOARD]);
        bytes.extend_from_slice(&1u64.to_be_bytes());
        bytes.push(TYPE_UHID_OUTPUT); // truncated tail → drained quietly
        let mut reader = DeviceMessageReader::new(Cursor::new(bytes));
        drain_device_messages(&mut reader);
    }
}
