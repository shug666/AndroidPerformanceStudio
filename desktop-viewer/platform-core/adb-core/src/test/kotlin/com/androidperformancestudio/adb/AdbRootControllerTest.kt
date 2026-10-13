package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdbRootControllerTest {
    @Test
    fun `restarts waits and verifies root`() =
        runBlocking {
            val requests = mutableListOf<HostProcessRequest>()
            val controller =
                AdbRootController(Path.of("/sdk/adb")) { request, _ ->
                    requests += request
                    completed(request, stdout = if (request.arguments.takeLast(3) == listOf("shell", "id", "-u")) "0\n" else "")
                }

            assertIs<StudioResult.Success<Unit>>(controller.activate("serial-1"))

            assertEquals(
                listOf(
                    listOf("-s", "serial-1", "root"),
                    listOf("-s", "serial-1", "wait-for-device"),
                    listOf("-s", "serial-1", "shell", "id", "-u"),
                ),
                requests.map(HostProcessRequest::arguments),
            )
        }

    @Test
    fun `fails closed when adbd reconnects without root`() =
        runBlocking {
            val controller =
                AdbRootController(Path.of("/sdk/adb")) { request, _ ->
                    completed(request, stdout = if (request.arguments.takeLast(3) == listOf("shell", "id", "-u")) "2000\n" else "")
                }

            val failure = assertIs<StudioResult.Failure>(controller.activate("serial-1"))

            assertEquals("ADB_ROOT_NOT_ACTIVE", failure.error.code)
        }
}

private fun completed(
    request: HostProcessRequest,
    stdout: String,
): HostCommandResult.Completed =
    HostCommandResult.Completed(
        HostCommandOutput(
            pid = 1,
            command = request.command,
            exitCode = 0,
            stdout = HostCapturedText(stdout, false),
            stderr = HostCapturedText("", false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        ),
    )
