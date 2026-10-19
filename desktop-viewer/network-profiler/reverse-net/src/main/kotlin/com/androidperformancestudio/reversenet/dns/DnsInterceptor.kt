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
package com.androidperformancestudio.reversenet.dns

import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import java.nio.ByteBuffer

/** Isolation mode deciding whether AAAA records are stripped from DNS responses. */
public enum class DnsFilterMode { RELAXED, ISOLATED }

/**
 * Parses UDP:53 traffic flowing through the relay. On responses it emits
 * domain-to-IP events and, in isolated mode, rewrites the payload to drop
 * AAAA records so the device never learns IPv6 addresses.
 */
public class DnsInterceptor(
    private val eventBus: ReverseNetEventBus,
    private val mode: DnsFilterMode,
) {
    public fun isDnsPort(port: Int): Boolean = port == DNS_PORT

    /**
     * Handle a DNS response flowing from the network towards the device.
     *
     * @return the payload to forward: the stripped payload in isolated mode when AAAA
     *         records were present, otherwise the original payload unchanged.
     */
    public fun onResponse(payload: ByteBuffer): ByteBuffer {
        val response = DnsMessage.parse(payload, stripAaaa = mode == DnsFilterMode.ISOLATED)
            ?: return payload

        eventBus.emit(
            ReverseNetEvent.DnsResolved(
                domain = response.questionName,
                addresses = response.aRecords.map { formatIpv4(it.addressInt) },
                aaaaStripped = response.aaaaStripped,
                monotonicNs = System.nanoTime(),
            ),
        )

        val stripped = response.strippedPayload
        return if (stripped != null) ByteBuffer.wrap(stripped) else payload
    }

    /** True when the payload is a DNS query from the device worth tapping (queries are not parsed). */
    public fun isResponse(payload: ByteBuffer): Boolean = DnsMessage.isResponse(payload)

    private fun formatIpv4(ip: Int): String =
        ((ip shr 24) and 0xff).toString() + "." + ((ip shr 16) and 0xff) + "." +
            ((ip shr 8) and 0xff) + "." + (ip and 0xff)

    public companion object {
        public const val DNS_PORT: Int = 53
    }
}
