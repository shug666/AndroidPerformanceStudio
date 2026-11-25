//! Screencast quality presets, ported from `ScreencastQuality` (spec §7).
//!
//! The three presets map onto scrcpy-server v3.1 `key=value` options verbatim;
//! the keys are the server's own parser keys (`max_size`, `max_fps`,
//! `video_bit_rate`). NATIVE deliberately omits `max_size` (native resolution)
//! and caps fps at 30 — a measured trade-off: 60 fps queues the device-side
//! H.264 encoder and adds 20 ms+ end-to-end latency (spec §7, TB355FU).

/// Encoding quality preset selected per session (spec §7).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ScreencastQuality {
    /// 流畅: 1280 / 10 fps / 2 Mbps. Forced by the Kotlin jcodec fallback chain.
    Fluent,
    /// 高清: 1600 / 60 fps / 12 Mbps.
    Hd,
    /// 原生: native resolution (no `max_size`) / 30 fps / 20 Mbps. APS
    /// default (the mirror is the interaction surface; downscaling hurts
    /// text legibility).
    #[default]
    Native,
}

impl ScreencastQuality {
    /// APS default preset: native resolution (user preference; the mirror is
    /// the interaction surface, and downscaling hurts text legibility).
    pub const DEFAULT: ScreencastQuality = ScreencastQuality::Native;

    /// UI-facing label; the dropdown renders `"<label> · <maxFps>fps"` (§7).
    pub fn label(self) -> &'static str {
        match self {
            Self::Fluent => "流畅",
            Self::Hd => "高清",
            Self::Native => "原生",
        }
    }

    /// `max_size` server option; `None` means "do not send `max_size`" (§7).
    pub fn max_size(self) -> Option<u32> {
        match self {
            Self::Fluent => Some(1280),
            Self::Hd => Some(1600),
            Self::Native => None,
        }
    }

    /// `max_fps` server option (§7).
    pub fn max_fps(self) -> u32 {
        match self {
            Self::Fluent => 10,
            Self::Hd => 60,
            Self::Native => 30,
        }
    }

    /// `video_bit_rate` server option (§7).
    pub fn video_bit_rate(self) -> u32 {
        match self {
            Self::Fluent => 2_000_000,
            Self::Hd => 12_000_000,
            Self::Native => 20_000_000,
        }
    }

    /// Server options appended after the fixed app_process argv (spec §1.3/§7).
    /// Option order follows the spec table: `max_size` first (when present),
    /// then `max_fps`, then `video_bit_rate`.
    pub fn server_argv(self) -> Vec<String> {
        let mut argv = Vec::with_capacity(3);
        if let Some(max_size) = self.max_size() {
            argv.push(format!("max_size={max_size}"));
        }
        argv.push(format!("max_fps={}", self.max_fps()));
        argv.push(format!("video_bit_rate={}", self.video_bit_rate()));
        argv
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The default preset is NATIVE (deliberate deviation from the spec's
    /// `ScreencastQuality.DEFAULT == HD`; the mirror is the interaction
    /// surface and downscaling hurts text legibility).
    #[test]
    fn default_is_native() {
        assert_eq!(ScreencastQuality::DEFAULT, ScreencastQuality::Native);
        assert_eq!(ScreencastQuality::default(), ScreencastQuality::Native);
    }

    /// Spec §10: FLUENT carries max_size=1280 / max_fps=10 / 2 Mbps.
    #[test]
    fn fluent_server_argv_matches_the_spec_table() {
        let quality = ScreencastQuality::Fluent;
        assert_eq!(
            quality.server_argv(),
            ["max_size=1280", "max_fps=10", "video_bit_rate=2000000"]
        );
        assert_eq!(quality.label(), "流畅");
    }

    /// Spec §10: HD carries max_size=1600 / max_fps=60 / 12 Mbps.
    #[test]
    fn hd_server_argv_matches_the_spec_table() {
        let quality = ScreencastQuality::Hd;
        assert_eq!(
            quality.server_argv(),
            ["max_size=1600", "max_fps=60", "video_bit_rate=12000000"]
        );
        assert_eq!(quality.label(), "高清");
    }

    /// Spec §10: NATIVE has no max_size and carries max_fps=30 / 20 Mbps.
    #[test]
    fn native_omits_max_size() {
        let quality = ScreencastQuality::Native;
        assert!(quality.max_size().is_none());
        assert_eq!(
            quality.server_argv(),
            ["max_fps=30", "video_bit_rate=20000000"]
        );
        assert_eq!(quality.label(), "原生");
    }
}
