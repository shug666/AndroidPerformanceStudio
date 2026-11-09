@file:Suppress("MagicNumber")

package com.androidperformancestudio.winscope.device

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One entry of the `/devices` payload, mirroring winscope_proxy.py ListDevicesEndpoint. */
public data class WinscopeDeviceInfo(
    val serial: String,
    val authorized: Boolean,
    val model: String,
    val displays: List<String>,
    val screenrecordVersion: String,
)

/**
 * Device catalog backing the high-frequency `/devices` polling observed in the
 * handshake spike (~1 request/second): every poll performs per-device adb probes,
 * so results are cached briefly to keep adb from being saturated.
 */
public class WinscopeDeviceCatalog(
    private val adbClient: AdbClient,
    private val cacheTtlMillis: Long = 1_000L,
) {
    private data class CacheEntry(
        val at: Long,
        val devices: Map<String, WinscopeDeviceInfo>,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    public suspend fun listDevices(): Map<String, WinscopeDeviceInfo> =
        withContext(Dispatchers.IO) {
            val key = "devices"
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { now - it.at < cacheTtlMillis }?.let { return@withContext it.devices }

            val devices = linkedMapOf<String, WinscopeDeviceInfo>()
            adbClient
                .listDevices()
                .filter { it.state != AdbDeviceState.NO_PERMISSIONS }
                .forEach { device ->
                    val authorized = device.state == AdbDeviceState.ONLINE
                    devices[device.serial] =
                        WinscopeDeviceInfo(
                            serial = device.serial,
                            authorized = authorized,
                            model = device.model ?: device.serial,
                            displays = if (authorized) probeDisplays(device.serial) else emptyList(),
                            screenrecordVersion = if (authorized) probeScreenrecordVersion(device.serial) else "0",
                        )
                }
            cache[key] = CacheEntry(now, devices)
            devices
        }

    public fun invalidate() {
        cache.clear()
    }

    private suspend fun probeDisplays(serial: String): List<String> =
        runCatching {
            adbClient
                .shell(serial, listOf("su", "root", "dumpsys", "SurfaceFlinger", "--display-id"))
                .stdout
                .trim()
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        }.getOrDefault(emptyList())

    private suspend fun probeScreenrecordVersion(serial: String): String =
        runCatching {
            // screenrecord prints its version on stderr on many devices; the reference
            // implementation merges stderr into stdout (subprocess.STDOUT), so both
            // streams are consulted here as well.
            val version = adbClient.shell(serial, listOf("screenrecord", "--version"))
            val merged = (version.stdout + version.stderr).trim()
            if (merged.isNotEmpty()) return@runCatching merged
            val help = adbClient.shell(serial, listOf("screenrecord", "--help"))
            val helpText = help.stdout + help.stderr
            helpText
                .let { text ->
                    val index = text.indexOf('v') + 1
                    text.substring(index, minOf(index + 3, text.length))
                }.trim()
                .ifBlank { "0" }
        }.getOrDefault("0")
}
