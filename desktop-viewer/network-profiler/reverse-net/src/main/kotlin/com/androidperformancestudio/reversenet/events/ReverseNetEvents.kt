package com.androidperformancestudio.reversenet.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

public enum class ReverseNetTransport { TCP, UDP }

public enum class ReverseNetAlertKind { V6_LEAK, PRIVATE_DNS_DEGRADED }

public sealed class ReverseNetEvent {
    public abstract val sequence: Long
    public abstract val monotonicNs: Long

    public data class Connection(
        val connectionId: Long,
        val transport: ReverseNetTransport,
        val srcAddress: String,
        val srcPort: Int,
        val dstAddress: String,
        val dstPort: Int,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()

    public data class State(
        val connectionId: Long,
        val state: String,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()

    public data class Bytes(
        val connectionId: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()

    public data class Closed(
        val connectionId: Long,
        val durationNs: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()

    public data class DnsResolved(
        val domain: String,
        val addresses: List<String>,
        val aaaaStripped: Boolean,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()

    public data class Alert(
        val alertKind: ReverseNetAlertKind,
        val message: String,
        override val monotonicNs: Long,
        override val sequence: Long = 0,
    ) : ReverseNetEvent()
}

/**
 * Bounded async event emitter. Emissions never block callers: when the
 * internal buffer is full the newest events are dropped and counted,
 * mirroring the network agent EventBuffer contract.
 */
public class ReverseNetEventBus {
    private val capacity: Int = 20_000
    private val sequenceGen = AtomicLong()
    private val droppedCount = AtomicLong()
    private val flow = MutableSharedFlow<ReverseNetEvent>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public val events: SharedFlow<ReverseNetEvent> = flow.asSharedFlow()

    public val droppedEvents: Long
        get() = droppedCount.get()

    public fun emit(event: ReverseNetEvent): ReverseNetEvent {
        val sequenced = when (event.sequence) {
            0L -> withSequence(event, sequenceGen.incrementAndGet())
            else -> event
        }
        flow.tryEmit(sequenced)
        return sequenced
    }

    private fun withSequence(event: ReverseNetEvent, sequence: Long): ReverseNetEvent = when (event) {
        is ReverseNetEvent.Connection -> event.copy(sequence = sequence)
        is ReverseNetEvent.State -> event.copy(sequence = sequence)
        is ReverseNetEvent.Bytes -> event.copy(sequence = sequence)
        is ReverseNetEvent.Closed -> event.copy(sequence = sequence)
        is ReverseNetEvent.DnsResolved -> event.copy(sequence = sequence)
        is ReverseNetEvent.Alert -> event.copy(sequence = sequence)
    }
}
