@file:Suppress("FunctionNaming")

package com.androidperformancestudio.desktop.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop.AppDestination
import com.androidperformancestudio.desktop.AppNavigator
import com.androidperformancestudio.desktop.navigationLabelResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_close
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_close_all
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_empty
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_home_pinned
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_open
import com.androidperformancestudio.desktop_app.generated.resources.task_manager_title
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/**
 * Lists the modules currently retained in [navigator.retainedDestinations] and lets the user
 * close (evict) individual modules or all of them, freeing their resources. HOME is pinned and
 * not closeable. Selecting a row navigates to that module. Closing the active module returns the
 * shell to HOME (handled by [AppNavigator.evict]).
 */
@Composable
internal fun ModuleTaskManagerOverlay(
    navigator: AppNavigator,
    language: UiLanguage,
    onDismiss: () -> Unit,
) {
    val title = localizedStringResource(Res.string.task_manager_title, language)
    val closeLabel = localizedStringResource(Res.string.task_manager_close, language)
    val closeAllLabel = localizedStringResource(Res.string.task_manager_close_all, language)
    val openLabel = localizedStringResource(Res.string.task_manager_open, language)
    val emptyLabel = localizedStringResource(Res.string.task_manager_empty, language)
    val homePinnedLabel = localizedStringResource(Res.string.task_manager_home_pinned, language)

    val destinations = navigator.retainedDestinations
    val active = navigator.destination

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier =
                    Modifier
                        .widthIn(min = 320.dp, max = 520.dp)
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                destinations.forEach { destination ->
                    val label = labelFor(destination, language, homePinnedLabel)
                    val isActive = destination == active
                    val isHome = destination == AppDestination.HOME
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f).testTag("tm-open-${destination.name}"),
                            onClick = {
                                navigator.open(destination)
                                onDismiss()
                            },
                        ) {
                            Text(
                                text = label + if (isActive) "  ·" else "",
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        if (!isHome) {
                            TextButton(
                                modifier = Modifier.testTag("tm-close-${destination.name}"),
                                onClick = { navigator.evict(destination) },
                            ) {
                                Text(closeLabel)
                            }
                        } else {
                            // HOME is pinned; show an Open affordance only.
                            TextButton(
                                modifier = Modifier.testTag("tm-open-${destination.name}"),
                                onClick = { navigator.open(destination); onDismiss() },
                            ) {
                                Text(openLabel)
                            }
                        }
                    }
                }
                // "Close all" closes every module except HOME and the active destination. When
                // nothing besides HOME and the active module is open, show the empty hint instead.
                val closeable = destinations.count { it != AppDestination.HOME && it != active }
                if (closeable > 0) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("tm-close-all"),
                        onClick = { navigator.evictAll() },
                    ) {
                        Text(closeAllLabel)
                    }
                } else {
                    Text(
                        text = emptyLabel,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

private fun labelFor(
    destination: AppDestination,
    language: UiLanguage,
    homePinnedLabel: String,
): String =
    if (destination == AppDestination.HOME) homePinnedLabel
    else localizedStringResource(destination.navigationLabelResource, language)
