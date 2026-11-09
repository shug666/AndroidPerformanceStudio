# Design: add-winscope-capture

## Context

ADR 0035（2026-09-10）移除了全部 Winscope 交付路径：原生 Compose workspace（ADR 0032）与基于 AOSP submodule 源码构建的浏览器查看器（ADR 0033/0034），原因是 4.3 GiB 的 submodule 与 Node/pnpm/Go 构建链是仓库最重的依赖。ADR 0035 的结论明确留了后路："if the browser viewer returns, prefer a smaller delivery path over a full source build in Gradle."

本次引入的预构建产物（下称 *bundle*）就是这条更小路径的实例：

| 内容 | 大小 | 说明 |
|---|---|---|
| `index.html` | 33 MB | Angular 应用主入口（构建期大量内联） |
| `js/` 分包 | 33 MB | webpack 分包（app、npm.three、npm.protobufjs、npm.rxjs 等） |
| `trace_processor.wasm` | 11 MB | Perfetto trace processor |
| `engine_bundle.js` | 0.25 MB | Perfetto 查询引擎 JS 侧 |
| 静态资源（svg 等） | 少量 | logo、trackpad 提示图 |

经逆向验证的客户端协议事实（均来自 bundle 内 `ProxyConnection` 的 minified 代码）：

- **端口写死**：`WINSCOPE_PROXY_URL = "http://localhost:5544"`，不可配置。
- **版本校验**：客户端读响应头 `Winscope-Proxy-Version`，校验规则为 major 相等且 (minor 更大 或 minor 相等且 patch ≥ 客户端)；客户端版本 `4.0.8`。服务端直接回 `4.0.8` 即可。
- **Token 握手**：客户端从 URL 查询参数 `?token=` 或 localStorage（key `adb.proxyKey`）取 token，并以 `Winscope-Token` 请求头发送；连接失败时 UI 提供"粘贴 token 重试"兜底（`onRetryConnection`）。
- **端点集合**：`GET /devices`、`GET /status`、`GET /fetch/...`、`POST /start`、`POST /end`、`POST /dump`、`GET /checkwayland`。
- **参考实现**：bundle 目录内的 `winscope_proxy.py`（AOSP v4.0.8）是上述协议的权威规范，含设备命令序列、perfetto 配置文本、降级决策表。

APS 已有的可复用件：`platform-core/adb-core` 的 `AdbClient` 抽象与 `AdbRootController`（`adb root` + uid 0 校验）、`perfetto-capture` 的抓取会话范式、`perfetto-ui-launch` spec 确立的 loopback 服务安全模式（`jdk.httpserver`、显式启动、固定端口、CORS/no-store 头纪律）。

## Goals / Non-Goals

**Goals:**

- 在 Windows/Ubuntu 上无需源码树、无需 Python/Node 工具链，从 APS 一键进入完整 Winscope 工作流（设备检查 → 抓取 → 浏览器查看）。
- APS 独占 ADB：抓取全部经现有 `AdbClient` 执行，浏览器查看器只作为 UI/配置面。
- 客户端协议一次对齐、钉死 bundle 版本；升级 bundle 是显式人工动作（重新对齐协议 + 校验闭包 + 更新 license 清单）。
- 抓取产物在 APS 内归档，为后续证据关联/分析预留钩子。

**Non-Goals:**

- 不做应用内 Winscope trace 渲染；查看一律走浏览器。
- 不做 APS 原生抓取参数 UI；抓取配置由 Winscope 浏览器 UI 下发（`/start`、`/dump` 载荷）。
- 不修改 bundle 的功能逻辑；仅允许打包期的资源层处理（剔除 analytics 外链脚本）。
- 不支持无 root 的生产版设备抓取 WM/SF 系统级 trace（平台能力限制，探测后明确报因）。
- 不实现 Wayland 设备的特殊支持（`/checkwayland` 仅协议兼容，返回客户端可接受的否定结果）。

## Decisions

### D1: APS 用 Kotlin 实现 proxy HTTP 协议，而非 spawn `winscope_proxy.py`

- 实现 bundle 客户端已知的 7 个端点 + token/版本头，运行于 `jdk.httpserver`，仅绑定 `127.0.0.1:5544`。
- **为什么**：spawn Python proxy 会要求 Windows 用户装 Python 3.10+（违背开箱即用），且 proxy 自行调用 `adb` 造成 ADB 所有权分裂、trace 落在 proxy 临时目录无法归档进 APS。协议风险（"客户端不认识我们的响应"）被 bundle 钉死版本所消除——对齐一次，永不漂移。
- **替代**：spawn Python proxy（实现最快，被系统依赖与所有权问题否决）；改造 bundle 去掉 proxy 依赖只做文件查看（丢掉浏览器驱动抓取的完整闭环，否决）。
- 端口 5544 由客户端写死，APS 与用户本机其他 Winscope proxy（如源码树启动的）可能冲突：服务启动时检测端口占用，失败时给出明确错误（提示关闭其他 winscope_proxy 实例），不做端口漂移（漂移后客户端永远探测不到）。

### D2: Token 采用会话级随机生成 + URL 参数注入

- 服务启动时生成 32 字节 hex 随机 token（内存持有，不落盘），浏览器以 `http://127.0.0.1:8000/index.html?token=<token>` 打开完成零感知握手；本地 localStorage 兜底为用户手动粘贴 token 的场景。
- **为什么**：替代 AOSP proxy 写 `~/.config/winscope/.token` 的全局持久 token——那是跨会话共享的秘密，还会与本机其他 proxy 冲突。会话级 token + URL 注入对用户零摩擦，且关闭即失效。
- 静态查看器资源另用一个本地端口（如 8000，可配置，冲突时顺延）由同一服务器家族托管；静态端点带 `Cache-Control: no-store`（证据纪律），静态资源本身可缓存但 bundle 引用带 hash，无需缓存也能接受。

### D3: 打包 bundle 为固定版本资源闭包，禁止进入 Gradle 构建链

- bundle 目录（剔除 `winscope_proxy.py`、`*.bat`、analytics 外链）作为资源进 desktop-app 打包闭包；资源清单记录来源版本标识与文件校验和，启动时校验（对齐 ADR 0033 的打包纪律，但闭包从 45 MB 基线换成实际 bundle）。
- **为什么**：ADR 0035 的教训是"构建期拉起上游构建"不可持续。bundle 是死产物，升级=人工替换+重新对齐协议+更新校验和与 license 清单，全部显式。
- **替代**：用户外部目录 + 设置页指定路径（安装体验破碎，否决）；恢复 submodule 源码构建（ADR 0035 已否决）。

### D4: 设备端抓取照抄 proxy.py 的决策表，现代路径优先、传统路径降级

- `/start`、`/dump` 到达时按 proxy.py 逻辑执行：
  1. **能力探测**：`perfetto --query-raw`（或等价命令）查询设备数据源，判定 WM shell transitions、SF transactions、ProtoLog、IME、ViewCapture、Input 等数据源可用性。
  2. **现代路径**：数据源可用 → 下发合流 perfetto 配置，`perfetto` 会话运行，产物为单一 `.perfetto-trace`。
  3. **传统路径降级**：探测不可用 → 逐源命令（`su root cmd window tracing start/stop`、`su root service call SurfaceFlinger 1025/1020/1041`、launcherapps dump-view-hierarchies 等），从 `/data/misc/wmtrace/` 收集产物。
- 逐源配置（tracing type/level、buffer size、SF flags 等）由浏览器 UI 随请求下发，与 proxy.py 的 config 字段对齐。
- **为什么**：用户决策（现代优先+探测降级）；proxy.py 是已验证的权威决策表，照抄而非自行发明降级启发式。
- 前置动作（每次抓取）：root 检查（复用 `AdbRootController`，失败即明确报因）；残留 perfetto 会话检测与 `traced` 重启清理（参照用户 `start_winscope.sh` 第 2 步，避免与 APS 自身 Perfetto 抓取共用 `traced` 冲突）；清理设备端 winscope 状态文件残留。

### D5: 抓取产物双路归档

- `/fetch` 返回给浏览器的字节流，同时落盘到 APS 会话工件目录（Winscope 会话：设备、时间、数据源集合、来源请求上下文）。
- **为什么**：proposal 承诺的 AI 分析钩子；零额外抓取成本（本来就是 APS 执行 pull）。
- 旧格式多文件（传统路径）按 proxy.py 的打包约定合并为浏览器可接受的载荷，同时逐文件归档。

### D6: 服务生命周期与安全边界

- Winscope 服务仅从 Winscope 页面的显式"启动"动作开启（对齐 `perfetto-ui-launch` 的"打开≠启动服务"纪律）；应用退出或用户显式停止时关闭、token 失效。
- 所有证据端点 `Cache-Control: no-store`；CORS 不用 `*` 通配（静态查看器与 proxy 端口不同源，CORS 按 proxy.py 的必要头集合最小化配置——保留 `Access-Control-Allow-Origin: *` 仅因客户端 bundle 自身如此请求，暴露头仅 `Winscope-Proxy-Version`）。
- 抓取进行中的状态在 APS 页面可见（设备、数据源、时长），`/status` keep-alive 超 5 秒间隔 ×2 判定浏览器失联，抓取按 proxy.py 的超时策略收尾而非永久悬挂。

### D7: 模块结构

```
desktop-viewer/winscope-viewer/
├── settings.gradle.kts          (composite, group com.androidperformancestudio.winscope)
├── winscope-app/                # Compose 抓取页面 + DesktopApp 装配（对齐 screencast-app 模式）
├── winscope-capture-service/    # proxy 协议服务端、静态托管、浏览器启动、token
├── winscope-device-capture/     # 设备命令决策表、能力探测、文件收集（依赖 adb-core）
└── winscope-resources/          # 打包 bundle 闭包 + 校验和清单 + license 清单
```

- 实现期新增 `:winscope-app`（design 原列三个模块）：页面与装配层参照 device-screencast 的
  `screencast-app` 模式（MainPage + Dependencies 工厂），页面代码不属于协议服务端也不属于 desktop-app。
- desktop-app 依赖 `aps-winscope-app`（app 再依赖 capture-service/device-capture）；home 新增
  `AppDestination.WINSCOPE` 入口与抓取页面（起停服务、设备/抓取状态、错误呈现、降级原因说明）。

## Risks / Trade-offs

- [minified 客户端协议细节遗漏（如 /fetch 流式格式、/devices JSON 字段）] → 以 proxy.py 为规范逐端点实现 + 真机端到端联调先行 spike（token 握手已验证，剩余为载荷形状）；对不齐的端点在真实设备上抓包对照 proxy。
- [bundle 78 MB 使安装包显著膨胀] → 记录实际压缩后增量并在 release notes 说明；闭包校验剔除陈旧文件；与 ADR 0033 的 45 MB 基线不同属预期，接受。
- [端口 5544 被占用（其他 Winscope proxy / 历史残留进程）] → 启动检测 + 明确错误文案指引；不自动杀进程。
- [Root 不可用设备上用户预期落差] → 能力探测前置，页面明确显示"设备未 root，无法抓取系统级 trace"及降级范围。
- [bundle 内 analytics 部分残留（gtag 外链剔除后仍有内联调用）] → 联调时网络验证无外发请求（对齐 ADR 0033 验收 6）；若仍有出站请求则评估剥离方案（如 hosts 级或响应拦截），不阻塞主流程。
- [客户端 localStorage 缓存旧 token 导致 403] → 403 响应触发客户端"粘贴 token 重试"UI（已验证存在）；文档注明重开浏览器即可（URL 带新 token）。
- [与 APS 自身 Perfetto 抓取并发冲突（共用设备 traced）] → 两处入口互斥提示：Winscope 抓取启动时若 APS Perfetto 会话在录，明确报错要求先停止；反向同理（探测设备 perfetto 会话名单）。

## Open Questions

- `/fetch` 对现代合流 trace 与传统多文件的确切响应封装格式（proxy.py `FetchFilesEndpoint` 细节）——实现首个 spike 解决。
- bundle 来源版本标识的登记形式（AOSP commit / 构建日期）——待用户提供 bundle 出处后补充到资源清单。
- 抓取页面是否需要展示浏览器查看器的"已打开"状态引导（避免重复开浏览器）——实现期按体验定。
