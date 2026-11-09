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

rootProject.name = "frame-profiler"

include(
    ":frame-model",
    ":frame-agent-protocol",
    ":capture-frame",
    ":parser-frame",
    ":analysis-frame",
    ":frame-storage-sqlite",
    ":frame-export-adapters",
    ":frame-presentation",
    ":frame-app",
)

project(":frame-storage-sqlite").projectDir = file("storage-sqlite")
project(":frame-export-adapters").projectDir = file("export-adapters")
project(":frame-presentation").projectDir = file("presentation")

// Resolves the shared ADB and host process tooling for standalone Frame Profiler builds.
includeBuild("../platform-core") {
    name = "frame-profiler-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "frame-profiler-platform-perfetto"
}

includeBuild("../ui-components") {
    name = "frame-profiler-ui-components"
}
