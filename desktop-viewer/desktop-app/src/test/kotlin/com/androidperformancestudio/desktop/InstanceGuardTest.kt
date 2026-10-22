package com.androidperformancestudio.desktop

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstanceGuardTest {
    private val guard = InstanceGuard()

    @AfterEach
    fun tearDown() {
        guard.release()
    }

    private fun freePort(): Int =
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun `acquire succeeds on a free port`() {
        assertTrue(guard.acquire(freePort()))
        assertTrue(guard.isHeld)
    }

    @Test
    fun `acquire fails when the port is already bound`() {
        val port = freePort()
        ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).use {
            assertFalse(guard.acquire(port))
            assertFalse(guard.isHeld)
        }
    }

    @Test
    fun `release frees the token so it can be reacquired`() {
        val port = freePort()
        assertTrue(guard.acquire(port))
        guard.release()
        assertFalse(guard.isHeld)
        assertTrue(guard.acquire(port))
    }
}
