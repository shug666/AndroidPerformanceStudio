## Why

对照官方 Default config.txtpb 发现:(1) System Overview 默认勾选 `PROCESS_MEMORY_POLL` 导致导出含 `proc_stats_poll_ms: 1000`,而官方 Default 的 `linux.process_stats` 只有 `scan_all_processes_on_start`,无轮询——应默认不勾以对齐。(2) `android.log` 数据源支持按 log buffer(Main/Crash/System 等)收窄抓取,但 UI 没有暴露这个选项,用户无法选择只抓特定 buffer;空 `android_log_config`(当前)= 全抓所有 buffer,与官方一致,但缺 UI 选项。

## What Changes

- **PROCESS_MEMORY_POLL 默认不勾**:`SYSTEM_OVERVIEW.defaultProbes()` 移除 `PerfettoProbe.PROCESS_MEMORY_POLL`。导出 System Overview 配置不再含 `proc_stats_poll_ms`(对齐官方)。用户仍可手动勾选该 probe 开启进程内存轮询。
- **ANDROID_LOG 加 log buffer 多选**:为 `ANDROID_LOG` probe 增加子参数——log buffer 多选对话框(类似 atrace 类别对话框)。`PerfettoAtraceCategory` 同款模式:新增 `PerfettoLogBuffer` 枚举(LID_DEFAULT/MAIN、LID_RADIO、LID_EVENTS、LID_SYSTEM、LID_CRASH、LID_STATS、LID_SECURITY、LID_KERNEL,8 项,对齐 `android_log_constants.proto` 的 `AndroidLogId`)。默认空选 = 全抓所有 buffer(对齐官方空 `android_log_config`);用户勾选 = 收窄为指定 buffer,生成 `log_ids: LID_xxx`。
- builder:`ANDROID_LOG` 勾选时,若配置了 log buffer 集合则输出 `android_log_config { log_ids: LID_xxx ... }`,否则输出空 `android.log`(全抓,现状)。
- UI:ANDROID_LOG probe 卡片加齿轮按钮(类似 atrace),点开弹对话框多选 buffer。

## Capabilities

### New Capabilities
_(无)_

### Modified Capabilities
- `trace-config-generation`: System Overview 默认不再含 proc_stats_poll_ms;ANDROID_LOG 支持按 log buffer 收窄输出 log_ids。

## Impact

- `perfetto-model/.../PerfettoCaptureConfig.kt`:新增 `PerfettoLogBuffer` 枚举;`PerfettoCaptureConfig` 增加 `logBuffers: Set<PerfettoLogBuffer>?` 字段;`SYSTEM_OVERVIEW.defaultProbes()` 移除 `PROCESS_MEMORY_POLL`。
- `perfetto-capture/.../PerfettoConfigTextBuilder.kt`:`ANDROID_LOG` 数据源按 `logBuffers` 输出 `android_log_config { log_ids }`。
- `perfetto-presentation/.../PerfettoCapturePage.kt`:ANDROID_LOG 卡片加 log buffer 多选对话框;`logBuffers` 状态 + 回调。
- strings.xml(中英)新增 log buffer 文案。
- 相关测试更新。
