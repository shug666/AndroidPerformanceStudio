@file:Suppress("FunctionName", "ktlint:standard:function-naming", "MagicNumber", "LongMethod")

package com.androidperformancestudio.network.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.network.network_app.generated.resources.*
import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.session.ReverseNetSessionState
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Standalone reverse-tethering page: share this computer's network with a connected
 * Android device over adb, with tunnel-level observability and IPv6 leak protection.
 */
@Composable
public fun FrameWindowScope.ReverseNetMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
) {
    val session = rememberReverseNetSession()
    val controller = session.controller
    val eventBus = session.eventBus
    val scope = rememberCoroutineScope()
    val sessionState by controller.state.collectAsState()
    var deviceSerial by remember { mutableStateOf("") }
    val collectedEvents = remember { mutableListOf<ReverseNetEvent>() }
    var eventVersion by remember { mutableStateOf(0) }

    // Collect tunnel events for the live list
    androidx.compose.runtime.LaunchedEffect(eventBus) {
        eventBus.events.collect { event ->
            collectedEvents.add(event)
            if (collectedEvents.size > 500) collectedEvents.removeAt(0)
            eventVersion++
        }
    }

    val active = sessionState !is ReverseNetSessionState.Idle && sessionState !is ReverseNetSessionState.Error
    val statusText = when (val st = sessionState) {
        is ReverseNetSessionState.Idle -> localizedStringResource(Res.string.reverse_net_idle, language)
        is ReverseNetSessionState.Starting -> localizedStringResource(Res.string.reverse_net_starting, language)
        is ReverseNetSessionState.AwaitingVpnAuthorization ->
            localizedStringResource(Res.string.reverse_net_awaiting_vpn, language)
        is ReverseNetSessionState.Sharing -> localizedStringResource(Res.string.reverse_net_sharing, language)
        is ReverseNetSessionState.Stopping -> localizedStringResource(Res.string.reverse_net_stopping, language)
        is ReverseNetSessionState.Error -> localizedStringResource(Res.string.reverse_net_error, language) + " " + st.message
    }

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = onBack,
            onNavigateSettings = null,
        ) {
            Text(
                text = localizedStringResource(Res.string.reverse_net_title, language),
                style = MaterialTheme.typography.titleMedium,
            )
            HeaderSpacer()
            ProfilerCompactTextField(
                label = localizedStringResource(Res.string.reverse_net_device, language),
                value = deviceSerial,
                onValueChange = { deviceSerial = it },
                enabled = !active,
                modifier = Modifier.width(200.dp),
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.reverse_net_start, language),
                enabled = deviceSerial.isNotBlank() && !active,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        controller.start(deviceSerial, com.androidperformancestudio.reversenet.dns.DnsFilterMode.RELAXED)
                    }
                },
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.reverse_net_start_isolated, language),
                enabled = deviceSerial.isNotBlank() && !active,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        controller.start(deviceSerial, com.androidperformancestudio.reversenet.dns.DnsFilterMode.ISOLATED)
                    }
                },
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.reverse_net_stop, language),
                enabled = active,
                onClick = {
                    scope.launch(Dispatchers.IO) { controller.stop() }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(statusText, null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Tunnel events panel
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "Tunnel events (${collectedEvents.size})",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            // eventVersion read forces recomposition on each new event
            @Suppress("UNUSED_EXPRESSION")
            eventVersion
            LazyColumn(Modifier.fillMaxSize()) {
                items(collectedEvents.reversed()) { event ->
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: ReverseNetEvent) {
    val text = when (event) {
        is ReverseNetEvent.Connection ->
            "${event.transport} ${event.srcAddress}:${event.srcPort} -> ${event.dstAddress}:${event.dstPort}"
        is ReverseNetEvent.State -> "state ${event.state}"
        is ReverseNetEvent.Bytes -> "bytes sent=${event.bytesSent} recv=${event.bytesReceived}"
        is ReverseNetEvent.Closed -> "closed duration=${event.durationNs / 1_000_000}ms"
        is ReverseNetEvent.DnsResolved -> "dns ${event.domain} -> ${event.addresses} aaaaStripped=${event.aaaaStripped}"
        is ReverseNetEvent.Alert -> "ALERT[${event.alertKind}] ${event.message}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
