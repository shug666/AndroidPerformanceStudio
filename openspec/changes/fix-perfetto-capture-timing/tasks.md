## 1. 启动命令改前台运行

- [x] 1.1 `startDeviceCapture` 去掉 `--background-wait`,命令改为 `cat $CONFIG | perfetto -c - --txt -o $TRACE`(对齐官方参数顺序)
- [x] 1.2 shell timeout 改为 `durationSeconds + 30` 秒(动态,覆盖长时长 + 写文件余量),替换固定 60s
- [x] 1.3 不再从输出提取 PID;`startCapture` 在 shell 阻塞协程中执行,返回即采集完成

## 2. 移除后台 PID 轮询逻辑

- [x] 2.1 删除 `stopRemoteCapture` / `waitForRemoteStop` / `forceStopRemoteCapture`
- [x] 2.2 删除 `activePerfettoPid` 字段及 `REMOTE_STOP_*` 常量
- [x] 2.3 `completeCapture` 移除 `waitForRemoteStop` 调用;自动完成时 shell 已返回直接拉文件
- [x] 2.4 `PerfettoCaptureState.Recording` 的 `pid`:确认下游使用方,移除或置 0 占位

## 3. Stop / 取消改用 SIGTERM(真机修正)

- [x] 3.1 引入可取消标志(AtomicBoolean 或持有 shell 的 Job/协程句柄)
- [x] 3.2 `AdbClient.shell` 调用传入 `isCancellationRequested` 回调(仅作保底,Stop 主路径不用)
- [x] 3.3 `stopCapture` 改为给设备 perfetto 发 `pkill -TERM -f 'perfetto -c - --txt'`(不中断 adb shell);perfetto 优雅 flush 写文件后退出,shell 自然返回
- [x] 3.4 保底:SIGTERM 后 job 仍 active 则置 `captureCancellationRequested` 强制中断,避免挂起
- [x] 3.5 中断后仍拉回部分 trace(若非空)再 `cleanupDeviceFiles`

## 4. 状态与元数据同步

- [x] 4.1 `CaptureMetadata.command` 去掉 `--background-wait`,改为 `perfetto -c - --txt -o <trace>`
- [x] 4.2 `scheduleAutomaticCompletion` 评估是否仍需 `delay(durationMs)`(前台 shell 已阻塞到完成);按 D5 简化
- [x] 4.3 确认 `PerfettoCaptureState.Recording` 在采集期间仍可见(启动后即置 Recording,shell 完成后转 Pulling/Completed)

## 5. 测试

- [x] 5.1 更新 `PerfettoCaptureCommandTest` 命令字符串断言(无 `--background-wait`,`-c - --txt` 顺序)
- [x] 5.2 新增:完成捕获产出非空 trace 的断言(fake adb 返回非空文件)
- [x] 5.3 新增:Stop 中断路径断言(取消标志置位、拉部分 trace)
- [x] 5.4 新增:长 duration 时 shell timeout 动态计算的断言
- [x] 5.5 移除/更新原 PID 提取相关测试

## 6. 验证

- [x] 6.1 单元测试全绿
- [x] 6.2 真机(已连 HA2E9HHH,Android 17/user)跑 System Overview 抓取,确认产出非空 trace 且可正常打开
- [x] 6.3 真机测 Stop 中断:采集中途停止,确认拿到部分 trace 或合理失败(非空文件检查)
