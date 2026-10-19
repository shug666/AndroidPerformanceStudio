@file:Suppress("LongMethod", "LoopWithTooManyJumpStatements", "EmptyFunctionBlock", "SwallowedException", "UnusedPrivateProperty", "MagicNumber")

package com.androidperformancestudio.reversenet.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Loopback integration test: drives the relay with a simulated device client.
 */
public class RelayLoopbackTest {
    @Test
    public fun `tcp connection round trip through relay`() {
        // 1. Real network target: local echo server
        val echoServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val echoPort = echoServer.localPort
        val echoPool = Executors.newFixedThreadPool(2)
        val echoThread = Thread {
            while (true) {
                val client = try {
                    echoServer.accept()
                } catch (e: IOException) {
                    return@Thread
                }
                echoPool.submit {
                    val buf = ByteArray(4096)
                    while (true) {
                        val r = client.getInputStream().read(buf)
                        if (r == -1) break
                        client.getOutputStream().write(buf, 0, r)
                    }
                    client.close()
                }
            }
        }
        echoThread.isDaemon = true
        echoThread.start()

        // 2. Start relay on a free port
        val relayPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val listener = RecordingListener()
        val engine = RelayEngine(relayPort, listener)
        engine.start()
        val relayThread = Thread { engine.runLoop() }
        relayThread.isDaemon = true
        relayThread.start()

        // 3. Simulate device: connect to relay over TCP (like adb reverse tunnel)
        val tunnel = SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(), relayPort))
        tunnel.configureBlocking(true)

        // 4. Read client id (4 bytes)
        val idBuf = ByteBuffer.allocate(4)
        while (idBuf.hasRemaining()) tunnel.read(idBuf)
        idBuf.flip()
        val clientId = idBuf.int
        assertTrue(clientId >= 0)

        // 5. Craft SYN packet: 10.0.0.2:12345 -> 10.0.2.2:echoPort (rewritten to localhost)
        val srcIp = intFromBytes(10, 0, 0, 2)
        val dstIp = intFromBytes(10, 0, 2, 2)
        val srcPort = 12345
        val clientSeq = Random.nextInt()
        val syn = buildTcpPacket(srcIp, srcPort, dstIp, echoPort, seq = clientSeq, flags = TCPHeader.FLAG_SYN)
        tunnel.write(ByteBuffer.wrap(syn))

        // 6. Expect SYN|ACK response
        val synAck = readPacket(tunnel, timeoutMs = 3000)
        assertTrue(synAck != null, "expected SYN|ACK")
        val respPacket = IPv4Packet(ByteBuffer.wrap(synAck))
        val respTcp = respPacket.transportHeader as? TCPHeader
        val isSynAck = respTcp != null && respTcp.isSyn() && respTcp.isAck()
        assertTrue(
            isSynAck,
            "expected SYN|ACK but got: " + (synAck?.joinToString(" ") { b -> "%02x".format(b) }?.take(120) ?: "null"),
        )
        val serverSeq = respTcp!!.getSequenceNumber()

        // 7. Complete the handshake with a bare ACK (SYN_RECEIVED -> ESTABLISHED), then send data
        val payload = "hello relay".toByteArray()
        val bareAck = buildTcpPacket(
            srcIp, srcPort, dstIp, echoPort,
            seq = clientSeq + 1,
            ackNum = serverSeq + 1,
            flags = TCPHeader.FLAG_ACK,
        )
        tunnel.write(ByteBuffer.wrap(bareAck))
        val dataPacket = buildTcpPacket(
            srcIp, srcPort, dstIp, echoPort,
            seq = clientSeq + 1,
            ackNum = serverSeq + 1,
            flags = TCPHeader.FLAG_ACK or TCPHeader.FLAG_PSH,
            payload = payload,
        )
        tunnel.write(ByteBuffer.wrap(dataPacket))

        // 8. Expect echo response
        // Read packets until we see one carrying the echoed payload (bare ACKs may interleave)
        val deadlineMs = System.currentTimeMillis() + 3000
        var echoed: String? = null
        while (System.currentTimeMillis() < deadlineMs) {
            val raw = readPacket(tunnel, timeoutMs = 500) ?: break
            val parsed = IPv4Packet(ByteBuffer.wrap(raw))
            val tcp = parsed.transportHeader as? TCPHeader ?: continue
            if (parsed.getPayloadLength() > 0) {
                val payloadBuf = parsed.getPayload()
                val bytes = ByteArray(parsed.getPayloadLength())
                payloadBuf.get(bytes)
                echoed = String(bytes)
                break
            }
        }
        assertEquals("hello relay", echoed)

        // 9. Close: FIN from device side
        val fin = buildTcpPacket(srcIp, srcPort, dstIp, echoPort, seq = clientSeq + 1 + payload.size, ackNum = 0, flags = TCPHeader.FLAG_FIN or TCPHeader.FLAG_ACK)
        tunnel.write(ByteBuffer.wrap(fin))

        // 10. Cleanup
        engine.stop()
        echoServer.close()
        echoPool.shutdownNow()
        tunnel.close()
    }

    private fun readPacket(channel: SocketChannel, timeoutMs: Long): ByteArray? {
        channel.configureBlocking(false)
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = IPv4PacketBuffer()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            buffer.readFrom(channel)
            val packet = buffer.asIPv4Packet()
            if (packet != null) {
                buffer.next()
                channel.configureBlocking(true)
                val raw = ByteArray(packet.getRawLength())
                packet.getRaw().get(raw)
                return raw
            }
            buffer.next()
            Thread.sleep(10)
        }
        channel.configureBlocking(true)
        return null
    }

    private fun intFromBytes(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

    private fun buildTcpPacket(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seq: Int,
        ackNum: Int = 0,
        flags: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val totalLength = 20 + 20 + payload.size
        val buf = ByteBuffer.allocate(totalLength)
        // IPv4 header
        buf.put((0x40 or 5).toByte())
        buf.put(0) // DSCP
        buf.putShort(totalLength.toShort())
        buf.putShort(0) // id
        buf.putShort(0x4000) // DF
        buf.put(64) // TTL
        buf.put(6) // TCP
        buf.putShort(0) // checksum (relay recomputes on response, doesn't validate inbound)
        buf.putInt(srcIp)
        buf.putInt(dstIp)
        // TCP header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq)
        buf.putInt(ackNum)
        buf.putShort(((20 shl 10) or flags).toShort())
        buf.putShort(8192.toShort()) // window
        buf.putShort(0) // checksum
        buf.putShort(0) // urgent
        buf.put(payload)
        buf.flip()
        return buf.array()
    }

    public class RecordingListener : RelayListener {
        public val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        public val closed = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun onClientConnected(clientId: Int) {}

        override fun onClientDisconnected(clientId: Int) {}

        override fun onConnectionOpened(connection: Connection, id: ConnectionId) {
            opened.add(id.toString())
        }

        override fun onConnectionClosed(connection: Connection, id: ConnectionId) {
            closed.add(id.toString())
        }

        override fun onPacketDropped(packet: IPv4Packet) {}
    }
}
