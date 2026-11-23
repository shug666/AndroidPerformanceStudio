//! Control protocol (host → device), ported from `ScrcpyControlWriter`
//! (spec §2). All multi-byte fields are big-endian; every message is one type
//! byte plus fixed fields and is flushed immediately.
//!
//! Length limits fail with an error enum instead of panicking or truncating
//! (spec §2.4: APS rejects over-limit text; the official client truncates).

use std::io::Write;

/// scrcpy v3.1 control message type bytes (spec §2.1 full table).
pub const INJECT_KEYCODE: u8 = 0;
pub const INJECT_TEXT: u8 = 1;
pub const INJECT_TOUCH_EVENT: u8 = 2;
pub const INJECT_SCROLL_EVENT: u8 = 3;
pub const BACK_OR_SCREEN_ON: u8 = 4;
pub const EXPAND_NOTIFICATION_PANEL: u8 = 5;
/// Constant only in APS; no sender method is exposed (spec §2.1).
pub const EXPAND_SETTINGS_PANEL: u8 = 6;
pub const COLLAPSE_PANELS: u8 = 7;
pub const GET_CLIPBOARD: u8 = 8;
pub const SET_CLIPBOARD: u8 = 9;
pub const SET_DISPLAY_POWER: u8 = 10;
pub const ROTATE_DEVICE: u8 = 11;
/// Re-opens the video stream; the server re-sends a config packet (spec §2.1).
pub const RESET_VIDEO: u8 = 17;

/// Android MotionEvent actions (spec §2.3).
pub const ACTION_DOWN: u8 = 0;
pub const ACTION_UP: u8 = 1;
pub const ACTION_MOVE: u8 = 2;
pub const ACTION_HOVER_MOVE: u8 = 7;

/// Android MotionEvent button flags (spec §2.3).
pub const BUTTON_PRIMARY: i32 = 1;
pub const BUTTON_SECONDARY: i32 = 2;
pub const BUTTON_TERTIARY: i32 = 4;

/// Android keycodes used by the navigation bar (spec §2.3).
pub const KEYCODE_HOME: i32 = 3;
pub const KEYCODE_BACK: i32 = 4;
pub const KEYCODE_VOLUME_UP: i32 = 24;
pub const KEYCODE_VOLUME_DOWN: i32 = 25;
pub const KEYCODE_POWER: i32 = 26;
pub const KEYCODE_APP_SWITCH: i32 = 187;

/// `POINTER_ID_MOUSE = -1L`, i.e. all-bits-set in the u64 wire field (§2.2/§6).
pub const POINTER_ID_MOUSE: u64 = u64::MAX;

/// `INJECT_TEXT_MAX_LENGTH` (spec §2.2).
pub const INJECT_TEXT_MAX_LENGTH: usize = 300;
/// `(1 << 18) - 14`: `MESSAGE_MAX_SIZE` minus the setClipboard header
/// (type + sequence + paste + length = 14 B; spec §2.2).
pub const SET_CLIPBOARD_MAX_BYTES: usize = (1 << 18) - 14;

/// Copy keys for `getClipboard` (spec §2.2).
pub const COPY_KEY_NONE: u8 = 0;
pub const COPY_KEY_COPY: u8 = 1;
pub const COPY_KEY_CUT: u8 = 2;

/// `u16` pressure fixed point: `clamp((v*65536) as i32, 0, 0xFFFF)` (spec §2.3).
/// `1.0 → 0xFFFF`, `0.0 → 0x0000`.
pub fn pressure_to_u16(value: f32) -> u16 {
    ((value * 65_536.0) as i32).clamp(0, u16::MAX as i32) as u16
}

/// `i16` scroll fixed point: `clamp((v*32768) as i32, -32768, 32767)` (§2.3).
/// `-1.0 → 0x8000` (the -32768 two's-complement path, matching APS).
pub fn scroll_to_i16(value: f32) -> i16 {
    ((value * 32_768.0) as i32).clamp(i16::MIN as i32, i16::MAX as i32) as i16
}

/// Control write failures. Limit violations are enum errors, never panics
/// (task constraint; spec §2.4 locks the reject behavior).
#[derive(Debug)]
pub enum ControlWriteError {
    Io(std::io::Error),
    TextTooLong { length: usize, max: usize },
    ClipboardTooLong { length: usize, max: usize },
}

impl std::fmt::Display for ControlWriteError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(f, "control socket write failed: {error}"),
            Self::TextTooLong { length, max } => write!(
                f,
                "inject text is {length} bytes, exceeding the {max}-byte limit"
            ),
            Self::ClipboardTooLong { length, max } => write!(
                f,
                "clipboard text is {length} bytes, exceeding the {max}-byte limit"
            ),
        }
    }
}

impl std::error::Error for ControlWriteError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<std::io::Error> for ControlWriteError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

pub type ControlResult<T = ()> = Result<T, ControlWriteError>;

/// Buffered big-endian writer over the control socket (§2: Kotlin used
/// `DataOutputStream(BufferedOutputStream(raw))`; each message is flushed).
pub struct ControlWriter<W: Write> {
    writer: W,
}

impl<W: Write> ControlWriter<W> {
    pub fn new(writer: W) -> Self {
        Self { writer }
    }

    /// Takes back the inner writer (used to close/shutdown on session finish).
    pub fn into_inner(self) -> W {
        self.writer
    }

    pub fn flush(&mut self) -> ControlResult<()> {
        self.writer.flush()?;
        Ok(())
    }

    /// injectTouch, 32 bytes total (spec §2.2).
    /// APS sends `pointer_id = POINTER_ID_MOUSE` and `action_button = 0` via
    /// [`crate::session`]; the writer itself accepts the full field domain.
    // The wire layout fixes the field count.
    #[allow(clippy::too_many_arguments)]
    pub fn inject_touch(
        &mut self,
        action: u8,
        pointer_id: u64,
        x: i32,
        y: i32,
        screen_width: u16,
        screen_height: u16,
        pressure: f32,
        action_button: i32,
        buttons: i32,
    ) -> ControlResult<()> {
        self.writer.write_all(&[INJECT_TOUCH_EVENT, action])?;
        self.writer.write_all(&pointer_id.to_be_bytes())?;
        self.write_position(x, y, screen_width, screen_height)?;
        self.writer
            .write_all(&pressure_to_u16(pressure).to_be_bytes())?;
        self.writer.write_all(&action_button.to_be_bytes())?;
        self.writer.write_all(&buttons.to_be_bytes())?;
        self.finish_message()
    }

    /// injectScroll, 21 bytes total (spec §2.2 field list).
    ///
    /// Note: the spec heading claims "25 字节总长", but its own field list sums
    /// to 21 bytes (type 1 + position 12 + i16×2 + buttons 4); the 25-byte
    /// figure matches the legacy scrcpy 1.x layout where scroll values were
    /// i32. The bundled server v3.1 parses the i16 fixed-point field list, so
    /// the field list is authoritative. Scroll values are already negated by
    /// the UI layer (§8.2); this writer passes them through.
    #[allow(clippy::too_many_arguments)]
    pub fn inject_scroll(
        &mut self,
        x: i32,
        y: i32,
        screen_width: u16,
        screen_height: u16,
        scroll_x: f32,
        scroll_y: f32,
        buttons: i32,
    ) -> ControlResult<()> {
        self.writer.write_all(&[INJECT_SCROLL_EVENT])?;
        self.write_position(x, y, screen_width, screen_height)?;
        self.writer
            .write_all(&scroll_to_i16(scroll_x).to_be_bytes())?;
        self.writer
            .write_all(&scroll_to_i16(scroll_y).to_be_bytes())?;
        self.writer.write_all(&buttons.to_be_bytes())?;
        self.finish_message()
    }

    /// injectKeycode, 14 bytes total (spec §2.2).
    pub fn inject_keycode(
        &mut self,
        action: u8,
        keycode: i32,
        repeat: i32,
        meta_state: i32,
    ) -> ControlResult<()> {
        self.writer.write_all(&[INJECT_KEYCODE, action])?;
        self.writer.write_all(&keycode.to_be_bytes())?;
        self.writer.write_all(&repeat.to_be_bytes())?;
        self.writer.write_all(&meta_state.to_be_bytes())?;
        self.finish_message()
    }

    /// tapKeycode: a down + up pair written as two consecutive messages
    /// (spec §10 ScrcpyControlWriterTest ③).
    pub fn tap_keycode(&mut self, keycode: i32) -> ControlResult<()> {
        self.inject_keycode(ACTION_DOWN, keycode, 0, 0)?;
        self.inject_keycode(ACTION_UP, keycode, 0, 0)
    }

    /// injectText (type 1); `> 300` UTF-8 bytes is rejected (spec §2.2/§2.4).
    pub fn inject_text(&mut self, text: &str) -> ControlResult<()> {
        let bytes = text.as_bytes();
        if bytes.len() > INJECT_TEXT_MAX_LENGTH {
            return Err(ControlWriteError::TextTooLong {
                length: bytes.len(),
                max: INJECT_TEXT_MAX_LENGTH,
            });
        }
        self.writer.write_all(&[INJECT_TEXT])?;
        self.writer.write_all(&(bytes.len() as u32).to_be_bytes())?;
        self.writer.write_all(bytes)?;
        self.finish_message()
    }

    /// setClipboard (type 9); APS sends `sequence = 0` (spec §2.2/§A.11).
    pub fn set_clipboard(&mut self, sequence: u64, paste: bool, text: &str) -> ControlResult<()> {
        let bytes = text.as_bytes();
        if bytes.len() > SET_CLIPBOARD_MAX_BYTES {
            return Err(ControlWriteError::ClipboardTooLong {
                length: bytes.len(),
                max: SET_CLIPBOARD_MAX_BYTES,
            });
        }
        self.writer.write_all(&[SET_CLIPBOARD])?;
        self.writer.write_all(&sequence.to_be_bytes())?;
        self.writer.write_all(&[u8::from(paste)])?;
        self.writer.write_all(&(bytes.len() as u32).to_be_bytes())?;
        self.writer.write_all(bytes)?;
        self.finish_message()
    }

    /// getClipboard (type 8, 2 bytes; spec §2.2).
    pub fn get_clipboard(&mut self, copy_key: u8) -> ControlResult<()> {
        self.writer.write_all(&[GET_CLIPBOARD, copy_key])?;
        self.finish_message()
    }

    /// backOrScreenOn (type 4, 2 bytes; spec §2.2).
    pub fn back_or_screen_on(&mut self, action: u8) -> ControlResult<()> {
        self.writer.write_all(&[BACK_OR_SCREEN_ON, action])?;
        self.finish_message()
    }

    /// expandNotificationPanel (type 5, empty payload; spec §2.2).
    pub fn expand_notification_panel(&mut self) -> ControlResult<()> {
        self.writer.write_all(&[EXPAND_NOTIFICATION_PANEL])?;
        self.finish_message()
    }

    /// collapsePanels (type 7, empty payload; spec §2.2).
    pub fn collapse_panels(&mut self) -> ControlResult<()> {
        self.writer.write_all(&[COLLAPSE_PANELS])?;
        self.finish_message()
    }

    /// rotateDevice (type 11, empty payload; spec §2.2).
    pub fn rotate_device(&mut self) -> ControlResult<()> {
        self.writer.write_all(&[ROTATE_DEVICE])?;
        self.finish_message()
    }

    /// resetVideo (type 17, empty payload; spec §2.1/§A.10).
    pub fn reset_video(&mut self) -> ControlResult<()> {
        self.writer.write_all(&[RESET_VIDEO])?;
        self.finish_message()
    }

    /// setDisplayPower (type 10, 2 bytes; `on` = 1 lights the display without
    /// sleeping; the video stream continues, spec §2.2).
    pub fn set_display_power(&mut self, on: bool) -> ControlResult<()> {
        self.writer.write_all(&[SET_DISPLAY_POWER, u8::from(on)])?;
        self.finish_message()
    }

    /// `position` field: `x:i32, y:i32, screenWidth:u16, screenHeight:u16`
    /// big-endian, 12 bytes (spec §2.2).
    fn write_position(
        &mut self,
        x: i32,
        y: i32,
        screen_width: u16,
        screen_height: u16,
    ) -> std::io::Result<()> {
        self.writer.write_all(&x.to_be_bytes())?;
        self.writer.write_all(&y.to_be_bytes())?;
        self.writer.write_all(&screen_width.to_be_bytes())?;
        self.writer.write_all(&screen_height.to_be_bytes())
    }

    /// Every message is flushed immediately (spec §2).
    fn finish_message(&mut self) -> ControlResult<()> {
        self.writer.flush()?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn written(writer: ControlWriter<Vec<u8>>) -> Vec<u8> {
        writer.into_inner()
    }

    /// Spec §10 ScrcpyControlWriterTest ①: exact 32-byte injectTouch.
    #[test]
    fn inject_touch_writes_the_exact_32_byte_layout() {
        let mut writer = ControlWriter::new(Vec::new());
        writer
            .inject_touch(
                ACTION_DOWN,
                0x1122_3344_5566_7788,
                108,
                215,
                1080,
                2400,
                1.0,
                1,
                BUTTON_PRIMARY,
            )
            .expect("touch write");
        let expected: [u8; 32] = [
            2, // type = INJECT_TOUCH_EVENT
            0, // ACTION_DOWN
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, // pointerId
            0, 0, 0, 108, // x
            0, 0, 0, 215, // y
            0x04, 0x38, // screenWidth 1080
            0x09, 0x60, // screenHeight 2400
            0xFF, 0xFF, // pressure 1.0 (clamped)
            0, 0, 0, 1, // actionButton
            0, 0, 0, 1, // buttons = PRIMARY
        ];
        assert_eq!(written(writer), expected);
    }

    /// Spec §10 ②: injectScroll with scrollY −1.0 → 0x8000.
    #[test]
    fn inject_scroll_writes_the_fixed_point_field_list() {
        let mut writer = ControlWriter::new(Vec::new());
        writer
            .inject_scroll(5, 6, 100, 200, 0.0, -1.0, 0)
            .expect("scroll write");
        let expected: [u8; 21] = [
            3, // type = INJECT_SCROLL_EVENT
            0, 0, 0, 5, // x
            0, 0, 0, 6, // y
            0, 100, // screenWidth 100
            0, 200, // screenHeight 200
            0x00, 0x00, // scrollX 0.0
            0x80, 0x00, // scrollY −1.0 → i16 −32768
            0, 0, 0, 0, // buttons
        ];
        assert_eq!(written(writer), expected);
    }

    /// Spec §10 ③: keycode 26 down, then tap BACK producing down+up.
    #[test]
    fn keycode_down_and_tap_produce_a_continuous_byte_stream() {
        let mut writer = ControlWriter::new(Vec::new());
        writer
            .inject_keycode(ACTION_DOWN, 26, 0, 0)
            .expect("keycode write");
        writer.tap_keycode(KEYCODE_BACK).expect("tap write");
        let expected: Vec<u8> = [
            // injectKeycode(POWER, down): type, action, keycode, repeat, metaState
            [0u8, 0].as_slice(),
            26u32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
            // tap BACK down
            [0u8, 0].as_slice(),
            4u32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
            // tap BACK up
            [0u8, 1].as_slice(),
            4u32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
            0i32.to_be_bytes().as_slice(),
        ]
        .concat();
        assert_eq!(written(writer), expected);
    }

    /// Spec §10 ④: setClipboard("你好", paste=true, sequence=7), 6 UTF-8 bytes.
    #[test]
    fn set_clipboard_writes_sequence_paste_and_utf8_length() {
        let mut writer = ControlWriter::new(Vec::new());
        writer
            .set_clipboard(7, true, "你好")
            .expect("clipboard write");
        let mut expected = vec![9u8];
        expected.extend_from_slice(&7u64.to_be_bytes());
        expected.push(1);
        expected.extend_from_slice(&6u32.to_be_bytes());
        expected.extend_from_slice("你好".as_bytes());
        assert_eq!(written(writer), expected);
    }

    /// Spec §10 ⑤: over-limit injectText/setClipboard are enum errors.
    #[test]
    fn over_limit_text_and_clipboard_are_rejected_without_writing() {
        let mut writer = ControlWriter::new(Vec::new());
        let long_text = "a".repeat(INJECT_TEXT_MAX_LENGTH + 1);
        let error = writer.inject_text(&long_text).expect_err("too long");
        assert!(matches!(
            error,
            ControlWriteError::TextTooLong {
                length: 301,
                max: 300
            }
        ));
        let long_clipboard = "a".repeat(SET_CLIPBOARD_MAX_BYTES + 1);
        let error = writer
            .set_clipboard(0, false, &long_clipboard)
            .expect_err("too long");
        assert!(matches!(
            error,
            ControlWriteError::ClipboardTooLong {
                max: SET_CLIPBOARD_MAX_BYTES,
                ..
            }
        ));
        // Reject happens before any byte is written: the stream stays empty and
        // the next message is intact.
        writer.rotate_device().expect("rotate after reject");
        assert_eq!(written(writer), [11u8]);
    }

    /// Boundary values still succeed (300-byte text, max-length clipboard).
    #[test]
    fn boundary_lengths_are_accepted() {
        let mut writer = ControlWriter::new(Vec::new());
        writer
            .inject_text(&"a".repeat(INJECT_TEXT_MAX_LENGTH))
            .expect("boundary text");
        writer
            .set_clipboard(0, false, &"b".repeat(SET_CLIPBOARD_MAX_BYTES))
            .expect("boundary clipboard");
        let buffer = written(writer);
        assert_eq!(buffer[0], INJECT_TEXT);
        assert_eq!(
            u32::from_be_bytes(buffer[1..5].try_into().unwrap()),
            INJECT_TEXT_MAX_LENGTH as u32
        );
        let clipboard = &buffer[5 + INJECT_TEXT_MAX_LENGTH..];
        assert_eq!(clipboard[0], SET_CLIPBOARD);
        assert_eq!(clipboard[9], 0, "paste flag false");
        assert_eq!(
            u32::from_be_bytes(clipboard[10..14].try_into().unwrap()),
            SET_CLIPBOARD_MAX_BYTES as u32
        );
    }

    /// Spec §10 ⑥: rotateDevice→[11], collapsePanels→[7], getClipboard→[8,1].
    #[test]
    fn single_byte_and_two_byte_messages_match_the_layout() {
        let mut writer = ControlWriter::new(Vec::new());
        writer.rotate_device().expect("rotate");
        writer.collapse_panels().expect("collapse");
        writer.get_clipboard(COPY_KEY_COPY).expect("get clipboard");
        writer.expand_notification_panel().expect("expand");
        writer.reset_video().expect("reset video");
        writer.back_or_screen_on(ACTION_DOWN).expect("back");
        writer.set_display_power(false).expect("display power");
        writer.inject_text("hi").expect("text");
        assert_eq!(
            written(writer),
            [
                11u8,
                7,
                8,
                COPY_KEY_COPY,
                5,
                17,
                4,
                ACTION_DOWN,
                10,
                0,
                1,
                0,
                0,
                0,
                2,
                b'h',
                b'i',
            ]
        );
    }

    /// Spec §2.3 fixed-point conversions: 1.0 → 0xFFFF, 0.0 → 0, −1.0 → 0x8000,
    /// and out-of-range values clamp.
    #[test]
    fn fixed_point_conversions_clamp_to_the_field_domain() {
        assert_eq!(pressure_to_u16(1.0), 0xFFFF);
        assert_eq!(pressure_to_u16(0.0), 0x0000);
        assert_eq!(pressure_to_u16(2.0), 0xFFFF);
        assert_eq!(pressure_to_u16(-1.0), 0x0000);
        assert_eq!(scroll_to_i16(-1.0), i16::MIN); // 0x8000
        assert_eq!(scroll_to_i16(1.0), i16::MAX); // 0x7FFF
        assert_eq!(scroll_to_i16(0.0), 0);
    }
}
