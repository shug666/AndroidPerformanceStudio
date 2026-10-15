# 页面 07：Startup Profiler

![Startup Profiler 设计参考](images/07-startup-profiler.png)

## 12.1 目标布局

```text
Cold | Warm | Hot

Toolbar
Device | App | Component | Runs | Compilation | Profile | Start

KPI
TTID | TTFD | First Frame | Stability/P95

Startup Waterfall
Process Start
Initializer
Activity Create
Activity Resume
First Frame
Fully Drawn

Runs Table                                  Run Detail
Iteration / Observed / Total / ...          Evidence
                                             Compiler/Profile
                                             Environment
                                             Trace correlation

Baseline Comparison / Recommendations
```

## 12.2 Startup Type

直接复用：

```text
COLD
WARM
HOT
```

不要将 requested type 与 observed type 混在一个标签。

## 12.3 KPI

基于现有真实统计：

- Median total time；
- First frame；
- Fully drawn；
- P90/P95；
- MAD/stability。

TTID/TTFD 必须展示 evidence confidence：

```text
EXACT
ESTIMATED
INFERRED
UNAVAILABLE
```

## 12.4 Waterfall

数据来自：

```text
StartupMilestone
StartupPhase
PlatformLaunchMetrics
```

不能为了图形连续性凭空补 phase duration。

## 12.5 Baseline Profile / Compilation

页面必须展示：

```text
requested compilation mode
compiler filter before/after
verified
profile source
profile source declared
failure reason
```

商业化价值很高，应放在 Run Detail 或 Optimization panel。

## 12.6 Perfetto Correlation

若 `traceEvidence.rootCause` 存在：

```text
Scheduling slices
Binder slices
Main thread slices
Frame slices
Correlation error bound
Limitations
```

提供：

```text
Open Trace
```

## 12.7 验收

- 运行中 progress 正确；
- cold/warm/hot 不混淆；
- baseline comparison 不隐去 confidence；
- trace correlation error/limitation 可见；
- Speed Profile 的配置状态可复核。

---
