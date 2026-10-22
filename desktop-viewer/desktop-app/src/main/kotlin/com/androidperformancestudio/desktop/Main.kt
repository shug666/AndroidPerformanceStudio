package com.androidperformancestudio.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane

internal const val APP_DISPLAY_NAME = "AndroidPerfermanceStudio"

fun main() {
    // Enforce a single running instance: hold a liveness token on a dedicated port for the JVM's
    // lifetime before composing any window. A second launch fails to bind and is rejected with a
    // message instead of opening a broken window whose trace-serving port 9001 the first instance
    // already owns. The OS frees the token on any process exit (normal, crash, kill).
    val guard = InstanceGuard()
    if (!guard.acquire(InstanceGuard.LIVENESS_TOKEN_PORT)) {
        reportSecondInstance()
        return
    }
    application {
        val appIcon = painterResource("icons/app-icon.png")
        val windowTitle = remember { mutableStateOf(APP_DISPLAY_NAME) }
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            onCloseRequest = ::exitApplication,
            icon = appIcon,
            title = windowTitle.value,
            state = windowState,
        ) {
            // Let the app paint the strip behind the native macOS window buttons with the theme.
            SideEffect { MacOsWindowChrome.install(window) }
            var settingsRequest by remember { mutableStateOf<SettingsRequest?>(null) }
            var nextSettingsRequestId by remember { mutableStateOf(0L) }
            val settingsMenuInstaller = remember {
                ApplicationSettingsMenuInstaller.desktop()
            }
            DisposableEffect(settingsMenuInstaller) {
                val registration = settingsMenuInstaller.install {
                    nextSettingsRequestId += 1
                    settingsRequest = SettingsRequest(SettingsPage.GENERAL, nextSettingsRequestId)
                }
                onDispose(registration::close)
            }
            window.minimumSize = java.awt.Dimension(1280, 720)
            DesktopAppMainPage(windowTitle, settingsRequest = settingsRequest)
        }
    }
}

/**
 * Surfaces the "another instance is already running" message for a rejected second launch. Uses a
 * Swing dialog on a graphical environment, or prints to stderr and exits non-zero when headless.
 */
private fun reportSecondInstance() {
    val message = "Another $APP_DISPLAY_NAME instance is already running. Please use the existing window."
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println(message)
        System.exit(1)
    } else {
        JOptionPane.showMessageDialog(null, message, APP_DISPLAY_NAME, JOptionPane.WARNING_MESSAGE)
    }
}
