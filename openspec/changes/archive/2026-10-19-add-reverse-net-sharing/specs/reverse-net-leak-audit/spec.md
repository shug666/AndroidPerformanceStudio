## ADDED Requirements

### Requirement: 隔离模式配置
供网会话 SHALL 支持宽松（默认）与隔离两种模式：宽松模式下 AAAA 记录原样转发、不执行泄漏审计；隔离模式下启用 AAAA 剥离与泄漏审计。模式选择 SHALL 在会话启动时确定，供网期间 SHALL NOT 动态切换（需停止后重启会话生效）。

#### Scenario: 宽松模式启动
- **WHEN** 用户以默认（宽松）模式开启供网
- **THEN** AAAA 记录原样转发，无泄漏审计任务运行

#### Scenario: 隔离模式启动
- **WHEN** 用户选择隔离模式开启供网
- **THEN** DNS 剥离 AAAA、泄漏审计启用，UI 展示隔离态标识

### Requirement: Private DNS 检测
隔离模式下，系统 SHALL 在会话启动时探测设备 Private DNS 配置（`adb shell settings get global private_dns_mode`）：`hostname` 模式（DoT）命中时产出降级告警事件，提示 DNS 可观测性与 AAAA 过滤已降级，并建议用户关闭 Private DNS 或改用自动模式。

#### Scenario: 命中 Private DNS
- **WHEN** 隔离模式会话启动时设备 `private_dns_mode` 为 `hostname`

- **THEN** 产出降级告警事件，UI 提示"设备启用了 Private DNS，DNS 观测与 v6 防泄漏已降级"

#### Scenario: 未启用 Private DNS
- **WHEN** 设备 `private_dns_mode` 为 `off` 或 `opportunistic`
- **THEN** 不产出降级告警，DNS 事件与 AAAA 过滤正常工作

### Requirement: 周期性 v6 泄漏审计
隔离模式下，系统 SHALL 在设备存在非隧道网络接口时（`dumpsys connectivity` 判定）周期（30s）探测 v6 出网能力：主探测 `ping6` 已知 v6 目标，辅助探测 `ip -6 route get`。探测失败（无 v6 出口）为预期结果；探测成功 SHALL 判定泄漏，产出泄漏告警事件，UI 亮红并给出建议操作（关闭设备 WiFi/切换飞行模式）。

#### Scenario: 设备有网且 v6 可出网（泄漏）
- **WHEN** 设备 WiFi 有 v6 出口，隔离模式会话运行中
- **THEN** 周期审计命中泄漏，UI 亮红并建议关闭 WiFi 或飞行模式

#### Scenario: 设备有网但无 v6 出口
- **WHEN** 设备 WiFi 仅有 v4，隔离模式会话运行中
- **THEN** 探测失败（预期），无告警，审计持续运行

#### Scenario: 设备无网
- **WHEN** `dumpsys connectivity` 显示设备无非隧道活跃网络
- **THEN** 审计不运行（不浪费探测），无泄漏告警

### Requirement: 探测降级与静默失败
LeakAuditor 的探测命令 SHALL 以可插拔策略实现；探测命令执行失败（命令不存在、权限不足）时 SHALL 静默降级：不计泄漏，仅记录探测失败计数，供网与审计继续运行。

#### Scenario: 探测命令不可用
- **WHEN** 设备 shell 不支持 `ping6` 与 `ip -6 route get`
- **THEN** 审计进入降级态：不产泄漏判定，累计探测失败计数，会话其余功能不受影响

### Requirement: 泄漏与降级告警事件接入网络事件流
泄漏告警、Private DNS 供给降级告警 SHALL 作为网络事件流中的告警类事件输出，network-profiler SHALL 能展示并区分告警类型（泄漏/降级）。

#### Scenario: 告警事件同轴展示
- **WHEN** 隔离模式会话产出泄漏告警
- **THEN** network-profiler 时间轴展示告警事件并标注类型
