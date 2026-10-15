package com.androidperformancestudio.perfetto.presentation

import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_title
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceAnalyzerWorkspaceLocalizationTest {
    @Test
    fun `trace workspace resource keys stay aligned across English and Chinese`() {
        val english = Files.readString(Path.of("src/main/composeResources/values/strings.xml"))
        val chinese = Files.readString(Path.of("src/main/composeResources/values-zh/strings.xml"))
        val keyPattern = Regex("<string name=\"(trace_workspace_[^\"]+)\"")

        assertEquals(
            keyPattern.findAll(english).map { it.groupValues[1] }.toSet(),
            keyPattern.findAll(chinese).map { it.groupValues[1] }.toSet(),
        )
        assertEquals(
            "04 系统跟踪分析 / Trace Analyzer",
            localizedStringResource(Res.string.trace_workspace_title, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "不可用",
            localizedStringResource(Res.string.trace_workspace_unavailable, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
