## Context

Trace Analyzer 捕获 trace 后报 `captured Perfetto trace must not be empty`。真机诊断(Android 17 / user build / perfetto v54 / 设备 HA2E9HHH)确认:

- 当前命令:`cat config | perfetto --txt -c - -o /data/misc/perfetto-traces/aps-perfetto-trace.pftrace --background-wait`。
- `--background-wait` 让 perfetto fork 后台进程、打印 PID、前台立即返回(EXIT=0)。代码(`PerfettoCaptureSession.kt:184-189`)用正则 `^\s*(\d+)$` 从 stdout+stderr 提取 PID 存入 `activePerfettoPid`。
- `completeCapture` 用 `waitForRemoteStop`(基于 `kill -0 <pid>` 轮询)判断后台进程结束后拉文件。
- 实测问题:在 user build 上,`--background-wait` 返回的 PID 提取/轮询失效(实测 stdout/stderr 为空、或 PID 已不存在),`waitForRemoteStop` 提前返回 true,代码在后台进程写完 trace 前拉到 0 字节文件。后台进程实际要跑满 `duration_ms`(10s)才写完文件(实测:0s 时 0 字节,11s 后 2.3MB)。
- 官方命令 `cat config | perfetto -c - --txt -o ...`(无 `--background-wait`):前台阻塞跑满 `duration_ms`,退出时已 `Wrote N bytes`,文件完整非空(实测 2MB)。

约束:`AdbClient.shell` 支持 `timeout` 与 `isCancellationRequested` 回调;`PerfettoCaptureSession` 当前用 `completionMutex`/`stateMutex`/`captureScope` 管理 state。

## Goals / Non-Goals

**Goals:**
- System Overview 等所有模板的 trace 抓取产出非空完整文件。
- 对齐官方命令语义(前台运行),采集结束即文件就绪。
- Stop/取消仍可中断未完成的采集,且中断后能拿到已采的部分 trace。

**Non-Goals:**
- 不改数据源/配置生成(属 `align-system-overview-default-probes`)。
- 不改 buffer policy / duration 默认值。
- 不引入 `--notify-fd` 等更复杂机制(前台阻塞已足够)。
- 不改 trace 后续的解析/导入链路。

## Decisions

### D1. 去掉 `--background-wait`,前台运行
`startDeviceCapture` 改为 `cat config | perfetto -c - --txt -o <trace>`(参数顺序对齐官方:`-c - --txt`),前台阻塞至 `duration_ms` 结束、perfetto 写完文件自然退出。shell 返回即文件就绪。消除 PID 提取 + kill -0 轮询的脆弱链。
- 备选:保留 `--background-wait` 但修 PID 提取——否,user build 上 PID 行为不稳,根因难根治;前台方案对齐官方、最简。

### D2. shell timeout 按 duration 动态设置
当前 `adbClient.shell(..., 60.seconds)` 固定 60s。改为 `duration_ms + 余量`(如 `durationSeconds + 30` 秒),避免长时长采集(最大 600s)触发 AdbClient 超时,又给 perfetto 写文件留余量。

### D3. Stop/取消用 SIGTERM 给设备 perfetto(经真机修正)
初始方案用 `isCancellationRequested` 中断 adb shell 来停止采集。真机验证发现这会**粗暴杀掉 adb 进程、断开管道,perfetto 来不及 flush,产出 0 字节文件**——手动 Stop 时空 trace 的根因。

修正(已真机验证):Stop 时给设备端 perfetto 发 SIGTERM(`pkill -TERM -f 'perfetto -c - --txt'`),perfetto 收到信号后 `disabling tracing`、优雅 flush 已采数据并写文件、正常退出,阻塞的 adb shell 随之返回,capture job pull 回**非空部分 trace**。**不设 `captureCancellationRequested`**(那会粗暴中断 adb)。
- 保底:发 SIGTERM 后 `captureJob.join()`;若 perfetto 未响应 SIGTERM、job 仍 active,再置 `captureCancellationRequested` 强制中断 adb,避免永久挂起。
- 自动完成(到 duration):shell 自然返回,无需 SIGTERM。
- 真机实测:`pkill -TERM` 后 perfetto `Wrote 914664 bytes`(914KB 部分 trace),shell 正常返回。

### D4. 移除后台 PID 相关逻辑
删除 `stopRemoteCapture`/`waitForRemoteStop`/`forceStopRemoteCapture`/`activePerfettoPid` 及 `REMOTE_STOP_*` 常量。`PerfettoCaptureState.Recording` 的 `pid` 字段:前台运行无意义,改为移除或保留为 0 占位(倾向移除以避免误导)。`CaptureMetadata.command` 去掉 `--background-wait`。

### D5. 自动完成调度简化
当前 `scheduleAutomaticCompletion` 用 `delay(durationMs)` 后 `completeCapture`。前台方案下 shell 本身会阻塞到 `duration_ms` 结束——`startCapture` 应在 shell 返回后直接进入完成/拉取流程,不再需要 `delay + completeCapture` 的轮询触发。但为保留"Recording 状态可见 + 可中途 Stop"的 UX,仍可:启动后立即置 Recording、shell 在协程里阻塞、完成时回调拉取;Stop 通过取消协程/置标志实现。

## Risks / Trade-offs

- [前台阻塞占用一个 shell/协程直到 duration 结束] → 已有 `captureScope`/IO dispatcher 承载,可接受;Stop 可中断。
- [Stop 中断后部分 trace 是否可用] → perfetto 收到中断会 flush 已采数据,通常产出可用部分 trace;若中断过早(<1s)可能仍空,属可接受边界(用户主动 Stop 的语义)。
- [移除 Recording.pid 可能影响 UI/依赖] → 需检查 `PerfettoCaptureState.Recording` 的 pid 使用方;若仅日志/状态显示则安全移除或置 0。
- [长 duration(600s)+ 余量 timeout] → AdbClient 默认 timeout 可能需调整;D2 动态计算覆盖。
- [前台 perfetto 的 "No PTY / CTRL+C" 警告] → 仅警告,不影响采集;官方命令同有此提示。

## Open Questions

- `PerfettoCaptureState.Recording.pid` 的下游使用方(状态栏/UI/测试)是否依赖 pid?需在实现时确认移除还是置 0。
- Stop 中断后是否仍调 `cleanupDeviceFiles` 删设备文件——需确认部分 trace 已拉回后再删。
