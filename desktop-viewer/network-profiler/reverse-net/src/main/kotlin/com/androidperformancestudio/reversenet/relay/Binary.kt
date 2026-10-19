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

public object Binary {
    public fun copy(buffer: ByteBuffer): ByteBuffer {
        buffer.rewind()
        val result = ByteBuffer.allocate(buffer.remaining())
        result.put(buffer)
        buffer.rewind()
        result.flip()
        return result
    }

    public fun slice(buffer: ByteBuffer, offset: Int, length: Int): ByteBuffer {
        // save
        val position = buffer.position()
        val limit = buffer.limit()

        // slice
        buffer.limit(offset + length).position(offset)
        val result = buffer.slice()

        // restore
        buffer.limit(limit).position(position)

        return result
    }
}
