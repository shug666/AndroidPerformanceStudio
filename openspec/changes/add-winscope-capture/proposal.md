# Proposal: add-winscope-capture

## Why

Android Performance Studio 在 ADR 0035 中整体移除了 Winscope，因为当时的交付路径（AOSP submodule + Node/pnpm/Go 源码构建）是仓库中最重、最脆弱的依赖。但用户仍然需要 Winscope 的核心工作流——抓取 WM/SF/Transitions 等系统级 trace 并在 Winscope UI 中查看。当前用户只能依赖本机 AOSP 源码树（`npm run start` + 源码内 `winscope_proxy.py`），无法在任意 Windows/Ubuntu 机器上开箱使用。

现有一份预构建的离线 Winscope 产物（AOSP `dist/prod` 精简闭包，约 78 MB，含 `winscope_proxy.py` 参考实现），恰好命中 ADR 0035 留下的方向："if the browser viewer returns, prefer a smaller delivery path over a full source build in Gradle"。经探索验证，该 bundle 的 ProxyConnection 客户端协议（端口 5544、版本头 `4.0.8`、`?token=` URL 参数握手、7 个 HTTP 端点）完整可用，且 bundle 版本被钉死后协议只需对齐一次、永不漂移。因此可以在纯 JVM 内实现 proxy 协议，让 APS 独占 ADB、不引入 Python 依赖、不进入 Gradle 构建链。

## What Changes

- 新增 Winscope 目的地入口（home 卡片 + 导航），打开 Winscope 抓取页面。
- 打包预构建 Winscope 浏览器查看器（固定版本闭包，含 `index.html`、`js/`、`trace_processor.wasm`、`engine_bundle.js` 及静态资源），打包阶段剔除 Google Analytics 外链脚本；不打包、不启动 `winscope_proxy.py`，不运行 AOSP npm 构建。
- APS 在 loopback 上实现 Winscope ADB proxy 协议（兼容客户端 `4.0.8`）：`GET /devices`、`GET /status`、`GET /fetch`、`POST /start`、`POST /end`、`POST /dump`、`GET /checkwayland`，以及 `Winscope-Token` 鉴权与 `Winscope-Proxy-Version` 版本头。
- APS 静态托管打包的查看器资源，并以 `?token=` 参数启动系统浏览器完成零感知握手；服务仅绑定 127.0.0.1，显式用户动作触发，随会话/应用关闭失效（对齐 `perfetto-ui-launch` 的既有安全模式）。
- 设备端抓取执行层复用现有 `AdbClient` / `AdbRootController`：现代路径优先（Perfetto 数据源探测 + 合流 trace），探测不可用时降级到传统逐源命令（`cmd window tracing`、`service call SurfaceFlinger` 等），采集文件从 `/data/misc/wmtrace/` 等目录拉取；抓取前置执行 root 检查与残留 perfetto 会话清理（避免与 APS 自身 Perfetto 抓取共用 `traced` 产生冲突）。
- 抓取产物同步归档为 APS 会话工件（为后续分析/AI 关联预留钩子）。
- 不做：应用内 Winscope trace 渲染（查看一律走浏览器查看器）、原生抓取参数 UI（配置由 Winscope 浏览器 UI 下发）、对 bundle 的功能改动（仅允许资源层剔除 analytics 外链）。

## Capabilities

### New Capabilities

- `winscope-capture-service`: APS 作为 Winscope ADB proxy 的服务面——5544 loopback 服务生命周期、proxy 协议兼容（token/版本头/7 端点）、打包查看器的静态托管、浏览器启动与 token 注入、服务安全边界（loopback、no-store、会话失效）。
- `winscope-device-capture`: 设备端抓取执行——设备前置检查（root、残留会话清理）、现代 Perfetto 数据源路径优先与传统路径降级、抓取启停与文件拉取、产物归档。

### Modified Capabilities

（无——ADR 0035 后不存在 Winscope 相关 spec；`perfetto-ui-launch` 的要求不受影响。）

## Impact

- **新模块**：`desktop-viewer/winscope-viewer/`（按 `perfetto-viewer` 的 composite 结构，预计含 capture-service 与 device-capture 子模块）。
- **平台层复用**：`platform-core/adb-core`（`AdbClient`、`AdbRootController`、`StudioHostProcessExecutor`）；loopback HTTP 服务沿用 `jdk.httpserver` 模式（参照 PerfettoUiServer）。
- **打包**：desktop-app 安装包体积增加约 78 MB（压缩前）；新增第三方 license 清单条目（Angular、three.js、protobufjs 等 bundle 内依赖）；打包任务需执行资源闭包校验（无 `winscope_proxy.py`、无残留分析脚本）。
- **构建**：不引入 Node/Python/Go 工具链，不进入 Gradle 常规构建路径；bundle 作为固定版本资源进仓库/包管理。
- **设备要求**：Winscope 抓取要求 root/userdebug 设备或模拟器（现有 `AdbRootController` 已覆盖），能力探测失败时在 Winscope 页面明确降级原因。
- **文档/ADR**：需新增 ADR 说明与 ADR 0033/0035 的关系（为何这次不进构建链、抓取归 APS、bundle 钉死升级流程）。
