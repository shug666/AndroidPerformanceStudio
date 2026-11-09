package com.androidperformancestudio.winscope.app

/** Lifecycle state of the bundled Winscope proxy + static viewer service. */
public enum class WinscopeServiceState {
    /** Service is not running; only an explicit user action starts it. */
    STOPPED,

    /** Bind/port-validation/closure-verification in progress. */
    STARTING,

    /** Proxy (5544) and static viewer host are up; the viewer can be opened in a browser. */
    RUNNING,

    /** Startup failed; [WinscopePageState.errorMessage] carries the reason. */
    FAILED,
}

/** Last-known device capture status reported by the proxy endpoints. */
public enum class WinscopeCaptureState {
    IDLE,
    RECORDING,
    COLLECTING,
}

/** Immutable snapshot rendered by [WinscopeMainPage]. */
public data class WinscopePageState(
    val serviceState: WinscopeServiceState = WinscopeServiceState.STOPPED,
    val captureState: WinscopeCaptureState = WinscopeCaptureState.IDLE,
    /** Serial of the device the browser currently drives capture on, when known. */
    val captureDeviceSerial: String? = null,
    /** Trace target names the active capture was started with, when known. */
    val captureTargets: List<String> = emptyList(),
    /** True when the running capture went through the legacy (non-perfetto) path. */
    val captureLegacyPath: Boolean = false,
    /** User-facing failure reason (root denied, port occupied, closure mismatch, ...). */
    val errorMessage: String? = null,
    /** Port the static viewer host is serving on when [serviceState] is RUNNING. */
    val viewerPort: Int? = null,
)
