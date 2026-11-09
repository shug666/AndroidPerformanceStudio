# winscope-capture-service spec

Android Performance Studio 作为 Winscope ADB proxy 的服务面：5544 loopback 服务生命周期、与打包 Winscope 查看器（客户端版本 4.0.8）的协议兼容、静态资源托管、浏览器启动与 token 注入，以及服务安全边界。

## ADDED Requirements

### Requirement: Winscope 服务仅从显式用户动作启动

Winscope 抓取服务 SHALL 仅在用户从 Winscope 页面执行显式"启动"动作后启动；打开 Winscope 页面、切换设备或导航离开 SHALL NOT 作为副作用启动服务或打开浏览器。应用退出或用户显式"停止"后，服务 SHALL 关闭且当前 token SHALL 失效。

#### Scenario: 打开页面不启动服务
- **WHEN** 用户导航进入 Winscope 页面
- **THEN** 不启动任何本地 HTTP 服务，也不打开浏览器

#### Scenario: 显式启动服务
- **WHEN** 用户在 Winscope 页面点击"启动"动作
- **THEN** proxy 服务与静态资源服务启动，页面呈现服务状态

#### Scenario: 停止后 token 失效
- **WHEN** 用户停止服务或应用退出
- **THEN** 服务端口不再接受连接，已发放的 token 在服务重启后不被接受（重启生成新 token）

### Requirement: proxy 服务绑定 loopback 并响应兼容的版本头

Winscope proxy 服务 SHALL 仅绑定 `127.0.0.1:5544`，并在每个响应中包含 `Winscope-Proxy-Version: 4.0.8` 头，使打包查看器客户端（校验规则：major 相等且 minor 更大，或 minor 相等且 patch 不小于客户端）判定兼容。端口被占用时 SHALL 启动失败并给出明确错误（提示关闭其他 Winscope proxy 实例），SHALL NOT 自动终止占用进程或改用其他端口。

#### Scenario: 客户端版本校验通过
- **WHEN** 查看器探测 `http://localhost:5544` 的任一端点
- **THEN** 响应头 `Winscope-Proxy-Version` 为 `4.0.8`，客户端判定代理兼容

#### Scenario: 端口被占用时明确失败
- **WHEN** 5544 端口已被其他进程占用时用户启动服务
- **THEN** 启动失败，错误信息说明端口被占用及处置建议，不自动改端口

#### Scenario: 仅 loopback 可达
- **WHEN** 从非本机地址访问 5544 端口
- **THEN** 连接不可达

### Requirement: 所有 proxy 请求必须携带有效 token

proxy 端点（`/devices`、`/status`、`/fetch`、`/start`、`/end`、`/dump`、`/checkwayland`）SHALL 校验 `Winscope-Token` 请求头与本服务会话 token 一致，不一致 SHALL 返回 403。token SHALL 为服务启动时生成的会话级随机值，SHALL NOT 持久化到磁盘。

#### Scenario: 无 token 或错误 token 被拒绝
- **WHEN** 请求未携带 `Winscope-Token` 头或 token 与会话不符
- **THEN** 返回 403，不执行任何设备操作

#### Scenario: 正确 token 通过
- **WHEN** 请求携带服务会话 token
- **THEN** 请求进入对应端点处理

### Requirement: 浏览器以 URL token 参数完成零感知握手

用户从 APS 启动查看时，应用 SHALL 以 `?token=<会话token>` 查询参数在系统浏览器打开静态查看器入口页。用户 SHALL NOT 被要求手动粘贴 token；当浏览器 localStorage 中存在过期 token 导致 403 时，应用提供的打开动作 SHALL 始终携带新 token 覆盖。

#### Scenario: 启动查看自动完成握手
- **WHEN** 用户在 Winscope 页面点击"打开查看器"且服务已启动
- **THEN** 系统浏览器打开查看器入口页且 URL 携带 `?token=` 参数，查看器无需人工输入即连接 proxy 成功

### Requirement: 实现与打包查看器兼容的完整 proxy 端点集

服务 SHALL 实现客户端依赖的 7 个端点并以 `winscope_proxy.py`（v4.0.8）为协议规范：`GET /devices`（设备列表）、`GET /status`（抓取保活）、`GET /fetch/`（拉取采集文件）、`POST /start`（启动抓取）、`POST /end`（停止抓取）、`POST /dump`（一次性 dump）、`GET /checkwayland`。请求/响应载荷 SHALL 与参考实现保持客户端可解析的形状；协议实现 SHALL 以针对参考行为的测试与真机联调验证。

#### Scenario: 设备列表端点
- **WHEN** 查看器请求 `GET /devices` 且设备已连接
- **THEN** 响应包含已连接设备的标识与状态信息，形状与参考实现对齐

#### Scenario: 未知端点返回明确错误
- **WHEN** 请求不存在的端点路径
- **THEN** 返回 400 级别错误而非崩溃

### Requirement: 打包查看器资源闭包受校验和保护

打包闭包 SHALL 包含查看器运行所需资源（`index.html`、`js/` 分包、`trace_processor.wasm`、`engine_bundle.js`、静态资源），并 SHALL NOT 包含 `winscope_proxy.py`、启动脚本（`*.bat`）或其他非运行必需文件。资源清单 SHALL 记录来源版本标识与文件校验和，服务启动静态托管时 SHALL 校验闭包完整性，校验失败 SHALL 拒绝启动并报因。打包处理 SHALL 剔除 index.html 中指向第三方分析服务的外链脚本标签。

#### Scenario: 闭包校验通过后可服务
- **WHEN** 服务启动静态托管且闭包校验和匹配清单
- **THEN** 查看器页面及其全部资源可从本地端口加载

#### Scenario: 闭包被篡改时拒绝启动
- **WHEN** 打包闭包中任一被跟踪文件校验和不匹配
- **THEN** 静态托管拒绝启动并明确报因

#### Scenario: 运行时无第三方分析请求
- **WHEN** 查看器在浏览器中完成一次完整加载与 trace 解析
- **THEN** 不产生任何指向第三方分析、CDN 或远程字体/图标源的请求

### Requirement: 证据端点禁止缓存

proxy 端点与静态托管的证据内容 SHALL 以 `Cache-Control: no-store` 响应，SHALL NOT 提供目录列举。

#### Scenario: 拉取的 trace 不被缓存
- **WHEN** 查看器通过 `/fetch` 获取采集文件
- **THEN** 响应带 `Cache-Control: no-store`

#### Scenario: 无法枚举资源
- **WHEN** 请求静态资源服务的目录路径
- **THEN** 不返回目录列表
