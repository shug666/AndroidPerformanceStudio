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

rootProject.name = "layout-inspector"

include(
    ":adb-gateway",
    ":application",
    ":presentation",
    ":protocol-model",
    ":compose-inspection-model",
    ":compose-inspection-host",
    ":analysis-engine",
    ":test-fixtures",
    ":compose-agent-build",
    ":mcp-server",
)

project(":adb-gateway").projectDir = file("adb-gateway")
project(":application").projectDir = file("application")
project(":presentation").projectDir = file("presentation")
project(":protocol-model").projectDir = file("shared-kernel/protocol-model")
project(":compose-inspection-model").projectDir = file("shared-kernel/compose-inspection-model")
project(":compose-inspection-host").projectDir = file("compose-inspection-host")
project(":analysis-engine").projectDir = file("shared-kernel/analysis-engine")
project(":test-fixtures").projectDir = file("shared-kernel/test-fixtures")
project(":compose-agent-build").projectDir = file("compose-agent-build")
project(":mcp-server").projectDir = file("mcp-server")

// Shared composite builds for dependency resolution
includeBuild("../ui-components") {
    name = "layout-inspector-ui-components"
}
includeBuild("../ai-core") {
    name = "layout-inspector-ai-core"
}
includeBuild("../platform-core") {
    name = "layout-inspector-platform-core"
}
