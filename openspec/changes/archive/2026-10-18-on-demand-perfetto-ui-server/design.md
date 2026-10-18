## Context

Trace Analyzer 当前依赖 `PerfettoUiServer`（`perfetto-ui-server` 模块，`HttpServer` + 固定端口 8090）实现两个目的：

1. **把本地 trace 送进浏览器**：`/trace` 端点带 CORS `*` 提供 trace 字节。
2. **托管 Perfetto UI 前端**：检测到本地 UI assets（`third_party/perfetto/out/ui/dist` 或用户目录缓存）时托管 `index.html`；否则回退到一个 iframe embed 兜底页（`buildEmbedPage`，postMessage 推送 trace 字节到 `ui.perfetto.dev` iframe）。

现状问题：`PerfettoMainPage.openTrace` 每次打开 trace 都自动 `uiServer.start()` 并弹浏览器——服务启动是隐式副作用，端口被占用直接失败，embed 兜底页是额外维护负担。

关键事实（调研确认）：
- **轨迹诊断与该服务无关**：诊断走 `TraceProcessorToolResolver` + `trace_processor_shell`（随机端口、点击时按需启动、`TraceAnalysisContext` 管理生命周期），本次不改。
- **官方 UI `?url=` 可行**：`ui.perfetto.dev` 支持 `?url=` 从 HTTP 端点拉取 trace（需 CORS）；浏览器将 `http://localhost` 视为 potentially trustworthy，HTTPS 页面 fetch localhost 不受混合内容限制；官方 UI 加载后会缓存 trace 进 IndexedDB 并重写 URL 为 `?local_cache_key=<uuid>`，之后刷新/重开不再依赖本地端点。

## Goals / Non-Goals

**Goals:**

- 打开 trace 零副作用：不启动服务、不弹浏览器。
- "在 Perfetto UI 中打开"为用户显式动作：默认走官方 `ui.perfetto.dev` 的 `?url=`，仅需瞬时 `/trace` 端点。
- 本地 UI assets 托管保留为菜单项（File → Open in local Perfetto UI），仅 assets 存在时可用。
- 端口 8090 优先、被占用回退随机端口，URL 动态生成。

**Non-Goals:**

- 不改动轨迹诊断的 trace_processor 启动模型。
- 不做 trace 的远程上传/分享（`?s=` perm link 之类）。
- 不支持 `file://` 直接打开（官方 UI 拒绝，无自动化路径）。
- 不改动 `PerfettoUiServer` 以外的任何 HTTP 服务。

## Decisions

### D1: 服务生命周期——显式触发 + 瞬时运行 + 页面失活/dispose 时停止

服务只由两个用户动作启动（官方 UI 按钮、本地 UI 菜单项）。启动后保持运行以便用户重复打开浏览器标签或切换官方/本地模式；停止时机沿用现有机制：`DisposableEffect.onDispose` 与 `isActive == false`（页面导航离开）时 `uiServer.stop()`。

*备选*：加载完成即自动 stop——无法可靠探测浏览器端 fetch 完成时机（服务端只能看到请求，不知晓 UI 渲染完毕），且会破坏"刷新重开"体验（`local_cache_key` 已写入后无碍，但首次加载期间 stop 会 404）。放弃。

### D2: 默认打开方式——官方 UI `?url=`，而非 postMessage 或拖放

`https://ui.perfetto.dev/#!/?url=http://localhost:<port>/trace`。

- postMessage 方案需要宿主页（embed 页）或 `window.open` 引用，正好是我们要移除的复杂度。
- 拖放无法自动化。
- `?url=` 是纯 URL 生成，无需 iframe、无需 JS 注入；CORS 已由现有 `/trace` 端点满足。
- `local_cache_key` 是官方 UI 的自然行为，免费获得"刷新不失效"体验。

*注*：trace 文件名不通过 URL 传递，官方 UI 显示默认标题；可在后续迭代用 `filename` 参数增强，本次不做。

### D3: `PerfettoUiServer.start()` 增加 UI 托管模式参数

`start(uiAssetsDir: Path?, serveUiAssets: Boolean)`（或引入 `enum UiServeMode { TRACE_ONLY, TRACE_AND_UI }`）：

- `TRACE_ONLY`（官方 UI 模式）：即使 `tryFindUiAssetsDir()` 找到 assets 也不托管，仅注册 `/trace`；`/` 注册 404。
- `TRACE_AND_UI`（本地 UI 模式）：现有行为——assets 存在时 `SimpleFileServer` 托管，菜单项启用条件即 `tryFindUiAssetsDir() != null`。

URL 生成移入 `openTrace`，按模式切换目标：官方 `https://ui.perfetto.dev/#!/?url=http://localhost:<port>/trace`；本地 `http://localhost:<port>/#!/?url=http://localhost:<port>/trace`。

### D4: 端口分配——8090 优先，占用回退随机

`start()` 时先尝试绑定 8090；`BindException` 则改绑 `InetSocketAddress(0)`（系统分配随机端口）。绑定成功后通过 `server.address` 暴露实际端口，`openTrace` 生成 URL 时使用实际端口。失败（如无可用端口）仍返回 `SERVER_START_FAILED`。

*备选*：直接总是随机端口——失去"固定地址可书签"的稳定性，且默认情况下 8090 就是空闲的。放弃。

### D5: 删除 embed 兜底页与 zoom postMessage 逻辑

`buildEmbedPage`、`hasUiAssets` 标志、`mode=embedded`/zoom 脚本、`ViewerDocumentTheme` 依赖一并移除。`hasUiAssets` 的语义（"资产是否托管"）由 start 参数显式表达，不再需要运行时探测标志。时间戳窗口参数（`visStart`/`visEnd`，`timestampNanos`）随 embed 页一起退役——官方 UI 的 `?url=` 无对应参数，深层链接到时间窗口不做（Non-Goal）。

*备选*：保留 embed 页作为无 assets 时的本地 UI 回退——与"默认走官方 UI"的方向矛盾，且 embed 页依赖 `?mode=embedded` 的官方页面行为（不稳定、属内部 API）。放弃。

### D6: `openTrace` 签名与调用点收敛

`PerfettoMainPage.openTrace` 不再接收/传递"是否启动 UI"语义：只更新状态（`activeTraceFile`/`activeArtifact`/`recentFiles`），UI 展示全部由按钮/菜单显式触发。`launchTraceInUi` 拆为两个入口函数（`openInPerfettoUi` / `openInLocalPerfettoUi`），内部共享"确保服务已启动 → openTrace"逻辑。`initialTraceTimestampNanos` 参数链（`AppDestination.openPerfettoTrace` → `PerfettoMainPage`）保留传递但不再驱动 URL zoom。

## Risks / Trade-offs

- [官方 UI 的 `?url=` / postMessage 契约属内部行为，未来可能变更] → `?url=` 是官方文档化的公开用法且广泛使用；本地 UI 模式（菜单项）保留为完整自托管回退，官方路径失效时用户仍有路可走。
- [8090 被其他应用长期占用] → D4 的随机端口回退保证功能可用；测试覆盖回退路径。
- [浏览器安全策略变化（localhost 混合内容豁免疫失）] → 届时本地 UI 模式不受影响；官方模式可回退到 postMessage/拖放，属远期风险，不预设计。
- [用户预期"打开 trace 即见浏览器"] → 行为变化是本 change 的目的（显式触发）；InitialTraceNotice / 状态栏可承载提示，文案随实现补充。
- [瞬时服务存续期间用户手动停止应用，浏览器刷新 404] → `local_cache_key` 已缓存的 trace 不受影响；首次加载窗口期极短。

## Migration Plan

单应用内行为变更，无数据迁移。发布说明中标注两点：打开 trace 不再自动弹出浏览器；官方 UI 打开改为 `?url=` 方式。回滚即 revert 提交，`PerfettoUiServer` API 变化均为模块内部，无外部消费者。

## Open Questions

无（端口策略与本地 UI 入口形态已由用户确认：8090 优先随机回退；菜单项）。
