//! Headless screencast probe: runs one real session against a connected device
//! and prints the timings that decide whether mirroring feels live.
//!
//! This exists because the mirroring latency depends on things no unit test can
//! see: when the first frame actually leaves the decoder, how far the display
//! cadence lags the stream, and whether a static screen still produces frames.
//!
//! ```sh
//! cargo run -p aps-screencast --example live_probe -- [SERIAL] [native|hd|fluent] [SECONDS]
//! ```
//!
//! Output is one line per second (frames decoded, fps, worst gap) plus the
//! startup breakdown and a final summary, so a before/after comparison is just
//! two runs.

use aps_screencast::{ScrcpySession, ScrcpySessionState, ScreencastQuality, SessionConfig};
use std::sync::mpsc::RecvTimeoutError;
use std::time::{Duration, Instant};

fn main() {
    let mut args = std::env::args().skip(1);
    let serial = args
        .next()
        .or_else(|| std::env::var("APS_SERIAL").ok())
        .unwrap_or_else(|| "HA2JXKGK".to_owned());
    let quality = match args.next().as_deref() {
        Some("native") | None => ScreencastQuality::Native,
        Some("hd") => ScreencastQuality::Hd,
        Some("fluent") => ScreencastQuality::Fluent,
        Some(other) => {
            eprintln!("unknown quality `{other}` (native|hd|fluent)");
            std::process::exit(2);
        }
    };
    let seconds: u64 = args
        .next()
        .and_then(|value| value.parse().ok())
        .unwrap_or(10);

    println!(
        "probe: serial={serial} quality={label} (max_size={max_size}, {fps} fps, {bitrate} bps) for {seconds}s",
        label = quality.label(),
        max_size = quality
            .max_size()
            .map_or_else(|| "native".to_owned(), |size| size.to_string()),
        fps = quality.max_fps(),
        bitrate = quality.video_bit_rate(),
    );

    let config = SessionConfig::new(serial.clone(), quality);
    let session = match ScrcpySession::new(config) {
        Ok(session) => session,
        Err(error) => {
            eprintln!("unable to create session: {error}");
            std::process::exit(1);
        }
    };

    let started = Instant::now();
    let frames = session.start();
    let running_at: Option<Duration>;
    loop {
        match session.state() {
            ScrcpySessionState::Running { width, height, .. } => {
                let at = started.elapsed();
                running_at = Some(at);
                println!(
                    "state: Running at {:.3}s ({}x{})",
                    at.as_secs_f64(),
                    width,
                    height
                );
                break;
            }
            ScrcpySessionState::Failed(message) => {
                eprintln!("session failed: {message}");
                std::process::exit(1);
            }
            ScrcpySessionState::Disconnected => {
                eprintln!("session disconnected during startup");
                std::process::exit(1);
            }
            _ => {}
        }
        if started.elapsed() > Duration::from_secs(30) {
            eprintln!("startup timed out (state {:?})", session.state());
            std::process::exit(1);
        }
        std::thread::sleep(Duration::from_millis(5));
    }

    // ---- frame stream measurement ------------------------------------------
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let mut first_frame_at: Option<Duration> = None;
    let mut total_frames = 0u64;
    let mut window_frames = 0u32;
    let mut window_start = Instant::now();
    let mut worst_gap = Duration::ZERO;
    let mut last_frame = Instant::now();
    let mut stall_reports = 0u32;
    let mut last_busy_ms = 0u64;

    while Instant::now() < deadline {
        match frames.recv_timeout(Duration::from_millis(250)) {
            Ok(_frame) => {
                let now = Instant::now();
                if first_frame_at.is_none() {
                    first_frame_at = Some(started.elapsed());
                    println!(
                        "first frame at {:.3}s (startup->first frame after Running: {})",
                        started.elapsed().as_secs_f64(),
                        running_at.map_or_else(
                            || "-".to_owned(),
                            |at| format!("{:.3}s", (started.elapsed() - at).as_secs_f64())
                        )
                    );
                }
                let gap = now.saturating_duration_since(last_frame);
                if first_frame_at.is_some() && gap > worst_gap {
                    worst_gap = gap;
                }
                if gap > Duration::from_millis(500) && stall_reports < 8 {
                    stall_reports += 1;
                    println!(
                        "STALL: gap {:.0}ms before frame #{total_frames}",
                        gap.as_secs_f64() * 1000.0
                    );
                }
                last_frame = now;
                total_frames += 1;
                window_frames += 1;
            }
            Err(RecvTimeoutError::Timeout) => {
                if first_frame_at.is_some() && last_frame.elapsed() > Duration::from_secs(1) {
                    // Report quiet stretches (a static screen legitimately sends
                    // nothing; the probe only needs to make the difference visible).
                    window_start = Instant::now();
                    window_frames = 0;
                }
            }
            Err(RecvTimeoutError::Disconnected) => {
                println!("frame channel disconnected (state {:?})", session.state());
                break;
            }
        }
        if window_start.elapsed() >= Duration::from_secs(1) {
            let fps = f64::from(window_frames) / window_start.elapsed().as_secs_f64();
            let metrics = session.metrics();
            let busy = metrics.decode_busy_ms.saturating_sub(last_busy_ms);
            last_busy_ms = metrics.decode_busy_ms;
            println!(
                "t={:>5.1}s  frames={total_frames:<5} fps={fps:5.1}  gap={:.0}ms  au={} dropped={} nudges={} resync={} decode_busy={busy}ms",
                started.elapsed().as_secs_f64(),
                worst_gap.as_secs_f64() * 1000.0,
                metrics.access_units,
                metrics.frames_dropped,
                metrics.nudges,
                metrics.resyncs,
            );
            window_start = Instant::now();
            window_frames = 0;
        }
    }

    let elapsed = started.elapsed();
    session.stop();
    println!("--- summary ---");
    println!(
        "startup            : {:.3}s",
        running_at.unwrap_or_default().as_secs_f64()
    );
    println!(
        "first frame        : {}",
        first_frame_at.map_or_else(
            || "NEVER".to_owned(),
            |at| format!("{:.3}s", at.as_secs_f64())
        )
    );
    println!(
        "frames             : {total_frames} in {:.1}s ({:.1} fps average)",
        elapsed.as_secs_f64(),
        total_frames as f64 / elapsed.as_secs_f64()
    );
    println!(
        "worst inter-frame  : {:.0}ms",
        worst_gap.as_secs_f64() * 1000.0
    );
    let metrics = session.metrics();
    println!("--- pipeline metrics ---");
    println!(
        "phases (from start): launch={}ms connected={}ms handshake={}ms running={}ms",
        metrics.launch_ms, metrics.connected_ms, metrics.handshake_ms, metrics.running_ms
    );
    println!("access units       : {}", metrics.access_units);
    println!(
        "bytes received     : {:.1} MB",
        metrics.bytes_received as f64 / 1e6
    );
    println!("frames decoded     : {}", metrics.frames_decoded);
    println!("frames delivered   : {}", metrics.frames_delivered);
    println!("frames dropped     : {}", metrics.frames_dropped);
    println!(
        "nudges / resyncs   : {} / {}",
        metrics.nudges, metrics.resyncs
    );
    println!("first frame (metric): {} ms", metrics.first_frame_ms);
    let wall = elapsed.as_millis() as u64;
    println!(
        "decode busy        : {} ms of {} ms wall ({:.0}%)",
        metrics.decode_busy_ms,
        wall,
        if wall == 0 {
            0.0
        } else {
            metrics.decode_busy_ms as f64 * 100.0 / wall as f64
        }
    );
    let _ = serial;
}
