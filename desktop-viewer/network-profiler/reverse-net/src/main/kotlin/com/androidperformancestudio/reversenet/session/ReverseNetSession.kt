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
package com.androidperformancestudio.reversenet.session

import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle state of a reverse-tethering session for one device. */
public sealed class ReverseNetSessionState {
    public data object Idle : ReverseNetSessionState()

    public data object Starting : ReverseNetSessionState()

    /** Waiting for the user to confirm the VPN dialog on the device. */
    public data object AwaitingVpnAuthorization : ReverseNetSessionState()

    /** Tunnel established; device traffic is flowing through the relay. */
    public data object Sharing : ReverseNetSessionState()

    public data object Stopping : ReverseNetSessionState()

    public data class Error(
        val message: String,
    ) : ReverseNetSessionState()
}

/** Configuration for one reverse-tethering session. */
public data class ReverseNetSessionConfig(
    val isolationMode: DnsFilterMode,
    val vpnAuthorizationTimeoutMs: Long = DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS,
) {
    public companion object {
        public const val DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS: Long = 15_000
    }
}

/** Source of truth for session state, consumed by the UI as a StateFlow. */
public interface ReverseNetSessionStore {
    public val state: StateFlow<ReverseNetSessionState>
}
