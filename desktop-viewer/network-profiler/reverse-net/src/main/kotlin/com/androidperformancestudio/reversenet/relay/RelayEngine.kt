/*
 * Copyright (C) 2017 Genymobile
 * Copyright (C) 2026 Android Performance Studio contributors.
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
import java.nio.channels.Selector

public open class RelayEngine
    @Throws(IOException::class)
    constructor(
        private val port: Int,
        private val listener: RelayListener,
    ) {
        public companion object {
            private const val CLEANING_INTERVAL = 60 * 1000L
            public const val DEFAULT_PORT: Int = 31416
        }

        private val selector: Selector = Selector.open()
        private var tunnelServer: TunnelServer? = null

        @Volatile
        public var isRunning: Boolean = false
            private set

        public open fun start() {
            tunnelServer = TunnelServer(port, selector, listener)
            isRunning = true
        }

        public open fun runLoop() {
            var nextCleaningDeadline = System.currentTimeMillis() + UDPConnection.IDLE_TIMEOUT
            while (isRunning) {
                val timeout = maxOf(0, nextCleaningDeadline - System.currentTimeMillis())
                selector.select(timeout)
                val selectedKeys = selector.selectedKeys()

                val now = System.currentTimeMillis()
                if (now >= nextCleaningDeadline || selectedKeys.isEmpty()) {
                    tunnelServer?.cleanUp()
                    nextCleaningDeadline = now + CLEANING_INTERVAL
                }

                for (selectedKey in selectedKeys) {
                    val selectionHandler = selectedKey.attachment() as SelectionHandler
                    selectionHandler.onReady(selectedKey)
                }
                // by design, we handled everything
                selectedKeys.clear()
            }
        }

        public open fun stop() {
            isRunning = false
            selector.wakeup()
            tunnelServer?.close()
            try {
                selector.close()
            } catch (e: IOException) {
                // ignore close failure
            }
        }
    }
