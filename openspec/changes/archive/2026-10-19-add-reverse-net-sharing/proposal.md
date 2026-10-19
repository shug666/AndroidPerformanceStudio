## Why

性能测试经常需要设备处于确定性网络环境：设备无网时被测 app 无法运行联网场景；设备有网时，流量绕过分析（走设备自身 WiFi/蜂窝，APS 看不到）。Android Performance Studio 需要一个"反向供网"模块，把电脑的网络通过 adb 提供给设备，同时把流经隧道的全部设备流量转化为可观测的网络事件。参考工程 gnirehtet（Apache 2.0）已验证该技术路线，其 relay 端核心状态机约 2500 行，可在 APS 桌面进程内以 Kotlin 复刻并植入可观测性钩子。

## What Changes

- 新增 `network-profiler/reverse-net` Gradle 模块，提供设备反向供网能力：
  - 内嵌 Kotlin relay 服务端（自 gnirehtet relay-java 移植，Apache 2.0 版权头保留），监听 `127.0.0.1:31416`，将设备 IP 包代开为电脑本机 socket。
  - 会话编排器（SessionController）：`adb install` vendor 的 gnirehtet.apk（官方 2.5.1，不重签）→ `adb reverse localabstract:gnirehtet tcp:31416` → 启动 relay → `am start` 启动设备端客户端，以及停止时的逆向清理。
  - VPN 授权状态机：以 relay 收到设备隧道连接（含 client id 握手）为"已授权供网中"信号；超时未连接判定为等待授权并在 UI 提示。
- relay 植入 tap 钩子，产出网络观测事件：
  - 连接表事件：设备侧连接五元组、TCP 状态、字节数、时长（含 TLS 流量的元数据）。
  - DNS 事件：解析隧道内 UDP:53 响应，产出域名→IP 映射事件。
- IPv6 防泄漏三道防线（供网会话期间）：
  - 防线 1：DNS 应答中剥离 AAAA 记录（app 拿不到 v6 地址，流量留在 v4 隧道）。
  - 防线 2：Private DNS (DoT) 检测，命中时 UI 警告 DNS 可观测性与 v6 防泄漏降级。
  - 阿线 3：泄漏审计（LeakAuditor）：设备存在非隧道网络时，周期经 adb 探测 v6 出网能力，命中则 UI 亮红并给出建议操作。
- `desktop-app` 设备面板新增「反向供网」控件：开关、隔离模式选择（宽松/隔离）、状态指示（供网中/等待 VPN 授权/错误）。
- 供网事件接入 network-profiler 现有事件流，与 OkHttp EventListener 数据在时间轴上互补。

## Capabilities

### New Capabilities

- `reverse-net-sharing`: 反向供网会话生命周期管理——安装、隧道建立、relay 启动、客户端启动/停止、状态报告（含 VPN 授权检测）。
- `reverse-net-relay`: 桌面端内嵌 relay 数据面——TCP/UDP 连接代开、IPv4 包转发、tap 事件产出、DNS 解析与 AAAA 过滤。
- `reverse-net-leak-audit`: IPv6 泄漏防护——Private DNS 检测、周期性 v6 泄漏审计、泄漏告警。

### Modified Capabilities

（无。供网事件对 network-profiler 的接入是事件流消费，不改变现有 capability 的需求。）

## Impact

- 新模块：`desktop-viewer/network-profiler/reverse-net/`（新 Gradle 模块，加入 `network-profiler/settings.gradle.kts`）。
- 依赖：复用 `platform-core/adb-core`（adb 命令执行、设备目录）；vendored `gnirehtet.apk`（官方 2.5.1，Apache 2.0）作为打包资源。
- UI：`desktop-app` 设备面板新增控件与状态指示。
- 许可合规：relay 移植代码保留 gnirehtet 原始版权与 Apache 2.0 声明。
- Non-goals：多设备并行供网；IPv6 隧道（完整 v6 转发，约 2000+ 行扩展头/分片/NDP 处理，留待真实需求）；重签或修改 gnirehtet.apk；蜂窝数据开关控制。
