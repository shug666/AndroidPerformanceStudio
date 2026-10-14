## Why

用户在 Trace Analyzer 的 Configuration 面板调好数据源/interval/atrace 等配置后,无法把生成的 Perfetto textproto 配置导出成文件。`PerfettoConfigTextBuilder.build()` 已能生成 textproto,但目前只用于推到设备抓取,没有"保存到本地文件"的出口。用户需要导出 `.txtpb` 以便存档、分享、或直接喂给 `perfetto --txt -c` 在命令行复现。

## What Changes

- 在 Configuration 面板新增 "Export config" 按钮(与 Start Capture 同行),点击后把当前配置导出为 `.txtpb` 文件。
- 导出走与 Start Capture 相同的 `createCaptureConfig` 组装,再 `PerfettoConfigTextBuilder.build(config)` 生成 textproto,弹 `chooseSaveFile` 保存对话框(默认文件名按模板,如 `system-overview-config.txtpb`),写入文件。
- `PerfettoCapturePage` 新增 `onExportConfig: (PerfettoCaptureConfig) -> Unit` 回调,presentation 层只负责组装 config 并上传;文件保存对话框与磁盘写入在 app 层(`PerfettoMainPage`)完成,复用现有 `exportRawTrace` 的协程 + `chooseSaveFile` + 错误显示模式。
- 导出的是"当前配置"(与点 Start 会推给设备的 config 一致),`androidSdk` 取 `deviceCapabilities?.androidSdk ?: 0`(未连设备时不输出 `disable_generic_events`,符合 pre-T 安全行为)。
- 不做导入(本轮仅导出)。
- Custom 模板导出即用户粘贴的 textproto 原样(`build()` 对 Custom 直接返回 customConfigText)。

## Capabilities

### New Capabilities
_(无)_

### Modified Capabilities
- `trace-capture-session`: Configuration 面板新增导出当前配置为 `.txtpb` 文件的能力。

## Impact

- `desktop-viewer/perfetto-viewer/perfetto-presentation/.../PerfettoCapturePage.kt`:`PerfettoConfigurationPanel` 与 `PerfettoCapturePage` 新增 `onExportConfig` 参数 + Export config 按钮。
- `desktop-viewer/perfetto-viewer/perfetto-app/.../PerfettoMainPage.kt`:实现 `exportConfig(config)`(build + chooseSaveFile + 写文件 + 错误显示),串到 capture page 的 `onExportConfig`。
- `desktop-viewer/perfetto-viewer/perfetto-capture/.../PerfettoConfigTextBuilder.kt`:无需改(build 已支持)。
- strings.xml(中英)新增 "Export config" 文案与保存对话框标题。
- 相关测试更新。
