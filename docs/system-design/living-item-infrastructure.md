# 活物品基础设施系统设计

> **文档版本**: 2026.08 v4  
> **最后更新**: 2026-08-17  
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
11. [关键文件索引](#11-关键文件索引)

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

**跨容器传输** — 在 `pullFromNeighbor`、`pushToNeighbor`、`pullFromNeighborToLivingChest`、`transferBetweenNeighbors` 四个方法中，遍历邻居 `IItemHandler` 槽位时，先通过 `getNeighborContainer` 获取邻居的 `Container` 接口，再调用 `canTakeItem`/`canPlaceItem` 过滤：

```java
// CrossContainerTransfer.pullFromNeighbor() 中的过滤逻辑
Container neighborContainer = getNeighborContainer(level, neighborPos);
for (int i = 0; i < neighborHandler.getSlots(); i++) {
    if (neighborContainer != null && !neighborContainer.canTakeItem(neighborContainer, i, sourceStack)) {
        continue;  // 跳过玩家不可取的槽位
    }
    // ... 正常传输逻辑 ...
}
```

**容器内传输** — 在 `TransferPipeline.executeInContainer()` 中，提取前检查 `canTakeItem`，放入前通过模拟提取检查 `canPlaceItem`：

```java
// TransferPipeline.executeInContainer() — 提取前检查
Container hostContainer = getHostContainer(ctx);
if (hostContainer != null && !hostContainer.canTakeItem(hostContainer, sourceSlot, sourceStack)) {
    return false;
}

// 放入前检查（模拟提取目标物品后验证）
if (hostContainer != null) {
    ItemStack simulated = source.simulateExtract(Math.min(stackSize, maxTransfer));
    if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
        return false;
    }
}
```

**降级策略**：如果邻居/宿主容器未实现 `Container` 接口（如纯 `IItemHandler` 的模组），则跳过过滤，回退到纯 `IItemHandler` 模式——保持原有行为不变。

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

### 7.2 玩家盔甲槽绕过

玩家背包的盔甲槽位（slot 36-39）有严格限制——只能放入对应类型的盔甲。活物品需要绕过这个限制：

```java
@Override
public void setItem(int logicalSlot, ItemStack stack) {
    handler.extractItem(logicalSlot, Integer.MAX_VALUE, false);
    ItemStack remaining = handler.insertItem(logicalSlot, toInsert, false);
    if (!remaining.isEmpty() && isArmorSlot(inventory, logicalSlot)) {
        // 活漏斗绕过盔甲槽限制，直接设置物品
        inventory.armor.set(logicalSlot - 36, toInsert);
        syncSlotToClients(logicalSlot, toInsert);
    }
}
```

这允许活漏斗将物品传输到盔甲槽位（如"方块放头上"等趣味玩法）。

### 7.3 客户端同步实现

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

[ContainerLivingItemHandler.processContext](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerLivingItemHandler.java) 采用两阶段设计：

**阶段 1 — 扫描**：遍历容器中所有物品，将活物品按功能类型分组收集。

**阶段 2 — 执行**：对每种功能只调用一次 `tick()`，传入该容器中所有拥有此功能的活物品列表。

```java
// 阶段 1：按功能分组
Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = new LinkedHashMap<>();
for (int i = 0; i < context.getSize(); i++) {
    ItemStack stack = context.getItem(i);
    if (LivingItemManager.isLivingItem(stack)) {
        var functions = LivingItemManager.getApplicableFunctions(stack);
        for (var function : functions) {
            grouped.computeIfAbsent(function, k -> new ArrayList<>())
                    .add(new LivingItemFunction.SlotEntry(i, stack));
        }
    }
}

// 阶段 2：按功能调用
for (var entry : grouped.entrySet()) {
    entry.getKey().tick(entry.getValue(), context, tick, level);
}
```

**为什么按功能分组而不是逐个调用？**

如果逐个调用 `tick`，每个活物品独立推进自己的状态，导致总速度随活物品数量线性增长（N 个活熔炉 = N 倍速度）。按功能分组后，由功能实现自行决定如何分配处理（如活熔炉每 tick 只处理一个），从根本上避免速度翻倍。

**容器级数据计算**：`tick()` 执行完毕后，`processContext` 收集所有实现了 `HasContainerData` 接口的功能类，按优先级排序后依次调用 `tickContainerData()`：

```java
// 收集 HasContainerData 实现者
List<Map.Entry<LivingItemFunction, List<SlotEntry>>> hcdEntries = new ArrayList<>();
for (var entry : grouped.entrySet()) {
    if (entry.getKey() instanceof HasContainerData) {
        hcdEntries.add(entry);
    }
}
// 按优先级排序（数字越小越先执行）
hcdEntries.sort(Comparator.comparingInt(e -> ((HasContainerData) e.getKey()).getPriority()));

// 依次执行容器级数据计算
for (var entry : hcdEntries) {
    ((HasContainerData) entry.getKey()).tickContainerData(entry.getValue(), context, tick);
}
```

**优先级顺序**：

| 优先级 | 功能类 | 容器级数据计算 |
|--------|--------|--------------|
| 0 | `LivingWaterBucketFunction` | 流体蔓延 + postTickSync |
| 1 | `LivingWaterWheelFunction` | 应力计算 + postTickSync |
| 2 | `LivingRedstoneFunction` | 红石信号传播 |
| 2 | `LivingRedstoneTorchFunction` | 红石信号传播（火把独立存在时） |

通过接口化设计，`ContainerLivingItemHandler.processContext()` 不再需要硬编码任何具体功能类的容器级数据计算逻辑。

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
| `redstoneData` | 容器关联的红石信号状态 |
| `containerDataStore` | 通用容器级数据存储（`Map<Class<?>, Object>`），新数据类型无需在 TickContext 中新增字段 |
| `functionSlots` | 功能槽位缓存，processContext 分组时填充，O(1) 读取各功能的活跃槽位集合 |

**对象池复用**：

TickContext 通过 `ThreadLocal` 对象池复用，减少 GC 压力：

```java
private static final ThreadLocal<TickContextPool> POOL = ThreadLocal.withInitial(TickContextPool::new);

public static TickContext acquire(ContainerContext ctx) {
    return POOL.get().acquire(ctx);  // 池中有则复用，否则创建新实例
}

public void release() {
    POOL.get().release(this);  // 归还到池中（池满则丢弃，最多缓存 4 个）
}
```

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
TickContext tick = TickContext.acquire(context);
if (context instanceof SimpleContainerContext simpleCtx) {
    simpleCtx.setTickContext(tick);
}
// ... 处理逻辑 ...
if (context instanceof SimpleContainerContext simpleCtx2) {
    simpleCtx2.flushDirtySlots();   // 批量发送
    simpleCtx2.setTickContext(null);
}
tick.release();
```

**效果**：同一 tick 内同一槽位多次修改只发送一次同步包，减少网络冗余。

### 8.5 ContainerSnapshot — 容器快照

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

### 8.5 ContainerFluidData — 容器流体数据

[ContainerFluidData](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/container/ContainerFluidData.java) 管理容器级流体状态（活水桶的水流），独立于活物品的槽位级状态。

**核心概念**：

- `sourceEntry`（level=0）对应水源方块
- `flowEntry`（level=1~7）对应流动水方块
- 无 entry 对应空气
- 水流按 `FLOW_STEP_TICKS`（4 tick）间隔蔓延
- 水流遇到物品会推动物品到相邻空槽位

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
| 对象池 | TickContext 对象池命中率 |
| 传输 | 活漏斗传输成功/失败次数 |

### 10.2 性能优化策略汇总

| 策略 | 位置 | 效果 |
|------|------|------|
| ContainerChunkCache | 容器发现 | 避免全量扫描所有区块 |
| 惰性检测 | 末影箱处理 | 无活物品时跳过，避免无效 InvWrapper 创建 |
| 按功能分组 | processContext | 避免 N 倍速度翻倍 |
| TickContext 对象池 | TickContext | 复用实例，减少 GC |
| 反向索引 | EnderChannelRegistry | 路由清理 O(路由总数) → O(相关路由) |
| 延迟同步 | EnderChannelRegistry | tick 末尾统一发包，减少网络抖动 |
| 贪心提取 | EnderChannelRegistry | 输出槽有物品时优先提取同类型（可堆叠），避免轮询到不同类型导致传输停止 |
| IdentityHashMap 去重 | 大箱子 | 避免同一 IItemHandler 被处理两次 |
| InvWrapper 缓存 | LivingEnderChestAccessor | 直连模式避免每 tick 重复创建 |

---

## 11. 关键文件索引

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
| `TickContext.java` | `container/` | Tick 级临时状态 + 对象池 + 脏槽位集合 |
| `ContainerSnapshot.java` | `container/` | 容器快照，预扫描连接图（过滤构建委托给 HopperFilterBuilder） |
| `ContainerCompatibilityConfig.java` | `transfer/` | 容器兼容性配置 |
| `SlotResolver.java` | `transfer/` | 槽位方向解析器 |
| `SlotAccessor.java` | `transfer/` | 存储后端抽象接口 |
| `SlotAccessorFactory.java` | `transfer/` | 注册式 Accessor 工厂 |
| `PlainSlotAccessor.java` | `transfer/` | 普通槽位访问器 |
| `FilteredSlotAccessor.java` | `transfer/` | 过滤装饰器 |
| `NeighborSlotAccessor.java` | `transfer/` | 邻居容器访问器 |
| `FilterData.java` | `transfer/` | 传输过滤规则数据（跨领域共享） |
| `PerfMetrics.java` | `perf/` | 性能监控 |
| `TransferPipeline.java` | `domain/hopper/` | 统一传输入口，含容器内传输的 `Container` 接口槽位过滤 |
| `HopperFilterBuilder.java` | `domain/hopper/` | 活漏斗过滤链构建（从 ContainerSnapshot 提取） |
| `CrossContainerTransfer.java` | `domain/hopper/` | 跨容器传输，含 `Container` 接口槽位过滤（方向解析 + 大箱子处理） |
| `EnderRouteManager.java` | `domain/ender/` | 活末影箱路由逻辑集中管理 |