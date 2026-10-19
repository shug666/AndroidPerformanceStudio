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

import java.nio.file.Files
import java.nio.file.Path

/** Locates vendored resources of the reverse-net module. */
public object ReverseNetPaths {
    private val CANDIDATE_ROOTS = listOf(
        "third_party/gnirehtet",
        "../third_party/gnirehtet",
        "../../third_party/gnirehtet",
        "../../../third_party/gnirehtet",
    )

    /** Path to the vendored official gnirehtet.apk; null when the checkout layout differs. */
    public fun vendoredApk(): Path? {
        for (candidate in CANDIDATE_ROOTS) {
            val path = Path.of(candidate, "gnirehtet.apk")
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize()
            }
        }
        return null
    }
}
