package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbInputException
import com.androidperformancestudio.platform.adb.AdbInputValidator
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Explicitly restarts adbd as root and verifies the selected device reconnects with uid 0. */
class AdbRootController(
    private val adbExecutable: Path,
    private val processInvocation: ProcessInvocation = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    suspend fun activate(
        serial: String,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<Unit> {
        val validatedSerial =
            try {
                AdbInputValidator.requireSerial(serial)
            } catch (error: AdbInputException) {
                return StudioResult.Failure(
                    StudioError(ErrorCategory.CONFIGURATION, "ADB_ROOT_SERIAL_INVALID", error.message.orEmpty(), error),
                )
            }
        execute(validatedSerial, listOf("root"), ROOT_TIMEOUT, cancellationSignal)
            .failureOrNull("ADB_ROOT_FAILED", "Failed to restart adbd as root")
            ?.let { return it }
        execute(validatedSerial, listOf("wait-for-device"), RECONNECT_TIMEOUT, cancellationSignal)
            .failureOrNull("ADB_ROOT_RECONNECT_FAILED", "Device did not reconnect after adb root")
            ?.let { return it }
        return when (val verification = execute(validatedSerial, listOf("shell", "id", "-u"), VERIFY_TIMEOUT, cancellationSignal)) {
            is HostCommandResult.Failed ->
                verification.rootFailure("ADB_ROOT_VERIFY_FAILED", "Failed to verify adb root")
            is HostCommandResult.Completed ->
                if (verification.output.stdout.text.trim() == ROOT_UID) {
                    StudioResult.Success(Unit)
                } else {
                    StudioResult.Failure(
                        StudioError(
                            category = ErrorCategory.CONFIGURATION,
                            code = "ADB_ROOT_NOT_ACTIVE",
                            message =
                                verification.output.stderr.text
                                    .trim()
                                    .ifEmpty { "adbd reconnected without root privileges" },
                        ),
                    )
                }
        }
    }

    private suspend fun execute(
        serial: String,
        arguments: List<String>,
        timeout: kotlin.time.Duration,
        cancellationSignal: HostCancellationSignal,
    ): HostCommandResult =
        processInvocation(
            HostProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial) + arguments,
                timeout = timeout,
            ),
            cancellationSignal,
        )
}

private fun HostCommandResult.failureOrNull(
    code: String,
    fallbackMessage: String,
): StudioResult.Failure? =
    (this as? HostCommandResult.Failed)?.rootFailure(code, fallbackMessage)

private fun HostCommandResult.Failed.rootFailure(
    code: String,
    fallbackMessage: String,
): StudioResult.Failure {
    val detail =
        sequenceOf(output?.stderr?.text, output?.stdout?.text, error.message)
            .filterNotNull()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?: fallbackMessage
    return StudioResult.Failure(
        StudioError(
            category = ErrorCategory.CONFIGURATION,
            code = code,
            message = detail,
            cause = error.cause,
        ),
    )
}

private const val ROOT_UID = "0"
private val ROOT_TIMEOUT = 30.seconds
private val RECONNECT_TIMEOUT = 45.seconds
private val VERIFY_TIMEOUT = 15.seconds
