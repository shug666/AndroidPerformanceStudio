//! Dumps one real device session's video stream to a file, so decoder and
//! latency work can be done offline against the exact byte stream a device
//! produces (access-unit sizes, keyframe cadence, config packets).
//!
//! ```sh
//! cargo run -p aps-screencast --example dump_stream -- [SERIAL] [SECONDS] [OUT]
//! ```
//!
//! Wire format written: `[4 B big-endian length][8 B ptsAndFlags][payload]`
//! per packet, i.e. the scrcpy video framing with the length in front so the
//! reader needs no length knowledge.

use aps_screencast::launcher::{AdbRunner, LaunchedServer, launch};
use aps_screencast::quality::ScreencastQuality;
use aps_screencast::video_stream::{CONFIG_FLAG, StreamReader};
use std::io::Write;
use std::net::TcpListener;
use std::time::{Duration, Instant};

fn main() {
    let mut args = std::env::args().skip(1);
    let serial = args.next().unwrap_or_else(|| "HA2JXKGK".to_owned());
    let seconds: u64 = args
        .next()
        .and_then(|value| value.parse().ok())
        .unwrap_or(10);
    let out = args
        .next()
        .unwrap_or_else(|| "/tmp/aps-stream.bin".to_owned());

    let runner = match AdbRunner::locate(None) {
        Ok(runner) => runner,
        Err(error) => {
            eprintln!("adb unavailable: {error}");
            std::process::exit(1);
        }
    };
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
    let local_port = listener.local_addr().expect("local addr").port();
    let mut launched = LaunchedServer::generate(local_port);
    if let Err(error) = launch(&runner, &serial, &mut launched, ScreencastQuality::Native) {
        eprintln!("launch failed: {error}");
        std::process::exit(1);
    }
    eprintln!("launched scid={:08x} port={local_port}", launched.scid);

    listener.set_nonblocking(true).expect("nonblocking");
    let deadline = Instant::now() + Duration::from_secs(15);
    let accept = || loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).ok();
                break stream;
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    panic!("accept timed out");
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("accept failed: {error}"),
        }
    };
    let video = accept();
    let _control = accept();
    let mut reader = StreamReader::new(video);
    let (name, metadata) = reader.read_device_meta().expect("device metadata");
    eprintln!(
        "device={name} codec={} {}x{}",
        metadata.codec_name(),
        metadata.width,
        metadata.height
    );

    let mut file = std::fs::File::create(&out).expect("create dump");
    let mut packets = 0u64;
    let mut config_packets = 0u64;
    let mut keyframes = 0u64;
    let mut bytes = 0u64;
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(seconds) {
        match reader.read_packet() {
            Ok(Some(packet)) => {
                packets += 1;
                bytes += packet.payload.len() as u64;
                if packet.is_config() {
                    config_packets += 1;
                } else if packet.is_key_frame() {
                    keyframes += 1;
                }
                let mut framed = Vec::with_capacity(12 + packet.payload.len());
                framed.extend_from_slice(&packet.pts_and_flags.to_be_bytes());
                framed.extend_from_slice(&(packet.payload.len() as u32).to_be_bytes());
                framed.extend_from_slice(&packet.payload);
                let _ = file.write_all(&framed);
                if packets.is_multiple_of(60) {
                    eprintln!(
                        "t={:.1}s packets={packets} bytes={bytes} avg_au={} ",
                        started.elapsed().as_secs_f64(),
                        bytes / packets
                    );
                }
                let _ = config_packets;
                let _ = keyframes;
                let _ = CONFIG_FLAG;
            }
            Ok(None) => {
                eprintln!("stream ended by the device");
                break;
            }
            Err(error) => {
                eprintln!("stream read error: {error}");
                break;
            }
        }
    }
    let _ = file.flush();
    aps_screencast::launcher::cleanup(&runner, &serial, &launched);
    eprintln!(
        "wrote {packets} packets ({bytes} bytes, config={config_packets}, keyframes={keyframes}) to {out}"
    );
}
