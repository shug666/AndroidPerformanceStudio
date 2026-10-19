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
import java.nio.channels.WritableByteChannel

/**
 * Circular buffer to store datagrams (preserving their boundaries).
 */
@Suppress("MagicNumber")
public class DatagramBuffer(
    capacity: Int,
) {
    public companion object {
        // every datagram is stored along with a header storing its length, on 16 bits
        private const val HEADER_LENGTH = 2
        private const val MAX_DATAGRAM_LENGTH = 1 shl 16
        private const val MAX_BLOCK_LENGTH = HEADER_LENGTH + MAX_DATAGRAM_LENGTH
    }

    private val data = ByteArray(capacity + MAX_BLOCK_LENGTH)
    private val wrapper = ByteBuffer.wrap(data)
    private var head = 0
    private var tail = 0
    private val circularBufferLength = capacity + 1

    public fun isEmpty(): Boolean = head == tail

    public fun hasEnoughSpaceFor(datagramLength: Int): Boolean {
        if (head >= tail) {
            // there is at least the extra space for storing 1 packet
            return true
        }
        val remaining = tail - head - 1 // 1 extra byte to distinguish empty vs full
        return HEADER_LENGTH + datagramLength <= remaining
    }

    public fun capacity(): Int = circularBufferLength - 1

    @Throws(IOException::class)
    public fun writeTo(channel: WritableByteChannel): Boolean {
        val length = readLength()
        wrapper.limit(tail + length).position(tail)
        tail += length
        if (tail >= circularBufferLength) {
            tail = 0
        }
        val w = channel.write(wrapper)
        if (w != length) {
            return false
        }
        return true
    }

    public fun readFrom(buffer: ByteBuffer): Boolean {
        val length = buffer.remaining()
        if (length > MAX_DATAGRAM_LENGTH) {
            throw IllegalArgumentException(
                "Datagram length (" + buffer.remaining() + ") may not be greater than " + MAX_DATAGRAM_LENGTH + " bytes",
            )
        }
        if (!hasEnoughSpaceFor(length)) {
            return false
        }
        writeLength(length)
        buffer.get(data, head, length)
        head += length
        if (head >= circularBufferLength) {
            head = 0
        }
        return true
    }

    private fun writeLength(length: Int) {
        assert((length and 0xffff.inv()) == 0) { "Length must be stored on 16 bits" }
        data[head++] = ((length shr 8) and 0xff).toByte()
        data[head++] = (length and 0xff).toByte()
    }

    private fun readLength(): Int {
        val length = ((data[tail].toInt() and 0xff) shl 8) or (data[tail + 1].toInt() and 0xff)
        tail += 2
        return length
    }
}
