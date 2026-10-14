# 13. 页面 08：Battery & Network

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
