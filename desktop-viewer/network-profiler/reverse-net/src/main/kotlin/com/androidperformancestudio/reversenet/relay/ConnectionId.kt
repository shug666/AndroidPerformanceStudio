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

public class ConnectionId(
    public val protocol: IPv4Header.Protocol,
    private val sourceIpValue: Int,
    private val sourcePortRaw: Short,
    private val destIpValue: Int,
    private val destPortRaw: Short,
) {
    private val idString: String = protocol.toString() + " " + Net.toString(sourceIpValue, sourcePortRaw) +
        " -> " + Net.toString(destIpValue, destPortRaw)

    public fun getSourceIp(): Int = sourceIpValue

    public fun getSourcePort(): Int = sourcePortRaw.toInt() and 0xffff

    public fun getDestinationIp(): Int = destIpValue

    public fun getDestinationPort(): Int = destPortRaw.toInt() and 0xffff

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionId) return false
        return sourceIpValue == other.sourceIpValue &&
            sourcePortRaw == other.sourcePortRaw &&
            destIpValue == other.destIpValue &&
            destPortRaw == other.destPortRaw &&
            protocol == other.protocol
    }

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + sourceIpValue
        result = 31 * result + sourcePortRaw.toInt()
        result = 31 * result + destIpValue
        result = 31 * result + destPortRaw.toInt()
        return result
    }

    override fun toString(): String = idString

    public companion object {
        public fun from(ipv4Header: IPv4Header, transportHeader: TransportHeader): ConnectionId =
            ConnectionId(
                protocol = ipv4Header.protocol,
                sourceIpValue = ipv4Header.getSource(),
                sourcePortRaw = transportHeader.getSourcePort().toShort(),
                destIpValue = ipv4Header.getDestination(),
                destPortRaw = transportHeader.getDestinationPort().toShort(),
            )
    }
}
