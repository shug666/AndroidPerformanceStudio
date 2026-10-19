@file:Suppress("FunctionName", "ktlint:standard:function-naming", "MagicNumber", "LongMethod", "SwallowedException", "ReturnCount", "UnusedParameter")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.network.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.network.network_app.generated.resources.*
import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import com.androidperformancestudio.reversenet.events.ReverseNetTransport
import com.androidperformancestudio.reversenet.session.DefaultReverseNetAdbOperations
import com.androidperformancestudio.reversenet.session.ReverseNetPaths
import com.androidperformancestudio.reversenet.session.ReverseNetSessionState
import com.androidperformancestudio.reversenet.session.SessionController
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Standalone reverse-tethering page: share this computer's network with a connected
 * Android device over adb, with tunnel-level observability and IPv6 leak protection.
 *
 * Devices are auto-detected via `adb devices`; the user does not need to type a serial.
 */
@Composable
public fun FrameWindowScope.ReverseNetMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // Resolve adb + vendored apk once; surface init failures instead of crashing the page.
    var initError by remember { mutableStateOf<String?>(null) }
    val eventBus = remember { ReverseNetEventBus() }
    val controller = remember { buildController(eventBus) { initError = it } }
    val sessionState by controller?.state?.collectAsState()
        ?: remember { mutableStateOf<ReverseNetSessionState>(ReverseNetSessionState.Error("init")) }

    val devices = remember { mutableStateListOf<String>() }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun refreshDevices() {
        val ctl = controller ?: return
        scope.launch(Dispatchers.IO) {
            refreshing = true
            try {
                val list = withContext(Dispatchers.IO) { listDevicesSafe(ctl) }
                devices.clear()
                devices.addAll(list)
                if (selectedSerial == null && list.isNotEmpty()) {
                    selectedSerial = list.first()
                }
                if (selectedSerial != null && selectedSerial !in list) {
                    selectedSerial = list.firstOrNull()
                }
            } catch (t: Throwable) {
                initError = "Failed to list devices: ${t.message}"
            } finally {
                refreshing = false
            }
        }
    }

    // Auto-detect devices on entry and poll while the page is active.
    LaunchedEffect(controller) {
        while (isActive) {
            val ctl = controller ?: break
            try {
                val list = withContext(Dispatchers.IO) { listDevicesSafe(ctl) }
                devices.clear()
                devices.addAll(list)
                if (selectedSerial == null && list.isNotEmpty()) selectedSerial = list.first()
                if (selectedSerial != null && selectedSerial !in list) selectedSerial = list.firstOrNull()
            } catch (t: Throwable) {
                // keep last known list; transient adb errors are common during device plug cycles
            }
            delay(2000)
        }
    }

    val collectedEvents = remember { mutableStateListOf<ReverseNetEvent>() }
    LaunchedEffect(eventBus) {
        eventBus.events.collect { event ->
            collectedEvents.add(event)
            if (collectedEvents.size > 500) collectedEvents.removeAt(0)
        }
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
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(statusMessage(sessionState, language), initError)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (initError != null) {
                Text(
                    text = initError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Device selection row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = localizedStringResource(Res.string.reverse_net_device, language) + ":",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(12.dp))
                if (devices.isEmpty()) {
                    Text(
                        text = if (refreshing) "…" else localizedStringResource(Res.string.reverse_net_no_device, language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = devices.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (devices.size > 1) {
                        // simple selector: cycle on click
                        Text(
                            text = "selected: $selectedSerial",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.reverse_net_refresh, language),
                    enabled = controller != null,
                    onClick = { refreshDevices() },
                )
            }

            Spacer(Modifier.height(16.dp))

            val active = sessionState !is ReverseNetSessionState.Idle &&
                sessionState !is ReverseNetSessionState.Error
            val serial = selectedSerial
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.reverse_net_start, language),
                    enabled = controller != null && serial != null && !active,
                    onClick = {
                        scope.launch(Dispatchers.IO) { controller?.start(serial!!, DnsFilterMode.RELAXED) }
                    },
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.reverse_net_start_isolated, language),
                    enabled = controller != null && serial != null && !active,
                    onClick = {
                        scope.launch(Dispatchers.IO) { controller?.start(serial!!, DnsFilterMode.ISOLATED) }
                    },
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.reverse_net_stop, language),
                    enabled = active,
                    onClick = {
                        scope.launch(Dispatchers.IO) { controller?.stop() }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            var showContextMenu by remember { mutableStateOf(false) }
            var menuOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Tunnel events (${collectedEvents.size})",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.reverse_net_clear_log, language),
                    enabled = collectedEvents.isNotEmpty(),
                    onClick = { collectedEvents.clear() },
                )
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Press) {
                                        val mouseEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                        if (mouseEvent?.button == java.awt.event.MouseEvent.BUTTON3) {
                                            val pos = event.changes.firstOrNull()?.position
                                            if (pos != null) {
                                                menuOffset = androidx.compose.ui.unit.DpOffset(pos.x.dp, pos.y.dp)
                                            }
                                            showContextMenu = true
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    items(collectedEvents.size) { index ->
                        EventRow(collectedEvents[collectedEvents.size - 1 - index])
                    }
                }
            }
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                offset = menuOffset,
            ) {
                DropdownMenuItem(
                    text = { Text(localizedStringResource(Res.string.reverse_net_clear_log, language)) },
                    onClick = {
                        collectedEvents.clear()
                        showContextMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(localizedStringResource(Res.string.reverse_net_copy_log, language)) },
                    onClick = {
                        val sb = StringBuilder()
                        for (ev in collectedEvents) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(formatEvent(ev))
                        }
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sb.toString()), null)
                        showContextMenu = false
                    },
                )
            }
        }
    }
}

@Composable
private fun statusMessage(state: ReverseNetSessionState?, language: UiLanguage): String {
    val s = state ?: return ""
    return when (s) {
        is ReverseNetSessionState.Idle -> localizedStringResource(Res.string.reverse_net_idle, language)
        is ReverseNetSessionState.Starting -> localizedStringResource(Res.string.reverse_net_starting, language)
        is ReverseNetSessionState.AwaitingVpnAuthorization ->
            localizedStringResource(Res.string.reverse_net_awaiting_vpn, language)
        is ReverseNetSessionState.Sharing -> localizedStringResource(Res.string.reverse_net_sharing, language)
        is ReverseNetSessionState.Stopping -> localizedStringResource(Res.string.reverse_net_stopping, language)
        is ReverseNetSessionState.Error -> localizedStringResource(Res.string.reverse_net_error, language) + " " + s.message
    }
}

private fun buildController(
    eventBus: ReverseNetEventBus,
    onError: (String) -> Unit,
): SessionController? {
    return try {
        val executable = defaultAdbExecutable()
            ?: run {
                onError("Android SDK Platform Tools not found. Set ANDROID_HOME or install platform-tools in PATH.")
                null
            }
            ?: return null
        val apk = ReverseNetPaths.vendoredApk()
            ?: run {
                onError("Vendored gnirehtet.apk not found.")
                null
            } ?: return null
        SessionController(
            adbOperations = DefaultReverseNetAdbOperations(
                adbClient = DefaultAdbClient(executable),
                adbExecutable = executable,
            ),
            vendoredApk = apk,
            eventBus = eventBus,
        )
    } catch (t: Throwable) {
        onError("Initialization failed: ${t.message}")
        null
    }
}

private suspend fun listDevicesSafe(controller: SessionController): List<String> {
    // SessionController doesn't expose listDevices; query via a fresh AdbClient.
    val executable = defaultAdbExecutable() ?: return emptyList()
    return try {
        DefaultAdbClient(executable).listDevices().map { it.serial }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun formatEvent(event: ReverseNetEvent): String = when (event) {
    is ReverseNetEvent.Connection -> {
        val proto = if (event.transport == ReverseNetTransport.TCP) "TCP" else "UDP"
        "$proto  ${event.dstAddress}:${event.dstPort}  (from ${event.srcAddress}:${event.srcPort})"
    }
    is ReverseNetEvent.State -> "  state ${event.state}"
    is ReverseNetEvent.Bytes -> "  ${event.bytesSent}B out, ${event.bytesReceived}B in"
    is ReverseNetEvent.Closed -> {
        val seconds = event.durationNs / 1_000_000_000.0
        "  closed  ${"%.1f".format(seconds)}s  ${event.bytesSent}B out ${event.bytesReceived}B in"
    }
    is ReverseNetEvent.DnsResolved -> {
        val strip = if (event.aaaaStripped) " [AAAA stripped]" else ""
        "DNS  ${event.domain} -> ${event.addresses.joinToString(",")}$strip"
    }
    is ReverseNetEvent.Alert -> "ALERT  ${event.message}"
}

@Composable
private fun EventRow(event: ReverseNetEvent) {
    val color = when (event) {
        is ReverseNetEvent.DnsResolved -> MaterialTheme.colorScheme.primary
        is ReverseNetEvent.Alert -> MaterialTheme.colorScheme.error
        else -> androidx.compose.ui.graphics.Color.Unspecified
    }
    val text = formatEvent(event)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
