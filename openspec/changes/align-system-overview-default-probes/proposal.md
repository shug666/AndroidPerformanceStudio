## Why

Trace Analyzer 的 "System Overview" 默认勾选与生成的 Perfetto text-proto 配置，与 Perfetto 官方 record 页 Default 预设（参考 `/home/ts/下载/config.txtpb` 与 ui.perfetto.dev）存在多处缺项与不一致。逐行对照后，主要缺口为：`android.log` 数据源默认缺失、`linux.sys_stats` 从不输出 `stat_counters`、atrace 类别只有官方 24 项中的 8 项、无目标包时 `atrace_apps` 为空导致 atrace 无法采集、`power/*` 与 psci 事件缺失、`disable_generic_events` 取值相反；此外 buffer 的 `fill_policy` 固定为 `RING_BUFFER` 无法选择 `DISCARD`，`proc_stats_poll_ms` 与 `PROCESS_STATS` 绑死不可单独勾选。本变更在补齐缺项的同时，把这两处行为做成可配置/可勾选。

## What Changes

**补齐缺项（对齐官方 Default）：**
- 在 `SYSTEM_OVERVIEW.defaultProbes()` 默认勾选中加入 `ANDROID_LOG`，使生成配置包含 `android.log` 数据源。
- 在 `PerfettoConfigTextBuilder.systemStats()` 中补充 `stat_counters: STAT_CPU_TIMES`（随 `CPU_USAGE`）与 `stat_counters: STAT_FORK_COUNT`（sys_stats 存在即输出）。
- 将 `SYSTEM_OVERVIEW` 的 atrace 类别对齐官方 Default 24 项：`aidl, am, binder_driver, camera, dalvik, disk, freq, gfx, hal, idle, input, memory, memreclaim, network, power, res, sched, ss, sync, thermal, view, webview, wm, workq`。
- 在 `SYSTEM_OVERVIEW` 且无目标包时使用 `atrace_apps: "*"`。
- 在 `CPU_FREQUENCY` 的 `ftraceEvents()` 中补充 `power/*`、`power/psci_domain_idle_enter`、`power/psci_domain_idle_exit`。
- `linux.ftrace` 的 `disable_generic_events` 改为 `true`（对齐 config.txtpb）；`symbolize_ksyms: true` 保留。
- 去掉生成配置中的 `flush_period_ms`（当前硬编码 5000）。Perfetto 官方 proto 明确指出自动刷新已足够、高频刷新影响性能，config.txtpb 亦未设置；且此项与捕获完成机制无关（完成延迟由 `duration_ms` 驱动）。

**新增可配置/可勾选项：**
- 新增 `PROCESS_MEMORY_POLL` probe 卡片（Memory 组）：勾选时在 `linux.process_stats` 中输出 `proc_stats_poll_ms: 1000`；`PROCESS_STATS` 本身只输出 `scan_all_processes_on_start` 与 `record_thread_names`。System Overview 默认勾选 `PROCESS_MEMORY_POLL` 以保留当前轮询行为，但用户可取消。
- 新增 `PerfettoBufferPolicy` 枚举（`RING_BUFFER` / `DISCARD`）作为捕获级可配置项：`PerfettoCaptureConfig`、`PerfettoCaptureDocument`、`PerfettoConfigComposer` 均支持；Configuration 面板新增 fill policy 选择控件。模板携带默认 fill policy：`SYSTEM_OVERVIEW` 默认 `DISCARD`（对齐 config.txtpb），其余模板默认 `RING_BUFFER`（保留现状）；切换模板时连同 probes 一起重置。

**明确保留不动（非目标）：**
- 保留 `KERNEL_MEMINFO` / `VMSTAT` 默认勾选（sys_stats 继续输出 `meminfo_period_ms` / `vmstat_period_ms`），对 Android 调优有用。
- `android.packages_list` 继续无条件添加。
- `record_thread_names: true` 继续随 `linux.process_stats` 输出。
- 不改双 buffer / `target_buffer` 结构、不改 `cpufreq_period_ms`（当前 250 刻意比官方 1000 频繁，采样粒度取舍非 bug）。
- 不改其他预设的默认勾选与 atrace 列表。

## Capabilities

### New Capabilities

_(无新增能力；buffer policy 与 process poll 复用既有 trace 配置生成能力，作为其新选项。)_

### Modified Capabilities

- `trace-config-generation`: System Overview 默认勾选集合、atrace 类别/应用、sys_stats stat_counters、ftrace 事件集合、`disable_generic_events`、buffer fill policy、process_stats 轮询开关的规格发生变化。

## Impact

- `desktop-viewer/perfetto-viewer/perfetto-model/.../PerfettoCaptureConfig.kt`：`SYSTEM_OVERVIEW.defaultProbes()` 加 `ANDROID_LOG` 与 `PROCESS_MEMORY_POLL`；新增 `PerfettoBufferPolicy` 枚举与 `defaultBufferPolicy()`；`PerfettoCaptureConfig` 增加 `bufferPolicy` 字段；新增 `PROCESS_MEMORY_POLL` probe。
- `desktop-viewer/perfetto-viewer/perfetto-capture/.../PerfettoConfigTextBuilder.kt`：`atraceCategories()`、`atraceApps`、`systemStats()`、`ftraceEvents(CPU_FREQUENCY)`、`ftrace()` 的 `disable_generic_events`、`processStats()` 的 poll 条件、`PROCESS_STATS_PROBES` 集合、buffer policy 透传、移除 `flushPeriodMillis = 5_000`（改传 null）。
- `desktop-viewer/platform-perfetto/.../PerfettoConfig.kt`：`PerfettoCaptureDocument` 增加 `bufferPolicy`；`PerfettoConfigComposer` 按 policy 输出 `fill_policy`。
- `desktop-viewer/perfetto-viewer/perfetto-presentation/.../PerfettoCapturePage.kt`：新增 fill policy 控件与状态、切换模板时重置；i18n 字符串。
- `PerfettoConfigTextBuilderTest.kt` 及 composer 相关测试：断言更新。
- 生成配置向后兼容（仅增加字段/选项），无 BREAKING；`PerfettoCaptureDocument`/`PerfettoCaptureConfig` 新字段带默认值，二进制兼容。
