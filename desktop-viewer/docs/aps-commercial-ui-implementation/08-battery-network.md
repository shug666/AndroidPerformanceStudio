# 页面 08：Battery & Network

![Battery & Network 设计参考](images/08-battery-network.png)

## 13.1 UI 合并，模型不合并

推荐页面结构：

```text
Battery & Network
[Battery] [Network] [Correlation]
```

实现上仍然是：

```text
BatteryProfilerController
NetworkProfilerController
```

不能建立一个丢失业务语义的通用 event model。

## 13.2 Battery Tab

### KPI

```text
Wakelock Duration
Wakeup Alarms
Network Bytes
Modeled Energy（只有 evidence 存在时）
```

### 主要区域

```text
Experiment summary
Runs
Battery snapshots / history
Wakelocks
Alarms
Jobs
Sensors
Energy
Network counters
Warnings
```

### History

按真实 `BatteryHistoryEventKind`：

```text
WAKELOCK
ALARM
JOB
SENSOR
NETWORK
APP_STATE
SCREEN
CHARGING
THERMAL
```

## 13.3 Network Tab

### Summary

```text
Requests
Failures
Transferred bytes
Median/slow request（从真实 call duration 聚合）
Evidence completeness
```

### Request List

字段：

```text
Method
Redacted URL
Status
Duration
Bytes
Protocol
Outcome
Source
```

### Request Detail

```text
Dispatcher Queue
DNS
Connect
TLS
Request Headers/Body
Server Wait
Response Headers/Body
Connection Held
```

必须显示 `NetworkConfidence`，尤其 HAR import 的 inferred/approximated phase。

## 13.4 Correlation Tab

第一阶段只在有可靠 mapping 时开放：

```text
Battery event + Network call
```

需要：

- 可比较的 time domain；
- capture window；
- clock mapping error bound；
- artifact provenance。

若不满足，显示：

```text
These artifacts are independent evidence and cannot be aligned on one clock.
```

而不是强行画在同一 X 轴。

## 13.5 验收

- Battery / Network 可以独立使用；
- 一方失败不影响另一方；
- HAR inferred phase 有视觉区分；
- sensitive URL/headers 继续遵守 redaction；
- correlation 无 clock evidence 时明确不可用。

---

## 13.6 当前落地与证据边界（2026-10-15）

- 合并入口保留 Battery / Network / Correlation，控制器及运行状态仍相互独立。
- Battery 增加可搜索、重新打开的持久化实验会话侧栏；新建仅清空当前查看状态，不删除已有会话，也不因打开历史证据而切换 ADB 采集目标。窄窗口侧栏移到内容上方。
- Battery 快照图使用真实 `capturedAt` / 电量样本，缺失样本断线；外部供电只表示已观测采样点，不外推充电区间。历史图按实际事件类型分轨，保留未知类型、无时间戳事件与原始证据，不将历史相对时钟强行映射为快照时间。
- Battery 资源区提供 Wakelocks / Alarms / Jobs / Sensors / Energy / Network / History / Warnings 标签、搜索、行选择及证据详情。未采集的最后触发时间、进程、线程和原因不补造；无能耗证据不显示 Modeled Energy KPI。
- SQLite 恢复保留网络分项、事件、快照顺序及热状态。旧会话或摘要 JSON 仅有流量总数时只显示总字节，分项不可用，不将总量归为 Wi-Fi 下载；历史会话 CSV 导出保留已知归因范围，未知范围标记 `UNKNOWN`。
- Network 增加请求活动图、脱敏字段搜索、调用结果筛选与 Domains 汇总；筛选同步影响列表和详情，窄窗口上下排列。活动图下半部是“请求结束时记录的字节分桶”，不是实测吞吐率；缺失字节证据的域名总量/分桶保持不可用。
- HAR 推断/估算阶段继续使用琥珀色虚线并显示置信度。无跨域时钟证据时，Correlation 仍明确不可用。
- 视觉沿用当前 `DESIGN.md` 的紧凑共享主题，不复制图片中的装饰横幅，不伪造图中数值、同比百分比或“高耗电”归因。

### 验证入口

在 `desktop-viewer` 下执行：

```bash
./gradlew :battery-profiler:battery-app:test \
  :battery-profiler:battery-app:ktlintCheck :battery-profiler:battery-app:detekt \
  :network-profiler:network-presentation:test \
  :network-profiler:network-presentation:ktlintCheck :network-profiler:network-presentation:detekt

./gradlew :desktop-app:test \
  --tests '*BatteryNetworkWorkspaceTest' --tests '*BatteryNetworkInteractionTest'
```

自动 Compose 截图由测试夹具提供，不注入生产数据。设置 `APS_BATTERY_PROFILER_SCREENSHOT_DIR` / `APS_NETWORK_PROFILER_SCREENSHOT_DIR` 可输出宽屏/窄屏截图；`APS_BATTERY_PROFILER_SCREENSHOT` / `APS_NETWORK_PROFILER_SCREENSHOT` 输出中文深色主截图。当前截图目录：`desktop-viewer/build/visual-captures/battery-network/`。

这些截图和本地测试不替代真实设备采集、原生窗口交互或打包应用验收。
