//! Winscope system-trace workspace: start/stop the bundled proxy + viewer
//! service, mirror the browser-driven capture state, and surface degradation
//! reasons. The actual capture configuration and triggers live in the Winscope
//! viewer opened in the system browser; this page never starts a service or
//! browser as a side effect. Port of the legacy Compose Winscope page
//! (ADR 0036) onto the Rust shell; see ADR 0037.

use aps_ui_foundation::{Language, TextKey, typography};
use aps_winscope::{ServiceSnapshot, WinscopeService};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

const POLL_INTERVAL: Duration = Duration::from_millis(500);

pub(crate) struct WinscopeWorkspace {
    language: Language,
    /// Session artifact root for dual-path archival; derived from the Rust data
    /// directory and disabled when it is unavailable.
    archive_root: Option<PathBuf>,
    /// Explicit viewer-closure candidates (checkout layout, env override) passed
    /// to the packaged resolution chain.
    viewer_candidates: Vec<PathBuf>,
    service: Option<Arc<WinscopeService>>,
    snapshot: ServiceSnapshot,
    start_in_flight: bool,
    error: Option<String>,
    generation: u64,
}

impl WinscopeWorkspace {
    pub(crate) fn new(language: Language, data_root: Option<PathBuf>, cx: &mut Context<Self>) -> Self {
        let mut viewer_candidates = Vec::new();
        if let Some(root) = std::env::var_os("APS_WINSCOPE_VIEWER_ROOT") {
            viewer_candidates.push(PathBuf::from(root));
        }
        // Debug builds resolve the closure from the checkout; packaged builds use
        // the executable-relative lookup inside the service.
        if cfg!(debug_assertions) {
            viewer_candidates.push(
                PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                    .join("../aps-winscope/assets/winscope-viewer"),
            );
        }
        let workspace = Self {
            language,
            archive_root: data_root.map(|root| root.join("winscope")),
            viewer_candidates,
            service: None,
            snapshot: ServiceSnapshot::default(),
            start_in_flight: false,
            error: None,
            generation: 0,
        };
        cx.notify();
        workspace
    }

    pub(crate) fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        self.language = language;
        cx.notify();
    }

    fn service_state(&self) -> ServiceState {
        if self
            .service
            .as_ref()
            .map(|service| service.snapshot().running)
            .unwrap_or(false)
        {
            ServiceState::Running
        } else if self.start_in_flight {
            ServiceState::Starting
        } else if self.error.is_some() {
            ServiceState::Failed
        } else {
            ServiceState::Stopped
        }
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        if self.start_in_flight {
            return;
        }
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.start_in_flight = true;
        self.error = None;
        cx.notify();
        let viewer_candidates = self.viewer_candidates.clone();
        let archive_root = self.archive_root.clone();
        let task = cx.background_executor().spawn(async move {
            let transport = aps_winscope::adb::AdbTransport::locate(None)?;
            let root = aps_winscope::resolve_viewer_root(&viewer_candidates)
                .ok_or_else(|| "viewer resource closure not packaged".to_owned())?;
            let manifest = aps_winscope::load_closure_manifest(&root);
            let service = WinscopeService::new(transport, root, manifest, archive_root);
            service.start()?;
            Ok::<_, String>(service)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.start_in_flight = false;
                match result {
                    Ok(service) => {
                        this.service = Some(service);
                        this.snapshot = this
                            .service
                            .as_ref()
                            .map(|service| service.snapshot())
                            .unwrap_or_default();
                        this.spawn_poll(generation, cx);
                    }
                    Err(error) => {
                        this.error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn stop_service(&mut self, cx: &mut Context<Self>) {
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        let Some(service) = self.service.clone() else {
            return;
        };
        let task = cx.background_executor().spawn(async move {
            service.stop();
        });
        cx.spawn(async move |this, cx| {
            task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.snapshot = ServiceSnapshot::default();
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn open_viewer(&mut self, cx: &mut Context<Self>) {
        let Some(service) = self.service.clone() else {
            return;
        };
        let task = cx.background_executor().spawn(async move {
            service.open_viewer()
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Err(error) = result {
                    this.error = Some(error);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Polls the service snapshot so browser-driven capture state mirrors onto
    /// the page without a listener channel (the Kotlin port used a state flow;
    /// the bounded cadence matches the shell's notice sampling). The task ends
    /// when the generation moves or the service stops.
    fn spawn_poll(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(POLL_INTERVAL).await;
            let stop = this
                .update(cx, |this, cx| {
                    if this.generation != generation {
                        return true;
                    }
                    let Some(service) = this.service.clone() else {
                        return true;
                    };
                    service.refresh_capture_state();
                    let snapshot = service.snapshot();
                    if snapshot != this.snapshot {
                        this.snapshot = snapshot.clone();
                        cx.notify();
                    }
                    !snapshot.running
                })
                .unwrap_or(true);
            if stop {
                break;
            }
        })
        .detach();
    }

    fn viewer_url(&self) -> Option<String> {
        self.snapshot.viewer_url.clone()
    }

    fn status_line(&self, language: Language) -> String {
        match self.service_state() {
            ServiceState::Stopped | ServiceState::Failed => {
                language.text(TextKey::WinscopeServiceStopped).to_owned()
            }
            ServiceState::Starting => language.text(TextKey::WinscopeServiceStarting).to_owned(),
            ServiceState::Running => language.format(
                TextKey::WinscopeServiceRunning,
                &[(
                    "port",
                    &self
                        .snapshot
                        .viewer_port
                        .unwrap_or_default()
                        .to_string(),
                )],
            ),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ServiceState {
    Stopped,
    Starting,
    Running,
    Failed,
}

impl Render for WinscopeWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.language;
        let state = self.service_state();
        let capture = self.snapshot.capture.clone();
        let mut body = div()
            .flex()
            .flex_col()
            .gap_3()
            .p_4();

        if let Some(error) = &self.error {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(cx.theme().warning)
                    .child(crate::studio::selectable(language.format(
                        TextKey::WinscopeErrorPrefix,
                        &[("error", error)],
                    ))),
            );
        }

        let mut actions = div().flex().items_center().gap_2();
        match state {
            ServiceState::Stopped | ServiceState::Failed => {
                actions = actions.child(
                    Button::new("winscope-start")
                        .primary()
                        .label(language.text(TextKey::WinscopeStartService))
                        .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                );
            }
            ServiceState::Starting => {
                actions = actions.child(
                    Button::new("winscope-start")
                        .primary()
                        .label(language.text(TextKey::WinscopeStartService))
                        .disabled(true)
                        .on_click(cx.listener(|_, _, _, _| {})),
                );
            }
            ServiceState::Running => {
                actions = actions
                    .child(
                        Button::new("winscope-open")
                            .label(language.text(TextKey::WinscopeOpenViewer))
                            .on_click(cx.listener(|this, _, _, cx| this.open_viewer(cx))),
                    )
                    .child(
                        Button::new("winscope-stop")
                            .label(language.text(TextKey::WinscopeStopService))
                            .on_click(cx.listener(|this, _, _, cx| this.stop_service(cx))),
                    );
            }
        }
        actions = actions.child(
            div()
                .text_size(px(typography::BODY))
                .text_color(cx.theme().muted_foreground)
                .child(self.status_line(language)),
        );
        body = body.child(actions);

        body = body.child(
            div()
                .text_size(px(typography::SUPPORTING))
                .text_color(cx.theme().muted_foreground)
                .child(crate::studio::selectable(
                    language.text(TextKey::WinscopeStopBrowserDrivesCapture),
                )),
        );

        if let Some(url) = self.viewer_url() {
            let token = self.snapshot.session_token.clone().unwrap_or_default();
            body = body
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .child(crate::studio::selectable(
                            language.text(TextKey::WinscopeViewerUrlLabel),
                        )),
                )
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(cx.theme().muted_foreground)
                        .child(crate::studio::selectable(url)),
                )
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .child(crate::studio::selectable(
                            language.text(TextKey::WinscopeTokenLabel),
                        )),
                )
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(cx.theme().muted_foreground)
                        .child(crate::studio::selectable(token)),
                );
        }

        match &capture {
            Some(capture) => {
                body = body.child(
                    div()
                        .text_size(px(typography::BODY))
                        .child(language.format(TextKey::WinscopeCaptureRecording, &[("device", &capture.serial)])),
                );
                if !capture.target_names.is_empty() {
                    let sources = capture.target_names.join(", ");
                    body = body.child(
                        div()
                            .text_size(px(typography::SUPPORTING))
                            .text_color(cx.theme().muted_foreground)
                            .child(crate::studio::selectable(language.format(
                                TextKey::WinscopeCaptureTargets,
                                &[("sources", &sources)],
                            ))),
                    );
                    let path_note = if capture.target_names.iter().any(|name| name == "perfetto_trace") {
                        language.text(TextKey::WinscopeCapturePerfettoPath)
                    } else {
                        language.text(TextKey::WinscopeCaptureLegacyPath)
                    };
                    body = body.child(
                        div()
                            .text_size(px(typography::SUPPORTING))
                            .text_color(cx.theme().muted_foreground)
                            .child(path_note),
                    );
                }
            }
            None => {
                body = body.child(
                    div()
                        .text_size(px(typography::BODY))
                        .child(language.text(TextKey::WinscopeCaptureIdle)),
                );
            }
        }

        if self.snapshot.error_message.is_some() && self.error.is_none() {
            if let Some(error) = &self.snapshot.error_message {
                body = body.child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(cx.theme().warning)
                        .child(crate::studio::selectable(language.format(
                            TextKey::WinscopeErrorPrefix,
                            &[("error", error)],
                        ))),
                );
            }
        }

        body
    }
}
