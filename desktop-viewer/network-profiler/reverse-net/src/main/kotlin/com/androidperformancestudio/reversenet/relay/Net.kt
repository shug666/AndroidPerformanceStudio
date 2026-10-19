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
import java.net.UnknownHostException

public object Net {
    public fun toInetAddress(address: String): InetAddress = try {
        InetAddress.getByName(address)
    } catch (e: UnknownHostException) {
        throw IllegalArgumentException(e)
    }

    public fun toInetAddress(raw: ByteArray): InetAddress = try {
        InetAddress.getByAddress(raw)
    } catch (e: UnknownHostException) {
        throw IllegalArgumentException(e)
    }

    public fun toInetAddress(ipAddr: Int): InetAddress {
        val ip = byteArrayOf(
            (ipAddr ushr 24).toByte(),
            ((ipAddr shr 16) and 0xff).toByte(),
            ((ipAddr shr 8) and 0xff).toByte(),
            (ipAddr and 0xff).toByte(),
        )
        return toInetAddress(ip)
    }

    public fun toInetSocketAddress(address: InetSocketAddress): String =
        address.address.hostAddress + ":" + address.port

    public fun toString(ip: Int, port: Short): String =
        toString(ip, port.toInt() and 0xffff)

    public fun toString(ip: Int, port: Int): String =
        toInetAddress(ip).hostAddress + ":" + port
}
