package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioCardTest {
    @Test
    fun `card defaults retain compact insets and content spacing`() =
        runDesktopComposeUiTest {
            var density = 1f
            setContent {
                ViewerTheme(darkTheme = false) {
                    density = LocalDensity.current.density
                    StudioCard(Modifier.testTag("card")) {
                        Box(Modifier.size(24.dp).testTag("first"))
                        Box(Modifier.size(24.dp).testTag("second"))
                    }
                }
            }

            val card = onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            val first = onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("second").fetchSemanticsNode().boundsInRoot
            assertEquals(8f * density, first.left - card.left, 0.5f)
            assertEquals(8f * density, first.top - card.top, 0.5f)
            assertEquals(8f * density, second.top - first.bottom, 0.5f)
            assertEquals(8f * density, card.bottom - second.bottom, 0.5f)
        }

    @Test
    fun `panel uses the Startup compact inset when backed by a shared card`() =
        runDesktopComposeUiTest {
            var density = 1f
            setContent {
                ViewerTheme(darkTheme = true) {
                    density = LocalDensity.current.density
                    StudioPanel(Modifier.testTag("panel")) {
                        Box(Modifier.size(24.dp).testTag("content"))
                    }
                }
            }

            val panel = onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
            val content = onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertEquals(8f * density, content.left - panel.left, 0.5f)
            assertEquals(8f * density, content.top - panel.top, 0.5f)
        }

    @Test
    fun `metric card uses the Startup compact inset when backed by a shared card`() =
        runDesktopComposeUiTest {
            var density = 1f
            setContent {
                ViewerTheme(darkTheme = false) {
                    density = LocalDensity.current.density
                    StudioMetricCard("Captured frames", "42", Modifier.testTag("metric"))
                }
            }

            val metric = onNodeWithTag("metric").fetchSemanticsNode().boundsInRoot
            val label = onNodeWithText("Captured frames").fetchSemanticsNode().boundsInRoot
            assertEquals(8f * density, label.left - metric.left, 0.5f)
            assertEquals(8f * density, label.top - metric.top, 0.5f)
        }

    @Test
    fun `compact table inset reaches the shared surface`() =
        runDesktopComposeUiTest {
            var density = 1f
            setContent {
                ViewerTheme(darkTheme = false) {
                    density = LocalDensity.current.density
                    StudioDataTable(
                        columns = listOf(StudioTableColumn("Column")),
                        modifier = Modifier.testTag("table"),
                        contentPadding = 8.dp,
                    ) {
                        Box(Modifier.size(24.dp).testTag("row"))
                    }
                }
            }

            val table = onNodeWithTag("table").fetchSemanticsNode().boundsInRoot
            val row = onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
            assertEquals(8f * density, row.left - table.left, 0.5f)
            assertEquals(8f * density, table.bottom - row.bottom, 0.5f)
        }

    @Test
    fun `light card derives its surface from the active viewer theme`() = assertThemeSurface(darkTheme = false)

    @Test
    fun `dark card derives its surface from the active viewer theme`() = assertThemeSurface(darkTheme = true)

    @Test
    fun `light card preserves an explicit container foreground without affecting its parent`() = assertContentColor(darkTheme = false)

    @Test
    fun `dark card preserves an explicit container foreground without affecting its parent`() = assertContentColor(darkTheme = true)

    @Test
    fun `card supports compact spacing and centered content within fixed bounds`() =
        runDesktopComposeUiTest {
            var density = 1f
            setContent {
                ViewerTheme(darkTheme = false) {
                    density = LocalDensity.current.density
                    StudioCard(
                        modifier = Modifier.size(120.dp, 60.dp).testTag("card"),
                        contentSpacing = 4.dp,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(16.dp).testTag("first"))
                        Box(Modifier.size(16.dp).testTag("second"))
                    }
                }
            }

            val card = onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            val first = onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("second").fetchSemanticsNode().boundsInRoot
            assertEquals(120f * density, card.width, 0.5f)
            assertEquals(60f * density, card.height, 0.5f)
            assertEquals(card.center.x, first.center.x, 0.5f)
            assertEquals(4f * density, second.top - first.bottom, 0.5f)
        }

    @Test
    fun `card click and child action remain separate`() =
        runDesktopComposeUiTest {
            var cardClicks = 0
            var buttonClicks = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    StudioCard(Modifier.testTag("card").clickable { cardClicks++ }) {
                        Text("Select card")
                        Button(onClick = { buttonClicks++ }) { Text("Open artifact") }
                    }
                }
            }

            onNodeWithText("Select card", useUnmergedTree = true).performClick()
            onNodeWithText("Open artifact").performClick()
            assertEquals(1, cardClicks)
            assertEquals(1, buttonClicks)
        }

    private fun assertThemeSurface(darkTheme: Boolean) =
        runDesktopComposeUiTest {
            var expected = Color.Unspecified
            setContent {
                ViewerTheme(darkTheme = darkTheme) {
                    expected = LocalViewerColors.current.panel
                    StudioCard(Modifier.size(80.dp).testTag("card")) {}
                }
            }

            val pixels = onNodeWithTag("card").captureToImage().toPixelMap()
            assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2])
        }

    private fun assertContentColor(darkTheme: Boolean) =
        runDesktopComposeUiTest {
            var expected = Color.Unspecified
            var parent = Color.Unspecified
            var inherited = Color.Unspecified
            var observed = Color.Unspecified
            setContent {
                ViewerTheme(darkTheme = darkTheme) {
                    expected = LocalViewerColors.current.success
                    parent = LocalViewerColors.current.error
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSecondaryContainer = expected)) {
                        CompositionLocalProvider(LocalContentColor provides parent) {
                            StudioCard(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                observed = LocalContentColor.current
                                Text("Container foreground")
                            }
                            StudioPanel { inherited = LocalContentColor.current }
                        }
                    }
                }
            }

            assertEquals(expected, observed)
            assertEquals(parent, inherited)
        }
}
