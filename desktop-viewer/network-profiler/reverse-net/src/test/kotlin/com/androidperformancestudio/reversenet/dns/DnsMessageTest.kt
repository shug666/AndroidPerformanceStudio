package com.androidperformancestudio.reversenet.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

public class DnsMessageTest {
    /** Build a DNS response with one A record and one AAAA record for example.test. */
    private fun buildResponse(aAddress: ByteArray, aaaaAddress: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // header: id, flags (response+recursion), qd=1, an=2, ns=0, ar=0
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0))
        // question: example.test A IN
        out.write(7)
        out.write("example".toByteArray())
        out.write(4)
        out.write("test".toByteArray())
        out.write(0) // root
        out.write(byteArrayOf(0, 1, 0, 1)) // type A, class IN
        // answer 1: pointer to question name (0xc00c), A, IN, ttl, rdlen 4
        out.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
        out.write(aAddress)
        // answer 2: pointer, AAAA, IN, ttl, rdlen 16
        out.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16))
        out.write(aaaaAddress)
        return out.toByteArray()
    }

    @Test
    public fun `parses a records from response`() {
        val bytes = buildResponse(byteArrayOf(1, 2, 3, 4), ByteArray(16))
        val response = DnsMessage.parseBytes(bytes, 0, bytes.size, stripAaaa = false)
        assertNotNull(response)
        assertEquals("example.test", response!!.questionName)
        assertEquals(1, response.aRecords.size)
        assertEquals((1 shl 24) or (2 shl 16) or (3 shl 8) or 4, response.aRecords[0].addressInt)
        assertEquals(1, response.aaaaRecords.size)
        assertFalse(response.aaaaStripped)
        assertNull(response.strippedPayload)
    }

    @Test
    public fun `isolated mode strips aaaa and rewrites ancount`() {
        val bytes = buildResponse(byteArrayOf(1, 2, 3, 4), ByteArray(16) { it.toByte() })
        val response = DnsMessage.parseBytes(bytes, 0, bytes.size, stripAaaa = true)
        assertNotNull(response)
        assertTrue(response!!.aaaaStripped)
        val stripped = response.strippedPayload!!
        // ANCOUNT (bytes 6..8) must now be 1
        assertEquals(1, ((stripped[6].toInt() and 0xff) shl 8) or (stripped[7].toInt() and 0xff))
        // Stripped message must still parse cleanly and contain only the A record
        val reparsed = DnsMessage.parseBytes(stripped, 0, stripped.size, stripAaaa = false)
        assertNotNull(reparsed)
        assertEquals(1, reparsed!!.aRecords.size)
        assertEquals(0, reparsed.aaaaRecords.size)
        assertFalse(DnsMessage.parseBytes(stripped, 0, stripped.size, stripAaaa = true)!!.aaaaStripped)
    }

    @Test
    public fun `relaxed mode keeps aaaa`() {
        val bytes = buildResponse(byteArrayOf(1, 2, 3, 4), ByteArray(16))
        val response = DnsMessage.parseBytes(bytes, 0, bytes.size, stripAaaa = false)
        assertNotNull(response)
        assertEquals(1, response!!.aaaaRecords.size)
        assertNull(response.strippedPayload)
    }

    @Test
    public fun `response detection requires qr bit`() {
        val response = buildResponse(byteArrayOf(1, 2, 3, 4), ByteArray(16))
        assertTrue(DnsMessage.isResponse(ByteBuffer.wrap(response)))
        // Clear QR bit
        response[2] = 0x01.toByte()
        assertFalse(DnsMessage.isResponse(ByteBuffer.wrap(response)))
    }

    @Test
    public fun `interceptor emits event and strips in isolated mode`() {
        val eventBus = com.androidperformancestudio.reversenet.events.ReverseNetEventBus()
        val interceptor = DnsInterceptor(eventBus, DnsFilterMode.ISOLATED)
        val bytes = buildResponse(byteArrayOf(8, 8, 4, 4), ByteArray(16))
        // UDP payload carries the full DNS message including its 12-byte header
        val payload = ByteBuffer.wrap(bytes)
        val rewritten = interceptor.onResponse(payload)
        assertTrue(rewritten !== payload, "isolated mode should rewrite when AAAA present")
        val out = ByteArray(rewritten.remaining())
        rewritten.duplicate().get(out)
        val reparsed = DnsMessage.parseBytes(out, 0, out.size, stripAaaa = false)
        assertEquals(0, reparsed!!.aaaaRecords.size)
    }

    @Test
    public fun `interceptor passes through in relaxed mode`() {
        val eventBus = com.androidperformancestudio.reversenet.events.ReverseNetEventBus()
        val interceptor = DnsInterceptor(eventBus, DnsFilterMode.RELAXED)
        val bytes = buildResponse(byteArrayOf(8, 8, 4, 4), ByteArray(16))
        val payload = ByteBuffer.wrap(bytes)
        val rewritten = interceptor.onResponse(payload)
        assertTrue(rewritten === payload, "relaxed mode must not rewrite")
    }
}
