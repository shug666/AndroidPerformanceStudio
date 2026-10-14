# 6. 页面 01：Home Dashboard

![Home Dashboard 设计参考](images/01-home-dashboard.png)

## 6.1 页面目标

从“功能入口卡片页”升级为：

> 当前性能工作、最近证据、异常、回归和快捷操作的总览入口。

不是把所有 profiler 数据实时聚合进内存，而是做**只读 Projection**。

## 6.2 页面区域

```text
Header
├── Workspace title
├── environment summary
└── Quick Capture / Import

KPI Row
├── Connected Devices
├── Recent Sessions
├── Open Findings
└── Regressions

Quick Actions
├── Start CPU Capture
├── Capture Layout
├── Open Trace
├── Analyze HPROF
└── Run Startup Test

Modules
└── feature cards

Recent Workspaces / Sessions
└── unified summary table

Right Rail
├── Devices
├── Recent Reports
└── Evidence-backed Insights
```

## 6.3 数据模型

新增**只读 summary model**：

```kotlin
enum class StudioFeature {
    LAYOUT, CPU, TRACE, MEMORY, FRAME, STARTUP,
    BATTERY, NETWORK, GPU, BENCHMARK
}

data class StudioRecentItem(
    val id: String,
    val feature: StudioFeature,
    val title: String,
    val subtitle: String?,
    val capturedAt: Instant?,
    val deviceLabel: String?,
    val packageName: String?,
    val status: StudioRecentStatus,
    val artifactId: String?,
    val openAction: StudioOpenAction,
)

data class StudioFindingSummary(
    val feature: StudioFeature,
    val severity: StudioSeverity,
    val title: String,
    val evidenceLabel: String,
    val openAction: StudioOpenAction,
)
```

不要复制完整 `FrameSample` / `HttpCall` / `BenchmarkMetric` 到 Dashboard。

## 6.4 WorkspaceOverviewRepository

建议 Adapter 模式：

```text
Layout recent adapter ──┐
CPU recent adapter ─────┤
Perfetto recent adapter ┤
Memory recent adapter ──┤
Frame recent adapter ───┤
Startup recent adapter ─┼─> WorkspaceOverviewRepository
Battery recent adapter ─┤
Network recent adapter ─┤
GPU artifact adapter ───┤
Benchmark adapter ──────┘
```

接口：

```kotlin
interface StudioOverviewSource {
    suspend fun recentItems(limit: Int): List<StudioRecentItem>
    suspend fun findings(limit: Int): List<StudioFindingSummary>
}
```

## 6.5 当前代码改造

主要影响：

```text
desktop-app/AppHomePage.kt
DesktopAppMainPage.kt
AppDestination.kt
```

推荐新增：

```text
desktop-app/dashboard/*
```

## 6.6 功能验收

- 无设备时首页可以正常打开；
- 首页不得初始化所有 profiler controller；
- 最近会话读取失败不阻塞首页；
- 点击 Recent Item 能跳转正确 feature；
- KPI 必须来自真实 store/projection；
- 没有数据时显示 `—` / EmptyState，不显示模拟数字；
- 首页首屏 1440x900 下无整体纵向滚动或仅主内容区滚动。

---
