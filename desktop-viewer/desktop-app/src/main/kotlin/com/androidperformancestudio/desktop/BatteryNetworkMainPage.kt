@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.battery.app.BatteryProfilerMainPage
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.network.app.NetworkProfilerMainPage
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.SegmentedSelector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.studio.StudioWorkspaceHeader

internal enum class BatteryNetworkTab {
    BATTERY,
    NETWORK,
    CORRELATION,
}

/**
 * Product-level Battery & Network workspace.
 *
 * The child pages remain composed so their controllers and in-progress capture state stay
 * independent and survive tab switches. Correlation deliberately remains a separate evidence
 * projection; it never merges the Battery and Network domain models.
 */
@Composable
internal fun FrameWindowScope.BatteryNetworkMainPage(
    language: UiLanguage,
    onBack: () -> Unit,
    isActive: Boolean = true,
) {
    BatteryNetworkWorkspace(
        language = language,
        isActive = isActive,
        batteryContent = { BatteryProfilerMainPage(language = language, onBack = onBack) },
        networkContent = { NetworkProfilerMainPage(language = language, onBack = onBack) },
    )
}

@Composable
internal fun BatteryNetworkWorkspace(
    language: UiLanguage,
    isActive: Boolean = true,
    batteryContent: @Composable () -> Unit,
    networkContent: @Composable () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(BatteryNetworkTab.BATTERY) }

    Column(
        modifier = Modifier.fillMaxSize().background(LocalViewerColors.current.workspace).testTag("battery-network-workspace"),
    ) {
        BatteryNetworkWorkspaceHeader(
            selectedTab = selectedTab,
            onSelectedTabChanged = { selectedTab = it },
            language = language,
        )
        Box(Modifier.fillMaxSize()) {
            BatteryNetworkRetainedLayer(
                active = selectedTab == BatteryNetworkTab.BATTERY,
                menuBarActive = isActive && selectedTab == BatteryNetworkTab.BATTERY,
                testTag = "battery-workspace-layer",
            ) {
                batteryContent()
            }
            BatteryNetworkRetainedLayer(
                active = selectedTab == BatteryNetworkTab.NETWORK,
                menuBarActive = false,
                testTag = "network-workspace-layer",
            ) {
                networkContent()
            }
            BatteryNetworkRetainedLayer(
                active = selectedTab == BatteryNetworkTab.CORRELATION,
                menuBarActive = false,
                testTag = "correlation-workspace-layer",
            ) {
                CorrelationUnavailablePage(language)
            }
        }
    }
}

@Composable
private fun BatteryNetworkWorkspaceHeader(
    selectedTab: BatteryNetworkTab,
    onSelectedTabChanged: (BatteryNetworkTab) -> Unit,
    language: UiLanguage,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(StudioTokens.compactContentPadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        StudioWorkspaceHeader(
            title = localizedStringResource(Res.string.battery_network, language),
            supportingText = localizedStringResource(Res.string.independent_analyzers, language),
            titleModifier = Modifier.testTag("battery-network-title"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SegmentedSelector(
                items = BatteryNetworkTab.entries,
                selectedItem = selectedTab,
                onItemSelected = onSelectedTabChanged,
                itemLabel = { tab -> tab.label(language) },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("battery-network-tabs"),
                equalWidthItems = true,
                selectorDescription = localizedStringResource(Res.string.battery_network, language),
            )
        }
    }
}

@Composable
private fun BatteryNetworkRetainedLayer(
    active: Boolean,
    menuBarActive: Boolean,
    testTag: String,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWindowMenuBarActive provides menuBarActive) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(if (active) 1f else 0f)
                    .alpha(if (active) 1f else 0f)
                    .blockBatteryNetworkPointerInputWhenInactive(active)
                    .blockBatteryNetworkKeyboardInputWhenInactive(active)
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                    .testTag(testTag),
        ) {
            content()
        }
    }
}

@Composable
private fun CorrelationUnavailablePage(language: UiLanguage) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier.fillMaxSize().padding(StudioTokens.compactContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        StudioPanel(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            StudioPanelHeader(
                title = localizedStringResource(Res.string.correlation_unavailable, language),
            ) {
                StudioStatusChip(
                    label = localizedStringResource(Res.string.correlation_unavailable, language),
                    tone = StudioStatusTone.WARNING,
                )
            }
            Text(
                text = localizedStringResource(Res.string.correlation_unavailable_message, language),
                color = colors.secondaryText,
                style = ViewerTypography.bodyCompact,
            )
            Text(
                text = localizedStringResource(Res.string.correlation_requirements, language),
                color = colors.primaryText,
                style = ViewerTypography.subsectionTitle,
                fontWeight = FontWeight.SemiBold,
            )
            CorrelationRequirement(localizedStringResource(Res.string.correlation_time_domain, language))
            CorrelationRequirement(localizedStringResource(Res.string.correlation_capture_window, language))
            CorrelationRequirement(localizedStringResource(Res.string.correlation_error_bound, language))
            CorrelationRequirement(localizedStringResource(Res.string.correlation_provenance, language))
        }
    }
}

@Composable
private fun CorrelationRequirement(label: String) {
    val colors = LocalViewerColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "—", color = colors.warning)
        Spacer(Modifier.width(8.dp))
        Text(text = label, color = colors.secondaryText, style = ViewerTypography.bodyCompact)
    }
}

private fun BatteryNetworkTab.label(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            BatteryNetworkTab.BATTERY -> Res.string.battery_tab
            BatteryNetworkTab.NETWORK -> Res.string.network_tab
            BatteryNetworkTab.CORRELATION -> Res.string.correlation_tab
        },
        language,
    )

private fun Modifier.blockBatteryNetworkPointerInputWhenInactive(active: Boolean): Modifier =
    if (active) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }

private fun Modifier.blockBatteryNetworkKeyboardInputWhenInactive(active: Boolean): Modifier =
    if (active) this else onPreviewKeyEvent { true }
