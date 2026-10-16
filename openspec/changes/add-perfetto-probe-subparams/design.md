## Context

Trace Analyzer 的 probe 卡片是布尔开关,无子参数。官网 record 页的 CPU/内存数据源可设 poll interval,atrace 可勾选具体类别。当前 interval 全硬编码、atrace 按模板整组预设。本变更让这两类子参数可在 UI 调整,放 probe 卡片下(对齐官网)。

关键约束:`PerfettoProbe` 是纯 `enum`,无实例状态承载参数;`onProbeChange(probe, Boolean)` 无参数通道。两类子参数形态不同(poll interval=数值,atrace 类别=集合)。atrace 内核态类别经 `predefined_events_` 会隐式拉额外 ftrace 事件(之前深挖的 D3 泄露通道)。

## Goals / Non-Goals

**Goals:**
- `CPU_USAGE`/`CPU_FREQUENCY`/`KERNEL_MEMINFO`/`VMSTAT` 的 poll interval 可在 probe 卡片下调整(默认沿用现值,>10ms 约束)。
- `ATRACE` 的类别可在卡片下勾选(初始=模板预设,可增减,覆盖预设传入配置)。
- 切模板时 atrace 类别跟随模板预设重置。
- `additionalCategories` 文本框仅保留 ftrace 事件追加,atrace 追加由多选承担。

**Non-Goals:**
- 不建通用"probe 子参数框架";仅加两个专门字段。
- 不改其他 probe 的子参数。
- 不改 `PerfettoProbe` 枚举为 sealed class(影响面过大)。
- 不动态从设备查询 atrace 类别(用固定全集,对齐官网 UI)。

## Decisions

### D1. 模型承载:PerfettoCaptureConfig 加专门字段(非通用框架)
`PerfettoCaptureConfig` 增加:
- `pollIntervals: Map<PerfettoProbe, Int>? = null` — null 时 builder 用默认值(向后兼容)。
- `atraceCategories: Set<String>? = null` — null 时用模板预设(向后兼容)。
- 备选:枚举声明参数 schema + `Map<Probe, Map<Key, Any>>` — 否,类型擦除、过度抽象,两个参数不值得。
- 备选:`PerfettoProbe` 改 sealed class 带 params — 否,大重构,影响所有 probe/测试/能力检测。

### D2. poll interval 归属天然 1:1
`linux.sys_stats` 的 4 个 `*_period_ms` 字段各属一个 probe(`stat_period_ms`→CPU_USAGE,`cpufreq_period_ms`→CPU_FREQUENCY,`meminfo_period_ms`→KERNEL_MEMINFO,`vmstat_period_ms`→VMSTAT),无归属歧义。UI 在各 probe 卡片下显示对应 interval 输入。`systemStats()` 按 `pollIntervals[probe]` 取值,缺省用默认。

### D3. atrace 类别用"覆盖"语义
`config.atraceCategories`(非 null)直接作为 `ftrace_config.atrace_categories` 的来源,**覆盖**模板预设;模板预设仅在 UI 初始化/切模板时作为多选的初始值。`additionalCategories` 不再参与 atrace(见 D4)。切模板时 `atraceCategories = 模板预设` 重置,与 `enabledProbes` 一起。

### D4. additionalCategories 收窄为 ftrace 事件追加
现有 `additionalCategories` 同时收 atrace 类别(无 `/`)和 ftrace 事件(有 `/`)。新增 atrace 多选后,atrace 追加由多选承担,文本框仅保留 ftrace 事件(有 `/`)。builder 的 `extraAtraceCategories` 分支移除,只留 `extraFtraceEvents`。

### D5. UI 子参数放对话框编辑(非 inline,经 UI 迭代修正)
原方案 D5 把子参数 inline 展开在 probe 卡片下。实现后 UI 迭代发现 inline 展开撑长数据源面板、atrace 37 类别 inline 排版难看,经三轮修正定为**对话框**方案:

1. probe 卡片只保留 checkbox + 齿轮按钮(`⚙`,仅有子参数且勾选时显示),不 inline 展开。
2. 点齿轮弹 `Dialog`(自定义 `Surface`,非 `AlertDialog`):
   - **poll interval 对话框**:标题=probe 名、数值输入(`>10ms` 校验提示)、OK/Cancel。
   - **atrace 对话框**:固定宽 520dp、内容区限高 360dp 可滚动;标题加粗 + 选中计数 `N/37`;All/None/Default 快捷按钮;类别**固定 3 列网格**(`chunked(3)` + 每项 `weight(1f)` 等宽 + 末行 `Spacer` 补齐),列间距 12dp、行间距 8dp、checkbox 20dp、checkbox↔文字 8dp、单行省略。
3. 对话框内编辑本地 state,OK 提交回写;Cancel 丢弃。默认关闭,数据源面板保持紧凑。

- 备选(否决):inline 展开——撑长面板、atrace 类别排版难看(单列/FlowRow 参差/固定列宽仍挤)。
- 备选(否决):FlowRow 自动换行——列宽不一参差;固定 3 列网格更整齐。
- `onProbeChange` 扩展为 `onPollIntervalChange(probe, Int)` + `onAtraceCategoriesChange(Set<String>)` 独立回调(Open Questions 已定:用独立回调而非 probeParams 结构)。

### D6. atrace 类别全集:固定,对齐官网(撤销动态方案)
原曾考虑动态查设备,但改为与官网 UI 完全一致:用固定全集。官网源码 `ui/src/plugins/dev.perfetto.RecordTraceV2/pages/android.ts` 的 `ATRACE_CATEGORIES` 定义 **37 项**固定列表,每项含 `title`(友好名)与 `isDefault`:
- 默认开 24 项(`isDefault:true`):`aidl am binder_driver camera dalvik disk freq gfx hal idle input memory memreclaim network power res sched ss sync thermal view webview wm workq`——正是我们 System Overview 现有预设,印证一致。
- 默认关 13 项:`adb audio binder_lock bionic database nnapi pm rro rs sm vibrator video`。

固定全集的理由:可预期、离线可配、与官网 UI 逐项对齐(含友好名)。设备不支持的类别(如某设备无 `workq`)由 perfetto 静默忽略,与官网同行为——官网也用固定全集不动态查设备。UI 可显示每项 `title`(如 am→"Activity Manager")而非裸 token。勾选语义(D3 覆盖)不变;模板预设即该模板默认勾的 `isDefault:true` 子集(System Overview=24,其他模板按现有预设)。

- 备选(否决):动态查 `atrace --list_categories`——精确但行为因设备而异、离线不可用、与官网不一致;官网本身不动态查。
- 内核态类别(`freq`/`memory`/`memreclaim`/`thermal` 等)经 `predefined_events_` 隐式拉 ftrace 事件——与官网同行为,不阻止勾选。

## Risks / Trade-offs

- [atrace 类别全集与官网不一致] → 实现时对照官网 UI 数一遍;遗漏类别用户可用 ftrace 事件追加口补(若某类别有对应 ftrace 事件)。
- [poll interval <10ms] → UI 校验 + builder 兜底 coerce 至 10。
- [atrace 类别覆盖预设后,切回模板需重置] → D3 切模板时重置类别=模板预设。
- [内核态类别隐式拉 ftrace 事件] → 与既有 D3 泄露同性质,非本次引入;提示用户。
- [`PerfettoProbeOption` 扩展增加布局测试负担] → render test 已覆盖:对话框关闭时类别不 inline 渲染、齿轮按钮存在、对话框打开时 37 类别全渲染 + OK/Cancel/All 按钮 + 截图。
- [interval 改 sys_stats 数据源级,4 probe 共用一源] → D2 字段天然分属,无冲突;勾多个 probe 产一个 sys_stats 块含多个 period 字段。
- [对话框内类别排版紧凑/丑] → 已迭代:对话框化 + 固定 3 列网格 + 调大间距(见 D5)。

## Open Questions

- atrace 类别 UI 是否显示官网 `title` 友好名(am→"Activity Manager")而非裸 token——已实现显示 `displayName`(`PerfettoAtraceCategory.displayName`,即官网 title 映射),已解决。
- `ATRACE_DIALOG_COLUMNS` 列数(当前 3)是否需按对话框宽度/类别名长度调优——当前 3 列 + 520dp 宽效果可接受,后续可调。
- **数据源名称中文化(已实现)**:`PerfettoProbeGroup.displayName` 与 `PerfettoProbe.displayName` 翻译为中文(如"内存""调度详情""CPU 频率与空闲状态")。保留英文术语不强行翻译:CPU、I/O、GPU、ART、JIT、GC、Atrace、ftrace、logcat、Mali、Perfetto SDK、Track events、Native、Java、LMK、Wi-Fi。probe 名称无既有 i18n 机制(枚举 `displayName` 直接用),故直接改枚举值;`render test` 用 `probe.displayName` 动态引用,无需改断言。
