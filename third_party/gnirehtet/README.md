# gnirehtet (vendored)

官方 gnirehtet 2.5.1 的 Android VPN 客户端 APK，原样 vendor，未修改、未重签。

- 来源: https://github.com/Genymobile/gnirehtet/releases/tag/v2.5.1
  (gnirehtet-java-v2.5.1.zip 中的 gnirehtet.apk)
- 许可: Apache License 2.0 (Copyright (C) 2017 Genymobile)
- 校验: sha256 c1ac2b869a48e3c836046aac5a168f3ade510288f3304a87ddb671315c564b9a

## 用途

`network-profiler/reverse-net` 模块的反向供网会话将其安装到设备上作为
VPN 数据面客户端。隧道协议为 client id 握手 + 裸 IPv4 包双向流，
与 APS 内嵌 Kotlin relay（自 relay-java 移植）直接兼容。

## 升级策略

手动替换 APK 并更新上方校验值。替换后验证隧道协议未变（client id
握手 + 裸 IP 包），协议自 2017 年未变。
