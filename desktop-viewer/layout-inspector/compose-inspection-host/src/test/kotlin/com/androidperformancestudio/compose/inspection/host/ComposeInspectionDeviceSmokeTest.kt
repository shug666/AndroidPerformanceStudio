package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbProcessRunner
import com.androidperformancestudio.compose.inspection.ComposableNode
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in end-to-end smoke test against a real device.
 *
 * It is skipped unless the environment supplies a serial, the agent bundle and the target package:
 *
 * ```
 * APS_COMPOSE_SMOKE_SERIAL=<serial>
 * APS_COMPOSE_SMOKE_BUNDLE=<abs path to compose-agent-bundle>
 * APS_COMPOSE_SMOKE_PACKAGE=<debuggable package with Compose>
 * ```
 *
 * It exercises exactly the runtime chain the Layout Inspector uses (preflight, injection,
 * inspector resolution, frame capture, on-demand detail) so a failing step is reported here
 * instead of silently degrading in the UI.
 */
class ComposeInspectionDeviceSmokeTest {
    @Test
    fun `attaches to a real device, captures a Compose frame and reads node detail`() {
        val serial = System.getenv("APS_COMPOSE_SMOKE_SERIAL")?.takeIf(String::isNotBlank)
        val bundle = System.getenv("APS_COMPOSE_SMOKE_BUNDLE")?.takeIf(String::isNotBlank)
        val packageName = System.getenv("APS_COMPOSE_SMOKE_PACKAGE")?.takeIf(String::isNotBlank)
        assumeTrue(serial != null && bundle != null && packageName != null) {
            "Set APS_COMPOSE_SMOKE_SERIAL, APS_COMPOSE_SMOKE_BUNDLE and APS_COMPOSE_SMOKE_PACKAGE"
        }

        val runner = AdbProcessRunner()
        val resolver =
            ComposeInspectorArtifactResolver(
                cacheDir = Path.of(System.getProperty("user.home"), ".android-performance-studio", "compose-inspectors"),
            )
        val manager = ComposeInjectionManager(processRunner = runner, artifactResolver = resolver)

        val prepared = manager.preflight(serial!!, packageName!!, Path.of(bundle!!))
        println(
            "[smoke] preflight ok: compose=" + prepared.preflight.composeVersion +
                " api=" + prepared.preflight.apiLevel + " abi=" + prepared.preflight.abi +
                " inspectorSource=" + prepared.preflight.inspectorSource,
        )

        val injected = manager.attach(prepared, ComposeInspectionAuthorization.authorize(prepared.preflight))
        try {
            ComposeLiveSession.open(
                injection = injected,
                artifactResolver = resolver,
                processRunner = runner,
                expectedComposeVersion = checkNotNull(prepared.preflight.composeVersion),
            ).use { session ->
                val capture = session.capture()
                val frame = capture.composeInspection.frame
                val nodes = frame.roots.flatMap { root -> flatten(root.nodes) }
                println(
                    "[smoke] capabilities=" +
                        frame.capabilities.joinToString { it.capability.name + "=" + it.availability.name },
                )
                println("[smoke] roots=" + frame.roots.size + " nodes=" + nodes.size + " mode=" + frame.mode)
                assertTrue(nodes.isNotEmpty()) { "No Compose nodes were returned" }

                // The details pane reads frame.details, so that is what has to carry modifiers.
                val details = frame.details
                val withModifiers = details.values.count { it.modifiers.isNotEmpty() }
                val withParameters = details.values.count { it.parameters.isNotEmpty() }
                println(
                    "[smoke] details=" + details.size + " withModifiers=" + withModifiers +
                        " withParameters=" + withParameters,
                )
                assertTrue(withModifiers + withParameters > 0) {
                    "No node carried modifiers or parameters; the MODIFIER section would stay empty"
                }
            }
        } finally {
            injected.close()
        }
    }

    private fun flatten(nodes: List<ComposableNode>): List<ComposableNode> =
        nodes.flatMap { node -> listOf(node) + flatten(node.children) }
}
