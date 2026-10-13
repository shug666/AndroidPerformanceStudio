package com.androidperformancestudio.benchmark.cli

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacrobenchmarkProcessRunnerTest {
    @Test
    fun `enforces timeout while process is still running`() {
        val root = Files.createTempDirectory("macrobenchmark-runner")
        val wrapper = root.resolve("gradlew")
        wrapper.writeText("#!/bin/sh\necho started\nsleep 3\n")
        wrapper.toFile().setExecutable(true)
        val command = MacrobenchmarkCommandBuilder.build(MacrobenchmarkExperimentPlan(root))

        val result = MacrobenchmarkProcessRunner(timeoutSeconds = 1).run(command)

        assertTrue(result.timedOut)
        assertTrue(result.output.contains("started"))
        assertFalse(result.cancelled)
        assertFalse(result.succeeded)
    }

    @Test
    fun `stops a running process as soon as the caller cancels`() {
        val root = Files.createTempDirectory("macrobenchmark-cancel")
        val wrapper = root.resolve("gradlew")
        wrapper.writeText("#!/bin/sh\necho started\nsleep 30\n")
        wrapper.toFile().setExecutable(true)
        val command = MacrobenchmarkCommandBuilder.build(MacrobenchmarkExperimentPlan(root))
        val cancel = AtomicBoolean(false)

        val result =
            MacrobenchmarkProcessRunner(timeoutSeconds = 30).run(
                command = command,
                isCancelled = { cancel.get() },
                onOutput = { chunk -> if (chunk.contains("started")) cancel.set(true) },
            )

        assertTrue(result.cancelled)
        assertFalse(result.timedOut)
        assertFalse(result.succeeded)
        assertTrue(result.output.contains("started"))
    }
}
