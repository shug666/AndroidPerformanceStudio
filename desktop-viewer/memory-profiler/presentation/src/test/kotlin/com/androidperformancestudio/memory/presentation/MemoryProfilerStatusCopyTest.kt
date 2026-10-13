package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MemoryProfilerStatusCopyTest {
    @Test
    fun `status error can be selected and copied without breaking retry`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val clipboard = TestClipboard()
            var retries = 0
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    MemoryProfilerScreen(
                        state =
                            MemoryProfilerState(
                                error = MemoryProfilerError(title = "Capture failed", detail = "PermissionDenied"),
                            ),
                        actions = MemoryProfilerActions(onRetry = { retries++ }),
                    )
                }
            }

            onNodeWithText("PermissionDenied", useUnmergedTree = true).performMouseInput { doubleClick() }
            onRoot().performKeyInput {
                val modifier = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
                keyDown(modifier)
                keyDown(Key.C)
                keyUp(Key.C)
                keyUp(modifier)
            }
            waitForIdle()
            assertEquals("PermissionDenied", clipboard.text)

            onNodeWithText("Retry", useUnmergedTree = true).performClick()
            assertEquals(1, retries)
        }

    private class TestClipboard : Clipboard {
        private var entry: ClipEntry? = null

        val text: String?
            get() = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
    }
}
