## 1. Model (PerfettoCaptureConfig.kt)

- [x] 1.1 `PerfettoCaptureConfig` 增加 `pollIntervals: Map<PerfettoProbe, Int>? = null`
- [x] 1.2 `PerfettoCaptureConfig` 增加 `atraceCategories: Set<String>? = null`
- [x] 1.3 新增默认 interval 常量(`CPU_USAGE=1000`/`CPU_FREQUENCY=250`/`KERNEL_MEMINFO=1000`/`VMSTAT=1000`)供 builder 回退
- [x] 1.4 新增 atrace 类别全集常量(对齐官网 `ATRACE_CATEGORIES` 37 项,含 title + isDefault);按模板预设查询(isDefault:true 子集作初始值)

## 2. Builder (PerfettoConfigTextBuilder.kt)

- [x] 2.1 `systemStats()` 改为按 `config.pollIntervals[probe] ?: DEFAULT` 取各 `*_period_ms`,替代硬编码
- [x] 2.2 atrace 类别来源改为 `config.atraceCategories ?: atraceCategories(config.template)`(覆盖语义)
- [x] 2.3 `extraAtraceCategories` 分支移除;`additionalCategories` 仅留 ftrace 事件(含 `/`)
- [x] 2.4 interval 值兜底 coerce 至 >10ms

## 3. UI (PerfettoCapturePage.kt + strings)

- [x] 3.1 新增 `pollIntervals` 状态(Map<Probe,Int>)与 `atraceCategories` 状态(Set<String>),初始沿用模板预设
- [x] 3.2 `PerfettoProbeOption` 扩展:勾选后按 probe 显示 poll interval 输入框(CPU_USAGE/CPU_FREQUENCY/KERNEL_MEMINFO/VMSTAT)
- [x] 3.3 `PerfettoProbeOption` 扩展:ATRACE 勾选后显示 atrace 类别 checkbox 列表
- [x] 3.4 interval 输入校验 >10ms
- [x] 3.5 切模板时 `atraceCategories` 重置为新模板预设;`pollIntervals` 重置为默认(或保留)
- [x] 3.6 `createCaptureConfig` 传入 `pollIntervals` 与 `atraceCategories`
- [x] 3.7 `additionalCategories` 文案/placeholder 更新为仅 ftrace 事件
- [x] 3.8 strings.xml(中英)新增 interval 文案、atrace 类别标签

## 4. 测试

- [x] 4.1 配置 poll interval → sys_stats 含对应 period 值
- [x] 4.2 未配置 interval → 回退默认值
- [x] 4.3 配置 atraceCategories → ftrace_config 含精确选中类别、无预设多余项
- [x] 4.4 未配置 atraceCategories → 回退模板预设
- [x] 4.5 additionalCategories 含无 `/` 项 → 不进 atrace_categories
- [x] 4.6 回归:其他模板/其他 probe 不受影响

## 5. 验证

- [x] 5.1 单元测试全绿
- [x] 5.2 真机/手动对照官网 UI:atrace 类别列表、interval 输入一致
