package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuDeviceContextParserTest {
    @Test
    fun `keeps reported API versions implementation modules and driver text separate`() {
        val context =
            GpuDeviceContextParser.parse(
                device = device(),
                getpropOutput =
                    """
                    [ro.product.model]: [Pixel 9]
                    [ro.build.version.sdk]: [36]
                    [ro.opengles.version]: [196610]
                    [ro.vulkan.version]: [0x00401000]
                    [ro.hardware.egl]: [angle]
                    [ro.hardware.vulkan]: [vulkan.tok]
                    [ro.gfx.driver.0]: [com.vendor.graphicsdriver]
                    """.trimIndent(),
                surfaceFlingerOutput = "GLES: Google, Mali-G715, OpenGL ES 3.2 v1.r42p0\n",
            )

        assertEquals(36, context.apiLevel)
        assertEquals("Google", context.gpuVendor)
        assertEquals("Mali-G715", context.gpuRenderer)
        assertEquals("v1.r42p0", context.driverVersion)
        assertEquals("dumpsys SurfaceFlinger GLES", context.evidenceSources["gpuRenderer"])
        assertEquals(setOf(GraphicsApi.OPENGL_ES, GraphicsApi.VULKAN), context.graphicsApis)
        assertEquals("EGL: angle · Vulkan: vulkan.tok", context.graphicsImplementation?.name)
        assertNull(context.graphicsImplementation?.version)
        assertNull(context.graphicsImplementation?.backendApi)
        assertTrue(context.evidenceSources.getValue("openGlEsVersion").contains("3.2"))
        assertTrue(context.evidenceSources.getValue("vulkanVersion").contains("1.1.0"))
        assertTrue(context.evidenceSources.getValue("graphicsDriverPackage").contains("com.vendor.graphicsdriver"))
        assertTrue(context.evidenceSources.getValue("surfaceFlingerGlVersion").contains("OpenGL ES 3.2 v1.r42p0"))
    }

    @Test
    fun `does not turn driver packages or modules into a driver version or API support`() {
        val context =
            GpuDeviceContextParser.parse(
                device(),
                """
                [ro.hardware.egl]: [angle]
                [ro.hardware.vulkan]: [adreno]
                [ro.gfx.driver.0]: [com.qualcomm.qti.gpudrivers.kalama.api33]
                """.trimIndent(),
                "SurfaceFlinger state without GLES details",
            )

        assertNull(context.gpuRenderer)
        assertNull(context.driverVersion)
        assertEquals(emptySet(), context.graphicsApis)
        assertEquals("EGL: angle · Vulkan: adreno", context.graphicsImplementation?.name)
        assertNull(context.graphicsImplementation?.version)
        assertNull(context.graphicsImplementation?.backendApi)
    }

    @Test
    fun `does not mislabel an ANGLE implementation version as the native driver version`() {
        val context =
            GpuDeviceContextParser.parse(
                device(),
                "",
                "GLES: Google, ANGLE (Qualcomm, Adreno Vulkan 1.3.0), OpenGL ES 3.2 (ANGLE 2.1.0 git hash)\n",
            )

        assertEquals("ANGLE (Qualcomm, Adreno Vulkan 1.3.0)", context.gpuRenderer)
        assertEquals(setOf(GraphicsApi.OPENGL_ES), context.graphicsApis)
        assertNull(context.driverVersion)
        assertTrue(context.evidenceSources.getValue("surfaceFlingerGlVersion").contains("ANGLE 2.1.0"))
    }

    @Test
    fun `uses only fresh evidence and labels absent graphics details explicitly`() {
        val staleDevice = device().copy(
            apiLevel = 35,
            gpuVendor = "old vendor",
            gpuRenderer = "old renderer",
            driverVersion = "old driver",
            graphicsApis = setOf(GraphicsApi.VULKAN),
            evidenceSources = mapOf("old" to "stale"),
        )
        val context = GpuDeviceContextParser.parse(staleDevice, "", "")

        assertEquals("serial-1", context.serial)
        assertEquals("Device", context.model)
        assertEquals("Device(serial-1)", context.displayName)
        assertNull(context.apiLevel)
        assertNull(context.gpuVendor)
        assertNull(context.gpuRenderer)
        assertNull(context.driverVersion)
        assertNull(context.graphicsImplementation)
        assertEquals(emptySet(), context.graphicsApis)
        assertTrue(context.evidenceSources.getValue("getprop").contains("No parseable"))
        assertTrue(context.evidenceSources.getValue("surfaceFlinger").contains("not reported"))
        assertTrue(context.evidenceSources.getValue("deviceGraphics").contains("not reported"))
        assertNull(context.evidenceSources["old"])
    }

    @Test
    fun `ignores malformed placeholder zero and out of range version properties`() {
        val context =
            GpuDeviceContextParser.parse(
                device(),
                """
                not a property
                [ro.build.version.sdk]: [0]
                [ro.hardware.egl]: [UNKNOWN]
                [ro.hardware.vulkan]: [null]
                [ro.opengles.version]: [0]
                [ro.vulkan.version]: [4294967296]
                """.trimIndent(),
                "GLES: unknown, [null], unknown",
            )

        assertNull(context.apiLevel)
        assertNull(context.gpuVendor)
        assertNull(context.gpuRenderer)
        assertNull(context.driverVersion)
        assertNull(context.graphicsImplementation)
        assertEquals(emptySet(), context.graphicsApis)
        assertNull(context.evidenceSources["gpuRenderer"])
        assertNull(context.evidenceSources["gpuVendor"])
    }

    @Test
    fun `decodes upper case hex version properties without losing Vulkan patch evidence`() {
        val context = GpuDeviceContextParser.parse(
            device(),
            "[ro.opengles.version]: [0X00030002]\n[ro.vulkan.version]: [0X00403007]",
            "",
        )

        assertEquals(setOf(GraphicsApi.OPENGL_ES, GraphicsApi.VULKAN), context.graphicsApis)
        assertTrue(context.evidenceSources.getValue("openGlEsVersion").contains("3.2"))
        assertTrue(context.evidenceSources.getValue("vulkanVersion").contains("1.3.7"))
    }

    private fun device() = GpuDeviceContext(
        serial = "serial-1",
        model = "Device",
        apiLevel = null,
        gpuVendor = null,
        gpuRenderer = null,
        driverVersion = null,
        displayName = "Device(serial-1)",
    )
}
