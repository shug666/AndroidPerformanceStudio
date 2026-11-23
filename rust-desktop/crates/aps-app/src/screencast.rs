//! Device screencast workspace: mirror a device screen over the embedded
//! scrcpy-server v3.1 protocol, render decoded BGRA frames on a GPUI canvas
//! and inject touch/scroll/key/text back to the device. Port of the legacy
//! Compose screencast page (see the port spec `/tmp/aps-port-spec-screencast.md`
//! §6/§8 and ADR 0038); session/protocol fidelity lives in `aps-screencast`.

use crate::studio;
use aps_adb::{AdbClient, AdbLocator};
use aps_platform::CancellationToken;
use aps_screencast::session::FrameReceiver;
use aps_screencast::{BgraFrame, ScrcpySession, ScrcpySessionState, ScreencastQuality};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
        input::{Input, InputEvent, InputState},
    },
    prelude::*,
    *,
};
use std::sync::Arc;
use std::time::Duration;

/// Device discovery cadence (the Kotlin controller polled every 2 s; 1 s keeps
/// the picker responsive without saturating adb).
const DEVICE_POLL: Duration = Duration::from_millis(1_000);
/// Keycodes reused from the control contract (spec §2.3).
const KEYCODE_BACK: i32 = 4;
const KEYCODE_HOME: i32 = 3;
const KEYCODE_APP_SWITCH: i32 = 187;
const KEYCODE_POWER: i32 = 26;
const ACTION_DOWN: u8 = 0;
const ACTION_UP: u8 = 1;
const ACTION_MOVE: u8 = 2;
const ACTION_HOVER_MOVE: u8 = 7;
const BUTTON_PRIMARY: i32 = 1;
const BUTTON_SECONDARY: i32 = 2;

#[derive(Clone, PartialEq, Eq)]
struct DeviceOption {
    serial: String,
    display_name: String,
}

/// Contain-fit viewport of the current frame inside the canvas bounds
/// (spec §8.1 `containViewport`).
struct Viewport {
    origin: Point<Pixels>,
    size: Size<Pixels>,
}

pub(crate) struct ScreencastWorkspace {
    language: Language,
    devices: Vec<DeviceOption>,
    selected: Option<String>,
    quality: ScreencastQuality,
    session: Option<Arc<ScrcpySession>>,
    state: ScrcpySessionState,
    frames: Option<FrameReceiver>,
    /// Latest decoded frame promoted to a render image; BGRA bytes are consumed
    /// directly by GPUI (Skia N32 layout, matching the Kotlin pipeline).
    frame: Option<Arc<RenderImage>>,
    /// Decoded frame dimensions (RenderImage exposes no size accessor).
    frame_size: Option<(u32, u32)>,
    screen_off: bool,
    power_key_active: bool,
    error: Option<String>,
    generation: u64,
    canvas_bounds: Option<Bounds<Pixels>>,
    pointer_pressed: bool,
    send_input: Entity<InputState>,
}

impl ScreencastWorkspace {
    pub(crate) fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let send_input = cx.new(|cx| InputState::new(window, cx));
        cx.subscribe_in(&send_input, window, |this, _, event: &InputEvent, window, cx| {
            if matches!(event, InputEvent::PressEnter { .. }) {
                this.send_text(window, cx);
            }
        })
        .detach();
        let mut workspace = Self {
            language,
            devices: Vec::new(),
            selected: None,
            quality: ScreencastQuality::DEFAULT,
            session: None,
            state: ScrcpySessionState::Idle,
            frames: None,
            frame: None,
            frame_size: None,
            screen_off: false,
            power_key_active: false,
            error: None,
            generation: 0,
            canvas_bounds: None,
            pointer_pressed: false,
            send_input,
        };
        workspace.refresh_devices(cx);
        cx.notify();
        workspace
    }

    pub(crate) fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        self.language = language;
        cx.notify();
    }

    /// Bounded-cadence poll: refresh devices and drain one frame per pass.
    fn spawn_poll(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(DEVICE_POLL).await;
            let alive = this
                .update(cx, |this, cx| {
                    if this.generation != generation {
                        return false;
                    }
                    let mut keep_polling = true;
                    let running = matches!(this.state, ScrcpySessionState::Running { .. });
                    if let Some(session) = this.session.clone() {
                        let state = session.state();
                        if state != this.state {
                            this.state = state;
                            cx.notify();
                        }
                        let is_active = session.is_active();
                        drop(session);
                        this.drain_frame(cx);
                        keep_polling = is_active || running;
                    }
                    keep_polling
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
                    .map(|device| {
                        let display_name = device.model().to_owned();
                        DeviceOption {
                            serial: device.serial.clone(),
                            display_name: if display_name.is_empty() {
                                device.serial.clone()
                            } else {
                                display_name
                            },
                        }
                    })
                    .collect::<Vec<_>>(),
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(devices) => {
                        this.devices = devices;
                        this.error = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                // Keep the selection stable; fall back to the first device like
                // the Kotlin controller did.
                let still_there = this
                    .selected
                    .as_ref()
                    .is_some_and(|selected| this.devices.iter().any(|device| &device.serial == selected));
                if !still_there {
                    this.selected = this.devices.first().map(|device| device.serial.clone());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Drains the frame channel and promotes the newest frame to a render
    /// image; extra frames are dropped (the display semantics keep only the
    /// latest frame).
    fn drain_frame(&mut self, cx: &mut Context<Self>) {
        let Some(receiver) = self.frames.as_ref() else {
            return;
        };
        let mut latest = None;
        while let Ok(frame) = receiver.try_recv() {
            latest = Some(frame);
        }
        if let Some(frame) = latest {
            self.frame_size = Some((frame.width, frame.height));
            self.frame = Some(Arc::new(to_render_image(frame)));
            cx.notify();
        }
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        let Some(serial) = self.selected.clone() else {
            self.error = Some(self.language.text(TextKey::ScreencastNoDevice).to_owned());
            cx.notify();
            return;
        };
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.error = None;
        if let Some(session) = self.session.take() {
            session.stop();
        }
        self.frames = None;
        let quality = self.quality;
        let task = cx.background_executor().spawn(async move {
            let config = aps_screencast::SessionConfig::new(serial, quality);
            Ok::<_, String>(Arc::new(ScrcpySession::new(config)?))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    if let Ok(session) = result {
                        session.stop();
                    }
                    return;
                }
                match result {
                    Ok(session) => {
                        this.frames = Some(session.start());
                        this.session = Some(session);
                        this.state = ScrcpySessionState::Connecting;
                        this.screen_off = false;
                        this.power_key_active = false;
                        this.spawn_poll(generation, cx);
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn stop(&mut self, cx: &mut Context<Self>) {
        self.generation = self.generation.wrapping_add(1);
        if let Some(session) = self.session.take() {
            session.stop();
        }
        self.frames = None;
        self.frame = None;
        self.frame_size = None;
        self.state = ScrcpySessionState::Idle;
        cx.notify();
    }

    fn running_size(&self) -> Option<(u32, u32)> {
        match &self.state {
            ScrcpySessionState::Running { width, height, .. } => Some((*width, *height)),
            _ => None,
        }
    }

    /// Contain-fit viewport for the latest frame inside the canvas bounds
    /// (spec §8.1); the `img` element uses `ObjectFit::Contain`, so this
    /// computation mirrors the displayed letterbox rect.
    fn viewport(&self) -> Option<Viewport> {
        let bounds = self.canvas_bounds?;
        let (frame_w, frame_h) = self.frame_size?;
        let canvas_w = f32::from(bounds.size.width);
        let canvas_h = f32::from(bounds.size.height);
        if canvas_w <= 0. || canvas_h <= 0. || frame_w == 0 || frame_h == 0 {
            return None;
        }
        let screen_ratio = frame_w as f32 / frame_h as f32;
        let canvas_ratio = canvas_w / canvas_h;
        let (offset_x, offset_y, width, height) = if screen_ratio > canvas_ratio {
            let width = canvas_w;
            let height = width / screen_ratio;
            (0., (canvas_h - height) / 2., width, height)
        } else {
            let height = canvas_h;
            let width = height * screen_ratio;
            ((canvas_w - width) / 2., 0., width, height)
        };
        Some(Viewport {
            origin: bounds.origin + point(px(offset_x), px(offset_y)),
            size: size(px(width), px(height)),
        })
    }

    /// Canvas → device coordinate mapping (spec §8.1 `mapToScreen`); letterbox
    /// points return None and never inject.
    fn map_to_device(&self, canvas_point: Point<Pixels>) -> Option<(i32, i32)> {
        let viewport = self.viewport()?;
        let (screen_w, screen_h) = self.running_size()?;
        let rel_x = (canvas_point.x - viewport.origin.x) / viewport.size.width;
        let rel_y = (canvas_point.y - viewport.origin.y) / viewport.size.height;
        if !(0.0..=1.0).contains(&f32::from(rel_x)) || !(0.0..=1.0).contains(&f32::from(rel_y)) {
            return None;
        }
        Some((
            (f32::from(rel_x) * screen_w as f32).round() as i32,
            (f32::from(rel_y) * screen_h as f32).round() as i32,
        ))
    }

    fn send_touch(&mut self, action: u8, canvas_point: Point<Pixels>, pressure: f32, buttons: i32) {
        let Some((x, y)) = self.map_to_device(canvas_point) else {
            return;
        };
        if let Some(session) = self.session.as_ref() {
            session.send_touch(action, x, y, pressure, buttons);
        }
    }

    fn send_scroll(&mut self, canvas_point: Point<Pixels>, scroll_x: f32, scroll_y: f32) {
        let Some((x, y)) = self.map_to_device(canvas_point) else {
            return;
        };
        // The UI scroll delta is content-flow positive; scrcpy's positive units
        // move the content up, so negate like the Compose layer did (§8.2).
        if let Some(session) = self.session.as_ref() {
            session.send_scroll(x, y, -scroll_x, -scroll_y);
        }
    }

    fn tap_navigation_key(&mut self, keycode: i32, cx: &mut Context<Self>) {
        if let Some(session) = self.session.as_ref() {
            session.tap_keycode(keycode);
        }
        cx.notify();
    }

    fn toggle_screen_power(&mut self, cx: &mut Context<Self>) {
        if let Some(session) = self.session.as_ref() {
            self.screen_off = !self.screen_off;
            session.set_display_power(!self.screen_off);
        }
        cx.notify();
    }

    fn toggle_power_key(&mut self, cx: &mut Context<Self>) {
        if let Some(session) = self.session.as_ref() {
            self.power_key_active = !self.power_key_active;
            session.tap_keycode(KEYCODE_POWER);
        }
        cx.notify();
    }

    fn send_text(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let text = self.send_input.read(cx).value().to_string();
        if text.trim().is_empty() {
            return;
        }
        if let Some(session) = self.session.as_ref() {
            session.paste_text(&text);
        }
        self.send_input.update(cx, |input, cx| {
            input.set_value("", window, cx);
        });
        cx.notify();
    }

    fn select_quality(&mut self, quality: ScreencastQuality, cx: &mut Context<Self>) {
        if quality == self.quality {
            return;
        }
        self.quality = quality;
        // Encoder options are start-time-only: restart the session when active.
        if self.session.is_some() {
            self.start(cx);
        }
        cx.notify();
    }
}

/// Wraps BGRA bytes into GPUI's render image; the byte order matches GPUI's
/// BGRA render format (Skia N32), so no channel swap is needed.
fn to_render_image(frame: BgraFrame) -> RenderImage {
    let width = frame.width.max(1);
    let height = frame.height.max(1);
    let mut pixels = frame.bgra;
    pixels.resize((width as usize) * (height as usize) * 4, 0);
    let buffer = image::ImageBuffer::from_raw(width, height, pixels)
        .unwrap_or_else(|| image::ImageBuffer::new(width, height));
    RenderImage::new([image::Frame::new(buffer)])
}

impl Render for ScreencastWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.language;
        let colors = studio::StudioColors::from_theme(cx);
        let theme = cx.theme().clone();
        let state = self.state.clone();
        let is_running = matches!(state, ScrcpySessionState::Running { .. });
        let is_connecting = state == ScrcpySessionState::Connecting;
        let frame = self.frame.clone();

        // Bounds probe: capture the canvas rect on first layout so pointer
        // events map onto device coordinates.
        let bounds_handle = cx.entity().downgrade();
        let mut canvas_area = div()
            .id("screencast-canvas")
            .relative()
            .flex_1()
            .min_h(px(280.))
            .overflow_hidden()
            .rounded(px(8.))
            .border_1()
            .border_color(colors.border)
            .bg(colors.panel)
            .child(canvas(
                move |bounds, _, cx: &mut App| {
                    bounds_handle
                        .update(cx, |this: &mut ScreencastWorkspace, _| {
                            this.canvas_bounds = Some(bounds);
                        })
                        .ok();
                },
                |_, _, _, _| {},
            ))
            .when(is_running && frame.is_some(), |area| {
                area.child(
                    img(ImageSource::Render(frame.expect("frame checked")))
                        .object_fit(ObjectFit::Contain)
                        .size_full(),
                )
            })
            .when(is_connecting, |area| {
                area.child(
                    div()
                        .absolute()
                        .size_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            div()
                                .text_size(px(typography::BODY))
                                .text_color(theme.muted_foreground)
                                .child(language.text(TextKey::ScreencastConnecting)),
                        ),
                )
            })
            .when(is_running, |area| {
                area.on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, event: &MouseDownEvent, _, _| {
                        this.pointer_pressed = true;
                        this.send_touch(ACTION_DOWN, event.position, 1.0, BUTTON_PRIMARY);
                    }),
                )
                .on_mouse_down(
                    MouseButton::Right,
                    cx.listener(|this, event: &MouseDownEvent, _, _| {
                        this.send_touch(ACTION_DOWN, event.position, 1.0, BUTTON_SECONDARY);
                    }),
                )
                .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, _| {
                    let (action, pressure, buttons) = if this.pointer_pressed {
                        (ACTION_MOVE, 1.0, BUTTON_PRIMARY)
                    } else {
                        (ACTION_HOVER_MOVE, 0.0, 0)
                    };
                    this.send_touch(action, event.position, pressure, buttons);
                }))
                .on_mouse_up(
                    MouseButton::Left,
                    cx.listener(|this, event: &MouseUpEvent, _, cx| {
                        this.pointer_pressed = false;
                        this.send_touch(ACTION_UP, event.position, 0.0, 0);
                        cx.notify();
                    }),
                )
                .on_mouse_up(
                    MouseButton::Right,
                    cx.listener(|this, event: &MouseUpEvent, _, _| {
                        this.send_touch(ACTION_UP, event.position, 0.0, 0);
                    }),
                )
                .on_scroll(cx.listener(|this, event: &ScrollEvent, _, _| {
                    let (scroll_x, scroll_y) = match event.delta {
                        ScrollDelta::Lines(delta) => (delta.x, delta.y),
                        ScrollDelta::Pixels(delta) => {
                            (f32::from(delta.x) / 40., f32::from(delta.y) / 40.)
                        }
                    };
                    this.send_scroll(event.position, scroll_x, scroll_y);
                }))
            });

        match &state {
            ScrcpySessionState::Idle => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastIdleTitle)),
                        )
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(theme.muted_foreground)
                                .child(language.text(TextKey::ScreencastIdleHint)),
                        )
                        .child(
                            Button::new("screencast-start-overlay")
                                .primary()
                                .label(language.text(TextKey::ScreencastStart))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            ScrcpySessionState::Disconnected => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastDisconnected)),
                        )
                        .child(
                            Button::new("screencast-reconnect")
                                .primary()
                                .label(language.text(TextKey::ScreencastReconnect))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            ScrcpySessionState::Failed(message) => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastFailed)),
                        )
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(theme.warning)
                                .child(crate::studio::selectable(message.clone())),
                        )
                        .child(
                            Button::new("screencast-retry")
                                .primary()
                                .label(language.text(TextKey::ScreencastRetry))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            _ => {}
        }

        let mut body = studio::page(&colors)
            .child(self.toolbar(language, is_running, &theme, cx))
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(crate::studio::selectable(self.status_text(language))),
            );

        if let Some(error) = &self.error {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.warning)
                    .child(crate::studio::selectable(language.format(
                        TextKey::ScreencastErrorPrefix,
                        &[("error", error)],
                    ))),
            );
        }

        body = body.child(canvas_area).child(self.send_bar(language, &colors, is_running, cx));
        body
    }
}

impl ScreencastWorkspace {
    fn toolbar(
        &self,
        language: Language,
        is_running: bool,
        theme: &gpui_kit::component::Theme,
        cx: &Context<Self>,
    ) -> AnyElement {
        let mut row = div().flex().flex_wrap().items_center().gap_2();

        for (label, keycode, key_id) in [
            (TextKey::ScreencastBack, KEYCODE_BACK, "nav-back"),
            (TextKey::ScreencastHome, KEYCODE_HOME, "nav-home"),
            (TextKey::ScreencastRecents, KEYCODE_APP_SWITCH, "nav-recents"),
        ] {
            row = row.child(
                Button::new(key_id)
                    .label(language.text(label))
                    .disabled(!is_running)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.tap_navigation_key(keycode, cx);
                    })),
            );
        }
        row = row
            .child(
                Button::new("screencast-power")
                    .label(language.text(TextKey::ScreencastPowerKey))
                    .disabled(!is_running)
                    .on_click(cx.listener(|this, _, _, cx| this.toggle_power_key(cx))),
            )
            .child(
                Button::new("screencast-screen")
                    .label(if self.screen_off {
                        language.text(TextKey::ScreencastScreenOn)
                    } else {
                        language.text(TextKey::ScreencastScreenOff)
                    })
                    .disabled(!is_running)
                    .on_click(cx.listener(|this, _, _, cx| this.toggle_screen_power(cx))),
            );

        // Device picker: compact toggle buttons over the online devices; the
        // picker is locked while a session runs.
        for (index, device) in self.devices.iter().enumerate() {
            let is_selected = self.selected.as_deref() == Some(device.serial.as_str());
            let serial = device.serial.clone();
            row = row.child(
                Button::new(("screencast-device", index))
                    .selected(is_selected)
                    .label(device.display_name.clone())
                    .disabled(is_running)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.selected = Some(serial.clone());
                        cx.notify();
                    })),
            );
        }
        if self.devices.is_empty() {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(language.text(TextKey::ScreencastNoDevice)),
            );
        }

        row = row.child(
            div()
                .text_size(px(typography::SUPPORTING))
                .text_color(theme.muted_foreground)
                .child(language.text(TextKey::ScreencastQuality)),
        );
        for preset in [
            ScreencastQuality::Fluent,
            ScreencastQuality::Hd,
            ScreencastQuality::Native,
        ] {
            let label = format!("{} · {}fps", preset.label(), preset.max_fps());
            row = row.child(
                Button::new(("screencast-quality", preset as usize))
                    .selected(self.quality == preset)
                    .label(label)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.select_quality(preset, cx);
                    })),
            );
        }
        row = row.child(
            Button::new("screencast-refresh")
                .label(language.text(TextKey::ScreencastRefresh))
                .disabled(is_running)
                .on_click(cx.listener(|this, _, _, cx| this.refresh_devices(cx))),
        );
        if is_running {
            row = row.child(
                Button::new("screencast-stop")
                    .label(language.text(TextKey::ScreencastStop))
                    .on_click(cx.listener(|this, _, _, cx| this.stop(cx))),
            );
        }
        row.into_any_element()
    }

    fn status_text(&self, language: Language) -> String {
        match &self.state {
            ScrcpySessionState::Idle => language.text(TextKey::ScreencastIdleHint).to_owned(),
            ScrcpySessionState::Connecting => {
                language.text(TextKey::ScreencastEstablishing).to_owned()
            }
            ScrcpySessionState::Running { device_name, width, height } => language.format(
                TextKey::ScreencastCasting,
                &[
                    ("device", &device_name),
                    ("width", &width.to_string()),
                    ("height", &height.to_string()),
                ],
            ),
            ScrcpySessionState::Disconnected => {
                language.text(TextKey::ScreencastDisconnected).to_owned()
            }
            ScrcpySessionState::Failed(message) => {
                language.format(TextKey::ScreencastErrorPrefix, &[("error", message)])
            }
        }
    }

    fn send_bar(
        &self,
        language: Language,
        colors: &studio::StudioColors,
        is_running: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        div()
            .flex()
            .items_center()
            .gap_2()
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::ScreencastSendHint)),
            )
            .child(
                Input::new(&self.send_input)
                    .disabled(!is_running)
                    .flex_1()
                    .h(px(28.))
                    .rounded(px(4.))
                    .bg(colors.field)
                    .border_1()
                    .border_color(colors.border),
            )
            .child(
                Button::new("screencast-send")
                    .label(language.text(TextKey::ScreencastSend))
                    .disabled(!is_running)
                    .on_click(cx.listener(|this, _, window, cx| this.send_text(window, cx))),
            )
            .into_any_element()
    }
}
