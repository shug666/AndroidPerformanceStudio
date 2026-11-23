//! Video stream protocol (device → host), ported from `ScrcpyVideoStreamReader`
//! (spec §4). Read from the video socket; `send_frame_meta`, `send_codec_meta`
//! and `send_device_meta` are all server defaults (APS never overrides them),
//! so the byte layout is fixed:
//!
//! ```text
//! [64 B]  device name: NUL-padded fixed field, cut at the first 0x00, UTF-8
//! [12 B]  metadata: codecId:i32, width:i32, height:i32 (big-endian)
//! loop:   [8 B ptsAndFlags:u64][4 B size:i32][size B H.264 Annex-B payload]
//! ```
//!
//! ptsAndFlags bit layout matches upstream `Streamer.writeFrameMeta`: bit 63 =
//! config packet, bit 62 = key frame, low 62 bits = pts in microseconds.

use crate::read_exact_or_eof;
use std::io::Read;

pub const DEVICE_NAME_BYTES: usize = 64;
pub const METADATA_BYTES: usize = 12;
pub const PTS_AND_FLAG_BYTES: usize = 8;
pub const PACKET_SIZE_BYTES: usize = 4;

/// APS self-imposed defensive cap; the official server has no such check
/// (spec §4/§A.6). 4K frames stay far below it.
pub const MAX_PACKET_SIZE: usize = 16 << 20;

pub const CONFIG_FLAG: u64 = 1 << 63;
pub const KEY_FRAME_FLAG: u64 = 1 << 62;
/// Low 62 bits carry the pts (`PACKET_FLAG_KEY_FRAME - 1` upstream).
pub const PTS_MASK: u64 = (1 << 62) - 1;

/// Codec ids on the wire ("h264"/"h265"/"av1" ASCII; spec §4).
pub const VIDEO_CODEC_H264: i32 = 0x6832_3634;
pub const VIDEO_CODEC_H265: i32 = 0x6832_3635;
pub const VIDEO_CODEC_AV1: i32 = 0x0061_7631;

/// The only codec APS accepts (spec §1.2 step 8: anything else fails with
/// `Unsupported video codec from server: <name>`).
pub fn codec_name(codec_id: i32) -> String {
    let name: &str = match codec_id {
        VIDEO_CODEC_H264 => "h264",
        VIDEO_CODEC_H265 => "h265",
        VIDEO_CODEC_AV1 => "av1",
        _ => return format!("0x{codec_id:08x}"),
    };
    name.to_owned()
}

/// The 12-byte video metadata block (spec §4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoMetadata {
    pub codec_id: i32,
    pub width: u32,
    pub height: u32,
}

impl VideoMetadata {
    pub fn codec_name(&self) -> String {
        codec_name(self.codec_id)
    }
}

/// One frame packet after the metadata header (spec §4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoPacket {
    pub pts_and_flags: u64,
    pub payload: Vec<u8>,
}

impl VideoPacket {
    /// bit 63: codec configuration (Annex-B SPS/PPS); low bits are not pts.
    pub fn is_config(&self) -> bool {
        self.pts_and_flags & CONFIG_FLAG != 0
    }

    /// bit 62: key frame.
    pub fn is_key_frame(&self) -> bool {
        self.pts_and_flags & KEY_FRAME_FLAG != 0
    }

    /// Low 62 bits: pts in microseconds (meaningful for non-config packets).
    pub fn pts(&self) -> u64 {
        self.pts_and_flags & PTS_MASK
    }
}

/// Reader over the video socket (spec §4).
pub struct StreamReader<R: Read> {
    inner: R,
}

impl<R: Read> StreamReader<R> {
    pub fn new(inner: R) -> Self {
        Self { inner }
    }

    /// Reads the device meta (64 B name) + video meta (12 B) that the server
    /// writes on the first socket before any frame packet (§1.2 step 8, §4).
    /// EOF here is a startup failure, not a clean session end.
    pub fn read_device_meta(&mut self) -> std::io::Result<(String, VideoMetadata)> {
        let mut name = [0u8; DEVICE_NAME_BYTES];
        if !read_exact_or_eof(&mut self.inner, &mut name)? {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "server closed before sending the device name",
            ));
        }
        let name_end = name
            .iter()
            .position(|&byte| byte == 0)
            .unwrap_or(name.len());
        let device_name = String::from_utf8_lossy(&name[..name_end]).into_owned();

        let mut metadata = [0u8; METADATA_BYTES];
        if !read_exact_or_eof(&mut self.inner, &mut metadata)? {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "server closed before sending the video metadata",
            ));
        }
        let codec_id = i32::from_be_bytes(metadata[..4].try_into().unwrap());
        let width = u32::from_be_bytes(metadata[4..8].try_into().unwrap());
        let height = u32::from_be_bytes(metadata[8..].try_into().unwrap());
        Ok((
            device_name,
            VideoMetadata {
                codec_id,
                width,
                height,
            },
        ))
    }

    /// Reads one frame packet. `Ok(None)` = clean EOF → session ends (§4).
    /// Packet sizes outside `0..=16 MiB` fail with "Invalid scrcpy packet size".
    pub fn read_packet(&mut self) -> std::io::Result<Option<VideoPacket>> {
        let mut header = [0u8; PTS_AND_FLAG_BYTES + PACKET_SIZE_BYTES];
        if !read_exact_or_eof(&mut self.inner, &mut header)? {
            return Ok(None);
        }
        let pts_and_flags = u64::from_be_bytes(header[..PTS_AND_FLAG_BYTES].try_into().unwrap());
        let size = i32::from_be_bytes(header[PTS_AND_FLAG_BYTES..].try_into().unwrap());
        if size < 0 || size as usize > MAX_PACKET_SIZE {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("Invalid scrcpy packet size: {size}"),
            ));
        }
        let mut payload = vec![0u8; size as usize];
        if !read_exact_or_eof(&mut self.inner, &mut payload)? {
            return Ok(None);
        }
        Ok(Some(VideoPacket {
            pts_and_flags,
            payload,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn device_meta_bytes(device_name: &str, codec_id: i32, width: u32, height: u32) -> Vec<u8> {
        let mut bytes = vec![0u8; DEVICE_NAME_BYTES];
        bytes[..device_name.len()].copy_from_slice(device_name.as_bytes());
        bytes.extend_from_slice(&codec_id.to_be_bytes());
        bytes.extend_from_slice(&width.to_be_bytes());
        bytes.extend_from_slice(&height.to_be_bytes());
        bytes
    }

    /// Spec §10 ScrcpyVideoStreamReaderTest ①: "Pixel 8" + h264 1080×2400.
    #[test]
    fn parses_device_name_and_h264_metadata() {
        let bytes = device_meta_bytes("Pixel 8", VIDEO_CODEC_H264, 1080, 2400);
        let mut reader = StreamReader::new(Cursor::new(bytes));
        let (device_name, metadata) = reader.read_device_meta().expect("device meta");
        assert_eq!(device_name, "Pixel 8");
        assert_eq!(
            metadata,
            VideoMetadata {
                codec_id: VIDEO_CODEC_H264,
                width: 1080,
                height: 2400
            }
        );
        assert_eq!(metadata.codec_name(), "h264");
    }

    /// Spec §10 ②: config packet, bit 63 only.
    #[test]
    fn config_packet_sets_only_the_config_flag() {
        let mut bytes = device_meta_bytes("d", VIDEO_CODEC_H264, 1, 1);
        bytes.extend_from_slice(&CONFIG_FLAG.to_be_bytes());
        bytes.extend_from_slice(&4i32.to_be_bytes());
        bytes.extend_from_slice(&[1, 2, 3, 4]);
        let mut reader = StreamReader::new(Cursor::new(bytes));
        reader.read_device_meta().expect("device meta");
        let packet = reader.read_packet().expect("packet").expect("some");
        assert!(packet.is_config());
        assert!(!packet.is_key_frame());
        assert_eq!(packet.pts(), 0);
        assert_eq!(packet.payload, [1, 2, 3, 4]);
    }

    /// Spec §10 ③: key frame with pts 12345 (bit 62 | pts).
    #[test]
    fn key_frame_packet_carries_the_pts_in_the_low_bits() {
        let pts_and_flags = 12_345 | KEY_FRAME_FLAG;
        let mut bytes = vec![];
        bytes.extend_from_slice(&pts_and_flags.to_be_bytes());
        bytes.extend_from_slice(&0i32.to_be_bytes());
        let mut reader = StreamReader::new(Cursor::new(bytes));
        let packet = reader.read_packet().expect("packet").expect("some");
        assert!(!packet.is_config());
        assert!(packet.is_key_frame());
        assert_eq!(packet.pts(), 12_345);
    }

    /// Spec §10 ④: both flags plus pts → mask keeps only the low 62 bits.
    #[test]
    fn double_flags_mask_the_pts_correctly() {
        let pts_and_flags = CONFIG_FLAG | KEY_FRAME_FLAG | 987_654_321;
        let mut bytes = vec![];
        bytes.extend_from_slice(&pts_and_flags.to_be_bytes());
        bytes.extend_from_slice(&0i32.to_be_bytes());
        let mut reader = StreamReader::new(Cursor::new(bytes));
        let packet = reader.read_packet().expect("packet").expect("some");
        assert!(packet.is_config());
        assert!(packet.is_key_frame());
        assert_eq!(packet.pts(), 987_654_321);
    }

    /// Spec §10 ⑤: EOF → null.
    #[test]
    fn eof_reads_as_none() {
        let mut reader = StreamReader::new(Cursor::new(Vec::new()));
        assert_eq!(reader.read_packet().expect("eof"), None);
    }

    /// Packet sizes outside `0..=16 MiB` fail with the spec message (§4).
    #[test]
    fn oversized_packet_size_is_rejected() {
        let mut bytes = vec![];
        bytes.extend_from_slice(&42u64.to_be_bytes());
        bytes.extend_from_slice(&((MAX_PACKET_SIZE + 1) as i32).to_be_bytes());
        let mut reader = StreamReader::new(Cursor::new(bytes));
        let error = reader.read_packet().expect_err("oversize");
        assert!(
            error
                .to_string()
                .starts_with("Invalid scrcpy packet size: ")
        );

        let mut bytes = vec![];
        bytes.extend_from_slice(&42u64.to_be_bytes());
        bytes.extend_from_slice(&(-1i32).to_be_bytes());
        let mut reader = StreamReader::new(Cursor::new(bytes));
        let error = reader.read_packet().expect_err("negative size");
        assert!(error.to_string().contains("-1"));
    }

    /// Non-h264 codec ids render human-readable names (§1.2 step 8 message).
    #[test]
    fn codec_names_cover_the_wire_ids() {
        assert_eq!(codec_name(VIDEO_CODEC_H264), "h264");
        assert_eq!(codec_name(VIDEO_CODEC_H265), "h265");
        assert_eq!(codec_name(VIDEO_CODEC_AV1), "av1");
        assert_eq!(codec_name(0x1234_5678), "0x12345678");
    }
}
