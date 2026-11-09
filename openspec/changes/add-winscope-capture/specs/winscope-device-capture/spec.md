# winscope-device-capture spec

Winscope 抓取的设备端执行：设备前置检查（root、残留会话清理）、现代 Perfetto 数据源路径优先与传统路径降级、抓取启停、采集文件拉取与产物归档。抓取配置由查看器随请求下发，APS 负责忠实执行并以参考实现 `winscope_proxy.py`（v4.0.8）为行为规范。

## ADDED Requirements

### Requirement: 抓取前置执行 root 检查并明确报因

启动设备抓取前，执行层 SHALL 验证设备具备 root 权限（复用现有 `adb root` 激活与 uid 0 校验流程）；设备不支持 root 时 SHALL 返回明确的失败原因（"设备未 root，无法抓取系统级 trace"），SHALL NOT 以静默失败或部分成功结束。

#### Scenario: root 可用时正常进入抓取
- **WHEN** 目标设备为 userdebug/工程版且 `adb root` 后 uid 为 0
- **THEN** 抓取流程继续执行能力探测

#### Scenario: root 不可用时明确失败
- **WHEN** 目标设备为生产版，`adb root` 后无法取得 uid 0
- **THEN** 抓取返回明确失败原因，不执行任何抓取命令

### Requirement: 抓取前清理设备残留 tracing 会话

每次启动抓取前，执行层 SHALL 检测设备上残留的 winscope 相关 perfetto tracing 会话并清理（按参考实现的清理策略），避免与本会话及 APS 自身 Perfetto 抓取共用 `traced` 产生冲突。

#### Scenario: 存在残留会话时清理后启动
- **WHEN** 设备上存在上次抓取遗留的 winscope perfetto 会话
- **THEN** 启动抓取前残留会话被清理，新抓取正常建立

#### Scenario: APS Perfetto 抓取进行中时拒绝并发
- **WHEN** APS 自身 Perfetto 抓取会话正在录制时用户发起 Winscope 抓取
- **THEN** Winscope 抓取被拒绝并提示先停止 Perfetto 抓取（反向同理）

### Requirement: 现代路径优先，探测不可用时降级到传统路径

启动抓取时执行层 SHALL 先探测设备 perfetto 数据源可用性；可用时 SHALL 走合流 Perfetto trace 路径（单一 `.perfetto-trace` 产物）；探测不可用或对应数据源缺失时 SHALL 按参考实现的降级决策表回退到传统逐源命令（`cmd window tracing`、`service call SurfaceFlinger` 等）并从 `/data/misc/wmtrace/` 等目录收集产物。实际采用的路径 SHALL 可从抓取结果中得知。

#### Scenario: 数据源齐备时走现代路径
- **WHEN** 设备支持所选数据源的现代 perfetto 实现
- **THEN** 抓取经 perfetto 会话执行，产物为单一合流 trace

#### Scenario: 探测不可用时降级
- **WHEN** 数据源探测不可用或部分缺失
- **THEN** 执行层按参考决策表降级到传统逐源命令，结果中记录实际路径

### Requirement: 抓取配置忠实执行查看器下发的请求

`/start`、`/end`、`/dump` 请求载荷中的抓取配置（数据源选择、tracing type/level、buffer size、SF flags 等）SHALL 被忠实转换为设备命令执行，转换 SHALL 与参考实现对齐，SHALL NOT 引入参考实现之外的设备副作用。

#### Scenario: 查看器下发的配置生效
- **WHEN** 查看器提交 `/start` 且载荷指定数据源与参数
- **THEN** 设备端按该配置建立采集，结果与参考实现行为一致

#### Scenario: end 停止并收集
- **WHEN** 查看器提交 `/end`
- **THEN** 设备端停止录制、收集产物并使其可被 `/fetch` 获取

### Requirement: 采集产物拉取并双路归档

执行层 SHALL 通过 APS 的 ADB 通道拉取采集文件并返回给查看器，同时 SHALL 将产物落盘归档为 APS 会话工件（含设备标识、时间、实际抓取路径与数据源集合）。归档 SHALL NOT 改变返回给查看器的字节内容。

#### Scenario: fetch 返回内容与归档一致
- **WHEN** 查看器通过 `/fetch` 获取抓取产物
- **THEN** 相同字节内容被同步归档到 APS 会话工件目录，并附设备与路径元数据

#### Scenario: 传统路径多文件归档
- **WHEN** 抓取经传统路径产生多个 trace 文件
- **THEN** 每个文件被逐个归档且全部可被查看器获取

### Requirement: 浏览器失联时抓取不悬挂

查看器 keep-alive（`/status`）超过参考实现的存活间隔后，进行中的抓取 SHALL 按参考实现的超时策略收尾（停止录制、收集产物、归档），SHALL NOT 无限悬挂占用设备 tracing 会话。

#### Scenario: 浏览器关闭后抓取收尾
- **WHEN** 浏览器在抓取进行中关闭，keep-alive 停止
- **THEN** 抓取在超时后被收尾，产物归档，设备会话释放
