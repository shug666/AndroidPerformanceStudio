package com.androidperformancestudio.desktop.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.desktop.AppDestination
import com.androidperformancestudio.desktop.AppNavigator
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ModuleTaskManagerOverlayTest {
    @Test
    fun `closing a row evicts that module from the retained set`() =
        runDesktopComposeUiTest(width = 480, height = 540) {
            val navigator = AppNavigator()
            navigator.open(AppDestination.PERFETTO)
            navigator.open(AppDestination.MEMORY_PROFILER)
            navigator.open(AppDestination.FRAME_PROFILER)
            // active = FRAME_PROFILER; retained = [HOME, PERFETTO, MEMORY, FRAME]
            setContent {
                ViewerTheme(darkTheme = false) {
                    ModuleTaskManagerOverlay(navigator = navigator, language = UiLanguage.ENGLISH, onDismiss = {})
                }
            }
            // Wait for composition to settle and assert a close button exists for PERFETTO.
            waitForIdle()
            onNodeWithTag("tm-close-PERFETTO").assertIsDisplayed().performClick()
            waitForIdle()

            assertFalse(navigator.retainedDestinations.contains(AppDestination.PERFETTO))
            assertEquals(AppDestination.FRAME_PROFILER, navigator.destination)
            assertEquals(
                listOf(AppDestination.HOME, AppDestination.MEMORY_PROFILER, AppDestination.FRAME_PROFILER),
                navigator.retainedDestinations,
            )
        }

    @Test
    fun `close all removes every module except home and the active module`() =
        runDesktopComposeUiTest(width = 480, height = 540) {
            val navigator = AppNavigator()
            navigator.open(AppDestination.PERFETTO)
            navigator.open(AppDestination.MEMORY_PROFILER)
            navigator.open(AppDestination.FRAME_PROFILER)
            setContent {
                ViewerTheme(darkTheme = false) {
                    ModuleTaskManagerOverlay(navigator = navigator, language = UiLanguage.ENGLISH, onDismiss = {})
                }
            }
            waitForIdle()
            onNodeWithTag("tm-close-all").assertIsDisplayed().performClick()
            waitForIdle()

            // HOME and the active (FRAME) remain; everything else is evicted.
            assertEquals(AppDestination.FRAME_PROFILER, navigator.destination)
            assertEquals(
                listOf(AppDestination.HOME, AppDestination.FRAME_PROFILER),
                navigator.retainedDestinations,
            )
            assertTrue(navigator.retainedDestinations.contains(AppDestination.HOME))
        }

    @Test
    fun `opening a row navigates to that module`() =
        runDesktopComposeUiTest(width = 480, height = 540) {
            val navigator = AppNavigator()
            navigator.open(AppDestination.PERFETTO)
            navigator.open(AppDestination.MEMORY_PROFILER)
            navigator.open(AppDestination.PERFETTO) // active = PERFETTO, both retained
            setContent {
                ViewerTheme(darkTheme = false) {
                    ModuleTaskManagerOverlay(navigator = navigator, language = UiLanguage.ENGLISH, onDismiss = {})
                }
            }
            waitForIdle()
            onNodeWithTag("tm-open-MEMORY_PROFILER").assertIsDisplayed().performClick()
            waitForIdle()

            assertEquals(AppDestination.MEMORY_PROFILER, navigator.destination)
        }
}
