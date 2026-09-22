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
ServerTickEvent.Pre
    ↓
1. 遍历所有在线玩家 → 处理玩家背包
    ↓
2. 遍历所有维度 → flushPendingRescans() → ContainerChunkCache.getProcessableChunks(level)
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
区块加载   → 只登记坐标，下一 tick 扫描该区块，若有容器则加入缓存
区块卸载   → 从缓存中移除（实际由 cleanupStaleEntries 兜底，见下）
方块放置   → 下一 tick 重新扫描该区块
方块破坏   → 下一 tick 重新扫描该区块
```

**两条独立的判据**（别混）：**发现**（扫描）覆盖**所有已加载区块**（`getChunkNow`）；
**处理**（读能力 / 读邻居）只针对 **ticking 区块**（`isPositionTicking`，见 §3.2.1）。
扫描不做 ticking 过滤是**必须的** —— 区块提升到 ticking **没有**对应事件可登记，
若扫描也过滤，那些"加载后一直没进 ticking 区"的区块就永久发现不到了。

> ⚠️ **红线：区块加载事件回调里禁止任何世界交互。**
>
> `ChunkEvent.Load` 在区块 FULL 任务的主线程回调里触发
> （`ChunkStatusTasks.full()` → `EVENT_BUS.post(...)`），此刻主线程正处在"区块加载"内部，
> 且该区块尚未保证提升到 `ChunkStatus.FULL`。此时做能力查询会执行第三方能力提供者，
> 其中有些（如 Create 的传送带）会去查**别的区块**的方块实体 → 触发同步区块加载
> （`ServerChunkCache.getChunk(..., requireChunk=true)` → `managedBlock` + `join`）
> → 去等一个"只能由主线程自己推进"的 chunk future ⇒ **自己等自己，服务端线程冻结**。
>
> NeoForge 在 `ChunkEvent.Load` 的 javadoc 里已明确警告：
> "You will cause chunk loading deadlocks if you don't delay your level interactions."
> 原版为此在 `ServerChunkCache.getChunk`/`getChunkNow` 里加了 `ChunkHolder.currentlyLoading`
> 旁路，但它**只覆盖"正在加载的那个区块本身"** —— 于是"方块在同一个区块内"侥幸不卡，
> "方块指向另一个区块"必卡。
>
> **实测事故（2026-09-18，living_item 1.3.2 + Create 6.0.10）**：进世界即服务端线程卡死
> （GUI 能动、不能合成、不能 tp、玩家掉虚空）。堆栈
> `ContainerChunkCache.onChunkLoad → BeltBlockEntity.initializeItemHandler → ServerChunkCache.getChunk`。
> 复现规律：单个传送带（控制器同区块）不卡；**跨区块传送带必卡**。
>
> **因此**：`onChunkLoad` 只写内存集合（唯一允许的调用是 `level.dimension()` 这类纯 getter），
> 真正的扫描一律推迟到 `flushPendingRescans`（由 `ServerTickEvent.Pre` 调用，
> 主线程不在任何区块任务内部）。`scanChunkForContainers` 是唯一做能力查询的重扫入口，
> **不要把它挪回事件回调里**。回归守卫：`ContainerChunkCacheChunkLoadTest`。

**延后重扫队列**：`pendingRescans`（按维度）同时承接区块加载、方块放置、方块破坏三类事件。
`flushPendingRescans` 每 tick 限量 `MAX_RESCANS_PER_TICK = 64` 个，剩余的留到下一 tick
（区块加载会成批涌入，视距 12 约 600 个区块，避免能力查询峰值堆到同一 tick）。
区块没到 FULL（`getChunkNow` 返回 null）就跳过 —— **绝不主动加载区块**；不会漏，
它真正加载完成时会再触发一次 `ChunkEvent.Load` 重新登记。

> 📌 **同一机制的"温和版"（tick 阶段仍可能发生，不是死锁但代价高）**
>
> tick 阶段做能力查询不会死锁，但若某个模组的 provider 去查**未加载**的区块，
> 就会走同一条 `getChunk(requireChunk = true)` —— 主线程**原地等**它生成完。
> 代价有三层：
> ① `ChunkPyramid.GENERATION_PYRAMID` 里 `FULL` 的累积依赖是 **`STRUCTURE_STARTS` 半径 8**
> ⇒ 要凑齐 **17×17 = 289 个区块**，目标区块本身还要跑完整状态链（含特性/结构/光照）；
> ② 这个 tick 的墙钟时间把上述生成时间全吃进去（专用服务器 `max-tick-time` 默认 1 分钟，
> 超了被看门狗判崩；单人存档无看门狗，表现为纯卡死）；
> ③ `TicketType.UNKNOWN` 的 lifespan 是 **1**（`Ticket.timedOut` = `currentTick - createdTick > 1`，
> `purgeStaleTickets` 每 tick 一次）⇒ 一次性触发留下的票据约 2 tick 后失效、区块被卸载。
> **但 `DistanceManager.addTicket` 会 `addOrGet` + `setCreatedTick(...)` 续期** ⇒ 连续每 tick
> 触发就是每 tick 续期、**那个区块被永久保持加载**（不是"反复重生成"；反复重生成只出现在
> 间歇触发、或容器所在区块来回卸载重载时）。
>
> 现状：`processLevelContainers` 每 tick 对每个 BE 调 `getCapability`，走这条路径；
> 已实测的 Create 传送带有 `isLoaded(controller)` 守卫，未加载时直接 return，不会触发。
> **暂未加额外防护** —— 排查触发条件：spark 火焰图里 `getChunkCacheMiss` / `chunkLoad`
> 占比异常高时再回来查（注意 `PerfMetrics` 覆盖不到 provider 内部，见 §10.3）。

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
| 1 | `onChunkUnload` | 区块卸载时 | **不立即移除**（与 `ChunkEvent.Load` 的时序窗口，见下）；只清 `EnderChannelRegistry` 路由 |
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

### 3.2.1 强制加载入口清单（2026-09-18 全量排查）

**判据**：`level.getBlockState(pos)` / `level.getBlockEntity(pos)` /
`level.getCapability(...BLOCK, pos, ...)` 里的 `pos` 若在**未加载区块**，就走
`getChunk(requireChunk = true)` ⇒ 强制加载。

⚠️ **能力查询本身就是加载入口** —— `BlockCapability.getCapability` 内部先
`level.getBlockState(pos)` 再 `getBlockEntity(pos)`，**不需要显式 `getChunk`**。

**为什么这是结构性问题**（`ChunkLevel` 常量）：

| 门槛 | ticket level |
|---|---|
| `FULL_CHUNK_LEVEL`（已加载） | **33** |
| `BLOCK_TICKING_LEVEL`（随机刻） | 32 |
| `ENTITY_TICKING_LEVEL`（**方块实体 tick**） | **31** |

原版保证：方块实体只在 ≤31 的区块里跑 ⇒ 它的邻居 ≤32 ⇒ **必然已加载，读邻居免费**。
本框架用 `getChunkNow` 拿的是 **≤33 的"已加载"区（含 32/33 两圈）** ⇒ 容器落在 **33 圈
（已加载但不 tick 的最外圈）** 时，它的邻居落在 **34+（生成余量圈，未加载）** ⇒ 强制加载。
**危险带 = 最外一圈区块**（视距 12 时约 625 区块中的 96 个）。

| 入口 | 频率 | 触发前提 |
|---|---|---|
| `ContainerRedstoneData.sampleFaceInput`（`getSignal` + `getBlockState`） | 每 tick × 每容器 × 4 水平方向 | 容器含**活红石系**物品（9 个 Function 才调 `calculate`）且落在危险带 |
| `CrossContainerTransfer.getNeighborHandler`（`getCapability`） | 每 tick × 每个在传输的**活漏斗** | 容器落在危险带 |
| `ContainerLivingItemHandler.processContainerAt`（`getBlockEntity(另一半)`） | 每 tick × 每个**大箱子** | 大箱子**跨区块边界**（一半在危险带） |
| `StressOutputManager.apply` / `CreateIntegration`（`getBlockEntity(below)`） | 每 tick × 每个**活水车** | 容器**正下方一格**跨区块边界 |
| `ExplosionComponent` 阶段1 读方块 + 阶段2/3 `getChunk` | 爆炸时一次 | 爆炸半径越过加载区边界（威力随数量缩放） |
| `TeleportHelper` / `SableIntegration`（`getChunk` 直调） | 传送时一次 | **有意保留**（传过去前必须先备好目标区块） |

**已做守卫的**：`LivingEnderChestAccessor`（多处 `isLoaded` 前置）、`ContainerEnergyStorage`
（只读 `be.getBlockPos()` 自身位置，不读邻居）、`flushPendingRescans`（`getChunkNow`）。

> ✅ **方向 A 已实施（2026-09-18）**：`ContainerChunkCache.getProcessableChunks(ServerLevel)`
> 在处理前过一道 `isPositionTicking`，**上面表里属于本模组自己的 5 个入口全部自动安全** ——
> 因为 `ChunkMap.prepareTickingChunk` 用 `getChunkRangeFuture(holder, 1, FULL)`，
> **ticking 区块的 3×3 邻域必然已是 FULL** ⇒ 一格距离的邻居永远已加载，读它免费。
> 剩余未覆盖：`ExplosionComponent`（半径 >1 格，待定口径，见 §3.2.2）与第三方 provider
> （`getCapability` 会执行任意模组代码，不可控面）。
> 回归守卫：`ContainerChunkCacheChunkLoadTest`「只处理 ticking 区」用例。

> ⚠️ **衍生风险：逐圈外扩（未实测；方向 A 后理论上已封死）**。被钉住的区块会触发
> `ChunkEvent.Load` → 进本类缓存 → 若它里面也有需要读邻居的活物品，就会读**更外一圈**。
> 但被钉住的是 **33 圈（不 ticking）** ⇒ 方向 A 下不再被处理 ⇒ **不再读它的邻居 ⇒ 链条断掉**。
> 验证方法：玩家静止不动反复执行 `/living_monitor cache`，观察 `loaded区块` 是否持续增长
> 或明显超过视距基准（见 §3.2「可观测性」）。

**发生概率 = 100%**（2026-09-18 结论，更正先前"小几率"的判断）：加载区边界随玩家移动扫过世界，
**任何需要读邻居的活物品容器迟早会被扫进危险带**。所以问题不是"会不会"，而是"多频繁 / 是否持续恶化"：
玩家在基地内部静止 → 边界远离，不触发；玩家移动（尤其把基地甩在身后）→ 边界扫过基地，
逐个容器触发一次。

**后果清单**：

| # | 后果 | 持续性 |
|---|---|---|
| 1 | 单 tick 卡顿（首读要凑 289 区块足迹；未探索区域是**真生成**） | 每次触发一次 |
| 2 | 移动中周期性卡顿（沿途每个活物品容器各一次） | 移动期间反复 |
| 3 | 邻居区块被**永久钉住**（票据每 tick 续期 ⇒ 超出视距仍加载）+ 连带内存、定期保存（存档体积 / 磁盘 IO）、重进存档变慢 | 容器所在区块加载期间 |
| 4 | **语义漂移**：活物品在"已加载但不 tick"的区块里继续工作（原版 BE 只在 ≤31 跑） | 持续 |
| 5 | **逐圈外扩**（见上） | 未实测 |
| 6 | 诊断困难：`PerfMetrics` 看不到 provider 内部（§10.3），要用 spark 看 `getChunkCacheMiss` / `chunkLoad` | — |
| 7 | **传染**：强制加载是同步的 ⇒ 被加载区块的 `ChunkEvent.Load` 在我们调用栈里被 post ⇒ 其他模组的 Load 处理器此刻运行在"主线程正阻塞在 `managedBlock` 内"的状态，若它们也做世界交互就会撞同一个死锁 | 每次强制加载 |

> 第 7 条不是本模组独有（任何同步加载都会这样），但**我们等于主动制造了这个窗口**。
> 反过来：修复后的 `onChunkLoad` 只登记坐标 ⇒ 这个嵌套的 Load 事件对我们自己是安全的
> —— 修复前它还会造成 scan → 加载 → Load → scan 的**递归重入**。

> 📌 **修复方案与状态（2026-09-18）**
>
> - ✅ **方向 A 已实施 —— 把处理范围从 loaded 区收窄到 ticking 区。**
>   判据由 `getChunkNow() != null` 换成 `chunkSource.isPositionTicking(chunkPos)`，
>   落点是 `ContainerChunkCache.getProcessableChunks(ServerLevel)`（处理侧唯一入口）。
>   差别**恰好只有最外一圈**（33 圈 = 危险带）—— `isPositionTicking` 对应
>   `FullChunkStatus.BLOCK_TICKING`（32）。
>   收益：① 本模组自己的读邻居入口**全部自动安全**（ticking 区块的 3×3 邻域必为 FULL，
>   见上）；② 被钉住的邻居（33 圈）不再被处理 ⇒ **外扩必然在 1 圈处停止**；
>   ③ 顺带消除「活物品在非 tick 区工作」的语义漂移。
>   代价：最外一圈区块里的活物品停摆（玩家看不见那里）。
>   ⚠️ 实现注意：`isPositionTicking` 只查票据距离（**不受 tick 冻结影响**），但区块提升到
>   BLOCK_TICKING 前返回 false ⇒ 刚加载的区块可能晚 1 tick 才开始工作。
>   ⚠️ **扫描（`flushPendingRescans`）不做此过滤** —— 见 §3.2「两条独立的判据」。
> - ✅ **可观测性已实施**：`ContainerChunkCache.describeCacheStats(ServerLevel)` 输出
>   `缓存区块 / 可处理 / loaded区块 / 视距基准`；调试命令 **`/living_monitor cache`**
>   （同时写日志）。这是判断"钉住 / 外扩"的**唯一直接观测量**。
> - ⏳ **方向 B（爆炸）待定口径** —— 见 §3.2.2。
> - ⏳ **方向 C（备选，未采用）**：逐处加 `isLoaded` 守卫，或传输层改用 `BlockCapabilityCache`
>   （NeoForge 官方推荐；其 `getCapability()` 自带 `if (!level.isLoaded(pos)) return null`
>   —— 不强制加载 + 失效自动重查。代价：需按 `(level, pos, side)` 管理生命周期，不能每 tick 新建）。
>   方向 A 已覆盖本模组自己的入口，故暂不引入。
> - **不可控面（无法消除）**：`getCapability` 会执行**第三方 provider**，它可以伸到任意距离
>   （Create 传送带即一例，它自带 `isLoaded` 守卫）。方向 A 覆盖不了这一面 ——
>   只能记录在案，遇到实测问题再针对性处理。

### 3.2.2 越界爆炸的处理逻辑（2026-09-18，探讨中）

**暴露面比"读邻居"大得多**：`radius = DEFAULT_BASE_RADIUS(4.0) × √TNT数`，球体横跨多个区块。

| TNT 数 | 模式 | radius（格） | 球体覆盖区块（水平） |
|---|---|---|---|
| 16 | 普通（≤64） | 16 | 3×3 = 9 |
| 64 | 普通 | 32 | 5×5 = 25 |
| 128 | 大当量（>64） | 45 | 7×7 = 49 |
| 1024 | 大当量 | 128 | 17×17 = 289 |
| 3456 | 超级爆炸（>3456） | 235 | **31×31 = 961** |

未加载区块一旦被 `getBlockState` 读到 ⇒ 强制加载，**每个区块 289 足迹** ⇒ 与爆炸本身的
O(r³) 叠加，直接打爆 tick。

#### ❌ 已否决的口径：「未加载区块整块跳过」

曾以"玩家看不见加载区外 + 与超级爆炸模式已有行为一致"为由提出，**2026-09-18 用户否决**：

> 「未加载区块不爆炸的话，**爆炸的语义就残缺了**。」

**否决理由（重新框定问题）**：「残缺」的本质不是少了几个方块，而是
**爆炸的作用范围由加载状态决定** —— 加载状态是**实现细节**，不该泄漏到游戏语义里。
同一个 64 TNT 爆炸，玩家站的位置不同 → 结果不同，这是**不可解释的行为**。
所以目标不是"要不要炸未加载区块"，而是：**让作用范围由物理（半径）决定，同时不阻塞主线程**。

#### 原版先例：TNT 引信在未 tick 区块**冻结**（2026-09-18 用户提出，已核实）

用户观察：「原版 TNT 在未加载区块爆炸，需要玩家靠近加载区块后才会真的爆炸。」**准确**，
机制比"靠近才爆炸"更精确 —— **引信冻结、随区块持久化、回来续走**：

| 环节 | 机制（已核实源码） |
|---|---|
| 实体只在 ticking 区块 tick | `ServerLevel.EntityCallbacks.onTickingStart/End` → `entityTickList.add/remove`；`ServerLevel.tick()` 只遍历该列表 ⇒ 区块不再 entity-ticking 时实体**移出 tick 列表**（不是消失） |
| 引信值持久化 | `PrimedTnt.addAdditionalSaveData/readAdditionalSaveData` 存 `"fuse"` 到区块 NBT |
| 玩家回来 | 区块重新 ticking → 实体恢复 → 引信**从冻结处继续** → 真的爆炸 |

**参考价值极大 —— 它给出了"未观测处不演化"的现成语义先例**：

> **世界只在被观测的地方演化；未观测处的演化被推迟到观测时，状态随区块持久化。**

这条原则下，「未加载区块不**立即**爆炸」**不是残缺，而是原版语义** ——
**前提是状态持久化 + 玩家回来时真的炸**。
⇒ 这也**修正了先前把"跳过"与"延迟到观测时"混为一谈的错误**：用户否决的是"跳过"（永不发生），
而原版支持的是"延迟"（最终一定发生）。

**但原版能"免费"做到，我们不能照抄** —— 差异在**账本的位置**：

| | 原版 TNT | 本模组爆炸 |
|---|---|---|
| 账本是什么 | `PrimedTnt` **实体**（fuse 字段） | 活 TNT 的 **DataComponent 引信** |
| 账本在哪 | **爆炸发生的那一个区块** | 容器所在区块（≠ 爆炸范围） |
| 爆炸范围 | 半径 4 ⇒ 全在 ticking 区块 + 1 格缓冲内 ⇒ **必然已加载** | 半径可达 235 ⇒ **跨几十上百区块** |

⇒ 原版不需要账本机制（实体本身就是账本，且与爆炸范围同区块）；**我们必须额外做一步**：
一个**全局**的待炸账本。**注意不能把账本挂在爆炸中心的区块上** —— 玩家从半径边缘进入时
中心可能不在加载区（视距 12 = 192 格 < 半径 235），账本不可见 ⇒ 又变残缺。

#### 三个候选（含原版模型）

| 方案 | 语义 | 原版先例 | 主线程阻塞 | 加载开销 | 复杂度 |
|---|---|---|---|---|---|
| ~~跳过~~ | **残缺** ✗（永不发生） | 无 | 无 | 无 | 极低 |
| **A. 异步预加载 + 分帧** | 立即完整 ✓ | **无**（原版从不主动生成区块） | 无 ✓ | 未加载区块**会真生成** | 中 |
| **B. 全局账本 + 自然加载时应用** ⭐ | 延迟完整 ✓ | **有**（= TNT 引信冻结模型） | 无 ✓ | **零**（区块本来就要加载） | 中高（需持久化） |

**方案 B（推荐，即原版模型）**——「未观测的地形变更推迟到观测时」：

1. 爆炸时：已加载部分立即处理 + 对未加载部分记一条**参数化**账本
   「中心 + 半径 + 模式 + 时间戳 + **已完成位图**」（位图 961 bit ≈ 121 字节，**不是**区块坐标清单）
2. 区块**自然加载**时（`ChunkEvent.Load` 登记 → 下一 tick 应用，**完全符合 §3.2 / §3.2.1 两条红线**）：
   若该区块落在某条未完成账本范围内且位图未标记 → 应用破坏 → 置位
3. 持久化：世界级 `SavedData`（参数化 ⇒ 极紧凑）。**必须是全局的** —— 挂中心区块会因
   玩家从边缘进入而不可见（见上）。
4. **可见性补偿**：应用破坏时在该位置 `playSound` + 粒子。原版玩家能看到"TNT 还在那儿等着"，
   我们未观测的破坏没有任何预告 ⇒ 补一次声光，让"地形炸开"在因果上说得通。

**方案 A（备选）**——复用超级爆炸骨架：

1. 几何算受影响区块列表（**纯计算，不碰世界**）
2. 已加载 → 立即处理；未加载 → `chunkSource.addRegionTicket(...)` 申请**异步**加载
   （API 已核实为 public，票据带 lifespan 会自动过期；**绝不调 `getChunk(requireChunk=true)`**）
3. 每 tick 检查 `getChunkNow` 就绪的区块 → 处理 → 出列；完成 / 超时 → `removeRegionTicket` 释放
4. **必须限速**（每 tick 最多申请 N 个）—— 否则 961 个区块同时排队生成会打爆 worldgen 队列

**推荐 B 的理由**（2026-09-18 修正，先前推荐 A）：

1. **原版先例** —— 它不是一个"新设计"，而是**把原版已经确立的语义（世界只在观测处演化）
   应用到爆炸范围上**。玩家对"我回来它才炸"有认知基础。
2. **零加载开销** —— A 会主动生成几百个区块并**永久留在存档**（玩家可能再也不去）；
   原版的原则恰恰相反（从不主动生成）。
3. 引信阶段**已经天然符合原版**（活 TNT 引信在 DataComponent，容器只在 ticking 区被处理
   ⇒ 引信自动冻结），B 让"爆炸那一刻"也遵循同一模型 ⇒ **整条链路口径统一**。

**B 的代价要诚实说明**：需要 `SavedData` + 完成位图 + 与三种破坏方式对接；且"走过去地形才炸开"
虽然符合原版模型，但原版有"引信还在闪"的预告而我们没有 ⇒ 靠第 4 条的声光补偿。

**推荐**：**方案 B**（原版模型：账本 + 自然加载时应用），A 降为备选 —— 见上方"推荐 B 的理由"。
若最终选 A，**必须配限速 + 总上限 + 超时**三个安全阀。

> ✅ **方案 B 已实施（2026-09-18）**。落地形态：
> `ExplosionParams`（参数 + 位图索引映射，`bitIndex ↔ chunkAt` 互逆）+ `ExplosionLedger`
> （世界级 `SavedData`，参数 + `long[]` 完成位图）+ 唯一破坏入口
> `ExplosionComponent.applyToChunk(level, params, chunk)`。
> 三种模式**统一为逐区块执行**（用户认可的方向），立即阶段与延迟阶段走同一个函数
> ⇒ 同一场爆炸无论区块何时加载，结果一致。
> 每 tick 预算 `MAX_CHUNKS_PER_TICK = 32`；**未加载区块一律丢弃、靠 `ChunkEvent.Load` 重新登记**
> （不轮询）；只有"已加载但没进预算"的才 carryOver。
> 同时修掉两个既有 bug：① 旧 `PENDING_SUPER_EXPLOSIONS` 服务端关闭不清理（内存泄漏）
> ② `tickAll` 每 tick 只处理 1 个区块且跳过未加载时无条件推进（静默残缺）。
> 实现细节见 [living-tnt-tech.md](../tech/living-tnt-tech.md) §4.3；守卫测试
> `ExplosionParamsTest`（4 项）+ `ExplosionLedgerTest`（9 项）。

#### 多场爆炸叠加（2026-09-18 推演 + 加固）

「上一场没炸完，又引爆了多个不同等级的爆炸」这个场景逐条推演过：

| 情形 | 结论 |
|---|---|
| 重叠区块落在多条条目内 | 对**每条条目各处理一次**。破坏**幂等**（第二次看到的已是空气）⇒ 不会重复掉落、不会报错。守卫：`overlappingEntries_eachAppliedOncePerEntry` |
| 不同等级 / 不同网格（SUPER 31×31 + NORMAL 3×3） | `bitIndexOf` 按各自 params 独立计算 ⇒ 互不干扰 ✓ |
| 掉落物归属 | **取决于处理顺序**（先炸的先掉落、后炸的看到空气）。顺序由队列迭代序决定 ⇒ 不确定，但玩家无法观测"本该掉什么" ⇒ 可接受（原版多 TNT 先后爆炸同理） |
| 预算被多条目瓜分 | 预算是 **flush 级**（不是每条目）⇒ 同时进行 N 场时每场都变慢（公平性问题，非 bug） |
| 队列积压 | 遍历成本 O(待检查区块数)，但只处理 32 个/tick。积压上限 = 各条目区块数之和 |

**推演中发现并修掉的两个真问题**：

1. ⚠️ **队列整体替换会丢新登记**（已修）。原写法在 flush 结束时 `put(carryOver)` / `remove`
   整体替换队列 —— 若**同 tick 期间又引爆一场**（`schedule` → `scheduleCheck`），新登记的区块
   会被一起丢掉；而它们**已经加载**、不会再触发 `ChunkEvent.Load` ⇒ **永远不炸**。
   改为：遍历**快照** + 只 `remove` 本 tick 处理过的区块，队列永不整体替换。
   守卫：`newRegistrationsDuringFlushSurvive`。
2. ⚠️ **账本满时静默丢整场爆炸**（已修）。原写法 `schedule` 超上限直接 `return false`，
   而调用方忽略返回值 ⇒ 声光与实体伤害已生效、**方块一个没坏** —— 玩家会以为模组坏了。
   改为：调用方据此**降级为「只炸当前已加载的世界」**（`applyToLoadedChunks`，只用
   `getChunkNow` 不强制加载）+ WARN 日志。守卫：`schedule_rejectsWhenFull`。

**已知遗留（未处理，非 bug）**：
- **条目可能长期驻留**：未加载区块若玩家再也不去，条目永不完成 ⇒ 账本里长期占着约 130 字节/条
  （256 条上限 ≈ 33 KB）。**有意不设过期** —— 过期就等于放弃，与用户否决的"跳过"同性质。
- **同位置重复引爆不去重**：自动装置连续引爆会产生多条同参数条目。合并是安全的
  （第二次爆炸看到的是空气），但会改变"掉落物"语义（NORMAL 模式下第二次本该不掉落），
  故**未实现**。

#### 复审：如果重来，哪里能更优雅（2026-09-18）

**骨架不需要推倒。** 逐个排除了替代方案：

| 替代 | 为什么不行 |
|---|---|
| **实体做账本**（最原版：像 `PrimedTnt` 那样把待办挂在实体上） | **可达性缺陷** —— 玩家从爆炸范围边缘进入时，中心区块可能不在加载区（视距 192 < 半径 235）⇒ 实体不在内存 ⇒ 账本不可见 ⇒ 该区块不炸。原版没这问题是因为它的账本（引信）与爆炸范围在**同一个区块** |
| **异步预加载**（申请 ticket 让区块自己加载出来） | 会**真生成**几百个区块并永久留在存档（玩家可能再也不去）—— 原版从不主动生成区块 |
| **跳过未加载** | 语义残缺（用户否决） |

⇒ **账本是当前约束下的唯一合理选择**。

**三处可以更优雅（按"值不值得动"排序）**：

1. ⭐ **把"位图 + 独立队列"合并成"每条目一个待办集合"**（最值得，但**建议暂不重构**）。
   现在描述同一件事的状态有 3 个：`done[]` 位图（持久化真相）+ `pendingChecks`（内存调度）
   + `remaining`（计数）。改成 `LinkedHashSet<ChunkPos>`（只放"还没炸的区块"）后：
   - 消除 3 个互逆索引方法（`bitIndex` / `chunkAt` / `bitIndexOf`）
   - 消除"网格定索引 / 圆定要不要炸"的**双判据**（列表只放要炸的）
   - 消除 `remaining` 与位图的**双重真相**（`load` 不必重算 —— 这是最容易写错的一处）
   - `flush` 复杂度从 **O(待检查 × 条目数)** 降到 **O(总待办数)**
   - 代价：存档从 128 B/条 → 约 7.7 KB/条（961 区块）⇒ 把 `MAX_ENTRIES` 降到 32 即可压回 250 KB
   - **不重构的理由**：现有 13 项守卫测试覆盖的是位图语义，重写要连带改测试；
     收益在"代码更少"而非"行为更对" ⇒ 风险 > 收益。**触发条件：要加第四种爆炸模式时一起做**。
2. **三种模式 → 策略接口**（`shouldBreak(...)` + `dispose(...)`）。收益只在**扩展性**
   （新增"只烧可燃物"之类只加一个实现类）。当前三种模式稳定 ⇒ **暂不做**，与第 1 条同批。
3. **`pendingChecks` 与 `ContainerChunkCache.pendingRescans` 同构**（都是"事件里只登记坐标 →
   tick 阶段处理 → 每 tick 限量 → 未加载丢弃"）。**不抽公共抽象** —— 两者语义不同
   （`pendingRescans` 是**无状态重扫**、丢了靠下次事件补；`pendingChecks` 是**有状态检查**、
   丢了永远不炸），抽象要参数化这个差异，成本 > 收益。但**两者的红线与限流参数应保持一致**。

**一处归类问题（✅ 已修 2026-09-18）**：`ExplosionLedger` 是**有状态**的（SavedData），原本却放在
`components/`，而 AGENTS 与 file-map 都把该目录描述为「**无状态**工具组件」。
且 TNT 技术文档原本写的是 `domain/tnt/ExplosionComponent.java` —— 说明**最初的领域归属意图就是
`domain/tnt/`**。
⇒ 已把 `ExplosionComponent` / `ExplosionParams` / `ExplosionLedger`（含两个测试类）一起迁到
`domain/tnt/`，与 `LivingTntFunction` / `ExplosionData` / `LivingTntData` 同域。
`components/` 现在只剩 `ItemFilterComponent`，与「无状态工具组件」的定位一致。

**已排除的一个"看起来更优雅"的改法**：给 `onChunkLoaded` 加内存 `activeDims` 集合，
让它完全不碰 `level`（纯内存判断，连 `getDataStorage()` 都省掉）。
**否决理由**：`onChunkLoaded` 里的 `get(level)` 是**首次区块加载时惰性读盘**的唯一入口 ——
加了 `activeDims` 就必须额外在服务端启动时预热，否则重启后已加载区块永远补不上；
而它省下的只是 2 次 map 查找（约 50 ns）。**收益不抵复杂度**。

#### 已确认安全的路径（不用改）

`applyExplosionDamage` 的 `level.getEntities(AABB)` —— 1.21 走 `LevelEntityGetter`（已加载实体索引），
**不碰区块**；`scheduleSuperExplosion` 的区块列表是纯几何计算（不碰世界）。

#### ⚠️ 顺带发现的两个既有问题（与方案无关，但必须一起修）

1. **`PENDING_SUPER_EXPLOSIONS` 服务端关闭不清理** —— `LivingItem.onServerStopped` 清了
   `EnderChannelRegistry` / `ContainerChunkCache` / `ContainerLivingItemHandler`，**唯独漏了爆炸任务**，
   且 `ExplosionComponent` 连 `clearAll()` 入口都没有。单机「退出存档 → 进另一个存档」**不重启 JVM**
   ⇒ 任务持有旧 `ServerLevel` 引用 ⇒ **内存泄漏**（整个旧世界无法 GC）+ 在已关闭的 level 上继续
   `hasChunk` ⇒ 行为未定义。**任何"跨 tick 状态"方案都会加重它，必须先修。**
2. **`tickAll` 每 tick 只处理 1 个区块** ⇒ 961 区块要 961 tick（约 48 秒）；且跳过未加载区块时
   `task.index++` **无条件推进、不重试** ⇒ **现在就是残缺的**（只是静默）。三个模式口径还各不相同。

> 📌 **独立的性能隐患**（同一循环，另行处理）：`radius = 235` 时收集循环是
> `(2×235+1)³ ≈ 1.05 亿次` 迭代 —— **即使全部已加载**，也是主线程上的一次巨长操作。
> 超级爆炸的"几何算区块列表 + 分帧"范式正是解法（用户 2026-09-18 认可可前移）。
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

[ContainerCompatibilityConfig](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/ContainerCompatibilityConfig.java) 为不同容器类型定义槽位解析规则，主要解决**非标准列数容器**的槽位方向解析问题。

**内置规则**（随 jar 打包，源文件 `src/main/resources/assets/living_item/container_rules.json`）：

| 容器 | 槽位数 | 列数 | 边界行为 | 备注 |
|------|--------|------|---------|------|
| `minecraft:chest` | 27 | 9 | INVALIDATE | 标准箱子 |
| `minecraft:double_chest` | 54 | 9 | INVALIDATE | 大箱子 |
| `minecraft:hopper` | 5 | 5 | INVALIDATE | 原版漏斗 |
| `minecraft:dispenser` / `dropper` | 9 | 3 | INVALIDATE | 发射器 / 投掷器 |
| `ironchest:iron_chest` | 54 | 9 | INVALIDATE | 铁箱子 |
| `ironchest:copper_chest` | 45 | 9 | INVALIDATE | 铜箱子 |
| `ironchest:gold_chest` | 81 | 9 | INVALIDATE | 金箱子 |
| `ironchest:diamond_chest` | 108 | 12 | INVALIDATE | 钻石箱子 |
| `ironchest:crystal_chest` | 108 | 12 | INVALIDATE | 水晶箱子 |
| `ironchest:obsidian_chest` | 108 | 12 | INVALIDATE | 黑曜石箱子 |
| `ironchest:trapped_*_chest` | 同上 | 同上 | INVALIDATE | 陷阱箱系列，槽位与普通版一致 |

> ⚠️ **2026-09-20 数据勘误**：旧表把铁箱子族写作 `ironchests:`（多一个 s）且槽位数全部编造
> （45/36/54/63/72），与 IronChests 16.0.7 实际注册名及尺寸均不符，那 7 条规则**从未生效**。
> 现真值取自该版本字节码 `IronChestsTypes.<clinit>` 的 `size, rowLength` 字段——
> IRON 54/9、COPPER 45/9、GOLD 81/9、DIAMOND 108/12、CRYSTAL 108/12、OBSIDIAN 108/12。
> 命名空间常量为 `ironchest`（`IronChestsItems.MODID`），该模组**不存在 silver 箱子**。

**自动生成规则**：

对于未注册的容器类型，`findOrGenerateRule()` 会根据槽位数自动推断标准矩形布局：

```java
public static ContainerRule findOrGenerateRule(int containerSize) {
    return findRuleBySize(containerSize)
        .orElseGet(() -> generateStandardRule(containerSize));
}
```

> ⚠️ **启发式的结构性缺陷**：`resolveColumns(int)` 按 `commonWidths = {9,10,12,13,8,...}` 顺序取
> **第一个能整除**槽位数的宽度。108 槽时 `108 % 9 == 0` 先命中 ⇒ 一律猜 **9 列**，
> 而铁箱子族的钻石/水晶/黑曜石箱真值是 **12 列** ⇒ 方向映射（UP/DOWN = `±columns`）整片错位。
> 这类容器**必须靠内置规则或玩家注册覆盖**，不能依赖推断。

#### 5.2.1 配置分层与开发期导出通道

规则有两个来源，物理位置决定了「能不能随包发布」：

| 位置 | 读 | 写 | 随 jar 打包 | 语义 |
|------|----|----|------------|------|
| jar 内 `assets/living_item/container_rules.json` | ✅ | ❌ classpath 只读 | ✅ | 内置规则（项目级） |
| `config/living_item/container_rules.json` | ✅ | ✅ | ❌ | **玩家差异**（新增 / 覆盖内置 / 删除内置） |

**加载顺序**（后者可覆盖前者）：

1. 内置资源 → 登记 `BUNDLED_IDS`
2. config 的 `removed` 列表 → 从注册表**删除**这些内置规则（`REMOVED_IDS`）
3. config 的 `rules` 列表 → **覆盖或新增**（`USER_RULES`）

**增量语义（2026-09-20 起）**：`save()` 只写玩家差异，**不再把内置规则回写成副本**。
此前全量回写会让 config 变成一份冻结的旧快照——里面那些拷贝永远因「内置优先」而不生效，
纯噪声，且掩盖了「哪些是玩家真正动过的」。

**三分状态**（`inspect` / `list` 会显示）：

| 状态 | 判定 | `list` 标记 |
|------|------|------------|
| 纯内置 | `isBundledRule` 且非 `isUserModified` | 无 |
| 玩家新增 | 非 `isBundledRule` | `+` 绿 |
| 玩家覆盖内置 | `isBundledRule` 且 `isUserModified` | `*` 橙 |

⚠️ **`removed` 字段不可省**：`load()` 每次都会先读内置资源，若删除不落盘，
玩家删掉的内置规则会在下次启动被「复活」。这不是假设——引入 `save()` 增量改造后
若不补这个字段，`remove` 内置规则会静默失效。（守卫测试：`ContainerRuleConfigTest`）

#### 5.2.2 社区贡献流程（文件级覆盖，2026-09-20）

**导出的是全量生效快照，不是差异**。`exportBundledFormat()` 直接对
`ContainerCompatibilityConfig.getAllRules()` 取快照（内置 + 玩家新增 − 玩家删除），
按 ID 排序后写出。因此产物**已包含作者原有的全部内置条目**，
可以被**直接复制覆盖**到 `src/main/resources/assets/living_item/container_rules.json`，
不必逐条摘录、也不必手工合并——这正是「文件级复制粘贴」而非「条目级复制粘贴」。

| 文件 | 内容 | 格式 |
|------|------|------|
| `config/living_item/container_rules.json` | 玩家差异（`rules` + `removed`） | v2 |
| `config/living_item/exported_rules.json` | **全量生效规则快照**（不含 `removed`） | v1，与内置资源**逐字段一致** |

```
游戏内 /livingitem container register <columns>     ← 对着真容器注册，ID 由 getKey() 自动检测
        ↓  save()
config/living_item/container_rules.json             ← 只含玩家差异（不打包）
        ↓  /livingitem container export
config/living_item/exported_rules.json              ← 全量生效快照，与 assets 完全同格式
        ↓  文件级复制粘贴（作者操作，无工具依赖）
src/main/resources/assets/living_item/container_rules.json  ← 重新打包后随模组发布
```

⚠️ **jar 内文件运行时不可写**是 Java/classpath 的硬限制，「玩家注册自动进包」物理上不存在；
导出通道是唯一可行路径，且**打包后的正式环境不参与写入**（`exportBundledFormat()` 只读内存状态）。

> **`export` 不导出 `removed`**：删除是玩家本地偏好，不构成对内置资源的修改。
> 若某条内置规则本身是错的，正确做法是**覆盖**成真值再导出，而不是删掉它。
> 玩家删掉的内置条目只是不出现在这份快照里（覆盖内置资源后它自然消失）。

**冲突处理：先到先得 + WARN**。作者合并多份玩家贡献后，同一 ID 可能出现两条不同数值的条目。
`loadFromResource()` 逐条比对，命中重复且**数值不同**时保留先出现的一条并打 WARN
（`Duplicate container id ... keeping first ... ignoring later ... resolve it manually`），
加载结束后再补一条汇总 WARN。`differs()` **只比较 `containerSize` 与 `columns`**——
描述文字措辞不同不算冲突，否则会被大量假冲突淹没。

**编码：全链路显式 UTF-8**。`save()` / `export` / `load()` 三处文件 IO 均显式指定
`StandardCharsets.UTF_8`。此前用 `FileReader`/`FileWriter` 走平台默认编码，
在中文 Windows（GBK）上导出的含中文描述文件拿给 UTF-8 环境的作者会直接
`MalformedInputException` 乱码——跨平台交换文件必须锁定编码。

**可测性接缝**：核心解析抽为包级 `loadRules(Reader, source, label)`，
`loadFromResource()` 只是它的薄壳。好处有二：
① 作者覆盖加载与内置资源加载**共用同一套解析 + 冲突检测**，不会出现"两条路径行为不同"；
② 测试可直接喂入一份模拟快照，验证「作者整文件覆盖内置资源」后的加载语义，
**无需真的替换 classpath 资源**。守卫用例：`exportedSnapshot_isLoadableAsBundledResource`、
`mergeConflict_keepsFirstAndWarns`。

> 实战价值：正因为玩家指令走 `BlockEntityType` 的 `getKey()` 自动检测，
> 玩家随手一注册往往比手写的内置数据更准——2026-09-20 的 `ironchest:` 勘误正是这样发现的。
> 社区贡献流程把这件偶然变成了常规机制：**玩家共同完善容器兼容性**。

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

**相关修复**：`SimpleContainerContext.setItem()` 的**写入**自 2026-08-17 起统一走 `IItemHandler`（**移除** `Container.setItem` 优先写入，避免大箱子左右两半双记账导致物品复制）；`simulateInsertItem()` 的 Container 读取自 2026-09-22 起**先过槽位体系一致性探针**（大箱子下单半箱 27 槽与 handler 54 槽错位 27，读到另一半箱的槽位会让活漏斗静默不传输），不过则回退 IItemHandler。详见 §7.2 与 [living-hopper-tech.md](../tech/living-hopper-tech.md) §10.25。

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

[SlotResolver](file:///g:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/transfer/SlotResolver.java) 将相对方向偏移转换为容器中的绝对槽位索引。

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

`setItem` 的**写入**统一只走 `IItemHandler`，**不再**经原版 `Container` 接口做精确槽位写入。`simulateInsertItem` 仍会读 `Container`，但自 2026-09-22 起该读取**先过槽位体系一致性探针**（见下方 ⚠️），不过则回退 IItemHandler；写回始终走 IItemHandler。

> ⚠️ 2026-09-22 修正：早期"读 `Container` 只是算容量，只读所以安全"的判断是**错的**。
> `simulateInsertItem` 除了算容量，还会用 `container.getItem(slot)` 判断"目标槽现有物品能否合并"——
> 这是**按逻辑槽位**的读，而大箱子下 `ContainerContext.getContainer` 只给出**单个半箱（27 槽）**，
> 与 handler 的 54 槽**错位 27**（Container 的槽 22 = GUI 的槽 49）⇒ 目标槽明明是空的，却读到
> 另一半箱对应格里的漏斗 ⇒ 判定不可插入 ⇒ 活漏斗静默不传输（源槽非空所以不设冷却，看着像卡死）。
>
> 修复：`SimpleContainerContext.isSameSlotSpaceAsHandler(container, slot)` 两级探针 ——
> ① 槽位数一致（挡"单体 vs 合并"）② 单槽交叉校验（挡"槽位数相同但合并顺序相反"）；
> 不过则回退 `handler.insertItem(slot, …, true)` —— 它内部同样转调 `canPlaceItem`/`isItemValid`，
> 语义不丢，且模拟与真实由此**同源**。判据不假设容器结构，三方块/四块容器通用。
> 详见 [living-hopper-tech.md](../tech/living-hopper-tech.md) §10.25。

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
│   for each chunk in ContainerChunkCache.getProcessableChunks(level): │
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

### 9.8 Mixin 层约定

- **配置**：服务端 Mixin 登记在 `living_item.mixins.json` 的 `mixins` 数组；客户端在
  `living_item.client.mixins.json`；Create 兼容在 `living_item.mixins-create.json`（条件加载）。
  **新增 Mixin 必须登记**，否则静默不生效。
- **`required: true` + `defaultRequire: 1` 是有意的 fail-fast**：`@At` 锚点或 target 描述符失效时
  **启动即崩**，好过静默失效 ——「功能悄悄没了」比「起不来」难查得多。
- **`@At` target 描述符的参数类型写父类型**：如
  `Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V`
  而非 `Player`（字节码描述符按**声明**类型，不按实参类型）。
- **注入在别人方法内部 ⇒ 必须 try/catch**：异常冒泡会破坏原版流程
  （如「方块已放、物品未扣」+ 炸 tick）。非必要功能（「软逻辑」）一律整段兜住 + WARN 日志。
- **读物品栈要锚在「数据还在」的时刻**：`BlockItem.place` 必须在 `consume` **之前**注入
  （`@At("RETURN")` 时单块放置已 count=0，空栈 `getComponents()` 返回 `EMPTY` ⇒ 静默失效，
  堆叠放置却正常）—— 详见 [living-farmland-tech.md §11.16](../tech/living-farmland-tech.md)。
- **Mixin 的 handler 方法保持无状态**（别加字段存中间结果）：单例 + 可重入场景下会串。
  项目内既有 Mixin 均无状态，沿用。

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

### 10.3 性能判读：`PerfMetrics` 的覆盖盲区（血的教训）

- `PerfMetrics` **只插桩 `processContext` 内部**。外部 mod 在自己 `ServerTickEvent` 里
  **直接调我们能力接口**的路径（Flux Networks → `ContainerEnergyStorage.receiveEnergy`）
  **完全不在计时区间内**。
- 因此「PerfMetrics 说 living_item 只占 3%」与「spark 说某方法占 98.79%」**不矛盾**，
  是覆盖盲区 —— 曾据此误判「卡顿与本模组无关」。
  **交叉验证必须看 spark 节点的绝对毫秒数，不能只看百分比。**
- 用户的场景描述（「只有传输电力才卡」）比任何采样百分比都值钱 —— **先问场景，再读火焰图**。


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