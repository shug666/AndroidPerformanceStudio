## Why

Trace Analyzer 抓取 trace 完成后报 `captured Perfetto trace must not be empty`——拉回的 trace 文件 0 字节。经真机诊断(Android 17 / user build / perfetto v54),根因是捕获命令用 `--background-wait` 后台化:perfetto 立即 fork 后台进程并返回 PID,代码用提取的 PID + `kill -0` 轮询判断采集结束再拉文件。但在 user build 上 PID 提取/轮询失效,代码在后台进程写完 trace 前就拉了 0 字节文件。官方抓取命令 `cat config | perfetto -c - --txt -o ...`(无 `--background-wait`,前台跑完 `duration_ms` 才退出)实测产出完整非空 trace。

## What Changes

- 去掉 `PerfettoCaptureSession.startDeviceCapture` 的 `--background-wait`,改用前台运行 `perfetto -c - --txt -o <trace>`(对齐官方命令),shell 命令阻塞至 `duration_ms` 结束 perfetto 自然退出后返回,此时 trace 文件已写完。
- shell 调用 timeout 设为 `duration_ms + 余量`(替代当前固定 60s,避免长时长采集超时)。
- Stop/取消改为通过 `AdbClient.shell` 的 `isCancellationRequested` 回调中断 adb 进程(adb 终止会转发给设备 perfetto,perfetto 收到中断后 flush 并写当前已采数据再退出);不再依赖后台 PID 的 `kill -TERM`。
- 移除基于后台 PID 的 `stopRemoteCapture`/`waitForRemoteStop`/`forceStopRemoteCapture`/`activePerfettoPid` 逻辑(前台运行无后台 PID 轮询)。
- `PerfettoCaptureState.Recording` 不再携带 `pid`(或保留为可选/0),`CaptureMetadata.command` 同步更新命令字符串。

## Capabilities

### New Capabilities
_(无)_

### Modified Capabilities
- `trace-capture-session`: 采集启动/完成/停止/取消的时序模型从"后台 PID 轮询"改为"前台阻塞 + 信号中断",修复空 trace。

## Impact

- `desktop-viewer/perfetto-viewer/perfetto-capture/.../PerfettoCaptureSession.kt`:核心改动(startDeviceCapture、completeCapture、stopRemoteCapture/waitForRemoteStop/forceStopRemoteCapture、scheduleAutomaticCompletion、activePerfettoPid)。
- `PerfettoCaptureState.Recording`:pid 字段语义变化(可能移除或保留占位)。
- `CaptureMetadata.command`:命令字符串去掉 `--background-wait`。
- 相关测试 `PerfettoCaptureCommandTest` 等需更新。
- 与 `align-system-overview-default-probes` 解耦:本变更只修采集时序,不涉及数据源/配置生成。
