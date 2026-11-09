# Winscope ADB Proxy 协议对照文档

> 实现规范来源：`winscope_proxy.py`（AOSP，`VERSION = '4.0.8'`）。本文档供 Kotlin 服务端实现逐条对照；
> 任何与本表冲突的实现都应视为缺陷。客户端侧事实来自打包 bundle 中 `ProxyConnection` 的 minified 代码。

## 0. Spike 实测记录（2026-11-09，headless Chrome 155 + 真实 bundle）

以 stub 服务（5544）+ `python -m http.server`（8000）+ headless Chrome 加载 `http://127.0.0.1:8000/index.html?token=TESTTOKEN` 验证：

- **握手成立**：URL `?token=` 参数被客户端采用并以 `Winscope-Token` 头发送，版本头校验通过（否则客户端拒绝连接），无任何人工输入。
- **请求时序**（实测）：`OPTIONS /devices/` → `GET /devices/` → `OPTIONS /checkwayland/` → `GET /checkwayland/` → **持续轮询 `GET /devices/`**（15 秒虚拟时间内 17 次，约 1 次/秒）。
- **路径带尾斜杠**：客户端请求 `/devices/`、`/checkwayland/`（服务端路由必须容忍尾斜杠）。
- **Host 为 `localhost`**：客户端按 `WINSCOPE_PROXY_URL = "http://localhost:5544"` 发起，`OPTIONS` 预检在每个跨端口请求前发生。
- **性能含义**：`/devices` 轮询极高频，而参考实现每次轮询执行 `adb devices -l` + 每台设备 `screenrecord --version`（失败再 `--help`）+ `dumpsys SurfaceFlinger --display-id`。Kotlin 实现必须缓存设备探测结果（如 1s TTL），否则 adb 会被轮询打满。
- 403 重试 UI（`onRetryConnection`）存在于代码路径，未在本次（token 正确）场景触发。

## 0.05 真实实现 E2E 验证记录（2026-11-11，Kotlin 服务端 + 真实闭包）

`WinscopeBrowserHandshakeIT` + headless Chrome（`-Dwinscope.it.enabled=true` 流程）：

- 真实 `WinscopeCaptureService` 启动：27 文件闭包校验和清单验证通过；proxy 绑定 127.0.0.1:5544、查看器托管 8100。
- curl 带 token 请求 `/devices/` → 200 `{}`；无 token → 403（正文与参考实现一致）。
- headless Chrome 加载 `http://127.0.0.1:8100/index.html?token=<session>` → DOM 34,280,039 字节（与 stub spike 的 34,275,484 同量级，Angular 完整 bootstrap），浏览器接受 Kotlin 实现的版本头并用 URL token 完成握手。
- 结论：方案 2 的服务端实现与打包闭包在浏览器级联调通过；剩余真机项为 root 设备抓取链路。

## 0.07 真机联调偏差修正记录（2026-11-11，TB710FU userdebug）

1. **screenrecord 版本探测必须合并 stderr**：参考实现 `call_adb` 用 `stderr=subprocess.STDOUT`，
   而多数设备 `screenrecord --version` 输出到 stderr。只读 stdout 会得到空 → 降级 `"0"` →
   浏览器禁用屏幕录制。修复后与参考实现实测输出等价（`"1.4"` vs `"1.4\n"`，客户端解析兼容）。
2. **抓取停止 = 本地 adb shell 取消**：参考实现 `/end` 对本地 adb 进程发 SIGINT（Windows kill），
   设备侧 trap 完成 stop 并写 `TRACE_OK`；本地命令的"取消"是正常停止机制而非失败。
   成功判定 = `TRACE_OK && (cancelled || stderr 空)`。
3. **fetch 用临时文件 pull 而非 exec-out cat**：host-toolchain 的输出捕获会按
   `maxOutputBytesPerStream` **预分配**缓冲（stdout+stderr 两份），大上限直接触发
   `Requested array size exceeds VM limit`。参考实现同样是 pull 到临时文件。

## 0.1 Bundle 来源登记（暂定）

- AOSP commit：**未知**（bundle 内未发现 commit 标识；用户副本，待用户确认出处）
- 文件时间戳：2026-04-05；proxy 参考实现版本：4.0.8；查看器客户端版本：4.0.8
- 目录：`/home/ts/下载/winscope-prod`（约 78 MB，含 `winscope_proxy.py` 60KB 与 `*.bat` 非运行文件）


## 1. 传输与鉴权

| 项 | 值 |
|---|---|
| 监听地址 | `localhost:5544`（客户端 `WINSCOPE_PROXY_URL` 写死） |
| 版本头 | 每个响应带 `Winscope-Proxy-Version: 4.0.8`；客户端校验 major 相等且 (minor 更大 或 minor 相等且 patch ≥ 客户端) |
| 鉴权 | 所有端点要求 `Winscope-Token` 请求头，与 secret 不符 → `403`，正文 `b"Bad Winscope authorization token!\nThis is Winscope ADB proxy.\n"` |
| token 来源（客户端） | URL `?token=` 参数 > localStorage（key `adb.proxyKey`）> 空（403 后 UI 提供粘贴 token 重试 `onRetryConnection`） |
| CORS（参考实现） | `Access-Control-Allow-Origin: *`；`Allow-Methods: POST, GET, OPTIONS`；`Allow-Headers: Winscope-Token, Content-Type, Content-Length`；`Expose-Headers: Winscope-Proxy-Version`；`OPTIONS` 回 `GET,POST` |
| 缓存 | `Cache-Control: no-cache, no-store, must-revalidate`（所有响应） |
| 路由 | 路径按 `/` 切分，首段为端点名，其后为参数（如 `/fetch/<serial>`）；未知端点名 → `400 Bad request!...Unknown endpoint /x/` |
| 请求体 | `POST` 读 `Content-Length` 字节后 `json.loads`；缺失/不可读 → `400` |

错误响应格式（参考实现语义）：`400` bad request、`403` bad token、`500` internal error（正文为错误文本，`text/txt`）。

## 2. 端点规范

### 2.1 `GET /devices`

解析 `adb devices -l`（正则 `^([A-Za-z0-9._:\-]+)\s+(\w+)(.*model:(\w+))?`，跳过表头行），对每台 authorized 设备：

- `screenrecord_version`：`adb shell screenrecord --version`；失败则从 `screenrecord --help` 输出中取首个 `v` 后 3 字符；再失败为 `'0'`
- `displays`：`adb shell su root dumpsys SurfaceFlinger --display-id` 按行 split（失败为 `[]`）

响应：`200`，`text/json`，形如

```json
{ "<serial>": { "authorized": true, "model": "Pixel 7", "displays": ["Display 2169155966275815240 (active)"], "screenrecord_version": "1.1" } }
```

### 2.2 `GET /checkwayland`（无设备段）

先执行 `/devices` 逻辑；仅当**恰好一台**设备且 authorized 且有 model 时，`adb shell service check Wayland`，输出含 `not found` → `false`，否则 `true`。多台/零台 → `false`。响应：`200`，`text/json`，正文 `true`/`false`。

### 2.3 `GET /status/<serial>`

- 无进行中抓取 → `400 Bad request!\n...No trace in progress for <serial>`（经 `_bad_request`）。
- 有抓取：对每个 trace 线程重置 keep-alive 计时（间隔 `KEEP_ALIVE_INTERVAL_S = 5`s，超时触发 `end_trace`），响应 `200` `text/plain`，正文 `True`/`False`（`str(thread.is_alive())`，首线程）。

### 2.4 `POST /start/<serial>`

请求体：JSON 数组，每项 `{"name": "<trace_name>", "config": [{"key": "...", "value": ...}]}`。
`name` 取值见 §3.1 TRACE_TARGETS 键。处理流程（`get_targets_and_prepare_for_tracing`）：

1. `su root rm -f /data/misc/perfetto-configs/winscope-proxy-trace.conf`
2. `adb shell perfetto --query` 取查询结果文本
3. `too_many_perfetto_sessions`：解析 `perfetto --query` 的 `TRACING SESSIONS:` 表（表头/尾标记见 §4 常量），会话数 ≥ 5 → warning `"Limit of 5 Perfetto sessions reached on device. Will attempt to collect legacy traces."` 并强制全部走 legacy；若表中含 winscope 自身会话名，先 `perfetto --attach=WINSCOPE-PROXY-TRACING-SESSION --stop` 并把计数减 1
4. 对每个请求项：未知 name 忽略（warning，不报错）；`is_perfetto = (!too_many) && target.is_perfetto_available(query_result)`；有 config getter 时按请求 config 调 `add()`，非法 key → `400 Unsupported config <key>`，随后执行 config 命令（见 §3.2/§3.3）；执行前校验 root（`su root id -u` == 0，失败 → 500 `Unable to acquire root privileges...`）
5. 目标选择：仅保留 `perfetto_trace`（is_perfetto 目标）或非 perfetto 的其他目标；**perfetto 目标移到列表末尾**（其配置须最后追加进 conf 文件）
6. 清理上次会话：`su root rm -rf /data/local/tmp/last_winscope_tracing_session/ && su root mkdir ...`
7. 若该设备已有抓取线程 → 直接响应 `200` 空 body（注意：参考实现在此响应后仍继续往下执行，为兼容保留其行为语义即可）
8. 对每个 (target, config) 组合（含 `trace_identifier` 展开，如多显示器）构造 shell 脚本（§3.4 TRACE_COMMAND 模板），`adb shell` 启动子进程（不阻塞），登记到 per-device 线程组

响应：`200`，`text/json`，warnings JSON 数组（无警告为 `[]`）。

### 2.5 `POST /end/<serial>`

- 无进行中抓取 → `400 No trace in progress for <serial>`
- 对每条线程：alive 则 `end_trace()`（SIGINT/Windows kill，等待 ≤ `COMMAND_TIMEOUT_S = 15`s）；取线程 out/err 与 `su root cat /data/local/tmp/winscope_signal_handler.log`；线程 cleanup 超时 → error `"Trace <name> timed out during cleanup"`；非成功 → error `"Error ending trace <name> on the device: <err>"`
- 按 target 的 files matcher（§3.1）将设备文件 `mv` 到 `/data/local/tmp/last_winscope_tracing_session/<filetype>`（`su root [ ! -f p ] || su root mv p dest`，失败仅 warning）
- 删除 status 文件，清除该设备线程组
- 响应：`200`，`text/plain`，errors JSON 数组（无错为 `[]`）

### 2.6 `GET /fetch/<serial>`

从 `/data/local/tmp/last_winscope_tracing_session/*`（`su root find ... -name` 空 matcher 全列）逐文件：

- `adb exec-out su root cat <path>` 拉取（失败：warning 并中止本次 fetch，返回已收集部分）
- 键名 = 原文件名 + `.gz`；同名多份时 value 为数组追加
- 值 = `base64.encodebytes(gzip.compress(bytes))` 的 UTF-8 文本（**带换行的 base64**，客户端按文本处理）

响应：`200`，`text/json`：

```json
{ "wm_trace.winscope.gz": ["<base64...>"], "trace.perfetto-trace.gz": ["<base64...>"] }
```

无文件时返回 `{}`（warning: "Proxy didn't find any file to fetch"）。

### 2.7 `POST /dump/<serial>`

同 `/start` 的准备流程（用 `DUMP_TARGETS` + `winscope-proxy-dump.conf`），但不启动常驻线程：把各 dump 命令（`{trace_identifier}`/`{options}` 格式化）按 `\n` 拼接后经单个 `adb shell` 会话执行（阻塞至完成）；执行后按 target 收集文件到 backup 目录。响应：`200`，`text/plain`，warnings JSON 数组。

DUMP_TARGETS 键：`window_dump`（`su root dumpsys window --proto > /data/local/tmp/wm_dump.winscope`）、`layers_dump`（`su root dumpsys SurfaceFlinger --proto > /data/local/tmp/sf_dump.winscope`，perfetto 可用时附 MODE_DUMP config）、`screenshot`（`screencap -p {options} > /data/local/tmp/screenshot_<id>.png`）、`perfetto_dump`（`duration_ms: 1` 的临时 perfetto 会话 → `winscope-proxy-dump.perfetto-trace`）。

## 3. 设备命令细节

### 3.1 TRACE_TARGETS（顺序即浏览器预期解码顺序）

| name | perfetto 数据源判定 | legacy start | legacy stop | 产物文件（`/data/misc/wmtrace/` 除非另注） |
|---|---|---|---|---|
| `view_capture_trace` | `android.viewcapture` | `settings put global view_capture_enabled 1` | `cmd launcherapps dump-view-hierarchies > view_capture_trace.zip` + 置 0 | `view_capture_trace.zip` |
| `window_trace` | `android.windowmanager` | `cmd window tracing start` | `cmd window tracing stop` | `wm_trace*.winscope/.pb`（WinscopeFileMatcher） |
| `layers_trace` | `android.surfaceflinger.layers` | `service call SurfaceFlinger 1025 i32 1` | `...1025 i32 0` | `layers_trace*.winscope/.pb` |
| `screen_recording` | 恒 false | `settings put system show_touches 1 && pointer_location 1 && screenrecord --bugreport --bit-rate 8M {options} /data/local/tmp/screen_<id>.mp4 &` | `show_touches 0 && pointer_location 0 && pkill -l SIGINT screenrecord` | `/data/local/tmp/screen_<id>.mp4`（`--display-id <id>`） |
| `transactions` | `android.surfaceflinger.transactions` | `service call SurfaceFlinger 1041 i32 1` | `...1041 i32 0` | `transactions_trace*.winscope/.pb` |
| `transactions_legacy` | 恒 false | `service call SurfaceFlinger 1020 i32 1` | `...1020 i32 0` | `transaction_trace*` + `transaction_merges_*` |
| `proto_log` | `android.protolog` | `cmd window logging start` | `cmd window logging stop` | `wm_log*.winscope/.pb` |
| `ime` | `android.inputmethod` | `ime tracing start` | `ime tracing stop` | `ime_trace_clients/service/managerservice*` |
| `wayland_trace` | 恒 false | `service call Wayland 26 i32 1` | `...26 i32 0` | `/data/misc/wltrace/wl_trace*` |
| `eventlog` | 恒 false | `rm -f eventlog.winscope && EVENT_LOG_TRACING_START_TIME=$EPOCHREALTIME` | `logcat -b events ... -t $EPOCHREALTIME >> eventlog.winscope` | `/data/local/tmp/eventlog.winscope` |
| `transition_traces` | `com.android.wm.shell.transition` | `cmd window shell tracing start && dumpsys activity service SystemUIService WMShell transitions tracing start` | 对应 stop | `wm_transition_trace*`、`shell_transition_trace*` |
| `input` | `android.input.inputevent` | （无独立 start） | （无独立 stop） | `input_trace*`（随 perfetto 会话采集） |
| `perfetto_trace` | 任一 winscope 数据源可用 | 见 §3.4 | `perfetto --attach=WINSCOPE-PROXY-TRACING-SESSION --stop` | `/data/misc/perfetto-traces/winscope-proxy-trace.perfetto-trace` |

### 3.2 可配置项（config key）

**SurfaceFlinger**（`layers_trace`）：flags `input/composition/metadata/hwc/tracebuffers/virtualdisplays`（legacy 位图 `1<<1..1<<6` 经 `service call SurfaceFlinger 1033 i32 <n>`；perfetto 映射 `TRACE_FLAG_INPUT/COMPOSITION/EXTRA/HWC/BUFFERS/VIRTUAL_DISPLAYS` 追加进 conf）；`sfbuffersize`（默认 `16000`，legacy `1029 i32`）。

**WindowManager**（`window_trace`）：`tracinglevel`（`verbose/debug/critical` → `LOG_LEVEL_*`）、`tracingtype`（`frame/transaction` → `LOG_FREQUENCY_*`）、`wmbuffersize`（默认 `16000`）。legacy 顺序固定：type → level → **buffer size 最后**（否则被覆盖）。

**ScreenRecording/Screenshot**：`displays`（字符串或数组，取首个空格前字段为 identifier；`"active"` 时不加 `--display-id`/`-d`）。

**ProtoLog/IME/ViewCapture/Transitions/Input**：无 config（perfetto conf 固定文本，见 proxy.py 各 `*Config.COMMAND`；Input 用 `TRACE_MODE_TRACE_ALL`，ProtoLog 用 `ENABLE_ALL`）。

### 3.3 perfetto conf 文本

现代路径经 `cat << EOF >> /data/misc/perfetto-configs/winscope-proxy-trace.conf` 逐数据源追加（先非 perfetto 目标后 perfetto 目标）；`perfetto_trace` 目标追加 buffer/会话头（`size_kb: 500000`、`fill_policy: RING_BUFFER`、`duration_ms: 0`、`file_write_period_ms: 999999999`、`write_into_file: true`、`unique_session_name: "winscope proxy perfetto tracing"`），然后 `rm -f` 产物后 `perfetto --out ... --txt --config ... --detach=WINSCOPE-PROXY-TRACING-SESSION`。

### 3.4 start 外壳脚本（TRACE_COMMAND 模板）

每条 target 的 start/stop 命令被包进一个 shell 脚本：`set -e`；写 `TRACE_START` 到 `/data/local/tmp/winscope_status_<name>`；注册 `stop_trace` trap（写 signal_handler 日志、执行 stop 命令、写 `TRACE_OK`）；执行 start 命令；`while true; do sleep 0.1; done` 保持前台。宿主端以 keep-alive 5s 计时，`/end` 或超时向 shell 进程发 SIGINT（Windows kill），脚本 trap 完成 stop 并写 `TRACE_OK`，宿主轮询 status 文件确认清理完成（0.1s 间隔、上限 15s）。

## 4. 关键常量

```
PERFETTO_TRACE_CONFIG_FILE  /data/misc/perfetto-configs/winscope-proxy-trace.conf
PERFETTO_DUMP_CONFIG_FILE   /data/misc/perfetto-configs/winscope-proxy-dump.conf
PERFETTO_TRACE_FILE         /data/misc/perfetto-traces/winscope-proxy-trace.perfetto-trace
PERFETTO_DUMP_FILE          /data/misc/perfetto-traces/winscope-proxy-dump.perfetto-trace
PERFETTO_UNIQUE_SESSION_NAME  winscope proxy perfetto tracing
WINSCOPE_DIR                /data/misc/wmtrace/
WINSCOPE_BACKUP_DIR         /data/local/tmp/last_winscope_tracing_session/
SIGNAL_HANDLER_LOG          /data/local/tmp/winscope_signal_handler.log
WINSCOPE_STATUS             /data/local/tmp/winscope_status
KEEP_ALIVE_INTERVAL_S       5
COMMAND_TIMEOUT_S           15
WINSCOPE_EXT                .winscope（legacy .pb）
```

`perfetto --query` 会话表解析标记：

```
START: "TRACING SESSIONS:\n\nID      UID     STATE      BUF (#) KB   DUR (s)   #DS  STARTED  NAME\n===     ===     =====      ==========   =======   ===  =======  ====\n"
END:   "\nNOTE: Some tracing sessions are not reported in the list above."
```

## 5. 与参考实现的既定偏差（APS 侧决策）

| 参考实现 | APS 实现 | 依据 |
|---|---|---|
| token 持久化 `~/.config/winscope/.token` | 会话级随机 token，不落盘；浏览器经 `?token=` 注入 | design D2 |
| 静态页面由外部 http.server 提供 | APS 同进程托管 bundle 闭包（另一本地端口） | design D2/D6 |
| 所有响应 CORS `*` | 保留 `*`（客户端跨端口请求所需），暴露头仅版本头 | design D6 |
| `/start` 对重复请求响应后继续执行 | 保持同语义：重复请求按参考实现处理 | 协议兼容 |
| fetch 前无归档 | `/fetch` 字节流同步落盘 APS 工件目录 | design D5 |
| 事件日志/wayland 目标 | 照抄保留（协议兼容），Wayland 设备不在支持范围 | design Non-Goals |
