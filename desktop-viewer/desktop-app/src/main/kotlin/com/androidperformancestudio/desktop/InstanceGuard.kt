package com.androidperformancestudio.desktop

import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Enforces a single running Android Performance Studio instance by holding a liveness token — a
 * `ServerSocket` bound to the loopback on a fixed, dedicated port — for the JVM's lifetime.
 *
 * The token port ([LIVENESS_TOKEN_PORT]) is deliberately distinct from the 9001 trace-serving port:
 * 9001 is repeatedly stopped and restarted by the "Open in Perfetto UI" / "Open with native engine"
 * actions within a single instance, whereas the liveness token must stay held continuously. The
 * OS releases the socket the instant the process exits (normal close, JVM termination, or crash),
 * so a subsequent launch acquires it cleanly with no stale-lock cleanup.
 *
 * Production code calls [acquire] with [LIVENESS_TOKEN_PORT]; tests call it with a free port to
 * avoid contending for the real token.
 */
class InstanceGuard {
    private var socket: ServerSocket? = null

    val isHeld: Boolean get() = socket != null

    /**
     * Tries to bind the liveness token on [port]. Returns `true` and holds the socket for the
     * remaining JVM lifetime on success; returns `false` (another instance holds it) on failure.
     */
    fun acquire(port: Int): Boolean =
        try {
            socket =
                ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress("127.0.0.1", port))
                }
            true
        } catch (error: Exception) {
            false
        }

    /** Releases the token explicitly (tests use this; production relies on JVM-exit release). */
    fun release() {
        socket?.close()
        socket = null
    }

    companion object {
        /**
         * Dedicated liveness-token port. This port carries NO trace-serving or other business
         * traffic — it exists only to signal "an Android Performance Studio instance is alive".
         * It must remain distinct from the 9001 trace-serving port, whose stop/start lifecycle
         * would race a token held on the same port.
         */
        const val LIVENESS_TOKEN_PORT: Int = 9002
    }
}
