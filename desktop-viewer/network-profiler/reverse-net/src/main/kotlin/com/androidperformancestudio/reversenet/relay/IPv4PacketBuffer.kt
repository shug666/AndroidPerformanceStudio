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
@file:Suppress("ReturnCount")

package com.androidperformancestudio.reversenet.relay

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

public class IPv4PacketBuffer {
    private val buffer = ByteBuffer.allocate(IPv4Packet.MAX_PACKET_LENGTH)

    @Throws(IOException::class)
    public fun readFrom(channel: ReadableByteChannel): Int = channel.read(buffer)

    @Suppress("MagicNumber")
    private fun getAvailablePacketLength(): Int {
        val length = IPv4Header.readLength(buffer)
        if (length == -1) {
            // no packet
            return 0
        }
        if (length > buffer.remaining()) {
            // no full packet available
            return 0
        }
        return length
    }

    public fun asIPv4Packet(): IPv4Packet? {
        buffer.flip()
        val length = getAvailablePacketLength()
        if (length == 0) {
            buffer.compact()
            return null
        }
        val limit = buffer.limit()
        buffer.limit(length).position(0)
        val packetBuffer = buffer.slice()
        buffer.limit(limit).position(length)
        // In order to avoid copies, packetBuffer is shared with this IPv4Packet instance that is returned.
        // Don't use it after another call to next()!
        return IPv4Packet(packetBuffer)
    }

    public fun next() {
        buffer.compact()
    }
}
