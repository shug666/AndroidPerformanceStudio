## 1. Model (PerfettoCaptureConfig.kt)

- [x] 1.1 新增 `enum PerfettoLogBuffer(val id: String, val displayName: String)` 8 项(LID_DEFAULT→Main, LID_RADIO→Radio, LID_EVENTS→Events, LID_SYSTEM→System, LID_CRASH→Crash, LID_STATS→Stats, LID_SECURITY→Security, LID_KERNEL→Kernel)
- [x] 1.2 `PerfettoCaptureConfig` 增加 `logBuffers: Set<PerfettoLogBuffer>? = null`
- [x] 1.3 `SYSTEM_OVERVIEW.defaultProbes()` 移除 `PerfettoProbe.PROCESS_MEMORY_POLL`

## 2. Builder (PerfettoConfigTextBuilder.kt)

- [x] 2.1 `ANDROID_LOG` 数据源:空/未配置 logBuffers → 空 `android.log`(现状,全抓)
- [x] 2.2 非空 logBuffers → `android.log` + `android_log_config { log_ids: LID_xxx ... }`
- [x] 2.3 确认 MEMORY_PROFILE 默认仍含 PROCESS_MEMORY_POLL(不回归)

## 3. UI (PerfettoCapturePage.kt + strings)

- [x] 3.1 新增 `logBuffers` 状态(Set<PerfettoLogBuffer>),初始空
- [x] 3.2 子参数判定扩展:`probe == PerfettoProbe.ANDROID_LOG` 也显示齿轮按钮
- [x] 3.3 新增 `LogBufferDialog`(复用 AtraceCategoryDialog 布局:3 列网格 + All/None + OK/Cancel)
- [x] 3.4 切模板时 `logBuffers` 重置为空(全抓)
- [x] 3.5 `createCaptureConfig` 传入 `logBuffers`
- [x] 3.6 strings.xml(中英)新增 log buffer 对话框文案 + buffer 显示名

## 4. 测试

- [x] 4.1 System Overview 默认配置不含 `proc_stats_poll_ms`
- [x] 4.2 手动勾 PROCESS_MEMORY_POLL → 含 `proc_stats_poll_ms: 1000`
- [x] 4.3 ANDROID_LOG 空 logBuffers → 空 android.log(全抓,无 log_ids)
- [x] 4.4 ANDROID_LOG 选 {CRASH,SYSTEM} → android_log_config 含 LID_CRASH + LID_SYSTEM
- [x] 4.5 回归:MEMORY_PROFILE 默认仍含 proc_stats_poll_ms

## 5. 验证

- [x] 5.1 单元测试全绿
- [x] 5.2 导出 System Overview 对照官方 config.txtpb:无 proc_stats_poll_ms,android.log 空(全抓)
