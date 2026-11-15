## Why

Windows（及 macOS）发行版上设备投屏不可用：FFmpeg native 库被构建配置写死成 `linux-x86_64`，Windows 包里只有 `.so` 而无 `.dll`，`org.bytedeco.ffmpeg.global.avutil` 类静态初始化失败，解码链降级到纯 Java jcodec。jcodec 软解吞吐卡在能力边缘（1280×800 下 ~10-15fps，原生分辨率 ~2fps），即便强制到 FLUENT 档（1280/10fps）也只能勉强出图、延迟堆积、体验不可用。根因是 `upgrade-screencast-decoder` 当初把"跨平台原生库分发"显式列为 Non-Goal，现在需兑现那个被推迟的决定。

## What Changes

- `screencast-core` 的 FFmpeg/javacpp native 依赖从写死的 `linux-x86_64` classifier 改为按目标平台选择，覆盖 windows-x64、linux-x64、linux-arm64、macos-x64、macos-arm64 五个桌面目标。
- 引入与 `desktop-app` 一致的 `targetOs`/`targetArch` 探测与映射（host OS/arch 探测 + `-Ptarget.arch` 覆盖），使独立 `device-screencast` 构建与 `desktop-app` 聚合打包两条路径选择同一平台 native 库，交叉打包时 classifier 与 runtime 平台一致。
- classifier 命名采用显式映射表（注意 `macosx` 非 `macos`、arm 平台用 `arm64` 非 `aarch64`），与 bytedeco 1.5.14 在 Maven Central 实际发布的 native jar 对齐。
- jcodec 保留为 native 库损坏/加载失败的最终回退路径，行为不变。
- 验证 Windows x64 发行版（MSI/EXE）内含正确的 FFmpeg `.dll` 且投屏以 FFmpeg 软解运行（无降级横幅）；顺带覆盖 macOS 构建路径不被本次改动破坏。

## Capabilities

### New Capabilities
<!-- 无新增 capability；本次修改的是既有 device-screencast 解码能力 -->

### Modified Capabilities
- `device-screencast`: 投屏实时解码能力须在 Windows/macOS 宿主上以 FFmpeg 软解运行，而非降级到 jcodec。修改"投屏画面实时解码渲染"需求，新增 native 库按目标平台分发的约束。

## Impact

- **构建配置**：`desktop-viewer/device-screencast/screencast-core/build.gradle.kts`（classifier 选择逻辑）；可能需要下沉 targetOs/targetArch 探测以避免与 `desktop-app/build.gradle.kts` 两份探测逻辑漂移。
- **依赖体积**：每个目标平台发行版 +~100MB FFmpeg native 库（与现有 Linux 包同等量级，互不叠加）。
- **CI**：`.github/workflows/package.yml` 的 `package-windows` job 产出的 MSI/EXE 将携带正确的 `.dll`；无需改 workflow 逻辑（已传 `-Ptarget.arch=x64`）。
- **运行时**：Windows 上 JavaCPP 提取 `.dll` 可能依赖 VC++ 运行库，需真机验证 jlink 精简运行时是否自带或需额外分发。
- **不动**：解码器接口、降级链结构、会话状态机、画质档位、UI、scrcpy 协议层。
