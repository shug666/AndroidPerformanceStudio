## Context

`upgrade-screencast-decoder` 引入 FFmpeg（JavaCV）软解替代 jcodec，但显式把"跨平台原生库分发"列为 Non-Goal（design.md D1：当前宿主为 linux-x86_64，Windows/macOS 按需追加分类器）。实现因此把 native classifier 写死成 `linux-x86_64`：

```kotlin
// desktop-viewer/device-screencast/screencast-core/build.gradle.kts
implementation("org.bytedeco:javacpp:1.5.14:linux-x86_64")
implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14:linux-x86_64")
```

后果：在 Windows 上构建/安装的发行版 classpath 里只有 Linux `.so`，无 Windows `.dll`。`ScreencastDependencies.resolveDecoderBackend()` 的三级降级链（VAAPI → FFmpeg 软解 → jcodec）在 Windows 上：
1. `VaapiSupport.probe()` 探测 `/dev/dri/renderD128` → Windows 无此文件 → false；
2. `new FFmpegFrameDecoder()` 的 init 块调用 `avutil.av_log_set_level(...)` → 触发 `org.bytedeco.ffmpeg.global.avutil` 静态初始化 → JavaCPP Loader 找不到对应平台 native 库 → `ExceptionInInitializerError`，后续访问抛 `NoClassDefFoundError: Could not initialize class org.bytedeco.ffmpeg.global.avutil`；
3. 捕获后落到 `H264FrameDecoder`（jcodec），`isFallback=true` 强制 FLUENT 档，UI 横幅显示 `decoderFallbackMessage` = 那条 NoClassDefFoundError 文本。

jcodec 回退不可作为可接受的临时态：纯 Java H.264 软解（`decodeFrameFromNals` + `Yuv420pToRgb` + 全帧逐像素 `packBgra` 循环）在 1280×800 下仅 ~10-15fps、原生分辨率 ~2fps。强制 FLUENT（1280/10fps）恰好踩在能力边缘，稍有不稳即跌破实时 → 慢动作 + 延迟堆积。scrcpy 反压链路自稳（`decodeLoop` 顺序 `readFully` 不丢包，解码慢只让画面变慢不断连，design.md D6 已记），所以表现是"卡顿不可用"而非黑屏/断连，但体验不可用。

`device-screencast` 是 includeBuild / composite build（根 settings.gradle.kts:170），有两条独立构建路径：
- **路径 A**：独立 `device-screencast/gradlew :screencast-app:run`，用 device-screencast 自己的 settings，**无 desktop-app 的 targetOs/targetArch 机制**；
- **路径 B**：`desktop-app:packageMsi` 聚合打包（CI `package-windows` job，`windows-latest`，传 `-Ptarget.arch=x64 -Ptarget.javaHome=...`），用根 settings includeBuild device-screencast。

`desktop-app/build.gradle.kts:357-372` 已有完整的 host OS/arch 探测，映射成 `windows/linux/macos` + `x64/arm64`，并支持 `-Ptarget.arch` 覆盖、`-Ptarget.javaHome` 交叉打包。但 `screencast-core` 完全没接入这套机制。

约束：
- 版本固定：javacpp/javacv 1.5.14、ffmpeg 8.1.2-1.5.14（libs.versions.toml）。
- javacv 1.5.14 无平台 native jar（纯绑定层），native 全靠 ffmpeg + javacpp 两个平台 jar，现有 `implementation(libs.javacv)` 不动。
- 桌面打包支持的目标平台：windows-x64、linux-x64、linux-arm64、macos-x64、macos-arm64。

## Goals / Non-Goals

**Goals:**
- Windows x64 发行版以 FFmpeg 软解运行投屏，无降级横幅。
- macOS（x64/arm64）构建路径不被破坏，顺带获得正确 native 库。
- 两条构建路径（独立 device-screencast 构建、desktop-app 聚合打包）选择同一目标平台 native 库，交叉打包时 classifier 与 runtime 平台一致。
- classifier 选择与 `desktop-app` 已有 targetOs/targetArch 语义对齐，避免两份探测逻辑漂移。

**Non-Goals:**
- 不改解码器接口、降级链结构、会话状态机、画质档位、UI、scrcpy 协议层。
- 不引入 javacv-platform 全平台胖包（体积 1-2GB 不可接受，沿用 D1 的单平台策略）。
- 不改 ffmpeg/javacpp/javacv 版本。
- 不处理 Android 端 native（android-arm64/x86_64 classifier 虽存在于 Maven，但本工具是桌面宿主侧解码，不涉及）。
- 不改 VAAPI 硬解路径（Linux 专属，Windows/macOS 无 render node，probe 自然 false，行为不变）。

## Decisions

### D1. classifier 按目标平台选择，沿用单平台策略，不用 javacv-platform

**选择**：把 `screencast-core` 的两个 native 依赖 classifier 从写死 `linux-x86_64` 改为按 `targetOs`-`targetArch` 映射查表，五个桌面目标各拉一个 native jar，每个发行版 +~100MB。

**理由**：延续 `upgrade-screencast-decoder` D1 的体积取舍（单分类器 ~100MB vs 全平台 1-2GB），只是把"单平台"从"写死 Linux"升级为"按目标平台"。Maven Central 已确认 bytedeco 1.5.14 / ffmpeg 8.1.2-1.5.14 五个桌面目标的 native jar 全部发布，无供应链障碍。

**备选**：javacv-platform 全平台胖包（体积不可接受，弃）；运行时 `-Djavacpp.platform` 按需选择但仍需全平台 jar 进 classpath（同体积问题，弃）。

### D2. classifier 命名用显式映射表，不直接拼 targetOs/targetArch

**选择**：bytedeco 的 native classifier 串与 `desktop-app` 的 `targetOs`/`targetArch` 取值不同形，必须显式映射：

```
targetOs-targetArch  →  ffmpeg/javacpp classifier
linux-x64            →  linux-x86_64
windows-x64          →  windows-x86_64
macos-arm64          →  macosx-arm64        ← macosx 非 macos
macos-x64            →  macosx-x86_64
linux-arm64          →  linux-arm64
```

**理由**：bytedeco classifier 串是 `macosx`（不是 `macos`）、arm 平台是 `arm64`（不是 `aarch64`），而 desktop-app 的 `targetOs` 取值是 `macos`、`targetArch` 是 `arm64`。直接 `"$targetOs-$targetArch"` 在 macos 上会拼出不存在的 `macos-arm64` → Maven 404。显式映射表消除这个陷阱。

**备选**：字符串转换规则（`macos`→`macosx`）——隐式、易在新增平台时漏改，不如显式表安全。

### D3. targetOs/targetArch 探测逻辑与 desktop-app 对齐，下沉到 device-screencast 可消费处

**选择**：在 `device-screencast` 构建内部引入与 `desktop-app/build.gradle.kts:357-372` 同形的探测——读 `os.name`/`os.arch` 映射成 targetOs/targetArch，支持 `-Ptarget.arch` 覆盖——供 screencast-core 选 classifier。两条路径都落在 device-screencast 构建内解析，保证一致。

**理由**：composite build 的 Gradle property 默认不跨 includeBuild 传递，desktop-app 的 targetOs 不会自动可见于 device-screencast。最稳妥的是让 device-screencast 自己探测 host 并读 `-Ptarget.arch`，与 desktop-app 用同一套映射规则，而不是让 desktop-app 把 targetOs 传进去（依赖 property 传递机制，脆弱）。

**备选**：
- 把探测下沉到共享 buildSrc convention plugin——最干净但需新建 buildSrc，对 device-screencast 独立构建（路径 A）引入额外结构改动，本次不必要。
- desktop-app 通过 composite build dependency substitution 注入 targetOs——property 跨 includeBuild 传递不可靠，弃。

**注意**：实现期需确认独立 device-screencast 构建是否也接受 `-Ptarget.arch`（Gradle property 在 composite build 中的可见性），若不可见则路径 A 始终用 host 探测（无交叉打包需求，可接受），路径 B 由 desktop-app 驱动。

### D4. jcodec 回退保留，行为不变

**选择**：`ScreencastDependencies.resolveDecoderBackend()` 三级降级链不动；jcodec 仍为 native 库损坏/加载失败的最终回退。修复后 Windows 正常路径走 FFmpeg 软解，降级横幅不再出现；native 库真损坏时仍安全降级。

**理由**：降级链本身设计正确，问题只在 native 库分发。回退路径在 Linux 上已有真机验证（design.md D6），保留零风险。

### D5. 保留非 GPL 变体

**选择**：classifier 继续用非 `-gpl` 变体（如 `windows-x86_64`，非 `windows-x86_64-gpl`），与现有 `linux-x86_64` 一致。

**理由**：H.264 解码在非 GPL 版即有；GPL 变体含 GPL 编解码器，引入许可证约束。无新增编解码需求，保持与现状一致。

## Risks / Trade-offs

- **[Windows JavaCPP 提取 .dll 依赖 VC++ 运行库]** → bytedeco ffmpeg DLL 可能依赖 MSVC 运行库，jlink 精简运行时未必自带。Linux 上 glibc 自带无此问题，作者未遇到。**缓解**：真机 Windows 验证；若缺，确认是否需随发行版分发 VC++ 运行时或换静态链接变体。实现期需在 Windows 上实跑一次。
- **[JavaCPP 提取目录权限]** → 企业策略下 `%TEMP%` 执行受限可能导致 .dll 加载失败。**缓解**：概率低；若出现，文档说明或配置 `org.bytedeco.javacpp.cachedir` 指向可写目录。
- **[classifier 拼错 → Maven 404]** → D2 已识别 `macosx`/`arm64` 命名陷阱。**缓解**：显式映射表；实现后用 `./gradlew :screencast-core:dependencies` 实际拉一次确认五个平台都能解析。
- **[交叉打包 classifier 与 runtime 不一致]** → 若路径 A/B 探测逻辑漂移。**缓解**：D3 让两条路径用同一套映射规则；CI `package-windows` 已传 `-Ptarget.arch=x64`，验证产物内含 windows `.dll`。
- **[每平台 +100MB 体积]** → 与现有 Linux 包同等量级，内部调试工具可接受；互不叠加（每目标平台单独打包）。
- **[macOS 无真机验证]** → 本次主要修 Windows，macOS 顺带对齐但可能无验证条件。**缓解**：至少保证 macOS 构建不报错（classifier 能解析），运行时验证留待有 macOS 环境时补。

## Migration Plan

- 纯构建配置改动，无数据迁移、无运行时状态迁移。
- 部署 = 重新构建各平台发行版（CI `package-linux` / `package-windows` / `package-macos`）。
- 回滚 = 将 classifier 选择逻辑还原为写死 `linux-x86_64`（一次小 revert，恢复降级到 jcodec 的旧行为）。
- 无兼容性破坏：现有 Linux 发行版行为不变（仍选 `linux-x86_64`）。

## Open Questions

- 独立 `device-screencast` 构建（路径 A）下 `-Ptarget.arch` 的可见性，实现期实测确认；不可见则路径 A 仅支持 host 平台（无交叉打包需求，可接受）。
- Windows jlink 精简运行时是否自带 bytedeco ffmpeg DLL 所需的 VC++ 运行库，需真机 Windows 实跑确认。
- 是否值得把 targetOs/targetArch 探测下沉到共享 buildSrc convention plugin（本次偏向不做，见 D3 备选），留待后续若有更多子工程需要平台 native 时再统一。
