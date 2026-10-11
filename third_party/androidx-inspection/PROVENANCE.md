# AndroidX Inspection provenance

The AOSP UI Inspector agent compiles against `androidx.inspection`, which is **not published to
Google Maven or Maven Central** — Android Studio ships it as an internal prebuilt
(`//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0`). Its upstream source is public,
so APS vendors the Java API subset it needs and builds it here instead of copying a Studio artifact.

- Upstream: `platform/frameworks/support`, subdirectory `inspection/inspection`
- Revision: `b2dcba37aea417626ed77a230e961f405d893c4c` (branch `androidx-main` at vendoring time)
- Archive: `https://android.googlesource.com/platform/frameworks/support/+archive/b2dcba37aea417626ed77a230e961f405d893c4c/inspection/inspection.tar.gz`
- Archive SHA-256: `1198000aaa82e026d20cc812a0761f5d6721a9ffe26314e01ab2cde7d1e8ab8b`
- License: Apache License 2.0; original per-file notices are preserved.

`src/main/java/androidx/inspection/` is an unmodified copy of that archive's Java sources. The
upstream module also carries native sources (`src/main/native`) and a Robolectric/androidTest suite
that APS does not vendor yet.

## Why only the Java API is vendored

The AOSP UI Inspector payload and View inspector reference exactly six types from this artifact:
`ArtTooling`, `Connection`, `Inspector`, `InspectorEnvironment`, `InspectorExecutors` and
`InspectorFactory`. They never call `ArtToolingImpl`/`DefaultArtTooling`, which are the only
classes that `System.loadLibrary("art_tooling")`; the UI Inspector agent implements its own
`ArtToolingBridge` inside `lib_ui_inspector_agent.so` instead. The upstream native module is
therefore not required for the agent bundle.
