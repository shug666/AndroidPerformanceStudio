## 1. 模块脚手架与 vendor 产物

- [x] 1.1 创建 `network-profiler/reverse-net/` Gradle 模块并加入 `network-profiler/settings.gradle.kts`；依赖 `platform-core/adb-core`（includeBuild）
- [x] 1.2 添加 `third_party/gnirehtet/`：放入官方 gnirehtet.apk 2.5.1（从本机 gnirehtet 工程构建或下载 release）+ README（来源、版本、Apache 2.0 许可说明）；接入 reverse-net 模块资源打包
- [x] 1.3 定义事件模型（`events/`）：连接事件（五元组、协议、TCP 状态、字节数、时长）、DNS 事件（域名→IP、AAAA 剥离标记）、告警事件（泄漏/降级），含有界队列 + 丢弃计数的异步发射器（参考 `EventBuffer` 模式）

## 2. 内嵌 relay 数据面（移植 relay-java）

- [x] 2.1 移植包解析层：`IPv4Header`/`IPv4Packet`/`TCPHeader`/`UDPHeader`/`TransportHeader`（对照 relay-java 逐文件，保留版权头）
- [x] 2.2 移植连接层：`ConnectionId`（五元组 key）、`AbstractConnection`/`TCPConnection`/`UDPConnection`、`StreamBuffer`/`DatagramBuffer`
- [x] 2.3 移植路由与代开层：`Router`（连接表 + 真实 socket 代开）、`Client`、`TunnelServer`（监听 127.0.0.1:31416 + client id 握手）、packetizer/buffered I/O 层
- [x] 2.4 植入 tap 钩子：IPv4 解析后、TCP/UDP 分发前的 choke point 产出连接/状态/字节/关闭事件；有界队列异步发射，验证不阻塞热路径
- [x] 2.5 relay 回环测试：本地模拟隧道两端，验证 TCP 握手/数据/优雅关闭/RST、UDP 数据报、非 v4 包丢弃、端口冲突错误态

## 3. DNS 解析与 AAAA 过滤

- [x] 3.1 实现 UDP:53 响应解析（仅 A/AAAA/CNAME 级别的轻量解析），产出域名→IP 事件
- [x] 3.2 实现隔离模式 AAAA 剥离：改写 DNS 应答（移除 AAAA 记录、修正 ancount）后转发，产出"AAAA 已剥离"事件；宽松模式直通
- [x] 3.3 测试：真实 DNS 查询经 relay 验证事件产出；隔离模式抓包验证设备收到无 AAAA 的应答

## 4. 会话编排与状态机

- [x] 4.1 实现 SessionController：install（版本不匹配或未装时）→ adb reverse → relay 启动 → am start START；停止逆序清理；步骤失败映射错误态
- [x] 4.2 实现 VPN 授权状态机：relay 连接信号 → 供网中；15s 超时 → 等待授权；adb 错误 → 错误态；StateFlow 暴露
- [x] 4.3 实现崩溃残留检测与恢复：启动时检查 `adb reverse --list`、检测设备端运行中的客户端，提供恢复入口
- [x] 4.4 JVM shutdown hook：进程退出时清理 relay、adb reverse 与设备端客户端（尽力而为）
- [x] 4.5 测试：状态机全流转（空闲→启动中→等待授权→供网中→停止中→空闲、错误态）、失败注入（reverse 失败、端口占用）

## 5. IPv6 防泄漏

- [x] 5.1 实现 Private DNS 检测：隔离模式会话启动时 `settings get global private_dns_mode` 探测，hostname 命中产出降级告警
- [x] 5.2 实现 LeakAuditor：`dumpsys connectivity` 判定非隧道网络接口存在时，30s 周期 `ping6` 主探测 + `ip -6 route get` 辅助；命中泄漏产出告警；探测失败静默降级计数
- [x] 5.3 测试：模拟有 v6 出口/无 v6 出口/无网三种设备状态下的审计行为与降级路径

## 6. UI 集成

- [x] 6.1 `desktop-app` 设备面板新增「反向供网」区块：开关、隔离模式选择（宽松/隔离，启动时确定）、状态指示（供网中/等待授权/停止中/错误 + 错误信息）
- [x] 6.2 接入会话控制：开启/停止调用 SessionController；等待授权时展示设备端确认提示；错误态提供重试与恢复
- [x] 6.3 隔离模式告警展示：泄漏亮红 + 建议操作；Private DNS 降级提示

## 7. 事件流接入与端到端验证

- [x] 7.1 供网事件流接入 network-profiler 消费侧，与 OkHttp agent 事件时间轴同轴展示（连接事件 + DNS 事件 + 告警）
- [x] 7.2 实机端到端验证（场景 A）：无网设备供网，浏览器访问网页，验证 relay 转发、DNS 事件、连接事件
- [x] 7.3 实机端到端验证（场景 B 隔离模式）：有网设备强制走电脑，验证 AAAA 剥离（设备侧 `ping -6` 域名无 v6 结果）、泄漏审计命中（WiFi 有 v6 时告警）
