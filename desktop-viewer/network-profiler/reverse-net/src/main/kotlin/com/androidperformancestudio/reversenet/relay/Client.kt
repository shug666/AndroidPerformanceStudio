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
@file:Suppress("TooManyFunctions", "SwallowedException", "ReturnCount")

package com.androidperformancestudio.reversenet.relay

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayList

public class Client
    @Throws(ClosedChannelException::class)
    constructor(
        selector: Selector,
        private val clientChannel: SocketChannel,
        private val listener: RelayListener,
        private val closeListener: CloseListener<Client>,
    ) {
        public companion object {
            private var nextId = 0

            private fun createIntBuffer(value: Int): ByteBuffer {
                val intSize = 4
                val buffer = ByteBuffer.allocate(intSize)
                buffer.putInt(value)
                buffer.flip()
                return buffer
            }
        }

        private val id: Int = nextId++

        private val clientToNetwork = IPv4PacketBuffer()
        private val networkToClient = StreamBuffer(16 * IPv4Packet.MAX_PACKET_LENGTH)

        private val pendingPacketSources: MutableList<PacketSource> = ArrayList()

        // store the remaining bytes of "id" to send to the client before relaying any data
        private var pendingIdBuffer: ByteBuffer? = createIntBuffer(id)

        public val router: Router = Router(this, selector, listener)

        private val selectionKey: SelectionKey
        private var interests: Int

        init {
            val selectionHandler = SelectionHandler { key ->
                if (key.isValid && key.isWritable) {
                    processSend()
                }
                if (channelValid(key)) {
                    processReceive()
                }
                if (key.isValid) {
                    updateInterests()
                }
            }
            // on start, we are interested only in writing (we must first send the client id)
            interests = SelectionKey.OP_WRITE
            selectionKey = clientChannel.register(selector, interests, selectionHandler)
        }

        private fun channelValid(key: SelectionKey): Boolean = key.isValid && key.isReadable

        public fun getId(): Int = id

        private fun processReceive() {
            if (!read()) {
                close()
                return
            }
            pushToNetwork()
        }

        private fun processSend() {
            if (mustSendId()) {
                if (!sendId()) {
                    close()
                }
                return
            }
            if (!write()) {
                close()
                return
            }
            processPending()
        }

        private fun read(): Boolean = try {
            val r = clientToNetwork.readFrom(clientChannel)
            r != -1
        } catch (e: IOException) {
            false
        }

        private fun write(): Boolean = try {
            networkToClient.writeTo(clientChannel) != -1
        } catch (e: IOException) {
            false
        }

        private fun mustSendId(): Boolean {
            val buffer = pendingIdBuffer
            return buffer != null && buffer.hasRemaining()
        }

        private fun sendId(): Boolean {
            assert(mustSendId())
            val buffer = pendingIdBuffer ?: return false
            return try {
                if (clientChannel.write(buffer) == -1) {
                    return false
                }
                if (!buffer.hasRemaining()) {
                    // we don't need this buffer anymore, release it
                    pendingIdBuffer = null
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        private fun pushToNetwork() {
            var packet = clientToNetwork.asIPv4Packet()
            while (packet != null) {
                router.sendToNetwork(packet)
                clientToNetwork.next()
                packet = clientToNetwork.asIPv4Packet()
            }
        }

        public fun close() {
            selectionKey.cancel()
            try {
                clientChannel.close()
            } catch (e: IOException) {
                // cannot close client connection
            }
            router.clear()
            closeListener.onClosed(this)
        }

        private fun updateInterests() {
            var interestOps = SelectionKey.OP_READ // we always want to read
            if (!networkToClient.isEmpty()) {
                interestOps = interestOps or SelectionKey.OP_WRITE
            }
            if (interests != interestOps) {
                // interests must be changed
                interests = interestOps
                selectionKey.interestOps(interestOps)
            }
        }

        public fun sendToClient(packet: IPv4Packet): Boolean {
            if (networkToClient.remaining() < packet.getRawLength()) {
                return false
            }
            networkToClient.readFrom(packet.getRaw())
            updateInterests()
            return true
        }

        public fun consume(source: PacketSource) {
            val packet = source.get()
            if (packet != null && sendToClient(packet)) {
                source.next()
                return
            }
            pendingPacketSources.add(source)
        }

        private fun processPending() {
            val iterator = pendingPacketSources.iterator()
            while (iterator.hasNext()) {
                val packetSource = iterator.next()
                val packet = packetSource.get()
                if (packet != null && sendToClient(packet)) {
                    packetSource.next()
                    iterator.remove()
                } else {
                    // client buffer full again
                    return
                }
            }
        }

        public fun cleanExpiredConnections() {
            router.cleanExpiredConnections()
        }
    }
