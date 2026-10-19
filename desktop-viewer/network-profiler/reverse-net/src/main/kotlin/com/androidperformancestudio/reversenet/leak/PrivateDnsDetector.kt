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
package com.androidperformancestudio.reversenet.leak

import com.androidperformancestudio.reversenet.events.ReverseNetAlertKind
import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus

/**
 * Detects Private DNS (DoT) on the device: when enabled, DNS flowing through the tunnel
 * disappears (device resolves over its own encrypted channel), degrading both DNS
 * observability and AAAA filtering.
 */
public class PrivateDnsDetector(
    private val eventBus: ReverseNetEventBus,
    private val probe: DeviceProbe,
) {
    /**
     * Probe the device's private DNS setting; emits a degradation alert in strict (hostname) mode.
     *
     * @return true when Private DNS degraded the session
     */
    public suspend fun checkAndAlert(serial: String): Boolean {
        val mode = probe.shell(serial, listOf("settings", "get", "global", "private_dns_mode"))
        if (mode != STRICT_MODE_HOSTNAME) {
            return false
        }
        eventBus.emit(
            ReverseNetEvent.Alert(
                alertKind = ReverseNetAlertKind.PRIVATE_DNS_DEGRADED,
                message = DEGRADED_MESSAGE,
                monotonicNs = System.nanoTime(),
            ),
        )
        return true
    }

    public companion object {
        public const val STRICT_MODE_HOSTNAME: String = "hostname"
        public const val DEGRADED_MESSAGE: String =
            "设备启用了 Private DNS，DNS 观测与 IPv6 防泄漏已降级。建议关闭 Private DNS 或改为自动模式。"
    }
}
