pluginManagement {
    repositories {
        // Aliyun mirrors serve networks where Google Maven / Maven Central are slow or
        // unreachable; on hosted CI a mirror 502 aborts resolution instead of falling
        // through, so CI resolves from the direct repositories first.
        if (System.getenv("GITHUB_ACTIONS") == null) {
            maven {
                name = "AliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "Aliyun"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        google()
        mavenCentral()
        if (System.getenv("GITHUB_ACTIONS") != null) {
            maven {
                name = "AliyunGoogleFallback"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "AliyunFallback"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Aliyun mirrors serve networks where Google Maven / Maven Central are slow or
        // unreachable; on hosted CI a mirror 502 aborts resolution instead of falling
        // through, so CI resolves from the direct repositories first.
        if (System.getenv("GITHUB_ACTIONS") == null) {
            maven {
                name = "AliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "Aliyun"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        google()
        mavenCentral()
        if (System.getenv("GITHUB_ACTIONS") != null) {
            maven {
                name = "AliyunGoogleFallback"
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                name = "AliyunFallback"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "memory-profiler"

include(
    ":memory-model",
    ":leakcanary-agent-protocol",
    ":capture-memory",
    ":parser-hprof",
    ":analysis-memory",
    ":memory-storage-sqlite",
    ":memory-export-adapters",
    ":memory-presentation",
    ":memory-app",
)

project(":memory-storage-sqlite").projectDir = file("storage-sqlite")
project(":memory-export-adapters").projectDir = file("export-adapters")
project(":memory-presentation").projectDir = file("presentation")

// The Android bridge is opt-in so desktop-app run/compile does not resolve the Android Gradle
// Plugin or mobile-only dependencies. Enable it with -PincludeLeakCanaryAgent=true.
if (providers.gradleProperty("includeLeakCanaryAgent").orNull == "true") {
    include(":android-agent-leakcanary")
    project(":android-agent-leakcanary").projectDir = file("android-agent-leakcanary")
}

// Makes standalone `./gradlew check` resolve neutral shared platform dependencies.
includeBuild("../platform-core") {
    name = "memory-profiler-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "memory-profiler-platform-perfetto"
}

includeBuild("../ui-components") {
    name = "memory-profiler-ui-components"
}
