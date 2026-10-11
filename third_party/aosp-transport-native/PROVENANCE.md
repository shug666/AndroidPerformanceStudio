# AOSP transport/native provenance

The Compose inspection agent (`lib_ui_inspector_agent.so`) links against a small part of Android
Studio's transport native runtime. APS vendors exactly the translation units required by the linked
closure instead of the whole Bazel target, so the vendored tree stays reviewable.

- Upstream: `platform/tools/base`, subdirectory `transport/native`
- Revision: `b1261356012800c0a93d18f03b060024e8162c2f` (same pin as `third_party/aosp-ui-inspector`)
- Archive: `https://android.googlesource.com/platform/tools/base/+archive/b1261356012800c0a93d18f03b060024e8162c2f/transport/native.tar.gz`
- Archive SHA-256: `6a81e8d589f4814fb02dd410c347967e8ded5d056f72f8a3ee57506985ff6de0`
- License: Apache License 2.0; original per-file notices are preserved.

The files are kept under their Bazel workspace path (`tools/base/transport/native/...`) because the
agent sources include them that way, e.g.
`#include "tools/base/transport/native/utils/device_info.h"`.

## Vendored files

`agent/jni_wrappers.h`; `jvmti/{hidden_api_silencer,jvmti_helper}.{h,cc}` + `scoped_local_ref.h`;
`utils/{bash_command,device_info,log,tokenizer,trace}.{h,cc}` with the Android variants
(`*.android.cc`); and `utils/fs/{dir,disk_file_system,file,file_system,path}.{h,cc}`.

Everything else in `transport/native` (daemon, tests, activity manager, file cache, protobuf
transport, ...) is **not** reachable from the agent: the closure was computed from the agent's
include graph and the translation units it references. Regenerate it with the native closure script
described in `third_party/aosp-transport-native/README.md`.
