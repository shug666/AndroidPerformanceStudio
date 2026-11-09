# Tasks: add-winscope-capture

## 1. 协议规范提取与 spike 验证

- [x] 1.1 通读 `winscope_proxy.py`，将 7 个端点的请求/响应形状（含 `/devices` JSON 字段、`/start` 载荷 config 字段、`/fetch` 流式封装）整理为实现对照文档（`docs/winscope-proxy-protocol.md`）
- [x] 1.2 spike：用本地 stub HTTP 服务 + 打包 bundle 在浏览器验证握手全链路（`?token=` 注入 → `/devices` 列表 → 版本头校验），记录失败路径（403 重试 UI 行为）
- [x] 1.3 确认 bundle 来源版本标识（AOSP commit / 构建时间）并登记到资源清单说明

## 2. winscope-viewer 模块骨架

- [x] 2.1 创建 `desktop-viewer/winscope-viewer/` composite（settings.gradle.kts）与 `winscope-capture-service`、`winscope-device-capture`、`winscope-resources` 三个子模块
- [x] 2.2 在 desktop-app 注册依赖与 `AppDestination.WINSCOPE` 入口（home 卡片、导航、双语字符串）
- [x] 2.3 Winscope 页面骨架：服务起停动作、状态区、错误/降级原因呈现（暂无后端行为）

## 3. 打包资源闭包

- [x] 3.1 将 bundle 运行闭包（index.html、js/、trace_processor.wasm、engine_bundle.js、静态资源）纳入 `winscope-resources`，剔除 `winscope_proxy.py`、`*.bat`、analytics 外链脚本标签
- [x] 3.2 生成资源清单（来源版本标识 + 文件校验和）与第三方 license 清单（bundle 内 js 依赖的 LICENSE.txt 汇总）
- [x] 3.3 desktop-app 打包任务接入闭包与校验（启动时校验和验证、失败拒绝托管）

## 4. 设备端抓取执行层

- [x] 4.1 实现 root 前置检查（复用 `AdbRootController`）与失败报因
- [x] 4.2 实现抓取前残留 perfetto 会话检测与清理（`traced` 重启策略、状态文件清理），及与 APS Perfetto 抓取的并发互斥
- [x] 4.3 实现 perfetto 数据源探测（`/devices` 所需的 screenrecord 版本、显示器信息探测一并实现）
- [x] 4.4 实现现代路径：合流 perfetto 配置下发、会话启动/停止、产物收集
- [x] 4.5 实现传统路径降级：逐源命令决策表（`cmd window tracing`、`service call SurfaceFlinger` 系列、view hierarchies dump）、`/data/misc/wmtrace/` 文件收集
- [x] 4.6 实现抓取状态机（录制中、keep-alive 超时收尾、产物就绪）

## 5. proxy 协议服务端

- [x] 5.1 实现 5544 loopback 服务生命周期（显式启停、端口占用检测与报错、会话级 token 生成与 403 校验、`Winscope-Proxy-Version: 4.0.8` 头）
- [x] 5.2 实现 `GET /devices`、`GET /checkwayland`
- [x] 5.3 实现 `POST /start`、`POST /end`、`POST /dump`（配置载荷 → 设备执行层）
- [x] 5.4 实现 `GET /fetch`（流式返回）与 `GET /status`（保活计时）
- [x] 5.5 实现静态托管（本地端口、no-store 纪律、无目录列举）与"打开查看器"动作（`?token=` 注入浏览器启动）
- [x] 5.6 实现 `/fetch` 双路归档：APS 会话工件落盘 + 设备/路径/数据源元数据

## 6. 页面集成与状态呈现

- [x] 6.1 Winscope 页面接入服务状态、设备与抓取状态展示（数据源、实际路径、时长）
- [x] 6.2 错误路径呈现：root 失败、端口占用、闭包校验失败、并发互斥提示

## 7. 测试与验收

- [x] 7.1 协议端点单测：以 proxy.py 参考行为为对照（token 校验、版本头、载荷形状、403/no-store/无目录列举）
- [x] 7.2 设备执行层单测：决策表降级路径、清理前置、超时收尾、并发互斥
- [x] 7.3 真机端到端联调：root 设备完整走通（握手 → 现代/传统路径抓取 → 浏览器查看 → 归档核对）
  - 设备：TB710FU（userdebug，`su root`=0，8 个 winscope 数据源全部可用）
  - 现代路径：`WinscopeDeviceCaptureIT` 驱动 /start → keep-alive → /end → /fetch，合流 `trace.perfetto-trace`（64KB）返回并双路归档（本地工件 + capture-meta.json），设备零残留会话
  - 传统路径：真机驱动 `transactions_legacy`+`eventlog`（恒 legacy 目标）→ end `[]` → fetch `eventlog.gz` 成功
  - `WinscopeBrowserHandshakeIT`（真机模式）+ headless Chrome：bundle 完整 bootstrap（DOM 34.28MB），浏览器接受 Kotlin 版本头与 token
  - `/devices` 载荷与参考 Python proxy（5555 端口实测）逐字段对比等价
  - 联调修复两个真实偏差：screenrecord 版本探测需合并 stderr（参考实现 `subprocess.STDOUT`）；抓取取消语义对齐参考 SIGINT 语义（取消 = 正常停止而非错误）；fetch 改为临时文件 pull（exec-out cat 的输出上限预分配会撑爆 VM 数组上限）
- [ ] 7.4 验收核对：运行时无第三方分析请求、闭包无 `winscope_proxy.py`/bat、Windows 与 Ubuntu 打包各跑一次冒烟〔闭包与 analytics/字体剔除已核对；Windows 打包冒烟待 CI 或本机 Windows 环境〕
- [x] 7.5 新增 ADR：记录与 ADR 0033/0035 的关系、bundle 钉死升级流程、协议对齐责任
