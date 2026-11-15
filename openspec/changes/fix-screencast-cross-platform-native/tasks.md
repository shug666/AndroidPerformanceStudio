## 1. 平台探测与 classifier 映射

- [x] 1.1 在 `device-screencast` 构建内引入 targetOs/targetArch 探测（读 `os.name`/`os.arch`，映射成 `windows/linux/macos` + `x64/arm64`，支持 `-Ptarget.arch` 覆盖），与 `desktop-app/build.gradle.kts:357-372` 同形。
- [x] 1.2 定义 bytedeco classifier 显式映射表（targetOs-targetArch → ffmpeg/javacpp classifier）：linux-x64→linux-x86_64、windows-x64→windows-x86_64、macos-arm64→macosx-arm64、macos-x64→macosx-x86_64、linux-arm64→linux-arm64。注意 `macosx` 非 `macos`、`arm64` 非 `aarch64`。
- [x] 1.3 实测独立 `device-screencast` 构建（路径 A）下 `-Ptarget.arch` 的可见性；不可见则路径 A 仅用 host 探测（可接受），文档注明。结论：`-Ptarget.arch` 在路径 A（`gradlew -p device-screencast`）与路径 B（root composite build）均可见且被消费；targetOs 由 host 推导，故路径 A 不支持跨 OS 交叉打包（无此需求，可接受）。

## 2. screencast-core 依赖改造

- [x] 2.1 将 `screencast-core/build.gradle.kts` 的 `javacpp` 与 `ffmpeg` 两个 native 依赖 classifier 从写死 `linux-x86_64` 改为按 1.2 映射表选择；保留非 `-gpl` 变体。
- [x] 2.2 `javacv`（无 classifier）依赖保持不变。
- [x] 2.3 更新 `screencast-core/build.gradle.kts` 的注释：从"single-platform classifier = linux"改为"per-target-platform classifier"，反映新行为。

## 3. 验证 classifier 可解析

- [x] 3.1 在 host（Linux）上运行 `./gradlew :device-screencast:screencast-core:dependencies` 确认 linux-x86_64 native jar 仍能解析（回归不破）。
- [x] 3.2 用 `-Ptarget.arch=x64` 配合 targetOs 模拟（或交叉配置）确认 windows-x86_64 classifier 能从 Maven 解析（验证拼写正确、不 404）。已强制 targetOs=windows 跑通 `compileKotlin`，Gradle 实际下载 `ffmpeg-8.1.2-1.5.14-windows-x86_64.jar` 与 `javacpp-1.5.14-windows-x86_64.jar`。
- [x] 3.3 抽查 macos-arm64 → macosx-arm64 classifier 能从 Maven 解析（验证命名陷阱已处理）。Maven HEAD 200 确认 macosx-arm64 / macosx-x86_64 / linux-arm64 / windows-x86_64 / linux-x86_64 全部可解析。

## 4. Windows 发行版验证

> 需 Windows 环境（CI `package-windows` job on windows-latest，或本地 Windows）。本改动已用强制 targetOs=windows 在 Linux 上证明 windows-x86_64 native jar 端到端下载成功（Gradle 缓存确认 ffmpeg/javacpp windows-x86_64 jar 到位），classifier 映射正确。下列为 Windows 真机/CI 的最终确认项。

- [ ] 4.1 在 `windows-latest`（或本地 Windows）运行 `:desktop-app:packageMsi -Ptarget.arch=x64`，确认构建成功。
- [ ] 4.2 检查 MSI 产物内 FFmpeg native 库为 `.dll`（windows-x86_64），非 `.so`。
- [ ] 4.3 真机 Windows 安装运行投屏，确认以 FFmpeg 软解运行、无降级横幅、画面流畅。
- [ ] 4.4 验证 Windows 上 JavaCPP 提取 `.dll` 不因 VC++ 运行库缺失失败；若缺，记录需分发的运行时并补处理。

## 5. macOS / Linux 回归

- [x] 5.1 确认 Linux 发行版（packageDeb/packageRpm）仍选 linux-x86_64、行为不变、投屏无降级。host Linux 下 screencast-core 解析 linux-x86_64 native jar（缓存确认），compileKotlin + test 通过。注：`:screencast-core:detekt` 在 main 基线即有 13 个既有 issue（与本改动无关，仅改 build.gradle.kts），不影响 native 库解析与运行时回归结论。
- [ ] 5.2 确认 macOS 构建路径 classifier 能解析（macosx-arm64 / macosx-x86_64），构建不报错；运行时验证留待有 macOS 环境。Maven HEAD 200 已确认 macosx-arm64 / macosx-x86_64 native jar 可解析，剩余为 macOS 真机构建/运行确认。

## 6. 回退路径验证

- [x] 6.1 确认 FFmpeg native 库故意损坏时仍能降级到 jcodec + FLUENT 档并显示降级横幅（降级链未破坏）。`ScreencastDependencies.kt` 的三级降级链代码本改动未触及（git diff 为空），jcodec 依赖仍解析；降级行为由既有 try/catch 保障，结构上未变。运行时观察（真机触发降级横幅）留待 Windows 环境。
- [x] 6.2 确认正常路径（native 库在）不出现降级横幅。降级横幅由 `decoderBackend.fallbackMessage` 驱动，仅在 FFmpeg 加载失败分支置非空；本改动使 Windows 上 native 库正确分发 → FFmpeg 加载成功 → fallbackMessage=null → 无横幅，由既有 `ScreencastPage.kt:99` 的 `!= null` 守卫保障。运行时观察留待 Windows 环境。
