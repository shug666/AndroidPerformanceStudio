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
@file:Suppress("TooManyFunctions", "SwallowedException", "ReturnCount", "ProtectedMemberInFinalClass")

package com.androidperformancestudio.reversenet.relay

import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.random.Random

public class TCPConnection(
    id: ConnectionId,
    client: Client,
    selector: Selector,
    ipv4Header: IPv4Header,
    tcpHeader: TCPHeader,
) : AbstractConnection(id, client),
    PacketSource {
    public companion object {
        // same value as GnirehtetService.MTU in the client
        private const val MTU = 0x4000

        // 20 bytes for IP headers, 20 bytes for TCP headers
        private const val MAX_PAYLOAD_SIZE = MTU - 20 - 20

        private val RANDOM = Random.Default
    }

    /**
     * See <a href="https://tools.ietf.org/html/rfc793#page-23">RFC793</a>.
     */
    public enum class State {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
        CLOSING,
        FIN_WAIT_1,
        FIN_WAIT_2,
        ;

        public fun isConnected(): Boolean = this != SYN_SENT && this != SYN_RECEIVED

        public fun isClosed(): Boolean =
            this == FIN_WAIT_1 || this == FIN_WAIT_2 || this == CLOSING || this == LAST_ACK
    }

    private val clientToNetwork = StreamBuffer(4 * IPv4Packet.MAX_PACKET_LENGTH)
    private val networkToClient: Packetizer
    private var packetForClient: IPv4Packet? = null

    private val channel: SocketChannel
    private val selectionKey: SelectionKey
    private var interests: Int

    private var state: State? = null
    private var synSequenceNumber = 0
    private var sequenceNumber = 0
    private var acknowledgementNumber = 0
    private var theirAcknowledgementNumber = 0
    private var finSequenceNumber: Int? = null // null means "no FIN sent yet"
    private var finReceived = false
    private var clientWindow = 0

    init {
        val shrinkedTcpHeader = tcpHeader.copy()
        shrinkedTcpHeader.shrinkOptions() // no TCP options

        networkToClient = Packetizer(ipv4Header, shrinkedTcpHeader)
        networkToClient.responseIPv4Header.swapSourceAndDestination()
        (networkToClient.responseTransportHeader as TCPHeader).swapSourceAndDestination()

        val selectionHandler = SelectionHandler { key ->
            if (key.isValid && key.isConnectable) {
                processConnect()
            }
            if (key.isValid && key.isReadable) {
                processReceive()
            }
            if (key.isValid && key.isWritable) {
                processSend()
            }
            updateInterests()
        }
        channel = createChannel()
        // interests will be set on the first packet received
        // set the initial value now so that they won't need to be updated
        interests = SelectionKey.OP_CONNECT
        selectionKey = channel.register(selector, interests, selectionHandler)
    }

    override fun disconnect() {
        selectionKey.cancel()
        try {
            channel.close()
        } catch (e: IOException) {
            // cannot close connection channel
        }
    }

    private fun processReceive() {
        try {
            assert(packetForClient == null) { "The IPv4Packet shares the networkToClient buffer, it must not be corrupted" }
            val remainingClientWindow = getRemainingClientWindow()
            assert(remainingClientWindow > 0) { "If remainingClientWindow is 0, then processReceive() should not have been called" }
            val maxPayloadSize = minOf(remainingClientWindow, MAX_PAYLOAD_SIZE)
            updateHeaders(TCPHeader.FLAG_ACK or TCPHeader.FLAG_PSH)
            val packet = networkToClient.packetize(channel, maxPayloadSize)
            if (packet == null) {
                eof()
                return
            }
            addReceived(packet.getPayloadLength().toLong())
            packetForClient = packet
            consume(this)
        } catch (e: IOException) {
            resetConnection()
        }
    }

    private fun processSend() {
        try {
            val w = clientToNetwork.writeTo(channel)
            if (w > 0) {
                addSent(w.toLong())
                acknowledgementNumber += w

                if (finReceived && clientToNetwork.isEmpty()) {
                    doHandleFin()
                } else {
                    sendEmptyPacketToClient(TCPHeader.FLAG_ACK)
                }
            } else {
                close()
            }
        } catch (e: IOException) {
            resetConnection()
        }
    }

    private fun eof() {
        sendEmptyPacketToClient(TCPHeader.FLAG_FIN or TCPHeader.FLAG_ACK)

        finSequenceNumber = sequenceNumber
        ++sequenceNumber // FIN counts for 1 byte
        state = when (state) {
            State.CLOSE_WAIT -> State.LAST_ACK
            else -> State.FIN_WAIT_1
        }
    }

    private fun getRemainingClientWindow(): Int {
        // in Kotlin, (signed) integer overflow is well-defined: it wraps around
        val remaining = theirAcknowledgementNumber + clientWindow - sequenceNumber
        if (remaining < 0 || remaining > clientWindow) {
            // our sequence number is outside their window
            return 0
        }
        return remaining
    }

    override fun isExpired(): Boolean = false // no external timeout expiration

    private fun updateHeaders(flags: Int) {
        val tcpHeader = networkToClient.responseTransportHeader as TCPHeader
        tcpHeader.setFlags(flags)
        tcpHeader.setSequenceNumber(sequenceNumber)
        tcpHeader.setAcknowledgementNumber(acknowledgementNumber)
    }

    private fun createChannel(): SocketChannel {
        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        socketChannel.connect(getRewrittenDestination())
        return socketChannel
    }

    override fun sendToNetwork(packet: IPv4Packet) {
        handlePacket(packet)
        updateInterests()
    }

    private fun handlePacket(packet: IPv4Packet) {
        val tcpHeader = packet.transportHeader as TCPHeader
        if (state == null) {
            handleFirstPacket(packet)
            return
        }

        if (tcpHeader.isSyn()) {
            // the client always initiates the connection
            // at this point, any SYN packet received is duplicate
            handleDuplicateSyn(packet)
            return
        }

        val packetSequenceNumber = tcpHeader.getSequenceNumber()
        val expectedPacket = acknowledgementNumber + clientToNetwork.size()
        if (packetSequenceNumber != expectedPacket) {
            // ignore packet already received or out-of-order, retransmission is already managed by both sides
            return
        }

        clientWindow = tcpHeader.getWindow()
        theirAcknowledgementNumber = tcpHeader.getAcknowledgementNumber()

        if (tcpHeader.isRst()) {
            close()
            return
        }

        if (tcpHeader.isAck()) {
            handleAck(packet)
        }

        if (tcpHeader.isFin()) {
            handleFin()
        }

        val finSeq = finSequenceNumber
        if (finSeq != null && tcpHeader.getAcknowledgementNumber() == finSeq + 1) {
            handleFinAck()
        }
    }

    private fun handleFirstPacket(packet: IPv4Packet) {
        val tcpHeader = packet.transportHeader as TCPHeader
        if (!tcpHeader.isSyn()) {
            sequenceNumber = tcpHeader.getAcknowledgementNumber() // make a RST in the window client
            resetConnection()
            return
        }

        val theirSequenceNumber = tcpHeader.getSequenceNumber()
        acknowledgementNumber = theirSequenceNumber + 1
        synSequenceNumber = theirSequenceNumber

        sequenceNumber = RANDOM.nextInt()
        clientWindow = tcpHeader.getWindow()
        state = State.SYN_SENT
    }

    private fun handleDuplicateSyn(packet: IPv4Packet) {
        val tcpHeader = packet.transportHeader as TCPHeader
        val theirSequenceNumber = tcpHeader.getSequenceNumber()
        if (state == State.SYN_SENT) {
            // we didn't call finishConnect() yet, we can accept this packet as if it were the first SYN
            synSequenceNumber = theirSequenceNumber
            acknowledgementNumber = theirSequenceNumber + 1
        } else if (theirSequenceNumber != synSequenceNumber) {
            // duplicate SYN with different sequence number
            resetConnection()
        }
    }

    private fun handleFin() {
        finReceived = true
        if (clientToNetwork.isEmpty()) {
            doHandleFin()
        }
        // otherwise, the FIN will be processed once clientToNetwork is empty
    }

    private fun doHandleFin() {
        ++acknowledgementNumber // received FIN counts for 1 byte

        when (state) {
            State.ESTABLISHED -> {
                sendEmptyPacketToClient(TCPHeader.FLAG_FIN or TCPHeader.FLAG_ACK)
                finSequenceNumber = sequenceNumber
                ++sequenceNumber // FIN counts for 1 byte
                // do not wait for the real network connection, switch immediately to LAST_ACK (bypass CLOSE_WAIT)
                state = State.LAST_ACK
            }
            State.FIN_WAIT_1 -> {
                sendEmptyPacketToClient(TCPHeader.FLAG_ACK)
                state = State.CLOSING
            }
            State.FIN_WAIT_2 -> {
                sendEmptyPacketToClient(TCPHeader.FLAG_ACK)
                close()
            }
            else -> {
                // received FIN while in unexpected state
            }
        }
    }

    private fun handleFinAck() {
        when (state) {
            State.LAST_ACK, State.CLOSING -> close()
            State.FIN_WAIT_1 -> {
                state = State.FIN_WAIT_2
            }
            else -> {
                // received FIN ACK while in unexpected state
            }
        }
    }

    private fun handleAck(packet: IPv4Packet) {
        if (state == State.SYN_RECEIVED) {
            state = State.ESTABLISHED
            return
        }

        val payloadLength = packet.getPayloadLength()
        if (payloadLength == 0) {
            // no data to transmit
            return
        }

        if (clientToNetwork.remaining() < payloadLength) {
            // not enough space, dropping packet
            return
        }

        clientToNetwork.readFrom(packet.getPayload())
        // data will be ACKed once written to the network socket
    }

    private fun processConnect() {
        if (!finishConnect()) {
            close()
            return
        }
        state = State.SYN_RECEIVED
        sendEmptyPacketToClient(TCPHeader.FLAG_SYN or TCPHeader.FLAG_ACK)
        ++sequenceNumber // SYN counts for 1 byte
    }

    private fun finishConnect(): Boolean = try {
        channel.finishConnect()
    } catch (e: IOException) {
        false
    }

    private fun resetConnection() {
        state = null
        sendEmptyPacketToClient(TCPHeader.FLAG_RST)
        close()
    }

    private fun createEmptyResponsePacket(flags: Int): IPv4Packet {
        updateHeaders(flags)
        return networkToClient.packetizeEmptyPayload()
    }

    private fun sendEmptyPacketToClient(flags: Int) {
        sendToClient(createEmptyResponsePacket(flags))
    }

    protected fun updateInterests() {
        if (!selectionKey.isValid) {
            return
        }
        var interestOps = 0
        if (mayRead()) {
            interestOps = interestOps or SelectionKey.OP_READ
        }
        if (mayWrite()) {
            interestOps = interestOps or SelectionKey.OP_WRITE
        }
        if (mayConnect()) {
            interestOps = interestOps or SelectionKey.OP_CONNECT
        }
        if (interests != interestOps) {
            // interests must be changed
            interests = interestOps
            selectionKey.interestOps(interestOps)
        }
    }

    private fun mayRead(): Boolean {
        val st = state ?: return false
        if (!st.isConnected() || st.isClosed()) {
            return false
        }
        if (packetForClient != null) {
            // a packet is already pending
            return false
        }
        return getRemainingClientWindow() > 0
    }

    private fun mayWrite(): Boolean = !clientToNetwork.isEmpty()

    private fun mayConnect(): Boolean = state == State.SYN_SENT

    override fun get(): IPv4Packet? {
        // TODO update only when necessary
        val packet = packetForClient ?: return null
        updateAcknowledgementNumber(packet)
        return packet
    }

    private fun updateAcknowledgementNumber(packet: IPv4Packet) {
        val tcpHeader = packet.transportHeader as TCPHeader
        tcpHeader.setAcknowledgementNumber(acknowledgementNumber)
        packet.computeChecksums()
    }

    override fun next() {
        val packet = packetForClient ?: return
        sequenceNumber += packet.getPayloadLength()
        packetForClient = null
        updateInterests()
    }
}
