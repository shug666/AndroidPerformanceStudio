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
public class TCPHeader(
    raw: ByteBuffer,
) : TransportHeader {
    public companion object {
        public const val FLAG_FIN: Int = 1 shl 0
        public const val FLAG_SYN: Int = 1 shl 1
        public const val FLAG_RST: Int = 1 shl 2
        public const val FLAG_PSH: Int = 1 shl 3
        public const val FLAG_ACK: Int = 1 shl 4
        public const val FLAG_URG: Int = 1 shl 5
    }

    private val raw: ByteBuffer
    private var sourcePortValue: Int
    private var destinationPortValue: Int
    private var headerLengthValue: Int
    private var sequenceNumberValue: Int
    private var acknowledgementNumberValue: Int
    private var flagsValue: Int
    private var windowValue: Int

    init {
        sourcePortValue = raw.getShort(0).toInt() and 0xffff
        destinationPortValue = raw.getShort(2).toInt() and 0xffff
        sequenceNumberValue = raw.getInt(4)
        acknowledgementNumberValue = raw.getInt(8)

        val dataOffsetAndFlags = raw.getShort(12).toInt()
        headerLengthValue = (dataOffsetAndFlags and 0xf000) shr 10
        flagsValue = dataOffsetAndFlags and 0x1ff
        windowValue = raw.getShort(14).toInt() and 0xffff

        raw.limit(headerLengthValue)
        this.raw = raw
    }

    public fun getWindow(): Int = windowValue

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

    public fun getSequenceNumber(): Int = sequenceNumberValue

    public fun setSequenceNumber(sequenceNumber: Int) {
        sequenceNumberValue = sequenceNumber
        raw.putInt(4, sequenceNumber)
    }

    public fun getAcknowledgementNumber(): Int = acknowledgementNumberValue

    public fun setAcknowledgementNumber(acknowledgementNumber: Int) {
        acknowledgementNumberValue = acknowledgementNumber
        raw.putInt(8, acknowledgementNumber)
    }

    override fun getHeaderLength(): Int = headerLengthValue

    override fun setPayloadLength(payloadLength: Int) {
        // do nothing
    }

    public fun getFlags(): Int = flagsValue

    public fun isFin(): Boolean = (flagsValue and FLAG_FIN) != 0

    public fun isSyn(): Boolean = (flagsValue and FLAG_SYN) != 0

    public fun isRst(): Boolean = (flagsValue and FLAG_RST) != 0

    public fun isPsh(): Boolean = (flagsValue and FLAG_PSH) != 0

    public fun isAck(): Boolean = (flagsValue and FLAG_ACK) != 0

    public fun isUrg(): Boolean = (flagsValue and FLAG_URG) != 0

    public fun setFlags(flags: Int) {
        flagsValue = flags
        // keep the data-offset high bits, replace the 9 flag bits (matches relay-java semantics)
        var dataOffsetAndFlags = raw.getShort(12).toInt()
        dataOffsetAndFlags = (dataOffsetAndFlags and 0xfe00) or (flags and 0x1ff)
        raw.putShort(12, dataOffsetAndFlags.toShort())
    }

    override fun getRaw(): ByteBuffer {
        raw.rewind()
        return raw.slice()
    }

    override fun copyTo(buffer: ByteBuffer): TransportHeader {
        raw.rewind()
        val slice = Binary.slice(buffer, buffer.position(), getHeaderLength())
        buffer.put(raw)
        return TCPHeader(slice)
    }

    public fun copy(): TCPHeader = TCPHeader(Binary.copy(raw))

    @Suppress("MagicNumber")
    override fun computeChecksum(ipv4Header: IPv4Header, payload: ByteBuffer) {
        // checksum computation is the most CPU-intensive task in gnirehtet
        // prefer optimization over readability
        val rawArray = raw.array()
        val rawOffset = raw.arrayOffset()
        val payloadArray = payload.array()
        val payloadOffset = payload.arrayOffset()

        // pseudo-header checksum (cf rfc793 section 3.1)
        val source = ipv4Header.getSource()
        val destination = ipv4Header.getDestination()
        val length = ipv4Header.totalLength - ipv4Header.headerLength

        var sum = source ushr 16
        sum += source and 0xffff
        sum += destination ushr 16
        sum += destination and 0xffff
        sum += IPv4Header.Protocol.TCP.number
        sum += length

        // reset checksum field
        setChecksum(0.toShort())

        for (i in 0 until headerLengthValue / 2) {
            sum += ((rawArray[rawOffset + 2 * i].toInt() and 0xff) shl 8) or (rawArray[rawOffset + 2 * i + 1].toInt() and 0xff)
        }

        val payloadLength = length - headerLengthValue
        for (i in 0 until payloadLength / 2) {
            sum += ((payloadArray[payloadOffset + 2 * i].toInt() and 0xff) shl 8) or (payloadArray[payloadOffset + 2 * i + 1].toInt() and 0xff)
        }
        if (payloadLength % 2 != 0) {
            sum += (payloadArray[payloadOffset + payloadLength - 1].toInt() and 0xff) shl 8
        }

        while ((sum and 0xffff.inv()) != 0) {
            sum = (sum and 0xffff) + (sum shr 16)
        }
        setChecksum((sum.inv()).toShort())
    }

    private fun setChecksum(checksum: Short) {
        raw.putShort(16, checksum)
    }

    public fun getChecksum(): Short = raw.getShort(16)

    public fun shrinkOptions() {
        setDataOffset(5)
        raw.limit(20)
    }

    private fun setDataOffset(dataOffset: Int) {
        var dataOffsetAndFlags = raw.getShort(12).toInt()
        dataOffsetAndFlags = dataOffsetAndFlags and 0x0fff or (dataOffset shl 12)
        raw.putShort(12, dataOffsetAndFlags.toShort())
        headerLengthValue = dataOffset shl 2
    }
}
