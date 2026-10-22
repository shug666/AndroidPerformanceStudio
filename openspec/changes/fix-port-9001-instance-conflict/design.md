## Context

APS's trace-viewing actions (`Open in Perfetto UI`, `Open with native engine`) bind a fixed port 9001 — the only local origin `ui.perfetto.dev`'s CSP allows. Port 9001 is exclusive: the byte-serving `PerfettoUiServer` and the native `trace_processor_shell` mutually own it, serialized by `openUiMutex`. This works within a single instance. But nothing prevents a *second* APS instance from launching. When it does, the first instance holds 9001 (or a stale process from a crash does), and the second instance's every open action fails to bind with `SERVER_START_FAILED` / `TRACE_PROCESSOR_NOT_READY` — silently, surfacing only as a small error line. The user perceives broken buttons.

The application entry point is `Main.kt`'s `fun main() = application { ... }`. The jpackage native launcher is an opaque ELF binary and cannot be modified, so the guard must live in the JVM entry point.

## Goals / Non-Goals

**Goals:**
- A second launched instance detects an existing instance and exits with a clear message instead of opening a broken window.
- The liveness token is independent of port 9001's stop/start business lifecycle, so it never collides with trace-serving operations.
- The token frees automatically on JVM exit (normal or crash) with no stale-lock cleanup needed.

**Non-Goals:**
- Single-window focus-stealing / bringing the existing window to the foreground (would require IPC between instances; out of scope).
- Migrating the second instance's args (e.g. "open this trace file") to the existing instance (same — needs IPC).
- Changing the 9001 port strategy or adding port-conflict detection inside `PerfettoUiServer` (the guard is upstream).
- Cross-machine / network instance locking (single-machine only; the loopback token is local).

## Decisions

### D1: Liveness token = a bound loopback socket on a fixed dedicated port (9002), held for the JVM lifetime

A `ServerSocket` bound to `127.0.0.1:9002`, opened at entry and never closed explicitly (the JVM holds it until exit). A second instance's bind to 9002 fails → another instance exists.

- Why a socket, not a file lock: a socket is released by the OS the instant the process dies (including `kill -9` / crash / power loss), leaving no stale token. A `FileLock` can survive a hard crash in edge cases and needs self-heal logic (checksum the lock file, etc.), adding complexity. The socket's "process died → port freed" guarantee is exactly the semantics wanted.
- Why a dedicated port (9002), not 9001: 9001 is the business port, repeatedly stopped and restarted by open actions within a single instance. Using it as the liveness token would race those operations and break the stop/start lifecycle. 9002 exists only to signal "an instance is alive" and carries no other traffic.
- Why not port 0 (ephemeral): an ephemeral port can't be re-discovered by a second instance; the token port must be fixed and known.
- Alternative considered: a `FileLock` on `~/.android-performance-studio/app.lock`. Rejected for the stale-after-crash reason above, though it remains a viable fallback if 9002 itself is taken by an unrelated process (see Risks).

### D2: On second-instance detection, show a message and exit — do not open a window

`Main.kt` checks the token before `application { }`. On failure, it shows a modal message ("Another Android Performance Studio instance is already running…") and returns, so no Compose window is created. On a headless environment (no display), it prints to stderr and exits with a non-zero code.

- Why not open a window anyway: the whole point is the second window is broken for trace viewing; opening it reproduces the bug.
- The message is the minimum viable UX. Focus-stealing / "switch to existing window" is a future enhancement (Non-Goal) requiring inter-process IPC.

### D3: Guard placement — before `application { }`, in `main()`

The token is acquired at the very top of `main()`, before any Compose/Compose-window setup. This guarantees no second window is ever constructed. A thin `InstanceGuard` component encapsulates acquire/hold/release-on-exit and is unit-testable with a configurable port (so tests use a free port, not 9002).

### D4: The token port (9002) is documented and dedicated

9002 is declared as a named constant with a comment explaining it is *not* a trace-serving port and must never be reused for business traffic, to prevent future confusion with 9001.

## Risks / Trade-offs

- **[Port 9002 occupied by an unrelated process]** → The guard would falsely report a second APS instance and refuse to start. Mitigation: 9002 is uncommon; document it. If this becomes real, fall back to a `FileLock`-based token (the design isolates the guard behind an interface, so the implementation can swap). Not blocking.
- **[User intentionally runs two instances]** → Now impossible. This was already broken for trace viewing (port conflict); the guard makes the failure explicit instead of silent. The **BREAKING** note in the proposal covers this.
- **[Stale process from a crash holds 9002]** → A crashed APS instance's socket is freed by the OS on process death, so this does not happen for the guard port. (The original bug's stale 9001 holder was a *live* leftover instance, not a crash artifact; this guard prevents that live-leftover scenario by rejecting the second launch.)
- **[No "switch to existing window"]** → The second instance just exits. Acceptable for v1; IPC-based focus is a future enhancement.

## Migration Plan

1. Implement `InstanceGuard` + wire into `Main.kt` (one PR).
2. Manual verify: launch instance A, launch instance B → B shows message and exits; close A → B (relaunch) starts normally; kill -9 A → relaunch starts normally (socket freed).
3. Rollback: revert PR. No data migration, no persisted state (the token is purely runtime).
