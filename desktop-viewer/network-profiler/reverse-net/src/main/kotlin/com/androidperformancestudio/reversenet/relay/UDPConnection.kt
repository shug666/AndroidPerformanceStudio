/*
 * Copyright (C) 2017 Genymobile
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
@file:Suppress("TooManyFunctions", "SwallowedException", "ReturnCount")

package com.androidperformancestudio.reversenet.relay

import com.androidperformancestudio.reversenet.dns.DnsInterceptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

public class UDPConnection(
    id: ConnectionId,
    client: Client,
    selector: Selector,
    ipv4Header: IPv4Header,
    udpHeader: UDPHeader,
    private val dnsInterceptor: DnsInterceptor? = null,
) : AbstractConnection(id, client) {
    public companion object {
        public const val IDLE_TIMEOUT: Long = 2 * 60 * 1000
    }

    private val clientToNetwork = DatagramBuffer(4 * IPv4Packet.MAX_PACKET_LENGTH)
    private val networkToClient: Packetizer

    private val channel: DatagramChannel
    private val selectionKey: SelectionKey
    private var interests: Int

    private var idleSince: Long = 0

    init {
        networkToClient = Packetizer(ipv4Header, udpHeader)
        networkToClient.responseIPv4Header.swapSourceAndDestination()
        (networkToClient.responseTransportHeader as UDPHeader).swapSourceAndDestination()

        touch()

        val selectionHandler = SelectionHandler { key ->
            touch()
            if (key.isValid && key.isReadable) {
                processReceive()
            }
            if (key.isValid && key.isWritable) {
                processSend()
            }
            updateInterests()
        }
        channel = createChannel()
        interests = SelectionKey.OP_READ
        selectionKey = channel.register(selector, interests, selectionHandler)
    }

    override fun sendToNetwork(packet: IPv4Packet) {
        if (!clientToNetwork.readFrom(packet.getPayload())) {
            // cannot send to network, dropping packet
            return
        }
        updateInterests()
    }

    override fun disconnect() {
        selectionKey.cancel()
        try {
            channel.close()
        } catch (e: IOException) {
            // cannot close channel
        }
    }

    override fun isExpired(): Boolean = System.currentTimeMillis() >= idleSince + IDLE_TIMEOUT

    private fun createChannel(): DatagramChannel {
        val datagramChannel = DatagramChannel.open()
        datagramChannel.configureBlocking(false)
        datagramChannel.connect(getRewrittenDestination())
        return datagramChannel
    }

    private fun touch() {
        idleSince = System.currentTimeMillis()
    }

    private fun processReceive() {
        val packet = read()
        if (packet == null) {
            close()
            return
        }
        pushToClient(packet)
    }

    private fun processSend() {
        if (!write()) {
            close()
        }
    }

    private fun read(): IPv4Packet? = try {
        networkToClient.packetize(channel)
    } catch (e: IOException) {
        null
    }

    private fun write(): Boolean = try {
        clientToNetwork.writeTo(channel)
    } catch (e: IOException) {
        false
    }

    private fun pushToClient(packet: IPv4Packet) {
        val interceptor = dnsInterceptor ?: run {
            sendRaw(packet)
            return
        }
        val dstPort = id.getDestinationPort()
        if (dstPort != DnsInterceptor.DNS_PORT) {
            sendRaw(packet)
            return
        }
        val payload = packet.getPayload()
        if (!interceptor.isResponse(payload)) {
            sendRaw(packet)
            return
        }
        val rewritten = interceptor.onResponse(payload)
        if (rewritten === payload) {
            sendRaw(packet)
            return
        }
        // DNS payload was rewritten (AAAA stripped): rebuild the packet in place.
        sendRaw(resizePacket(packet, rewritten))
    }

    private fun sendRaw(packet: IPv4Packet) {
        if (!sendToClient(packet)) {
            // cannot send to client, dropping packet
            return
        }
    }

    /**
     * Rebuild a packet whose payload was replaced: copy headers + new payload into a fresh
     * buffer, update IPv4 total length and UDP length, then recompute checksums.
     */
    private fun resizePacket(packet: IPv4Packet, newPayload: ByteBuffer): IPv4Packet {
        val ipHeaderLength = packet.ipv4Header.headerLength
        val udpHeaderLength = packet.transportHeader!!.getHeaderLength()
        val newTotalLength = ipHeaderLength + udpHeaderLength + newPayload.remaining()

        val buffer = ByteBuffer.allocate(newTotalLength)
        val oldRaw = packet.getRaw()
        oldRaw.limit(ipHeaderLength)
        buffer.put(oldRaw)
        oldRaw.limit(oldRaw.capacity())
        packet.getRaw().position(ipHeaderLength).limit(ipHeaderLength + udpHeaderLength)
        val udpHeaderBytes = ByteArray(udpHeaderLength)
        packet.getRaw().duplicate().get(udpHeaderBytes)
        buffer.put(udpHeaderBytes)
        buffer.put(newPayload.duplicate())
        buffer.flip()

        val rebuilt = IPv4Packet(buffer)
        rebuilt.ipv4Header.totalLength = newTotalLength
        (rebuilt.transportHeader as com.androidperformancestudio.reversenet.relay.UDPHeader).setPayloadLength(newPayload.remaining())
        rebuilt.computeChecksums()
        return rebuilt
    }

    private fun updateInterests() {
        if (!selectionKey.isValid) {
            return
        }
        var interestOps = SelectionKey.OP_READ
        if (mayWrite()) {
            interestOps = interestOps or SelectionKey.OP_WRITE
        }
        if (interests != interestOps) {
            // interests must be changed
            interests = interestOps
            selectionKey.interestOps(interestOps)
        }
    }

    private fun mayWrite(): Boolean = !clientToNetwork.isEmpty()
}
