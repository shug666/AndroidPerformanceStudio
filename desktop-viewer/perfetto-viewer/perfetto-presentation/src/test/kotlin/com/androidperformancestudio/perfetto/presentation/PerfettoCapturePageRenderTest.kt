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
    fun `atrace categories align in three columns when atrace is enabled`() {
        SwingUtilities.invokeAndWait {
            ImageComposeScene(1200, 1600) {
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
                // All atrace category labels must be present (ATRACE is enabled by default in System Overview).
                val categoryLabels =
                    com.androidperformancestudio.perfetto.model.PerfettoAtraceCategory.entries
                        .map { it.displayName }
                val renderedCategoryNodes =
                    categoryLabels.mapNotNull { label ->
                        nodes.firstOrNull { node -> node.text == label }
                    }
                assertEquals(categoryLabels.size, renderedCategoryNodes.size, "all atrace categories must render")

                // Group category nodes by row (same y rounded), then assert each row has <= 3 and
                // same-column nodes across rows share the same x (column alignment).
                val byRow =
                    renderedCategoryNodes
                        .groupBy { (it.positionInRoot.y / 4).toInt() } // tolerance bucket
                        .toSortedMap()
                byRow.values.forEach { row ->
                    assertTrue(row.size <= 3, "atrace row must have at most 3 columns, had ${row.size}")
                }
                // Column alignment: the set of distinct x positions is small (<= 3 columns).
                val columnXs = renderedCategoryNodes.map { it.positionInRoot.x }.distinct()
                assertTrue(columnXs.size <= 3, "atrace categories must align to <= 3 columns, found ${columnXs.size} distinct x positions")

                val path = Path.of("build/reports/trace-layout/atrace-grid.png")
                Files.createDirectories(path.parent)
                scene.render().use { image ->
                    val encoded = checkNotNull(image.encodeToData())
                    encoded.use { Files.write(path, it.bytes) }
                }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

    private val SemanticsNode.text: String?
        get() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
}
