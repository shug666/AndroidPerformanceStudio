## 1. PerfettoUiServer 重构

- [x] 1.1 给 `start()` 增加 UI 托管模式参数（`serveUiAssets: Boolean` 或 `UiServeMode`）：`TRACE_ONLY` 不注册 `/` 文件/嵌入处理器，`TRACE_AND_UI` 沿用现有 assets 托管
- [x] 1.2 实现端口策略：先绑定 8090，`BindException` 回退 ephemeral 端口；暴露实际绑定端口供 URL 生成；绑定完全失败返回 `SERVER_START_FAILED`
- [x] 1.3 `openTrace` 改为按模式生成目标 URL：官方 `https://ui.perfetto.dev/#!/?url=http://localhost:<port>/trace`；本地 `http://localhost:<port>/#!/?url=http://localhost:<port>/trace`（使用实际端口）
- [x] 1.4 删除 embed 兜底页：`buildEmbedPage`、`hasUiAssets` 标志、zoom postMessage 逻辑、`ViewerDocumentTheme`/`ViewerDocumentTheme` 相关依赖；`timestampNanos`/`visStart`/`visEnd` 参数链退役
- [x] 1.5 更新 `PerfettoUiServerTest`：TRACE_ONLY/TRACE_AND_UI 两种模式、端口回退、URL 断言；移除 embed 页断言

## 2. PerfettoMainPage 接线

- [x] 2.1 `openTrace` 移除 `launchTraceInUi` 自动调用：打开 trace 只更新 `activeTraceFile`/`activeArtifact`/`recentFiles`，零服务、零浏览器
- [x] 2.2 将 `launchTraceInUi` 拆为显式入口（`openInPerfettoUi` / `openInLocalPerfettoUi`）：共享"确保服务已启动（含模式）→ `openTrace`"逻辑，供按钮/菜单调用
- [x] 2.3 "在 Perfetto UI 中打开"按钮接官方 UI 入口（`PerfettoCapturePage` 回调不变，仅换实现）；无活动 trace 时报错
- [x] 2.4 File 菜单新增 "Open in local Perfetto UI" 菜单项：assets 不可发现时禁用；启用时走本地 UI 入口
- [x] 2.5 服务停止时机确认：`DisposableEffect.onDispose` 与 `isActive == false` 分支的 `uiServer.stop()` 保留
- [x] 2.6 清理 `initialTraceTimestampNanos` 链路（参数保留传递，不再驱动 URL zoom）

## 3. 文案与资源

- [x] 3.1 `perfetto-presentation` 与 `perfetto-app` 的 `values/strings.xml` 与 `values-zh/strings.xml` 新增 "Open in local Perfetto UI" / “在本地 Perfetto UI 中打开” 文案
- [x] 3.2 确认 "Open in Perfetto UI" 现有文案无需变更

## 4. 验证

- [ ] 4.1 手动验证：打开 trace（文件/最近会话/捕获完成/外部跳转）无服务启动、无浏览器弹出（`lsof -i :9001` 无监听）
- [x] 4.2 手动验证：官方 UI 模式——浏览器打开 `ui.perfetto.dev/#!/?url=http://127.0.0.1:9001/trace_file.perfetto-trace&referrer=record_android_trace`，trace 正常加载（已由用户于 2026-10-18 实测通过；刷新后经 `local_cache_key` 仍可加载）
- [ ] 4.3 手动验证：本地 UI 模式——assets 存在时菜单可用并正常打开本地 UI；assets 不存在时菜单禁用
- [ ] 4.4 手动验证：9001 被占用时启动报错提示（不回退其它端口）
- [ ] 4.5 手动验证：轨迹诊断在服务未启动时正常执行
- [x] 4.6 运行受影响模块测试：`perfetto-ui-server`、`perfetto-app`（`./gradlew` 相关 test 任务）

## 5. 实现后追加修复（实现过程中发现并落地）

- [x] 5.1 CORS/PNA 修复：`/trace` 预检响应（OPTIONS 204）；最终裁剪为 record_android_trace 头集合（Origin 回显 + `Cache-Control: no-cache`）
- [x] 5.2 端口契约修正：8090 优先+随机回退 改为固定 `127.0.0.1:9001` 不回退（`ui.perfetto.dev` CSP 只允许 9001，spec 已同步）
- [x] 5.3 URL 形态对齐 record_android_trace：`localhost` → `127.0.0.1`、`/trace` → `/trace_file.perfetto-trace` 命名路径、`referrer=record_android_trace`
- [x] 5.4 最近会话"打开"按钮与 File → Open Recent 接 `openInPerfettoUi`：激活 trace + 官方 UI 打开
- [x] 5.5 时间戳显示修复：最近会话 `capturedAt` 与录制中 `startTime` 由 `Instant.toString()`（UTC 纳秒）改为本地时区 `yyyy-MM-dd HH:mm:ss`（`formatRecentSessionTimestamp` / `formatCaptureTimestamp`，含单元测试）
