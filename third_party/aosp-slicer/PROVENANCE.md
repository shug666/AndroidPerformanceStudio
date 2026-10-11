# AOSP slicer provenance

The Compose inspection agent rewrites DEX entry points to capture array parameters, which needs the
`slicer` dex IR/instrumentation library.

- Upstream: `platform/tools/dexter`, subdirectory `slicer`
- Revision: `d992a222ec28b56efa29f9104db060379298049c` (branch `main` at vendoring time)
- Archive: `https://android.googlesource.com/platform/tools/dexter/+archive/d992a222ec28b56efa29f9104db060379298049c/slicer.tar.gz`
- Archive SHA-256: `7cdf41a2c606d7ef39af958cc8d6c51df56409231730698ba293afb32ef2383d`
- License: Apache License 2.0; original per-file notices are preserved.

`slicer/*.cc` and `slicer/export/**` are unmodified. The upstream `tests/` tree is not vendored.
Consumers add `slicer/export` (for `slicer/*.h`) and `slicer` (for `export/slicer/*.h`) to the
include path, matching the upstream CMake setup.
