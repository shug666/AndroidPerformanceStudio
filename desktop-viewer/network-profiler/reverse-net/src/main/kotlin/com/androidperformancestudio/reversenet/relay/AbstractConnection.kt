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

import java.net.InetAddress
import java.net.InetSocketAddress

public abstract class AbstractConnection(
    public final override val id: ConnectionId,
    protected val client: Client,
) : Connection {
    protected fun close() {
        disconnect()
        client.router.remove(this)
    }

    protected fun consume(source: PacketSource) {
        client.consume(source)
    }

    protected fun sendToClient(packet: IPv4Packet): Boolean = client.sendToClient(packet)

    public companion object {
        // 10.0.2.2 must be forwarded to localhost
        private const val LOCALHOST_FORWARD = 0x0a000202
    }

    private fun getRewrittenAddress(ip: Int): InetAddress =
        if (ip == LOCALHOST_FORWARD) InetAddress.getLoopbackAddress() else Net.toInetAddress(ip)

    /**
     * Get destination, rewritten to `localhost` if it was `10.0.2.2`.
     */
    protected fun getRewrittenDestination(): InetSocketAddress {
        val destIp = id.getDestinationIp()
        val port = id.getDestinationPort()
        return InetSocketAddress(getRewrittenAddress(destIp), port)
    }
}
