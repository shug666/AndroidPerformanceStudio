# 9. 页面 04：Trace Analyzer

![Trace Analyzer 设计参考](images/04-trace-analyzer.png)

## 9.1 实施原则

**APS 不自己重新实现完整 Perfetto Timeline。**

当前 `PerfettoUiServer` + Trace Processor 是高价值资产，应继续作为主 Trace Viewer。

## 9.2 目标架构

```text
APS Shell
┌─────────────────────────────────────────────────────────────────┐
│ Session / Capture / Open / Export / Search / Diagnostic Preset  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                   Embedded / Launched Perfetto UI                │
│                                                                 │
├──────────────────────────────┬──────────────────────────────────┤
│ Diagnostic Result            │ APS Insights / Correlations       │
└──────────────────────────────┴──────────────────────────────────┘
```

如果当前技术上 Perfetto UI 仍通过本地 server + browser 打开，第一阶段可以保持外部/本地 Web View 行为；不要为了“必须内嵌”阻塞商业化 Shell。

## 9.3 APS 自有功能

### Capture

- Device；
- Trace template；
- Duration；
- buffer；
- capability；
- start / stop；
- recent session。

### Diagnostics

现有 SQL 诊断可商业化为卡片/查询列表：

```text
CPU Scheduling Hotspots
CPU Frequency Distribution
Binder Transaction Latency
Frame Jank Detection
Memory Usage Timeline
Input Event Latency
Thread State Breakdown
Thread Wakeup Latency
```

### Insight

Insight 必须携带：

```text
Query ID
Trace Artifact ID
Result rows / aggregate
Limitations
Open in timeline action（若可定位）
```

## 9.4 不做

第一阶段不做：

- 完整自研 Track rendering；
- 自研 SQL editor IDE；
- 自研 trace processor；
- 假设所有设备都支持同一 Perfetto schema。

## 9.5 验收

- Capture → artifact → open → diagnostic 闭环；
- existing recent session 不丢失；
- diagnostics failure 不影响 trace 打开；
- 各 SQL Query 使用真实 schema compatibility；
- Frame / Startup 跳转到 Perfetto 的关联动作继续工作。

---
