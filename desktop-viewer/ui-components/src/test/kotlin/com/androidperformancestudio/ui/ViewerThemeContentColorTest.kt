package com.androidperformancestudio.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class ViewerThemeContentColorTest {
    @Test
    fun `plain profiler text follows dark and light theme switches`() =
        runDesktopComposeUiTest {
            val darkTheme = mutableStateOf(true)
            var contentColor = Color.Unspecified
            var renderedColor = Color.Unspecified
            setContent {
                ViewerTheme(darkTheme = darkTheme.value) {
                    contentColor = LocalContentColor.current
                    Text("Profiler content", onTextLayout = { renderedColor = it.layoutInput.style.color })
                }
            }

            onNodeWithText("Profiler content").assertExists()
            assertEquals(viewerColors(true).primaryText, contentColor)
            assertEquals(contentColor, renderedColor)
            assertNotEquals(Color.Black, renderedColor)

            darkTheme.value = false
            waitForIdle()
            assertEquals(viewerColors(false).primaryText, contentColor)
            assertEquals(contentColor, renderedColor)
        }

    @Test
    fun `dark theme overrides a light parent content color while controls keep their own colors`() =
        runDesktopComposeUiTest {
            var bodyColor = Color.Unspecified
            var explicitColor = Color.Unspecified
            setContent {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    ViewerTheme(darkTheme = true) {
                        Text("Body", onTextLayout = { bodyColor = it.layoutInput.style.color })
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                            Text("Control", onTextLayout = { explicitColor = it.layoutInput.style.color })
                        }
                    }
                }
            }

            onNodeWithText("Body").assertExists()
            assertEquals(viewerColors(true).primaryText, bodyColor)
            assertEquals(viewerMaterialColorScheme(true).onPrimary, explicitColor)
        }

    @Test
    fun `restored viewer context supplies text color across a separate composition`() =
        runDesktopComposeUiTest {
            var context: ViewerThemeContext? = null
            val showRestored = mutableStateOf(false)
            var restoredColor = Color.Unspecified
            setContent {
                if (showRestored.value) {
                    CompositionLocalProvider(LocalContentColor provides Color.Black) {
                        ProvideViewerThemeContext(requireNotNull(context)) {
                            Text("Restored content", onTextLayout = { restoredColor = it.layoutInput.style.color })
                        }
                    }
                } else {
                    ViewerTheme(darkTheme = true) {
                        context = rememberViewerThemeContext()
                        Text("Capture context")
                    }
                }
            }

            onNodeWithText("Capture context").assertExists()
            showRestored.value = true
            waitForIdle()
            onNodeWithText("Restored content").assertExists()
            assertEquals(viewerColors(true).primaryText, restoredColor)
        }
}
