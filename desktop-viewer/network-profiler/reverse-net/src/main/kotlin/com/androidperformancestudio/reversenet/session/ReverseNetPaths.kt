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
@file:Suppress("ReturnCount")

package com.androidperformancestudio.reversenet.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Locates the vendored gnirehtet.apk for the reverse-net module. */
public object ReverseNetPaths {
    private const val RESOURCE_PATH = "/gnirehtet.apk"
    private const val APK_FILE_NAME = "gnirehtet.apk"

    /** Dev-mode candidate roots (a checkout run, not an installed package). */
    private val DEV_CANDIDATE_ROOTS = listOf(
        "third_party/gnirehtet",
        "../third_party/gnirehtet",
        "../../third_party/gnirehtet",
        "../../../third_party/gnirehtet",
    )

    /** Directory used to cache the extracted apk in an installed package. */
    private val cacheDir: Path by lazy {
        Path.of(System.getProperty("user.home"), ".android-performance-studio", "reverse-net")
    }

    /**
     * Path to the vendored gnirehtet.apk.
     *
     * Dev mode (run from a checkout): returns the file under third_party/.
     * Installed package: extracts the apk from the jar classpath resource into
     * ~/.android-performance-studio/reverse-net/ on first use and returns the cached path.
     */
    public fun vendoredApk(): Path? {
        // 1. dev mode: read straight from the checkout
        for (candidate in DEV_CANDIDATE_ROOTS) {
            val path = Path.of(candidate, APK_FILE_NAME)
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize()
            }
        }
        // 2. installed package: extract from classpath resource, cache on disk
        return extractFromResource()
    }

    private fun extractFromResource(): Path? {
        val cached = cacheDir.resolve(APK_FILE_NAME)
        if (Files.isRegularFile(cached) && Files.size(cached) == RESOURCE_SIZE) {
            return cached
        }
        val resource = ReverseNetPaths::class.java.getResourceAsStream(RESOURCE_PATH) ?: return null
        resource.use { input ->
            Files.createDirectories(cacheDir)
            Files.copy(input, cached, StandardCopyOption.REPLACE_EXISTING)
        }
        return cached.takeIf { Files.isRegularFile(it) }
    }

    private const val RESOURCE_SIZE: Long = 23_904L
}
