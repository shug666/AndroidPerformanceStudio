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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.util.ArrayList

/**
 * Handle the connections from the clients.
 */
public class TunnelServer
    @Throws(IOException::class)
    constructor(
        port: Int,
        selector: Selector,
        private val listener: RelayListener,
    ) {
        private val clients: MutableList<Client> = ArrayList()
        private val serverSocketChannel: ServerSocketChannel

        init {
            val opened = ServerSocketChannel.open()
            opened.configureBlocking(false)
            // sessions restart frequently; allow immediate rebinding of the listening socket
            opened.socket().reuseAddress = true
            opened.socket().bind(InetSocketAddress(Inet4Address.getLoopbackAddress(), port))
            serverSocketChannel = opened

            val socketChannelHandler = SelectionHandler { selectionKey ->
                try {
                    val serverChannel = selectionKey.channel() as ServerSocketChannel
                    acceptClient(selector, serverChannel)
                } catch (e: IOException) {
                    // cannot accept client
                }
            }
            opened.register(selector, SelectionKey.OP_ACCEPT, socketChannelHandler)
        }

        @Throws(IOException::class)
        private fun acceptClient(selector: Selector, serverSocketChannel: ServerSocketChannel) {
            val socketChannel = serverSocketChannel.accept()
            socketChannel.configureBlocking(false)
            // will register the socket on the selector
            val client = Client(selector, socketChannel, listener) { c -> removeClient(c) }
            clients.add(client)
            listener.onClientConnected(client.getId())
        }

        private fun removeClient(client: Client) {
            clients.remove(client)
            listener.onClientDisconnected(client.getId())
        }

        public fun cleanUp() {
            for (client in clients) {
                client.cleanExpiredConnections()
            }
        }

        public fun isListeningSocketOpen(): Boolean = serverSocketChannel.isOpen

        /** Closes the listening socket and disconnects every client. */
        public fun close() {
            for (client in clients.toList()) {
                client.close()
            }
            clients.clear()
            try {
                serverSocketChannel.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }
