package com.androidperformancestudio.reversenet.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

public class IPv4PacketTest {
    @Test
    public fun `non-ipv4 packet is unsupported`() {
        // IPv6 version nibble
        val buf = ByteBuffer.allocate(40)
        buf.put(0x60.toByte())
        repeat(39) { buf.put(0) }
        buf.flip()
        val packet = IPv4Packet(buf)
        assertFalse(packet.isValid())
    }

    @Test
    public fun `unsupported protocol is marked invalid`() {
        val buf = ByteBuffer.allocate(20)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(20)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(64)
        buf.put(1) // ICMP
        buf.putShort(0)
        buf.putInt(0x0a000002)
        buf.putInt(0x08080808)
        buf.flip()
        val packet = IPv4Packet(buf)
        assertFalse(packet.isValid())
    }

    @Test
    public fun `tcp packet exposes headers`() {
        val totalLength = 40
        val buf = ByteBuffer.allocate(totalLength)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(40.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(64)
        buf.put(6)
        buf.putShort(0)
        buf.putInt(0x0a000002)
        buf.putInt(0x08080808)
        buf.putShort(12345.toShort())
        buf.putShort(443.toShort())
        buf.putInt(1000)
        buf.putInt(2000)
        buf.putShort(((20 shl 10) or TCPHeader.FLAG_SYN).toShort())
        buf.putShort(8192.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.flip()
        val packet = IPv4Packet(buf)
        assertTrue(packet.isValid())
        assertEquals(20, packet.ipv4Header.headerLength)
        assertEquals(0x0a000002, packet.ipv4Header.getSource())
        assertEquals(0x08080808, packet.ipv4Header.getDestination())
        val tcp = packet.transportHeader as TCPHeader
        assertTrue(tcp.isSyn())
        assertEquals(12345, tcp.getSourcePort())
        assertEquals(443, tcp.getDestinationPort())
    }
}
