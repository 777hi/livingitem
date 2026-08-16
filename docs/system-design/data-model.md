# DataComponent 数据模型与关键设计决策

> **文档版本**: 2026.08 v2
> **最后更新**: 2026-08-17
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [数据模型概览](#1-数据模型概览)
2. [数据流转模式](#2-数据流转模式)
3. [新旧架构对比](#3-新旧架构对比)
4. [关键设计决策](#4-关键设计决策)

---

## 1. 数据模型概览

每个功能类拥有独立的 DataComponent 类型，数据通过不可变 Record 聚合：

```
LivingItemManager (DataComponent 注册中心)
    ├── LIVING_TNT_DATA          → LivingTntData
    │     └── explosion: ExplosionData (ignited, fuseDuration)
    │
    ├── LIVING_WATER_BUCKET_DATA → LivingWaterBucketData
    │     └── water: WaterData (flow, hostSlot, width)
    │
    ├── LIVING_FURNACE_DATA      → LivingFurnaceData
    │     ├── progress: ProgressData (progress, total)
    │     ├── fuel: FuelData (burnTime, maxBurnTime)
    │     ├── transform: TransformData (input, output, inputCount, outputCount)
    │     └── direction: DirectionSlotsData (slots, activeSlotIndex)
    │
    ├── LIVING_HOPPER_DATA       → LivingHopperData
    │     ├── transfer: TransferData (cooldown, maxCooldown)
    │     ├── filter: FilterData (blacklist, whitelist, tags, slots)
    │     └── direction: DirectionTransferData (sourceOffset, targetOffset)
    │
    └── LIVING_ENDER_CHEST_DATA  → LivingEnderChestData
          └── channel: EnderChannelData (channel, boundPlayer, routes)
    └── LIVING_WATER_WHEEL_DATA  → LivingWaterWheelData
          └── wheel: WaterWheelData (cwStress, ccwStress, netStress)

活地图传送系统（无独立 DataComponent，活末影珍珠为纯工具类）
    ├── LivingEnderPearlFunction — 活末影珍珠工具类（查找/统计/消耗/冷却，无 tick 逻辑）
    ├── MapTeleportExecutor      — 传送执行器（优先级：旗帜 > 宝藏 > 坐标，未探索消耗16珍珠）
    ├── TeleportHelper           — 传送工具类（安全Y/跨维度/载具/Sable飞艇/粒子音效/珍珠消耗）
    └── MapCoordHelper           — 坐标转换工具类（UV↔像素↔世界坐标，旗帜/宝藏命中检测）
```

---

## 2. 数据流转模式

以活熔炉为例：

```
tick() 入口
    ↓
LivingFurnaceData data = LivingItemManager.getData(stack, LIVING_FURNACE_DATA.value(), LivingFurnaceData.DEFAULT)
    ↓
读取子数据：data.progress(), data.fuel(), data.direction()
    ↓
无状态工具类处理：
    data = tickProgress(data, stack.getCount())   // ProgressComponent.tick()
    data = tickFuel(data, fuelSlot, stack.getCount()) // FuelConsumeComponent.tick()
    data = tickTransform(data, inputSlot)          // ItemTransformComponent.tick()
    ↓
写入新数据：LivingItemManager.setData(stack, LIVING_FURNACE_DATA.value(), data, LivingFurnaceData.DEFAULT)
    ↓
同步到客户端：context.syncSlotToClients(slot, stack)
```

---

## 3. 新旧架构对比

| 维度 | 旧架构（编排器 + ComponentState） | 新架构（功能内聚 + DataComponent） |
|------|--------------------------------|--------------------------------|
| 数据存储 | `LivingFunctionData` → `Map<String, CompoundTag>` | 独立 `DataComponent<LivingXxxData>` |
| 数据模型 | `ComponentState`（可变 NBT 包装器） | Java Record（不可变，`withXxx()` 创建新实例） |
| 编排方式 | `LivingOrchestrator.orchestrate()` | 功能类自行实现 `tick()` |
| 组件调用 | `ILivingComponent.tick(context, state)` | 无状态工具类：`ProgressComponent.tick(data) → data` |
| 方向配置 | `DirectionModeComponent` + `ComponentState` + 硬编码物品类型判断 | `HasDirection` 接口 + `DirectionSlotsData` / `DirectionTransferData` Record，输入处理器和网络处理器自动识别 |
| 容器级数据 | 硬编码在 `ContainerLivingItemHandler` 和 `TickContext` 中 | `HasContainerData` 接口，功能类自行声明并按优先级排序执行 |
| Tooltip | `addToTooltip(CompoundTag, ...)` | `addToTooltip(Item.TooltipContext, ..., ItemStack)` |
| 同步粒度 | 整个 `LivingFunctionData` 一起同步 | 独立 DataComponent 增量同步 |
| 类型安全 | 运行时字符串键（`state.getInt("progress")`） | 编译时类型检查（`data.progress()`） |
| 容器上下文 | 上帝接口（所有职责混杂） | 组合接口（4 个正交接口） |
| tick 状态 | 存储在 ContainerContext 中 | 独立 TickContext（生命周期仅为单次 tick） |
| 数据访问 | 多个 getXxxData/setXxxData 方法 | 泛型 getData/setData（等于默认值自动移除） |
| 流体数据 | 静态 FLUID_DATA_CACHE | 容器实例字段（SimpleContainerContext.fluidData） |

---

## 4. 关键设计决策

### 4.1 功能内聚 + DataComponent 直接管理

每个功能类直接管理其类型化的 DataComponent，自行实现 tick 逻辑，不再依赖编排器和 FunctionExecutor。

**为什么从编排器模式迁移到功能内聚？**
- 编排器模式虽然减少了重复代码，但引入了间接层（`ComponentState` → `ILivingComponent.tick()` → `ComponentState`），数据流转不透明
- `ComponentState` 是无类型的 NBT 包装器，运行时字符串键（`state.getInt("progress")`）缺乏编译时类型安全
- 编排器将编排逻辑从功能类中分离，但功能类仍需理解编排器的行为才能正确配置，认知负担并未减少
- 新架构中，功能类直接操作类型化的 Record 数据，调用无状态工具类处理具体逻辑，编排流程由功能类自己控制

**新架构的核心原则：**
- **数据不可变**：所有数据模型使用 Java Record，通过 `withXxx()` 创建新实例
- **无状态工具类**：`ProgressComponent.tick(data) → data`，不持有状态，接收数据返回新数据
- **增量同步**：数据变化时才写入 DataComponent 并同步到客户端
- **类型安全**：`data.progress()` 替代 `state.getInt("progress")`
- **接口隔离**：`ContainerContext` 拆分为 4 个正交接口，功能类只依赖最小接口
- **状态分离**：tick 级临时状态独立为 `TickContext`，生命周期仅为单次 tick
- **泛型数据访问**：`getData/setData` 替代多个专属 getter/setter，等于默认值时自动移除

### 4.2 不可变数据模型

所有数据模型使用 Java Record，确保数据不可变性：
- `Pos2D`、`SlotMapping` — 方向和映射
- `ProgressData`、`FuelData`、`TransformData` — 熔炉子数据
- `TransferData`、`FilterData` — 漏斗子数据
- `ExplosionData`、`WaterData` — TNT/水桶子数据
- `LivingTntData`、`LivingFurnaceData` 等 — 功能聚合数据

数据变更通过 `withXxx()` 方法创建新实例，而非修改现有实例。

### 4.3 方向数据模型

| 模式 | 用途 | 数据结构 | 输入方式 |
|------|------|----------|----------|
| **DirectionSlotsData** | 活熔炉等需要多个命名槽位的场景 | `Map<String, Pos2D>` + `activeSlotIndex` | 代码配置，运行时可通过 WASD 切换活跃槽位 |
| **DirectionTransferData** | 活漏斗等需要动态传输方向的场景 | `sourceOffset: Pos2D` + `targetOffset: Pos2D` | WASD 键入，运行时可变 |

两种方向数据均为不可变 Record，通过 `withXxx()` 方法创建新实例。替代旧的 `DirectionModeComponent` + `ComponentState` 模式。

### 4.4 活物品隔离

所有组件在处理物品时检查 `LivingItemManager.isLivingItem()`：
- `ItemTransferComponent`：不传输活物品
- `FuelConsumeComponent`：不消耗活物品作为燃料
- `ItemTransformComponent`：不熔炼活物品

### 4.5 服务端权威 + 手动同步

物品数据在服务端是权威的。活物品 tick 修改 DataComponent 后，通过 `ContainerContext.syncSlotToClients()` 主动发送 `ClientboundContainerSetSlotPacket` 同步到客户端，因为原版 `broadcastChanges()` 无法检测自定义 DataComponent 的变化。

**增量同步策略**：新架构中，功能类只在数据实际变化时才写入 DataComponent 并调用同步，减少不必要的网络传输。

**接口拆分**：同步能力独立为 `ContainerSync` 接口，功能类只需依赖此接口即可同步，无需依赖完整的 `ContainerContext`。

### 4.6 光标物品操作

活漏斗方向配置时，物品被拿在光标上（`containerMenu.getCarried()`），不在任何槽位中。服务端通过 `getCarried()` 获取引用，修改后用 `ClientboundContainerSetSlotPacket(-1, stateId, -1, ...)` 同步回客户端。

### 4.7 跨容器传输方向映射

活漏斗在容器边界时触发跨容器传输，需要将容器GUI的二维方向（上下左右）转换为世界三维方向（东南西北）。

**方向映射算法**：
1. 以方块朝向北方为基准：UP→SOUTH(后方), DOWN→NORTH(前方), LEFT→EAST(右方), RIGHT→WEST(左方)
2. 根据方块实际朝向进行Y轴顺时针旋转（北0°、东90°、南180°、西270°）

**大箱子半箱选择**：
大箱子由LEFT和RIGHT两个半箱组成，不同边界的跨容器传输需要基于不同半箱的位置查找邻居：
- UP/DOWN方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI下半部分）
- LEFT/RIGHT方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI右半部分）

**防内部传输**：通过位置比较（而非实例比较）检测相邻容器是否为大箱子的另一半箱。`ChestBlock.getContainer()` 每次返回新的 CompoundContainer 实例，`==` 比较无效。

### 4.8 配方缓存优化

`ItemTransformComponent` 通过 `resolveRecipe()` 公共方法实现配方缓存，避免每 tick 重复查询 `RecipeManager`。

**缓存策略：**
- 在 `ComponentState` 中缓存输入物品 ID（`KEY_CACHED_INPUT`）和配方结果（`KEY_CACHED_OUTPUT`、`KEY_CACHED_OUTPUT_COUNT`）
- 输入物品不变时直接从缓存读取，跳过 `RecipeManager.getRecipeFor()` 查询
- `canProcess()` 和 `executeTransform()` 共享 `resolveRecipe()` 方法，消除代码重复

**性能影响：**大量活熔炉同时工作时，每个活熔炉每 tick 的配方查询从 2 次降为 0 次（缓存命中），零配方查询开销。

### 4.9 IItemHandler 统一容器抽象

项目全面使用 NeoForge 的 `IItemHandler` 能力替代原版 `Container` 接口进行容器读写和物品交互，**无需适配器层**。

> 📄 详见 [living-item-infrastructure.md](living-item-infrastructure.md)