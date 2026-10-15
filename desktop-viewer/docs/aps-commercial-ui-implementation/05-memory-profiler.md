# 页面 05：Memory Profiler

![Memory Profiler 设计参考](images/05-memory-profiler.png)

## 10.1 产品定位

第一阶段明确定位为：

> Android compatible HPROF investigation workspace + Native/Bitmap 辅助证据。

不是“实时 Memory Profiler 全功能复制品”。

## 10.2 目标布局

```text
Toolbar
Import HPROF | Dump Heap | Mapping | Compare | Export | Search

Snapshot Tabs
[Snapshot A] [Snapshot B] [+]

Summary
Heap Size | Objects | Classes | Leak Suspects

┌───────────────┬──────────────────────────────────┬─────────────────────────┐
│ Filters       │ Classes / Dominators / Diff      │ Object Inspector        │
│ Heap          │ Main virtualized table           │ Object ID / Size         │
│ Scope         │                                  │ Fields                   │
│ Leak          │                                  │ Inbound references       │
│ Search        │                                  │ GC Root path             │
├───────────────┴──────────────────────────────────┴─────────────────────────┤
│ Status / Parse warnings / Evidence capabilities                           │
└────────────────────────────────────────────────────────────────────────────┘
```

## 10.3 必须支持的主视图

```text
Classes
Dominators
Diff
Leaks & Bitmaps
Native Heap（有对应 artifact 时）
```

## 10.4 Classes

字段至少：

```text
Class
Instances
Shallow Size
Retained Size
Native Size（可用时）
Heap
```

支持：

- Class / Package grouping；
- heap filter；
- project/system scope；
- leak filter；
- regex/case；
- sort；
- instance drill down。

## 10.5 Object Inspector

显示：

```text
Object ID
Class
Reachability
Depth
Shallow
Retained
Native size（有证据时）
Fields
References
Inbound references
Paths to GC Root
```

必须支持：

- open target object；
- back/forward；
- copy object id；
- pin；
- large array pagination。

## 10.6 Diff

**只能做类级可信比较。**

不得把两个 dump 中相同 object id 视为同一个对象。

表格：

```text
Class | A Count | B Count | Δ Count | A Retained | B Retained | Δ Retained
```

## 10.7 设计图中需要删除/改造的内容

删除：

```text
Realtime Heap Timeline
GC marker timeline
live allocation/deallocation timeline
```

除非未来独立开发 `Live Memory Recording`。

替代为：

```text
Snapshot comparison
Heap distribution
Retained size composition
Top growth classes
```

## 10.8 代码范围

现有重点：

```text
memory-profiler/presentation/MemoryProfilerScreen.kt
MemoryProfilerState.kt
MemoryProfilerController.kt
HprofParser
DominatorTreeAnalyzer
InstanceReferenceQuery
MemoryHistogramAnalyzer
```

已有 Memory HPROF design 应作为 Codex 强制输入资料。

## 10.9 验收

- 大 HPROF 解析不阻塞 Compose UI；
- cancellation 有效；
- class → instance → reference chain 闭环；
- Dominator / retained size 可复核；
- Diff 不伪造跨 dump identity；
- 无 allocation evidence 时对应字段明确 unavailable/not applicable；
- Export 包含 source hash / mapping / algorithm version / limitation。

---
