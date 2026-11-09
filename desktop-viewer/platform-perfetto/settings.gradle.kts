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

rootProject.name = "platform-perfetto"

include(":platform-perfetto")

includeBuild("../platform-core") {
    name = "platform-perfetto-core"
}
