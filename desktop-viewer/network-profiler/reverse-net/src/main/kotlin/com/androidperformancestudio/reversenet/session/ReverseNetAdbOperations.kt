/*
 * Copyright (C) 2026 Android Performance Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("SwallowedException")

package com.androidperformancestudio.reversenet.session

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.DefaultAdbCommandExecutor
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * adb operations required by the session orchestration, isolated for testability.
 * APK installation goes through a raw adb command because AdbClient does not expose install.
 */
public interface ReverseNetAdbOperations {
    /** True when the gnirehtet package is installed on the device. */
    public suspend fun isClientInstalled(serial: String): Boolean

    public suspend fun installClient(serial: String, apkPath: Path)

    public suspend fun addReverseTunnel(serial: String)

    public suspend fun removeReverseTunnel(serial: String)

    /** Returns current `adb reverse --list` entries for the device. */
    public suspend fun listReverseTunnels(serial: String): List<String>

    public suspend fun startClientOnDevice(serial: String)

    public suspend fun stopClientOnDevice(serial: String)
}

public class DefaultReverseNetAdbOperations(
    private val adbClient: AdbClient,
    private val adbExecutable: Path,
) : ReverseNetAdbOperations {
    private val executor = DefaultAdbCommandExecutor(adbExecutable)

    override suspend fun isClientInstalled(serial: String): Boolean = try {
        val result = adbClient.shell(serial, listOf("pm", "path", CLIENT_PACKAGE), ADB_TIMEOUT)
        result.stdout.isNotBlank()
    } catch (e: Exception) {
        false
    }

    override suspend fun installClient(serial: String, apkPath: Path) {
        val arguments = listOf("-s", serial, "install", "-r", "-g", apkPath.toString())
        val result = executor.executeText(arguments, INSTALL_TIMEOUT)
        if (!result.stdout.contains("Success")) {
            error("gnirehtet.apk installation failed: ${result.stdout.take(200)}")
        }
    }

    override suspend fun addReverseTunnel(serial: String) {
        // adb reverse (device connects to host) is not exposed by AdbClient, which only
        // supports forward; run it as a raw adb command.
        val result = executor.executeText(
            listOf("-s", serial, "reverse", LOCAL_ABSTRACT_HOST_SPEC, "tcp:$RELAY_PORT"),
            ADB_TIMEOUT,
        )
        if (result.exitCode != 0) {
            error("adb reverse failed: ${result.stdout.take(200)}")
        }
    }

    override suspend fun removeReverseTunnel(serial: String) {
        try {
            executor.executeText(listOf("-s", serial, "reverse", "--remove", LOCAL_ABSTRACT_HOST_SPEC), ADB_TIMEOUT)
        } catch (e: Exception) {
            // tunnel may already be gone
        }
    }

    override suspend fun listReverseTunnels(serial: String): List<String> = try {
        val result = executor.executeText(listOf("-s", serial, "reverse", "--list"), ADB_TIMEOUT)
        result.stdout.lines().filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun startClientOnDevice(serial: String) {
        adbClient.shell(
            serial,
            listOf(
                "am", "start", "-a", START_ACTION,
                "-n", "$CLIENT_PACKAGE/.GnirehtetActivity",
            ),
            ADB_TIMEOUT,
        )
    }

    override suspend fun stopClientOnDevice(serial: String) {
        try {
            adbClient.shell(
                serial,
                listOf(
                    "am", "start", "-a", STOP_ACTION,
                    "-n", "$CLIENT_PACKAGE/.GnirehtetActivity",
                ),
                ADB_TIMEOUT,
            )
        } catch (e: Exception) {
            // device may be gone; cleanup continues
        }
    }

    public companion object {
        public const val CLIENT_PACKAGE: String = "com.genymobile.gnirehtet"
        public const val LOCAL_ABSTRACT: String = "localabstract:gnirehtet"

        // host-side spec used by `adb reverse` (device-side name stays plain `localabstract:gnirehtet`)
        public const val LOCAL_ABSTRACT_HOST_SPEC: String = "localabstract:gnirehtet"
        public const val RELAY_PORT: Int = 31416
        public const val START_ACTION: String = "com.genymobile.gnirehtet.START"
        public const val STOP_ACTION: String = "com.genymobile.gnirehtet.STOP"

        private val ADB_TIMEOUT: Duration = 10.seconds
        private val INSTALL_TIMEOUT: Duration = 120.seconds
    }
}
