## Why

Trace Analyzer 当前在每次打开 trace 时都会自动启动一个本地 HTTP 服务（默认 `http://localhost:8090/`）并把浏览器导向本地托管的 Perfetto UI。对多数只想快速查看 trace 的用户来说，这是一个常驻的、不可见的后台服务，带来了不必要的端口占用与攻击面；而官方 `ui.perfetto.dev` 的 `?url=` 加载方式配合浏览器 IndexedDB 缓存（`local_cache_key`）已经可以提供同等的浏览体验，无需常驻本地 UI 托管。

## What Changes

- 打开 trace（文件、最近会话、捕获完成、外部跳转）不再自动启动本地 Perfetto UI 服务，也不再自动弹出浏览器。
- "在 Perfetto UI 中打开"按钮改为用户显式触发：瞬时启动一个仅暴露 `/trace` 端点的本地 HTTP 服务（CORS `*`），并以 `https://ui.perfetto.dev/#!/?url=http://localhost:<port>/trace` 打开官方 UI。官方 UI 加载后 trace 进入浏览器 IndexedDB 并重写为 `local_cache_key`，此后刷新/重开不再依赖本地服务。
- 新增菜单项 "Open in local Perfetto UI"（File 菜单）：仅当检测到本地 UI assets 时可用，用户显式选择后瞬时启动服务并托管 UI assets，以 `http://localhost:<port>/#!/?url=http://localhost:<port>/trace` 打开本地 UI。
- 服务端口策略改为 8090 优先、被占用时回退到随机可用端口；浏览器目标 URL 使用实际绑定端口动态生成。
- 移除 iframe embed 兜底页（`buildEmbedPage`，含 `mode=embedded` 与 zoom postMessage 逻辑）——默认路径不再存在"无 assets 托管页面"场景。
- 轨迹诊断（Trace Diagnostics）行为不变：其依赖的 `trace_processor_shell` 本就是点击诊断时按需启动（随机端口），与该本地 UI 服务无耦合。

## Capabilities

### New Capabilities

- `perfetto-ui-launch`: 覆盖"在 Perfetto UI 中打开"的用户触发式启动流程：官方 UI（`?url=`）与本地 UI assets 托管两种模式、瞬时服务的生命周期、端口分配策略（8090 优先、占用回退随机端口）、以及打开 trace 时不再自动启动服务的约束。

### Modified Capabilities

<!-- 无既有 spec 描述 PerfettoUiServer 的服务启动行为；trace-capture-session 与 trace-processor-tool-resolution 的需求不受影响 -->

## Impact

- **代码**：
  - `desktop-viewer/perfetto-viewer/perfetto-ui-server/.../PerfettoUiServer.kt`：start 增加 UI 托管模式参数、端口回退、URL 生成改为官方 `?url=`、删除 embed 页。
  - `desktop-viewer/perfetto-viewer/perfetto-app/.../PerfettoMainPage.kt`：`openTrace` 移除 `launchTraceInUi` 自动调用；按钮/菜单回调改为显式启动；本地 UI 菜单项（含 assets 检测禁用态）。
  - `desktop-viewer/perfetto-viewer/perfetto-app/.../PerfettoFileMenuBar`（菜单结构）与 `perfetto-presentation` / `perfetto-app` 双语言 strings 资源：新增菜单项文案。
  - `desktop-viewer/perfetto-viewer/perfetto-ui-server/.../PerfettoUiServerTest.kt`：两种模式的 URL/端口断言，移除 embed 页断言。
- **行为**：打开 trace 不再有副作用（无服务、无浏览器弹出）；浏览器打开是显式用户动作。
- **兼容性**：无持久化数据格式变化；`PerfettoUiServer` API 为模块内部使用，无外部契约破坏。
