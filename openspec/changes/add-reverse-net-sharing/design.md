## Context

探索阶段已核实的关键事实：

- gnirehtet 2.5.1（Apache 2.0，上游停止活跃维护）由三部分组成：设备端 VPN 客户端（`app/`，VpnService，API 21+）、relay 服务端（`relay-rust` / `relay-java`）、adb reverse 隧道。relay-java 核心状态机（`TCPConnection` 487 行、`Client` 223 行、`Router`/`StreamBuffer`/包解析等）约 2500 行纯 Java。
- 隧道协议无 API key：设备端 `RelayTunnel` 连接 `localabstract:gnirehtet`，relay 回 4 字节 client id，之后是裸 IPv4 包双向流。自研 relay 与官方 APK 协议兼容，无需改 APK。
- 隧道里传的是裸 IP 包，relay 对字节流版本无关；IPv4 假设埋在 TUN 配置（浅层）与 relay 状态机（深层）。
- IPv6 完整隧道需要扩展头解析、分片重组、PMTU/MSS 处理，估 2000+ 行且联调成本高；上游 issue #69 挂多年未做。
- APS 已有 `platform-core/adb-core`（adb 命令执行、设备目录、监控）；`desktop-app` 为 Compose Desktop；`network-profiler` 是模块家族（`network-app` 是其桌面入口应用，非设备端 app）。APS 没有常驻设备端 app。
- APS 已 vendor `third_party/perfetto`，vendor 第三方产物有既有模式。

## Goals / Non-Goals

**Goals:**

- 设备无网（场景 A）或有网需强制走电脑（场景 B）时，通过 USB + adb 反向供网。
- 白盒 relay：流经隧道的所有设备 TCP/UDP 流量产出可观测事件，与 OkHttp agent 数据互补。
- VPN 授权状态可检测，UI 给出引导。
- IPv6 防泄漏：DNS AAAA 过滤 + Private DNS 检测 + 周期性 v6 泄漏审计。
- 零新增设备端组件（除 vendor 的 gnirehtet.apk）。

**Non-Goals:**

- 多设备并行供网（单设备会话）。
- IPv6 隧道（完整 v6 转发）。
- 重签/修改 gnirehtet.apk。
- 设备蜂窝/WiFi 开关控制。
- 供网吞吐性能优化（JVM relay 单设备够用；瓶颈出现时再演进）。

## Decisions

### D1: 内嵌 Kotlin relay，而非 vendor Rust 二进制或编排外部进程

- vendor Rust 二进制（黑盒）：看不到包，可观测性目标直接失败；加 tap 需改 Rust 源码 + 维护交叉编译链。
- 外部进程编排：同样黑盒，且引入子进程生命周期管理、平台差异。
- **Kotlin 复刻 relay-java 核心**：在 APS 进程内运行，tap 钩子自然植入；Apache 2.0 允许；~2500 行移植量可控；JVM relay 单设备吞吐足够（gnirehtet 官方将 Java 版定位为"可用备选"）。
- 参考实现选 `relay-java`（与目标语言同构，比移植 Rust 源码更直接）。

### D2: vendor 官方 gnirehtet.apk，不重签

- VPN 授权是系统 VpnService 对话框，与 APK 签名无关。
- 自研 relay 与官方 APK 隧道协议完全兼容（无 API key，client id 握手 + 裸 IP 包）。
- 遵循 `third_party/` 既有 vendor 模式：`third_party/gnirehtet/gnirehtet.apk` + README 注明来源版本（2.5.1）与许可。

### D3: VPN 授权状态由 relay 连接状态反推，无需设备端钩子

```
启动会话后：
  relay 收到隧道连接 + client id 握手 ──▶ 已授权，供网中
  15s 内无连接                        ──▶ 等待授权，UI 提示"请在设备上确认 VPN 连接请求"
  adb reverse 失败 / 设备离线          ──▶ 错误态
```

- 依据：`GnirehtetActivity` 逻辑——`VpnService.prepare()` 返回 null（已授权）才启动 service 建隧道；未授权时弹系统对话框，用户确认后隧道才建立。
- 辅助精确化：`adb shell dumpsys vpn`（若平台命令可用）交叉验证；设备离线时 adb 层错误直接映射错误态。

### D4: IPv6 采用三道防线，不做隧道

- 防线 1（DNS 层，relay 内）：tap 解析 UDP:53 响应时剥离 AAAA 记录。app 拿不到 v6 地址 → 流量留在 v4 隧道。覆盖绝大多数泄漏渠道。
- 防线 2（检测层）：检测 Private DNS (DoT)——`adb shell settings get global private_dns_mode` 探测为主，DNS 事件流消失为辅。命中时 UI 警告 DNS 可观测性与 AAAA 过滤降级。
- 防线 3（审计层，LeakAuditor）：会话期间周期（30s）经 adb 探测设备 v6 出网能力（`ping6` 主探测 + `ip -6 route get` 辅助）。仅在设备存在非隧道网络接口时启用（`dumpsys connectivity` 判定），无网设备不浪费探测。命中则 UI 亮红 + 建议操作（关 WiFi/飞行模式）。周期审计同时天然覆盖"供网期间 WiFi 漂移重连"的检测。
- Hardcode v6 地址的 app 不被防线覆盖，UI 注明此局限。
- 完整 v6 隧道列 non-goals：扩展头/分片/NDP 处理成本约 2000+ 行，APS 场景下优先级不足，留待真实需求出现再评估。

### D5: 模块落点 network-profiler 下，事件走独立事件流

- 新模块 `network-profiler/reverse-net/`，加入 `network-profiler/settings.gradle.kts`。
- 内部结构：`session/`（编排 + 状态机）、`relay/`（数据面 + tap）、`dns/`（UDP:53 解析 + AAAA 过滤）、`leak/`（泄漏审计）、`events/`（事件模型与输出）、`status/`（授权状态机）。
- 依赖 `platform-core/adb-core`（已通过 includeBuild 引用）。
- 供网事件输出为独立事件流，network-profiler UI 消费（时间轴上与 OkHttp agent 事件互补）；不修改现有 agent 协议。
- UI 控件落在 `desktop-app` 设备面板（开关 + 隔离模式选择 + 状态指示），订阅 SessionController 暴露的 StateFlow。

### D6: relay 线程模型与 tap 位置

- 移植 relay-java 的阻塞式线程模型，移植保真度优先于性能重构。
- tap 放在包解析 choke point（IPv4 头解析后、TCP/UDP 分发前），DNS 解析与 AAAA 过滤在 tap 通路内对 UDP:53 同步完成（代价低）。
- tap 事件经有界队列异步发出（容量上限 + 丢弃计数，参考 `EventBuffer` 模式），不阻塞转发热路径。

## Risks / Trade-offs

- [JVM relay 吞吐不足] → 单设备场景够用；tap 有界队列不阻塞热路径；架构上先固定 tap 事件模型，热路径实现可替换为 Rust+事件通道（演进项）。
- [移植引入转发 bug（TCP 状态机细节）] → 移植保持 relay-java 结构逐文件对照；单连接回环测试（本地模拟隧道两端）验证 SYN/数据/关闭序列；实机验证 DNS、HTTP、TLS、长连接。
- [AAAA 过滤对 hardcode v6 的 app 无效] → UI 注明局限；防线 3 审计兜底检测并告警。
- [Private DNS 使 DNS 事件与 AAAA 过滤失效] → 防线 2 检测并 UI 警告；`settings get global private_dns_mode` 各版本可用。
- [探测命令跨版本差异（`ping6`/`ip` 可用性）] → 探测策略可插拔；首版 `ping6` 主 + `ip -6 route get` 辅，失败静默降级（不计泄漏，仅记录）。
- [供网会话异常退出（APS 崩溃）残留状态] → 停止路径逆向清理（am stop → reverse --remove → relay 停）；APS 启动时检测残留（`adb reverse --list`）并提供恢复。
- [设备网络被外部改变（WiFi 重连等）] → 防线 3 周期审计覆盖漂移检测，命中告警。
- [用户设备上已有其他 VPN 冲突] → 供网启动前检测已有 VPN（`dumpsys connectivity`），命中时 UI 确认后继续。
- [任一侧休眠导致隧道断开] → 风险接受：异常断开映射错误态 + 一键恢复。
- [vendor APK 版本固化] → README 注明来源版本与许可；升级策略：手动替换 + 验证隧道协议未变（协议自 2017 年未变）。
- [JVM GC 停顿影响转发延迟] → 风险接受：单设备场景影响有限。
