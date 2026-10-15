@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.presentation.generated.resources.*
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.studio.StudioWorkspaceHeader

@Composable
public fun GpuAnalysisHubScreen(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId }
    BoxWithConstraints(modifier.fillMaxSize().background(LocalViewerColors.current.workspace)) {
        val compactHeight = maxHeight < StudioTokens.workspaceCompactHeight
        val workspaceScroll = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (compactHeight) Modifier.verticalScroll(workspaceScroll) else Modifier)
                    .padding(StudioTokens.compactContentPadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
        ) {
            HubHeading(state.artifacts.size, language)
            DeviceGraphicsSection(state, actions, language)
            AgiCapabilitySection(state.capability, language)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = ViewerTypography.secondary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = ViewerTypography.secondary) }
            ArtifactWorkspace(
                state,
                actions,
                selectedArtifact,
                language,
                if (compactHeight) Modifier.height(400.dp) else Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HubHeading(artifactCount: Int, language: UiLanguage) {
    StudioWorkspaceHeader(
        title = localizedStringResource(Res.string.gpu_analysis_hub, language),
        subtitle = localizedStringResource(Res.string.tool_responsibility_hint, language),
        supportingText = localizedStringResource(Res.string.artifact_count, language, artifactCount),
        modifier = Modifier.testTag("gpu-workspace-heading"),
    )
}

@Composable
private fun DeviceGraphicsSection(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.device_graphics_context, language),
            actions = {
                DropdownSelector(
                    items = state.devices,
                    selectedItem = selectedDevice,
                    onItemSelected = { it.serial?.let(actions.onSelectDevice) },
                    itemLabel = GpuDeviceContext::selectorLabel,
                    selectedItemLabel = GpuDeviceContext::selectorLabel,
                    placeholder = localizedStringResource(Res.string.select_connected_device, language),
                    modifier = Modifier.widthIn(min = 200.dp, max = 360.dp),
                    enabled = !state.isRefreshingDevices && state.devices.isNotEmpty(),
                    selectorDescription = localizedStringResource(Res.string.device_graphics_context, language),
                )
                TextButton(onClick = actions.onRefreshDevices, enabled = !state.isRefreshingDevices) {
                    Text(localizedStringResource(Res.string.refresh_devices, language))
                }
            },
        )
        if (state.isInspectingDevice || state.isRefreshingDevices) {
            Text(localizedStringResource(Res.string.reading_device_context, language), color = colors.secondaryText)
        }
        if (state.devices.isEmpty() && !state.isRefreshingDevices) {
            Text(localizedStringResource(Res.string.no_connected_devices, language), color = colors.secondaryText)
        }
        state.deviceError?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ContextValue(localizedStringResource(Res.string.gpu_renderer, language), selectedDevice?.gpuRenderer, Modifier.weight(1.3f))
            ContextValue(localizedStringResource(Res.string.gpu_vendor, language), selectedDevice?.gpuVendor, Modifier.weight(1f))
            ContextValue(localizedStringResource(Res.string.gpu_driver, language), selectedDevice?.driverVersion, Modifier.weight(1f))
            ContextValue(
                localizedStringResource(Res.string.graphics_api, language),
                selectedDevice?.graphicsApis?.takeIf { it.isNotEmpty() }?.joinToString { it.displayName(language) },
                Modifier.weight(1f),
            )
            ContextValue(localizedStringResource(Res.string.graphics_implementation, language), selectedDevice?.graphicsImplementation?.name, Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun AgiCapabilitySection(capability: com.androidperformancestudio.gpu.model.AgiCapability?, language: UiLanguage) {
    val colors = LocalViewerColors.current
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioPanelHeader(localizedStringResource(Res.string.agi_capability, language))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ContextValue(localizedStringResource(Res.string.agi_version, language), capability?.version, Modifier.weight(0.8f))
                ContextValue(localizedStringResource(Res.string.launch_mode, language), capability?.launchMode?.displayName(language), Modifier.weight(0.9f))
                ContextValue(localizedStringResource(Res.string.agi_executable, language), capability?.executable?.toString(), Modifier.weight(2f))
            }
            if (capability?.warnings?.isNotEmpty() == true) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    capability.warnings.forEach { warning ->
                        Text(localizedWarning(warning, language), color = colors.secondaryText, style = ViewerTypography.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = ViewerTypography.subsectionTitle, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ContextValue(label: String, value: String?, modifier: Modifier = Modifier) {
    val colors = LocalViewerColors.current
    Column(modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = colors.secondaryText, style = ViewerTypography.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value ?: "—", color = colors.primaryText, style = ViewerTypography.bodyCompact, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun GpuDeviceContext.selectorLabel(): String =
    listOfNotNull(displayName?.takeIf(String::isNotBlank), model?.takeIf(String::isNotBlank), serial?.takeIf(String::isNotBlank))
        .distinct()
        .joinToString(" · ")

internal fun GraphicsApi.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GraphicsApi.VULKAN -> Res.string.graphics_api_vulkan
            GraphicsApi.OPENGL_ES -> Res.string.graphics_api_opengl_es
            GraphicsApi.WEBGPU -> Res.string.graphics_api_webgpu
            GraphicsApi.UNKNOWN -> Res.string.graphics_api_unknown
        },
        language,
    )
