# APS patches for AOSP UI Inspector

Apply these patches in lexical order to the unmodified snapshot in
`../aosp-ui-inspector` before building the packaged agent artifacts.

`0001-require-session-token.patch` passes a random per-session token through the
native agent, service and payload, then requires the first framed host message
to match it in constant time. The token is never written to an archive or log.

`0002-unique-native-library-directory.patch` unpacks the inspector's JNI libraries
(`inspector.jar` ships `lib/<abi>/libcompose_inspection_jni.so`) into a session-qualified
directory. A native library file can only be opened by one class loader, so the upstream stable
directory made every session after the first fail with `UnsatisfiedLinkError` inside
`LambdaLocation.resolveWithJvmTI`, which surfaced as a Compose inspector that stopped answering
parameter queries (b/187342510).

Check against the pinned snapshot:

```sh
(cd third_party/aosp-ui-inspector && patch --dry-run -p1 < ../aosp-ui-inspector-patches/0001-require-session-token.patch)
```
