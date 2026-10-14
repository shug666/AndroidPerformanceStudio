# Android Performance Studio 商业化 UI 实施文档

实施方案已按功能页拆分。开始任务前先阅读共用设计、架构与交付约束，再阅读对应功能页；页面设计参考已嵌入对应文档，图片原文件仍位于 `images/`。

## 共用规范与实施流程

- [共用基础与交付规范](00-shared-foundation-and-delivery.md)：文档约定、产品目标、架构、Design System、App Shell、跨页行为、Codex 执行资料、任务拆分、测试策略与 Definition of Done。
- [实施进度](IMPLEMENTATION_PROGRESS.md)
- [Codex 输入资料检查清单](CODEX_INPUT_CHECKLIST.md)

## 功能页

1. [Home Dashboard](01-home-dashboard.md)
2. [Layout Inspector](02-layout-inspector.md)
3. [CPU Profiler](03-cpu-profiler.md)
4. [Trace Analyzer](04-trace-analyzer.md)
5. [Memory Profiler](05-memory-profiler.md)
6. [Frame Profiler](06-frame-profiler.md)
7. [Startup Profiler](07-startup-profiler.md)
8. [Battery & Network](08-battery-network.md)
9. [GPU Analysis Hub](09-gpu-analysis-hub.md)
10. [Benchmark Regression](10-benchmark-regression.md)
11. [Reports Center](11-reports-center.md)
12. [Settings](12-settings.md)
13. [Source Workspaces](13-source-workspaces.md)

## 使用约定

- 所有页面都必须遵守共用文档中的证据边界、模块职责和设计系统约束。
- 设计图只用于视觉层级和布局参考；真实能力以源码、测试、ADR 和 feature 设计文档为准。
- Codex 任务应附上对应页面文档、页面设计图、相关源码/ADR，以及可用的脱敏 fixture。

## 遗漏核对

- 13 个功能页中有 12 页已嵌入对应设计图；`Source Workspaces` 没有专属设计素材，页面文档已注明。
- [`images/reference-original.png`](images/reference-original.png) 是 Reports 与 Settings 的旧版组合参考；当前页面采用 `reports-settings-v2-*.png`，旧图保留作历史素材，没有重复嵌入。
- 原方案的 fixture 清单只覆盖 Layout、CPU、Trace、Memory、Frame、Startup、Battery、Network、GPU、Benchmark；Home、Reports Center、Settings、Source Workspaces 没有定义专属 fixture。这是原方案已有的覆盖缺口，本次没有臆造测试数据要求。
