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

rootProject.name = "android-performance-studio"

include(
    ":app-desktop",
    ":simpleperf-presentation",
    ":simpleperf-application",
    ":capture-simpleperf",
    ":parser-simpleperf-proto",
    ":profile-model",
    ":profile-analysis",
    ":simpleperf-storage-sqlite",
    ":analysis-rules",
    ":visualization",
    ":simpleperf-export-adapters",
    ":simpleperf-test-fixtures",
    ":parser-art-trace",
    ":capture-method-trace",
    ":method-recording-app",
)

project(":simpleperf-presentation").projectDir = file("presentation")
project(":simpleperf-application").projectDir = file("application")
project(":simpleperf-storage-sqlite").projectDir = file("storage-sqlite")
project(":simpleperf-export-adapters").projectDir = file("export-adapters")
project(":simpleperf-test-fixtures").projectDir = file("test-fixtures")
project(":parser-art-trace").projectDir = file("parser-art-trace")
project(":capture-method-trace").projectDir = file("capture-method-trace")
project(":method-recording-app").projectDir = file("method-recording-app")

includeBuild("../ui-components") {
    name = "simpleperf-ui-components"
}

includeBuild("../platform-core") {
    name = "simpleperf-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "simpleperf-platform-perfetto"
}
