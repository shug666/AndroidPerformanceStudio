package com.androidperformancestudio.memory.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryWorkspaceStyleTest {
    @Test
    fun `all memory modes share one localized heading before snapshot evidence`() {
        val source = source("MemoryProfilerScreen.kt")
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        assertTrue(source.contains("title = localizedStringResource(Res.string.memory_profiler, language)"))
        assertTrue(source.contains(".testTag(\"memory-workspace-header\")"))
        assertTrue(source.indexOf("StudioWorkspaceHeader(") < source.indexOf("if (presentedState.snapshotSummary"))
        assertTrue(source.indexOf("StudioWorkspaceHeader(") < source.indexOf("when (presentedState.viewMode)"))
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
