//! Winscope system-trace capture for the Rust desktop: an APS-owned re-implementation
//! of the AOSP Winscope ADB proxy protocol (client version `4.0.8`) plus loopback
//! hosting of the bundled Winscope viewer closure.
//!
//! The capture decision table, device commands, perfetto config text, endpoint set,
//! token/version/CORS headers and the fetch payload encoding follow
//! `docs/winscope-proxy-protocol.md`, which is the source of truth ported from the
//! AOSP `winscope_proxy.py` reference. Any deviation must update that document first.
//!
//! Crate layout:
//! - [`targets`]: device-side trace/dump target registry and paths (wire contract).
//! - [`config`]: perfetto config text fragments appended on the device.
//! - [`adb`]: the blocking adb transport (probe shells, long-running trace shells
//!   with cancellation, streamed fetch).
//! - [`catalog`]: the TTL-cached `/devices` payload backing high-frequency polling.
//! - [`engine`]: start/end/dump/fetch/keep-alive capture engine.
//! - [`proxy`]: loopback proxy HTTP server on the client-fixed port 5544.
//! - [`viewer`]: loopback hosting of the prebuilt viewer closure with checksum
//!   verification.
//! - [`service`]: the facade owning both endpoints, the session token, the
//!   keep-alive watchdog and app-exit cleanup.
//!
//! Mirrors the Kotlin implementation in the removed legacy `desktop-viewer`
//! workspace (ADR 0036); ADR 0037 records the Rust port.

pub mod adb;
pub mod catalog;
pub mod config;
pub mod engine;
pub mod error;
pub mod http;
pub mod proxy;
pub mod service;
pub mod targets;
pub mod viewer;

pub use catalog::{DeviceCatalog, DeviceInfo};
pub use engine::{CaptureEngine, ConfigEntry, TraceRequest};
pub use error::WinscopeError;
pub use proxy::{PROXY_PORT, ProxyServer};
pub use service::{
    load_closure_manifest, resolve_viewer_root, ServiceSnapshot, ServiceState, WinscopeService,
};
pub use targets::PROXY_VERSION;
pub use viewer::{ViewerHost, VIEWER_PREFERRED_PORT};

use std::sync::{Mutex, OnceLock, Weak};

/// Live services registered for app-exit cleanup. The facade registers itself on
/// start and removes itself on stop, mirroring the Kotlin shutdown hook: an
/// application close without an explicit stop still ends in-progress device
/// captures and releases both loopback ports.
static LIVE_SERVICES: OnceLock<Mutex<Vec<Weak<WinscopeService>>>> = OnceLock::new();

pub(crate) fn register_for_cleanup(service: &std::sync::Arc<WinscopeService>) {
    let registry = LIVE_SERVICES.get_or_init(|| Mutex::new(Vec::new()));
    if let Ok(mut live) = registry.lock() {
        live.retain(|weak| weak.upgrade().is_some());
        live.push(std::sync::Arc::downgrade(service));
    }
}

pub(crate) fn unregister_from_cleanup(service: &std::sync::Arc<WinscopeService>) {
    let registry = LIVE_SERVICES.get_or_init(|| Mutex::new(Vec::new()));
    if let Ok(mut live) = registry.lock() {
        live.retain(|weak| weak.upgrade().is_none_or(|live| !std::sync::Arc::ptr_eq(&live, service)));
    }
}

/// Ends in-progress captures and stops both endpoints for every live service.
/// Called from the application quit path; bounded so exit stays responsive.
pub fn shutdown_all() {
    let Some(registry) = LIVE_SERVICES.get() else {
        return;
    };
    let Ok(live) = registry.lock() else {
        return;
    };
    for weak in live.iter() {
        if let Some(service) = weak.upgrade() {
            service.shutdown_cleanup();
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn crate_modules_are_wired() {
        // Compilation of the crate itself is the assertion; this anchor keeps a
        // test target in the crate root for `cargo test -p aps-winscope`.
        assert!(true);
    }
}
