## Context

Trace Analyzer 已有完整的 trace 导出基建:`PerfettoFileMenu` 的 Export 子菜单、`chooseSaveFile`(JFileChooser 保存对话框)、`exportRawTraceFile` 写文件、`PerfettoMainPage.exportRawTrace` 协程模式。`PerfettoConfigTextBuilder.build(config)` 已能生成 textproto。本变更新增"导出当前配置为 .txtpb 文件",复用这套基建。

分层约束:presentation 层(`PerfettoCapturePage`)有配置 state 但不应做文件 IO;app 层(`PerfettoMainPage`)有 `chooseSaveFile`/磁盘 IO/`diagnosticError`。`build()` 在 capture 模块,app 层可调用。

## Goals / Non-Goals

**Goals:**
- Configuration 面板加 "Export config" 按钮,导出当前配置为 `.txtpb`。
- 导出的配置 = `createCaptureConfig` 组装后 `build()` 的产物(与 Start Capture 推给设备的一致)。
- 复用现有 `chooseSaveFile` + 协程 + 错误显示模式。
- 默认文件名按模板(如 `system-overview-config.txtpb`)。

**Non-Goals:**
- 不做导入(读 .txtpb 回 UI)。
- 不做反向解析(textproto → probe 控件)。
- 不改 `PerfettoConfigTextBuilder.build()`。
- 不加配置预览对话框(导出即文件,保持简单)。

## Decisions

### D1. 入口在 Configuration 面板按钮
Configuration 面板的 Start Capture 同行加 "Export config" 按钮。语义贴合:配置在哪导出在哪。不放文件菜单(文件菜单现有 Export 导出的是已抓 trace 产物,配置导出是另一语义;且用户调完配置直接导出最顺)。**按钮位置(已调整)**:Export config 按钮放在 Start Capture 按钮**左侧**(而非右侧),便于导出与开始采集区分。
- 备选(否决):文件菜单 Export 子菜单加项——语义上与 trace 产物导出混淆,且离配置远。

### D2. presentation 层只组装上传,app 层做文件 IO
`PerfettoCapturePage` 加 `onExportConfig: (PerfettoCaptureConfig) -> Unit` 回调。按钮 onClick 调 `createCaptureConfig`(与 Start 同一套组装)后上调。app 层 `exportConfig(config)`:`build(config)` → `chooseSaveFile` → `Files.writeString` → 失败置 `diagnosticError`。复用 `exportRawTrace` 的 `Dispatchers.IO` 协程 + `chooseSaveFile` 模式。
- 理由:presentation 层不做文件 IO(AWT/磁盘),与现有分层一致。

### D3. 导出"当前配置",androidSdk 随设备
组装用 `androidSdk = deviceCapabilities?.androidSdk ?: 0`——与 Start Capture 完全一致。未连设备时 `androidSdk=0`,`disable_generic_events` 不输出(pre-T 安全)。用户想要 T+ 版本就连 T+ 设备再导出。语义清晰:导出 = 此刻点 Start 会用的 config。

### D4. Custom 模板原样导出
`build()` 对 Custom 直接返回 `customConfigText`,故 Custom 导出即用户粘贴的文本原样。非 Custom 导出生成的 textproto。统一走 `build()`,无需特判。

### D5. 默认文件名按模板
`<template-kebab>-config.txtpb`,如 `system-overview-config.txtpb`、`app-performance-config.txtpb`、`custom-config.txtpb`。从 `config.template` 推导。

## Risks / Trade-offs

- [未连设备导出缺 disable_generic_events] → D3 已说明,符合 pre-T 安全;用户连设备再导出得 T+ 版本。
- [导出按钮在 Start 同行可能挤] → 现有行有 Start/Stop/Open(条件显示),加 Export 按钮需确认宽度;Export 始终显示(不依赖 capture 状态),Start 依赖 canStartCapture,布局可调。
- [Custom 空配置导出空文件] → build() 对 Custom 空 customConfigText 返回空串;可加校验或允许(空配置本就无效,用户自负)。
- [文件已存在覆盖] → JFileChooser 保存对话框会提示覆盖,标准行为。

## Open Questions
- 导出成功是否给轻提示(diagnosticError 只显示失败)——实现时定,倾向成功静默或短 toast。
