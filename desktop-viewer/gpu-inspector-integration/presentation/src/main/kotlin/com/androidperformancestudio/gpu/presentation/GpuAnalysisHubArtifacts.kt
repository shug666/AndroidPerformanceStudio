@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.presentation.generated.resources.*
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
internal fun ArtifactWorkspace(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(localizedStringResource(Res.string.gpu_artifacts, language))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = actions.onOpenTraceAnalyzer) {
                Text(localizedStringResource(Res.string.open_trace_analyzer, language))
            }
        }
        ArtifactTableHeader(language)
        if (state.artifacts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(localizedStringResource(Res.string.no_gpu_artifacts, language), color = colors.secondaryText)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.artifacts, key = GpuArtifact::id) { artifact ->
                    ArtifactTableRow(
                        artifact = artifact,
                        availability = state.availabilityByArtifactId[artifact.id],
                        selected = artifact.id == state.selectedArtifactId,
                        isBusy = state.isBusy,
                        language = language,
                        onSelect = { actions.onSelectArtifact(artifact.id) },
                        onOpen = { actions.onOpenArtifact(artifact) },
                    )
                }
            }
        }
        selectedArtifact?.let { artifact -> ArtifactDetails(artifact, state, actions, language) }
    }
}

@Composable
private fun ArtifactDetails(
    artifact: GpuArtifact,
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val serial = artifact.device?.serial
    val packageName = artifact.packageName
    val relatedArtifacts = state.artifacts.filter { candidate ->
        candidate.id != artifact.id &&
            (
                (serial != null && serial == candidate.device?.serial) ||
                    (packageName != null && packageName == candidate.packageName)
            )
    }
    HorizontalDivider(color = colors.border)
    Row(Modifier.fillMaxWidth().heightIn(max = 190.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ArtifactMetadata(artifact, language, Modifier.weight(1.2f))
        VerticalDivider(color = colors.border)
        RelatedEvidence(relatedArtifacts, actions, language, Modifier.weight(1f))
        ArtifactActions(artifact, state, actions, language)
    }
}

@Composable
private fun ArtifactMetadata(artifact: GpuArtifact, language: UiLanguage, modifier: Modifier = Modifier) {
    val colors = LocalViewerColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SectionTitle(localizedStringResource(Res.string.artifact_metadata, language))
        Text(artifact.path.toString(), style = ViewerTypography.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ContextValue(localizedStringResource(Res.string.artifact_hash, language), artifact.sha256, Modifier.fillMaxWidth())
        Text(
            artifact.notes ?: localizedStringResource(Res.string.no_artifact_notes, language),
            style = ViewerTypography.secondary,
            color = colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RelatedEvidence(
    artifacts: List<GpuArtifact>,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(localizedStringResource(Res.string.related_evidence, language))
        Text(
            localizedStringResource(Res.string.related_evidence_hint, language),
            style = ViewerTypography.dense,
            color = colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artifacts.isEmpty()) {
            Text(localizedStringResource(Res.string.no_related_evidence, language), style = ViewerTypography.secondary, color = colors.secondaryText)
        } else {
            artifacts.take(3).forEach { artifact ->
                TextButton(onClick = { actions.onSelectArtifact(artifact.id) }) {
                    Text("${artifact.kind.displayName(language)} · ${artifact.path.fileName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ArtifactActions(artifact: GpuArtifact, state: GpuIntegrationState, actions: GpuIntegrationActions, language: UiLanguage) {
    val availability = state.availabilityByArtifactId[artifact.id]
    Column(Modifier.widthIn(min = 120.dp, max = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(
            onClick = { actions.onOpenArtifact(artifact) },
            enabled = !state.isBusy && availability?.canOpen == true,
        ) {
            Text(availability?.primaryAction?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language), maxLines = 1)
        }
        TextButton(onClick = { actions.onVerifyArtifact(artifact) }, enabled = !state.isBusy) {
            Text(localizedStringResource(Res.string.verify, language))
        }
        TextButton(onClick = { actions.onRevealArtifact(artifact) }, enabled = !state.isBusy) {
            Text(localizedStringResource(Res.string.show_in_files, language))
        }
        TextButton(onClick = { actions.onRelocateArtifact(artifact) }, enabled = !state.isBusy) {
            Text(localizedStringResource(Res.string.relocate, language))
        }
    }
}

@Composable
private fun ArtifactTableHeader(language: UiLanguage) {
    val colors = LocalViewerColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.sectionBackground).padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtifactCell(localizedStringResource(Res.string.artifact_column_kind, language), 1.1f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_file, language), 1.8f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_device, language), 1.2f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_package, language), 1.4f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_api, language), 0.9f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_captured, language), 1.35f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_status, language), 1.05f, true)
        ArtifactCell(localizedStringResource(Res.string.artifact_column_action, language), 1.1f, true)
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun ArtifactTableRow(
    artifact: GpuArtifact,
    availability: GpuArtifactAvailability?,
    selected: Boolean,
    isBusy: Boolean,
    language: UiLanguage,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val status = availability?.locationStatus?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language)
    Row(
        Modifier.fillMaxWidth().background(if (selected) colors.selectedRow else colors.transparent).clickable(onClick = onSelect).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtifactCell(artifact.kind.displayName(language), 1.1f)
        ArtifactCell(artifact.path.fileName?.toString() ?: artifact.path.toString(), 1.8f)
        ArtifactCell(artifact.device?.displayName ?: artifact.device?.model ?: artifact.device?.serial.orEmpty(), 1.2f)
        ArtifactCell(artifact.packageName.orEmpty(), 1.4f)
        ArtifactCell(artifact.graphicsApi?.displayName(language) ?: localizedStringResource(Res.string.graphics_api_unknown, language), 0.9f)
        ArtifactCell(artifact.capturedAt?.toString().orEmpty(), 1.35f)
        ArtifactCell(status, 1.05f)
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterStart) {
            TextButton(
                onClick = onOpen,
                enabled = !isBusy && availability?.canOpen == true,
            ) {
                Text(
                    availability?.primaryAction?.displayName(language)
                        ?: localizedStringResource(Res.string.unavailable, language),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RowScope.ArtifactCell(text: String, weight: Float, header: Boolean = false) {
    val colors = LocalViewerColors.current
    Text(
        text,
        modifier = Modifier.weight(weight).padding(horizontal = 3.dp),
        style = if (header) ViewerTypography.dense else ViewerTypography.bodyCompact,
        color = if (header) colors.secondaryText else colors.primaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
