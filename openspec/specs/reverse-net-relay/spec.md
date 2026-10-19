## ADDED Requirements

### Requirement: relay 隧道握手与连接管理
内嵌 relay SHALL 监听 `127.0.0.1:31416`，接受来自 adb reverse 隧道的连接，完成 client id 握手（向设备回写 4 字节 client id），并 SHALL 与官方 gnirehtet.apk 2.5.1 的隧道协议保持兼容。

#### Scenario: 官方 APK 客户端连接
- **WHEN** vendor 的 gnirehtet.apk 客户端经 adb reverse 连接 relay
- **THEN** relay 完成 client id 握手并开始双向转发 IP 包

#### Scenario: relay 端口冲突
- **WHEN** `127.0.0.1:31416` 被占用
- **THEN** 会话进入错误态并提示端口被占用

### Requirement: IPv4 TCP/UDP 转发
relay SHALL 解析隧道中的 IPv4 包，按五元组维护连接表，为每个新连接在电脑上代开真实 socket 并双向转发。relay SHALL 支持 TCP 状态机（三次握手、数据、优雅关闭、RST）。仅支持 IPv4；非 v4 包 SHALL 丢弃并计数。

#### Scenario: TCP 连接代开
- **WHEN** 设备 app 发起 TCP 连接（如 93.184.216.34:443）
- **THEN** relay 在电脑上代开到同目标的 TCP socket 并双向转发数据

#### Scenario: UDP 转发
- **WHEN** 设备 app 发起 UDP 流量（如 DNS 查询到 8.8.8.8:53）
- **THEN** relay 代开 UDP socket 转发数据报并回传响应

#### Scenario: 非 IPv4 包丢弃
- **WHEN** 隧道中出现非 IPv4 包（IP 版本字段非 4）
- **THEN** relay 丢弃该包并增加丢弃计数，不影响其他连接

### Requirement: tap 观测事件产出
relay SHALL 在包解析点产出观测事件：连接建立（五元组、协议）、TCP 状态变迁、字节量累计、关闭（时长）。事件经有界队列异步发出，队列满时 SHALL 丢弃新事件并累计丢弃数，且 MUST NOT 阻塞转发热路径。

#### Scenario: 连接事件覆盖 TLS 流量
- **WHEN** 设备 app 建立 TLS 连接（内容加密不可见）
- **THEN** relay 产出连接建立、字节量、时长等元数据事件

#### Scenario: 队列满时降级
- **WHEN** tap 事件队列达到容量上限
- **THEN** 新事件被丢弃并累计丢弃计数，转发继续正常工作

### Requirement: DNS 解析与 AAAA 过滤
relay SHALL 解析隧道内 UDP:53 流量：对 DNS 响应产出域名→IP 映射事件；隔离模式下 SHALL 剥离 AAAA 记录后再转发给设备；宽松模式不剥离。剥离行为按会话隔离模式配置。

#### Scenario: DNS 事件产出
- **WHEN** DNS 响应含 A 记录
- **THEN** 产出域名→IP 事件，域名与 OkHttp agent 事件中的 URL 主机名可关联

#### Scenario: 隔离模式剥离 AAAA
- **WHEN** 隔离模式下 DNS 响应含 AAAA 记录
- **THEN** 设备收到的应答不含 AAAA 记录，A 记录保留，产出"AAAA 已剥离"事件

#### Scenario: 宽松模式保留 AAAA
- **WHEN** 宽松模式下 DNS 响应含 AAAA 记录
- **THEN** AAAA 记录原样转发，设备可能经自身网络走 v6

### Requirement: relay 生命周期
relay SHALL 在会话停止或 JVM shutdown hook 触发时清理代开 socket、监听 socket 与连接表；重启会话时以全新状态启动。

#### Scenario: 停止时资源清理
- **WHEN** 用户停止供网会话
- **THEN** relay 关闭所有代开 socket、释放 31416 端口，连接表清空

### Requirement: relay 同端口重启
relay 监听 socket SHALL 设 SO_REUSEADDR，会话停止时 SHALL 显式关闭监听 socket 与所有 client 连接。同端口二次启动 SHALL NOT 报 BindException。

#### Scenario: 会话重启
- **WHEN** 停止会话后立即以同端口启动新会话
- **THEN** relay 成功绑定原端口，不报地址占用

### Requirement: 连接字节计数
每个连接 SHALL 累计设备→网络与网络→设备方向的字节数；Closed 事件 SHALL 携带真实字节总量。

#### Scenario: 关闭事件含字节量
- **WHEN** 一个转发连接关闭
- **THEN** Closed 事件包含该连接生命周期内累计的 bytesSent 与 bytesReceived

### Requirement: APK 运行时定位
vendored gnirehtet.apk SHALL 以 classpath 资源形式打入 reverse-net 模块 jar；安装包运行时 SHALL 从 classpath 提取到 `~/.android-performance-studio/reverse-net/` 缓存；dev 模式 SHALL 优先使用 `third_party/` 源文件。

#### Scenario: 安装包运行
- **WHEN** 应用以安装包形式运行（CWD 非仓库根）
- **THEN** relay 能从 classpath 资源提取 gnirehtet.apk 并安装到设备

#### Scenario: dev 模式
- **WHEN** 从仓库 checkout 运行
- **THEN** 优先使用 `third_party/gnirehtet/gnirehtet.apk` 源文件

### Requirement: 隧道事件日志交互
事件日志区 SHALL 支持鼠标选中复制、右键弹出上下文菜单（清除日志 / 复制全部）；右键 SHALL 抑制 SelectionContainer 内置 Copy 菜单，只弹自定义菜单。

#### Scenario: 选中复制
- **WHEN** 用户在日志区拖选文本
- **THEN** 选中文本可被复制到剪贴板

#### Scenario: 右键菜单
- **WHEN** 用户在日志区右键
- **THEN** 弹出含"清除日志"和"复制全部"两项的菜单，紧贴光标位置

#### Scenario: 抑制内置菜单
- **WHEN** 日志区有选中文本时右键
- **THEN** 只弹自定义菜单，SelectionContainer 内置 Copy 菜单不出现
