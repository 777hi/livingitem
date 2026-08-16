# Living Ender Chest (活末影箱) 技术文档

> **文档版本**: 2026.08 v11  
> **最后更新**: 2026-08-16  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [核心数据结构](#2-核心数据结构)
3. [双模式工作机制](#3-双模式工作机制)
4. [Push 端流程（注册路由）](#4-push-端流程注册路由)
5. [Pull 端流程（无线提取）](#5-pull-端流程无线提取)
6. [路由生命周期](#6-路由生命周期)
7. [冷却机制](#7-冷却机制)
8. [频道隔离](#8-频道隔离)
9. [路由清理策略](#9-路由清理策略)
10. [区块卸载处理](#10-区块卸载处理)
11. [Tooltip 显示](#11-tooltip-显示)
12. [已知问题与修复记录](#12-已知问题与修复记录)
13. [调试指南](#13-调试指南)

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

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingEnderChestFunction` | `domain/ender/LivingEnderChestFunction.java` | 活末影箱功能入口，实现 `LivingItemFunction` 接口，管理玩家绑定数据和路由清理 |
| `LivingEnderChestAccessor` | `domain/ender/LivingEnderChestAccessor.java` | 活末影箱槽位访问器，实现 extract/insert/rollback，支持路由模式和直连模式 |
| `EnderRouteManager` | `domain/ender/EnderRouteManager.java` | 路由逻辑集中管理，路由注册/提取/验证/同通道防护/偏好类型/直连模式 |
| `EnderChannelRegistry` | `domain/ender/EnderChannelRegistry.java` | 全局路由表单例（服务端），维护频道→路由条目列表的映射，轮询调度，路由清理 |
| `EnderChannelClientCache` | `domain/ender/EnderChannelClientCache.java` | 客户端路由缓存，存储频道快照供 Tooltip 读取（ConcurrentHashMap，线程安全） |
| `EnderChannelSyncPacket` | `network/EnderChannelSyncPacket.java` | S2C 同步包，将路由快照从服务端发送到客户端 |
| `EnderChannelEntry` | `domain/ender/EnderChannelEntry.java` | 路由条目 record，描述源物品的"指针"，支持方块容器和玩家背包两种类型 |
| `SlotAccessorFactory` | `transfer/SlotAccessorFactory.java` | 工厂类，检测到活末影箱时创建 LivingEnderChestAccessor |
| `CrossContainerTransfer` | `domain/hopper/CrossContainerTransfer.java` | 跨容器传输工具类，处理相邻容器与活末影箱之间的路由注册和物品拉取 |
| `TransferPipeline` | `domain/hopper/TransferPipeline.java` | 统一传输入口，通过 EnderRouteManager 注册路由 |
| `LivingHopperFunction` | `domain/hopper/LivingHopperFunction.java` | 活漏斗功能入口，通过 TransferPipeline 检测活末影箱并分发到路由注册/提取逻辑 |

### 1.4 组件架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                    LivingEnderChestFunction                      │
│                    (功能入口 · 实现 LivingItemFunction)           │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  活末影箱本身不执行传输逻辑！路由和传输由活漏斗的                    │
│  TransferPipeline.execute() 触发，通过 LivingEnderChestAccessor   │
│  和 EnderRouteManager 完成。                                     │
│                                                                  │
│  tick() 中仅做路由清理：                                          │
│    - validateRoutes(context, activeRegistrarSlots, activeTargetSlots) │
│      一次性验证注册者/目标/源物品三项有效性                        │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                TransferPipeline.execute()                        │
│                (统一传输入口 · 分发)                              │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  target instanceof LivingEnderChestAccessor?             │    │
│  │  ├─ YES → push端：EnderRouteManager.registerRoute()      │    │
│  │  │        → return true                                  │    │
│  │  └─ NO  → 继续                                          │    │
│  │                                                          │    │
│  │  source instanceof LivingEnderChestAccessor?             │    │
│  │  ├─ YES → pull端：extract() → 从路由表查找 → 跳转提取    │    │
│  │  └─ NO  → 普通传输：SlotAccessor.transfer()              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────────────────┐  │
│  │ EnderRouteManager│         │ extract()                    │  │
│  │ .registerRoute() │         │ "读取路由表 → 跳转 → 提取"   │  │
│  │ "写入路由表"      │         │                              │  │
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
    BlockPos sourcePos,                 // 源容器方块位置
    int sourceSlot,                     // 源物品在容器中的槽位索引
    int registrarSlot,                  // 注册此路由的活漏斗所在槽位（用于清理）
    String containerKey,                // 源容器唯一标识 key
    int targetSlot,                     // 活末影箱所在槽位（真实槽位，用于清理关联）
    String registrarContainerKey        // 注册者容器的唯一标识 key（用于跨容器路由清理）
)
```

路由条目**不存储物品本身**，只存储"指针"——指向源物品的位置。物品始终留在源容器中，由 pull 端的活漏斗负责实际提取。

### 2.2 EnderChannelRegistry — 全局路由表

```java
// 单例
private static final EnderChannelRegistry INSTANCE = new EnderChannelRegistry();

// 内部结构
private final Map<Integer, ChannelData> channels = new HashMap<>();

// 反向索引（v5 新增）
private final Map<BlockPos, List<EnderChannelEntry>> posIndex = new HashMap<>();     // 方块位置 → 路由条目
private final Map<String, List<EnderChannelEntry>> keyIndex = new HashMap<>();       // 容器 key → 路由条目
private final Map<String, List<EnderChannelEntry>> registrarKeyIndex = new HashMap<>(); // 注册者容器 key → 路由条目（v9 新增）

// 延迟同步（v6 新增）
private final Set<Integer> dirtyChannels = new HashSet<>();                          // 本 tick 内被修改的频道集合
```

**反向索引**（v5 新增）：

路由注册/移除时同步维护 `posIndex`、`keyIndex` 和 `registrarKeyIndex` 三个反向索引，清理时直接查询相关路由，不需要遍历所有频道：

| 索引 | 键 | 用途 |
|------|-----|------|
| `posIndex` | `BlockPos` | 按方块位置快速查找相关路由 |
| `keyIndex` | `String`（容器 key） | 按容器标识快速查找相关路由（玩家背包等无 BlockPos 的场景） |
| `registrarKeyIndex` | `String`（注册者容器 key） | 按注册者容器标识快速查找相关路由（v9 新增） |

**性能优化**：`validateRoutes()` 使用反向索引后，时间复杂度从 O(所有频道) 优化到 O(相关路由)。

**关键方法**：

| 方法 | 职责 |
|------|------|
| `offer(channel, entry)` | 注册路由条目（去重），同步更新反向索引 |
| `contains(channel, entry)` | 检查条目是否已存在（快速路径） |
| `peek(channel, filterData)` | 轮询查看路由条目（不移除），支持物品过滤 |
| `peek(channel, filterData, preferredItemType)` | 贪心查看：优先返回匹配偏好类型的条目（v10 新增） |
| `poll(channel)` | 从头部取出路由条目 |
| `poll(channel, preferredItemType)` | 贪心取出：优先取出匹配偏好类型的条目（v10 新增） |
| `reoffer(channel, entry)` | 将条目放回尾部（源物品还有剩余时） |
| `remove(channel, entry)` | 移除指定路由条目，同步更新反向索引 |
| `removeByPositionAndSlot(channel, pos, slot)` | 移除指定位置+槽位的路由 |
| `validateRoutes(context, activeRegistrarSlots, activeTargetSlots)` | 统一路由验证：一次遍历完成注册者/目标/源物品三项检查（v9 新增，替代旧版 5 个独立清理方法） |
| `onChunkUnload(level, chunkPos)` | 区块卸载时清理该区块所有相关路由 |
| `flushDirtyChannels()` | 延迟同步：遍历 dirty 频道统一发包后清空（由 `ContainerLivingItemHandler.processContext()` 在 tick 末尾调用） |

### 2.3 ChannelData — 频道数据

```java
private static class ChannelData {
    List<EnderChannelEntry> entries = new ArrayList<>();
    int nextIndex;  // 轮询指针，保证公平调度
}
```

---

## 3. 双模式工作机制

### 3.1 模式概述

活末影箱支持两种工作模式，通过**玩家绑定机制**自动切换：

| 模式 | 触发条件 | 数据流向 | 路由表 |
|------|---------|---------|--------|
| **路由模式** | 无绑定玩家 | 通过全局路由表跨容器无线传输 | ✅ 使用 |
| **直连模式** | 有绑定玩家 | 直接读写绑定玩家的末影箱背包 | ❌ 跳过 |

### 3.2 玩家绑定机制

**绑定触发**：玩家在末影箱 GUI 中，光标持有末影箱物品，点击活按钮 → 末影箱被活化并绑定当前玩家。

绑定数据存储在活末影箱物品的 DataComponent 中（通过 `LivingEnderChestData` + `EnderChannelData` record）：

```java
// EnderChannelData — 绑定玩家数据
public record EnderChannelData(
    Optional<String> boundPlayerUuid,   // 绑定玩家 UUID（字符串形式）
    Optional<String> boundPlayerName    // 绑定玩家名称
) {
    public static final EnderChannelData EMPTY = new EnderChannelData(Optional.empty(), Optional.empty());

    public EnderChannelData withBoundPlayer(UUID uuid, String name) {
        return new EnderChannelData(Optional.of(uuid.toString()), Optional.of(name));
    }

    public Optional<UUID> getPlayerUuid() {
        return boundPlayerUuid.map(UUID::fromString);
    }
}

// LivingEnderChestData — 活末影箱数据容器
public record LivingEnderChestData(EnderChannelData channel) implements TooltipProvider {
    public static final LivingEnderChestData EMPTY = new LivingEnderChestData(EnderChannelData.EMPTY);
    public LivingEnderChestData withChannel(EnderChannelData c) { return new LivingEnderChestData(c); }
}
```

> **v5 变更**：`EnderChannelData` 的 `boundPlayerUuid` 字段类型从 `Optional<UUID>` 改为 `Optional<String>`，序列化时直接存储 UUID 字符串，避免 Codec 兼容性问题。通过 `getPlayerUuid()` 方法在运行时转换回 `UUID`。

### 3.3 访问器工厂分发

`SlotAccessorFactory.create()` 根据绑定状态创建不同类型的 `LivingEnderChestAccessor`：

```java
if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
    int ch = stack.getCount();
    UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(stack);
    if (boundUuid != null) {
        // 直连模式：传入绑定 UUID
        return new LivingEnderChestAccessor(server, ch, filterData, transferredTargetSlots, boundUuid);
    }
    // 路由模式：无绑定 UUID
    return new LivingEnderChestAccessor(server, ch, filterData, transferredTargetSlots);
}
```

### 3.4 直连模式数据流

直连模式下，活漏斗通过 `LivingEnderChestAccessor` 直接与玩家末影箱背包交互：

```
┌──────────────────────────────────────────────────────────────┐
│                    直连模式数据流                              │
│                                                              │
│  活漏斗 → LivingEnderChestAccessor                           │
│                       │                                      │
│                       ├─ extract() → 获取玩家在线状态         │
│                       │   ├─ 在线 → 读取 PlayerEnderChestContainer │
│                       │   └─ 离线 → 返回 EMPTY（跳过）        │
│                       │                                      │
│                       └─ insert() → 写入玩家末影箱            │
│                           ├─ 在线 → 写入背包                  │
│                           └─ 离线 → 返回 0（跳过）            │
│                                                              │
│  注意：直连模式完全跳过 EnderChannelRegistry！                │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. Push 端流程（注册路由）

### 4.1 容器内路由注册

当活漏斗的 target 是活末影箱（路由模式）时，在 `TransferPipeline.execute()` 中触发：

```java
// TransferPipeline.execute()
if (target.unwrap() instanceof LivingEnderChestAccessor enderChest) {
    if (enderChest.isDirectMode()) {
        // 直连模式 → 直接传输
        return SlotAccessor.transfer(source, target, amount);
    }
    // 路由模式 → 注册路由（通过 EnderRouteManager）
    ItemStack srcStack = ctx.getItem(sourceSlot);
    if (!srcStack.isEmpty() && !LivingItemManager.isLivingItem(srcStack)) {
        EnderRouteManager.registerRoute(srcStack, ctx, sourceSlot, hostSlot, targetSlot);
    }
    return true;
}
```

### 4.2 跨容器路由注册

当活漏斗的 source 越界（从相邻容器拉取）、target 是活末影箱时，通过 `CrossContainerTransfer` 处理：

```
TransferPipeline.execute()
  └─ sourceOutOfBounds → CrossContainerTransfer
      └─ pullFromNeighbor()
          └─ target 是活末影箱 → pullFromNeighborToLivingEnderChest()
              ├─ 有绑定 UUID → 直连模式提取
              └─ 无绑定 → 路由模式
                  ├─ 遍历相邻容器槽位，找到可传输物品
                  └─ registry.insert(channel, EnderChannelEntry(
                         itemType, sourceDim, sourcePos, sourceSlot,
                         registrarSlot, containerKey, -1,  // targetSlot = -1
                         registrarContainerKey
                     ))
```

**targetSlot 设计**：路由条目记录活末影箱所在的真实槽位，用于 `validateRoutes()` 判断活末影箱是否被移走。跨容器场景下活末影箱不在当前容器中，`CrossContainerTransfer` 传入实际的 `targetSlot`。

---

## 5. Pull 端流程（无线提取）

### 5.1 提取流程

当活漏斗的 source 是活末影箱（路由模式）时：

```
TransferPipeline.execute()
  └─ source 是 LivingEnderChestAccessor
      └─ enderChest.extract(amount, filterType)
          ├─ 从 EnderChannelRegistry 查找匹配路由
          ├─ peek(channel, filterState) → 轮询获取路由条目
          ├─ 根据路由条目中的 sourceDim + sourcePos + sourceSlot
          │   跳转到源容器，提取物品
          ├─ 提取成功 → 移除路由条目
          └─ 提取失败 → 路由条目保留（下次重试）
```

### 5.2 轮询调度与贪心提取

`EnderChannelRegistry` 使用 `Deque` 的 `poll`/`reoffer` 实现轮询公平调度：取出头部 → 验证 → 有效则提取，源还有物品则放回尾部。

**默认轮询行为**：铁锭、金锭交替提取，保证公平。

**贪心提取策略**（v10 新增）：当输出槽已有物品时，优先提取相同类型的物品（可堆叠），避免轮询到不同类型导致传输停止。

```
场景：push端铁锭+金锭，pull端输出槽已有铁锭

轮询模式（旧）：
  Tick 1: 提取铁锭 → 输出槽=铁锭
  Tick 2: 轮询到金锭 → 无法堆叠 → 传输停止 ❌
  （必须拿走铁锭才能继续）

贪心模式（新）：
  Tick 1: 提取铁锭 → 输出槽=铁锭
  Tick 2: 偏好铁锭 → 继续提取铁锭 → 堆叠 ✅
  Tick 3: 铁锭满了/没了 → 回退轮询 → 提取金锭
```

实现方式：

1. `TransferPipeline.execute()` 创建 source accessor 后，检查 target 槽位物品类型
2. 如果 target 槽有物品，将物品注册名设置到 `LivingEnderChestAccessor.preferredItemType`
3. `EnderChannelRegistry.peek(channel, filterData, preferredItemType)` 优先返回匹配偏好类型的条目
4. `EnderChannelRegistry.poll(channel, preferredItemType)` 优先取出匹配偏好类型的条目
5. 找不到匹配条目时，回退到正常轮询（头部条目）

```java
// TransferPipeline.execute()
if (source.unwrap() instanceof LivingEnderChestAccessor sourceEnder && !sourceEnder.isDirectMode()) {
    ItemStack targetStack = ctx.getItem(targetSlot);
    if (!targetStack.isEmpty()) {
        sourceEnder.setPreferredItemType(
            BuiltInRegistries.ITEM.getKey(targetStack.getItem()).toString());
    }
}

// EnderChannelRegistry.peek()
public EnderChannelEntry peek(int channel, FilterData filterData, String preferredItemType) {
    // 1. 优先查找偏好类型
    if (preferredItemType != null) {
        EnderChannelEntry preferred = findPreferred(data, filterData, preferredItemType);
        if (preferred != null) return preferred;
    }
    // 2. 找不到 → 回退正常轮询
    return filterData == null ? data.entries.peekFirst() : ...;
}
```

---

## 6. 路由生命周期

### 6.1 路由注册

```
活漏斗 tick → TransferPipeline.execute() → target=活末影箱
  └─ EnderRouteManager.registerRoute()
      └─ EnderChannelRegistry.insert(channel, entry)
          ├─ 检查重复（contains）
          └─ 添加到 channels[channel].entries
```

### 6.2 路由消费

```
活漏斗 tick → TransferPipeline.execute() → source=活末影箱
  └─ LivingEnderChestAccessor.extract()
      └─ EnderChannelRegistry.peek(channel, filter)
          ├─ 找到匹配路由 → 跳转提取
          └─ 提取成功 → remove(entry)
```

### 6.3 路由清理

路由在以下情况被清理（详见第 9 章）：
- 源物品被移走或改变
- 活漏斗被移走
- 活末影箱被移走
- 容器区块卸载
- 注册者容器区块卸载

---

## 7. 冷却机制

活末影箱本身没有冷却机制。冷却由活漏斗管理（详见活漏斗文档）。

---

## 8. 频道隔离

### 8.1 频道定义

频道号 = 活末影箱物品的堆叠数量：

```java
int channel = enderChestStack.getCount();
```

不同堆叠数的活末影箱属于不同频道，路由互不干扰。

### 8.2 频道用途

- 同一频道内的活末影箱共享路由表
- 不同频道的活末影箱完全隔离
- 玩家可以通过堆叠/拆分活末影箱来切换频道

---

## 9. 路由清理策略

### 9.1 统一验证方法（v9 重构）

路由清理统一通过 `EnderChannelRegistry.validateRoutes()` 完成，替代旧版 5 个独立清理方法：

```java
// LivingHopperFunction.cleanupStaleRoutes()
Set<Integer> activeEnderChestSlots = tick.getFunctionSlots("living_ender_chest");
registry.validateRoutes(context, activeSlots, activeEnderChestSlots);

// LivingEnderChestFunction.tick()
Set<Integer> activeHopperSlots = tick.getFunctionSlots("living_hopper");
registry.validateRoutes(context, activeHopperSlots, activeEnderChestSlots);
```

**参数说明**：

| 参数 | 含义 | null 行为 |
|------|------|----------|
| `context` | 容器上下文，提供容器位置和物品读取 | 不可为 null |
| `activeRegistrarSlots` | 当前容器中活漏斗所在槽位集合 | null = 跳过注册者检查 |
| `activeTargetSlots` | 当前容器中活末影箱所在槽位集合 | null = 跳过目标检查 |

**内部流程**：

```
validateRoutes(context, activeRegistrarSlots, activeTargetSlots)
    │
    ├─ 通过 3 个反向索引收集相关路由（posIndex + keyIndex + registrarKeyIndex）
    ├─ 去重后遍历
    │
    ├─ 检查1: 注册者还在吗？  registrarSlot ∈ activeRegistrarSlots?
    ├─ 检查2: 目标还在吗？    targetSlot ∈ activeTargetSlots?
    ├─ 检查3: 源物品还在吗？  context.getItem(sourceSlot) 匹配?
    │
    └─ 任一不满足 → 删除路由
```

**槽位来源**：`TickContext.functionSlots` 缓存，由 `processContext()` 分组时一次性填充，功能类 O(1) 读取，无需遍历容器。

### 9.2 清理场景

| 场景 | 触发方 | activeRegistrarSlots | activeTargetSlots | 效果 |
|------|--------|---------------------|-------------------|------|
| 活漏斗被拿走 | `LivingEnderChestFunction.tick()` | 活漏斗槽位（可能为空集） | 活末影箱槽位 | 空集 → 所有路由的注册者不在 → 清理 |
| 活末影箱被拿走 | `LivingHopperFunction.cleanupStaleRoutes()` | 活漏斗槽位 | 活末影箱槽位（可能为空集） | 空集 → 所有路由的目标不在 → 清理 |
| 源物品消失 | 两者均可 | — | — | 检查3 → 源槽空或类型不匹配 → 清理 |
| 区块卸载 | `onChunkUnload()` | — | — | 独立方法，不经过 validateRoutes |

### 9.3 容器隔离清理

`registrarContainerKey` 字段确保跨容器路由的清理只影响正确的容器：

```
容器A（注册者）          容器B（源）
┌──────────────┐       ┌──────────────┐
│ 活漏斗(槽3)  │       │ 钻石(槽0)    │
│ target=末影箱 │       │              │
└──────────────┘       └──────────────┘

路由条目：itemType="钻石", sourcePos=容器B, registrarSlot=3, registrarContainerKey=容器A

容器A区块卸载 → onChunkUnload → 清理所有 registrarContainerKey="容器A" 的路由
容器B区块卸载 → 不会误删容器A注册的路由
```

---

## 10. 区块卸载处理

### 10.1 源容器区块卸载

当源容器所在的区块被卸载时，`onChunkUnload()` 会清理该区块所有相关路由（通过 `posIndex` 反向索引查找）。

### 10.2 注册者容器区块卸载

当注册者容器所在的区块被卸载时，`onChunkUnload()` 会清理所有以该容器为注册者的路由（通过 `registrarKeyIndex` 反向索引查找），防止路由泄漏。

---

## 11. Tooltip 显示

### 11.1 显示内容

| 状态 | 显示内容 |
|------|---------|
| 有绑定玩家 | "绑定玩家: xxx"（紫色加粗） |
| 路由模式 | "频道: N" + "路由: X条/共Y条" |
| 高级模式（F3+H） | 每条路由的详细信息（物品类型、位置、槽位） |

### 11.2 实现

```java
@Override
public void addToTooltip(Item.TooltipContext context,
                         Consumer<Component> tooltipAdder,
                         TooltipFlag flag,
                         ItemStack stack) {
    LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
    EnderChannelData channel = data.channel();

    tooltipAdder.accept(Component.nullToEmpty(""));
    tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_chest.status"));

    if (channel.boundPlayerUuid().isPresent()) {
        // 直连模式
        String name = channel.boundPlayerName().orElse("???");
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.ender_chest.bound_player", name)
            .withStyle(style -> style.withColor(0xDD44FF).withBold(true)));
    } else {
        // 路由模式 — 从客户端缓存读取
        int ch = stack.getCount();
        var snapshot = EnderChannelClientCache.getSnapshot(ch);

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.ender_chest.channel", ch)
            .withStyle(style -> style.withColor(0xCC66FF)));
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.ender_chest.routes", snapshot.channelSize(), snapshot.totalRoutes())
            .withStyle(style -> style.withColor(0xAA88FF)));

        // 高级模式显示详细路由
        if (snapshot.channelSize() > 0 && flag.isAdvanced()) {
            for (var entry : snapshot.entries()) {
                String locStr;
                if (entry.sourcePos() != null) {
                    locStr = entry.sourcePos().toShortString();
                } else if (entry.dimKey() != null) {
                    locStr = entry.dimKey();
                } else {
                    locStr = "???";
                }
                tooltipAdder.accept(Component.literal(
                    "  " + entry.itemType() + " @" + locStr + " slot=" + entry.sourceSlot())
                    .withStyle(style -> style.withColor(0x9966CC).withItalic(true)));
            }
        }
    }
}
```

### 11.3 客户端缓存同步

路由信息通过 `EnderChannelSyncPacket`（S2C）从服务端同步到客户端 `EnderChannelClientCache`。

**v6 延迟同步机制**：

路由变化时不再立即发包，而是标记频道为 dirty，在 tick 末尾统一同步：

```
服务端 EnderChannelRegistry.insert/remove
  └─ syncChannelToAll(channel)
      └─ dirtyChannels.add(channel)              ← 仅标记 dirty，不发包

ContainerLivingItemHandler.processContext() 末尾
  └─ EnderChannelRegistry.flushDirtyChannels()
      └─ for each dirty channel:
          ├─ 构造 EnderChannelSyncPacket
          └─ 发送给所有在线玩家
              └─ 客户端 EnderChannelClientCache.update(channel, ...)
                  └─ Tooltip 读取 EnderChannelClientCache.getSnapshot(ch)
```

**为什么需要延迟同步？** 同一个 tick 内，push 端注册路由和 pull 端提取删除路由可能交替发生。如果每次变化都立即同步，客户端会收到"有路由→无路由→有路由"的闪烁序列，导致 Tooltip 闪烁。延迟同步将同一 tick 内的所有变化合并为一次同步（最终状态），消除闪烁。

`EnderChannelClientCache` 使用 `ConcurrentHashMap` 存储，确保网络线程写入和渲染线程读取的线程安全。

---

## 12. 已知问题与修复记录

### 12.1 跨容器路由 targetSlot 误绑定

**问题**：跨容器场景下 `targetSlot` 被设为活末影箱在当前容器中的槽位，容器刷新时路由被误清理。

**修复**：将跨容器场景下的 `targetSlot` 设为 `-1`，避免路由与活末影箱的具体槽位绑定。

> **v9 更新**：此问题已通过 `validateRoutes()` 统一验证方法彻底解决。`targetSlot` 现在始终记录真实槽位，`validateRoutes()` 通过 `activeTargetSlots` 参数判断活末影箱是否还在，不再依赖 `targetSlot >= 0` 的硬编码条件。

### 12.2 注册者容器路由泄漏

**问题**：注册者容器区块卸载时，跨容器路由未被清理，导致路由泄漏。

**修复**：新增 `registrarContainerKey` 字段和 `removeStaleRoutesByRegistrarKey()` 方法，实现按注册者容器隔离清理。

### 12.3 Tooltip 路由闪烁

**问题**：路由模式下 Tooltip 中路由条目频繁闪烁（消失又出现）。原因是 `syncChannelToAll()` 在每次路由变化时立即发包，同一 tick 内 push 端注册路由和 pull 端提取删除路由交替发生，客户端收到"有路由→无路由"的闪烁序列。

**修复**：引入延迟同步机制。`syncChannelToAll()` 不再立即发包，只标记频道为 dirty；`flushDirtyChannels()` 在 `ContainerLivingItemHandler.processContext()` 的 tick 末尾统一同步所有 dirty 频道。同一 tick 内的所有变化合并为一次同步（最终状态），消除闪烁。

---

## 13. 调试指南

### 13.1 查看路由表

在游戏中通过 Tooltip（F3+H 高级模式）查看当前频道的路由条目。

### 13.2 日志输出

启用 debug 日志可查看路由注册、消费和清理的详细记录：
```
EnderChannelComponent: cleaned N stale routes at tick T
```

### 13.3 常见问题排查

| 问题 | 可能原因 | 排查方法 |
|------|---------|---------|
| 物品不传输 | 频道不匹配 | 检查两端活末影箱堆叠数是否相同 |
| 路由丢失 | 区块卸载 | 检查源容器和注册者容器是否在同一区块 |
| 传输速度慢 | 轮询调度 | 正常现象，多个路由条目轮流调度 |
| 直连模式不工作 | 玩家离线 | 直连模式需要玩家在线 |

---

### 12.3 v5 架构变更 (2026-07-28)

**变更1：EnderChannelData 字段类型调整**

`boundPlayerUuid` 从 `Optional<UUID>` 改为 `Optional<String>`，序列化时直接存储 UUID 字符串，避免 Codec 兼容性问题。运行时通过 `getPlayerUuid()` 转换回 `UUID`。

**变更2：反向索引优化**

`EnderChannelRegistry` 新增 `posIndex`（BlockPos → 路由条目）和 `keyIndex`（容器 key → 路由条目）两个反向索引。路由注册/移除时同步维护，`cleanStaleSourceRoutes()` 使用反向索引后时间复杂度从 O(所有路由) 优化到 O(相关路由)。

**变更3：tick() 使用 entries 参数**

`LivingEnderChestFunction.tick()` 从遍历全容器查找活末影箱槽位改为直接从 `entries` 参数读取，消除冗余扫描。

**变更4：EnderChannelClientCache 线程安全**

客户端缓存使用 `ConcurrentHashMap` 替代普通 `HashMap`，确保网络线程写入和渲染线程读取的线程安全。

---

### 12.4 v6 架构变更 (2026-07-28)

**变更1：延迟同步机制（Deferred Sync）**

`syncChannelToAll()` 不再在每次路由变化时立即发包，只标记频道为 dirty。新增 `flushDirtyChannels()` 方法，由 `ContainerLivingItemHandler.processContext()` 在 tick 末尾统一同步所有 dirty 频道。

修复 Tooltip 路由闪烁问题：同一 tick 内 push 端注册路由和 pull 端提取删除路由交替发生时，延迟同步将所有变化合并为一次同步（最终状态），消除"有路由→无路由"的闪烁序列。

---

### 12.5 v7 架构变更：路由提取指针推进修正 (2026-07-28)

**问题**：无黑白名单时，`routeExtract()` 中 `peek()` 先推进指针再验证条目，失效条目被 `remove()` 时 `nextIndex` 再次调整，导致指针抖动，提取顺序出现"部分随机"现象。

**根因**：`peek()` 和 `remove()` 各自独立修改 `nextIndex`，两步操作非原子：

```
peek()  →  nextIndex = (oldIndex + 1) % size  （推进）
remove() →  if idx < nextIndex: nextIndex--    （回退）
```

失效条目被跳过时，指针经历了"推进→回退"的抖动，相邻条目可能被跳过。

**修复**：新增 `peekEntry()` 和 `advancePointer()` 方法，将"查看"和"推进"分离：

- `peekEntry(channel)` — 返回当前 `nextIndex` 位置的条目，不推进指针
- `advancePointer(channel)` — 推进 `nextIndex` 到下一个位置

`routeExtract()` 改为：先 `peekEntry()` 查看 → 验证有效性 → 无效则 `remove()`（`nextIndex` 自动修正）→ 有效则 `advancePointer()` + 提取。

**效果**：指针仅在条目验证通过后才推进，失效条目被 remove 时指针自然指向下一个条目，消除抖动。

### 12.6 v8 架构重构：Deque 替代 List+nextIndex (2026-07-28)

**背景**：v7 修复了指针抖动 bug，但 `List<Entry> + nextIndex` 的设计本质上是手动模拟轮询，
导致 `peekEntry()`/`advancePointer()`/`peek()` 三个方法协作管理一个指针，概念复杂且容易出错。

**重构**：用 `ArrayDeque` 天然实现轮询，消除手动指针管理。

| 改动前 | 改动后 |
|--------|--------|
| `List<Entry> + nextIndex` | `Deque<Entry>` |
| `peekEntry()` + `advancePointer()` | `poll()` 取出头部 |
| 保留条目 = 不动列表 | `reoffer()` 放回尾部 |
| 丢弃条目 = `remove()` + 指针调整 | `poll()` 已取出，不还回去 |
| `contains()` = O(N) 线性扫描 | `RouteKey` HashSet = O(1) |
| 清理 = 遍历+删除+指针调整 | 收集后删除，不触碰 Deque 迭代器 |
| `findChannelForEntry()` = O(C×N) | `entryToChannel` = O(1) |

**代码量变化**：~580 行 → ~500 行，消除 `nextIndex`、`findChannelForEntry()`、`removeEntryFromChannel()`。

**安全设计**：热路径（`poll`/`reoffer`/`offer`/`contains`）只做单元素操作，永不触发迭代器的 `ConcurrentModificationException`。
清理路径（`removeIf`/`removeStaleRoutesInternal`）使用"收集后删除"模式，迭代期间不修改 Deque。

**核心逻辑变更**：

```java
// 改动前：查看→验证→推进→删除（三步骤）
entry = registry.peekEntry(channel);    // 看
if (invalid) { registry.remove(...); continue; }  // 删
registry.advancePointer(channel);       // 推进
extract...
if (sourceEmpty) { registry.remove(...); }  // 删

// 改动后：取出→验证→决定是否还回去（单操作）
entry = registry.poll(channel);         // 取出（已从队列移除）
if (invalid) { continue; }              // 丢弃（不还回去）
extract...
if (sourceEmpty) { /* 丢弃 */ }         // 不还回去
else { registry.reoffer(channel, entry); }  // 放回尾部
```

---

### 12.7 v9 架构重构：统一路由验证 + functionSlots 缓存 (2026-07-29)

**问题1：路由注销 API 碎片化**

5 个独立清理方法（`removeStaleRoutes`、`removeStaleEnderChestRoutes`、`removeStaleRoutesByRegistrarKey`、`cleanStaleSourceRoutes`、`removeStaleRoutes(containerKey)`）分属 2 个调用者，存在重复清理（`removeStaleEnderChestRoutes` 被两个函数各调一次），且调用者需自行拼凑调用顺序。

**问题2：活末影箱被拿走后路由不清理**

`EnderRouteManager.registerRoute()` 硬编码 `targetSlot = -1`，导致 `removeStaleEnderChestRoutes` 的条件 `targetSlot >= 0` 永远不满足，活末影箱被拿走后路由残留。放回后旧路由仍在，`contains()` 返回 true，新路由无法注册。

**问题3：活漏斗被拿起到鼠标后路由不清理**

`LivingEnderChestFunction.tick()` 传 `null` 给 `activeRegistrarSlots`，跳过注册者检查，导致活漏斗被拿走后路由残留。

**修复1：`validateRoutes()` 统一验证方法**

替代旧版 5 个方法，一次遍历完成注册者/目标/源物品三项检查。通过 3 个反向索引（posIndex + keyIndex + **registrarKeyIndex** 新增）收集相关路由，去重后遍历。

**修复2：`EnderRouteManager.registerRoute()` 接收 `targetSlot` 参数**

路由条目正确记录活末影箱所在槽位，`validateRoutes()` 通过 `activeTargetSlots` 参数判断活末影箱是否还在。

**修复3：`TickContext.functionSlots` 缓存**

`processContext()` 分组时一次性填充各功能的活跃槽位集合，功能类通过 `tick.getFunctionSlots("living_hopper")` / `tick.getFunctionSlots("living_ender_chest")` O(1) 读取，无需遍历容器。`LivingEnderChestFunction.tick()` 现在也扫描活漏斗槽位，空集 = 所有路由的注册者不在 → 立即清理。

| 维度 | 旧版 | v9 |
|------|------|-----|
| API 数量 | 5 个公开方法 | 1 个 `validateRoutes` |
| 调用次数 | 6 次（含重复） | 2 次（无重复） |
| 查找方式 | 全频道扫描 ×3 | 反向索引 ×1 |
| 槽位查找 | 各功能类遍历容器 O(N) | `functionSlots` 缓存 O(1) |
| 活末影箱被拿走 | 路由残留 | 立即清理 |
| 活漏斗被拿走 | 路由残留 | 立即清理 |

---

### 12.8 v10 贪心提取策略 (2026-07-29)

**问题：轮询调度不考虑输出槽状态**

多 push 一 pull 场景下（如铁锭+金锭），pull 端轮询提取铁锭后，下一 tick 轮询到金锭，但输出槽有铁锭无法堆叠，传输停止。只有拿走铁锭后才能继续，用户体验差。

**根因**：`poll(channel)` 严格按 Deque 头部取出，`reoffer` 放回尾部，不考虑输出槽已有物品类型。

**修复：`preferredItemType` 偏好提取**

1. `LivingEnderChestAccessor` 新增 `preferredItemType` 字段
2. `TransferPipeline.execute()` 创建 source accessor 后，检查 target 槽位物品类型并设置偏好
3. `EnderChannelRegistry.peek(channel, filterData, preferredItemType)` 优先返回匹配偏好类型的条目
4. `EnderChannelRegistry.poll(channel, preferredItemType)` 优先取出匹配偏好类型的条目
5. 找不到匹配条目时，回退到正常轮询（头部条目）

**效果**：输出槽有铁锭时，优先继续提取铁锭（可堆叠）；铁锭没了或满了，自然回退到金锭。无需白名单即可实现"贪心堆叠"行为。

| 维度 | 旧版（纯轮询） | v10（贪心提取） |
|------|----------------|-----------------|
| 提取策略 | 严格轮询，不考虑输出槽 | 偏好匹配输出槽物品类型 |
| 铁锭+金锭场景 | 铁锭→金锭（卡住）→停止 | 铁锭→铁锭→...→金锭 |
| 需要白名单 | 是（才能单独提取指定物品） | 否（默认贪心，白名单仍可用） |
| 回退行为 | N/A | 偏好类型无匹配时回退轮询 |

---

## 14. 验证清单

> 重构或架构迁移后，必须逐项验证以下用例。标注 `(→ 12.X)` 的条目来源于历史 bug，不可省略。

### 14.1 双模式切换

- [ ] 未绑定玩家时为路由模式，堆叠数=频道号
- [ ] 绑定玩家后切换为直连模式，直接读写玩家末影箱
- [ ] 直连模式下玩家离线时跳过传输
- [ ] 解绑后恢复路由模式

### 14.2 路由模式

- [ ] 活漏斗 target 指向活末影箱时注册路由到全局路由表
- [ ] 活漏斗 source 指向活末影箱时从路由表提取物品
- [ ] 同频道多个源物品轮询公平调度
- [ ] 频道号=堆叠数，不同堆叠数的活末影箱在不同频道
- [ ] 路由条目去重：同一源物品不重复注册
- [ ] 贪心提取：输出槽有铁锭时优先继续提取铁锭，而非轮询到金锭（→ 12.8）
- [ ] 贪心回退：偏好类型无匹配路由时，回退到正常轮询提取其他类型

### 14.3 跨容器路由

- [ ] 活漏斗 source 越界 + target 是活末影箱时，路由仍能注册（→ 10.9）
- [ ] 跨容器路由的 targetSlot 设为 -1，不绑定具体槽位（→ 12.1）
- [ ] 注册者容器区块卸载时，跨容器路由被清理（→ 12.2）

### 14.4 路由清理

- [ ] 源物品被移走后，路由被清理（validateRoutes 检查3：源物品验证）
- [ ] 活末影箱被移走后，相关路由被清理（validateRoutes 检查2：目标验证）
- [ ] 活漏斗被拿起到鼠标后，路由被清理（validateRoutes 检查1：注册者验证，空集触发）
- [ ] 区块卸载时，该区块所有相关路由被清理（onChunkUnload）
- [ ] 三个反向索引正确维护：路由注册/移除时 posIndex、keyIndex、registrarKeyIndex 同步更新
- [ ] validateRoutes 使用反向索引，不遍历所有频道
- [ ] TickContext.functionSlots 缓存正确填充，功能类 O(1) 读取

### 14.5 过滤规则

- [ ] 活末影箱的过滤规则（黑白名单）在路由提取时生效
- [ ] peek(channel, filterData) 正确过滤不匹配的源物品

### 14.6 Tooltip 与同步

- [ ] 路由模式显示频道号和路由数量
- [ ] 直连模式显示绑定玩家名称（紫色加粗）
- [ ] 高级模式（F3+H）显示每条路由的详细信息
- [ ] 客户端缓存与服务端同步：路由变化后 Tooltip 立即更新
- [ ] EnderChannelClientCache 线程安全（ConcurrentHashMap）
- [ ] 延迟同步：同一 tick 内路由注册+删除不会导致 Tooltip 闪烁（→ 12.3）

### 14.7 数据持久化

- [ ] boundPlayerUuid 以字符串形式序列化，运行时通过 getPlayerUuid() 转换
- [ ] 解绑后 EnderChannelData 为 EMPTY，不残留旧数据