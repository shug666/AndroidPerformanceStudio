# 页面 12：Settings

![Settings 页面设计参考](images/reports-settings-v2-overview.png)

![Settings 页面与交互规格](images/reports-settings-v2-spec-board.png)

## 17.1 目标信息架构

建议从单纯 Dialog 逐步演进为分组 Settings，但是否保持 Dialog 可根据现有实现决定。

```text
General
├── Language
├── Theme
├── Accent
├── Display Scale
└── Startup behavior

Android Toolchain
├── Android SDK
├── adb
├── trace_processor
├── simpleperf
└── AGI

Profiler
├── Simpleperf
├── Layout Inspector
├── Memory limits
└── Capture defaults

AI
├── Provider
├── Model
├── Privacy / source inclusion
└── Timeout

Storage
├── Database paths
├── Cache
├── Quota
└── Cleanup

Privacy
├── Redaction defaults
├── Sensitive evidence
└── Export behavior
```

## 17.2 原则

- 不把 feature-specific 所有设置提升到全局；
- Settings 只作为配置入口；
- sensitive token 不写日志；
- tool path 必须验证并显示版本/availability；
- reset 操作分级，不允许一个“Reset All”误删用户 evidence。

---
