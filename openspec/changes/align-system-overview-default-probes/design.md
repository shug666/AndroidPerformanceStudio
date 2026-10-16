## Context

Trace Analyzer 的 System Overview 由两层驱动：
1. **勾选层** — `PerfettoCaptureConfig.kt` 的 `SYSTEM_OVERVIEW.defaultProbes()` 决定 UI 默认勾选哪些 probe 卡片；模板还决定 buffer 行为（本次新增）。
2. **生成层** — `PerfettoConfigTextBuilder.kt` 把选中 probes + 捕获设置翻译成 Perfetto text-proto，由 `platform-perfetto` 的 `PerfettoConfigComposer` 序列化（buffer/fill_policy/duration/flush）。

UI 上：左侧模板列、中间 DATA SOURCES（probe 卡片，每卡一个 checkbox，无子选项）、右侧 Configuration（target package / duration / buffer MB / additional categories）。对照 `/home/ts/下载/config.txtpb` 与 ui.perfetto.dev 后确认 6 项缺项 + 2 项需可配置（fill_policy、proc_stats_poll_ms）。

## Goals / Non-Goals

**Goals:**
- 补齐 6 项缺项（android.log、stat_counters、atrace 24 项、atrace_apps "*"、power/* + psci、disable_generic_events=true）。
- `proc_stats_poll_ms` 成为独立可勾选 probe（`PROCESS_MEMORY_POLL`），与 `PROCESS_STATS` 解耦。
- buffer `fill_policy` 可在 RING_BUFFER / DISCARD 间切换；模板携带默认值，System Overview 默认 DISCARD。

**Non-Goals:**
- 不改双 buffer / `target_buffer` 结构（config.txtpb 用双 buffer，本次保持单 buffer）。
- 不改 `flush_period_ms`（保持 5000）、不改 `cpufreq_period_ms`（保持 250）、不改 buffer size 默认值。
- 不改其他预设（APP_PERFORMANCE / GFX_PIPELINE / INPUT_LATENCY / MEMORY_PROFILE）的默认勾选或 atrace 列表；其 fill_policy 默认保持 RING_BUFFER（现状）。
- 不改 `android.packages_list`（继续无条件加）、`record_thread_names`（继续随 process_stats 输出）、`KERNEL_MEMINFO`/`VMSTAT`（继续默认勾选）。
- 不引入 probe 子选项 UI；新选项一律以独立 probe 卡片形式存在。

## Decisions

### D1. `ANDROID_LOG` 与 `PROCESS_MEMORY_POLL` 走默认勾选
`ANDROID_LOG` probe 已存在，加入 `SYSTEM_OVERVIEW.defaultProbes()` 即可生成 `android.log`，无需改生成层。`PROCESS_MEMORY_POLL` 为新增 probe，默认勾选以保留当前 System Overview 的 process 轮询行为，但用户可取消（与用户保留 meminfo/vmstat 的实用主义立场一致）。

### D2. `stat_counters` 条件输出
`STAT_CPU_TIMES` 当 `CPU_USAGE in probes`；`STAT_FORK_COUNT` 当 `linux.sys_stats` 数据源存在（即任一 `SYS_STATS_PROBES` 开启）时始终输出。不新增独立 fork probe（避免暴露内部细节）。

### D3. atrace 24 项只作用于 SYSTEM_OVERVIEW
仅替换 `atraceCategories()` 的 `SYSTEM_OVERVIEW` 分支为官方 24 项；其余模板分支不动。

**第二条语义泄露通道（已查实，比 power/* 更广）**：atrace 类别经 traced_probes `predefined_events_`（源 `predefined_tracepoints.cc`，仅当 `atrace_userspace_only` 默认 false 时生效）会额外启用一批 ftrace 事件，与显式 `ftrace_events` 取并集。量化 System Overview 经此通道隐式采集的**归属未勾选 probe** 的 ftrace 事件共 12 个：
- `memory` atrace 类别 → `HIGH_FREQUENCY_MEMORY` probe 的 6 事件（`kmem/rss_stat`、`ion/ion_stat`、`mm_event/mm_event_record`、`kmem/ion_heap_grow/shrink`、`dmabuf_heap/dma_heap_stat`）
- `memreclaim` atrace 类别 → `LOW_MEMORY_KILLER` probe 的 2 事件（`lowmemorykiller/lowmemory_kill`、`oom/oom_score_adj_update`）
- `freq` atrace 类别 → `BOARD_VOLTAGES` 的 3 clock 事件 + `GPU_FREQUENCY` 的 `power/gpu_frequency`（与 D5 的 `power/*` 通道重叠）
- 另 18 个无 probe 归属的事件（`binder/*`、`block/block_bio_*`、`f2fs_iostat`、`sched_cpu_hotplug` 等）也被拉入。

即：atrace 24 项让 System Overview 隐式启用了 memory/LMK/clock/gpu 频率 ftrace 事件，绕过对应 probe 勾选。**这是官方 Default 的固有行为**（config.txtpb 同样含 `memory`/`memreclaim`/`freq` 等 24 项），与 power/* 泄露同源、同性质。

**注意：这是本次相对现状的新增泄露，而非既有**。当前 System Overview 的 8 项 atrace（`am/binder_driver/dalvik/database/gfx/view/wm/power`）中只有 `binder_driver`/`gfx` 触发预定义 ftrace 事件，**不含 memory/LMK/clock/gpu 频率泄露**。新增到 24 项（加入 `freq/idle/memory/memreclaim/disk/sched` 等）将引入这 12 个事件的新泄露。属对齐官方 Default 的有意取舍，但相对现状是行为变化（采集面变广、开销增加），需在实现验收时确认对默认 trace 体积的影响可接受。结论与 D5 一致：跟随官方 Default，记录为已知张力；probe 严格控事件需独立重构（含对 atrace 类别的裁剪）。

### D4. `atrace_apps: "*"` 仅在无目标包
`atraceApps` 的 `buildSet`：有 `targetPackage` 用具体包；无包且 `ATRACE in probes` 且模板为 `SYSTEM_OVERVIEW` 时加 `"*"`。`"*"` 会拉起所有应用 atrace，开销略增但即官方 Default 行为。

### D5. `power/*` + psci 并入 CPU_FREQUENCY
`ftraceEvents(CPU_FREQUENCY)` 追加 `power/*`、`power/psci_domain_idle_enter`、`power/psci_domain_idle_exit`。`power/*` 与已列显式事件经 `distinct()` 去重；测试断言 psci 事件存在即可。

**traced_probes 通配机制（权威，源自 `ftrace_config_muxer.cc` GetFtraceEvents）**：`power/*` 在设备运行时由 `ReadEventsInGroupFromFs(tracefs, "power")` 读取内核 `events/power/` 目录下实际存在的事件并全部启用；设备没有的事件不会被启用、也不会报错。即通配是**优雅降级**而非硬失败，旧设备/缺事件设备安全。

**语义泄露注意**：`power/*` 通配在 traced_probes 会展开为设备上所有 `power/` 事件，含 `power/gpu_frequency`、`power/clock_enable/disable/set_rate` 等——这些本属于未勾选的 `GPU_FREQUENCY` / `BOARD_VOLTAGES` probe。即用户没勾 GPU 频率，却因 `CPU_FREQUENCY` 的通配采到了。量化：当前 SO 已采 4 个 power 事件，加 `power/*` 后多采最多 9 个（含 psci 2、gpu 2、clock 3、cpu_freq_switch 2），其中 5 个归属未勾选 probe。这是官方 Default 的固有行为（config.txtpb 同样用 `power/*`），与我们的 probe 抽象有张力，但**不引入超出官方 Default 的新行为**。

**最终选择：跟随官方 Default 用 `power/*`**，而非拆成显式枚举。理由：(1) 通配优雅降级，显式枚举反而会在缺事件设备上报"事件不存在"警告；(2) atrace_categories 也会经 `predefined_events_` 额外拉入 ftrace 事件，probe 勾选本就非"严格控事件"的单一来源，`power/*` 的泄露不破坏既有契约边界；(3) 与官方 Default 一致便于复现。若后续要让 probe 严格控事件，需把通配拆成显式枚举并同步改 `GPU_FREQUENCY`/`BOARD_VOLTAGES` 等去重——属独立重构，不在本次范围。

### D6. `disable_generic_events` + `symbolize_ksyms` 无条件输出（`disable_generic_events` 按 SDK 门控）
**修正**：当前 `ftrace()` 把 `symbolize_ksyms` 与 `disable_generic_events` 都包在 `if (advanced)`（`advanced = ADVANCED_FTRACE in probes`）内。System Overview 默认**不勾 ADVANCED_FTRACE**，故当前默认配置**根本不输出这两行**。而官方 config.txtpb 是**无条件** `symbolize_ksyms: true` + `disable_generic_events: true`。因此 D6 不是"改 advanced 分支取值"，而是**把这两行从 advanced 分支移出、对所有生成 ftrace 的配置生效**。

**`symbolize_ksyms: true` 无条件输出**——field 13 远古字段、无版本门槛，所有版本认识，安全。

**`disable_generic_events` 按 Android SDK 门控（已定）**：`disable_generic_events` 是 **Android T (13)** 引入字段（field 16）。捕获用严格 `--txt`（`PerfettoCaptureSession.kt:177`），经 `text_to_proto.cc` `FindFieldByName`+`AddError`(设 `parsed_successfully_=false`)+顶层 `if (!reporter.success()) return ErrStatus` 确认：**严格模式遇设备 perfetto 不认识的字段名会硬失败**。支持矩阵含 API 29（pre-T），故**不能无条件输出**。

**决策（已定）**：`disable_generic_events: true` **仅当 `androidSdk >= 33`（T）时输出**；pre-T 设备不输出此字段、沿用默认（允许 generic events），不硬失败。`symbolize_ksyms: true` 无条件输出。实现需让 `PerfettoConfigTextBuilder.build()` 接收 `androidSdk`（当前不接收，改签名/传参：`PerfettoCaptureConfig` 已可带 `androidSdk` 经由 `PerfettoDeviceCapabilities`，但 builder 当前只拿 `config`——需在调用处把 `deviceCapabilities.androidSdk` 传入，或加到 `PerfettoCaptureConfig`）。

**与 `power/*` 通配的交互（权威，已查实，关键安全确认）**：D5 加 `power/*` 通配 + D6 设 `disable_generic_events: true`（T+ 设备）的组合，最初担心"通配拉入的设备特定事件因无 proto 翻译变 generic event，被 disable_generic_events 丢弃，使 psci 等新增事件失效"。经 `ftrace_config_muxer.cc:595-613` 与 `protos/perfetto/trace/ftrace/power.proto` 源码确认，此担忧**不成立**：

1. traced_probes 对每个 ftrace 事件先查 `ProtoTranslationTable`：有编译时 proto 翻译则正常启用；无则 `CreateGenericEvent` 归为 generic。
2. `disable_generic_events: true` 仅丢弃 **无 proto 翻译的 generic 事件**，有 proto 的不受影响。
3. `power.proto` 在编译时**已定义 psci 两个事件**（`PsciDomainIdleEnterFtraceEvent`、`PsciDomainIdleExitFtraceEvent`）——故显式列的 `power/psci_domain_idle_enter/exit` 有 proto、**不会被丢弃**，D5 的 psci 增项在 D6 下安全。
4. `power/*` 通配拉入的 `cpu_frequency/cpu_idle/suspend_resume/gpu_frequency/gpu_work_period/clock_*/wakeup_source_*/device_pm_callback_*/gpu_power_state/psci_*` 全部在 `power.proto` 有 proto，保留；仅 `cpu_frequency_switch_start/end` 等少数设备特定事件无 proto 被丢弃。
5. 被丢弃的 generic 事件进 `failed_ftrace_events` 诊断列表，**不导致 capture 失败**（config 返回非零 FtraceConfigId 才算失败，单事件丢弃不会触发）。

结论：**D5 + D6 在 T+ 设备上与官方 Default 完全一致、事件层面安全**；pre-T 设备不输出 `disable_generic_events`（沿用默认允许 generic，`power/*` 会拉入少量无 proto 杂项 power 事件作 generic event 保留——可接受，与 pre-T 官方行为一致）。



### D6b. 去掉 flush_period_ms
当前 `PerfettoConfigTextBuilder.kt:49` 硬编码 `flushPeriodMillis = 5_000`，使每个非 custom trace 带 `flush_period_ms: 5000`。Perfetto 官方 TraceConfig proto 原文明确：自动刷新已足够、高频刷新显著影响性能；config.txtpb 亦未设置。已确认与捕获完成机制无关（`automaticCompletionDelayMillis` 走 `duration_ms`）。决策：改为传 `null`，生成配置不再输出 `flush_period_ms`。
- 备选：做成可配置——否，官方劝阻手动设置，不应给用户一个被劝阻的选项。

### D7. `PROCESS_MEMORY_POLL` 新 probe 解耦轮询
新增 `PerfettoProbe.PROCESS_MEMORY_POLL`（Memory 组，`requiredDataSource = "linux.process_stats"`）。`processStats(pollMemory)` 的 `pollMemory` 改为 `PerfettoProbe.PROCESS_MEMORY_POLL in probes`；`PROCESS_STATS` 仅触发 `scan_all_processes_on_start` + `record_thread_names`。`PROCESS_STATS_PROBES` 集合加入 `PROCESS_MEMORY_POLL`，保证仅勾选轮询时数据源仍生成。System Overview 默认勾选 `PROCESS_MEMORY_POLL`（保留现状），其余预设按需（MEMORY_PROFILE 保留轮询，其他预设不默认加）。

### D8. buffer fill_policy 可配置（默认仍 RING_BUFFER）
新增 `enum PerfettoBufferPolicy { RING_BUFFER, DISCARD }`。`PerfettoCaptureConfig.bufferPolicy`（默认 RING_BUFFER）→ `PerfettoCaptureDocument.bufferPolicy` → `PerfettoConfigComposer` 按 policy 输出 `fill_policy`。UI 新增 fill policy 选择控件。

**决策（已定）**：**所有模板默认 RING_BUFFER**（含 System Overview），不改默认；DISCARD 作为可选选项供高级用户。不引入 `defaultBufferPolicy()`——所有模板默认即 RING_BUFFER，无需随模板重置 fill_policy（切换模板时 bufferPolicy 不变，仅 probes 重置）。理由：原 32MB + DISCARD + 10s 在高事件密度下可能截断后段；默认 RING_BUFFER 保最近数据不丢尾，**零回归**。

**默认时长与缓冲区（已调整）**：默认采集时长从 10s 改为 **30s**，默认缓冲区从 32MB 改为 **64MB**（`PerfettoCaptureConfig` 默认参数 + UI 初始 state 同步）。64MB 对齐官方 Default 的 buffer size，30s 更适合实际分析场景。用户需要"短时完整快照"时可手选 DISCARD。

**PROCESS_MEMORY_POLL 默认不勾（已调整，见 `align-log-and-process-poll`）**：System Overview 默认不再勾选 `PROCESS_MEMORY_POLL`，导出不含 `proc_stats_poll_ms`（对齐官方 Default 仅 `scan_all_processes_on_start`）。用户可手动勾选开启轮询。
- 备选：支持双 buffer + `target_buffer:1`——否，超出本次范围，模型改动大。

`PerfettoCaptureDocument`/`PerfettoCaptureConfig` 新字段带默认值 RING_BUFFER，二进制兼容；composer 默认 RING_BUFFER 保持既有调用方行为。


## Risks / Trade-offs

- [atrace_apps `"*"` 增加开销] → 仅 System Overview + 无目标包时生效，与官方一致；可切 App Performance 规避。
- [atrace 24 项含部分设备不支持类别] → Perfetto 对未知 atrace category 静默忽略，不报错。
- [`power/*` 通配与显式事件重复] → `distinct()` 去重；测试断言 psci 存在而非逐行比对。
- [`disable_generic_events: true` 可能丢弃 generic 事件] → 与官方 Default 一致；需 advanced ftrace 用户可开 `ADVANCED_FTRACE`。
- [System Overview 默认改 DISCARD 改变语义] → DISCARD 满了丢新数据保前段，适合短时完整快照；默认仍 RING_BUFFER，DISCARD 可选。默认时长/缓冲区已调整为 30s/64MB。
- [新 probe `PROCESS_MEMORY_POLL` 默认勾选] → 后调整为**默认不勾**（见 `align-log-and-process-poll`），对齐官方 Default 不含 proc_stats_poll_ms。
- [`PerfettoCaptureDocument` 公共 API 加字段] → 带默认值，二进制兼容；composer 默认 RING_BUFFER 保持既有调用方行为。
- [能力检测静默丢弃默认项] → `PerfettoDeviceCapabilities.unsupportedReason` + `PerfettoCapturePage` 的 `enabledProbes.filterTo` 会把设备 `perfetto --query` 未注册的数据源对应 probe **静默从配置删除**。`android.log`、`linux.process_stats` 在能力样本里未出现，若设备不注册则补的 android.log / process poll 会被吃掉且无提示。缓解：本次不引入额外提示机制（属既有行为），但实现后应人工在真实设备上确认 `android.log` 在 `--query` 输出中的注册名，确认 `ANDROID_LOG` 不被误判 unavailable。
- [`stat_counters` 是能力检测盲区] → 能力检测只看数据源名不看字段，无法验证设备 `/proc/stat` 支持 `STAT_CPU_TIMES`/`STAT_FORK_COUNT`。这俩是 Linux 标准 `/proc/stat` 字段，所有 Android 均有，风险低；盲区记录在案。
- [`power/*` 通配绕过 probe 勾选] → 见 D5。会让 System Overview 隐式采到未勾选 probe 的 power 事件（最多多采 9 个）。但 traced_probes 对通配是优雅降级（按设备 tracefs 实际事件展开，缺则不采、不报错），无硬失败风险。跟随官方 Default，记录为已知张力；严格控事件需独立重构。
- [atrace 24 项第二条泄露通道] → 见 D3。atrace `memory`/`memreclaim`/`freq` 类别经 `predefined_events_` 隐式启用 12 个归属未勾选 probe 的 ftrace 事件（memory/LMK/clock/gpu 频率），比 `power/*` 通道更广。与官方 Default 同源同性质，跟随官方、记录为已知张力；与 D5 同属"probe 非严格控事件"的既有边界，非本次引入。

## Open Questions

- `PROCESS_MEMORY_POLL` 在 MEMORY_PROFILE 之外的其他非 System Overview 预设是否默认勾选？当前设想：仅 SYSTEM_OVERVIEW 与 MEMORY_PROFILE 默认勾选，其余不默认加（待实现时确认）。
- fill policy 控件 UI 形态（下拉 / 单选按钮）与中文文案——实现时定，不阻塞设计。
- `android.log` 在设备 `perfetto --query` 输出中的实际注册名需在真机确认，以排除 `unsupportedReason` 误杀（见 Risks）。
- D6 实现：`androidSdk` 传给 `PerfettoConfigTextBuilder.build()` 的具体方式（加到 `PerfettoCaptureConfig` 字段 vs 调用处额外传参）——实现时定。


