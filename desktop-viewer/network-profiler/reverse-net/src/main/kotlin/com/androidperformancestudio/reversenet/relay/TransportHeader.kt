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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. * distributed under the License is distributed on an "AS IS" BASIS,

 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.androidperformancestudio.reversenet.relay

import java.nio.ByteBuffer

public interface TransportHeader {
    public fun getSourcePort(): Int

    public fun getDestinationPort(): Int

    public fun setSourcePort(port: Int)

    public fun setDestinationPort(port: Int)

    public fun getHeaderLength(): Int

    public fun setPayloadLength(payloadLength: Int)

    public fun getRaw(): ByteBuffer

    public fun copyTo(buffer: ByteBuffer): TransportHeader

    public fun computeChecksum(ipv4Header: IPv4Header, payload: ByteBuffer)

    public fun swapSourceAndDestination() {
        val tmp = getSourcePort()
        setSourcePort(getDestinationPort())
        setDestinationPort(tmp)
    }
}
