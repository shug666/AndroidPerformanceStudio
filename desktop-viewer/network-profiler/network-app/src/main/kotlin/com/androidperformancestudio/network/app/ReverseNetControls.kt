@file:Suppress("FunctionName", "ktlint:standard:function-naming", "MagicNumber", "LongMethod")

package com.androidperformancestudio.network.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.network.network_app.generated.resources.*
import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import com.androidperformancestudio.reversenet.session.ReverseNetSessionState
import com.androidperformancestudio.reversenet.session.SessionController
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Toolbar controls for the reverse-tethering session: start/stop toggle,
 * isolation mode is fixed at session start, and live status text.
 */
@Composable
public fun ReverseNetControls(
    deviceSerial: String,
    language: UiLanguage,
    controller: SessionController,
    eventBus: com.androidperformancestudio.reversenet.events.ReverseNetEventBus? = null,
) {
    val scope = rememberCoroutineScope()
    val sessionState by controller.state.collectAsState()
    var alertText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var tunnelEventCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    if (eventBus != null) {
        androidx.compose.runtime.LaunchedEffect(eventBus) {
            eventBus.events.collect { event ->
                tunnelEventCount++
                if (event is com.androidperformancestudio.reversenet.events.ReverseNetEvent.Alert) {
                    alertText = event.message
                }
            }
        }
    }

    fun statusText(state: ReverseNetSessionState): String = when (state) {
        is ReverseNetSessionState.Idle -> localizedStringResource(Res.string.reverse_net_idle, language)
        is ReverseNetSessionState.Starting -> localizedStringResource(Res.string.reverse_net_starting, language)
        is ReverseNetSessionState.AwaitingVpnAuthorization ->
            localizedStringResource(Res.string.reverse_net_awaiting_vpn, language)
        is ReverseNetSessionState.Sharing -> localizedStringResource(Res.string.reverse_net_sharing, language)
        is ReverseNetSessionState.Stopping -> localizedStringResource(Res.string.reverse_net_stopping, language)
        is ReverseNetSessionState.Error -> localizedStringResource(Res.string.reverse_net_error, language) + " " + state.message
    }

    val active = sessionState !is ReverseNetSessionState.Idle && sessionState !is ReverseNetSessionState.Error

    HeaderSpacer()
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.reverse_net_start, language),
        enabled = deviceSerial.isNotBlank() && !active,
        onClick = {
            scope.launch(Dispatchers.IO) {
                controller.start(deviceSerial, DnsFilterMode.RELAXED)
            }
        },
    )
    HeaderSpacer()
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.reverse_net_start_isolated, language),
        enabled = deviceSerial.isNotBlank() && !active,
        onClick = {
            scope.launch(Dispatchers.IO) {
                controller.start(deviceSerial, DnsFilterMode.ISOLATED)
            }
        },
    )
    HeaderSpacer()
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.reverse_net_stop, language),
        enabled = active,
        onClick = {
            scope.launch(Dispatchers.IO) {
                controller.stop()
            }
        },
    )
    HeaderSpacer()
    androidx.compose.material3.Text(
        text = if (tunnelEventCount > 0) "$tunnelEventCount tunnel events" else "",
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
    )
    androidx.compose.material3.Text(
        text = alertText ?: statusText(sessionState),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = if (alertText != null) {
            androidx.compose.material3.MaterialTheme.colorScheme.error
        } else {
            androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        },
    )
}

/** Bus + controller pair for the reverse-tethering session. */
public class ReverseNetSession(
    val controller: SessionController,
    val eventBus: com.androidperformancestudio.reversenet.events.ReverseNetEventBus,
)

/** Factory wiring the default session controller against the vendored gnirehtet.apk. */
@Composable
public fun rememberReverseNetSession(): ReverseNetSession {
    val eventBus = remember { com.androidperformancestudio.reversenet.events.ReverseNetEventBus() }
    val controller = remember {
        val executable = defaultAdbExecutable()
            ?: error("Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT.")
        val apk = com.androidperformancestudio.reversenet.session.ReverseNetPaths.vendoredApk()
        SessionController(
            adbOperations = com.androidperformancestudio.reversenet.session.DefaultReverseNetAdbOperations(
                adbClient = DefaultAdbClient(executable),
                adbExecutable = executable,
            ),
            vendoredApk = requireNotNull(apk) {
                "third_party/gnirehtet/gnirehtet.apk not found; run from the repository checkout"
            },
            eventBus = eventBus,
        )
    }
    return ReverseNetSession(controller, eventBus)
}
