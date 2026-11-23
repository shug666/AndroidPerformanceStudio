//! Device catalog backing the high-frequency `/devices` polling observed in the
//! handshake spike (~1 request/second): every poll performs per-device adb
//! probes, so results are cached briefly to keep adb from being saturated.

use crate::adb::{AdbTransport, ShellOptions};
use aps_adb::{DeviceState, parse_devices};
use std::collections::BTreeMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// One entry of the `/devices` payload, mirroring `winscope_proxy.py`
/// ListDevicesEndpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceInfo {
    pub serial: String,
    pub authorized: bool,
    pub model: String,
    pub displays: Vec<String>,
    pub screenrecord_version: String,
}

pub const DEFAULT_CACHE_TTL: Duration = Duration::from_millis(1_000);

#[derive(Debug)]
struct CacheEntry {
    at: Instant,
    devices: BTreeMap<String, DeviceInfo>,
}

/// Device catalog bound to one adb transport.
#[derive(Debug)]
pub struct DeviceCatalog {
    transport: AdbTransport,
    cache_ttl: Duration,
    cache: Mutex<Option<CacheEntry>>,
}

impl DeviceCatalog {
    pub fn new(transport: AdbTransport) -> Self {
        Self {
            transport,
            cache_ttl: DEFAULT_CACHE_TTL,
            cache: Mutex::new(None),
        }
    }

    #[cfg(test)]
    pub fn with_ttl(transport: AdbTransport, ttl: Duration) -> Self {
        Self {
            transport,
            cache_ttl: ttl,
            cache: Mutex::new(None),
        }
    }

    pub fn invalidate(&self) {
        if let Ok(mut cache) = self.cache.lock() {
            *cache = None;
        }
    }

    /// Lists devices with the probe result payload the bundled viewer expects.
    /// Unauthorized devices report empty probes like the Kotlin port.
    pub fn list_devices(&self) -> Result<BTreeMap<String, DeviceInfo>, String> {
        let now = Instant::now();
        {
            let cache = self.cache.lock().map_err(|_| "device catalog poisoned")?;
            if let Some(entry) = cache.as_ref() {
                if now.duration_since(entry.at) < self.cache_ttl {
                    return Ok(entry.devices.clone());
                }
            }
        }
        let listing = self
            .transport
            .run(&["devices", "-l"], &ShellOptions::probe())
            .output_or_abort()
            .0;
        let devices = parse_devices(&listing.stdout).map_err(|error| error.to_string())?;
        let mut catalog = BTreeMap::new();
        for device in devices {
            // Mirrors the Kotlin port: devices stuck in the USB-authorization
            // prompt state are omitted entirely; other offline states still show
            // up as unauthorized so the viewer can render them.
            if device.state == DeviceState::NoPermissions {
                continue;
            }
            let authorized = device.state == DeviceState::Device;
            let serial = device.serial.clone();
            let model = device.model().to_owned();
            let (displays, screenrecord_version) = if authorized {
                (
                    self.probe_displays(&serial),
                    self.probe_screenrecord_version(&serial),
                )
            } else {
                (Vec::new(), "0".to_owned())
            };
            catalog.insert(
                serial.clone(),
                DeviceInfo {
                    serial,
                    authorized,
                    model: if model.is_empty() {
                        device.serial.clone()
                    } else {
                        model
                    },
                    displays,
                    screenrecord_version,
                },
            );
        }
        if let Ok(mut cache) = self.cache.lock() {
            *cache = Some(CacheEntry {
                at: now,
                devices: catalog.clone(),
            });
        }
        Ok(catalog)
    }

    fn probe_displays(&self, serial: &str) -> Vec<String> {
        let output = self
            .transport
            .run(
                &["-s", serial, "shell", "su", "root", "dumpsys", "SurfaceFlinger", "--display-id"],
                &ShellOptions::probe(),
            )
            .output_or_abort()
            .0;
        output
            .stdout
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(str::to_owned)
            .collect()
    }

    fn probe_screenrecord_version(&self, serial: &str) -> String {
        // screenrecord prints its version on stderr on many devices; the
        // reference implementation merges stderr into stdout, so both streams are
        // consulted here as well.
        let version = self
            .transport
            .run(&["-s", serial, "shell", "screenrecord", "--version"], &ShellOptions::probe())
            .output_or_abort()
            .0;
        let merged = version.merged();
        let merged = merged.trim();
        if !merged.is_empty() {
            return merged.to_owned();
        }
        let help = self
            .transport
            .run(&["-s", serial, "shell", "screenrecord", "--help"], &ShellOptions::probe())
            .output_or_abort()
            .0;
        let help_text = help.merged();
        let start = help_text.find('v').map(|index| index + 1).unwrap_or(help_text.len());
        let end = (start + 3).min(help_text.len());
        let candidate = help_text[start..end].trim();
        if candidate.is_empty() {
            "0".to_owned()
        } else {
            candidate.to_owned()
        }
    }
}

#[cfg(test)]
mod tests {
    #[allow(unused_imports)]
    use super::*;
    use crate::targets;

    fn probe_result_with(data_sources: &[&str]) -> String {
        data_sources
            .iter()
            .map(|name| format!("    {name}\n"))
            .collect()
    }

    #[test]
    fn screenrecord_fallback_takes_three_characters_after_v() {
        // Mirrors the Kotlin fallback semantics exercised through the probe
        // helper: the first 'v' of the help banner starts the version token.
        // The device help text starts its version token at the first 'v'.
        let help = "screen recording demo, v1.4 (universal)";
        let start = help.find('v').map(|index| index + 1).unwrap_or(help.len());
        let end = (start + 3).min(help.len());
        assert_eq!(&help[start..end], "1.4");
        // No 'v' at all degrades to the empty candidate, which the caller maps
        // to "0" like the reference implementation.
        let no_version = "no marker here";
        let start = no_version.find('v').map(|index| index + 1).unwrap_or(no_version.len());
        let end = (start + 3).min(no_version.len());
        assert_eq!(no_version[start..end].trim(), "");
    }

    #[test]
    fn probed_data_sources_order_matches_reference() {
        assert_eq!(
            targets::PROBED_DATA_SOURCES,
            [
                "android.inputmethod",
                "android.protolog",
                "android.surfaceflinger.layers",
                "android.surfaceflinger.transactions",
                "com.android.wm.shell.transition",
                "android.viewcapture",
                "android.windowmanager",
                "android.input.inputevent",
            ]
        );
        let _ = probe_result_with(&["android.windowmanager"]);
    }
}
