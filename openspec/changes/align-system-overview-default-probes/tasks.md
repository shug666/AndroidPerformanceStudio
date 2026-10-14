## 1. 默认勾选层 (PerfettoCaptureConfig.kt)

- [x] 1.1 在 `SYSTEM_OVERVIEW.defaultProbes()` 加入 `PerfettoProbe.ANDROID_LOG` 与 `PerfettoProbe.PROCESS_MEMORY_POLL`
- [x] 1.2 确认其他预设默认勾选集合未受影响（仅改 SYSTEM_OVERVIEW 分支；MEMORY_PROFILE 按需保留轮询）

## 2. 新增 PROCESS_MEMORY_POLL probe (PerfettoCaptureConfig.kt)

- [x] 2.1 在 `PerfettoProbe` 中新增 `PROCESS_MEMORY_POLL(PerfettoProbeGroup.MEMORY, "Process memory polling", requiredDataSource = "linux.process_stats")`
- [x] 2.2 在 strings.xml（values / values-zh）补充对应 i18n 显示名（如已有自动生成机制则确认）

## 3. atrace 类别与应用 (PerfettoConfigTextBuilder.kt)

- [x] 3.1 将 `atraceCategories()` 的 `SYSTEM_OVERVIEW` 分支替换为官方 24 项
- [x] 3.2 在 `atraceApps` 的 `buildSet` 中，无 `targetPackage`、`ATRACE in probes` 且模板为 `SYSTEM_OVERVIEW` 时加 `"*"`
- [x] 3.3 确认有 `targetPackage` 时用具体包、不输出 `"*"`

## 4. sys_stats stat_counters (PerfettoConfigTextBuilder.kt)

- [x] 4.1 `systemStats()` 中 `CPU_USAGE in probes` 时输出 `stat_counters: STAT_CPU_TIMES`
- [x] 4.2 `systemStats()` 中 sys_stats 数据源存在时始终输出 `stat_counters: STAT_FORK_COUNT`

## 5. CPU_FREQUENCY ftrace 事件 (PerfettoConfigTextBuilder.kt)

- [x] 5.1 `ftraceEvents(CPU_FREQUENCY)` 追加 `power/*`、`power/psci_domain_idle_enter`、`power/psci_domain_idle_exit`

## 6. disable_generic_events + symbolize_ksyms (PerfettoConfigTextBuilder.kt)

- [x] 6.1 把 `symbolize_ksyms: true` 从 advanced 分支移出、改为对所有生成 ftrace 的配置无条件输出（远古字段，版本安全）
- [x] 6.2 `disable_generic_events: true` 从 advanced 分支移出，改为**按 SDK 门控**：仅当 `androidSdk >= 33`(T) 时输出；pre-T/未知 SDK 不输出（避免严格 `--txt` 硬失败）
- [x] 6.3 让 `PerfettoConfigTextBuilder.build()` 接收 `androidSdk`（加到 `PerfettoCaptureConfig` 字段或调用处传参；调用处从 `deviceCapabilities.androidSdk` 取）
- [x] 6.4 移除旧的 `if (advanced)` 包裹两行的逻辑（advanced 分支不再独占这两行）

## 6b. 去掉 flush_period_ms (PerfettoConfigTextBuilder.kt)

- [x] 6b.1 `PerfettoConfigTextBuilder.build()` 中 `flushPeriodMillis = 5_000` 改为 `null`（或不传），使生成配置不再输出 `flush_period_ms`

## 7. process_stats 轮询解耦 (PerfettoConfigTextBuilder.kt)

- [x] 7.1 `PROCESS_STATS_PROBES` 集合加入 `PROCESS_MEMORY_POLL`
- [x] 7.2 `processStats()` 的 `pollMemory` 改为 `PerfettoProbe.PROCESS_MEMORY_POLL in probes`；`PROCESS_STATS` 仅输出 scan + record_thread_names
- [x] 7.3 确认调用点 `processStats(PerfettoProbe.PROCESS_STATS in probes)` 改为传 pollMemory 新条件

## 8. buffer fill_policy 可配置 (model / platform / builder)

- [x] 8.1 在 `PerfettoCaptureConfig.kt` 新增 `enum PerfettoBufferPolicy { RING_BUFFER, DISCARD }`
- [x] 8.2 `PerfettoCaptureConfig` 增加 `bufferPolicy: PerfettoBufferPolicy = PerfettoBufferPolicy.RING_BUFFER`（所有模板默认 RING_BUFFER，不引入 `defaultBufferPolicy()`）
- [x] 8.3 `PerfettoConfigTextBuilder.build()` 将 `config.bufferPolicy` 透传到 `PerfettoCaptureDocument`
- [x] 8.4 `platform-perfetto/PerfettoConfig.kt`：`PerfettoCaptureDocument` 增加 `bufferPolicy: PerfettoBufferPolicy = RING_BUFFER`
- [x] 8.5 `PerfettoConfigComposer.compose()` 按 policy 输出 `fill_policy: RING_BUFFER` / `fill_policy: DISCARD`（注意 platform-perfetto 不依赖 perfetto-model，policy 类型需在 platform 层定义或以枚举/字符串传递）

## 9. UI (PerfettoCapturePage.kt + strings)

- [x] 9.1 新增 `bufferPolicy` 状态，初始 `PerfettoBufferPolicy.RING_BUFFER`
- [x] 9.2 切换模板时 `bufferPolicy` **不变**（不随模板重置），仅 probes 重置
- [x] 9.3 Configuration 面板新增 fill policy 选择控件（下拉或单选）
- [x] 9.4 `createCaptureConfig` 传入 `bufferPolicy`
- [x] 9.5 strings.xml（values / values-zh）补充 fill policy 相关文案（如 RING_BUFFER / DISCARD 标签）

## 10. 测试 (PerfettoConfigTextBuilderTest.kt + composer)

- [x] 10.1 System Overview 默认配置含 `name: "android.log"`
- [x] 10.2 System Overview 默认配置 `sys_stats_config` 含 `STAT_CPU_TIMES` 与 `STAT_FORK_COUNT`
- [x] 10.3 System Overview atrace_categories 含全部 24 项
- [x] 10.4 无目标包时 System Overview `atrace_apps` 含 `"*"`；有包时不含 `"*"`
- [x] 10.5 `CPU_FREQUENCY` 开启时含 `power/psci_domain_idle_enter`、`power/psci_domain_idle_exit`、`power/*`
- [x] 10.6 `linux.ftrace` 的 `ftrace_config` 在 `androidSdk>=33` 时含 `disable_generic_events: true`；pre-T/未知 SDK 时**不含**该字段
- [x] 10.6a `linux.ftrace` 的 `ftrace_config` 无条件含 `symbolize_ksyms: true`（所有 SDK）
- [x] 10.6c System Overview 配置（T+ 设备）同时含 `disable_generic_events: true` 与 `ftrace_events: "power/*"`（确认 D5+D6 组合，对齐官方 Default）
- [x] 10.6b 非 custom 模板生成配置不含 `flush_period_ms`
- [x] 10.7 `PROCESS_MEMORY_POLL` 开启时含 `proc_stats_poll_ms: 1000`；仅 `PROCESS_STATS` 时不含
- [x] 10.8 所有模板默认 fill_policy 为 RING_BUFFER
- [x] 10.9 `PerfettoCaptureDocument` 未指定 policy 时 composer 输出 RING_BUFFER
- [x] 10.10 回归：APP_PERFORMANCE / GFX_PIPELINE / INPUT_LATENCY / MEMORY_PROFILE 的 atrace 类别与默认勾选不变

## 11. 验证

- [x] 11.1 运行 `PerfettoConfigTextBuilderTest` 与 composer 测试全绿
- [x] 11.2 人工对照：System Overview 默认生成配置与 `/home/ts/下载/config.txtpb` 逐项核对新增/对齐项一致
- [ ] 11.3 真机确认 `perfetto --query` 输出中 `android.log` 与 `linux.process_stats` 的注册名，排除 `unsupportedReason` 静默删除新增默认项（见 design Risks）
- [ ] 11.4 真机对比改动前后 System Overview 默认 trace 体积/事件数，确认 atrace 24 项 + power/* 引入的额外 ftrace 事件（含 memory/LMK/clock/gpu 泄露，见 design D3/D5）对开销影响可接受
