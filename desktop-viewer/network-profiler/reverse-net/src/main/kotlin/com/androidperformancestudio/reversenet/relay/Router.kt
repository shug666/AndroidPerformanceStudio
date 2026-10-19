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
@file:Suppress("SwallowedException")

package com.androidperformancestudio.reversenet.relay

import java.io.IOException
import java.nio.channels.Selector
import java.util.ArrayList

public class Router(
    private val client: Client,
    private val selector: Selector,
    private val listener: RelayListener,
) {
    // there are typically only few connections per client, HashMap would be less efficient
    private val connections: MutableList<Connection> = ArrayList()

    public fun sendToNetwork(packet: IPv4Packet) {
        if (!packet.isValid()) {
            listener.onPacketDropped(packet)
            return
        }
        try {
            val connection = getConnection(packet.ipv4Header, packet.transportHeader!!)
            connection.sendToNetwork(packet)
        } catch (e: IOException) {
            listener.onPacketDropped(packet)
        }
    }

    @Throws(IOException::class)
    private fun getConnection(ipv4Header: IPv4Header, transportHeader: TransportHeader): Connection {
        val id = ConnectionId.from(ipv4Header, transportHeader)
        val existing = find(id)
        if (existing != null) {
            return existing
        }
        val connection = createConnection(id, ipv4Header, transportHeader)
        connections.add(connection)
        listener.onConnectionOpened(connection, id)
        return connection
    }

    @Throws(IOException::class)
    private fun createConnection(id: ConnectionId, ipv4Header: IPv4Header, transportHeader: TransportHeader): Connection =
        when (id.protocol) {
            IPv4Header.Protocol.UDP -> UDPConnection(id, client, selector, ipv4Header, transportHeader as UDPHeader)
            IPv4Header.Protocol.TCP -> TCPConnection(id, client, selector, ipv4Header, transportHeader as TCPHeader)
            IPv4Header.Protocol.OTHER -> throw UnsupportedOperationException("Unsupported protocol: " + id.protocol)
        }

    private fun find(id: ConnectionId): Connection? {
        for (connection in connections) {
            if (id == connection.id) {
                return connection
            }
        }
        return null
    }

    public fun clear() {
        for (connection in connections) {
            connection.disconnect()
        }
        connections.clear()
    }

    public fun remove(connection: Connection) {
        if (!connections.remove(connection)) {
            throw AssertionError("Removed a connection unknown from the router")
        }
        listener.onConnectionClosed(connection, id = connection.id)
    }

    public fun cleanExpiredConnections() {
        for (i in connections.indices.reversed()) {
            val connection = connections[i]
            if (connection.isExpired()) {
                connection.disconnect()
                connections.removeAt(i)
            }
        }
    }
}
