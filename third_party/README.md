# Third-party sources

## Vendored AOSP inspection sources

Four upstream snapshots are vendored verbatim so APS can build the Compose inspection agent without
an AOSP Bazel workspace or an Android Studio installation. APS patches live outside the snapshots,
in `aosp-ui-inspector-patches/`.

| Path | Upstream | Pin |
| --- | --- | --- |
| `aosp-ui-inspector/` | `platform/tools/base`, `ui-inspector` | `b1261356012800c0a93d18f03b060024e8162c2f` |
| `androidx-inspection/` | `platform/frameworks/support`, `inspection/inspection` | `b2dcba37aea417626ed77a230e961f405d893c4c` |
| `aosp-transport-native/` | `platform/tools/base`, `transport/native` | `b1261356012800c0a93d18f03b060024e8162c2f` |
| `aosp-slicer/` | `platform/tools/dexter`, `slicer` | `d992a222ec28b56efa29f9104db060379298049c` |

Each directory carries a `PROVENANCE.md` with the archive URL, SHA-256 and licence. See
`desktop-viewer/layout-inspector/compose-agent-build/` for the build that consumes them.

## Firefox Profiler

`firefox-profiler/` is a shallow Git Submodule of
<https://github.com/firefox-devtools/profiler> pinned to:

```text
faaf1a14affd3c6d8b7342188371079b999abf5b
```

The upstream project is licensed under MPL-2.0. Its source, license, dependency lockfile, and
notices remain inside the submodule checkout.

Initialize and verify the pinned source:

```bash
git submodule update --init --depth 1 --recursive
./scripts/firefox-profiler.sh verify
```

The pinned revision requires Node.js 24 and Yarn Classic 1.x. Install dependencies and create the
production web build independently from the Kotlin/Gradle application build:

```bash
./scripts/firefox-profiler.sh install
./scripts/firefox-profiler.sh build
```

The generated static assets are written to `third_party/firefox-profiler/dist/` and remain ignored
by the upstream submodule.

Android Performance Studio does not embed the React application. When the Firefox Profiler local
engine is selected, the desktop application serves this pinned build from `127.0.0.1`, serves the
generated `perf_data.json.gz` from the same local origin, and opens the local page in the system
browser. The Firefox Profiler engine without the local qualifier continues to open the official
website.
Native application distributions copy the already-built `dist/` directory into the application
resources; keep the Node/Yarn build as a separate prerequisite before packaging.

```bash
./scripts/firefox-profiler.sh build
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:createDistributable
```

To intentionally update the pinned upstream version, fetch and check out an explicit commit inside
the submodule, validate its build, then commit the updated Git link in this repository:

```bash
git -C third_party/firefox-profiler fetch origin
git -C third_party/firefox-profiler checkout <commit>
./scripts/firefox-profiler.sh install
./scripts/firefox-profiler.sh build
git add third_party/firefox-profiler
```
