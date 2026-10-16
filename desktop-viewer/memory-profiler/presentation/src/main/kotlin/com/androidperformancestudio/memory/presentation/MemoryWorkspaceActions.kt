@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.memory_add_snapshot
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export_diff
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export_object
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export_report
import com.androidperformancestudio.memory.presentation.generated.resources.memory_export_snapshot
import com.androidperformancestudio.memory.presentation.generated.resources.memory_mapping
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryWorkspaceActions(
    state: MemoryProfilerState,
    language: UiLanguage,
    onImport: () -> Unit,
    onMapping: () -> Unit,
    onCompare: () -> Unit,
    onExport: (MemoryWorkspaceExport) -> Unit,
) {
    var exporting by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(LocalViewerColors.current.toolbar)
            .horizontalScroll(
                rememberScrollState(),
            ).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("memory-workspace-actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProfilerCompactButton(
            localizedStringResource(Res.string.memory_add_snapshot, language),
            onImport,
            enabled = !state.isDumping,
        )
        ProfilerCompactButton(
            localizedStringResource(Res.string.memory_mapping, language),
            onMapping,
            enabled =
                state.snapshotSummary != null && !state.isDumping,
        )
        ProfilerCompactButton(
            localizedStringResource(Res.string.memory_compare, language),
            onCompare,
            enabled = state.snapshotSummaries.size >= 2 && !state.isDumping,
            selected = state.viewMode == MemoryProfilerViewMode.Diff,
        )
        Box {
            ProfilerCompactButton(
                localizedStringResource(Res.string.memory_export, language),
                { exporting = true },
                enabled =
                    state.snapshotSummary != null && !state.isDumping,
            )
            DropdownMenu(expanded = exporting, onDismissRequest = { exporting = false }) {
                listOf(
                    MemoryWorkspaceExport.REPORT to Res.string.memory_export_report,
                    MemoryWorkspaceExport.SNAPSHOT to Res.string.memory_export_snapshot,
                    MemoryWorkspaceExport.HISTOGRAM to Res.string.memory_export_histogram,
                    MemoryWorkspaceExport.DIFF to Res.string.memory_export_diff,
                    MemoryWorkspaceExport.OBJECT to Res.string.memory_export_object,
                ).forEach { (kind, label) ->
                    DropdownMenuItem(
                        text = { Text(localizedStringResource(label, language)) },
                        onClick = {
                            exporting = false
                            onExport(kind)
                        },
                        enabled =
                            when (kind) {
                                MemoryWorkspaceExport.DIFF -> state.heapDiff != null
                                MemoryWorkspaceExport.OBJECT -> state.selectedInstanceDetail != null
                                else -> true
                            },
                    )
                }
            }
        }
    }
}
