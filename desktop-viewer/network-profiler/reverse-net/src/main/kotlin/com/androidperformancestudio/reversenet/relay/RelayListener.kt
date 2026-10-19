/*
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

/**
 * Observation hooks invoked on the relay forwarding hot path. Implementations
 * MUST NOT block: emit into bounded queues only.
 */
public interface RelayListener {
    public fun onClientConnected(clientId: Int)

    public fun onClientDisconnected(clientId: Int)

    public fun onConnectionOpened(connection: Connection, id: ConnectionId)

    public fun onConnectionClosed(connection: Connection, id: ConnectionId)

    public fun onPacketDropped(packet: IPv4Packet)

    public companion object {
        public val NONE: RelayListener = object : RelayListener {
            override fun onClientConnected(clientId: Int) {}

            override fun onClientDisconnected(clientId: Int) {}

            override fun onConnectionOpened(connection: Connection, id: ConnectionId) {}

            override fun onConnectionClosed(connection: Connection, id: ConnectionId) {}

            override fun onPacketDropped(packet: IPv4Packet) {}
        }
    }
}
