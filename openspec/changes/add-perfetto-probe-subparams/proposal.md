## Why

Trace Analyzer 的数据源 probe 卡片目前是纯布尔开关:勾/不勾。但 perfetto 官网 record 页的对应 probe 支持子参数——"Coarse CPU usage counter"、"CPU frequency and idle states"(以及 meminfo/vmstat)可设 **poll interval**,"Atrace userspace annotations" 可勾选**具体 atrace 类别**。当前这些值要么硬编码(`stat_period_ms:1000`、`cpufreq_period_ms:250`、`meminfo/vmstat:1000`),要么按模板整组预设(System Overview 固定 24 项 atrace),用户无法在 UI 上调整,与官网能力不对齐。

## What Changes

- **Poll interval 可调**:为 `CPU_USAGE`/`CPU_FREQUENCY`/`KERNEL_MEMINFO`/`VMSTAT` 四个 probe 增加 poll interval 参数,UI 上 probe 卡片下显示 interval 输入框(勾选该 probe 时可见),默认沿用当前值(`stat_period_ms:1000`、`cpufreq_period_ms:250`、`meminfo_period_ms:1000`、`vmstat_period_ms:1000`)。`PerfettoConfigTextBuilder.systemStats()` 改为从配置读 interval,替代硬编码。proto 约束 interval >10ms,UI 校验。
- **Atrace 类别可勾选**:为 `ATRACE` probe 增加类别多选,UI 上 ATRACE 卡片显示齿轮按钮,点开弹对话框(自定义 `Dialog`+`Surface`,非 inline),对话框内类别固定 3 列网格 + All/None/Default 快捷按钮 + 选中计数,初始值为该模板预设(System Overview 24 项 / APP_PERFORMANCE 7 项等)。用户可增减,选中集合**覆盖**模板预设传入 `ftrace_config.atrace_categories`。切模板时类别集跟随模板预设重置(与 probes 一起)。
- **`additionalCategories` 文本框重定位**:现有"Additional categories / events"文本框同时接受 atrace 类别(无 `/`)和 ftrace 事件(有 `/`)。新增 atrace 类别多选后,atrace 追加由多选覆盖,该文本框仅保留 ftrace 事件追加用途(避免两套 atrace 入口冲突)。
- 不改其他 probe;不引入通用"probe 子参数框架"(按需加两个专门字段,不过度抽象)。

## Capabilities

### New Capabilities
_(无)_

### Modified Capabilities
- `trace-config-generation`: poll interval 从硬编码改为可配置、atrace 类别从模板预设改为可勾选覆盖,生成的 sys_stats period 与 atrace_categories 反映用户选择。

## Impact

- `desktop-viewer/perfetto-viewer/perfetto-model/.../PerfettoCaptureConfig.kt`:`PerfettoCaptureConfig` 增加 `pollIntervals: Map<PerfettoProbe, Int>?` 与 `atraceCategories: Set<String>?` 字段;新增模板预设类别查询。
- `desktop-viewer/perfetto-viewer/perfetto-capture/.../PerfettoConfigTextBuilder.kt`:`systemStats()` 读 `pollIntervals` 替代硬编码;`atraceCategories` 读 `config.atraceCategories` 替代模板预设(模板预设仅作初始值)。
- `desktop-viewer/perfetto-viewer/perfetto-presentation/.../PerfettoCapturePage.kt`:`PerfettoProbeOption` 齿轮按钮 + 子参数对话框(poll interval 对话框、atrace 类别 3 列网格对话框);`onPollIntervalChange`/`onAtraceCategoriesChange` 回调;切换模板时重置 atrace 类别。
- strings.xml(中英)新增 atrace 类别标签与 interval 文案。
- 相关测试更新。
- 向后兼容:新字段带默认值(null → 沿用现有默认 interval 与模板预设)。
