# Layout Inspector MCP Server

为 AndroidPerformanceStudio 提供 MCP（Model Context Protocol）能力，复用 `adb-gateway` / `protocol-model` 抓取 View 层级，并支持从 Layout Inspector 右键定位到 Android 源码位置。

## 架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  AndroidPerformanceStudio desktop-viewer (Layout Inspector)          │
│  右键 view 节点 → "定位源码"                                          │
│   → SourceLocateBridge.pushLocateRequest(...)                        │
│   → HTTP POST http://127.0.0.1:7821/pending                          │
└──────────────────────────────────────────────────────────────────────┘
                  │  (本地 HTTP 桥接，JDK HttpServer)
                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│  mcp-server (Kotlin + MCP SDK, stdio)                                │
│  LocalBridgeHttpServer :7821  ←  存入 PendingLocateRequests          │
│  AutoLocateCoordinator (默认开启):                                    │
│    后台轮询 pending → SourceLocator 定位 → sendLoggingMessage 推结果  │
│  MCP 工具:                                                            │
│    - list_devices                                                    │
│    - capture_view_hierarchy  (复用 LiveDeviceClient)                  │
│    - locate_view_source      (SourceLocator 多候选搜索)               │
│    - pop_pending_locate_request  (手动消费，自动模式通常用不到)       │
│    - list_pending_locate_requests                                   │
└──────────────────────────────────────────────────────────────────────┘
                  │  (MCP stdio, notifications/message)
                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│  源码项目 Android Studio 终端 → claude cli                           │
│  自动模式: 右键后终端自动出现 file:line（可点击跳转），无需手动调用  │
│  手动模式: 调 pop_pending_locate_request 显式定位                    │
└──────────────────────────────────────────────────────────────────────┘
```

## 模块组成

- `Main.kt` — 入口，启动 stdio MCP server + 本地 HTTP 桥接 + 自动定位协调器
- `McpServerHost.kt` — MCP 工具注册
- `AutoLocateCoordinator.kt` — 后台轮询右键请求，自动定位并通过 MCP logging notification 推送结果
- `ViewHierarchyCaptureService.kt` — 复用 `LiveDeviceClient` 抓层级
- `SourceLocator.kt` — 源码定位（activity 范围 + resource-id/class 多候选 + 置信度排序）
- `LocalBridgeHttpServer.kt` — JDK `HttpServer` 本地桥接
- `PendingLocateRequests.kt` — 待定位请求队列
- `LocateModels.kt` — 数据模型

## 构建

```bash
# 在 desktop-viewer 目录下
./gradlew -p layout-inspector :mcp-server:shadowJar
# 产物: layout-inspector/mcp-server/build/libs/layout-inspector-mcp-0.1.0-SNAPSHOT.jar
```

## 使用流程

### 1. 构建 fat jar

```bash
cd /Users/zjw/AndroidStudioProjects/AndroidPerformanceStudio/desktop-viewer
./gradlew -p layout-inspector :mcp-server:shadowJar
```

### 2. 在被测 app 源码项目根目录放置 `.mcp.json`

把 `mcp.json.example` 复制为源码项目根目录的 `.mcp.json`，把 `args` 里的 jar 路径改为绝对路径：

```json
{
  "mcpServers": {
    "aps-layout-inspector": {
      "command": "java",
      "args": ["-jar", "/Users/zjw/AndroidStudioProjects/AndroidPerformanceStudio/desktop-viewer/layout-inspector/mcp-server/build/libs/layout-inspector-mcp-0.1.0-SNAPSHOT.jar"],
      "env": { "APS_BRIDGE_PORT": "7821" }
    }
  }
}
```

### 3. 在源码项目 Android Studio 内置终端启动 claude cli

claude cli 会自动加载 `.mcp.json` 并连接本 MCP server。MCP server 同时在 `127.0.0.1:7821` 开本地桥接。

### 4. 在 AndroidPerformanceStudio 运行 Layout Inspector

```bash
./gradlew :desktop-app:run
```

连接设备，抓取层级。在层级树中**右键（长按）view 节点 → "定位源码"**，节点信息会推送到桥接端口。

### 5. 在 claude cli 中定位

**自动模式（默认）**：右键后无需任何操作，claude cli 终端会自动出现一行 `[aps-locator] ... file:line ...`，点击 `file:line` 即可跳转到源码。MCP server 后台轮询桥接请求，定位完成后通过 `notifications/message` 推送给 client。

**手动模式**（`APS_AUTO_LOCATE=false`）：对 claude 说「定位刚才右键的 view」或让它调用 `pop_pending_locate_request` 工具，显式取出节点并定位。

## MCP 工具

| 工具 | 说明 |
|------|------|
| `list_devices` | 列出已连接的在线 Android 设备 |
| `capture_view_hierarchy` | 抓取前台 app 的 View 层级树（Agent→fallback），返回可读树 |
| `locate_view_source` | 在 Android 工程中定位 view 源码（activity 范围 + resource-id/class 多候选） |
| `pop_pending_locate_request` | 手动消费右键推送的节点并定位（自动模式下通常用不到） |
| `list_pending_locate_requests` | 查看待定位请求数量与最近一条 |

## 源码定位策略

`SourceLocator` 按置信度从高到低搜索（合并去重后排序）：

| 置信度 | 策略 |
|--------|------|
| 1.00 | Compose 检查提供的 source hint（fileName:line） |
| 0.90 | 自定义 View 的 class 声明（`class MyView`） |
| 0.85 | 布局 XML 中的 `@+id/entry` |
| 0.75 | 布局 XML 中的自定义 View 标签（`<com.example.MyView`） |
| 0.65 | 代码中 `R.id.entry` 引用 |
| 0.60 | `values/ids.xml` 中的 `<item name="entry"` |
| 0.55 | Activity/Window class 声明 |
| 0.50 | `strings.xml` 中 `name="text"` |
| 0.45 | 代码中的文本字面量 |

搜索范围限定在 `src/` 下的 `.kt`/`.java`/`.xml`，跳过 `build`/`.gradle`/`.idea`/`generated` 等。

## 端口与环境变量配置

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `APS_BRIDGE_PORT` | `7821` | 本地 HTTP 桥接端口，desktop-viewer 与 mcp-server 必须一致 |
| `APS_AUTO_LOCATE` | `true` | MCP 模式下自动定位（通过 logging notification 推送，claude cli 通常不显示，建议用 `--watch` 模式） |
| `APS_PROJECT_ROOT` | 当前工作目录 | 源码定位的工程根目录 |

## 两种运行模式

### 模式 A：`--watch` 独立终端（推荐用于右键跳转源码）

不跑 MCP，只轮询桥接 + 定位 + `println`。在**被测 app 源码项目的 Android Studio 内置终端**开一个 tab 跑：

```bash
cd /path/to/被测app源码项目
java -jar /Users/zjw/AndroidStudioProjects/AndroidPerformanceStudio/desktop-viewer/layout-inspector/mcp-server/build/libs/layout-inspector-mcp-0.1.0-SNAPSHOT.jar --watch
```

然后到 desktop-viewer 的 Layout Inspector 右键 view 节点 → "定位源码"。**watch 终端会自动出现**：

```
[aps-watch] 收到右键定位请求:
  viewClass: android.widget.Button
  resourceId: com.example:id/btn_login
  activity: com.example.MainActivity

[aps-watch] 找到 2 个候选（扫描 1234 个文件）:
  1. /Users/.../app/src/main/res/layout/activity_main.xml:42  (置信度 0.92)  Layout XML @+id/btn_login (tag <Button> matches)
  2. /Users/.../app/src/main/java/com/example/MainActivity.kt:88  (置信度 0.85)  R.id.btn_login in Activity

[aps-watch] >> 点击上方 路径:行号 可跳转到源码。
```

Android Studio 终端会把 `绝对路径:行号` 渲染成可点击链接，点击即跳转。**无需 claude cli，无需手动调用工具。**

### 模式 B：MCP 模式（用于让 claude cli 抓层级/定位）

默认模式（不加 `--watch`），跑 MCP stdio 给 claude cli 用。`.mcp.json` 配置见上文。claude cli 可调 `list_devices` / `capture_view_hierarchy` / `pop_pending_locate_request` 等工具。

> ⚠️ 两种模式**不能同时跑**（都占用同一个桥接端口）。要右键自动跳转用模式 A；要让 claude cli 抓层级用模式 B。

## 直接运行

```bash
# watch 模式（右键自动跳转）
./layout-inspector/mcp-server/run.sh --watch

# MCP 模式（给 claude cli）
./layout-inspector/mcp-server/run.sh
```