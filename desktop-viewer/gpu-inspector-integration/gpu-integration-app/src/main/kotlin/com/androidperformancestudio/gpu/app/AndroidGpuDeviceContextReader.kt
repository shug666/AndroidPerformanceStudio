package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.adb.AndroidTargetMonitor
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.model.GraphicsImplementationContext
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal class AndroidGpuDeviceContextReader(
    adbExecutable: Path,
    private val targetMonitor: AndroidTargetMonitor = AndroidTargetMonitors.shared(adbExecutable),
    private val adbClient: AdbClient = DefaultAdbClient(adbExecutable),
) {
    suspend fun discoverDevices(): List<GpuDeviceContext> =
        when (val result = withContext(Dispatchers.IO) { targetMonitor.refreshDevices() }) {
            is StudioResult.Success ->
                result.value.filter(AndroidDeviceInfo::online).map { device ->
                    GpuDeviceContext(
                        serial = device.serial,
                        model = device.deviceName,
                        apiLevel = null,
                        gpuVendor = null,
                        gpuRenderer = null,
                        driverVersion = null,
                        displayName = device.displayName,
                    )
                }
            is StudioResult.Failure -> error("GPU device discovery failed: ${result.error.message}")
        }

    suspend fun inspect(device: GpuDeviceContext): GpuDeviceContext = withContext(Dispatchers.IO) {
        val serial = requireNotNull(device.serial?.takeIf(String::isNotBlank)) { "Device serial is unavailable" }
        // A failed/partial command is not an empty successful observation. The caller retains the
        // discovery identity and displays the inspection error instead of accepting stale GPU facts.
        val properties = readShell(serial, listOf("getprop"))
        val surfaceFlinger = readShell(serial, listOf("dumpsys", "SurfaceFlinger"))
        GpuDeviceContextParser.parse(device, properties, surfaceFlinger)
    }

    private suspend fun readShell(serial: String, arguments: List<String>): String =
        try {
            val result = adbClient.shell(serial, arguments, timeout = 12.seconds)
            check(result.isSuccess) {
                "ADB exited ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }.trim()}"
            }
            check(!result.stdoutTruncated && !result.stderrTruncated) { "ADB output was truncated" }
            result.stdout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException(
                "GPU device inspection failed for $serial (${arguments.joinToString(" ")}): ${error.message}",
                error,
            )
        }
}

internal object GpuDeviceContextParser {
    private val propertyLine = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
    private val glesLine = Regex("(?im)^\\s*GLES:\\s*(.+)$")
    private val glesFields = Regex("^([^,]*),\\s*(.*?),\\s*(OpenGL ES(?:-CM|-CL)?\\s+.*)$", RegexOption.IGNORE_CASE)
    private val openGlEsVersion = Regex("^OpenGL ES(?:-CM|-CL)?\\s+(\\d+\\.\\d+)(?:\\.\\d+)?(?:\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val driverSuffix = Regex("^(?:v\\d|V@\\d|Mesa\\s+\\d)", RegexOption.IGNORE_CASE)

    private data class GlesObservation(
        val vendor: String?,
        val renderer: String?,
        val version: String?,
        val driverVersion: String?,
        val hasDetails: Boolean,
    )

    private data class GraphicsObservation(
        val apiLevel: Int?,
        val openGlEsVersion: String?,
        val vulkanVersion: String?,
        val apis: Set<GraphicsApi>,
    )

    fun parse(
        device: GpuDeviceContext,
        getpropOutput: String,
        surfaceFlingerOutput: String,
    ): GpuDeviceContext {
        val properties = parseProperties(getpropOutput)
        val gles = parseGles(surfaceFlingerOutput)
        val graphics = parseGraphics(properties, gles.version != null)
        return device.copy(
            model = properties["ro.product.model"].usableValue() ?: device.model.usableValue(),
            apiLevel = graphics.apiLevel,
            gpuVendor = gles.vendor,
            gpuRenderer = gles.renderer,
            driverVersion = gles.driverVersion,
            evidenceSources = evidenceSources(properties, gles, graphics),
            graphicsApis = graphics.apis,
            graphicsImplementation = graphicsImplementation(properties),
        )
    }

    private fun parseProperties(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            propertyLine.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() }
        }.toMap()

    private fun parseGles(output: String): GlesObservation {
        val glesText = glesLine.find(output)?.groupValues?.get(1)
        // ANGLE renderers can themselves contain commas. The GL_VERSION prefix is the
        // delimiter for that format; a simple three-way split would truncate the renderer.
        val glesParts = glesText?.let { text ->
            glesFields.matchEntire(text)?.groupValues?.drop(1)
                ?: text.split(Regex(",\\s*"), limit = 3)
        }.orEmpty()
        val vendor = glesParts.getOrNull(0).usableValue()
        val renderer = glesParts.getOrNull(1).usableValue()
        val reportedGlVersion = glesParts.getOrNull(2).usableValue()
        val reportedEsVersion = reportedGlVersion?.let(openGlEsVersion::matchEntire)
        val driverVersion = reportedEsVersion?.groupValues?.getOrNull(2).usableValue()
            ?.takeIf(driverSuffix::containsMatchIn)
        return GlesObservation(vendor, renderer, reportedGlVersion, driverVersion, glesParts.isNotEmpty())
    }

    private fun parseGraphics(properties: Map<String, String>, hasReportedOpenGlEs: Boolean): GraphicsObservation {
        val openGlEsVersion = properties["ro.opengles.version"]?.toVersionValue()?.let(::decodeOpenGlVersion)
        val vulkanVersion = properties["ro.vulkan.version"]?.toVersionValue()?.let(::decodeVulkanVersion)
        val apiLevel = properties["ro.build.version.sdk"]?.toIntOrNull()?.takeIf { it > 0 }
        val apis = buildSet {
            if (openGlEsVersion != null || hasReportedOpenGlEs) add(GraphicsApi.OPENGL_ES)
            if (vulkanVersion != null) add(GraphicsApi.VULKAN)
        }
        return GraphicsObservation(apiLevel, openGlEsVersion, vulkanVersion, apis)
    }

    private fun graphicsImplementation(properties: Map<String, String>): GraphicsImplementationContext? {
        val eglImplementation = properties["ro.hardware.egl"].usableValue()
        val vulkanImplementation = properties["ro.hardware.vulkan"].usableValue()
        val implementationNames = buildList {
            eglImplementation?.let { add("EGL: $it") }
            vulkanImplementation?.let { add("Vulkan: $it") }
        }
        return implementationNames.takeIf { it.isNotEmpty() }?.let { names ->
            GraphicsImplementationContext(
                name = names.joinToString(" · "),
                // These properties identify system modules, not module versions or the backend
                // selected by an application. API version properties must not fill these fields.
                version = null,
                backendApi = null,
                evidenceSource = "getprop ro.hardware.egl / ro.hardware.vulkan (system module names)",
            )
        }
    }

    private fun evidenceSources(
        properties: Map<String, String>,
        gles: GlesObservation,
        graphics: GraphicsObservation,
    ): Map<String, String> =
        buildMap {
            put("getprop", if (properties.isEmpty()) "No parseable Android system properties reported" else "adb shell getprop")
            put("surfaceFlinger", if (!gles.hasDetails) "GLES details not reported by dumpsys SurfaceFlinger" else "dumpsys SurfaceFlinger GLES")
            if (properties["ro.product.model"].usableValue() != null) put("model", "getprop ro.product.model")
            if (graphics.apiLevel != null) put("apiLevel", "getprop ro.build.version.sdk")
            if (gles.renderer != null) put("gpuRenderer", "dumpsys SurfaceFlinger GLES")
            if (gles.vendor != null) put("gpuVendor", "dumpsys SurfaceFlinger GLES")
            if (gles.version != null) put("surfaceFlingerGlVersion", "dumpsys SurfaceFlinger GLES GL_VERSION: ${gles.version}")
            if (gles.driverVersion != null) put("driverVersion", "dumpsys SurfaceFlinger GLES GL_VERSION driver suffix (reported text)")
            properties["ro.gfx.driver.0"].usableValue()?.let {
                put("graphicsDriverPackage", "getprop ro.gfx.driver.0 (driver package, not version): $it")
            }
            if (graphics.openGlEsVersion != null) {
                put("openGlEsVersion", "getprop ro.opengles.version: ${graphics.openGlEsVersion} (raw: ${properties["ro.opengles.version"]})")
            }
            if (graphics.vulkanVersion != null) {
                put("vulkanVersion", "getprop ro.vulkan.version: ${graphics.vulkanVersion} (raw: ${properties["ro.vulkan.version"]})")
            }
            if (graphics.apis.isNotEmpty()) {
                put("graphicsApis", "Android system graphics version evidence; not an application's API or AGI compatibility validation")
            }
            if (gles.vendor == null && gles.renderer == null && gles.driverVersion == null && graphics.apis.isEmpty()) {
                put("deviceGraphics", "GPU renderer, vendor, driver version and graphics API evidence not reported")
            }
        }

    private fun String?.usableValue(): String? = this?.trim()?.takeUnless {
        it.isEmpty() || it.lowercase() in setOf("unknown", "null", "[null]", "(null)", "n/a")
    }

    private fun String.toVersionValue(): Long? {
        val value = trim()
        val parsed = if (value.startsWith("0x", ignoreCase = true)) value.drop(2).toLongOrNull(16) else value.toLongOrNull()
        return parsed?.takeIf { it in 1..0xffff_ffffL }
    }

    private fun decodeOpenGlVersion(value: Long): String? {
        val major = (value shr 16) and 0xffff
        return if (major > 0) "$major.${value and 0xffff}" else null
    }

    private fun decodeVulkanVersion(value: Long): String? {
        val major = (value shr 22) and 0x7f
        val minor = (value shr 12) and 0x3ff
        val patch = value and 0xfff
        val variant = value shr 29
        // Vulkan variant bits are kept in the raw evidence above rather than mislabelled as a
        // standard Vulkan API version.
        return if (major > 0 && variant == 0L) "$major.$minor.$patch" else null
    }
}
