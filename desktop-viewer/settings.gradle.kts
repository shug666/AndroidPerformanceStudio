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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
}

rootProject.name = "AndroidPerformanceStudio"

include(":desktop-app")

// Shared public Compose controls used by every application and presentation module.
includeBuild("ui-components") {
    name = "ui-components"
}

// Shared provider-neutral AI transport and structured response infrastructure.
includeBuild("ai-core") {
    name = "ai-core"
}

// Independent evidence-bound Agent Platform; no Profiler owns its runtime.
includeBuild("ai-platform") {
    name = "ai-platform"
}

// Shared import contracts and filesystem source validation.
includeBuild("source-workspace") {
    name = "source-workspace"
}
// Neutral profiler contracts and shared platform infrastructure live in one composite build.
includeBuild("platform-core") {
    name = "platform-core"
}

// Shared verified Perfetto capture configuration and Trace Processor analysis.
includeBuild("platform-perfetto") {
    name = "platform-perfetto"
}

// Layout Inspector is kept as an isolated composite build.
includeBuild("layout-inspector") {
    name = "layout-inspector"
}

// Android Agent modules and the sample application require the Android Gradle Plugin.
// Keep the desktop-only build independent of AGP; opt in with -PincludeLayoutInspectorAgents=true.
if (providers.gradleProperty("includeLayoutInspectorAgents").orNull == "true") {
    val layoutInspectorAgentModules =
        mapOf(
            ":layout-inspector-agent-core" to "layout-inspector/shared-kernel/android-agent-core",
            ":layout-inspector-agent-view" to "layout-inspector/shared-kernel/android-agent-view",
            ":layout-inspector-agent-frame" to "layout-inspector/shared-kernel/android-agent-frame",
            ":layout-inspector-agent-startup" to "layout-inspector/shared-kernel/android-agent-startup",
            ":layout-inspector-agent-startup-metrics" to "layout-inspector/shared-kernel/android-agent-startup-metrics",
        )

    layoutInspectorAgentModules.forEach { (path, directory) ->
        include(path)
        project(path).projectDir = file(directory)
    }

    include(":layout-inspector-sample-app")
    project(":layout-inspector-sample-app").projectDir = file("layout-inspector/samples/android-view-app")
}



// CPU profiling is intentionally kept as an isolated composite build.
includeBuild("simpleperf-viewer") {
    name = "simpleperf-viewer"
}

// Perfetto trace analysis is kept as an isolated composite build.
includeBuild("perfetto-viewer") {
    name = "perfetto-viewer"
}

// Java/Kotlin heap capture and HPROF analysis is kept as an isolated composite build.
includeBuild("memory-profiler") {
    name = "memory-profiler"
}

// Frame timing and jank analysis is kept as an isolated composite build.
includeBuild("frame-profiler") {
    name = "frame-profiler"
}

// Application startup timing and launch analysis is kept as an isolated composite build.
includeBuild("startup-profiler") {
    name = "startup-profiler"
}

// Battery resource attribution and energy estimation is kept as an isolated composite build.
includeBuild("battery-profiler") {
    name = "battery-profiler"
}

// HTTP request capture and HAR analysis is kept as an isolated composite build.
includeBuild("network-profiler") {
    name = "network-profiler"
}

// Android GPU Inspector tooling and artifact management is kept isolated.
includeBuild("gpu-inspector-integration") {
    name = "gpu-inspector-integration"
}

// AndroidX Benchmark import, comparison, and CI reporting is kept isolated.
includeBuild("benchmark-regression") {
    name = "benchmark-regression"
}

// In-app device screencasting via an embedded scrcpy protocol client is kept isolated.
includeBuild("device-screencast") {
    name = "device-screencast"
}

// Winscope capture service, device capture execution, and the packaged viewer closure
// are kept isolated as a composite build.
includeBuild("winscope-viewer") {
    name = "winscope-viewer"
}
