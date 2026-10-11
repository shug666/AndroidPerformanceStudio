# Compose agent bundle

Full runtime Compose inspection is deliberately unavailable unless APS is packaged with its verified
agent bundle. All four artifacts are built inside this repository; no Android Studio installation and
no AOSP Bazel workspace are required.

| Artifact | Built by |
| --- | --- |
| `lib_ui_inspector_service.jar` | `:layout-inspector:compose-agent-build` (Java, dexed) |
| `lib_ui_inspector_payload.jar` | `:layout-inspector:compose-agent-build` (Java, dexed) |
| `view-inspector.jar` | `:layout-inspector:compose-agent-build` (Kotlin, jarjar-relocated, dexed) |
| `agent/<abi>/lib_ui_inspector_agent.so` | `:layout-inspector:compose-agent-build` (NDK/CMake) |

## Build

```bash
./desktop-viewer/gradlew -p desktop-viewer/layout-inspector :compose-agent-build:composeAgentBundle
```

writes `compose-agent-build/build/compose-agent-bundle/` with the four artifacts,
`manifest.properties` (a SHA-256 for every artifact) and `PROVENANCE.md`.

The task mirrors the vendored snapshots into `build/agent-src`, applies every patch in
`third_party/aosp-ui-inspector-patches/` (including the per-session token handshake), retargets
Android Studio's shaded protobuf onto the public protobuf runtime, compiles the jars, relocates
`kotlin`/`kotlinx.coroutines` with jarjar, dexes with the Android SDK build-tools `d8`, and
compiles the three JVMTI agents with the NDK. It needs `ANDROID_HOME` (platform, build-tools, cmake,
ndk) and `patch` on the path.

Verify an already produced bundle without rebuilding it:

```bash
./desktop-viewer/gradlew -p desktop-viewer/layout-inspector :compose-agent-build:verifyComposeAgentBundle
```

## Using the bundle

For a development build, enable the internal feature and point it at the output:

```text
-Dagentperf.compose.full.enabled=true
-Dagentperf.compose.agent.bundle=/path/to/compose-agent-bundle
```

The runtime never downloads or hot-updates agent code. Only the exact official Compose
`inspector.jar` matching the target library version may be resolved and cached after explicit
authorization.

## Legacy AOSP Bazel path

`tools/build-compose-agent-bundle.sh` builds the same artifacts from a full AOSP Studio checkout. It
is kept for cross-checking the in-repo build against upstream and is no longer required to produce a
bundle.
