package com.androidperformancestudio.perfetto.presentation

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoProbeGroup
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfettoCapturePageRenderTest {
    @Test
    fun `category cards render in narrow and wide capture workspaces`() {
        SwingUtilities.invokeAndWait {
            listOf(840 to 700, 1200 to 800).forEach { (width, height) ->
                listOf(false, true).forEach { darkTheme ->
                    ImageComposeScene(width, height) {
                        ViewerTheme(darkTheme = darkTheme) {
                            PerfettoCapturePage(
                                captureState = PerfettoCaptureState.Idle,
                                language = UiLanguage.ENGLISH,
                                onStartCapture = { _, _ -> },
                                onStopCapture = {},
                                onOpenTrace = {},
                            )
                        }
                    }.use { scene ->
                        repeat(3) { scene.render().close() }
                        val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.descendants() }
                        val titles =
                            PerfettoProbeGroup.entries.map { group ->
                                nodes.single { node -> node.text == group.displayName.uppercase() }
                            }
                        titles.zipWithNext().forEach { (previous, next) ->
                            assertTrue(
                                next.positionInRoot.y >= previous.positionInRoot.y + previous.size.height,
                                "Category headings must not overlap at width $width",
                            )
                        }
                        PerfettoProbe.entries.forEach { probe ->
                            val option = nodes.single { node -> node.text == probe.displayName }
                            val title = titles[probe.group.ordinal]
                            assertTrue(option.positionInRoot.y >= title.positionInRoot.y + title.size.height)
                            val nextTitle = titles.getOrNull(probe.group.ordinal + 1)
                            if (nextTitle != null) {
                                assertTrue(option.positionInRoot.y + option.size.height <= nextTitle.positionInRoot.y)
                            }
                        }

                        val path = Path.of("build/reports/trace-layout/categories-$width-${if (darkTheme) "dark" else "light"}.png")
                        Files.createDirectories(path.parent)
                        scene.render().use { image ->
                            val encoded = checkNotNull(image.encodeToData())
                            encoded.use { Files.write(path, it.bytes) }
                        }
                        val screenshot = ImageIO.read(path.toFile())
                        assertEquals(width, screenshot.width)
                        assertEquals(height, screenshot.height)
                        val colors =
                            (0 until width step 8)
                                .flatMap { x ->
                                    (0 until height step 8).map { y -> screenshot.getRGB(x, y) }
                                }.toSet()
                        assertTrue(colors.size > 10, "Rendered workspace must contain content, not a blank surface")
                    }
                }
            }
        }
    }

    @Test
    fun `atrace sub-params live behind a config button not inline`() {
        SwingUtilities.invokeAndWait {
            ImageComposeScene(1200, 800) {
                ViewerTheme(darkTheme = false) {
                    PerfettoCapturePage(
                        captureState = PerfettoCaptureState.Idle,
                        language = UiLanguage.ENGLISH,
                        onStartCapture = { _, _ -> },
                        onStopCapture = {},
                        onOpenTrace = {},
                    )
                }
            }.use { scene ->
                repeat(3) { scene.render().close() }
                val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.descendants() }
                // The atrace probe card is present (ATRACE is enabled by default in System Overview).
                assertTrue(nodes.any { it.text == "Atrace 用户态注解" })
                // The config button (gear) is rendered for the atrace probe.
                assertTrue(nodes.any { it.text == "⚙" })
                // Atrace category labels are NOT rendered inline (they live inside the dialog, which
                // is closed by default) — the data sources panel stays compact.
                val categoryLabels =
                    com.androidperformancestudio.perfetto.model.PerfettoAtraceCategory.entries
                        .map { it.displayName }
                val renderedCategoryNodes =
                    categoryLabels.mapNotNull { label -> nodes.firstOrNull { it.text == label } }
                assertEquals(0, renderedCategoryNodes.size, "atrace categories must not render inline while the dialog is closed")
            }
        }
    }

    @Test
    fun `atrace category dialog renders all categories when open`() {
        SwingUtilities.invokeAndWait {
            ImageComposeScene(700, 600) {
                ViewerTheme(darkTheme = false) {
                    androidx.compose.material3.Surface {
                        AtraceCategoryDialog(
                            initial = com.androidperformancestudio.perfetto.model.PerfettoAtraceCategory.defaultIds.toSet(),
                            onConfirm = {},
                            onDismiss = {},
                        )
                    }
                }
            }.use { scene ->
                repeat(3) { scene.render().close() }
                val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.descendants() }
                val labels =
                    com.androidperformancestudio.perfetto.model.PerfettoAtraceCategory.entries
                        .map { it.displayName }
                val rendered = labels.mapNotNull { l -> nodes.firstOrNull { it.text == l } }
                assertEquals(labels.size, rendered.size, "all atrace categories must render in the dialog")
                assertTrue(nodes.any { it.text == "Atrace categories" })
                assertTrue(nodes.any { it.text == "OK" })
                assertTrue(nodes.any { it.text == "Cancel" })
                assertTrue(nodes.any { it.text == "All" })

                val path = Path.of("build/reports/trace-layout/atrace-dialog.png")
                Files.createDirectories(path.parent)
                scene.render().use { image ->
                    val encoded = checkNotNull(image.encodeToData())
                    encoded.use { Files.write(path, it.bytes) }
                }
            }
        }
    }

    @Test
    fun `recording state renders a single stop button and no second capture button`() {
        SwingUtilities.invokeAndWait {
            val recording =
                PerfettoCaptureState.Recording(
                    startTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    pid = 1234L,
                )
            ImageComposeScene(1200, 800) {
                ViewerTheme(darkTheme = false) {
                    PerfettoCapturePage(
                        captureState = recording,
                        language = UiLanguage.ENGLISH,
                        onStartCapture = { _, _ -> },
                        onStopCapture = {},
                        onOpenTrace = {},
                    )
                }
            }.use { scene ->
                repeat(3) { scene.render().close() }
                val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.descendants() }
                // Exactly one capture control button labeled "Stop Capture" (ENGLISH localization).
                val stopButtons = nodes.filter { it.text == "Stop Capture" }
                assertEquals(1, stopButtons.size, "exactly one Stop Capture button must be present while recording")
                // The start label must NOT be present — the toggle is showing Stop Capture, not Start Capture.
                assertTrue(nodes.none { it.text == "Start Capture" }, "the start label must not appear while recording")
            }
        }
    }

    @Test
    fun `recording state stop button renders in the error color`() {
        SwingUtilities.invokeAndWait {
            val recording =
                PerfettoCaptureState.Recording(
                    startTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    pid = 1234L,
                )
            ImageComposeScene(1200, 800) {
                ViewerTheme(darkTheme = false) {
                    PerfettoCapturePage(
                        captureState = recording,
                        language = UiLanguage.ENGLISH,
                        onStartCapture = { _, _ -> },
                        onStopCapture = {},
                        onOpenTrace = {},
                    )
                }
            }.use { scene ->
                repeat(3) { scene.render().close() }
                val path = Path.of("build/reports/trace-layout/recording-stop.png")
                Files.createDirectories(path.parent)
                scene.render().use { image ->
                    val encoded = checkNotNull(image.encodeToData())
                    encoded.use { Files.write(path, it.bytes) }
                }
                val screenshot = ImageIO.read(path.toFile())
                // The Stop button's border/text use MaterialTheme.colorScheme.error (a red-ish tone).
                // Confirm the rendered frame contains reddish pixels (error color is present).
                val hasErrorRed =
                    (0 until screenshot.width step 4).any { x ->
                        (0 until screenshot.height step 4).any { y ->
                            val rgb = screenshot.getRGB(x, y)
                            val r = (rgb shr 16) and 0xFF
                            val g = (rgb shr 8) and 0xFF
                            val b = rgb and 0xFF
                            r > 150 && g < 120 && b < 120
                        }
                    }
                assertTrue(hasErrorRed, "recording state must render the Stop button in the error color")
            }
        }
    }

    @Test
    fun `preparing and pulling states render a single disabled start button`() {
        SwingUtilities.invokeAndWait {
            val preparing =
                PerfettoCaptureState.Preparing(
                    config = com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig(
                        template = com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    ),
                )
            val pulling = PerfettoCaptureState.Pulling(bytesTransferred = 0L, totalBytes = null)
            listOf("preparing" to preparing, "pulling" to pulling).forEach { (label, state) ->
                ImageComposeScene(1200, 800) {
                    ViewerTheme(darkTheme = false) {
                        PerfettoCapturePage(
                            captureState = state,
                            language = UiLanguage.ENGLISH,
                            onStartCapture = { _, _ -> },
                            onStopCapture = {},
                            onOpenTrace = {},
                        )
                    }
                }.use { scene ->
                    repeat(3) { scene.render().close() }
                    val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.descendants() }
                    // A single Start Capture label is present; the Stop Capture label is not.
                    val startButtons = nodes.filter { it.text == "Start Capture" }
                    assertEquals(1, startButtons.size, "exactly one Start Capture button in $label state")
                    assertTrue(nodes.none { it.text == "Stop Capture" }, "no Stop Capture button in $label state")
                    // The button is disabled during intermediate (uninterruptible) phases. The
                    // Disabled semantics flag lives on the clickable Box (not the Text child), so
                    // assert a disabled control node is present in the unmerged tree.
                    assertTrue(
                        nodes.any { it.config.getOrNull(SemanticsProperties.Disabled) != null },
                        "a disabled control must be present in $label state",
                    )
                }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

    private val SemanticsNode.text: String?
        get() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
}
