@file:Suppress("FunctionName", "ktlint:standard:function-naming", "UnusedParameter")

package com.androidperformancestudio.winscope.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.winscope.winscope_app.generated.resources.Res
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_collecting
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_idle
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_legacy_path
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_perfetto_path
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_recording
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_capture_targets
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_error_prefix
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_open_viewer
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_service_running
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_service_starting
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_service_stopped
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_start_service
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_stop_browser_drives_capture
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_stop_service
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_token_label
import com.androidperformancestudio.winscope.winscope_app.generated.resources.winscope_viewer_url_label

/**
 * Winscope capture page: start/stop the bundled proxy + viewer service, mirror
 * the browser-driven capture state, and surface degradation reasons. The actual
 * capture configuration and triggers live in the Winscope viewer opened in the
 * system browser; this page never starts a service or browser as a side effect.
 */
@Composable
public fun WinscopeMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    isActive: Boolean = true,
    onBack: () -> Unit = {},
    controller: WinscopePageController,
) {
    val state by controller.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = onBack,
            onNavigateSettings = null,
        ) {
            Text(
                text = localizedStringResource(Res.string.winscope, language),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            state.errorMessage?.let { error ->
                Text(
                    text = localizedStringResource(Res.string.winscope_error_prefix, language, error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }

            ServiceActionRow(state, language, controller)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(
                text = localizedStringResource(Res.string.winscope_stop_browser_drives_capture, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            ManualHandoffSection(state, language)
            CaptureStatusSection(state, language)
        }
    }
}

@Composable
private fun ManualHandoffSection(
    state: WinscopePageState,
    language: UiLanguage,
) {
    // Manual-handoff path: when the browser's automatic token handshake fails
    // (stale cached tab, blocked request), the URL and token are shown here so
    // the viewer's paste-token retry dialog can be served by hand.
    state.viewerUrl?.let { url ->
        SelectionContainer {
            Column {
                Text(
                    text = localizedStringResource(Res.string.winscope_viewer_url_label, language),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localizedStringResource(Res.string.winscope_token_label, language),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = state.sessionToken ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ServiceActionRow(
    state: WinscopePageState,
    language: UiLanguage,
    controller: WinscopePageController,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state.serviceState) {
            WinscopeServiceState.STOPPED ->
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.winscope_start_service, language),
                    selected = true,
                    enabled = true,
                    onClick = { controller.start() },
                )
            WinscopeServiceState.STARTING ->
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.winscope_start_service, language),
                    selected = false,
                    enabled = false,
                    onClick = {},
                )
            WinscopeServiceState.RUNNING -> {
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.winscope_open_viewer, language),
                    selected = false,
                    enabled = true,
                    onClick = { controller.openViewer() },
                )
                Spacer(Modifier.padding(start = 8.dp))
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.winscope_stop_service, language),
                    selected = false,
                    enabled = true,
                    onClick = { controller.stop() },
                )
            }
            WinscopeServiceState.FAILED ->
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.winscope_start_service, language),
                    selected = true,
                    enabled = true,
                    onClick = { controller.start() },
                )
        }
        Spacer(Modifier.padding(start = 12.dp))
        Text(
            text = serviceStatusText(state, language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureStatusSection(
    state: WinscopePageState,
    language: UiLanguage,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                when (state.captureState) {
                    WinscopeCaptureState.IDLE ->
                        localizedStringResource(Res.string.winscope_capture_idle, language)
                    WinscopeCaptureState.RECORDING ->
                        localizedStringResource(
                            Res.string.winscope_capture_recording,
                            language,
                            state.captureDeviceSerial ?: "?",
                        )
                    WinscopeCaptureState.COLLECTING ->
                        localizedStringResource(
                            Res.string.winscope_capture_collecting,
                            language,
                            state.captureDeviceSerial ?: "?",
                        )
                },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (state.captureTargets.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    localizedStringResource(
                        Res.string.winscope_capture_targets,
                        language,
                        state.captureTargets.joinToString(", "),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.captureState != WinscopeCaptureState.IDLE) {
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    localizedStringResource(
                        if (state.captureLegacyPath) {
                            Res.string.winscope_capture_legacy_path
                        } else {
                            Res.string.winscope_capture_perfetto_path
                        },
                        language,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun serviceStatusText(
    state: WinscopePageState,
    language: UiLanguage,
): String =
    when (state.serviceState) {
        WinscopeServiceState.STOPPED, WinscopeServiceState.FAILED ->
            localizedStringResource(Res.string.winscope_service_stopped, language)
        WinscopeServiceState.STARTING ->
            localizedStringResource(Res.string.winscope_service_starting, language)
        WinscopeServiceState.RUNNING ->
            localizedStringResource(
                Res.string.winscope_service_running,
                language,
                (state.viewerPort ?: 0).toString(),
            )
    }
