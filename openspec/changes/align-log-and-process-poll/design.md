## Context

对照官方 Default config.txtpb 逐行核对发现两处偏差:(1) 我们 System Overview 默认含 `proc_stats_poll_ms`(因 PROCESS_MEMORY_POLL 默认勾选),官方 `linux.process_stats` 仅 `scan_all_processes_on_start`。(2) `android.log` 的 `android_log_config` 支持 `log_ids` 按 buffer 收窄(8 个 AndroidLogId),我们 UI 未暴露,空 config 全抓(与官方一致但无选项)。

`android_log_config` proto:`log_ids` 空 = 抓所有 buffer;非空 = 只抓指定。官方 Default 空 `android_log_config {}` = 全抓。`AndroidLogId`:LID_DEFAULT(=MAIN)、LID_RADIO、LID_EVENTS、LID_SYSTEM、LID_CRASH、LID_STATS、LID_SECURITY、LID_KERNEL(8 项)。

## Goals / Non-Goals

**Goals:**
- System Overview 默认不含 `proc_stats_poll_ms`(对齐官方),PROCESS_MEMORY_POLL 默认不勾。
- ANDROID_LOG probe 加 log buffer 多选对话框,默认空=全抓,选=收窄,输出 `log_ids`。
- 复用 atrace 类别对话框模式(枚举 + 对话框 + 齿轮按钮)。

**Non-Goals:**
- 不改 min_prio / filter_tags / preserve_log_buffer(本轮只做 log_ids buffer 选择)。
- 不改其他模板的 PROCESS_MEMORY_POLL(MEMORY_PROFILE 保留,它本就需要轮询)。
- 不改 atrace/poll interval 对话框(已有)。

## Decisions

### D1. PROCESS_MEMORY_POLL 默认不勾(仅 System Overview)
`SYSTEM_OVERVIEW.defaultProbes()` 移除 `PROCESS_MEMORY_POLL`。导出 System Overview 不再含 `proc_stats_poll_ms`,对齐官方。用户可手动勾选该 probe 开启。MEMORY_PROFILE 保留 `PROCESS_MEMORY_POLL`(内存分析需轮询)。
- 回顾:之前 D7 为"保留现状轮询"默认勾了它;现对照官方决定对齐,改默认不勾。

### D2. log buffer 语义:空=全抓,选=收窄
与 atrace 类别(覆盖预设)不同,log buffer 是"收窄"语义:`log_ids` 空 = 全抓所有 buffer(proto 默认);非空 = 只抓选中。默认空选对齐官方空 `android_log_config`。用户勾选 = 收窄。无需"预设"概念。

### D3. PerfettoLogBuffer 枚举(对齐 AndroidLogId)
新增 `enum PerfettoLogBuffer(id, displayName)` 8 项,对齐 `android_log_constants.proto`:
- LID_DEFAULT → "Main"(LID_DEFAULT 在 NDK 即 MAIN buffer)
- LID_RADIO → "Radio"
- LID_EVENTS → "Events"
- LID_SYSTEM → "System"
- LID_CRASH → "Crash"
- LID_STATS → "Stats"
- LID_SECURITY → "Security"
- LID_KERNEL → "Kernel"
`id` 用 proto 枚举名(如 `LID_CRASH`),builder 输出 `log_ids: LID_CRASH`。

### D4. builder:ANDROID_LOG 按 logBuffers 输出
`addIf(ANDROID_LOG, "android.log")` 改为:若 `config.logBuffers` 非空,输出 `android.log` + `android_log_config { log_ids: <id> ... }`;空则输出空 `android.log`(现状,全抓)。复用现有 direct-source 模式或单独函数。

### D5. UI:ANDROID_LOG 卡片加齿轮 + 对话框(复用 atrace 对话框模式)
ANDROID_LOG probe 卡片加齿轮按钮(已有子参数机制:probe in DEFAULT_POLL_INTERVALS 或 ==ATRACE 才显示齿轮)。扩展子参数判定:`probe == ANDROID_LOG` 也显示齿轮,弹 log buffer 多选对话框(3 列网格 + All/None + OK/Cancel,复用 `AtraceCategoryDialog` 布局)。

## Risks / Trade-offs
- [PROCESS_MEMORY_POLL 默认不勾改变现状] → 回归风险低;用户可手动勾;MEMORY_PROFILE 不受影响。
- [log buffer 选了空集] → 空集=全抓(语义清晰);builder 空集不输出 log_ids(全抓)。
- [LID_DEFAULT 与 MAIN 命名] → proto 是 LID_DEFAULT,NDK 即 MAIN buffer;display 名 "Main" 对齐 logcat 惯例;输出 `log_ids: LID_DEFAULT`。
- [子参数判定扩展] → hasSubParams 加 `probe == ANDROID_LOG`,与现有 poll/atrace 机制一致。

## Open Questions
- log buffer 对话框是否需 All/None 快捷按钮(类似 atrace)——倾向加(None=全抓默认,All=显式全选)。
