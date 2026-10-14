package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryNetworkWorkspaceTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")

    @Test
    fun `shell exposes one composite destination instead of separate battery and network routes`() {
        val destinations = Files.readString(sourceRoot.resolve("AppDestination.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))

        assertTrue(destinations.contains("BATTERY_NETWORK"))
        assertFalse(destinations.contains("BATTERY_PROFILER"))
        assertFalse(destinations.contains("NETWORK_PROFILER"))
        assertTrue(shell.contains("AppDestination.BATTERY_NETWORK"))
        assertTrue(home.contains("Res.string.battery_network"))
    }

    @Test
    fun `composite page retains both independent child workspaces and fail closed correlation`() {
        val page = Files.readString(sourceRoot.resolve("BatteryNetworkMainPage.kt"))

        assertTrue(page.contains("BatteryProfilerMainPage("))
        assertTrue(page.contains("NetworkProfilerMainPage("))
        assertTrue(page.contains("BatteryNetworkTab.CORRELATION"))
        assertTrue(page.contains("correlation_unavailable_message"))
        assertTrue(page.contains("blockBatteryNetworkPointerInputWhenInactive"))
        assertTrue(page.contains("LocalWindowMenuBarActive provides menuBarActive"))
        assertTrue(page.contains("isActive && selectedTab == BatteryNetworkTab.BATTERY"))
    }
}
