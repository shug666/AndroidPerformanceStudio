## ADDED Requirements

### Requirement: 反向供网会话启动编排
系统 SHALL 提供会话编排器，按固定顺序执行：安装 vendor 的 gnirehtet.apk（若未安装或版本不匹配）→ 建立 `adb reverse localabstract:gnirehtet tcp:31416` → 启动内嵌 relay → 向设备发送启动 intent。任一步失败时，编排器 SHALL 停止后续步骤并将失败原因映射到会话错误态。

#### Scenario: 首次启动（APK 未安装）
- **WHEN** 用户对已连接设备开启反向供网且设备未安装 gnirehtet.apk
- **THEN** 系统先安装 vendor 的 gnirehtet.apk，再建立 adb reverse、启动 relay 与设备端客户端

#### Scenario: adb reverse 失败
- **WHEN** `adb reverse` 命令返回失败（设备离线或 unauthorized）
- **THEN** 编排器不启动 relay 与客户端，会话进入错误态并展示失败原因

### Requirement: 反向供网会话停止编排
系统 SHALL 提供停止路径，按逆序清理：向设备发送停止 intent → 移除 adb reverse → 停止内嵌 relay。会话异常退出（如桌面端崩溃）后，系统 SHALL 能检测残留状态（`adb reverse --list` 中的 gnirehtet 条目、设备端运行中的客户端）并提供恢复入口。

#### Scenario: 正常停止
- **WHEN** 用户关闭已处于供网中状态的会话
- **THEN** 设备端客户端停止、adb reverse 条目移除、relay 关闭且端口释放

#### Scenario: 崩溃后残留恢复
- **WHEN** APS 启动时检测到 `adb reverse --list` 存在 gnirehtet 条目
- **THEN** 系统在设备面板提示残留供网会话并提供一键恢复（清理或重启会话）

### Requirement: VPN 授权状态检测
系统 SHALL 依据 relay 侧隧道连接状态判定 VPN 授权状态：收到隧道连接并完成 client id 握手即为"供网中"；启动后 15 秒内无隧道连接则判定为"等待 VPN 授权"并提示用户在设备上确认；adb 层错误映射为错误态。

#### Scenario: 设备已授权
- **WHEN** 设备此前已授权过 gnirehtet 的 VPN 权限，用户开启供网
- **THEN** 隧道在短时间内建立，状态显示"供网中"

#### Scenario: 设备等待授权
- **WHEN** 设备从未授权，系统对话框在设备上弹出
- **THEN** 15 秒内无隧道连接时状态显示"等待 VPN 授权"，并提示用户确认设备上的 VPN 连接请求

#### Scenario: 用户拒绝授权
- **WHEN** 用户在设备上拒绝 VPN 授权请求
- **THEN** 会话保持"等待授权"提示或进入错误态，用户可重试（重新触发启动 intent）

### Requirement: 会话状态机
会话 SHALL 维护单一状态机：空闲 → 启动中 → 等待授权 | 供网中 → 停止中 → 空闲，以及任意态 → 错误态。状态 SHALL 以响应式状态（StateFlow）暴露给 UI，UI SHALL 展示当前状态与隔离模式。

#### Scenario: 状态流转可见
- **WHEN** 会话经历启动 → 授权 → 供网 → 停止
- **THEN** UI 按实际流转展示每个状态，错误发生时展示错误信息

### Requirement: 供网事件接入网络事件流
供网会话产生的网络观测事件（连接事件、DNS 事件、泄漏告警）SHALL 以独立事件流输出，network-profiler SHALL 能在时间轴上与 OkHttp agent 事件一同消费展示。

#### Scenario: 供网事件与 OkHttp 事件同轴展示
- **WHEN** 被测 app 集成了 OkHttp agent 且供网会话活跃
- **THEN** 网络事件时间轴同时展示 OkHttp 语义事件与供网隧道层连接事件

### Requirement: 设备自动检测
系统 SHALL 在反向供网页面进入时自动经 `adb devices` 列出已连接设备，每 2 秒轮询刷新；自动选中第一个设备；无设备时显示明确提示。用户 SHALL NOT 需要手动输入设备 serial。

#### Scenario: 设备已连接
- **WHEN** 用户进入反向供网页面且设备已连接
- **THEN** 设备 serial 自动列出并选中，可直接启动会话

#### Scenario: 无设备
- **WHEN** 进入页面时无已连接设备
- **THEN** 显示"未检测到设备"提示，启动按钮禁用

### Requirement: 独立导航入口
反向供网 SHALL 作为独立的左侧导航目的地与首页功能卡片存在，位于导航顺序末尾（Source Workspaces 之后），SHALL NOT 嵌入既有 profiler 工具栏。

#### Scenario: 导航可达
- **WHEN** 用户点击左侧导航栏的反向供网图标或首页卡片
- **THEN** 进入独立的反向供网页面

### Requirement: 初始化容错
adb 或 vendored APK 解析失败时 SHALL 在页面内显示错误信息，SHALL NOT 导致页面组合崩溃。

#### Scenario: adb 未找到
- **WHEN** 平台工具未配置且系统 PATH 无 adb
- **THEN** 页面显示初始化错误，不崩溃

### Requirement: 设备选择与刷新
系统 SHALL 提供刷新按钮手动重新查询设备列表。

#### Scenario: 手动刷新
- **WHEN** 用户点击刷新按钮
- **THEN** 重新执行 `adb devices` 并更新设备列表
