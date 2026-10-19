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
public class IPv4Packet(
    private val raw: ByteBuffer,
) {
    public companion object {
        // packet length is stored on 16 bits
        public const val MAX_PACKET_LENGTH: Int = 1 shl 16
    }

    public val ipv4Header: IPv4Header
    public val transportHeader: TransportHeader?

    init {
        raw.rewind()

        val header = IPv4Header(raw.duplicate())
        ipv4Header = header
        if (!header.isSupported) {
            transportHeader = null
        } else {
            raw.limit(header.totalLength)
            transportHeader = createTransportHeader()
        }
    }

    public fun isValid(): Boolean = transportHeader != null

    private fun createTransportHeader(): TransportHeader = when (ipv4Header.protocol) {
        IPv4Header.Protocol.UDP -> UDPHeader(getRawTransport())
        IPv4Header.Protocol.TCP -> TCPHeader(getRawTransport())
        IPv4Header.Protocol.OTHER -> throw AssertionError("Should be unreachable if ipv4Header.isSupported")
    }

    private fun getRawTransport(): ByteBuffer {
        raw.position(ipv4Header.headerLength)
        return raw.slice()
    }

    public fun swapSourceAndDestination() {
        ipv4Header.swapSourceAndDestination()
        transportHeader!!.swapSourceAndDestination()
    }

    public fun getRaw(): ByteBuffer {
        raw.rewind()
        return raw.duplicate()
    }

    public fun getRawLength(): Int = raw.limit()

    public fun getPayload(): ByteBuffer {
        val headersLength = ipv4Header.headerLength + transportHeader!!.getHeaderLength()
        raw.position(headersLength)
        return raw.slice()
    }

    public fun getPayloadLength(): Int =
        raw.limit() - ipv4Header.headerLength - transportHeader!!.getHeaderLength()

    public fun computeChecksums() {
        ipv4Header.computeChecksum()
        transportHeader!!.computeChecksum(ipv4Header, getPayload())
    }
}
