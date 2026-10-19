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
@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.reversenet.relay

import java.nio.ByteBuffer

@Suppress("MagicNumber")
public class IPv4Header(
    raw: ByteBuffer,
) {
    public enum class Protocol(
        val number: Int,
    ) {
        TCP(6),
        UDP(17),
        OTHER(-1),
        ;

        public companion object {
            public fun fromNumber(number: Int): Protocol = when (number) {
                TCP.number -> TCP
                UDP.number -> UDP
                else -> OTHER
            }
        }
    }

    public companion object {
        private const val MIN_IPV4_HEADER_LENGTH = 20

        /** Read the packet IP version, or -1 if the buffer is empty. */
        public fun readVersion(buffer: ByteBuffer): Int {
            if (buffer.limit() == 0) {
                return -1
            }
            val versionAndIHL = buffer.get(0)
            return (versionAndIHL.toInt() and 0xf0) shr 4
        }

        /** Read the packet length, or -1 if the length field is not available. */
        public fun readLength(buffer: ByteBuffer): Int {
            if (buffer.limit() < 4) {
                return -1
            }
            return buffer.getShort(2).toInt() and 0xffff
        }
    }

    private val raw: ByteBuffer
    public val version: Byte
    public var headerLength: Int
        private set
    private var totalLengthValue: Int

    public var totalLength: Int
        get() = totalLengthValue
        set(value) {
            totalLengthValue = value
            raw.putShort(2, value.toShort())
        }

    public val protocol: Protocol

    private var sourceValue: Int
    private var destinationValue: Int

    init {
        assert(raw.limit() >= MIN_IPV4_HEADER_LENGTH) { "IPv4 headers length must be at least 20 bytes" }
        var buf = raw
        buf = buf.duplicate()
        buf.limit(raw.limit())

        val versionAndIHL = buf.get(0)
        version = ((versionAndIHL.toInt() shr 4) and 0xf).toByte()

        val ihl = versionAndIHL.toInt() and 0xf
        // Non-IPv4 packets (e.g. IPv6) can carry a zero IHL; clamp so the header reads below never
        // overflow. Unsupported packets are rejected via isSupported afterwards.
        headerLength = maxOf(ihl shl 2, MIN_IPV4_HEADER_LENGTH)

        buf.limit(minOf(headerLength, raw.limit()))
        this.raw = buf

        totalLengthValue = buf.getShort(2).toInt() and 0xffff

        val protocolNumber = buf.get(9).toInt() and 0xff
        protocol = Protocol.fromNumber(protocolNumber)

        sourceValue = buf.getInt(12)
        destinationValue = buf.getInt(16)
    }

    public val isSupported: Boolean
        get() = version.toInt() == 4 && protocol != Protocol.OTHER

    public fun getSource(): Int = sourceValue

    public fun setSource(source: Int) {
        sourceValue = source
        raw.putInt(12, source)
    }

    public fun getDestination(): Int = destinationValue

    public fun setDestination(destination: Int) {
        destinationValue = destination
        raw.putInt(16, destination)
    }

    public fun swapSourceAndDestination() {
        val tmp = sourceValue
        setSource(destinationValue)
        setDestination(tmp)
    }

    public fun getRaw(): ByteBuffer {
        raw.rewind()
        return raw.slice()
    }

    public fun copyTo(target: ByteBuffer): IPv4Header {
        raw.rewind()
        val slice = Binary.slice(target, target.position(), headerLength)
        target.put(raw)
        return IPv4Header(slice)
    }

    public fun copy(): IPv4Header = IPv4Header(Binary.copy(raw))

    public fun computeChecksum() {
        // reset checksum field
        setChecksum(0)

        // checksum computation is the most CPU-intensive task in gnirehtet
        // prefer optimization over readability
        val rawArray = raw.array()
        val rawArrayOffset = raw.arrayOffset()

        var sum = 0
        for (i in 0 until headerLength / 2) {
            sum += ((rawArray[rawArrayOffset + 2 * i].toInt() and 0xff) shl 8) or (rawArray[rawArrayOffset + 2 * i + 1].toInt() and 0xff)
        }
        while ((sum and 0xffff.inv()) != 0) {
            sum = (sum and 0xffff) + (sum shr 16)
        }
        setChecksum((sum.inv()).toShort())
    }

    private fun setChecksum(checksum: Short) {
        raw.putShort(10, checksum)
    }

    public fun getChecksum(): Short = raw.getShort(10)
}
