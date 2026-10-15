package com.androidperformancestudio.perfetto.model
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioError
import java.nio.file.Path
import java.time.Instant

/**
 * In-memory buffer fill policy for a Perfetto capture.
 *
 * - [RING_BUFFER]: ring buffer; when full, the oldest trace data is overwritten
 *   (keeps the most recent data). Perfetto default; suits "keep latest activity".
 * - [DISCARD]: stops accepting data once full (keeps the earliest data); suits
 *   "short complete snapshot" but risks tail truncation if the buffer is too
 *   small for the event density.
 */
enum class PerfettoBufferPolicy {
    RING_BUFFER,
    DISCARD,
}

/**
 * Preset trace configuration templates, mirroring Simpleperf's [SamplingTemplate].
 */
enum class PerfettoTraceTemplate(
    val displayName: String,
    val description: String,
) {
    SYSTEM_OVERVIEW("System Overview", "sched, freq, binder, memory, gfx — full system trace"),
    APP_PERFORMANCE("App Performance", "atrace for target app + sched + binder"),
    GFX_PIPELINE("Graphics Pipeline", "SurfaceFlinger, HWUI, frame timeline, vsync"),
    INPUT_LATENCY("Input Latency", "input dispatcher + app response + binder"),
    MEMORY_PROFILE("Memory Profile", "meminfo, heapprofd, Java heap sampling"),
    CUSTOM("Custom", "User-defined trace config"),
}

enum class PerfettoProbeGroup(
    val displayName: String,
) {
    CPU("CPU"),
    IO("I/O"),
    RUNTIME("ART Runtime"),
    MEMORY("Memory"),
    POWER("Power"),
    GPU("GPU"),
    ANDROID("Android apps & services"),
    NETWORK("Network"),
    STACK_SAMPLING("Stack sampling"),
    PERFETTO_SDK("Perfetto SDK"),
    ADVANCED("Advanced"),
}

/**
 * Atrace categories offered for selection, matching the official perfetto UI
 * `ATRACE_CATEGORIES` list. [id] is the value emitted to `ftrace_config.atrace_categories`;
 * [displayName] is the friendly label; [defaultSelected] marks the categories the official
 * UI selects by default (and which form the System Overview template preset).
 */
enum class PerfettoAtraceCategory(
    val id: String,
    val displayName: String,
    val defaultSelected: Boolean,
) {
    ADB("adb", "ADB", defaultSelected = false),
    AIDL("aidl", "AIDL calls", defaultSelected = true),
    AM("am", "Activity Manager", defaultSelected = true),
    AUDIO("audio", "Audio", defaultSelected = false),
    BINDER_DRIVER("binder_driver", "Binder Kernel driver", defaultSelected = true),
    BINDER_LOCK("binder_lock", "Binder global lock trace", defaultSelected = false),
    BIONIC("bionic", "Bionic C Library", defaultSelected = false),
    CAMERA("camera", "Camera", defaultSelected = true),
    DALVIK("dalvik", "Dalvik VM", defaultSelected = true),
    DATABASE("database", "Database", defaultSelected = false),
    DISK("disk", "Disk I/O", defaultSelected = true),
    FREQ("freq", "CPU Frequency", defaultSelected = true),
    GFX("gfx", "Graphics", defaultSelected = true),
    HAL("hal", "Hardware Modules", defaultSelected = true),
    IDLE("idle", "CPU Idle", defaultSelected = true),
    INPUT("input", "Input", defaultSelected = true),
    MEMORY("memory", "Memory", defaultSelected = true),
    MEMRECLAIM("memreclaim", "Kernel Memory Reclaim", defaultSelected = true),
    NETWORK("network", "Network", defaultSelected = true),
    NNAPI("nnapi", "NNAPI", defaultSelected = false),
    PM("pm", "Package Manager", defaultSelected = false),
    POWER("power", "Power Management", defaultSelected = true),
    RES("res", "Resource Loading", defaultSelected = true),
    RRO("rro", "Runtime Resource Overlay", defaultSelected = false),
    RS("rs", "RenderScript", defaultSelected = false),
    SCHED("sched", "CPU Scheduling", defaultSelected = true),
    SM("sm", "Sync Manager", defaultSelected = false),
    SS("ss", "System Server", defaultSelected = true),
    SYNC("sync", "Synchronization", defaultSelected = true),
    THERMAL("thermal", "Thermal event", defaultSelected = true),
    VIBRATOR("vibrator", "Vibrator", defaultSelected = false),
    VIDEO("video", "Video", defaultSelected = false),
    VIEW("view", "View System", defaultSelected = true),
    WEBVIEW("webview", "WebView", defaultSelected = true),
    WM("wm", "Window Manager", defaultSelected = true),
    WORKQ("workq", "Kernel Workqueues", defaultSelected = true),
    ;

    companion object {
        /** All category ids, in the official order. */
        val allIds: List<String> = entries.map(PerfettoAtraceCategory::id)

        /** The default-selected category ids (the System Overview preset). */
        val defaultIds: List<String> = entries.filter(PerfettoAtraceCategory::defaultSelected).map(PerfettoAtraceCategory::id)
    }
}

/**
 * Android log buffers selectable for the `android.log` data source, matching the official
 * `AndroidLogId` values. [id] is the proto enum name emitted to `android_log_config.log_ids`;
 * [displayName] is the friendly label. An empty selection (null/empty) captures all buffers
 * (matching the official default empty `android_log_config`).
 */
enum class PerfettoLogBuffer(
    val id: String,
    val displayName: String,
) {
    LID_DEFAULT("LID_DEFAULT", "Main"),
    LID_RADIO("LID_RADIO", "Radio"),
    LID_EVENTS("LID_EVENTS", "Events"),
    LID_SYSTEM("LID_SYSTEM", "System"),
    LID_CRASH("LID_CRASH", "Crash"),
    LID_STATS("LID_STATS", "Stats"),
    LID_SECURITY("LID_SECURITY", "Security"),
    LID_KERNEL("LID_KERNEL", "Kernel"),
}

/** Android-relevant probes exposed by Perfetto's record page. */
enum class PerfettoProbe(
    val group: PerfettoProbeGroup,
    val displayName: String,
    val minimumAndroidSdk: Int = 0,
    val requiredDataSource: String? = null,
    val requiresTargetPackage: Boolean = false,
) {
    CPU_USAGE(PerfettoProbeGroup.CPU, "Coarse CPU usage", requiredDataSource = "linux.sys_stats"),
    CPU_SCHEDULING(PerfettoProbeGroup.CPU, "Scheduling details", requiredDataSource = "linux.ftrace"),
    CPU_FREQUENCY(PerfettoProbeGroup.CPU, "CPU frequency and idle states", requiredDataSource = "linux.sys_stats"),
    SYSCALLS(PerfettoProbeGroup.CPU, "Syscalls", requiredDataSource = "linux.ftrace"),
    FILESYSTEM_IO(PerfettoProbeGroup.IO, "Filesystem and block I/O", requiredDataSource = "linux.ftrace"),
    PAGE_FAULTS(PerfettoProbeGroup.IO, "Page faults", requiredDataSource = "linux.ftrace"),
    ART_RUNTIME(PerfettoProbeGroup.RUNTIME, "ART GC, JIT and class loading", requiredDataSource = "linux.ftrace"),

    NATIVE_HEAP(
        PerfettoProbeGroup.MEMORY,
        "Native heap profiling",
        minimumAndroidSdk = 29,
        requiredDataSource = "android.heapprofd",
        requiresTargetPackage = true,
    ),
    JAVA_HEAP_DUMP(
        PerfettoProbeGroup.MEMORY,
        "Java heap dumps",
        minimumAndroidSdk = 30,
        requiredDataSource = "android.java_hprof",
        requiresTargetPackage = true,
    ),
    KERNEL_MEMINFO(PerfettoProbeGroup.MEMORY, "Kernel meminfo", requiredDataSource = "linux.sys_stats"),
    VMSTAT(PerfettoProbeGroup.MEMORY, "Virtual memory stats", requiredDataSource = "linux.sys_stats"),
    HIGH_FREQUENCY_MEMORY(PerfettoProbeGroup.MEMORY, "High-frequency memory events", requiredDataSource = "linux.ftrace"),
    LOW_MEMORY_KILLER(PerfettoProbeGroup.MEMORY, "Low memory killer", requiredDataSource = "linux.ftrace"),
    PROCESS_STATS(PerfettoProbeGroup.MEMORY, "Process stats", requiredDataSource = "linux.process_stats"),
    PROCESS_MEMORY_POLL(PerfettoProbeGroup.MEMORY, "Process memory polling", requiredDataSource = "linux.process_stats"),

    POWER_RAILS(PerfettoProbeGroup.POWER, "Battery drain & power rails", requiredDataSource = "android.power"),
    BOARD_VOLTAGES(PerfettoProbeGroup.POWER, "Board voltages & frequencies", requiredDataSource = "linux.ftrace"),
    THERMAL(PerfettoProbeGroup.POWER, "Thermal and throttling signals", requiredDataSource = "linux.ftrace"),

    GPU_FREQUENCY(PerfettoProbeGroup.GPU, "GPU frequency", requiredDataSource = "linux.ftrace"),
    GPU_MEMORY(PerfettoProbeGroup.GPU, "GPU memory", minimumAndroidSdk = 31, requiredDataSource = "android.gpu.memory"),
    GPU_WORK_PERIOD(PerfettoProbeGroup.GPU, "GPU work period", minimumAndroidSdk = 34, requiredDataSource = "linux.ftrace"),
    GPU_RENDER_STAGES(PerfettoProbeGroup.GPU, "GPU render stages", requiredDataSource = "gpu.renderstages"),
    MALI_FENCE_EVENTS(PerfettoProbeGroup.GPU, "Mali fence events", requiredDataSource = "linux.ftrace"),

    ATRACE(PerfettoProbeGroup.ANDROID, "Atrace userspace annotations", requiredDataSource = "linux.ftrace"),
    ANDROID_LOG(PerfettoProbeGroup.ANDROID, "Event log (logcat)", requiredDataSource = "android.log"),
    FRAME_TIMELINE(
        PerfettoProbeGroup.ANDROID,
        "Frame timeline",
        minimumAndroidSdk = 31,
        requiredDataSource = "android.surfaceflinger.frametimeline",
    ),
    GAME_INTERVENTIONS(
        PerfettoProbeGroup.ANDROID,
        "Game intervention list",
        minimumAndroidSdk = 33,
        requiredDataSource = "android.game_interventions",
    ),
    NETWORK_PACKETS(
        PerfettoProbeGroup.ANDROID,
        "Network packet tracing",
        minimumAndroidSdk = 34,
        requiredDataSource = "android.network_packets",
    ),

    WIFI_NETWORK(PerfettoProbeGroup.NETWORK, "Wi-Fi and network events", requiredDataSource = "linux.ftrace"),
    CALLSTACK_SAMPLING(PerfettoProbeGroup.STACK_SAMPLING, "Callstack sampling", requiredDataSource = "linux.perf"),
    TRACK_EVENTS(PerfettoProbeGroup.PERFETTO_SDK, "Track events", requiredDataSource = "track_event"),
    ADVANCED_FTRACE(PerfettoProbeGroup.ADVANCED, "Kernel tracing (ftrace)", requiredDataSource = "linux.ftrace"),
    PROCESS_THREAD_ASSOCIATION(PerfettoProbeGroup.ADVANCED, "Process and thread association", requiredDataSource = "linux.process_stats"),
}

fun PerfettoTraceTemplate.defaultProbes(): Set<PerfettoProbe> =
    when (this) {
        PerfettoTraceTemplate.SYSTEM_OVERVIEW ->
            setOf(
                PerfettoProbe.CPU_USAGE,
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.CPU_FREQUENCY,
                PerfettoProbe.KERNEL_MEMINFO,
                PerfettoProbe.VMSTAT,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.FILESYSTEM_IO,
                PerfettoProbe.PAGE_FAULTS,
                PerfettoProbe.ART_RUNTIME,
                PerfettoProbe.THERMAL,
                PerfettoProbe.ATRACE,
                PerfettoProbe.ANDROID_LOG,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.APP_PERFORMANCE ->
            setOf(
                PerfettoProbe.CPU_USAGE,
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.FILESYSTEM_IO,
                PerfettoProbe.PAGE_FAULTS,
                PerfettoProbe.ART_RUNTIME,
                PerfettoProbe.ATRACE,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.GFX_PIPELINE ->
            setOf(
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.CPU_FREQUENCY,
                PerfettoProbe.GPU_FREQUENCY,
                PerfettoProbe.ATRACE,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.INPUT_LATENCY ->
            setOf(PerfettoProbe.CPU_SCHEDULING, PerfettoProbe.ATRACE)
        PerfettoTraceTemplate.MEMORY_PROFILE ->
            setOf(
                PerfettoProbe.NATIVE_HEAP,
                PerfettoProbe.KERNEL_MEMINFO,
                PerfettoProbe.VMSTAT,
                PerfettoProbe.HIGH_FREQUENCY_MEMORY,
                PerfettoProbe.LOW_MEMORY_KILLER,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.PROCESS_MEMORY_POLL,
                PerfettoProbe.PAGE_FAULTS,
                PerfettoProbe.ART_RUNTIME,
                PerfettoProbe.ATRACE,
            )
        PerfettoTraceTemplate.CUSTOM -> emptySet()
    }

data class PerfettoDeviceCapabilities(
    val androidSdk: Int,
    val buildType: String = "unknown",
    val dataSourceNames: Set<String> = emptySet(),
    val queryError: String? = null,
) {
    fun unsupportedReason(probe: PerfettoProbe): String? {
        val source = probe.requiredDataSource
        val sourceUnavailable =
            queryError == null && dataSourceNames.isNotEmpty() && source != null && source !in dataSourceNames
        val unsupportedBuild =
            probe == PerfettoProbe.SYSCALLS && buildType != "unknown" && buildType !in setOf("userdebug", "eng")
        return when {
            androidSdk > 0 && androidSdk < probe.minimumAndroidSdk -> "Requires Android ${probe.minimumAndroidSdk}+"
            unsupportedBuild -> "Requires a userdebug or eng build"
            sourceUnavailable -> "$source is unavailable on this device"
            else -> null
        }
    }
}

data class PerfettoCaptureConfig(
    val template: PerfettoTraceTemplate,
    val targetPackage: String? = null,
    val durationSeconds: Int = 10,
    val bufferSizeKb: Int = 32768,
    val additionalCategories: List<String> = emptyList(),
    val customConfigText: String? = null,
    val enabledProbes: Set<PerfettoProbe>? = null,
    val bufferPolicy: PerfettoBufferPolicy = PerfettoBufferPolicy.RING_BUFFER,
    val androidSdk: Int = 0,
    val pollIntervals: Map<PerfettoProbe, Int>? = null,
    val atraceCategories: Set<String>? = null,
    val logBuffers: Set<PerfettoLogBuffer>? = null,
) {
    init {
        require(durationSeconds in 1..600) { "durationSeconds must be in [1, 600], was $durationSeconds" }
        require(bufferSizeKb in 1024..1048576) { "bufferSizeKb must be in [1024, 1048576], was $bufferSizeKb" }
        pollIntervals?.values?.forEach {
            require(it > MIN_POLL_INTERVAL_MS) { "poll interval must be > $MIN_POLL_INTERVAL_MS ms, was $it" }
        }
    }

    companion object {
        /** sys_stats poll intervals must be greater than this (proto constraint). */
        const val MIN_POLL_INTERVAL_MS: Int = 10

        /** Default poll intervals per sys-stats probe, used when [pollIntervals] is null/absent. */
        val DEFAULT_POLL_INTERVALS: Map<PerfettoProbe, Int> =
            mapOf(
                PerfettoProbe.CPU_USAGE to 1000,
                PerfettoProbe.CPU_FREQUENCY to 250,
                PerfettoProbe.KERNEL_MEMINFO to 1000,
                PerfettoProbe.VMSTAT to 1000,
            )
    }
}

sealed interface PerfettoCaptureState {
    data object Idle : PerfettoCaptureState

    data class Preparing(
        val config: PerfettoCaptureConfig,
    ) : PerfettoCaptureState

    data class Recording(
        val startTime: Instant,
        val pid: Long,
    ) : PerfettoCaptureState

    data class Pulling(
        val bytesTransferred: Long,
        val totalBytes: Long?,
    ) : PerfettoCaptureState

    data class Completed(
        val traceFile: Path,
        val metadata: CaptureMetadata,
    ) : PerfettoCaptureState

    data class Failed(
        val error: StudioError,
    ) : PerfettoCaptureState
}

data class CaptureMetadata(
    val deviceSerial: String,
    val deviceModel: String,
    val androidSdk: Int,
    val capturedAt: Instant,
    val durationNanos: Long,
    val traceFileSizeBytes: Long,
    val config: PerfettoCaptureConfig,
    val command: String,
    val artifact: CaptureArtifact,
)

data class PerfettoDevice(
    /** Stable ADB identity used for capture commands and selection. */
    val serial: String,
    /** Device model/name without a serial-number suffix. */
    val model: String,
    /** Canonical user-facing label supplied by adb-core; never concatenate this in UI layers. */
    val displayName: String = model,
    val androidSdk: Int = 0,
    val online: Boolean = true,
)
