# 页面 06：Frame Profiler

![Frame Profiler 设计参考](images/06-frame-profiler.png)

## 11.1 当前基础

现有模型已经包含：

```text
intended/actual vsync
frame complete / present
expected duration
refresh rate
FrameTimeline VSync ID
Input / Animation / Layout / Draw / Sync / Command / Swap / GPU
platform jank
jank types
missed vsync count
cluster
```

这是最适合商业化图形化的模块之一。

## 11.2 目标布局

```text
Toolbar
Device | Process | Capture | Import | Source | Filter

KPI
Frames | Deadline Miss | P50 | P95 | Worst

Frame Timeline
||||||||||||██||||||||████||||||||||

┌───────────────────────────────────────┬──────────────────────────┐
│ Jank Clusters / Problem Frames        │ Selected Frame           │
│ Table                                 │ Duration / Budget         │
│                                       │ Stages                    │
│                                       │ Jank reason               │
│                                       │ Open Layout / Open Trace  │
└───────────────────────────────────────┴──────────────────────────┘
```

## 11.3 Frame Timeline

继续使用 Canvas/高效绘制，不要用成百上千 Composable Box 渲染 frame bar。

支持：

- zoom / range；
- hover；
- select；
- jank color；
- cluster marker；
- refresh-rate-aware threshold；
- selected frame auto scroll。

## 11.4 Selected Frame

必须显示证据来源：

```text
Source
Frame ID
FrameTimeline VSync ID
Duration
Expected budget
Budget source
Missed VSync
Platform Jank
Jank Types
Largest Stage
Stage breakdown
State labels
```

操作：

```text
Inspect Layout
Open in Trace Analyzer
Copy Evidence
```

## 11.5 验收

- Frame capture/import 继续工作；
- timeline 对 10k+ frame 不出现大量 recomposition；
- cluster 和 selected frame 关联正确；
- Open Layout / Open Trace 不改变证据含义；
- `ExpectedDurationSource.UNKNOWN` 时 UI 不显示伪预算。

---
