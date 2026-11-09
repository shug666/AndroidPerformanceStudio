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

rootProject.name = "network-profiler"

include(
    ":network-model",
    ":network-agent-protocol",
    ":network-instrumentation",
    ":capture-network",
    ":parser-har",
    ":analysis-network",
    ":network-storage-sqlite",
    ":network-export-adapters",
    ":network-presentation",
    ":network-app",
    ":reverse-net",
)

// The Android capture agent requires AGP and is not needed for desktop-only analysis/import work.
// Enable it with -PincludeNetworkAgent=true when building the device-side agent.
if (providers.gradleProperty("includeNetworkAgent").orNull == "true") {
    include(":android-agent-network")
}

project(":network-storage-sqlite").projectDir = file("storage-sqlite")
project(":network-export-adapters").projectDir = file("export-adapters")
project(":network-presentation").projectDir = file("presentation")

includeBuild("../platform-core") {
    name = "network-profiler-platform-core"
}

includeBuild("../ui-components") {
    name = "network-profiler-ui-components"
}
