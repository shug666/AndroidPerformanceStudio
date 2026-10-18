package com.androidperformancestudio.frame.app

import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.frame.presentation.FrameDeviceOption
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameDeviceAutoSelectionTest {
    @Test
    fun `entry refresh selects the sole online device and loads its processes`() =
        runBlocking {
            val backend = DeviceBackend(listOf(FrameDeviceOption("offline", "Offline", online = false), onlineDevice))
            val controller = FrameProfilerController(onlineBackend = backend)
            try {
                controller.refreshDevices()

                assertEquals("phone", controller.state.value.selectedDeviceSerial)
                assertEquals(listOf(process), controller.state.value.processes)
                assertEquals(listOf("phone"), backend.processRequests)
                assertNull(controller.state.value.errorMessage)
            } finally {
                controller.close()
            }
        }

    @Test
    fun `reentry refresh detects a device connected after the first visit`() =
        runBlocking {
            val backend = DeviceBackend(emptyList())
            val controller = FrameProfilerController(onlineBackend = backend)
            try {
                controller.refreshDevices()
                assertNull(controller.state.value.selectedDeviceSerial)
                backend.devices = listOf(onlineDevice)

                controller.refreshDevices()

                assertEquals("phone", controller.state.value.selectedDeviceSerial)
                assertEquals(listOf(process), controller.state.value.processes)
                assertEquals(2, backend.deviceRequests)
            } finally {
                controller.close()
            }
        }

    @Test
    fun `reentry retains a valid device and process without duplicate target discovery`() =
        runBlocking {
            val backend = DeviceBackend(listOf(onlineDevice))
            val controller = FrameProfilerController(onlineBackend = backend)
            try {
                controller.refreshDevices()
                controller.selectProcess(process.pid)

                controller.refreshDevices()

                assertEquals("phone", controller.state.value.selectedDeviceSerial)
                assertEquals(process.pid, controller.state.value.selectedProcessId)
                assertEquals(listOf("phone"), backend.processRequests)
            } finally {
                controller.close()
            }
        }

    @Test
    fun `multiple online devices still require explicit selection`() =
        runBlocking {
            val backend = DeviceBackend(listOf(onlineDevice, FrameDeviceOption("other", "Other phone")))
            val controller = FrameProfilerController(onlineBackend = backend)
            try {
                controller.refreshDevices()

                assertNull(controller.state.value.selectedDeviceSerial)
                assertEquals(emptyList(), backend.processRequests)
            } finally {
                controller.close()
            }
        }

    private class DeviceBackend(
        var devices: List<FrameDeviceOption>,
    ) : FrameOnlineBackend {
        var deviceRequests = 0
        val processRequests = mutableListOf<String>()

        override fun registerTargetListener(listener: AndroidTargetListener) = AndroidTargetSubscription {}

        override suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>> {
            deviceRequests++
            return FrameBackendResult.Success(devices)
        }

        override suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>> {
            processRequests += serial
            return FrameBackendResult.Success(listOf(process))
        }

        override suspend fun openCapture(
            serial: String,
            process: FrameProcessOption,
            sessionId: String,
        ): FrameBackendResult<OnlineFrameCapture> = error("Device refresh must not start a capture")
    }

    private companion object {
        val onlineDevice = FrameDeviceOption("phone", "Test phone")
        val process = FrameProcessOption(123, "com.example.app", "com.example.app")
    }
}
