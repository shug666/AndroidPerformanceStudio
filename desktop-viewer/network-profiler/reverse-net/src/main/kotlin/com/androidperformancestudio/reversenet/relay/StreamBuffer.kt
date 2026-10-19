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
import java.nio.channels.WritableByteChannel

/**
 * Circular buffer to store a stream. Read/write boundaries are not preserved.
 */
public class StreamBuffer(
    capacity: Int,
) {
    private val data = ByteArray(capacity + 1)
    private val wrapper = ByteBuffer.wrap(data)
    private var head = 0
    private var tail = 0

    public fun isEmpty(): Boolean = head == tail

    public fun isFull(): Boolean = (head + 1) % data.size == tail

    public fun size(): Int {
        if (head < tail) {
            return head + data.size - tail
        }
        return head - tail
    }

    public fun capacity(): Int = data.size - 1

    public fun remaining(): Int = capacity() - size()

    @Throws(IOException::class)
    public fun writeTo(channel: WritableByteChannel): Int {
        if (head > tail) {
            wrapper.limit(head).position(tail)
            val w = channel.write(wrapper)
            tail = wrapper.position()
            optimize()
            return w
        }

        if (head < tail) {
            wrapper.limit(data.size).position(tail)
            val w = channel.write(wrapper)
            tail = wrapper.position() % data.size
            optimize()
            return w
        }

        // else head == tail, which means empty buffer, nothing to do
        return 0
    }

    public fun readFrom(buffer: ByteBuffer) {
        val requested = minOf(buffer.remaining(), remaining())
        if (requested <= data.size - head) {
            buffer.get(data, head, requested)
        } else {
            buffer.get(data, head, data.size - head)
            buffer.get(data, 0, head + requested - data.size)
        }
        head = (head + requested) % data.size
    }

    /**
     * To avoid unnecessary copies, StreamBuffer writes at most until the "end" of the circular
     * buffer, which is suboptimal (it could have written more data if they have been contiguous).
     *
     * In order to minimize the occurrence of this event, reset the head and tail to 0 when the
     * buffer is empty (no copy is involved).
     *
     * This is especially useful when the StreamBuffer is used to read/write one packet at a time,
     * so the "end" of the buffer is guaranteed to never be reached.
     */
    private fun optimize() {
        if (isEmpty()) {
            head = 0
            tail = 0
        }
    }
}
