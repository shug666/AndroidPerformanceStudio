package com.androidperformancestudio.benchmark.cli

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

public data class MacrobenchmarkProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val cancelled: Boolean = false,
) {
    /** True only for a clean exit: a timeout or a cancellation is never reported as success. */
    public val succeeded: Boolean get() = !timedOut && !cancelled && exitCode == 0
}

public class MacrobenchmarkProcessRunner(
    private val timeoutSeconds: Long = 60L * 60L,
) {
    init {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    }

    public fun run(command: MacrobenchmarkCommand): MacrobenchmarkProcessResult = run(command, { false })

    /**
     * Runs [command] while polling [isCancelled], so a desktop caller can stop a Gradle benchmark run
     * instead of waiting for the timeout, and forwards every drained chunk to [onOutput] so the caller
     * can show progress while the run is still going.
     *
     * Draining happens on a dedicated thread: reading the whole stream before waiting would make both
     * the timeout and the cancellation ineffective for a noisy or hung Gradle process. A stopped run
     * is destroyed like a timed-out one and keeps whatever output was already drained.
     */
    public fun run(
        command: MacrobenchmarkCommand,
        isCancelled: () -> Boolean,
        onOutput: ((String) -> Unit)? = null,
    ): MacrobenchmarkProcessResult {
        val process =
            ProcessBuilder(listOf(command.executable.toString()) + command.arguments)
                .directory(command.workingDirectory.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(command.environment) }
                .start()
        val output = StringBuilder()
        val reader =
            Thread({ drain(process, output, onOutput) }, OUTPUT_THREAD_NAME).apply { isDaemon = true }
        reader.start()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var finished = false
        var cancelled = false
        while (!finished) {
            if (process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
            if (isCancelled()) {
                cancelled = true
                break
            }
            if (System.nanoTime() >= deadlineNanos) break
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(FORCED_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        reader.join(OUTPUT_DRAIN_TIMEOUT_MILLIS)
        return MacrobenchmarkProcessResult(
            exitCode = if (finished) process.exitValue() else STOPPED_EXIT_CODE,
            output = synchronized(output) { output.toString() },
            timedOut = !finished && !cancelled,
            cancelled = cancelled,
        )
    }

    private fun drain(
        process: Process,
        output: StringBuilder,
        onOutput: ((String) -> Unit)?,
    ) {
        process.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val chunk = String(buffer, 0, read, StandardCharsets.UTF_8)
                synchronized(output) { output.append(chunk) }
                // A progress listener must never break the drain loop.
                runCatching { onOutput?.invoke(chunk) }
            }
        }
    }

    private companion object {
        const val OUTPUT_THREAD_NAME = "aps-macrobenchmark-output"
        const val OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 200L
        const val FORCED_EXIT_TIMEOUT_SECONDS = 5L
        const val STOPPED_EXIT_CODE = -1
    }
}
