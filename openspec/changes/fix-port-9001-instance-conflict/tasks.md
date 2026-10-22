## 1. InstanceGuard 组件

- [x] 1.1 新增 `InstanceGuard`（desktop-app 或共享模块）：`acquire(port): Boolean` 尝试在 `127.0.0.1:port` 绑定 `ServerSocket`，成功则持有（存为字段，JVM 退出时 OS 自动释放），失败返回 false；端口为可配置参数（生产用固定 9002，测试用 free port）
- [x] 1.2 声明固定 liveness token 端口常量（9002）并加注释：仅用于实例存在性，不得承载 trace serving 或任何业务流量，区别于 9001
- [x] 1.3 单元测试：首次 acquire 成功、同端口二次 acquire 失败、release 后可重新 acquire（用 free port 避免 9002 真实占用）

## 2. Main 入口接线

- [x] 2.1 `Main.kt`：在 `application { }` 之前调用 `InstanceGuard.acquire`；失败时走"提示 + 退出"路径，不构造 Compose 窗口
- [x] 2.2 图形环境：弹模态消息（"另一个 Android Performance Studio 实例已在运行，请使用已有窗口。"）后退出
- [x] 2.3 headless 环境（无 display）：打印到 stderr 并以非零状态码退出

## 3. 验收

- [ ] 3.1 启动实例 A → 正常开窗；再启动实例 B → B 显示提示并退出，不开窗
- [ ] 3.2 关闭 A → 重新启动 → 正常开窗（liveness token 已释放）
- [ ] 3.3 `kill -9` A → 重新启动 → 正常开窗（socket 由 OS 释放，无 stale token）
- [ ] 3.4 在 A 内来回切换 9001 的字节服务/原生引擎 → liveness token (9002) 不受影响，B 仍被正确拒绝
