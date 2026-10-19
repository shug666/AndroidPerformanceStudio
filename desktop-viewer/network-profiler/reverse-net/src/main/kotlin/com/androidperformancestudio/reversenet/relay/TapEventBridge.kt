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
@file:Suppress("EmptyFunctionBlock")

package com.androidperformancestudio.reversenet.relay

import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import com.androidperformancestudio.reversenet.events.ReverseNetTransport
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges relay hot-path observation points to the bounded async event bus.
 * All callbacks run on relay selector threads; emission never blocks.
 */
public class TapEventBridge(
    private val eventBus: ReverseNetEventBus,
) : RelayListener {
    private val openedAtNs = ConcurrentHashMap<Long, Long>()

    override fun onClientConnected(clientId: Int) {
        // tunnel-level event; connection events carry the observability payload
    }

    override fun onClientDisconnected(clientId: Int) {}

    override fun onConnectionOpened(connection: Connection, id: ConnectionId) {
        val transport = when (id.protocol) {
            IPv4Header.Protocol.TCP -> ReverseNetTransport.TCP
            IPv4Header.Protocol.UDP -> ReverseNetTransport.UDP
            IPv4Header.Protocol.OTHER -> return
        }
        openedAtNs[id.hashCode().toLong()] = System.nanoTime()
        eventBus.emit(
            ReverseNetEvent.Connection(
                connectionId = id.hashCode().toLong(),
                transport = transport,
                srcAddress = Net.toString(id.getSourceIp(), id.getSourcePort()).substringBefore(':'),
                srcPort = id.getSourcePort(),
                dstAddress = Net.toString(id.getDestinationIp(), id.getDestinationPort()).substringBefore(':'),
                dstPort = id.getDestinationPort(),
                monotonicNs = System.nanoTime(),
            ),
        )
    }

    override fun onConnectionClosed(connection: Connection, id: ConnectionId) {
        val start = openedAtNs.remove(id.hashCode().toLong()) ?: System.nanoTime()
        eventBus.emit(
            ReverseNetEvent.Closed(
                connectionId = id.hashCode().toLong(),
                durationNs = System.nanoTime() - start,
                bytesSent = 0,
                bytesReceived = 0,
                monotonicNs = System.nanoTime(),
            ),
        )
    }

    override fun onPacketDropped(packet: IPv4Packet) {
        eventBus.emit(
            ReverseNetEvent.State(
                connectionId = -1,
                state = "PACKET_DROPPED",
                monotonicNs = System.nanoTime(),
            ),
        )
    }
}
