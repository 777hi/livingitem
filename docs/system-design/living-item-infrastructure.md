# 活物品基础设施系统设计

> **文档版本**: 2026.08 v9  
> **最后更新**: 2026-08-26  
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [概述](#1-概述)
2. [容器抽象层](#2-容器抽象层)
3. [容器发现与缓存](#3-容器发现与缓存)
4. [容器类型检测与访问](#4-容器类型检测与访问)
5. [模组容器兼容](#5-模组容器兼容)
6. [容器大小自动解析](#6-容器大小自动解析)
7. [统一容器上下文实现](#7-统一容器上下文实现)
8. [Tick 执行模型](#8-tick-执行模型)
9. [SlotAccessor 存储后端抽象](#9-slotaccessor-存储后端抽象)
10. [性能监控](#10-性能监控)
11. [Tooltip 渲染机制](#11-tooltip-渲染机制客户端)
12. [关键文件索引](#12-关键文件索引)

---

## 1. 概述

### 1.1 什么是"活物品基础设施"

活物品模组的核心逻辑是：**让容器中的物品自动执行 tick 逻辑**。要实现这一点，需要解决一系列基础问题：

- 如何发现世界中哪些容器需要处理？
- 如何统一访问不同类型的容器（箱子、漏斗、玩家背包、末影箱、模组容器）？
- 如何在不同容器间传输物品？
- 如何保证高效，不卡死服务端？

活物品基础设施就是解决这些问题的底层系统，它为上层的功能类（活熔炉、活漏斗、活末影箱等）提供统一的容器访问能力。

### 1.2 基础设施分层

```
┌──────────────────────────────────────────────────────────────────┐
│  领域层：domain/hopper/, domain/chest/, domain/ender/, ...       │
│  ─────────────────────────────────────────────────────────────── │
│  API 层：LivingItemFunction, HasDirection, HasContainerData     │
│  ─────────────────────────────────────────────────────────────── │
│  传输层：SlotAccessor (Plain/LivingChest/EnderChest/Neighbor)    │
│          + FilterData, FilteredSlotAccessor                      │
│  ─────────────────────────────────────────────────────────────── │
│  容器抽象层：ContainerContext, TickContext, ContainerSnapshot    │
│  ─────────────────────────────────────────────────────────────── │
│  容器发现层：ContainerChunkCache, ContainerLivingItemHandler     │
│  ─────────────────────────────────────────────────────────────── │
│  兼容层：ContainerCompatibilityConfig, SlotResolver             │
│  ─────────────────────────────────────────────────────────────── │
│  NeoForge 能力系统：IItemHandler (Capabilities.ItemHandler)      │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 核心设计原则

| 原则 | 说明 |
|------|------|
| **统一抽象** | 所有容器通过 `IItemHandler` 统一访问，不区分原版/模组 |
| **接口隔离** | `ContainerContext` 拆分为 4 个正交接口，功能类只依赖最小接口 |
| **拉取模型** | 不推送事件，而是每 tick 主动拉取所有含容器的区块 |
| **模拟优先** | 传输前先模拟，确认可行后再执行，rollback 仅作安全兜底 |
| **开闭原则** | 新增容器类型/存储类型通过注册机制扩展，无需修改核心代码 |

---

## 2. 容器抽象层

### 2.1 接口拆分概览

`ContainerContext` 已从上帝接口重构为组合接口，拆分为 4 个正交接口：

```
ContainerContext (组合接口，继承以下 4 个接口)
    ├── LivingContainer          — 基础物品读写
    ├── SlotInfoProvider         — 槽位能力查询
    ├── ContainerSync            — 客户端数据同步
    └── ContainerIdentity        — 身份标识 + 世界信息
```

### 2.2 LivingContainer — 基础物品读写

[LivingContainer](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/LivingContainer.java) 是容器能力体系的最小接口，所有其他容器接口都继承它。

```java
public interface LivingContainer {
    int getSize();                                   // 容器总槽位数
    ItemStack getItem(int logicalSlot);              // 读取槽位物品
    void setItem(int logicalSlot, ItemStack stack);  // 写入槽位物品
    int getMaxStackSize();                           // 默认最大堆叠数
}
```

功能类如果只需要读写物品，依赖此接口即可。

### 2.3 SlotInfoProvider — 槽位能力查询

[SlotInfoProvider](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/SlotInfoProvider.java) 扩展 `LivingContainer`，增加槽位限制、物品验证、模拟插入等能力。

```java
public interface SlotInfoProvider extends LivingContainer {
    int getSlotLimit(int slot);                      // 槽位最大堆叠上限（模组槽位可能远超 64）
    boolean isItemValid(int slot, ItemStack stack);  // 物品是否能放入指定槽位
    int simulateInsertItem(int slot, ItemStack stack); // 模拟插入，返回实际可插入数量
    int getWidth();                                   // 容器列数（GUI 宽度）
}
```

**为什么需要 `getSlotLimit` 和 `simulateInsertItem`？**

- 模组容器（如抽屉、精妙背包）的槽位限制可能远超 64，甚至达到 1024+
- `simulateInsertItem` 比 `isItemValid` 更全面，因为它会考虑槽位中已有物品的堆叠情况
- 对于玩家盔甲槽位，非盔甲物品会返回 false，防止物品消失

### 2.4 ContainerSync — 客户端同步

[ContainerSync](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerSync.java) 负责将服务端修改后的物品数据同步到客户端。

**为什么需要手动同步？**

原版 `broadcastChanges()` 依赖 `ItemStack.matches()` 检测变化，而 `PatchedDataComponentMap.equals()` 无法检测到自定义 DataComponent 的变化。因此活物品修改 DataComponent 后，必须手动发送 `ClientboundContainerSetSlotPacket`。

```java
public interface ContainerSync extends LivingContainer {
    void syncSlotToClients(int logicalSlot, ItemStack stack);
}
```

同步策略分两种：
- **玩家背包**：通过 `-2` 窗口 ID 发送，同时更新 `inventoryMenu` 和 `containerMenu`
- **世界容器**：遍历所有正在查看该容器的玩家，发送对应槽位的同步包

### 2.5 ContainerIdentity — 身份标识

[ContainerIdentity](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerIdentity.java) 提供容器唯一标识和世界信息，适用于需要跨容器操作的功能。

```java
public interface ContainerIdentity {
    String getContainerKey();                        // 容器唯一标识 key
    String getStableKey(int logicalSlot, String functionId); // 槽位稳定 key
    BlockPos getBlockPos();                          // 容器方块位置（nullable）
    Level getLevel();                                // 容器所在世界（nullable）
}
```

**ContainerKey 的生成规则**：

| 容器类型 | Key 格式 | 示例 |
|---------|---------|------|
| 玩家背包 | `player_<uuid>` | `player_550e8400-...` |
| 玩家末影箱 | `player_<uuid>_ender_chest` | `player_550e8400-..._ender_chest` |
| 方块容器 | `chest_<x>_<y>_<z>` | `chest_0_64_0` |
| 大箱子 | `chest_<x1>_<y1>_<z1>_<x2>_<y2>_<z2>` | `chest_0_64_0_1_64_0` |
| 未知容器 | `container_<handlerHashCode>` | `container_1a2b3c4d` |

`getStableKey` 用于生成槽位级别的稳定标识，格式为 `containerKey_slot_<N>_func_<functionId>`，用于缓存和状态关联。

---

## 3. 容器发现与缓存

### 3.1 拉取模型（Pull Model）

活物品不依赖事件推送来获知容器变化。相反，每 tick 主动拉取所有已知容器：

```
ServerTickEvent.Post
    ↓
1. 遍历所有在线玩家 → 处理玩家背包
    ↓
2. 遍历所有维度 → ContainerChunkCache.getCachedChunks()
    ↓
3. 遍历每个含容器区块的方块实体
    ↓
4. ContainerLivingItemHandler.processContainerAt()
```

这种设计避免了事件丢失的风险，同时通过 `ContainerChunkCache` 将扫描范围从"所有区块"缩小到"有容器的区块"。

### 3.2 ContainerChunkCache — 容器区块缓存

[ContainerChunkCache](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerChunkCache.java) 维护"包含容器方块实体的区块"列表，避免每 tick 全量扫描所有区块。

**为什么需要这个缓存？**

如果每 tick 都扫描所有已加载区块的所有方块实体，性能开销极大。实际上大部分区块不包含任何容器（纯地形区块），所以只需缓存"有容器的区块"即可。

**缓存维护策略（事件驱动）**：

```
区块加载   → 扫描该区块，若有容器则加入缓存
区块卸载   → 从缓存中移除
方块放置   → 若放置容器方块，重新扫描该区块
方块破坏   → 若破坏容器方块，重新扫描该区块
```

**内部结构**：

```java
// 按维度存储包含容器的区块坐标集合
private final Map<ResourceKey<Level>, Set<ChunkPos>> chunkCache;
```

**容器检测方式**：通过 NeoForge 能力系统检查方块是否提供 `IItemHandler`：

```java
private static boolean hasContainerOrItemHandler(ServerLevel level, BlockPos pos) {
    return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
}
```

这天然支持所有原版容器和任何通过 `Capabilities.ItemHandler.BLOCK` 注册能力的模组容器。

**缓存清理机制**（三层保障）：

| 层级 | 机制 | 触发时机 | 作用 |
|------|------|---------|------|
| 1 | `onChunkUnload` | 区块卸载时 | 主要清理机制，即时从缓存移除 |
| 2 | `!hasContainer` 检查 | 每 tick 遍历 | 自清洁，容器消失时移除（如方块破坏） |
| 3 | `cleanupStaleEntries` | 每 6000 ticks（约 5 分钟） | 兜底清理，移除已卸载但事件遗漏的区块 |

**兜底清理的必要性**：

`processLevelContainers` 中曾使用 `getChunkNow()` 返回 null 时立即移除区块缓存。但 `getChunkNow()` 内部通过 `GenerationChunkHolder.getChunkIfPresent(ChunkStatus.FULL)` 检查状态，而 `FULL` 状态的 `CompletableFuture` 可能与 `ChunkEvent.Load` 存在时序窗口：

```
tick N:
  ChunkStatusTasks.full() 执行 → ChunkEvent.Load → onChunkLoad → 缓存加入 ✅
  full() 返回 → FULL future 完成 → ChunkHolder 状态更新
  ServerTickEvent.Post → processLevelContainers → getChunkNow()
    ↑ 若 future 尚未完成，返回 null → toRemove 移除缓存 ❌
```

**修复**（2026-08-17）：`getChunkNow` 返回 null 时不再立即移除，只跳过本次处理。区块由 `onChunkUnload` 正常移除，`cleanupStaleEntries` 作为兜底定期清理残留。

**优化**（2026-08-19）：遍历世界容器时跳过未打开的战利品容器（`RandomizableContainer` 且 `lootTable != null`），避免 `getItem()` 内部 `unpackLootTable()` 触发战利品表生成。战利品表（如 `minecraft:chests/shipwreck_map`）中的 `ExplorationMapFunction` 搜索结构，在未探索区域耗时极高。

### 3.3 大箱子去重

大箱子（`ChestBlock`）在 NeoForge 中，左右两半返回**同一个 `IItemHandler` 实例**。因此去重策略分两层：

1. **IdentityHashMap 按 IItemHandler 实例去重**：同一实例意味着同一容器，已处理则跳过
2. **HashSet 按 containerKey 去重**：防止某些模组容器每次创建新 `IItemHandler` 实例

```java
// 在 LivingItem.processLevelContainers() 中
private final IdentityHashMap<IItemHandler, Boolean> reusableHandlerMap = new IdentityHashMap<>();
private final Set<String> reusableKeySet = new HashSet<>();
```

`findDoubleChestPositions()` 方法通过 `ChestBlock.TYPE` 属性检测大箱子，返回 LEFT 和 RIGHT 两半的位置，用于构建包含两个坐标的 ContainerKey。

---

## 4. 容器类型检测与访问

### 4.1 三种容器来源

活物品需要处理三种不同来源的容器：

| 容器来源 | 获取方式 | 特点 |
|---------|---------|------|
| 玩家背包 | `player.getInventory()` | 始终在线，需同步到客户端 |
| 玩家末影箱 | `player.getEnderChestInventory()` | 独立于背包，需单独处理 |
| 世界方块容器 | `level.getCapability(ItemHandler.BLOCK, pos)` | 通过区块缓存发现 |

### 4.2 玩家背包处理

[LivingItem.onServerTick](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/LivingItem.java) 中，每 tick 遍历所有在线玩家：

```java
for (var player : server.getPlayerList().getPlayers()) {
    ContainerLivingItemHandler.processContainer(player.getInventory(), player.level());
}
```

`processContainer` 通过 `player.getCapability(Capabilities.ItemHandler.ENTITY)` 获取 `IItemHandler`，然后构建 `SimpleContainerContext`。

**ContainerKey**：`player_<uuid>`

### 4.3 玩家末影箱处理

末影箱（`PlayerEnderChestContainer`）既不是方块实体也不是玩家背包的一部分，需要单独处理。

```java
public static void processEnderChest(Player player, Level level) {
    PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
    // 先检查是否有活物品，没有则跳过（惰性优化）
    boolean hasLivingItem = false;
    for (int i = 0; i < enderChest.getContainerSize(); i++) {
        if (LivingItemManager.isLivingItem(enderChest.getItem(i))) {
            hasLivingItem = true;
            break;
        }
    }
    if (!hasLivingItem) return;
    // 通过 InvWrapper 包装为 IItemHandler
    IItemHandler handler = new InvWrapper(enderChest);
    ContainerContext context = new EnderChestContainerContext(handler, player, level);
    processContext(context, level);
}
```

**ContainerKey**：`player_<uuid>_ender_chest`（与玩家背包的 key 区分）

### 4.4 生存模式 vs 创造模式

活物品的容器处理逻辑在**服务端**运行，不区分生存/创造模式。两者的区别体现在客户端 GUI 层：

- **生存模式背包**：通过 `InventoryScreen` 渲染，使用 `InventoryScreenMixin` 注入 GUI 交互
- **创造模式背包**：通过 `CreativeModeInventoryScreen` 渲染，使用 `CreativeModeInventoryScreenMixin` 注入 GUI 交互

创造模式的一个特殊处理是 `carriedTag` 序列化：在创造模式下，光标物品可能被客户端修改，因此需要在发送交互包时序列化光标物品的 NBT，服务端收到后恢复：

```java
// GuiInteractionHelper.resolveCarriedTag()
// 创造模式背包下序列化光标物品 NBT
```

### 4.5 世界方块容器处理

世界中的方块容器（箱子、漏斗、熔炉等）通过 `ContainerChunkCache` 发现，然后逐个处理：

```java
// LivingItem.processLevelContainers()
for (var chunkPos : chunkSet) {
    var chunk = level.getChunk(chunkPos.x, chunkPos.z);
    for (var be : chunk.getBlockEntities().values()) {
        IItemHandler handler = level.getCapability(
            Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
        if (handler == null) continue;
        ContainerLivingItemHandler.processContainerAt(level, pos, handler, ...);
    }
}
```

**ContainerKey**：`chest_<x>_<y>_<z>`（或大箱子：`chest_<x1>_<y1>_<z1>_<x2>_<y2>_<z2>`）

---

## 5. 模组容器兼容

### 5.1 设计理念

活物品通过 **NeoForge 能力系统（Capability System）** 实现模组容器兼容。只要模组容器通过 `Capabilities.ItemHandler.BLOCK` 注册了 `IItemHandler` 能力，活物品就能自动发现并处理。

**不需要为每个模组容器写适配代码**。NeoForge 自动为所有原版 `Container` 方块注册 `IItemHandler`，大多数模组也遵循此惯例。

### 5.2 ContainerCompatibilityConfig — 容器兼容性配置

[ContainerCompatibilityConfig](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/config/ContainerCompatibilityConfig.java) 为不同容器类型定义槽位解析规则，主要解决**非标准列数容器**的槽位方向解析问题。

**内置规则**：

| 容器 | 槽位数 | 列数 | 边界行为 | 备注 |
|------|--------|------|---------|------|
| `minecraft:chest` | 27 | 9 | INVALIDATE | 标准箱子 |
| `minecraft:double_chest` | 54 | 9 | WRAP | 大箱子左右环绕 |
| `minecraft:hopper` | 5 | 1 | INVALIDATE | 仅中间 3 格可宿主 |
| `ironchests:iron_chest` | 45 | 9 | INVALIDATE | 铁箱子 |
| `ironchests:diamond_chest` | 108 | 12 | INVALIDATE | 钻石箱子 |
| `sophisticatedbackpacks:backpack` | 120 | 12 | INVALIDATE | 精妙背包 12×10 |
| `sophisticatedbackpacks:backpack` | 108 | 12 | INVALIDATE | 精妙背包 12×9 |

**自动生成规则**：

对于未注册的容器类型，`findOrGenerateRule()` 会根据槽位数自动推断标准矩形布局：

```java
public static ContainerRule findOrGenerateRule(int containerSize) {
    return findRuleBySize(containerSize)
        .orElseGet(() -> generateStandardRule(containerSize));
}
```

### 5.3 ContainerRule — 容器规则

```java
public record ContainerRule(
    int containerSize,                              // 容器槽位数
    ContainerLayoutType layoutType,                 // 布局类型
    int columns,                                    // 列数
    List<Integer> validHostSlots,                   // 可作为活物品宿主的槽位
    Map<Pos2D, Integer> directionMappings,          // 方向 → 偏移量映射
    EdgeBehavior edgeBehavior,                      // 边界行为
    boolean crossBlockEntitySupport,                // 是否跨方块实体
    String description                              // 规则描述
)
```

**布局类型**：

| 类型 | 说明 |
|------|------|
| `RECTANGULAR_STANDARD` | 标准 9 列矩形 |
| `RECTANGULAR_CUSTOM` | 自定义列宽矩形 |
| `LINEAR` | 线性布局（单行） |
| `IRREGULAR` | 不规则布局 |

**边界行为**：

| 行为 | 说明 |
|------|------|
| `INVALIDATE` | 返回 -1（无效槽位，默认） |
| `WRAP` | 环绕到容器另一端（大箱子左右环绕） |
| `CLAMP` | 钳制到最近的有效边界 |
| `SKIP` | 跳过此方向 |

### 5.4 扩展方式

新增模组容器兼容只需注册规则：

```java
ContainerCompatibilityConfig.register(
    ResourceLocation.fromNamespaceAndPath("modid", "custom_container"),
    ContainerRule.builder()
        .containerSize(81)
        .columns(9)
        .validHostSlots(range(0, 80))
        .directionMapping(Pos2D.LEFT, -1)
        .directionMapping(Pos2D.RIGHT, 1)
        .directionMapping(Pos2D.UP, -9)
        .directionMapping(Pos2D.DOWN, 9)
        .edgeBehavior(EdgeBehavior.INVALIDATE)
        .build()
);
```

### 5.5 Container 接口槽位过滤 — 模拟玩家操作

**问题背景**：部分模组容器的 `IItemHandler` 实现可能暴露不可交互的槽位（如配置槽、幽灵槽、升级槽）。这些槽位在玩家 GUI 中不可见/不可操作，但通过 `IItemHandler` 遍历时可能被访问到，导致活漏斗复制物品等异常行为。

**解决方案**：在传输路径中引入 `Container` 接口的 `canTakeItem` 和 `canPlaceItem` 检查，模拟玩家操作逻辑。

**Container 接口**：`net.minecraft.world.Container` 是 Minecraft 原版接口，所有容器方块实体都必须实现它才能让玩家 GUI 正常工作。`canTakeItem` 和 `canPlaceItem` 是玩家 GUI 用来判断槽位是否可操作的底层方法，可靠性远高于各模组自由实现的 `IItemHandler`。

**过滤位置**：两条传输路径均已添加过滤：

| 传输路径 | 文件 | `canTakeItem` | `canPlaceItem` |
|----------|------|:---:|:---:|
| 跨容器传输 | `CrossContainerTransfer` | ✅ 源槽 | ✅ 目标槽 |
| 容器内传输 | `TransferPipeline` | ✅ 源槽 | ✅ 目标槽 |

**跨容器传输** — 邻居槽位遍历已提取为两个核心 helper 方法，所有传输方法复用：

```java
// CrossContainerTransfer — 拉取方向：遍历邻居槽位，过滤后创建 source，transfer 到 target
private static boolean tryPullFromNeighbor(IItemHandler handler, BlockPos pos, Level level,
    FilterData filter, SlotAccessor target, int amount) {
    Container container = getNeighborContainer(level, pos);
    for (int i = 0; i < handler.getSlots(); i++) {
        ItemStack stack = handler.getStackInSlot(i);
        if (stack.isEmpty() || LivingItemManager.isLivingItem(stack)) continue;
        if (container != null && !container.canTakeItem(container, i, stack)) continue;
        SlotAccessor source = SlotAccessorFactory.createForNeighbor(handler, i, filter, level, pos);
        if (SlotAccessor.transfer(source, target, amount)) return true;
    }
    return false;
}

// 推送方向：遍历邻居槽位，过滤后创建 target，从 source transfer
private static boolean tryPushToNeighbor(IItemHandler handler, BlockPos pos, Level level,
    SlotAccessor source, int amount, ItemStack filterItem) {
    Container container = getNeighborContainer(level, pos);
    for (int i = 0; i < handler.getSlots(); i++) {
        if (container != null && !container.canPlaceItem(i, filterItem)) continue;
        SlotAccessor target = SlotAccessorFactory.createForNeighbor(handler, i, null, level, pos);
        if (SlotAccessor.transfer(source, target, amount)) return true;
    }
    return false;
}
```

**调用方**：`tryPullFromNeighbor` 被 `pullFromNeighbor` 和 `pullFromNeighborToLivingChest` 复用；`tryPushToNeighbor` 被 `pushToNeighbor` 和 `pushFromLivingStorageToNeighbor`（合并了 `pushFromLivingChestToNeighbor` + `pushFromLivingEnderChestToNeighbor`）复用。`pushToNeighbor` 额外增加了一层"满槽跳过"优化（槽位已满且物品不同 → 跳过）。

**容器内传输** — 在 `TransferPipeline.executeInContainer()` 中，提取前检查 `canTakeItem`，放入前通过模拟提取检查 `canPlaceItem`：

```java
// TransferPipeline.executeInContainer() — 提取前检查
Container hostContainer = CrossContainerTransfer.getNeighborContainer(level, ctx.getBlockPos());
if (hostContainer != null && !hostContainer.canTakeItem(hostContainer, sourceSlot, sourceStack)) {
    return false;
}

int transferAmount = Math.min(stackSize, maxTransfer);

// 放入前检查（模拟提取目标物品后验证）
if (hostContainer != null) {
    ItemStack simulated = source.simulateExtract(transferAmount);
    if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
        return false;
    }
}
```

**降级策略**：如果邻居/宿主容器未实现 `Container` 接口（如纯 `IItemHandler` 的模组），则跳过过滤，回退到纯 `IItemHandler` 模式——保持原有行为不变。

### 5.6 IItemHandler.insertItem 槽位参数不可靠问题

**问题**：仅靠 `canTakeItem`/`canPlaceItem` 检查还不够。即使 `canPlaceItem(targetSlot, stack)` 返回 `true`，实际执行 `IItemHandler.insertItem(targetSlot, stack, false)` 时，物品仍可能被写入其他槽位。这是因为 `IItemHandler.insertItem` 的 `slot` 参数在 API 层面只是"建议"，许多模组容器实现会忽略它。

**解决方案**：在 `SimpleContainerContext` 的物品写入路径中，当 `Container` 接口可用时，直接使用 `Container.setItem(slot, stack)` 而非 `IItemHandler.insertItem(slot, stack, false)`。详见 [7.2 物品写入策略](#72-物品写入策略container-优先-iitemhandler-兜底)。

**影响范围**：
| 传输类型 | 是否受影响 | 原因 |
|----------|:---:|------|
| 容器内传输 | ✅ 受影响 | 精确指定目标槽位，必须槽位精确写入 |
| 跨容器传输 | ❌ 不受影响 | 遍历所有槽位逐个尝试，物品落入第一个可用槽位是预期行为 |

**相关修复**：`SimpleContainerContext.setItem()` 的**写入**自 2026-08-17 起统一走 `IItemHandler`（**移除** `Container.setItem` 优先写入，避免大箱子左右两半双记账导致物品复制）；`simulateInsertItem()` 仍读 `Container` 仅用于容量计算。详见 §7.2。

---

## 6. 容器大小自动解析

### 6.1 列数推断

容器列数（GUI 宽度）是活物品方向解析的基础。标准容器为 9 列，但模组容器可能有不同列数。

**推断策略**：从 `getSize()` 推断列数，尝试 9~13 列，找到能整除的列宽：

```java
// ContainerCompatibilityConfig.resolveColumns()
private static int resolveColumns(int slotCount) {
    for (int w = 9; w <= 13; w++) {
        if (slotCount % w == 0) return w;
    }
    return 9;
}
```

**推断示例**：

| 槽位数 | 推断列数 | 说明 |
|--------|---------|------|
| 27 | 9 | 27 ÷ 9 = 3 |
| 54 | 9 | 54 ÷ 9 = 6 |
| 45 | 9 | 45 ÷ 9 = 5 |
| 108 | 9 | 108 ÷ 9 = 12（优先 9，但 108 也是 12 的倍数） |
| 108 | 12 | 如果注册了 12 列规则 |
| 50 | 10 | 50 ÷ 10 = 5 |
| 91 | 13 | 91 ÷ 13 = 7 |
| 47 | 9 | 47 不能被 9~13 整除，默认 9 |
| 120 | 10 | 120 ÷ 10 = 12（优先 10，但 120 也是 12 的倍数） |

> **注意**：`resolveColumns()` 从 9 开始递增尝试，因此 108 格会优先推断为 9 列而非 12 列，120 格会优先推断为 10 列而非 12 列。对于 12 列的非标准矩形容器（如精妙背包、钻石箱子），**必须显式注册规则**，否则自动推断会得到错误的列数。

### 6.2 SimpleContainerContext.getWidth() 的宽解析

`SimpleContainerContext.getWidth()` 实现了完整的列数解析链：

```java
public int getWidth() {
    // 1. 玩家背包 → 固定 9 列
    if (inventory != null) return 9;

    // 2. 查找已注册的 ContainerRule
    Optional<ContainerRule> rule = findContainerRule();
    if (rule.isPresent() && rule.get().columns() > 0) return rule.get().columns();

    // 3. 标准矩形（size % 9 == 0）→ 9 列
    int size = getSize();
    if (size > 0 && size % 9 != 0) {
        return guessWidth(size);  // 4. 非标准矩形 → 尝试推断
    }

    return ContainerContext.super.getWidth();  // 默认 9
}
```

**规则查找中的容量校验**：

`findContainerRule()` 在通过方块实体 ID 或模糊匹配找到规则后，会额外校验 `rule.containerSize() == getSize()`。这是因为同一方块实体类型可能对应多种容量（如精妙背包的 120 格和 108 格），仅靠 ID 匹配可能返回错误容量的规则。加入容量校验后，不匹配的规则会被跳过，最终回退到 `findOrGenerateRule(getSize())` 按容量查找。

```java
// SimpleContainerContext.findContainerRule()
var rule = ContainerCompatibilityConfig.findRule(id);
if (rule.isPresent() && rule.get().containerSize() == getSize()) return rule;  // 容量匹配才返回
```

### 6.3 SlotResolver — 槽位方向解析

[SlotResolver](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/SlotResolver.java) 将相对方向偏移转换为容器中的绝对槽位索引。

**核心公式**：

```
result = (baseSlot / width + direction.y) * width + (baseSlot % width + direction.x)
```

**示例**（9 列标准容器）：

```
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│ 9 │10 │11 │12 │13 │14 │15 │16 │17 │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│18 │19 │20 │21 │22 │23 │24 │25 │26 │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘

活物品在槽位 13：
  LEFT  → 12  (13 + (-1,0))
  RIGHT → 14  (13 + (1,0))
  UP    → 4   (13 + (0,-1))
  DOWN  → 22  (13 + (0,1))
```

**边界检查**：左/右边界通过列数检查，上/下边界通过容器大小检查。越界返回 `-1`。

---

## 7. 统一容器上下文实现

### 7.1 SimpleContainerContext

[SimpleContainerContext](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/SimpleContainerContext.java) 是 `ContainerContext` 的通用实现，基于 `IItemHandler`。

**核心字段**：

```java
private final IItemHandler handler;              // 物品读写
private final Inventory inventory;              // 玩家背包引用（nullable）
private final String containerKey;              // 容器唯一标识
private final List<BlockPos> associatedBlockPositions;   // 关联方块位置
private final List<BlockEntity> associatedBlockEntities; // 关联方块实体
private final Level overrideLevel;              // 覆盖的世界
```

**两种构造模式**：

```java
// 玩家背包模式
new SimpleContainerContext(handler, inventory);

// 方块容器模式
new SimpleContainerContext(handler, positions, blockEntities);
```

### 7.2 物品写入策略：统一走 IItemHandler（2026-08-17 起移除 Container 优先写入）

`setItem` 的**写入**统一只走 `IItemHandler`，**不再**经原版 `Container` 接口做精确槽位写入。`simulateInsertItem` 仍会读 `Container` 仅用于**容量计算**（只读，安全），写回仍走 IItemHandler。

**为什么放弃 Container 优先写入（2026-08-17 修正）**：大箱子（ChestBlock）左右两半在 NeoForge 下返回**同一个 `IItemHandler` 实例**，但各自是独立的 `Container`。若经 `Container.setItem` 写入，同一物理物品可能被两半的 `Container` 分别记账，引发**物品复制 bug**。改为统一走 IItemHandler 后，读写都收敛到共享的那一个 handler，大箱子两端读写一致，复制 bug 消除（详见 §10.2 性能表 "handler 统一读写" 一行）。

> ⚠️ 历史坑：本文档早期版本（及 §5.6）曾描述 `setItem` 采用 "Container 优先、IItemHandler 兜底" 的**写入**策略——那是 2026-08-17 **修正前**的设计，**不要据此重新加回 `Container.setItem` 写入路径**，否则复制 bug 复发。

**实际实现（与代码一致）**：

```java
@Override
public void setItem(int logicalSlot, ItemStack stack) {
    if (logicalSlot < 0 || logicalSlot >= handler.getSlots()) return;
    ItemStack toInsert = stack.copy();

    // 统一走 IItemHandler：先抽空再写入（slot 为建议值，模组容器可能忽略）
    handler.extractItem(logicalSlot, Integer.MAX_VALUE, false);
    ItemStack remaining = handler.insertItem(logicalSlot, toInsert, false);
    if (!remaining.isEmpty() && isArmorSlot(inventory, logicalSlot)) {
        // 活漏斗绕过盔甲槽限制，直接设置物品（盔甲非 BlockEntity Container，走此兜底）
        inventory.armor.set(logicalSlot - 36, toInsert);
        syncSlotToClients(logicalSlot, toInsert);
    } else if (!remaining.isEmpty()) {
        LOGGER.warn("SimpleContainerContext.setItem: {} items of {} 未能插入槽位 {}",
            remaining.getCount(), toInsert.getItem(), logicalSlot);
    }
    notifyBlockEntitiesChanged();
    ContainerLivingItemHandler.bumpContainerRevision(this);
}
```

```java
@Override
public int simulateInsertItem(int slot, ItemStack stack) {
    // 优先：Container 接口 + 手动容量计算
    Container container = ContainerContext.getContainer(getLevel(), getBlockPos());
    if (container != null && slot < container.getContainerSize()) {
        if (!container.canPlaceItem(slot, stack)) return 0;
        ItemStack existing = container.getItem(slot);
        int slotLimit = container.getMaxStackSize();
        if (existing.isEmpty()) {
            return Math.min(stack.getCount(), Math.min(slotLimit, stack.getMaxStackSize()));
        }
        if (ItemStack.isSameItemSameComponents(existing, stack)) {
            int space = Math.min(slotLimit, existing.getMaxStackSize()) - existing.getCount();
            return Math.min(stack.getCount(), Math.max(0, space));
        }
        return 0;
    }
    // 兜底：IItemHandler 模拟（slot 参数可能被忽略）
    ItemStack remaining = handler.insertItem(slot, stack.copy(), true);
    return stack.getCount() - remaining.getCount();
}
```

**为什么接受 IItemHandler-only 写入（权衡）**：`IItemHandler.insertItem` 的 `slot` 参数在 API 契约上只是"建议"，部分模组容器会忽略它、按"第一个可用槽位"插入——即容器内精确写入在模组容器上**不保证落入指定槽位**。这是为消除大箱子复制 bug 主动接受的代价；可靠性由传输层兜底：`Container.canTakeItem/canPlaceItem` 过滤不可交互槽位（§5.5）+ 模拟优先（`SlotAccessor.transfer` 先 `simulateInsert` 探容量再真实写入，§9.3），使正常流程下 `insertItem` 必全量落入、`remaining` 为空（见下方注）。

### 7.3 玩家盔甲槽绕过

玩家背包的盔甲槽位（slot 36-39）有严格限制——只能放入对应类型的盔甲。当 `IItemHandler.insertItem` 拒绝写入盔甲槽时，`setItem` 的兜底路径会检测 `remaining` 并直接通过 `inventory.armor.set()` 写入，绕过限制。

这允许活漏斗将物品传输到盔甲槽位（如"方块放头上"等趣味玩法）。

### 7.4 客户端同步实现

同步分两种路径：

**玩家背包同步**：

```java
// 发送 -2 窗口包（玩家背包）
serverPlayer.connection.send(
    new ClientboundContainerSetSlotPacket(-2, 0, logicalSlot, stack.copy()));

// 同步 inventoryMenu
syncInventoryMenuSlot(serverPlayer, serverPlayer.inventoryMenu, logicalSlot, stack);

// 如果玩家打开了其他容器，也同步该容器的菜单
if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) {
    syncInventoryMenuSlot(serverPlayer, serverPlayer.containerMenu, logicalSlot, stack);
}
```

**世界容器同步**：遍历所有正在查看该容器的服务端玩家，通过 `containerMenu` 的 slot 匹配找到对应槽位，发送 `ClientboundContainerSetSlotPacket`。

---

## 8. Tick 执行模型

### 8.1 总体流程

```
ServerTickEvent.Post
    ↓
┌─ 玩家背包循环 ──────────────────────────────────────────────────┐
│ for each player:                                                │
│   processContainer(player.getInventory())                       │
│   processEnderChest(player)                                     │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─ 世界容器循环 ──────────────────────────────────────────────────┐
│ for each level:                                                 │
│   for each chunk in ContainerChunkCache.getCachedChunks():      │
│     for each BlockEntity in chunk:                              │
│       processContainerAt(level, pos, handler)                   │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 processContext — 按功能分组执行

[ContainerLivingItemHandler.processContext](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerLivingItemHandler.java) 采用六阶段设计：

```
processContext(context, level)
  ├─ 阶段 1：扫描（scanAndGroupLivingItems + syncContentRevision）
  │   ├─ 遍历全槽位，按功能类型分组收集活物品
  │   └─ 校验内容签名，必要时 bump 修订计数（防稳态死锁）
  │
  ├─ [空容器分支：handleEmptyContainer]
  │   └─ grouped 为空时，仅让残留红石信号归零后提前返回
  │
  ├─ 阶段 2：功能 tick（tickFunctionSlots + runFunctionTicks）
  │   ├─ 将功能槽位集合写入 TickContext
  │   └─ 对每种功能只调用一次 tick()，传入该组所有活物品
  │
  ├─ 阶段 3：EnderChannel 刷新（flushEnderChannels）
  │   ├─ 刷新脏通道
  │   └─ 清理无活水桶容器中的冗余流数据
  │
  ├─ 阶段 4：容器级数据（runContainerDataTicks）
  │   └─ 收集 HasContainerData 实现者，按优先级排序后依次执行
  │
  ├─ 阶段 5：写回 BlockEntity（writebackBlockEntities + incrementCleanup）
  │   ├─ 应力与流体数据写回关联的 BlockEntity（或玩家脚底）
  │   └─ 达到清理间隔时执行过期数据清理
  │
  └─ [finally] 脏槽同步 + TickContext 清理（flushDirtySlots + setTickContext(null)）
```

**阶段 1 — 扫描**：遍历容器中所有物品，将活物品按功能类型分组收集，同时校验内容签名以打破稳态跳过死锁。

```java
Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = scanAndGroupLivingItems(context);
syncContentRevision(context);

if (grouped.isEmpty()) {
    handleEmptyContainer(context, startNanos, monitorKey);
    return;
}
```

**阶段 2 — 功能 tick**：对每种功能只调用一次 `tick()`，传入该容器中所有拥有此功能的活物品列表。

```java
tickFunctionSlots(grouped, tick);           // 写入 TickContext 缓存
runFunctionTicks(grouped, context, tick, level);  // 逐功能执行 tick
```

**为什么按功能分组而不是逐个调用？**

如果逐个调用 `tick`，每个活物品独立推进自己的状态，导致总速度随活物品数量线性增长（N 个活熔炉 = N 倍速度）。按功能分组后，由功能实现自行决定如何分配处理（如活熔炉每 tick 只处理一个），从根本上避免速度翻倍。

**阶段 4 — 容器级数据计算**：`tick()` 执行完毕后，收集所有实现了 `HasContainerData` 接口的功能类，按优先级排序后依次调用 `tickContainerData()`：

```java
runContainerDataTicks(grouped, context, tick);
```

**优先级顺序**：

| 优先级 | 功能类 | 容器级数据计算 |
|--------|--------|--------------|
| 0 | `LivingWaterBucketFunction` | 流体蔓延 + postTickSync |
| 1 | `LivingWaterWheelFunction` | 应力计算 + postTickSync |
| 2 | `LivingRedstoneFunction` | 红石信号传播 |
| 2 | `LivingRedstoneTorchFunction` | 红石信号传播（火把独立存在时） |

通过接口化设计，`ContainerLivingItemHandler.processContext()` 不再需要硬编码任何具体功能类的容器级数据计算逻辑。

**阶段 5 — 写回**：将应力与流体数据写入关联的 BlockEntity：

```java
writebackBlockEntities(context, tick);
incrementCleanup();
```

**空容器处理**：当容器内无任何活物品时，`handleEmptyContainer` 检查是否有残留红石数据，若有则再跑一次 `calculate()` 使信号归零，避免信号层"集体死掉"。

### 8.3 TickContext — Tick 级临时状态

[TickContext](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/TickContext.java) 的生命周期仅为单次 tick，包含：

| 字段 | 用途 |
|------|------|
| `occupiedSlots` | 槽位互斥集合，防止多个活熔炉处理同一输入槽位 |
| `transferredTargetSlots` | 级联传输防护，防止同 tick 内漏斗链级联传输 |
| `dirtySlots` | 脏槽位集合，tick 内被修改的槽位索引，tick 结束时批量同步 |
| `snapshot` | 容器快照，预扫描的活漏斗连接图和过滤链 |
| `fluidData` | 容器关联的流体状态 |
| `stressData` | 容器关联的应力状态 |
| `redstoneData` | 容器关联的红石信号状态（延迟获取，从 `ContainerLivingItemHandler.CONTAINER_DATA` 静态缓存的聚合条目中按 `containerKey` 取 `redstone` 字段持久化实例，确保 `edgeGrid`/`prevEdgeGrid`/`tickCounter` 跨 tick 保留） |
| `containerDataStore` | 通用容器级数据存储（`Map<Class<?>, Object>`），新数据类型无需在 TickContext 中新增字段 |
| `functionSlots` | 功能槽位缓存，processContext 分组时填充，O(1) 读取各功能的活跃槽位集合 |

**生命周期**：

TickContext 是短生命周期对象，每次 tick 创建新实例，tick 结束后自然丢弃。
JVM 年轻代 GC 可高效回收此类短生命周期对象，无需池化。

### 8.4 脏槽位批量同步

`SimpleContainerContext` 与 `TickContext` 协作，实现 tick 内脏槽位的延迟同步。

**问题**：之前每次 `setItem` 后立即调用 `syncSlotToClients` 发送同步包，一个 tick 内同一槽位可能被多次修改，产生冗余网络包。

**方案**：在 `TickContext` 中维护 `dirtySlots` 集合，`syncSlotToClients` 只标记脏，tick 结束时统一发送。

```java
// SimpleContainerContext.syncSlotToClients()
@Override
public void syncSlotToClients(int logicalSlot, ItemStack stack) {
    if (currentTickContext != null) {
        currentTickContext.dirtySlots.add(logicalSlot);  // 延迟：只标记脏
    } else {
        flushSlotSync(logicalSlot, stack);               // 立即：无 TickContext 时直接发
    }
}

// ContainerLivingItemHandler.tick() 中
TickContext tick = new TickContext(context);
if (context instanceof SimpleContainerContext simpleCtx) {
    simpleCtx.setTickContext(tick);
}
// ... 处理逻辑 ...
if (context instanceof SimpleContainerContext simpleCtx2) {
    simpleCtx2.flushDirtySlots();   // 批量发送
    simpleCtx2.setTickContext(null);
}
```

**效果**：同一 tick 内同一槽位多次修改只发送一次同步包，减少网络冗余。

### 8.5 跨容器虚影防护与大箱匹配 — syncWorldContainer 容器归属验证

`flushSlotSync` 对世界容器走 `syncWorldContainer`，它遍历所有玩家当前打开的菜单，按 `slot.getContainerSlot() == logicalSlot` 匹配槽位并发送同步包。

**原问题**：只匹配槽位索引，未验证 `slot.container` 是否属于当前容器。当容器 A 的活物品 tick 同步时，玩家若正打开容器 B，B 的同索引槽位会收到 A 的物品栈，客户端显示为无法拿取的虚影。

**修复**：在匹配条件中增加容器归属验证，`myContainers` 从 `associatedBlockEntities` 中收集所有实现了 `Container` 接口的方块实体，确保同步包只发送给真正属于本容器的槽位。

```java
// 修复前：只匹配槽位索引
if (slot.getContainerSlot() == logicalSlot && slot.container != serverPlayer.getInventory())

// 修复后：同时匹配槽位索引 + 容器归属
if (slot.getContainerSlot() == logicalSlot
    && slot.container != serverPlayer.getInventory()
    && slotBelongsTo(slot.container, myContainers))
```

**大箱匹配（2026-09-09 补丁）**：容器归属验证最初是纯实例匹配 `myContainers.contains(slot.container)`，但原版大箱子菜单的槽位容器是 `new CompoundContainer(左半BE, 右半BE)` **包装对象**而非 BE 本体——实例匹配对大箱**永远不命中**，导致活物品 DataComponent 变化的同步包从不发给大箱查看者（症状：活水车旋转动画/Tooltip、活水桶水流 Tooltip 停留在开箱快照，重开界面才恢复；单箱正常）。单箱菜单容器就是 BE 本体，故不受影响。修复为两级匹配（`slotBelongsTo`）：先实例匹配（单箱），再对 `CompoundContainer` 用其自带的 `contains(Container)`（引用相等）逐个匹配关联 BE（大箱）。

> **与 tooltip-system.md §3.2 的关系**：同一 bug 模式在 v19.1 修 `ContainerRuntimeCache.isViewingContainer`（遥测链路）时就出现过，当时只修了遥测一处，此处（组件同步链路）是漏掉的平行断点。教训已提炼为通用规则：「任何『玩家菜单 ↔ 容器实例』匹配必须兼容 `CompoundContainer.contains(be)`」（[tooltip-system.md §7 坑清单第 3 条](tooltip-system.md#7-设计原则与坑清单)）。**新增按玩家菜单匹配容器的代码时，必须检查这两个先例**。

**槽位对齐**：大箱的 IItemHandler（`InvWrapper(CompoundContainer)`，`logicalSlot` 的来源）与菜单 `CompoundContainer` 都经 `ChestBlock.combine → DoubleBlockCombiner` 生成，拼接顺序由 `ChestBlock.TYPE`（LEFT/RIGHT）归一化——无论从哪个半箱查询，`container1` 恒为左半箱，槽位索引天然一致，同步不会左右对调。

### 8.6 ContainerSnapshot — 容器快照

[ContainerSnapshot](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerSnapshot.java) 在 tick 开始时预扫描容器状态，避免传输过程中反复查询。

**核心数据**：

```java
private final int[] sourceOf;   // sourceOf[slot] = 此槽位活漏斗的 source 指向哪个槽位
private final int[] targetOf;   // targetOf[slot] = 此槽位活漏斗的 target 指向哪个槽位
private final FilterData[] filterOf;  // filterOf[slot] = 此槽位继承的过滤规则
```

**过滤链构建**：过滤规则构建已委托给 `HopperFilterBuilder`（`domain/hopper/HopperFilterBuilder.java`），`ContainerSnapshot` 在 `capture()` 中调用 `HopperFilterBuilder.buildAll()` 完成预计算。详见活漏斗技术文档。

**过滤链继承**：当多个活漏斗串联时，过滤规则会沿链传递。例如：

```
[活漏斗A: 黑名单=石头] → [活漏斗B: 白名单=钻石] → [目标槽位]
                                          ↑
                    目标槽位的 filterOf = 黑名单[石头] + 白名单[钻石]
```

过滤链使用 `visited` 集合防止循环引用。

**快照缓存与失效（修订计数）**：快照构建涉及全容器扫描 + 过滤链递归，为避免每 tick
重复构建做了**跨 tick 缓存**——`TickContext.getSnapshot()` 懒构建，经
`ContainerLivingItemHandler.getCachedSnapshot()` 按容器**修订计数**复用：修订计数未变
直接返回上次快照。修订计数由两条路径推进：

1. **内容签名兜底**（`syncContentRevision`，每 tick 阶段 1 调用）：逐槽位混入
   「物品 id + 数量」计算签名，与上一 tick 不同则 bump——任何物品增删/移动（含漏斗
   自己的传输、玩家点击）都会打破缓存。
2. **组件变更显式 bump**（`syncSlotToClients` / `setItem`）：自定义 DataComponent 不在
   内容签名里（只可能是本模组改的），这些路径手动 bump。

因此快照里的过滤链/连接图始终反映容器现状：布局或货物一变，下一 tick 快照重建、
漏斗的黑白名单随之更新（漏斗被搬到新容器后旧规则过期，同样在首 tick 重建自愈——
详见 [living-hopper-tech.md](../tech/living-hopper-tech.md) §2.4.2 存储位置沿革）。

### 8.7 ContainerFluidData — 容器流体数据

[ContainerFluidData](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/domain/water/ContainerFluidData.java) 管理容器级流体状态（活水桶的水流），独立于活物品的槽位级状态。

**核心概念**：

- `sourceEntry`（level=0）对应水源方块
- `flowEntry`（level=1~7）对应流动水方块
- 无 entry 对应空气
- 水流按 `FLOW_STEP_TICKS`（4 tick）间隔蔓延
- 水流遇到物品会推动物品到下游槽位

**水流蔓延**：BFS 从水源槽位开始，逐层向外扩展，遇到活物品（障碍物）停止。每层的 `fromSlot` 记录上游来源，形成水流树。

**物品推动（pushItems）**：

物品沿水流方向（从上游到下游）被推动。处理顺序按 level **升序**（离水源近的先处理），确保物品逐层向外移动，与水流方向一致。

| 步骤 | 说明 |
|------|------|
| 1. 构建 downstream 映射 | `fromSlot → [子节点列表]`，表示水流方向 |
| 2. 按 level 升序排序 | 先处理 level=1（离水源最近），后处理 level=7 |
| 3. 逐槽位推动 | 对每个有物品的非水源槽位，尝试推到下游子节点 |
| 4. 堆叠合并 | 目标槽位有同类物品且未满时合并，否则移到空槽位 |
| 5. 每源每 tick 一次 | 部分合并后立即 break，避免用过期数量继续合并 |

**安全约束**：

- 不推入水源槽位（活水桶所在）
- 不推入活物品槽位（活漏斗/活熔炉等）
- 使用 `moved` 集合避免同一物品被级联推动两次
- 通过 `ctx.setItem()` 统一走 `IItemHandler` 路径，确保大箱子读写一致

**生命周期**：活水桶放入 → 注册水源槽位 → 水源蔓延 → 活水桶移除 → 水源取消，但流动槽位继续干涸。过期条目的清理阈值是 120 秒未访问。

---

## 9. SlotAccessor 存储后端抽象

### 9.1 设计目标

[SlotAccessor](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/SlotAccessor.java) 将传输引擎与具体存储类型解耦。传输引擎只调用 `extract` 和 `insert`，不关心槽位背后是普通物品、活箱子、活末影箱还是跨容器。

### 9.2 接口定义

```java
public interface SlotAccessor {
    ItemStack extract(int amount, ItemStack filterType);  // 提取物品
    int insert(ItemStack stack);                          // 插入物品（会修改 stack count）
    ItemStack simulateExtract(int amount);                // 模拟提取（不修改状态）
    int simulateInsert(ItemStack stack);                  // 模拟插入（不修改状态）
    void rollback(ItemStack stack);                       // 回滚（安全兜底）
    boolean isEmpty();
    boolean isFull();
    void markTransferred();                               // 标记已传输（级联防护）
    void sync();                                          // 同步到客户端
    SlotAccessor unwrap();                                // 解包装饰器
}
```

### 9.3 模拟优先传输模式

`SlotAccessor.transfer()` 是所有传输的统一入口，采用模拟优先模式：

```
simulateExtract(amount)        → 模拟提取：源能提供多少？
    ↓
simulateInsert(simulated)      → 模拟插入：目标能接受多少？
    ↓
extract(toExtract, null)       → 确认可行，执行真实提取
    ↓
insert(extracted)              → 执行真实插入
    ↓
rollback(leftover)             → 仅当模拟与真实不一致时触发（WARN 日志）
```

**为什么采用模拟优先？**

- 避免"先提取后插入失败导致物品丢失"的经典问题
- 模拟操作不修改任何状态，可以安全地探测传输可行性
- `rollback` 仅作为安全兜底：如果模拟与真实不一致，说明存在 bug

**上层 `Container` 接口过滤**：在进入 `SlotAccessor.transfer()` 之前，`TransferPipeline` 和 `CrossContainerTransfer` 会先通过 `Container.canTakeItem`/`canPlaceItem` 过滤不可交互的槽位（详见 [5.5 Container 接口槽位过滤](#55-container-接口槽位过滤--模拟玩家操作)）。这层过滤在模拟之前执行，避免无效的模拟开销。

### 9.4 Accessor 实现类型

| 实现 | 适用场景 |
|------|---------|
| `PlainSlotAccessor` | 普通容器槽位，直接读写 `ContainerContext` |
| `LivingChestAccessor` | 活箱子，通过 `LivingChestFunction` API 操作虚拟存储 |
| `LivingEnderChestAccessor` | 活末影箱，路由模式（查路由表）或直连模式（直接读写玩家末影箱） |
| `NeighborSlotAccessor` | 邻居容器，包装 `IItemHandler` 槽位用于跨容器传输。配合 `CrossContainerTransfer` 的 `Container` 接口过滤，仅访问玩家可交互的槽位 |
| `FilteredSlotAccessor` | 装饰器，在所有操作前检查过滤规则 |

### 9.5 FilteredSlotAccessor — 过滤装饰器

[FilteredSlotAccessor](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/FilteredSlotAccessor.java) 是一个装饰器，包装任意 `SlotAccessor`，在所有操作前检查过滤规则：

```java
public ItemStack extract(int amount, ItemStack filterType) {
    ItemStack result = delegate.extract(amount, filterType);
    if (result.isEmpty()) return result;
    if (filterData != null && !ItemFilterComponent.allows(filterData, result)) {
        delegate.rollback(result);  // 不符合过滤规则，回滚
        return ItemStack.EMPTY;
    }
    return result;
}
```

**注意**：`extract` 中过滤失败会触发 `rollback`，因为物品已经从源槽位移除。这与模拟优先模式的设计一致——`rollback` 是安全兜底。

### 9.6 SlotAccessorFactory — 注册式工厂

[SlotAccessorFactory](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/SlotAccessorFactory.java) 通过注册机制解耦类型判断与创建逻辑：

```java
// 注册 Provider（按优先级从高到低）
registerProvider(LivingChestAccessor::tryCreate);      // 优先级 1：活箱子
registerProvider(LivingEnderChestAccessor::tryCreate); // 优先级 2：活末影箱
registerProvider(SlotAccessorFactory::defaultProvider); // 优先级 3：普通槽位（兜底）
```

**创建流程**：

1. 检查是否为活物品（非活箱子/活末影箱的活物品不参与传输，返回 null）
2. 遍历注册的 Provider，找到第一个匹配的
3. 所有 Provider 都不匹配时，使用 `defaultProvider`（`PlainSlotAccessor`）
4. 创建的 Accessor 自动包裹 `FilteredSlotAccessor`

> ⚠️ 第 1 条是跨容器能力的一个关键分界：**邻居侧槽位走 `createForNeighbor`（不查活物品）**，
> 本容器侧才走 `create`。因此「邻居侧特殊槽位」的替代语义（如骨粉 → 活耕地施肥）
> 不可能靠访问器工厂涌现，必须由下面的槽位交互注册表接管。

### 9.7 SlotInteractions — 注册式槽位交互

[SlotInteraction](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/SlotInteraction.java) /
[SlotInteractions](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/SlotInteractions.java)
把「货物 × 目标槽」的**替代语义**（不插入、改做别的动作）抽象成可注册条目，
是活漏斗传输层继 SlotAccessor 之后的第二个扩展点：

```java
// 注册（内置条目在 SlotInteractions 静态块）
SlotInteractions.register(new FarmlandBonemealInteraction());   // 骨粉 → 活耕地 = 施肥

// 两个分发入口 —— 三处传输分支只调这两个，与具体交互无关
SlotInteractions.canInteract(cargo, targetStack);                                     // 廉价筛选（纯谓词）
SlotInteractions.tryInteract(sourceAccessor, cargo, targetStack, level);              // 货物已知
SlotInteractions.tryInteractFromNeighbor(handler, pos, targetStack, level, filter);   // 拉取方向
```

| 维度 | 说明 |
|------|------|
| 协议 | 模拟优先（`simulateExtract(consumeAmount)` → `interact` 生效 → 才真 `extract`）；不生效不扣货不设冷却 |
| 实现约定 | `matches` 纯谓词；`interact` 只改 target；equals 零空转必须返回 false |
| **货物准入** | 两个入口都先过 `isEligibleCargo`（**唯一定义点**：活物品不作货物，活箱子/活末影箱除外）。施肥属传输语义 ⇒ **活骨粉（活物品）不施肥**；`TransferPipeline.isTransferableSource` 直接委托同一谓词，规则不会两处漂移 |
| 调用点 | 容器内 `TransferPipeline.executeInContainer` / 跨容器推送 `tryPushToNeighbor` / 跨容器拉取 `pullFromNeighbor` |
| 源槽 Accessor | 三处都用既有工厂入口（容器内/推送 `create`、拉取 `createForNeighbor`）；非箱类活物品在 `create` 处返回 null，与准入规则同口径 |
| 扩展成本 | 新增交互 = 1 个实现类 + 1 行注册，**零传输代码改动** |
| 相关文档 | [living-hopper-tech.md §6.2.1](../tech/living-hopper-tech.md)（三处调用点与扩展方式）、§6.2.2（跨容器能力覆盖矩阵） |

> **边界**：`SlotAccessor` / `SlotInteractions` 只覆盖**容器内**的传输与槽位交互。
> **物品 ↔ 世界**这条链路完全独立、不经此处——例如「放置活耕地回世界时自动种下自带作物」
> 走的是 `mixin/BlockItemMixin` + `domain/farmland/LivingFarmlandPlacement`
> （模拟玩家右键，见 [living-farmland-tech.md §3.5](../tech/living-farmland-tech.md)）。
> 新增面向世界的物品能力时不要试图往传输层挂。

---

## 10. 性能监控

### 10.1 PerfMetrics

[PerfMetrics](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/perf/PerfMetrics.java) 收集模组运行时的性能数据，每 60 秒自动打印报告。

**监控指标**：

| 指标 | 说明 |
|------|------|
| Tick 耗时 | 处理容器的平均/P99/最大耗时 |
| 活物品数量 | 每种活物品的总数 |
| 功能调用 | 每种功能被调用的次数 |
| 传输 | 活漏斗传输成功/失败次数 |

### 10.2 性能优化策略汇总

| 策略 | 位置 | 效果 |
|------|------|------|
| ContainerChunkCache | 容器发现 | 避免全量扫描所有区块 |
| 惰性检测 | 末影箱处理 | 无活物品时跳过，避免无效 InvWrapper 创建 |
| 按功能分组 | processContext | 避免 N 倍速度翻倍 |
| 反向索引 | EnderChannelRegistry | 路由清理 O(路由总数) → O(相关路由) |
| 延迟同步 | EnderChannelRegistry | tick 末尾统一发包，减少网络抖动 |
| 贪心提取 | EnderChannelRegistry | 输出槽有物品时优先提取同类型（可堆叠），避免轮询到不同类型导致传输停止 |
| IdentityHashMap 去重 | 大箱子 | 避免同一 IItemHandler 被处理两次 |
| InvWrapper 缓存 | LivingEnderChestAccessor | 直连模式避免每 tick 重复创建 |
| 瞬态数据服务端缓存 | LivingWaterBucketFunction | WaterData 瞬态字段存入 BUCKET_STATES Map，不再每 tick 写 DataComponent，消除玩家背包场景下的网络同步开销 |
| handler 统一读写 | SimpleContainerContext.setItem() | 移除 container.setItem() 路径，统一走 IItemHandler，确保大箱子读写一致，消除物品复制 bug |
| BUCKET_STATES 周期清理 | LivingWaterBucketFunction | cleanupStaleEntries() 清理 120s 未访问的瞬态缓存条目，防止内存泄漏 |
| 双时间尺度 | BucketState(lastAccessMs, lastGameTick) | lastAccessMs 用 System.currentTimeMillis() 供缓存清理，lastGameTick 用 gameTime 供 needsReset 检测 |

---

## 11. Tooltip 渲染机制（客户端）

Tooltip 是**客户端渲染**的。理解这条链路，才能解释「tooltip 显示的信息不在 NBT 里」
以及「哪些数据能显示、哪些不能」。

### 11.1 数据链路

```
服务端 tick：修改 DataComponent → context.syncSlotToClients(slot, stack)
    ↓ 槽位同步包（ClientboundContainerSetSlotPacket，含全部 networkSynchronized 组件）
客户端：物品副本更新
    ↓ 玩家悬停
ItemTooltipEvent（NeoForge 客户端事件，见 client/render/LivingItemTooltip.java）
    ↓ 各功能 addToTooltip(...) 读取客户端副本 + 实时计算
渲染
```

### 11.2 信息的三类来源

| 来源 | 例子 | 是否需要存储 |
|---|---|---|
| **纯实时计算** | 耦合管径（物品类型查表）、偏好周期（= 堆叠数）、宽带态（count==1） | 否——物品身份与堆叠数的纯函数，渲染时现算 |
| **DataComponent**（NBT + 网络双通道） | 铜灯电量 `chargeMilliFe` | 组件注册时成对声明：`persistent(CODEC)` 写 NBT，`networkSynchronized(STREAM_CODEC)` 同步客户端——**两条独立管道** |
| **服务端内存态** | `ContainerPowerData` 的周期估计/解锁度/合因子 | 不同步也不持久——**当前 tooltip 显示不了**，需阶段五 telemetry 写回组件 |

### 11.3 关键结论

1. **客户端能读什么，取决于什么被同步**：组件有 `networkSynchronized` 就能显示；
   `ContainerPowerData` 这类服务端内存态永远显示不了，除非显式写回组件；
2. **NBT 持久化与网络同步是两条独立管道**：「不在 NBT 里」不影响显示，
   「没有 networkSynchronized」才影响；
3. **服务端改组件后必须 syncSlotToClients**：漏了它，客户端副本是旧的，tooltip 显示旧值
   （原版 `broadcastChanges()` 的 `ItemStack.matches()` 检测不到自定义组件变化，见 §2.4）；
4. **`Item.TooltipContext` 当前传 `EMPTY`**（LivingItemTooltip.java）——tooltip 里查不了
   世界状态，因此「检测数据写回组件」是唯一正路，而不是在 tooltip 里反查世界。

### 11.4 与电力层 telemetry 的关系

阶段五的 tooltip 仪表盘（检测周期/相数/解锁度/功率）即按此机制实现：
服务端事件驱动算出的检测值 → 写回该槽位物品的小型 DataComponent
（参照 `LivingGrateData.sumSignal` 模式）→ `syncSlotToClients` → 客户端 tooltip 读取。
只在数值变化时同步，无额外开销。

---

## 12. 关键文件索引

| 文件 | 路径 | 职责 |
|------|------|------|
| `LivingItem.java` | `com.qiqi.li` | Mod 主类，服务端 tick 总入口 |
| `ContainerLivingItemHandler.java` | `container/` | 容器处理器，统一包装 + 按功能分组执行 + TickContext 生命周期 |
| `ContainerChunkCache.java` | `container/` | 容器区块缓存，事件驱动的容器发现 |
| `ContainerContext.java` | `container/` | 组合接口，继承 4 个子接口 |
| `LivingContainer.java` | `container/` | 基础物品读写接口 |
| `SlotInfoProvider.java` | `container/` | 槽位能力查询接口 |
| `ContainerSync.java` | `container/` | 客户端同步接口 |
| `ContainerIdentity.java` | `container/` | 容器身份标识接口 |
| `SimpleContainerContext.java` | `container/` | 统一容器上下文实现 + 脏槽位批量同步 |
| `TickContext.java` | `container/` | Tick 级临时状态 + 脏槽位集合 |
| `ContainerSnapshot.java` | `container/` | 容器快照，预扫描连接图（过滤构建委托给 HopperFilterBuilder） |
| `ContainerCompatibilityConfig.java` | `transfer/` | 容器兼容性配置 |
| `SlotResolver.java` | `transfer/` | 槽位方向解析器 |
| `SlotAccessor.java` | `transfer/` | 存储后端抽象接口 |
| `SlotAccessorFactory.java` | `transfer/` | 注册式 Accessor 工厂 |
| `SlotInteraction.java` / `SlotInteractions.java` | `transfer/` | 槽位交互接口 + 注册表/分发器（三处传输分支唯一入口） |
| `PlainSlotAccessor.java` | `transfer/` | 普通槽位访问器 |
| `FilteredSlotAccessor.java` | `transfer/` | 过滤装饰器 |
| `NeighborSlotAccessor.java` | `transfer/` | 邻居容器访问器 |
| `FilterData.java` | `transfer/` | 传输过滤规则数据（跨领域共享） |
| `PerfMetrics.java` | `perf/` | 性能监控 |
| `ContainerMonitor.java` | `debug/` | 容器监控系统，检测物品复制/丢失/活物品覆盖 |
| `ContainerMonitorCommand.java` | `debug/` | `/living_monitor` 命令注册 |
| `ContainerFluidData.java` | `domain/water/` | 容器流体数据，BFS 水流蔓延 + 物品推动 |
| `LivingWaterBucketFunction.java` | `domain/water/` | 活水桶功能类，瞬态数据服务端缓存优化 |
| `TransferPipeline.java` | `domain/hopper/` | 统一传输入口，含容器内传输的 `Container` 接口槽位过滤，复用 `CrossContainerTransfer.getNeighborContainer` |
| `HopperFilterBuilder.java` | `domain/hopper/` | 活漏斗过滤链构建（从 ContainerSnapshot 提取） |
| `CrossContainerTransfer.java` | `domain/hopper/` | 跨容器传输，含 `Container` 接口槽位过滤 + `tryPullFromNeighbor`/`tryPushToNeighbor` 核心 helper + 方向解析 + 大箱子处理 |
| `EnderRouteManager.java` | `domain/ender/` | 活末影箱路由逻辑集中管理 |