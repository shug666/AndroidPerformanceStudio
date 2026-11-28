//! Screencast quality presets, ported from `ScreencastQuality` (spec §7).
//!
//! The three presets map onto scrcpy-server v3.1 `key=value` options verbatim;
//! the keys are the server's own parser keys (`max_size`, `max_fps`,
//! `video_bit_rate`).
//!
//! The numbers are budgeted against the *host* software decoder, which is what
//! this app uses (see `decoder`): one ffmpeg decode thread sustains ~72 fps at
//! 2136×1280 on a current desktop CPU, and the cost scales with pixel count.
//! The presets therefore keep every option inside that budget instead of
//! asking the device for frames the host cannot present:
//!
//! - 流畅 / FLUENT mirrors 1280 px and 30 fps: a mirror that updates ten times
//!   a second (the legacy preset) reads as broken, and 1280 px is comfortable
//!   for every host.
//! - 高清 / HD mirrors 1600 px at up to 60 fps: the smooth-scrolling preset for
//!   hosts with headroom; at 1600×960 one decode thread still sustains well
//!   over 60 fps.
//! - 原生 / NATIVE keeps the device resolution (no `max_size`) at 30 fps —
//!   downscaling hurts text legibility, and 30 fps fits the one-thread decode
//!   budget at 2136×1280.
//!
//! Bitrates stay near scrcpy's own default (8 Mbps) rather than racing the
//! resolution: the link is adb (USB or Wi-Fi) and the device encoder pays for
//! every megabit, while a still screen costs almost nothing either way.

/// Encoding quality preset selected per session (spec §7).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ScreencastQuality {
    /// 流畅: 1280 / 30 fps / 4 Mbps. For weak hosts and USB 2 links.
    #[default]
    Fluent,
    /// 高清: 1600 / 60 fps / 12 Mbps. Smooth scrolling with host headroom.
    Hd,
    /// 原生: native resolution (no `max_size`) / 30 fps / 12 Mbps. The
    /// interaction surface at full device resolution; opt-in because of the
    /// per-frame texture-upload cost on iGPU hosts.
    Native,
}

impl ScreencastQuality {
    /// Fluent is the default: each promoted mirror frame becomes a
    /// full-resolution GPU texture upload, and at Native (1920x1200) that
    /// churned hundreds of MB/s - enough to hang the display stack on iGPU
    /// hosts mid-cast. Native stays available as an explicit choice.
    pub const DEFAULT: ScreencastQuality = ScreencastQuality::Fluent;

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
            Self::Fluent => 30,
            Self::Hd => 60,
            Self::Native => 30,
        }
    }

    /// `video_bit_rate` server option (§7).
    pub fn video_bit_rate(self) -> u32 {
        match self {
            Self::Fluent => 4_000_000,
            Self::Hd => 12_000_000,
            Self::Native => 12_000_000,
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
    /// `ScreencastQuality.DEFAULT == Fluent`: the default keeps the host-side
    /// texture-upload cost low (4 MB/frame at 1280x800 instead of 9 MB at
    /// Native), which is what froze iGPU hosts during casting.
    #[test]
    fn default_is_fluent() {
        assert_eq!(ScreencastQuality::DEFAULT, ScreencastQuality::Fluent);
        assert_eq!(ScreencastQuality::default(), ScreencastQuality::Fluent);
    }

    /// FLUENT carries max_size=1280 / 30 fps / 4 Mbps: a mirrored screen that
    /// updates ten times a second (the legacy values) is not a mirror.
    #[test]
    fn fluent_server_argv_matches_the_spec_table() {
        let quality = ScreencastQuality::Fluent;
        assert_eq!(
            quality.server_argv(),
            ["max_size=1280", "max_fps=30", "video_bit_rate=4000000"]
        );
        assert_eq!(quality.label(), "流畅");
    }

    /// HD carries max_size=1600 / 60 fps / 12 Mbps.
    #[test]
    fn hd_server_argv_matches_the_spec_table() {
        let quality = ScreencastQuality::Hd;
        assert_eq!(
            quality.server_argv(),
            ["max_size=1600", "max_fps=60", "video_bit_rate=12000000"]
        );
        assert_eq!(quality.label(), "高清");
    }

    /// NATIVE has no max_size and carries max_fps=30 / 12 Mbps.
    #[test]
    fn native_omits_max_size() {
        let quality = ScreencastQuality::Native;
        assert!(quality.max_size().is_none());
        assert_eq!(
            quality.server_argv(),
            ["max_fps=30", "video_bit_rate=12000000"]
        );
        assert_eq!(quality.label(), "原生");
    }

    /// Every preset must stay inside the one-thread software decode budget:
    /// pixel count × fps is the cost driver, and 2136×1280 @ 30 fps is the
    /// measured ceiling (~72 fps of decode capacity at that size).
    #[test]
    fn presets_stay_inside_the_software_decode_budget() {
        let native_pixels = 2136.0 * 1280.0;
        let budget = native_pixels * 30.0 * 1.4; // 40% headroom over the default
        for quality in [
            ScreencastQuality::Fluent,
            ScreencastQuality::Hd,
            ScreencastQuality::Native,
        ] {
            let pixels = match quality.max_size() {
                // max_size caps the *long* side; the device here is 16:10-ish.
                Some(size) => {
                    let scale = f64::from(size) / 2136.0;
                    native_pixels * scale * scale
                }
                None => native_pixels,
            };
            let load = pixels * f64::from(quality.max_fps());
            assert!(
                load <= budget,
                "{} preset needs {load:.0} px·fps, budget is {budget:.0}",
                quality.label()
            );
        }
    }
}
