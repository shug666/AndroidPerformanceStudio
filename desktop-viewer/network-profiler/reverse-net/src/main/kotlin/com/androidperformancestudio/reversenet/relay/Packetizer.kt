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
package com.androidperformancestudio.reversenet.relay

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

/**
 * Convert from level 5 to level 3 by appending correct IP and transport headers.
 */
public class Packetizer(
    ipv4Header: IPv4Header,
    transportHeader: TransportHeader,
) {
    private val buffer = ByteBuffer.allocate(IPv4Packet.MAX_PACKET_LENGTH)
    private val payloadBuffer: ByteBuffer

    public val responseIPv4Header: IPv4Header
    public val responseTransportHeader: TransportHeader

    init {
        responseIPv4Header = ipv4Header.copyTo(buffer)
        responseTransportHeader = transportHeader.copyTo(buffer)
        payloadBuffer = buffer.slice()
    }

    public fun packetizeEmptyPayload(): IPv4Packet {
        payloadBuffer.limit(0).position(0)
        return inflate()
    }

    @Throws(IOException::class)
    public fun packetize(channel: ReadableByteChannel, maxChunkSize: Int): IPv4Packet? {
        payloadBuffer.limit(maxChunkSize).position(0)
        val payloadLength = channel.read(payloadBuffer)
        if (payloadLength == -1) {
            return null
        }
        payloadBuffer.flip()
        return inflate()
    }

    @Throws(IOException::class)
    public fun packetize(channel: ReadableByteChannel): IPv4Packet? =
        packetize(channel, payloadBuffer.capacity())

    private fun inflate(): IPv4Packet {
        val payloadLength = payloadBuffer.remaining()
        buffer.limit(payloadBuffer.arrayOffset() + payloadBuffer.limit()).position(0)

        val ipv4HeaderLength = responseIPv4Header.headerLength
        val transportHeaderLength = responseTransportHeader.getHeaderLength()
        val totalLength = ipv4HeaderLength + transportHeaderLength + payloadLength

        responseIPv4Header.totalLength = totalLength
        responseTransportHeader.setPayloadLength(payloadLength)

        // In order to avoid copies, buffer is shared with this IPv4Packet instance that is returned.
        // Don't use it after another call to packetize()!
        val packet = IPv4Packet(buffer)
        packet.computeChecksums()
        return packet
    }
}
