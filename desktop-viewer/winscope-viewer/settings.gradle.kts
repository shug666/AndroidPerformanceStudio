pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Prefer the Alibaba Cloud mirror when Google's Maven endpoint is unavailable.
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Prefer the Alibaba Cloud mirror when Google's Maven endpoint is unavailable.
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        google()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "winscope-viewer"

include(
    ":winscope-app",
    ":winscope-capture-service",
    ":winscope-device-capture",
    ":winscope-resources",
)

// Resolves the shared ADB and host process tooling for standalone Winscope builds.
includeBuild("../platform-core") {
    name = "winscope-platform-core"
}

includeBuild("../ui-components") {
    name = "winscope-ui-components"
}
