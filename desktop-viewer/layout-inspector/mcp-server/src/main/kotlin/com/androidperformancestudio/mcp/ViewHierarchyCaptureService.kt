package com.androidperformancestudio.mcp

import com.androidperformancestudio.adb.ConnectedDeviceSession
import com.androidperformancestudio.adb.LiveDeviceClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ProtocolCodec

class ViewHierarchyCaptureService {
    private val codec = ProtocolCodec(supportedMajor = 1)

    fun listDevices(): List<DeviceSummary> =
        runCatching { LiveDeviceClient().listAuthorizedDevices() }
            .getOrElse { emptyList() }
            .map { it.toSummary() }

    fun captureForeground(deviceId: String? = null): CaptureOutcome {
        val client = LiveDeviceClient()
        val session: ConnectedDeviceSession = try {
            client.connectForegroundApp(serial = deviceId)
        } catch (error: Throwable) {
            return CaptureOutcome(success = false, error = error.message ?: error.toString())
        }
        return session.use { s ->
            try {
                val frame = s.capture()
                val snapshot = codec.decodeSnapshot(frame.snapshotJson)
                CaptureOutcome(success = true, snapshot = snapshot, packageName = s.packageName)
            } catch (error: Throwable) {
                CaptureOutcome(success = false, error = error.message ?: error.toString())
            }
        }
    }

    data class DeviceSummary(
        val serial: String,
        val state: String,
        val model: String?,
        val product: String?,
    )

    data class CaptureOutcome(
        val success: Boolean,
        val snapshot: LayoutSnapshot? = null,
        val packageName: String? = null,
        val error: String? = null,
    )

    private fun AdbDevice.toSummary() =
        DeviceSummary(serial = serial, state = state.name, model = model, product = product)
}