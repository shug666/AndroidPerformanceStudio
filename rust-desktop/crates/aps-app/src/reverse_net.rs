//! Reverse-tethering workspace: share the host network with the device over
//! adb (embedded gnirehtet relay) and surface tunnel events, DNS resolutions
//! and IPv6-leak alerts. Port of the legacy Compose ReverseNetMainPage
//! (port spec `/tmp/aps-port-spec-reversenet.md` §6/§7, ADR 0038); the relay
//! and session state machine live in `aps-reversenet`.

use crate::studio;
use aps_adb::{AdbClient, AdbLocator};
use aps_platform::CancellationToken;
use aps_reversenet::dns::DnsFilterMode;
use aps_reversenet::events::{ReverseNetEvent, ReverseNetEventBus, ReverseNetTransport};
use aps_reversenet::session::{SessionController, SessionState};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};
use std::sync::Arc;
use std::time::Duration;

/// Device discovery cadence (spec §7.5: 2000 ms while the page is visible).
const DEVICE_POLL: Duration = Duration::from_millis(2_000);
/// UI-side event backlog cap (spec §7.5: at most 500 rows, newest first).
const EVENT_LIMIT: usize = 500;

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum ReverseNetRow {
    Connection {
        transport: &'static str,
        label: String,
    },
    State {
        state: String,
    },
    Closed {
        duration_secs: f64,
        bytes_sent: u64,
        bytes_received: u64,
    },
    Dns {
        domain: String,
        addresses: String,
        aaaa_stripped: bool,
    },
    Alert {
        message: String,
    },
}

pub(crate) struct ReverseNetWorkspace {
    language: Language,
    controller: Option<Arc<SessionController>>,
    init_error: Option<String>,
    devices: Vec<String>,
    selected: Option<String>,
    events: Vec<ReverseNetRow>,
    state: SessionState,
    generation: u64,
}

impl ReverseNetWorkspace {
    pub(crate) fn new(language: Language, cx: &mut Context<Self>) -> Self {
        // Production assembly mirrors the Kotlin `buildController`: locate adb,
        // extract the vendored APK and surface a page-level init error instead
        // of failing the shell.
        let bus = Arc::new(ReverseNetEventBus::new());
        let (controller, init_error) = match SessionController::with_defaults(bus) {
            Ok(controller) => (Some(Arc::new(controller)), None),
            Err(error) => (None, Some(error)),
        };
        let mut workspace = Self {
            language,
            controller,
            init_error,
            devices: Vec::new(),
            selected: None,
            events: Vec::new(),
            state: SessionState::Idle,
            generation: 0,
        };
        workspace.refresh_devices(cx);
        cx.notify();
        workspace
    }

    pub(crate) fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        self.language = language;
        cx.notify();
    }

    /// Poll cadence: refresh the device list and the tunnel-event snapshot.
    fn spawn_poll(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(DEVICE_POLL).await;
            let alive = this
                .update(cx, |this, cx| {
                    if this.generation != generation {
                        return false;
                    }
                    this.refresh_devices(cx);
                    this.sync_session(cx);
                    true
                })
                .unwrap_or(false);
            if !alive {
                break;
            }
        })
        .detach();
    }

    fn refresh_devices(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async move {
            let cancel = CancellationToken::new();
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            let client = AdbClient::new(location);
            let devices = client
                .list_devices(&cancel)
                .map_err(|error| error.to_string())?;
            Ok::<_, String>(
                devices
                    .into_iter()
                    .filter(|device| device.is_online())
                    .map(|device| device.serial)
                    .collect::<Vec<_>>(),
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(devices) => {
                        this.devices = devices;
                        let still_there = this
                            .selected
                            .as_ref()
                            .is_some_and(|selected| this.devices.contains(selected));
                        if !still_there {
                            this.selected = this.devices.first().cloned();
                        }
                    }
                    Err(error) => {
                        // The Kotlin page surfaced listing failures as a one-shot
                        // init error; keep them visible but non-fatal.
                        this.init_error = Some(this.language.format(
                            TextKey::ReverseNetListFailed,
                            &[("error", &error)],
                        ));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn sync_session(&mut self, cx: &mut Context<Self>) {
        let Some(controller) = self.controller.as_ref() else {
            return;
        };
        let state = controller.state();
        if state != self.state {
            self.state = state;
            cx.notify();
        }
        let snapshot = controller.events_snapshot(EVENT_LIMIT);
        if snapshot.len() != self.events.len() {
            self.events = snapshot.iter().map(format_event).collect();
            cx.notify();
        }
    }

    fn start(&mut self, isolation_mode: DnsFilterMode, cx: &mut Context<Self>) {
        let Some(controller) = self.controller.clone() else {
            return;
        };
        let Some(serial) = self.selected.clone() else {
            self.init_error = Some(self.language.text(TextKey::ReverseNetNoDevice).to_owned());
            cx.notify();
            return;
        };
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.init_error = None;
        let task = cx.background_executor().spawn(async move {
            controller.start(&serial, isolation_mode)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                if let Err(error) = result {
                    this.state = SessionState::Error(error);
                } else {
                    this.sync_session(cx);
                    this.spawn_poll(generation, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn stop(&mut self, cx: &mut Context<Self>) {
        let Some(controller) = self.controller.clone() else {
            return;
        };
        self.generation = self.generation.wrapping_add(1);
        let task = cx.background_executor().spawn(async move {
            controller.stop();
        });
        cx.spawn(async move |this, cx| {
            task.await;
            this.update(cx, |this, cx| {
                this.sync_session(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn clear_log(&mut self, cx: &mut Context<Self>) {
        self.events.clear();
        cx.notify();
    }

    fn status_text(&self, language: Language) -> String {
        match &self.state {
            SessionState::Idle => language.text(TextKey::ReverseNetIdle).to_owned(),
            SessionState::Starting => language.text(TextKey::ReverseNetStarting).to_owned(),
            SessionState::AwaitingVpnAuthorization => {
                language.text(TextKey::ReverseNetAwaitingVpn).to_owned()
            }
            SessionState::Sharing => language.text(TextKey::ReverseNetSharing).to_owned(),
            SessionState::Stopping => language.text(TextKey::ReverseNetStopping).to_owned(),
            SessionState::Error(message) => {
                format!(
                    "{} {message}",
                    language.text(TextKey::ReverseNetError)
                )
            }
        }
    }
}

/// Event row formatting per the Kotlin `formatEvent` contract (spec §7.4).
fn format_event(event: &ReverseNetEvent) -> ReverseNetRow {
    match event {
        ReverseNetEvent::Connection { transport, src_address, src_port, dst_address, dst_port, .. } => {
            let transport = match transport {
                ReverseNetTransport::Tcp => "TCP",
                ReverseNetTransport::Udp => "UDP",
            };
            ReverseNetRow::Connection {
                transport,
                label: format!("{dst_address}:{dst_port} (from {src_address}:{src_port})"),
            }
        }
        ReverseNetEvent::State { state, .. } => ReverseNetRow::State { state: state.clone() },
        ReverseNetEvent::Bytes { .. } => ReverseNetRow::State {
            state: String::new(),
        },
        ReverseNetEvent::Closed { duration_ns, bytes_sent, bytes_received, .. } => {
            ReverseNetRow::Closed {
                duration_secs: *duration_ns as f64 / 1e9,
                bytes_sent: *bytes_sent,
                bytes_received: *bytes_received,
            }
        }
        ReverseNetEvent::DnsResolved { domain, addresses, aaaa_stripped, .. } => ReverseNetRow::Dns {
            domain: domain.clone(),
            addresses: addresses.join(","),
            aaaa_stripped: *aaaa_stripped,
        },
        ReverseNetEvent::Alert { message, .. } => ReverseNetRow::Alert {
            message: message.clone(),
        },
    }
}

impl Render for ReverseNetWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.language;
        let colors = studio::StudioColors::from_theme(cx);
        let theme = cx.theme().clone();
        let state = self.state.clone();
        let active = !matches!(state, SessionState::Idle | SessionState::Error(_));
        let mut body = studio::page(&colors);

        if let Some(error) = &self.init_error {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.warning)
                    .child(crate::studio::selectable(error.clone())),
            );
        }

        // Device selector row (spec §7.1 block 3).
        let mut device_row = div()
            .flex()
            .flex_wrap()
            .items_center()
            .gap_2()
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(language.text(TextKey::ReverseNetDevice)),
            );
        if self.devices.is_empty() {
            device_row = device_row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(language.text(TextKey::ReverseNetNoDevice)),
            );
        } else {
            for (index, serial) in self.devices.iter().enumerate() {
                let is_selected = self.selected.as_deref() == Some(serial.as_str());
                let serial = serial.clone();
                device_row = device_row.child(
                    Button::new(("reverse-net-device", index))
                        .selected(is_selected)
                        .label(serial.clone())
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = Some(serial.clone());
                            cx.notify();
                        })),
                );
            }
        }
        device_row = device_row.child(
            Button::new("reverse-net-refresh")
                .label(language.text(TextKey::ReverseNetRefresh))
                .on_click(cx.listener(|this, _, _, cx| this.refresh_devices(cx))),
        );

        // Action row: start RELAXED / start ISOLATED / stop (spec §7.1 block 4).
        let has_controller = self.controller.is_some();
        let can_start = has_controller && self.selected.is_some() && !active;
        let can_stop = has_controller
            && !matches!(state, SessionState::Idle | SessionState::Error(_));
        let action_row = div()
            .flex()
            .flex_wrap()
            .items_center()
            .gap_2()
            .child(
                Button::new("reverse-net-start")
                    .primary()
                    .label(language.text(TextKey::ReverseNetStart))
                    .disabled(!can_start)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.start(DnsFilterMode::Relaxed, cx);
                    })),
            )
            .child(
                Button::new("reverse-net-start-isolated")
                    .primary()
                    .label(language.text(TextKey::ReverseNetStartIsolated))
                    .disabled(!can_start)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.start(DnsFilterMode::Isolated, cx);
                    })),
            )
            .child(
                Button::new("reverse-net-stop")
                    .label(language.text(TextKey::ReverseNetStop))
                    .disabled(!can_stop)
                    .on_click(cx.listener(|this, _, _, cx| this.stop(cx))),
            )
            .child(
                div()
                    .text_size(px(typography::BODY))
                    .text_color(theme.muted_foreground)
                    .child(crate::studio::selectable(self.status_text(language))),
            );

        // Tunnel events (spec §7.1 block 5).
        let events_header = div()
            .flex()
            .items_center()
            .justify_between()
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(language.format(
                        TextKey::ReverseNetTunnelEvents,
                        &[("count", &self.events.len().to_string())],
                    )),
            )
            .child(
                Button::new("reverse-net-clear")
                    .label(language.text(TextKey::ReverseNetClearLog))
                    .disabled(self.events.is_empty())
                    .on_click(cx.listener(|this, _, _, cx| this.clear_log(cx))),
            );

        let mut event_rows = div().flex().flex_col().gap(px(2.));
        for row in self.events.iter().rev() {
            let element = match row {
                ReverseNetRow::Connection { transport, label } => div()
                    .text_size(px(typography::SUPPORTING))
                    .child(format!("{transport}  {label}")),
                ReverseNetRow::State { state } => div()
                    .text_size(px(typography::SUPPORTING))
                    .child(format!("  state {state}")),
                ReverseNetRow::Closed { duration_secs, bytes_sent, bytes_received } => div()
                    .text_size(px(typography::SUPPORTING))
                    .child(format!(
                        "  closed  {duration_secs:.1}s  {bytes_sent}B out {bytes_received}B in"
                    )),
                ReverseNetRow::Dns { domain, addresses, aaaa_stripped } => {
                    let suffix = if *aaaa_stripped { " [AAAA stripped]" } else { "" };
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(theme.accent)
                        .child(format!("DNS  {domain} -> {addresses}{suffix}"))
                }
                ReverseNetRow::Alert { message } => div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.warning)
                    .child(crate::studio::selectable(format!("ALERT  {message}"))),
            };
            event_rows = event_rows.child(element);
        }

        body = body
            .child(device_row)
            .child(action_row)
            .child(events_header)
            .child(
                div()
                    .id("reverse-net-events")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .rounded(px(6.))
                    .border_1()
                    .border_color(colors.border)
                    .bg(colors.panel)
                    .p_2()
                    .child(event_rows),
            );
        body
    }
}
