package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path

/** System property that pins the Compose inspection agent bundle. */
internal const val COMPOSE_AGENT_BUNDLE_PROPERTY = "agentperf.compose.agent.bundle"

/** Environment fallback for launches where passing a system property is awkward. */
internal const val COMPOSE_AGENT_BUNDLE_ENV = "APC_COMPOSE_AGENT_BUNDLE"

/**
 * Bundle locations searched under the working directory when nothing is configured.
 *
 * The first entry is where a bundle shipped next to the application sits; the second is the layout
 * produced by :layout-inspector:compose-agent-build, which is where a development run started from
 * desktop-viewer/desktop-app finds it. Resolving against the working directory keeps the app
 * independent of how it was launched: a build-script jvmArg cannot do this, because the Compose
 * Desktop plugin assigns the run task's jvmArgs during its own configuration and drops additions.
 */
internal val COMPOSE_AGENT_BUNDLE_CANDIDATES = listOf(
    "compose-agent-bundle",
    "../layout-inspector/compose-agent-build/build/compose-agent-bundle",
)

/**
 * Resolves the agent bundle root. An explicit configuration (system property, then environment)
 * always wins and is resolved against the working directory; otherwise the first candidate that
 * exists is used, falling back to the default location so the failure message names a real path.
 */
internal fun resolveComposeAgentBundle(
    configured: String? =
        System.getProperty(COMPOSE_AGENT_BUNDLE_PROPERTY) ?: System.getenv(COMPOSE_AGENT_BUNDLE_ENV),
    workingDirectory: Path = Path.of("").toAbsolutePath(),
    isDirectory: (Path) -> Boolean = { Files.isDirectory(it) },
): Path {
    configured?.takeIf(String::isNotBlank)?.let { return workingDirectory.resolve(it).normalize() }
    return COMPOSE_AGENT_BUNDLE_CANDIDATES
        .map { workingDirectory.resolve(it).normalize() }
        .firstOrNull(isDirectory)
        ?: workingDirectory.resolve(COMPOSE_AGENT_BUNDLE_CANDIDATES.first()).normalize()
}
