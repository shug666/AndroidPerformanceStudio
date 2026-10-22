## ADDED Requirements

### Requirement: Android Performance Studio SHALL enforce a single running instance

The application SHALL acquire a liveness token (a `ServerSocket` bound to `127.0.0.1` on a fixed, dedicated port distinct from the 9001 trace-serving port) at startup, before any Compose window is constructed, and hold it for the entire JVM lifetime. The token port SHALL be dedicated solely to instance existence and SHALL NOT carry any trace-serving or other business traffic. If the token cannot be acquired (the port is already bound), the application SHALL determine that another instance is already running.

#### Scenario: First instance starts
- **WHEN** the user launches Android Performance Studio and no other instance is running
- **THEN** the application acquires the liveness token and starts normally, composing its window

#### Scenario: Second instance is rejected
- **WHEN** the user launches Android Performance Studio while another instance is already running
- **THEN** the second launch fails to acquire the liveness token, does not open a window, and exits

#### Scenario: Liveness token frees on process exit
- **WHEN** a running instance exits (window close, JVM termination, or crash)
- **THEN** the operating system releases the liveness token socket, and a subsequent launch acquires it and starts normally

### Requirement: A rejected second instance SHALL surface a clear message

When a second instance is detected, the application SHALL inform the user that another instance is already running and that they should use the existing window, then exit without composing a UI. On a graphical environment this SHALL be a user-facing message; on a headless environment it SHALL print to standard error and exit with a non-zero status code.

#### Scenario: Graphical environment
- **WHEN** a second instance is detected and a display is available
- **THEN** the application shows a message stating another instance is already running and exits without opening a window

#### Scenario: Headless environment
- **WHEN** a second instance is detected and no display is available
- **THEN** the application prints the message to standard error and exits with a non-zero status code

### Requirement: The liveness token port SHALL be distinct from and independent of port 9001

The liveness token SHALL bind a port other than 9001, so that the trace-serving port's stop/start lifecycle (the byte server and native engine mutually owning 9001) never races or collides with the instance guard. The token port SHALL be a fixed, documented constant dedicated to instance existence only.

#### Scenario: Trace serving does not affect the liveness token
- **WHEN** the running instance stops and restarts its 9001 trace-serving endpoint (e.g. switching between the byte server and native engine)
- **THEN** the liveness token on its dedicated port remains held and is unaffected
