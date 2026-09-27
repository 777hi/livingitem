# 容器监控 —— 使用指南

> *2026-09-27 自 `system-design/` 迁至 `guides/`（位置按「内容性质」归位）*

> 📄 **本文讲「怎么用」**：启用、日志、报告格式、集成点、性能。
> ⚖️ **「什么算异常」的判定口径属契约层**，见
> [`container-monitor-invariants.md`](../system-design/container-monitor-invariants.md)
> —— 本文刻意不重复维护口径，避免两处漂移。
>
> 命令语法以 [`reference/commands.md`](../reference/commands.md) 为导航、**以代码为准**。

## 1. 概述

容器监控系统（ContainerMonitor）是活物品模组的调试工具，用于**实时检测容器处理过程中的异常状态变化**，包括物品复制、物品丢失、活物品被覆盖等问题。

系统在容器处理前后各捕获一次快照，对比差异，当检测到异常时同时写入日志文件和向 OP 玩家聊天栏广播。

## 2. 启用方式

### 2.1 游戏内命令

监控开关挂在 `/livingitem debug monitor`（`on` / `off` / `status`），
背包转储为 `/livingitem debug dump_inventory`。

**完整命令树见 [`reference/commands.md`](../reference/commands.md)** —— 本文不重复维护命令细节
（命令名与语法以代码为准，由 `doc_check` 第 8 项校验文档引用是否真实存在）。

### 2.2 日志文件

监控日志输出到 `logs/living_item_container_monitor.log`，自动追加写入。

### 2.3 聊天栏广播

异常发生时，向所有 OP 玩家发送聊天栏消息，格式：

```
[Monitor] <异常类型> in <容器key> | <摘要>
```

示例：
```
[Monitor] ITEM_DUPLICATION in chest_100_67_-340354 | Gained: +3x minecraft:dirt
[Monitor] ITEM_LOSS in player_xxx | Lost: -1x minecraft:water_bucket slot[45] LIVING_OVERWRITTEN
```

## 3. 检测能力

异常类型的定义、计数方式（按物品类型统计总量 ⇒ 堆叠合并不误报）、
差异计算流程 —— **全部见契约层
[`container-monitor-invariants.md`](../system-design/container-monitor-invariants.md)**。

> 为什么不放这儿：那些是「什么算异常」的口径，属于不变量（违反即 bug）；
> 使用指南只负责「怎么开、看什么」。分开放后，改口径只需动一处。

## 4. 异常报告格式

### 4.1 日志文件（详细）

```
[1724683138793] === ANOMALY: chest_100_67_-340354 === ITEM_DUPLICATION
  GAINED (possible duplication):
    +3x minecraft:dirt
  SLOT CHANGES:
    slot[5]: dirt x1 -> dirt x4
    slot[8]: (empty) -> dirt x1
  BEFORE:
    slot[3]: minecraft:water_bucket x1 [L]
    slot[5]: minecraft:dirt x1
  AFTER:
    slot[3]: minecraft:water_bucket x1 [L]
    slot[5]: minecraft:dirt x4
    slot[8]: minecraft:dirt x1
```

- `[L]` 标记表示活物品
- `GAINED` / `LOST` 按物品类型统计总数量差异
- `SLOT CHANGES` 列出每个发生变化的槽位
- `BEFORE` / `AFTER` 列出处理前后的完整容器状态

### 4.2 聊天栏（摘要）

```
[Monitor] ITEM_DUPLICATION in chest_100_67_-340354 | Gained: +3x minecraft:dirt
```

## 5. 工作原理

### 5.1 快照捕获

```
beforeProcess(containerKey, ctx)  →  捕获 BEFORE 快照
    ↓
processContext(ctx, level)         →  容器处理（活物品 tick）
    ↓
afterProcess(containerKey, ctx)   →  捕获 AFTER 快照 → 对比差异 → 报告异常
```

### 5.2 快照内容

- 每个槽位的 `ItemStack.copy()`（深拷贝，防止后续修改影响快照）
- 按物品类型统计的总数量映射

### 5.3 差异计算

1. 逐槽位对比，记录 `MODIFIED` / `ADDED` / `LIVING_OVERWRITTEN` 变化
2. 按**物品类型**对比总数量，计算 `gained` / `lost`
3. 如果存在 `gained` / `lost` 或 `LIVING_OVERWRITTEN`，生成异常报告

## 6. 性能影响

| 场景 | 开销 |
|------|------|
| 监控关闭 | 零开销（`isEnabled()` 短路返回） |
| 监控开启，无异常 | 每容器 2 次快照捕获 + 1 次差异计算 |
| 监控开启，有异常 | 上述 + 日志写入 + 聊天栏广播 |

快照捕获需要 `ItemStack.copy()` 每个槽位，对大容器（54格大箱子）有一定开销。**建议仅在调试时开启，生产环境关闭。**

## 7. 集成点

监控系统在 `ContainerLivingItemHandler.processContext()` 中集成：

```java
public static void processContext(ContainerContext context, Level level) {
    String monitorKey = context.getContainerKey();
    ContainerMonitor.beforeProcess(monitorKey, context);

    // ... 容器处理逻辑 ...

    ContainerMonitor.afterProcess(monitorKey, context);
}
```

## 8. 关键文件

| 文件 | 职责 |
|------|------|
| `ContainerMonitor.java` | 监控核心：快照捕获、差异计算、异常报告、日志写入、聊天栏广播 |
| `ContainerMonitorCommand.java` | `/livingitem debug` 命令注册与处理 |
| `ContainerLivingItemHandler.java` | 集成点：beforeProcess / afterProcess 调用 |

## 9. 已发现并修复的 Bug

以下 Bug 是通过本监控系统发现的：

| Bug | 容器类型 | 异常类型 | 根因 | 修复 |
|-----|---------|---------|------|------|
| 物品复制 | 大箱子 | ITEM_DUPLICATION | `setItem()` 走 `container.setItem()` 只写左半箱子，`getItem()` 走 handler 合并视图，读写不一致 | 统一走 `handler.extractItem()` + `handler.insertItem()` |
| 活水桶消失 | 大箱子 | LIVING_OVERWRITTEN | 同上，`setItem()` 写入错误半箱子覆盖活水桶 | 同上 |
| 物品复制（级联） | 所有容器 | ITEM_DUPLICATION | `pushItems()` 部分合并后没有 break，用过期数量继续合并 | 部分合并后 break + moved.add() |
| 物品方向异常 | 所有容器 | — | `pushItems()` 按 level 降序排序，物品从外层往内层推 | 改为 level 升序，物品沿水流方向推动 |

## 10. 代码审查修复记录

2026-08-26 代码审查发现并修复的问题：

| # | 文件 | 问题 | 严重度 | 修复 |
|---|------|------|--------|------|
| 1 | `ContainerFluidData.java` | Javadoc 说"按 level 降序"但代码是升序 | 低 | 修正 Javadoc 为"按 level 升序" |
| 2 | `LivingWaterBucketFunction.java` | `cleanupStaleEntries()` 是空方法体，`BUCKET_STATES` 无限增长 | 🔴 高 | 实现清理逻辑：移除 120s 未访问条目 |
| 3 | `LivingWaterBucketFunction.java` | `BucketState.lastTick` 存 `gameTime`（~1000），但 `cleanupStaleEntries` 用 `System.currentTimeMillis()`（~1.7万亿）做差值，所有条目立即被清除 | 🔴 高 | 拆为双字段：`lastAccessMs`（System.currentTimeMillis）供缓存清理，`lastGameTick`（gameTime）供 needsReset 检测 |
| 4 | `ContainerLivingItemHandler.java` | `cleanupStaleEntries` 未接入周期清理逻辑 | 中 | 在 CLEANUP_INTERVAL 周期清理中调用 |