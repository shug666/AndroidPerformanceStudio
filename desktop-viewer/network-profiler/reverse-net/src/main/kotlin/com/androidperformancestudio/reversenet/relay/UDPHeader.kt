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

import java.nio.ByteBuffer

@Suppress("MagicNumber")
public class UDPHeader(
    raw: ByteBuffer,
) : TransportHeader {
    public companion object {
        private const val UDP_HEADER_LENGTH = 8
    }

    private val raw: ByteBuffer
    private var sourcePortValue: Int
    private var destinationPortValue: Int

    init {
        raw.limit(UDP_HEADER_LENGTH)
        sourcePortValue = raw.getShort(0).toInt() and 0xffff
        destinationPortValue = raw.getShort(2).toInt() and 0xffff
        this.raw = raw
    }

    override fun getSourcePort(): Int = sourcePortValue

    override fun setSourcePort(port: Int) {
        sourcePortValue = port
        raw.putShort(0, port.toShort())
    }

    override fun getDestinationPort(): Int = destinationPortValue

    override fun setDestinationPort(port: Int) {
        destinationPortValue = port
        raw.putShort(2, port.toShort())
    }

    override fun getHeaderLength(): Int = UDP_HEADER_LENGTH

    override fun setPayloadLength(payloadLength: Int) {
        val length = getHeaderLength() + payloadLength
        raw.putShort(4, length.toShort())
    }

    override fun getRaw(): ByteBuffer {
        raw.rewind()
        return raw.slice()
    }

    override fun copyTo(buffer: ByteBuffer): TransportHeader {
        raw.rewind()
        val slice = Binary.slice(buffer, buffer.position(), getHeaderLength())
        buffer.put(raw)
        return UDPHeader(slice)
    }

    override fun computeChecksum(ipv4Header: IPv4Header, payload: ByteBuffer) {
        // disable checksum validation
        raw.putShort(6, 0)
    }
}
