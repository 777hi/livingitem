# Living Ender Chest (活末影箱) 技术文档

> **文档版本**: 2026.07 v1
> **最后更新**: 2026-07-22
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [核心数据结构](#2-核心数据结构)
3. [Push 端流程（注册路由）](#3-push-端流程注册路由)
4. [Pull 端流程（无线提取）](#4-pull-端流程无线提取)
5. [路由生命周期](#5-路由生命周期)
6. [冷却机制](#6-冷却机制)
7. [频道隔离](#7-频道隔离)
8. [路由清理策略](#8-路由清理策略)
9. [区块卸载处理](#9-区块卸载处理)
10. [已知问题与修复记录](#10-已知问题与修复记录)
11. [调试指南](#11-调试指南)

---

## 1. 架构概览

### 1.1 什么是活末影箱？

活末影箱是一种特殊的活物品（Living Item），它**不存储任何物品**，本质是一个**无线传输路由器**。通过与活漏斗配合，活末影箱可以将不同容器中的物品传输链路连接起来，实现跨容器甚至跨维度的无线物品传输。

活末影箱的宿主物品是 `minecraft:ender_chest`（末影箱），必须同时具备活物品标记。

### 1.2 核心设计理念：共享黑板模式

```
┌──────────────────────────────────────────────────────────────────┐
│                       EnderChannelRegistry                       │
│                       (全局单例·共享黑板)                        │
│                                                                  │
│  频道1: [钻石@容器A槽0, 铁锭@容器B槽2, ...]                       │
│  频道2: [石头@容器C槽5, ...]                                     │
│  频道3: [空]                                                     │
│                                                                  │
│         ▲ 写入路由                          ▼ 读取路由            │
│  ┌──────┴──────────┐              ┌─────────┴──────────┐        │
│  │ push端活漏斗     │              │ pull端活漏斗        │        │
│  │ [活漏斗]→[活末影箱]│              │ [活末影箱]→[输出槽]  │        │
│  │                 │              │                    │        │
│  │ 容器A           │              │ 容器B              │        │
│  └─────────────────┘              └────────────────────┘        │
└──────────────────────────────────────────────────────────────────┘
```

**没有主导者，没有协调者。** push端和pull端互不感知对方的存在，它们只通过 `EnderChannelRegistry` 这个全局路由表（黑板）进行通信：
- **push端**（活漏斗→活末影箱）：往黑板上写"我这里有XX物品在YY位置"
- **pull端**（活末影箱→输出槽）：从黑板上读"有东西吗？有就跳转过去取"

这是一个典型的**独立代理 + 共享状态**架构，每个活漏斗独立执行自己的 tick，互不干扰。

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingEnderChestFunction` | `function/LivingEnderChestFunction.java` | 活末影箱功能入口，**不注册任何组件**，路由由 ItemTransferComponent 触发 |
| `LivingEnderChestAccessor` | `core/accessor/LivingEnderChestAccessor.java` | 活末影箱槽位访问器，实现 registerRoute/extract/rollback |
| `EnderChannelRegistry` | `core/accessor/EnderChannelRegistry.java` | 全局路由表单例，维护频道→路由条目列表的映射，轮询调度 |
| `EnderChannelEntry` | `core/accessor/EnderChannelEntry.java` | 路由条目 record，描述源物品的"指针"（类型+维度+位置+槽位） |
| `SlotAccessorFactory` | `core/accessor/SlotAccessorFactory.java` | 工厂类，检测到活末影箱时创建 LivingEnderChestAccessor |
| `ItemTransferComponent` | `core/components/ItemTransferComponent.java` | 活漏斗传输引擎，检测到 target/source 为活末影箱时分发到对应逻辑 |

### 1.4 组件架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                    LivingEnderChestFunction                      │
│                    (功能入口 · 无组件)                            │
│                    Orchestrators.SIMPLE                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  活末影箱本身不注册任何组件！路由和传输由活漏斗的                    │
│  ItemTransferComponent 触发，通过 LivingEnderChestAccessor 完成。  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                ItemTransferComponent.executeTransfer()           │
│                (活漏斗传输引擎 · 分发)                            │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  target instanceof LivingEnderChestAccessor?             │    │
│  │  ├─ YES → push端：registerRoute() → return true          │    │
│  │  └─ NO  → 继续                                          │    │
│  │                                                          │    │
│  │  source instanceof LivingEnderChestAccessor?             │    │
│  │  ├─ YES → pull端：doTransfer() → return true             │    │
│  │  └─ NO  → 普通传输：doTransfer() → return result         │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────────────────┐  │
│  │ registerRoute()  │         │ extract()                    │  │
│  │ "写入路由表"      │         │ "读取路由表 → 跳转 → 提取"   │  │
│  └────────┬─────────┘         └──────────────┬───────────────┘  │
│           │                                  │                   │
│           │     ┌────────────────────┐       │                   │
│           └────→│ EnderChannelRegistry│◄─────┘                   │
│                 │  (全局单例)         │                          │
│                 │  Map<频道, ChannelData>                        │
│                 │  ChannelData {                                 │
│                 │    entries: List<EnderChannelEntry>            │
│                 │    nextIndex: int (轮询指针)                   │
│                 │  }                                             │
│                 └────────────────────┘                           │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心数据结构

### 2.1 EnderChannelEntry — 路由条目

```java
public record EnderChannelEntry(
    String itemType,                    // 物品注册名，如 "minecraft:diamond"
    ResourceKey<Level> sourceDim,       // 源容器所在维度
    BlockPos sourcePos,                 // 源容器方块位置（玩家背包时为 null）
    int sourceSlot,                     // 源物品在容器中的槽位索引
    int registrarSlot,                  // 注册此路由的活漏斗所在槽位（用于清理）
    String containerKey                 // 容器唯一标识 key
)
```

路由条目**不存储物品本身**，只存储"指针"——指向源物品的位置。物品始终留在源容器中，由 pull 端的活漏斗负责实际提取。

### 2.2 EnderChannelRegistry — 全局路由表

```java
// 单例
private static final EnderChannelRegistry INSTANCE = new EnderChannelRegistry();

// 内部结构
private static class ChannelData {
    List<EnderChannelEntry> entries = new ArrayList<>();
    int nextIndex;  // 轮询指针，保证公平调度
}

private final Map<Integer, ChannelData> channels = new HashMap<>();
```

**关键方法**：

| 方法 | 复杂度 | 职责 |
|------|--------|------|
| `insert(channel, entry)` | O(n) | 注册路由条目（去重） |
| `contains(channel, entry)` | O(n) | 检查条目是否已存在（快速路径） |
| `peek(channel, filterState)` | O(n) | 轮询查看路由条目（不移除），支持物品过滤 |
| `remove(channel, entry)` | O(n) | 移除指定路由条目 |
| `removeByPositionAndSlot(channel, pos, slot)` | O(n) | 移除指定位置+槽位的路由 |
| `removeByPositionAndSlotFromAllChannels(pos, slot)` | O(k×n) | 跨频道清理指定位置+槽位的路由 |
| `removeStaleRoutes(pos, activeSlots)` | O(k×n) | 清理活漏斗被移走后的残留路由 |
| `onChunkUnload(level, chunkPos)` | O(k×n) | 区块卸载时清理该区块的路由 |

### 2.3 轮询调度算法

`peek()` 方法使用 `nextIndex` 指针实现公平调度：

```
频道1 entries: [钻石@容器A, 铁锭@容器B, 石头@容器C]
                ↑ nextIndex

第1次 peek → 返回钻石@容器A, nextIndex → 1
第2次 peek → 返回铁锭@容器B, nextIndex → 2
第3次 peek → 返回石头@容器C, nextIndex → 0
第4次 peek → 返回钻石@容器A, nextIndex → 1
```

**目的**：多 push 端场景下，每个 push 端的物品都有机会被提取，不会出现排在前面的条目被反复提取而排在后面的永远拿不到的情况。

---

## 3. Push 端流程（注册路由）

### 3.1 触发条件

活漏斗的方向配置为 `sourceOffset → targetOffset`，其中 targetOffset 指向活末影箱所在的槽位。

在 `ItemTransferComponent.executeTransfer()` 中：
```java
SlotAccessor target = SlotAccessorFactory.create(server, containerCtx, targetSlot,
    null, transferredTargetSlots);

if (target instanceof LivingEnderChestAccessor enderChest) {
    // push端：注册路由
    enderChest.registerRoute(sourceStackForRoute, containerCtx, sourceSlot, hostSlot);
    return true;  // 触发冷却
}
```

### 3.2 registerRoute() 详细流程

```
registerRoute(sourceStack, containerCtx, sourceSlot, hostSlot)
  │
  ├─ 验证：sourceStack 非空、level 非空、pos 或 containerKey 有效
  │
  ├─ 构建 EnderChannelEntry：
  │     itemType = "minecraft:diamond"（从 sourceStack 获取）
  │     sourceDim = containerCtx.getLevel().dimension()
  │     sourcePos = containerCtx.getBlockPos()
  │     sourceSlot = 源物品在容器中的槽位
  │     registrarSlot = hostSlot（活漏斗所在槽位）
  │     containerKey = containerCtx.getContainerKey()
  │
  ├─ contains(channel, entry) → 快速路径：
  │     如果当前频道已存在相同条目 → return（跳过）
  │     （优化：避免每 tick 都删了又加，节省 O(k×n) 遍历）
  │
  ├─ removeByPositionAndSlotFromAllChannels(pos, sourceSlot)：
  │     清理所有频道中同位置+槽位的旧路由
  │     （频道可能已改变，防止旧频道残留路由）
  │
  └─ insert(channel, entry)：
        去重插入到当前频道
```

### 3.3 insert() 去重逻辑

```java
for (EnderChannelEntry existing : data.entries) {
    if (Objects.equals(existing.sourcePos(), entry.sourcePos())
        && existing.sourceSlot() == entry.sourceSlot()
        && existing.itemType().equals(entry.itemType())
        && Objects.equals(existing.containerKey(), entry.containerKey())) {
        return false;  // 重复条目，跳过
    }
}
data.entries.add(entry);
return true;
```

**去重规则**：相同位置 + 相同槽位 + 相同物品类型 + 相同容器 key → 视为重复，不添加。

---

## 4. Pull 端流程（无线提取）

### 4.1 触发条件

活漏斗的方向配置为 `sourceOffset → targetOffset`，其中 sourceOffset 指向活末影箱所在的槽位。

在 `ItemTransferComponent.executeTransfer()` 中：
```java
SlotAccessor source = SlotAccessorFactory.create(server, containerCtx, sourceSlot,
    filterState, transferredTargetSlots);

if (source instanceof LivingEnderChestAccessor) {
    // pull端：从路由表提取
    doTransfer(source, target, Math.min(stackSize, maxTransfer));
    return true;  // 触发冷却
}
```

### 4.2 extract() 详细流程

```
extract(amount, filterType)
  │
  └─ 循环（直到提取成功或路由表为空）：
       │
       ├─ registry.peek(channel, filterState)：
       │     轮询获取路由条目
       │     ├─ 无 filterState → 直接取 nextIndex 指向的条目
       │     └─ 有 filterState → 遍历找第一个匹配过滤器的条目
       │
       ├─ entry == null → 路由表为空 → return ItemStack.EMPTY
       │
       ├─ 验证源维度：
       │     server.getLevel(entry.sourceDim())
       │     ├─ null → 维度无效 → remove 路由 → continue
       │
       ├─ 获取源容器 IItemHandler：
       │     ├─ sourcePos 非 null（方块容器）：
       │     │   ├─ 区块未加载 → remove 路由 → continue
       │     │   └─ getHandler(level, pos, be) → IItemHandler
       │     └─ sourcePos 为 null（玩家背包）：
       │         └─ 通过 containerKey 获取玩家 IItemHandler
       │
       ├─ 验证源物品：
       │     ├─ sourceStack.isEmpty() → 源空 → remove 路由 → continue
       │     ├─ 物品类型不匹配 → remove 路由 → continue
       │     └─ 过滤器不匹配 → remove 路由 → continue
       │
       ├─ 提取物品：
       │     extracted = sourceHandler.extractItem(sourceSlot, toExtract, false)
       │
       ├─ 保存回滚信息：
       │     rollbackDim, rollbackPos, rollbackSlot, rollbackContainerKey
       │
       ├─ 源槽位变空 → remove 路由
       │
       └─ return extracted
```

### 4.3 提取验证链

`extract()` 在提取前会进行多层验证，任何一层失败都会**移除路由条目**并继续尝试下一个：

```
验证链（任一失败 → remove 路由 → continue）：
  ① 维度有效性
  ② 区块加载状态
  ③ IItemHandler 可用性
  ④ 源槽位非空
  ⑤ 物品类型匹配
  ⑥ 过滤器匹配
```

这确保了路由表始终保持干净，不会有"僵尸路由"指向无效位置。

---

## 5. 路由生命周期

### 5.1 路由的出生与死亡

```
┌─────────────────────────────────────────────────────────────────┐
│                        路由生命周期                              │
│                                                                 │
│  [出生] registerRoute()  ← push端活漏斗检测到物品               │
│     │                                                            │
│     ├── 正常提取 → extract() 成功 → 源槽位变空 → remove() → [死亡]│
│     │                                                            │
│     ├── 源容器区块卸载 → onChunkUnload() → [死亡]                │
│     │                                                            │
│     ├── 活漏斗被移走 → removeStaleRoutes() → [死亡]              │
│     │                                                            │
│     ├── 活末影箱频道改变 → removeByPositionAndSlotFromAllChannels│
│     │                     → registerRoute() 新频道 → [重生]       │
│     │                                                            │
│     └── 提取验证失败 → remove() → [死亡]                         │
│         （维度无效/区块卸载/物品消失/类型不匹配/过滤器不匹配）     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 路由的"重生"（频道改变）

当活末影箱的堆叠数改变时（频道改变），`registerRoute()` 会调用 `removeByPositionAndSlotFromAllChannels()` 清理旧频道中的残留路由，然后在当前频道中重新注册：

```
堆叠数 3 → 堆叠数 4（频道 3 → 频道 4）
  registerRoute():
    ① contains(4, entry) → false（频道4没有）
    ② removeByPositionAndSlotFromAllChannels(pos, slot) → 清理频道3的旧路由
    ③ insert(4, entry) → 在频道4注册新路由
```

---

## 6. 冷却机制

### 6.1 冷却触发规则

| 场景 | 是否触发冷却 | 原因 |
|------|-------------|------|
| push端：源有物品，注册路由成功 | ✅ 触发 | 让push端歇一歇，等pull端提取 |
| push端：源有物品，路由已存在（contains=true） | ❌ 不触发 | contains快速返回，不走return true |
| push端：源为空 | ❌ 不触发 | sourceStack.isEmpty() 提前返回 false |
| pull端：路由表有物品，提取成功 | ✅ 触发 | 正常传输 |
| pull端：路由表为空 | ✅ 触发 | 走 doTransfer→返回false，但 executeTransfer 返回 true |
| 普通传输：源空或目标满 | ❌ 不触发 | doTransfer 返回 false |

### 6.2 冷却独立性

**每个活漏斗有自己的 ComponentState，冷却完全独立：**

```
push端活漏斗的 ComponentState:
  transfer_cooldown: 8  ← 只影响push端自己

pull端活漏斗的 ComponentState:
  transfer_cooldown: 8  ← 只影响pull端自己
```

两个活漏斗的冷却互不干扰。不存在"共享冷却"问题——每个活漏斗只有一个方向（source→target），一次只做一件事。

### 6.3 计算逻辑

```java
private int calculateCooldown(int baseCooldown, int stackSize) {
    return baseCooldown;  // 默认 8 ticks
}
```

当前版本堆叠加速逻辑已暂时注销，所有活漏斗统一使用 8 ticks 基础冷却。

---

## 7. 频道隔离

### 7.1 频道号 = 堆叠数

```java
// SlotAccessorFactory.create() 中
int channel = stack.getCount();  // 堆叠数就是频道号
return new LivingEnderChestAccessor(server, channel, filterState, transferredTargetSlots);
```

**频道隔离规则**：
- 堆叠数为 1 的活末影箱 → 频道 1
- 堆叠数为 3 的活末影箱 → 频道 3
- 堆叠数为 64 的活末影箱 → 频道 64

不同频道的活末影箱**完全隔离**，互不干扰。这意味着：
- 频道 1 的 push 端只能被频道 1 的 pull 端提取
- 频道 3 的 push 端只能被频道 3 的 pull 端提取

### 7.2 频道本意

频道隔离的设计意图是让玩家可以通过调整堆叠数来创建独立的传输网络。例如：
- 频道 1：钻石传输网络
- 频道 2：铁锭传输网络
- 频道 3：石头传输网络

不同网络互不干扰，即使它们都使用相同的源容器。

---

## 8. 路由清理策略

### 8.1 清理时机

| 触发时机 | 清理范围 | 调用方法 |
|---------|---------|---------|
| 提取后源槽位变空 | 当前频道 | `registry.remove(channel, entry)` |
| 提取验证失败 | 当前频道 | `registry.remove(channel, entry)` |
| 频道改变 | 所有频道 | `removeByPositionAndSlotFromAllChannels` |
| 活漏斗被移走 | 所有频道 | `removeStaleRoutes` |
| 区块卸载 | 所有频道 | `onChunkUnload` |

### 8.2 注册时跨频道清理

在 `registerRoute()` 中，**先删后加**：

```java
// 必须先清理所有频道中的旧路由
registry.removeByPositionAndSlotFromAllChannels(pos, sourceSlot);
// 再在当前频道插入新路由
registry.insert(channel, entry);
```

**为什么需要跨频道清理？** 因为活末影箱的堆叠数可能改变（频道改变），旧频道路由如果不清理，pull 端会从旧频道提取到已失效的路由。

### 8.3 活漏斗移走清理

当活漏斗从容器中移除后，`removeStaleRoutes()` 清理该漏斗注册的所有路由：

```java
public void removeStaleRoutes(BlockPos sourcePos, Set<Integer> activeRegistrarSlots) {
    removeStaleRoutesInternal(route ->
        Objects.equals(route.sourcePos(), sourcePos)
        && !activeRegistrarSlots.contains(route.registrarSlot()));
}
```

通过 `registrarSlot` 字段判断：如果路由的注册者（活漏斗）不在当前容器的活跃槽位中，说明活漏斗已被移走，路由应清理。

---

## 9. 区块卸载处理

### 9.1 触发机制

在 `LivingItem.onChunkUnload()` 中注册事件监听：

```java
@SubscribeEvent
public void onChunkUnload(ChunkEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel serverLevel) {
        EnderChannelRegistry.getInstance()
            .onChunkUnload(serverLevel, event.getChunk().getPos());
    }
}
```

### 9.2 清理逻辑

```java
public void onChunkUnload(Level level, ChunkPos chunkPos) {
    // 遍历所有频道的所有条目
    for (ChannelData data : channels.values()) {
        data.entries.removeIf(entry -> {
            // 只清理同维度、同区块的源容器路由
            if (!entry.sourceDim().equals(dim)) return false;
            BlockPos pos = entry.sourcePos();
            if (pos == null) return false;
            return pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
                && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ;
        });
    }
    // 清理空频道
    channels.entrySet().removeIf(e -> e.getValue().entries.isEmpty());
}
```

**为什么需要？** 源容器所在区块卸载后，pull 端的 `extract()` 无法访问源容器，必须清理路由避免无效提取。

---

## 10. 已知问题与修复记录

### 10.1 已修复：push端源耗尽后pull端不再拉取

**问题描述**：
push端源容器物品耗尽后，pull端输出槽还能输出但不再拉取新物品。

**根因分析**：
1. push端源物品耗尽 → `sourceStack.isEmpty()` → 返回 false → 不设置冷却
2. pull端路由表为空 → `extract()` 返回 EMPTY → `doTransfer` 返回 false → 但 `executeTransfer` 返回 true → 设置冷却 8 ticks
3. pull端陷入"空路由表→触发冷却→等冷却→再试→空路由表→再触发冷却"的死循环

**修复方案**：
玩家往源容器补物品后，push端下一tick检测到物品 → `registerRoute()` 注册路由 → pull端冷却到期后成功提取。系统可自动恢复，但最坏延迟 8 ticks。

**优化建议**（未实施）：
将 pull 端从 `return true` 改为 `return doTransfer(...)`，路由表为空时不触发冷却，实现零延迟恢复。

### 10.2 已修复：registerRoute 每 tick 重复删加

**问题描述**：
push端每 tick 冷却到期后，`registerRoute()` 都会执行 `removeByPositionAndSlotFromAllChannels`（遍历所有频道 O(k×n)）+ `insert`（遍历当前频道 O(n)），即使路由已存在。

**修复方案**：
添加 `contains()` 方法作为快速路径检查。如果当前频道已存在相同条目，直接跳过，避免不必要的跨频道遍历。

```java
// 修复后
if (registry.contains(channel, entry)) {
    return;  // 快速跳过
}
```

### 10.3 已修复：频道改变后旧路由残留

**问题描述**：
修改活末影箱堆叠数（频道改变）后，旧频道中的路由条目未被清理，导致 pull 端可能从旧频道提取到失效路由。

**修复方案**：
`registerRoute()` 中调用 `removeByPositionAndSlotFromAllChannels()` 跨频道清理旧路由。

---

## 11. 调试指南

### 11.1 查看路由表状态

```java
// 获取频道大小
int size = EnderChannelRegistry.getInstance().getChannelSize(channel);

// 获取活跃频道数
int count = EnderChannelRegistry.getInstance().getActiveChannelCount();

// 清空所有路由（调试用）
EnderChannelRegistry.getInstance().clearAll();
```

### 11.2 关键日志

所有关键日志使用 SLF4J，通过 `LogUtils.getLogger()` 获取：

```java
// 路由注册
LOGGER.debug("registered route channel={}, item={}, pos={}, key={}, slot={}, count={}");

// 物品提取
LOGGER.info("extracted channel={}, item={}, count={}, from={}, slot={}");

// 路由为空
LOGGER.trace("extract channel={}, no entry found");

// 路由表轮询
LOGGER.trace("peek channel={}, no filter, index={} → {}");

// 区块卸载
LOGGER.info("chunkUnload dim={}, chunk=({},{}), removed={} entries");
```

### 11.3 常见问题排查

| 问题 | 排查方向 |
|------|---------|
| pull端不拉取 | 检查路由表是否为空（`getChannelSize`），检查 push 端是否正常注册路由 |
| 物品类型不匹配 | 检查 `extract()` 中 `itemType` 是否一致，源容器物品是否被替换 |
| 跨维度不工作 | 确认源区块和目标区块都已加载 |
| 频道隔离不生效 | 确认两个活末影箱的堆叠数是否一致 |
| 路由残留 | 调用 `clearAll()` 清理，检查活漏斗是否被正确移除 |