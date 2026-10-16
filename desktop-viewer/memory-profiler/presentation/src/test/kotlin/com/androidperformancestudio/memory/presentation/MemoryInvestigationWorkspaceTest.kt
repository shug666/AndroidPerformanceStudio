package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapSegment
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryInvestigationWorkspaceTest {
    @Test
    fun `default workspace has filters virtualized classes and separate object inspector`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            var state by mutableStateOf(fixture())
            val selected = mutableListOf<Long>()
            setContent {
                ViewerTheme(darkTheme = true) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onSelectInstance = { selected += it },
                            onMatchCaseChange = { state = state.copy(matchCase = it) },
                        ),
                    )
                }
            }
            onNodeWithTag("memory-investigation-workspace").assertExists()
            val filters = onNodeWithTag("memory-workspace-filters").fetchSemanticsNode().boundsInRoot
            val inspector = onNodeWithTag("memory-object-inspector").fetchSemanticsNode().boundsInRoot
            assertTrue(filters.right < inspector.left)
            assertTrue(inspector.right <= onRoot().fetchSemanticsNode().boundsInRoot.right)
            onNodeWithText("Match case").performClick()
            assertTrue(state.matchCase)
            onNodeWithText("sample.Child #2", useUnmergedTree = true).performClick()
            onNodeWithText("Inbound references").performClick()
            onNodeWithText("sample.Owner #3", useUnmergedTree = true).performClick()
            onNodeWithText("GC Root path").performClick()
            onNodeWithText("1. sample.Root", useUnmergedTree = true).performClick()
            assertEquals(listOf(2L, 3L, 4L), selected)
        }

    @Test
    fun `diff exposes full retained evidence and chosen snapshot pair`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val snapshotA = HeapSnapshotSummary("A", sourceFile = Path.of("before.hprof"))
            val snapshotB = HeapSnapshotSummary("B", sourceFile = Path.of("after.hprof"))
            val compared = mutableListOf<Pair<String, String>>()
            setContent {
                MemoryProfilerScreen(
                    fixture().copy(
                        viewMode = MemoryProfilerViewMode.Diff,
                        snapshotSummaries = listOf(snapshotA, snapshotB),
                        heapDiff =
                            HeapDiff(
                                listOf(
                                    HeapDiffEntry(
                                        "sample.Item",
                                        2,
                                        4,
                                        2,
                                        64,
                                        128,
                                        64,
                                        beforeRetainedSize = 1024,
                                        afterRetainedSize = 4096,
                                    ),
                                ),
                                snapshotA,
                                snapshotB,
                            ),
                    ),
                    MemoryProfilerActions(onCompareSnapshots = { a, b -> compared += a to b }),
                )
            }
            onNodeWithContentDescription("Snapshot A").assertExists()
            onNodeWithContentDescription("Snapshot B").assertExists()
            onNodeWithText("Compare").performClick()
            assertEquals(listOf("A" to "B"), compared)
            onNodeWithText("A Retained").assertExists()
            onNodeWithText("B Retained").assertExists()
            onNodeWithText("Δ Retained").assertExists()
            onNodeWithText("+3.0 KB").assertExists()
            onNodeWithText("A: before.hprof  |  B: after.hprof").assertExists()
        }

    @Test
    fun `workspace captures desktop layouts in both languages and themes`() {
        listOf(1440 to UiLanguage.ENGLISH, 1280 to UiLanguage.SIMPLIFIED_CHINESE).forEach { (width, language) ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = width, height = 860) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            Column(Modifier.fillMaxSize()) {
                                MemoryWorkspaceActions(fixture(), language, {}, {}, {}, {})
                                MemoryProfilerSnapshotTabs(fixture(), {}, {}, language, {})
                                MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language, Modifier.weight(1f))
                            }
                        }
                    }
                    val image = onRoot().captureToImage()
                    assertEquals(width, image.width)
                    val pixels = image.toPixelMap()
                    assertTrue(
                        (50 until 800 step 50)
                            .flatMap { y ->
                                (50 until width step 50).map { x -> pixels[x, y] }
                            }.distinct()
                            .size >
                            3,
                    )
                    val output =
                        Path.of(
                            "build/screenshots/memory-$width-${language.name}-${if (dark) "dark" else "light"}.png",
                        )
                    Files.createDirectories(output.parent)
                    val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until image.height) {
                        for (x in 0 until image.width) {
                            val c = pixels[x, y]
                            bitmap.setRGB(
                                x,
                                y,
                                ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or
                                    ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt(),
                            )
                        }
                    }
                    ImageIO.write(bitmap, "png", output.toFile())
                    val footer = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
                    assertEquals(860f, footer.bottom)
                }
            }
        }
    }

    @Suppress("LongMethod")
    private fun fixture(): MemoryProfilerState {
        val snapshot =
            HeapSnapshotSummary(
                "heap-after.hprof",
                sourceFile = Path.of("heap-after.hprof"),
                objectCount = 412587,
                classCount = 6484,
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
                sourceFileDigest = "sample-sha256",
                format = "JAVA PROFILE 1.0.3",
                idSize = 4,
            )
        val classStats =
            listOf(
                ClassStats(
                    "android.graphics.Bitmap",
                    12481,
                    48L * 1024 * 1024,
                    96L * 1024 * 1024,
                    heapNames = setOf("App"),
                ),
                ClassStats(
                    "java.lang.String",
                    56291,
                    9L * 1024 * 1024,
                    18L * 1024 * 1024,
                    heapNames = setOf("App", "Zygote"),
                ),
            )
        return MemoryProfilerState(
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            activeSnapshotId = snapshot.id,
            summary =
                HeapSummary(
                    412587,
                    256L * 1024 * 1024,
                    6484,
                    listOf(
                        HeapSegment("App", 192L * 1024 * 1024, 300000),
                        HeapSegment("Zygote", 64L * 1024 * 1024, 112587),
                    ),
                ),
            classes = classStats,
            heapBaseClasses = classStats,
            selectedClassifierId = "class:android.graphics.Bitmap",
            availableHeaps = listOf("App", "Zygote"),
            selectedClassName = "android.graphics.Bitmap",
            selectedClassInstances = listOf(MemoryInstanceRow(42, 1, 48, 4096, 3, true)),
            selectedInstanceDetail =
                MemoryInstanceDetail(
                    42,
                    "android.graphics.Bitmap",
                    48,
                    4096,
                    3,
                    false,
                    null,
                    fields = listOf(MemoryInstanceField("mBuffer", "sample.Child #2", 2, "sample.Child")),
                    references = listOf(MemoryInstanceField("owner", "sample.Owner #3", 3, "sample.Owner")),
                    referenceChain = listOf(ObjectReference("staticRoot", 4, "sample.Root")),
                ),
        )
    }
}
