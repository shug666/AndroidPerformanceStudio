## 1. presentation 层 (PerfettoCapturePage.kt)

- [x] 1.1 `PerfettoCapturePage` 新增 `onExportConfig: (PerfettoCaptureConfig) -> Unit` 参数,透传到 `PerfettoConfigurationPanel`
- [x] 1.2 `PerfettoConfigurationPanel` 新增 `onExportConfig` 参数
- [x] 1.3 Configuration 面板 Start Capture 同行新增 "Export config" 按钮(始终可见,不依赖 capture 状态)
- [x] 1.4 按钮 onClick 调 `createCaptureConfig`(与 Start 同一套组装,androidSdk = deviceCapabilities?.androidSdk ?: 0)后上调 `onExportConfig`
- [x] 1.5 strings.xml(中英)新增 "Export config" 文案 + 保存对话框标题

## 2. app 层 (PerfettoMainPage.kt)

- [x] 2.1 新增 `exportConfig(config: PerfettoCaptureConfig)`:`Dispatchers.IO` 协程内 `PerfettoConfigTextBuilder.build(config)` → `chooseSaveFile`(默认名按模板)→ `Files.writeString` → 失败置 `diagnosticError`(复用 exportRawTrace 模式)
- [x] 2.2 默认文件名按模板:`<template-kebab>-config.txtpb`(system-overview/app-performance/.../custom)
- [x] 2.3 串到 capture page 的 `onExportConfig` 回调
- [x] 2.4 成功静默或轻提示(实现时定)

## 3. 测试

- [x] 3.1 导出非 Custom 配置 → 文件内容 = build(config)
- [x] 3.2 导出 Custom 配置 → 文件内容 = customConfigText 原样
- [x] 3.3 默认文件名按模板(system-overview-config.txtpb)
- [x] 3.4 androidSdk≥33 导出含 disable_generic_events;未连设备不含
- [x] 3.5 写文件失败 → 错误提示

## 4. 验证

- [x] 4.1 单元测试全绿
- [x] 4.2 手动导出 system-overview 配置,对照 config.txtpb 关键字段一致
