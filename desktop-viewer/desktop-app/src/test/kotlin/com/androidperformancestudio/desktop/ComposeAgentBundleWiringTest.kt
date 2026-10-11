package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeAgentBundleWiringTest {
    @Test
    fun `development runs point the Layout Inspector at the in-repo agent bundle`() {
        val buildScript = Files.readString(Path.of("build.gradle.kts"))

        // Only the run task is configured, so the build-machine path cannot reach a distribution.
        assertTrue(buildScript.contains("tasks.withType<JavaExec>().configureEach"))
        assertTrue(buildScript.contains("if (name == \"run\")"))
        assertTrue(buildScript.contains("agentperf.compose.agent.bundle"))
        assertTrue(
            buildScript.contains(
                "../layout-inspector/compose-agent-build/build/compose-agent-bundle",
            ),
        )
        // The Compose DSL jvmArgs are shared with packaging; the bundle path must not be one of them.
        val applicationBlock = buildScript.substringAfter("compose.desktop {").substringBefore("\n}\n")
        assertTrue(!applicationBlock.contains("agentperf.compose.agent.bundle"))
    }
}
