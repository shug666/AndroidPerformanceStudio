package com.androidperformancestudio.memory.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryWorkspaceStyleTest {
    @Test
    fun `all memory modes omit the decorative banner before snapshot evidence`() {
        val source = source("MemoryProfilerScreen.kt")
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.indexOf("if (presentedState.snapshotSummary") >= 0)
        assertTrue(source.indexOf("when (presentedState.viewMode)") >= 0)
        assertTrue(source.indexOf("when (presentedState.viewMode)") < source.indexOf("MemoryProfilerStatusBar("))
        assertTrue(source.contains("StudioTokens.compactContentPadding"))
    }

    @Test
    fun `nested views retain evidence without duplicating workspace headers`() {
        listOf("MemoryProfilerBitmapDumpPage.kt", "MemoryProfilerNativeHeapPage.kt", "MemoryProfilerLeakCanaryPage.kt").forEach { file ->
            assertFalse(source(file).contains("StudioWorkspaceHeader("), file)
        }
        val source = source("MemoryProfilerScreen.kt")
        assertTrue(source.contains("MemoryProfilerClassListPane("))
        assertTrue(source.contains("MemoryProfilerDominatorPane("))
        assertTrue(source.contains("MemoryProfilerDiffPane("))
        assertTrue(source.contains("MemoryProfilerBitmapDumpPage("))
        assertTrue(source.contains("MemoryProfilerNativeHeapPage("))
        assertTrue(source.contains("MemoryProfilerLeakCanaryPage("))
        assertTrue(source.contains(".clickable { expanded = !expanded }"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/memory/presentation", file))
}
