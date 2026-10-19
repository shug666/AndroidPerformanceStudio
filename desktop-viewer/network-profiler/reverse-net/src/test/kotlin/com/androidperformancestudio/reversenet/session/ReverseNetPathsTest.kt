package com.androidperformancestudio.reversenet.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

public class ReverseNetPathsTest {
    @Test
    public fun `classpath resource is available and has expected size`() {
        val resource = ReverseNetPaths::class.java.getResourceAsStream("/gnirehtet.apk")
        assertTrue(resource != null, "gnirehtet.apk must be on the classpath as a resource")
        val bytes = resource!!.readBytes()
        assertEquals(23_904, bytes.size, "vendored gnirehtet.apk 2.5.1 is 23904 bytes")
    }

    @Test
    public fun `vendoredApk resolves to a real file`() {
        val apk = ReverseNetPaths.vendoredApk()
        assertTrue(apk != null, "vendoredApk() must resolve in both dev and installed modes")
        assertTrue(Files.isRegularFile(apk), "resolved apk must be a regular file: $apk")
        assertTrue(Files.size(apk) == 23_904L, "resolved apk must be the full 2.5.1 apk")
    }
}
