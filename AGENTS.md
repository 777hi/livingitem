# Living Item Template (活物品模板)

**Minecraft 1.21.1 + NeoForge 21.1.x**

## 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过 DataComponent 持久化，跟随物品跨容器迁移。

### 核心特性

- **活按钮 UI**：在容器界面点击按钮，将手持物品转化为活物品
- **容器内自动执行**：含活物品的被加载容器会自动 tick
- **DataComponent 直接管理**：每个功能类直接管理其类型化的 DataComponent（如 `LivingTntData`、`LivingFurnaceData`），替代旧的 `ComponentState` + `LivingFunctionData` 中转层
- **功能内聚**：每个功能类自行实现 tick 逻辑和数据管理，不再依赖编排器或 FunctionExecutor 调度
- **不可变数据模型**：使用 Java Record 实现不可变数据结构（如 `ProgressData`、`FuelData`），通过 `withXxx()` 方法创建新实例
- **增量同步**：功能数据拆分为独立的 DataComponent，只在数据变化时更新并同步到客户端
- **方向数据模型**：`DirectionSlotsData`（多槽位映射）和 `DirectionTransferData`（传输方向）替代旧的 `DirectionModeComponent` + `ComponentState`
- **WASD 输入配置**：在容器界面拿起活漏斗悬停活按钮上，通过 WASD 键入改变传输方向
- **状态持久化**：所有运行数据保存在 DataComponent 中，跨容器迁移不丢失
- **活物品隔离**：活物品不会被其他活物品当作普通物品处理（不传输、不熔炼、不作为燃料）
- **GUI交互系统**：容器界面中活物品之间的鼠标交互（如活打火石右键活TNT），声明式规则 + 统一拦截 + 服务端处理
- **活TNT爆炸**：容器中的可爆炸活物品，引信倒计时后爆炸，威力随数量缩放，支持普通/大当量双模式
- **活物品图标系统**：组件化的客户端图标框架，声明式配置即可实现活物品图标根据状态动态切换，支持上下文感知（GUI/手持显示不同图标）和 ItemDecorator 叠加层
- **活箱子系统**：将箱子虚拟化到物品 DataComponent 中，堆叠数 × 27 槽 = 虚拟箱子容量。UUID 映射管理、LRU 缓存、磁盘持久化、漏斗自动传输、跨容器传输、GUI 拆分/合并 UUID 自动分配
- **活末影箱系统**：无线传输路由器，支持路由模式（共享黑板架构，通过全局路由表跨容器无线传输）和直连模式（绑定玩家末影箱直连）。频道隔离、轮询公平调度、反向索引路由清理、黑白名单统一过滤

---

## 架构设计

### 整体数据流

```
服务端 tick (LivingItem.onServerTick)
    ↓
ContainerChunkCache (容器位置缓存，拉取模型，直接遍历所有已知容器位置)
    ↓
ContainerLivingItemHandler (扫描容器、按功能分组)
    ↓
LivingItemFunction.tick(entries, context, tick, level) (各功能类自行实现 tick 逻辑)
    ↓
┌─────────────────────────────────────────────────────────────────┐
│  功能类直接管理 DataComponent，无中间层                          │
│                                                                 │
│  LivingTntFunction        → LivingTntData (ExplosionData)       │
│  LivingWaterBucketFunction→ LivingWaterBucketData (WaterData)   │
│  LivingFurnaceFunction    → LivingFurnaceData (ProgressData,    │
│                              FuelData, TransformData,           │
│                              DirectionSlotsData)                │
│  LivingHopperFunction     → LivingHopperData (TransferData,     │
│                              FilterData, DirectionTransferData) │
│  LivingEnderChestFunction → LivingEnderChestData (EnderChannel) │
│  LivingChestFunction      → InternalStorageComponent (旧架构)   │
│                                                                 │
│  无状态工具类（接收类型化数据 → 返回新数据）：                     │
│  ProgressComponent  → tick(ProgressData) → ProgressData         │
│  FuelConsumeComponent → tick(FuelData) → FuelData               │
│  ItemTransformComponent → transform(TransformData) → Transform  │
│  ExplosionComponent → tick(ExplosionData) → ExplosionData       │
│  ItemFilterComponent → allows(FilterData, ItemStack) → boolean  │
│  ItemTransferComponent → transfer(...) → boolean                │
│  EnderChannelComponent → cleanup(...) → void                    │
└─────────────────────────────────────────────────────────────────┘
    ↓
ContainerContext (组合接口：LivingContainer + SlotInfoProvider + ContainerSync + ContainerIdentity)
    ↓
TickContext (tick 级临时状态：槽位互斥、级联防护、容器快照、流体数据)
    ↓
SlotAccessor (模拟优先传输：simulateExtract → simulateInsert → extract → insert → rollback安全兜底 + FilteredSlotAccessor 过滤)
```

### DataComponent 数据模型

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
```

**数据流转模式（以活熔炉为例）：**
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

### 容器上下文架构（接口拆分）

`ContainerContext` 已从上帝接口重构为组合接口，拆分为 4 个正交接口：

```
ContainerContext (组合接口，继承以下 4 个接口)
    ├── LivingContainer          — 基础物品读写（getSize, getItem, setItem, getMaxStackSize）
    ├── SlotInfoProvider         — 槽位能力（getSlotLimit, isItemValid, simulateInsertItem, getWidth）
    ├── ContainerSync            — 客户端同步（syncSlotToClients）
    └── ContainerIdentity        — 身份标识（getContainerKey, getStableKey, getBlockPos, getLevel）
```

**为什么拆分？**
- 原 `ContainerContext` 承担了太多职责（物品读写、槽位能力、同步、身份标识、tick 状态），违反接口隔离原则
- 功能类通常只需要部分能力（如熔炉只需读写+同步，不需要槽位验证）
- 拆分后，功能类可以只依赖最小接口，降低耦合

**TickContext 模式**：
```
TickContext (tick 级临时状态，生命周期仅为单次 tick)
    ├── occupiedSlots: Set<String>          — 槽位互斥集合（防止同槽位重复处理）
    ├── transferredTargetSlots: Set<Integer> — 级联传输防护（防止漏斗循环传输）
    ├── snapshot: ContainerSnapshot         — 容器快照（预扫描活漏斗连接图）
    └── fluidData: ContainerFluidData       — 容器级流体数据（实例绑定，非静态缓存）
```

**泛型数据访问**：
```java
// 替代所有 getXxxData/setXxxData 方法
LivingItemManager.getData(stack, type, defaultValue)
LivingItemManager.setData(stack, type, data, defaultValue)  // 等于默认值时自动移除
```

### 编排器体系（旧架构，已废弃）

> ⚠️ 编排器模式已被功能内聚模式替代。新功能类（`LivingTntFunction`、`LivingWaterBucketFunction`、`LivingFurnaceFunction`、`LivingHopperFunction`、`LivingEnderChestFunction`）自行实现 tick 逻辑，不再使用编排器。旧编排器文件保留仅用于 `LivingChestFunction` 等尚未迁移的功能。

**旧模式**：编排器将"如何协调组件执行"从活物品功能类中分离出来，使新活物品只需选择合适的编排器，无需重写编排逻辑。

**新模式**：每个功能类直接管理其 DataComponent，自行实现 tick 逻辑。功能类内部调用无状态工具类（`ProgressComponent`、`FuelConsumeComponent` 等）处理具体逻辑，但编排流程由功能类自己控制。

```
LivingOrchestrator (接口) — 旧架构，仅 LivingChestFunction 使用
    ├── SimpleOrchestrator       — 直接遍历组件 tick
    ├── ProgressOrchestrator     — 检查输入 → tick/pauseTick → 完成时转化
    └── FuelProgressOrchestrator — 检查燃料+输入 → tick/pauseTick → 完成时转化
```

**新旧架构对比：**

| 维度 | 旧架构（编排器 + ComponentState） | 新架构（功能内聚 + DataComponent） |
|------|--------------------------------|--------------------------------|
| 数据存储 | `LivingFunctionData` → `Map<String, CompoundTag>` | 独立 `DataComponent<LivingXxxData>` |
| 数据模型 | `ComponentState`（可变 NBT 包装器） | Java Record（不可变，`withXxx()` 创建新实例） |
| 编排方式 | `LivingOrchestrator.orchestrate()` | 功能类自行实现 `tick()` |
| 组件调用 | `ILivingComponent.tick(context, state)` | 无状态工具类：`ProgressComponent.tick(data) → data` |
| 方向数据 | `DirectionModeComponent` + `ComponentState` | `DirectionSlotsData` / `DirectionTransferData` Record |
| Tooltip | `addToTooltip(CompoundTag, ...)` | `addToTooltip(Item.TooltipContext, ..., ItemStack)` |
| 同步粒度 | 整个 `LivingFunctionData` 一起同步 | 独立 DataComponent 增量同步 |
| 类型安全 | 运行时字符串键（`state.getInt("progress")`） | 编译时类型检查（`data.progress()`） |
| 容器上下文 | 上帝接口（所有职责混杂） | 组合接口（4 个正交接口） |
| tick 状态 | 存储在 ContainerContext 中 | 独立 TickContext（生命周期仅为单次 tick） |
| 数据访问 | 多个 getXxxData/setXxxData 方法 | 泛型 getData/setData（等于默认值自动移除） |
| 流体数据 | 静态 FLUID_DATA_CACHE | 容器实例字段（SimpleContainerContext.fluidData） |

### 客户端输入流（活漏斗方向配置）

```
玩家在容器界面按 WASD 键
    ↓
ScreenEvent.CharacterTyped.Pre (LivingItemInputHandler)
    ├─ 检查：是否在容器界面？
    ├─ 检查：光标是否悬停在活按钮上？
    ├─ 检查：手持物品是否为活漏斗？
    ├─ 检查：按键是否为有效键（W/A/S/D）？
    ├─ 收集 2 次按键 → InputSession（2 秒超时）
    ↓
processInput() → keyToDirection() 解析为 Pos2D
    ├─ 从 LivingHopperFunction.readDirectionData() 读取当前方向
    ├─ 更新 source/target Pos2D
    ├─ SlotMapping.fromDirections(source, target) 构建映射
    └─ 发送 HopperDirectionPacket(mappingData)
        ↓
ServerPacketHandler (服务端)
    ├─ getCarried() 获取光标活漏斗
    ├─ LivingHopperFunction.updateTransferMapping() 更新方向
    └─ ClientboundContainerSetSlotPacket 同步到客户端
```

### GUI交互数据流（活物品间交互）

```
玩家在容器界面鼠标点击（右键活TNT等）
    ↓
Screen Mixin (mouseClicked / mouseReleased HEAD注入)
    ├─ AbstractContainerScreenMixin  — 通用容器（箱子、潜影盒）
    ├─ InventoryScreenMixin          — 生存模式背包
    └─ CreativeModeInventoryScreenMixin — 创造模式背包
    ↓
GuiInteractionHelper.tryInteract(hoveredSlot, button, menu)
    ├─ 获取光标物品(trigger)和槽位物品(target)
    ├─ InteractionRegistry.findInteraction(trigger, target, button)
    │     遍历所有 InteractionEntry，匹配 triggerItem + targetItem + button
    │     同时验证双方都是活物品
    ├─ resolveContainerSlot() — 解析真实容器索引（兼容 SlotWrapper）
    ├─ resolveCarriedTag() — 创造模式背包下序列化光标物品NBT
    └─ 发送 GuiInteractionPacket(slotIndex, containerSlot, actionId, carriedTag)
        ↓
GuiInteractionPacket.handle() (服务端)
    ├─ 创造模式 + carriedTag非空 → menu.setCarried() 恢复光标物品
    ├─ resolveSlot() 定位目标槽位（slotIndex优先，containerSlot回退）
    ├─ InteractionRegistry.getHandler(actionId) 查找处理器
    ├─ handler.handle(player, targetSlot) 执行交互逻辑
    └─ 创造模式光标被修改 → CarriedUpdatePacket 同步回客户端
        ↓
CarriedUpdatePacket.handle() (客户端)
    └─ 直接更新 menu.setCarried()，绕过原版对 CreativeModeInventoryScreen 的排除
```

**交互规则声明示例（活TNT）：**
```java
// LivingTntFunction.CONFIG 中声明两条交互规则：
new InteractionEntry(Items.TNT, Items.FLINT_AND_STEEL, 1, "ignite")
//  目标=活TNT, 触发器=活打火石, 右键 → actionId="ignite" → IgniteHandler

new InteractionEntry(Items.FLINT_AND_STEEL, Items.TNT, 1, "ignite_carried")
//  目标=活打火石, 触发器=活TNT, 右键 → actionId="ignite_carried" → IgniteCarriedHandler
```

**新增交互只需两步：**
1. 在 `LivingFunctionConfig` 中 `addInteraction(new InteractionEntry(...))`
2. 注册处理器 `InteractionRegistry.registerHandler("actionId", new XxxHandler())`

### 组件体系

```
数据层（DataComponent Record，不可变）
    ├── LivingTntData          — 活TNT 聚合数据
    │     └── explosion: ExplosionData (ignited, fuseDuration)
    ├── LivingWaterBucketData  — 活水桶聚合数据
    │     └── water: WaterData (flow, hostSlot, width)
    ├── LivingFurnaceData      — 活熔炉聚合数据
    │     ├── progress: ProgressData (progress, total)
    │     ├── fuel: FuelData (burnTime, maxBurnTime)
    │     ├── transform: TransformData (input, output, inputCount, outputCount)
    │     └── direction: DirectionSlotsData (slots, activeSlotIndex)
    ├── LivingHopperData       — 活漏斗聚合数据
    │     ├── transfer: TransferData (cooldown, maxCooldown)
    │     ├── filter: FilterData (blacklist, whitelist, tags, slots)
    │     └── direction: DirectionTransferData (sourceOffset, targetOffset)
    └── LivingEnderChestData   — 活末影箱聚合数据
          └── channel: EnderChannelData (channel, boundPlayer, routes)

无状态工具类（接收类型化数据 → 返回新数据，不持有状态）
    ├── ProgressComponent       — 进度计时与暂停（tick/pauseTick/isComplete/reset）
    ├── FuelConsumeComponent    — 燃料消耗与可用性检查
    ├── ItemTransformComponent  — 配方匹配与物品转化（含配方缓存）
    ├── ExplosionComponent      — 引信倒计时 + 爆炸逻辑（活TNT）
    ├── ItemFilterComponent     — 黑白名单过滤（链式传递 + FilterData 支持）
    ├── ItemTransferComponent   — 物品传输逻辑（含跨容器传输触发 + SlotAccessor 调度）
    ├── EnderChannelComponent   — 活末影箱频道组件（路由清理 + Tooltip）
    ├── CrossContainerTransfer  — 跨容器传输工具类（方向映射 + 大箱子处理 + 邻居容器查找）
    └── InternalStorageComponent — 活箱子内部存储（UUID 管理、LRU 缓存、磁盘 I/O）— 旧架构

旧架构组件（ILivingComponent 接口，仅 LivingChestFunction 使用）
    ├── DirectionModeComponent  — 方向/槽位配置（SLOTS/TRANSFER 双模式 + ComponentState）
    └── （其他旧组件已迁移为无状态工具类）

容器上下文（接口拆分）
    ├── ContainerContext        — 组合接口（继承以下 4 个接口）
    ├── LivingContainer         — 基础物品读写（getSize, getItem, setItem, getMaxStackSize）
    ├── SlotInfoProvider        — 槽位能力（getSlotLimit, isItemValid, simulateInsertItem, getWidth）
    ├── ContainerSync           — 客户端同步（syncSlotToClients）
    └── ContainerIdentity       — 身份标识（getContainerKey, getStableKey, getBlockPos, getLevel）

TickContext（tick 级临时状态，对象池复用）
    ├── occupiedSlots           — 槽位互斥集合
    ├── transferredTargetSlots  — 级联传输防护
    ├── snapshot                — 容器快照
    └── fluidData               — 容器级流体数据

TickContextPool（对象池，ThreadLocal 线程安全）
    ├── MAX_POOL_SIZE = 4       — 每个线程最多缓存 4 个实例
    ├── acquire(ctx)            — 池中有则复用，否则创建新实例
    ├── release(tick)           — 归还到池中（池满则丢弃）
    └── reset(ctx)              — 每次复用前重新扫描容器真实状态

SlotAccessor 存储后端抽象（独立于组件体系，供传输引擎使用）
    ├── SlotAccessor          — 接口：simulateExtract/simulateInsert/extract/insert/rollback/isEmpty/isFull/markTransferred/sync + transfer() 模拟优先传输
    │     ├── simulateExtract(amount) — 模拟提取：检查源能提供多少物品，不修改状态，返回物品副本
    │     ├── simulateInsert(stack) — 模拟插入：检查目标能接受多少物品，不修改状态，返回可接受数量
    │     └── transfer(source, target, amount) — 模拟优先模式：simulate→confirm→execute→rollback安全兜底
    │           流程：simulateExtract → simulateInsert → extract → insert → rollback(仅安全兜底+WARN日志)
    ├── PlainSlotAccessor     — 普通槽位：直接读写 ContainerContext（支持 getSlotLimit 感知模组槽位上限）
    ├── LivingChestAccessor   — 活箱子：通过 LivingChestFunction API 操作虚拟存储（insert/extract/isEmpty/isFull）
    ├── LivingEnderChestAccessor — 活末影箱双模式访问器
    │     ├── 路由模式（无绑定玩家）：insert=注册路由（不存物品），extract=查路由表→跳转源容器提取
    │     ├── 直连模式（有绑定玩家）：直接读写 PlayerEnderChestContainer（构造时预加载引用，离线跳过）
    │     └── registerRoute() — push端注册路由条目到 EnderChannelRegistry
    ├── NeighborSlotAccessor  — 邻居容器：包装 IItemHandler 槽位，跨容器传输统一接入 SlotAccessor 架构
    │     ├── simulateExtract → handler.extractItem(slot, amount, true)
    │     ├── simulateInsert → ItemHandlerHelper.insertItemStacked(handler, stack.copy(), true)
    │     └── rollback → 优先放回原槽位（current.isEmpty || sameItemSameComponents），否则 ItemHandlerHelper.insertItem
    ├── FilteredSlotAccessor  — 过滤装饰器（Decorator 模式）：为任意 Accessor 添加黑白名单过滤
    │     ├── extract()：提取后检查过滤，不通过则 rollback 退回
    │     └── insert()：插入前检查过滤，不通过则拒绝（返回0）
    ├── EnderChannelRegistry  — 全局路由表（服务端单例）：频道→路由条目映射
    │     ├── 轮询公平调度：nextIndex 指针轮流取，每个 push 端机会均等
    │     ├── 反向索引：posIndex（方块位置→条目）+ keyIndex（容器key→条目），O(相关路由) 清理
    │     └── 路由清理：removeStaleEnderChestRoutes / cleanStaleSourceRoutes / removeStaleRoutes / onChunkUnload
    ├── EnderChannelEntry     — 路由条目 record：itemType + sourceDim + sourcePos + sourceSlot + registrarSlot + containerKey + targetSlot
    └── SlotAccessorFactory   — 注册式工厂：根据槽位物品类型创建对应访问器 + 自动包装 FilteredSlotAccessor
          ├── Provider 接口 — 返回 null 表示不匹配，交给下一个 Provider
          ├── registerProvider(provider) — 注册新 Provider（第三方模组可扩展）
          ├── create() — 遍历 Provider 列表，活箱子→LivingChestAccessor，活末影箱→LivingEnderChestAccessor，其他活物品→null，普通→PlainSlotAccessor
          └── createForNeighbor() — 邻居容器→NeighborSlotAccessor + FilteredSlotAccessor

交互体系（独立于组件，处理GUI中的活物品间交互）
    ├── InteractionEntry   — 交互规则（record：targetItem + triggerItem + button + actionId）
    ├── InteractionRegistry — 交互注册表（规则查询 + 处理器注册）
    ├── InteractionHandler  — 处理器接口（服务端执行交互逻辑）
    ├── IgniteHandler       — 点燃槽位TNT（活打火石→活TNT）
    └── IgniteCarriedHandler — 点燃光标TNT（活TNT→活打火石）
```

### 模型层

```
core/model/
    ├── Pos2D       — 不可变 2D 坐标（record），含方向常量（UP/DOWN/LEFT/RIGHT 等）
    └── SlotMapping — 不可变槽位映射（record），含 12 种预设方向 + NBT 序列化
```

### 客户端图标系统

活物品图标采用三层架构，通过声明式配置（`LivingIconSpec`）驱动，新增活物品图标无需编写任何 Java 类。

```
┌─────────────────────────────────────────────────────┐
│  Layer 3: IItemDecorator（可选）                     │  ← 箭头叠加层（仅物品栏）
│  例: LivingHopperDecorator                          │
│      hopper_arrow_in.png / hopper_arrow_out.png     │
├─────────────────────────────────────────────────────┤
│  Layer 2: GenericContextAwareModel                   │  ← 上下文切换（GUI vs 手持）
│  ┌──────────────────┬──────────────────────┐        │
│  │ GUI: 活物品图标    │ 手持/地面: 原版图标   │        │
│  └──────────────────┴──────────────────────┘        │
├─────────────────────────────────────────────────────┤
│  Layer 1: GenericLivingModelWrapper                  │  ← 模型注入（区分活/原版）
│  └→ GenericLivingItemOverrides.resolve()             │
│     ├─ 不是活物品 → 返回原版模型                      │
│     └─ 是活物品 → 遍历 Variant.predicate 匹配变体     │
└─────────────────────────────────────────────────────┘
```

**注册示例（在 `LivingIconRegistry.registerAll()` 中）：**

```java
// 活熔炉：两种状态
register(LivingIconSpec.builder(Items.FURNACE)
    .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
    .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
    .build());

// 活TNT：闪烁动画（引信倒计时 % 10 == 0 时切换图标）
register(LivingIconSpec.builder(Items.TNT)
    .addVariant("lit", "item/tnt_lit", stack -> getFuseTimer(stack) > 0 && getFuseTimer(stack) % 10 == 0)
    .addVariant("idle", "item/tnt_idle", stack -> true)  // 兜底
    .build());

// 活漏斗：基础图标 + 箭头叠加层
register(LivingIconSpec.builder(Items.HOPPER)
    .addVariant("base", "item/hopper_living", stack -> true)
    .decorator(new LivingHopperDecorator())
    .build());
```

**新增活物品图标只需两步：**
1. 在 `LivingIconRegistry.registerAll()` 中添加一个 `LivingIconSpec` 声明
2. 准备对应的纹理 PNG 和模型 JSON 文件

**当前支持的活物品图标：**

| 活物品 | 变体 | 纹理 | 特效 |
|--------|------|------|------|
| 活漏斗 | `base` | `hopper_base.png` | 箭头叠加层（方向旋转） |
| 活熔炉 | `idle` / `active` | `furnace_idle.png` / `furnace_active.png` | 燃烧状态切换 |
| 活TNT | `idle` / `lit` | `tnt_idle.png` / `tnt_lit.png` | 引信闪烁动画（每10 tick切换） |
| 活箱子 | `base` | `chest_living.png` | 无 |

---

### 活箱子系统架构

活箱子将原版箱子的格子存储虚拟化到物品 DataComponent 中，每个物品堆叠计数对应一个虚拟箱子（27 槽），通过 UUID 映射到磁盘持久化文件。

#### 数据流

```
ItemStack (DataComponent)
    └── LIVING_FUNCTION_DATA (旧架构) / CONTAINER (ItemContainerContents)
        └── internal_storage
            ├── _us : int              ← 已用槽位计数（O(1) 空/满判断）
            └── uuids: ListTag<String>  ← 每个堆叠对应一个 UUID
                    ↓
            WorldStorage (LRU 缓存, 最大 200 条)
                    ↓  磁盘路径: data/living_chests/xx/uuid.dat
            ItemStack[27]  ← 每个 UUID 对应一个虚拟箱子内容
```

#### UUID 生命周期

```
创建
├── 活箱子首次 tick → 初始化所有 UUID
├── 堆叠数增加（合并）→ 追加新 UUID
├── insertItem() 发现 UUID 不足 → 自动补充
└── createAndRegisterNewUuid() → UUID.randomUUID() + 注册到缓存

读取
├── getUuids() → 从 ComponentState 解析 UUID 列表
├── getStorageState() → 从 ItemStack NBT 读取完整状态
└── getOrCreate(uuid) → 从缓存/磁盘加载虚拟箱子数据

⚠️ UUID 只增不减：堆叠数减少、insertItem 溢出等均不删除 UUID
删除（唯一路径：用户取消活化）
├── popUuid() → 弹出最后一个 UUID（调用方需先处理物品）
├── 取消活化（dropAllItems）→ 清理所有 UUID 和磁盘文件
└── cleanupOrphanedFiles() → 被动清理无引用的孤儿空文件

持久化
├── saveUuids() → UUID 列表 → ComponentState → ItemStack NBT
├── saveToDisk(uuid, items) → 虚拟箱子数据写入磁盘
├── markDirty(uuid) → 标记脏数据，等待定时保存
└── cleanupIdle() → 5 分钟未访问 → 从缓存淘汰（脏数据先保存）
```

#### 关键设计决策

**堆叠倍增模型**：1 个堆叠 = 1 个虚拟箱子（27 槽）。64 个活箱子 = 64 × 27 = 1728 槽。物品插入优先填充已有箱子（先填满现有的），提取优先从已有箱子取；只有满了才新增/空了才删除。

**LRU 缓存策略**：最大 200 条缓存条目，5 分钟空闲超时。淘汰前检查脏标记，脏数据先写盘。避免反复加载/卸载同一 UUID 数据。

**快速短路判断**：`_us`（used slots）字段记录已用槽位总数，O(1) 判断空/满。漏斗传输前先检查源是否空/目标是否满，避免无效的完整插入/提取调用链。

**漏斗自动传输**：`ItemTransferComponent` 在活箱子 tick 时，自动通过漏斗向相邻容器推拉物品。传输方向由 `DirectionModeComponent` 的 TRANSFER 模式控制。

**跨容器传输**：活箱子在容器边界时，通过 `CrossContainerTransfer` 向相邻容器传输物品。方向映射基于容器方块朝向旋转，插入使用 `tryInsert()`（IItemHandler 分支通过 `ItemHandlerHelper.insertItemStacked()` 一行完成）。

**GUI 拆分/合并 UUID 分配**：`ItemStackMixin` 拦截 `split()`/`grow()`/`shrink()`/`copyWithCount()`，通过 `LivingChestStackHandler` 自动分配/合并 UUID。`LivingChestStackFlags` 线程局部标志允许跨 UUID 堆叠。

**被动孤儿清理**：`cleanupOrphanedFiles()` 在 `onLevelSave` 时每 10 次执行一次，扫描 `data/living_chests/` 下所有文件，删除 NBT 全空且无活跃 UUID 引用的孤儿文件，防止取消活化后磁盘文件泄漏。

#### 核心文件

| 文件 | 职责 |
|------|------|
| `LivingChestFunction` | 活箱子功能：insertItem/extractItem/getStorageState 等公开 API |
| `InternalStorageComponent` | 底层实现：UUID 管理、LRU 缓存、磁盘 I/O、WorldStorage |
| `LivingChestStackHandler` | UUID 列表工具：标准化、创建、拆分、合并、数据校验 |
| `LivingChestStackFlags` | 线程局部标志：允许跨 UUID 堆叠（GUI 操作期间） |
| `ChestTransaction` | 事务包装器：确保多次操作间原子保存状态 |

---

### 活末影箱系统架构

活末影箱**不存储任何物品**，本质是一个**无线传输路由器**。通过与活漏斗配合，将不同容器中的物品传输链路连接起来，实现跨容器甚至跨维度的无线物品传输。支持路由模式（无绑定玩家）和直连模式（有绑定玩家）。

#### 双模式工作机制

```
┌──────────────────────────────────────────────────────────────────┐
│                    活末影箱双模式                                  │
│                                                                  │
│  路由模式（无绑定玩家）           直连模式（有绑定玩家）            │
│  ┌────────────────────────┐     ┌──────────────────────────┐    │
│  │ Push: registerRoute()  │     │ Push: 直接写入玩家末影箱   │    │
│  │   → 写入全局路由表      │     │   → PlayerEnderChestContainer │
│  │ Pull: 查路由表→跳转提取 │     │ Pull: 直接读取玩家末影箱   │    │
│  │   → EnderChannelRegistry│    │   → 离线时跳过            │    │
│  │ 频道号 = 堆叠数         │     │ 频道号无意义              │    │
│  └────────────────────────┘     └──────────────────────────┘    │
│                                                                  │
│  绑定触发：末影箱GUI中活化 → 绑定当前玩家UUID+名称               │
│  取消活化：清空绑定数据                                          │
└──────────────────────────────────────────────────────────────────┘
```

#### 路由模式数据流（共享黑板架构）

```
┌──────────────────────────────────────────────────────────────────┐
│                       EnderChannelRegistry                       │
│                       (全局单例·共享黑板)                        │
│                                                                  │
│  频道1: [钻石@容器A槽0, 铁锭@容器B槽2, ...]                       │
│  频道2: [石头@容器C槽5, ...]                                     │
│                                                                  │
│         ▲ 写入路由                          ▼ 读取路由            │
│  ┌──────┴──────────┐              ┌─────────┴──────────┐        │
│  │ push端活漏斗     │              │ pull端活漏斗        │        │
│  │ [活漏斗]→[活末影箱]│              │ [活末影箱]→[输出槽]  │        │
│  └─────────────────┘              └────────────────────┘        │
│                                                                  │
│  反向索引：posIndex(方块位置→条目) + keyIndex(容器key→条目)       │
│  轮询调度：nextIndex 指针轮流取，每个 push 端机会均等             │
└──────────────────────────────────────────────────────────────────┘
```

#### 路由生命周期

```
注册（push端）
├── ItemTransferComponent 检测 target 为活末影箱 → registerRoute()
├── CrossContainerTransfer 跨容器拉取到活末影箱 → registry.insert()
└── contains() 快速路径：已存在相同条目则跳过

提取（pull端）
├── ItemTransferComponent 检测 source 为活末影箱 → accessor.extract()
├── registry.peek() 轮询获取路由条目（支持黑白名单预过滤）
├── 跳转到源容器 → handler.extractItem() 实际提取
└── 提取失败 → registry.remove() 清理无效条目

清理
├── 活漏斗移走 → removeStaleRoutes(pos, activeSlots)
├── 活末影箱移走 → removeStaleEnderChestRoutes(activeSlots)
├── 源物品移走/替换 → cleanStaleSourceRoutes(context) [反向索引优化]
├── 频道改变 → removeByPositionAndSlotFromAllChannels(pos, slot)
└── 区块卸载 → onChunkUnload(level, chunkPos)
```

#### SlotAccessor 统一传输架构

所有物品传输统一通过 `SlotAccessor` 接口完成，黑白名单过滤由 `FilteredSlotAccessor` 装饰器统一处理：

```
SlotAccessorFactory.create() / createForNeighbor()
    ↓ 自动包装 FilteredSlotAccessor
    ↓
┌──────────────────────────────────────────────────────────────┐
│  FilteredSlotAccessor (装饰器)                                │
│  ├── extract() → 委托 → 检查过滤 → 不通过则 rollback 退回     │
│  └── insert()  → 检查过滤 → 不通过则拒绝 → 委托              │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 实际 SlotAccessor 实现                                  │  │
│  │ ├── PlainSlotAccessor     — 普通槽位                    │  │
│  │ ├── LivingChestAccessor   — 活箱子虚拟存储              │  │
│  │ ├── LivingEnderChestAccessor — 活末影箱（路由/直连）     │  │
│  │ └── NeighborSlotAccessor  — 邻居容器跨容器传输          │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

统一传输流程：SlotAccessor.transfer(source, target, amount)
    1. source.extract() → 提取物品
    2. target.insert()  → 插入物品
    3. 失败时 source.rollback() → 回滚，确保物品不丢失
    4. target.markTransferred() → 级联防护
    5. source.sync() + target.sync() → 客户端同步
```

#### 关键设计决策

**共享黑板模式**：push端和pull端互不感知，只通过 `EnderChannelRegistry` 通信。push端写"我这里有XX物品在YY位置"，pull端读"有东西吗？有就跳转过去取"。

**频道隔离**：堆叠数 = 频道号，不同堆叠数的活末影箱互不干扰。

**直连模式预加载**：`LivingEnderChestAccessor` 构造时一次性获取 `PlayerEnderChestContainer` 引用并缓存，避免每次操作都查找玩家。玩家离线时 `cachedEnderChest` 为 null，操作直接跳过。

**反向索引优化**：`EnderChannelRegistry` 维护 `posIndex`（方块位置→条目）和 `keyIndex`（容器key→条目）两个反向索引，路由清理时直接查询相关路由，时间复杂度从 O(所有路由) 优化到 O(相关路由)。

**黑白名单统一过滤**：`FilteredSlotAccessor` 装饰器在 `SlotAccessor` 层统一处理过滤逻辑，所有活漏斗主导的传输自动遵守黑白名单，无需在各处手动检查 `filterState`。

**跨容器传输统一**：`NeighborSlotAccessor` 将邻居容器的 `IItemHandler` 槽位包装为 `SlotAccessor`，使跨容器传输也能复用 `FilteredSlotAccessor` 过滤和 `SlotAccessor.transfer()` 统一传输逻辑。

**末影箱容器处理**：`ContainerLivingItemHandler.processEnderChest()` 将玩家末影箱空间加入被处理的容器类型，使用 `EnderChestContainerContext`（containerKey = `player_<uuid>_ender_chest`）确保末影箱中的活物品正常工作，但跨容器传输不生效（`getBlockPos()` 返回 null）。

**双重去重**：大箱子处理时，通过 `IdentityHashMap<IItemHandler, Boolean>` 按 IItemHandler 实例去重 + `HashSet<String>` 按 containerKey 去重，确保同一容器不被重复处理。

#### 核心文件

| 文件 | 职责 |
|------|------|
| `LivingEnderChestFunction` | 活末影箱功能入口：双模式切换、玩家绑定数据管理、Tooltip 显示 |
| `LivingEnderChestAccessor` | 活末影箱访问器：路由模式（registerRoute + 查路由表跳转提取）+ 直连模式（预加载玩家末影箱引用） |
| `EnderChannelRegistry` | 全局路由表：频道→路由条目映射 + 反向索引 + 轮询调度 + 多种清理策略 |
| `EnderChannelEntry` | 路由条目 record：物品类型 + 维度 + 位置 + 槽位 + 注册者槽位 + 容器key + 目标槽位 |
| `EnderChannelComponent` | 活末影箱频道组件：路由清理（移走末影箱/源物品）+ Tooltip 构建 |
| `FilteredSlotAccessor` | 过滤装饰器：为任意 SlotAccessor 添加黑白名单过滤（活漏斗侧统一处理，活末影箱无需拥有 ItemFilterComponent） |
| `NeighborSlotAccessor` | 邻居容器访问器：包装 IItemHandler 槽位，跨容器传输统一接入 |
| `SlotAccessorFactory` | 工厂：create() + createForNeighbor()，自动包装 FilteredSlotAccessor |

> 📄 详细技术文档见 [living-ender-chest-tech.md](docs/tech/living-ender-chest-tech.md)

---

## 核心文件索引

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── Config.java                              # NeoForge 配置
│
├── living/
│   ├── LivingItemManager.java               # 核心管理器：DataComponent 注册、数据读写、功能注册
│   ├── LivingItemFunction.java              # 功能接口定义（tick + addToTooltip + canApply + getFunctionId）
│   ├── BaseLivingFunction.java              # 旧架构功能基类（仅 LivingChestFunction 使用）
│   ├── LivingFunctionData.java              # 旧架构功能数据载体（仅 LivingChestFunction 使用）
│   │
│   ├── data/                                # ⭐ DataComponent 数据模型（不可变 Record）
│   │   ├── LivingTntData.java               # 活TNT 聚合数据（含 ExplosionData）
│   │   ├── LivingWaterBucketData.java       # 活水桶聚合数据（含 WaterData）
│   │   ├── LivingFurnaceData.java           # 活熔炉聚合数据（含 ProgressData + FuelData + TransformData + DirectionSlotsData）
│   │   ├── LivingHopperData.java            # 活漏斗聚合数据（含 TransferData + FilterData + DirectionTransferData）
│   │   ├── LivingEnderChestData.java        # 活末影箱聚合数据（含 EnderChannelData）
│   │   ├── ExplosionData.java               # 爆炸数据（ignited, fuseDuration）
│   │   ├── WaterData.java                   # 水流数据（flow, hostSlot, width）
│   │   ├── ProgressData.java                # 进度数据（progress, total）
│   │   ├── FuelData.java                    # 燃料数据（burnTime, maxBurnTime）
│   │   ├── TransformData.java               # 转化数据（input, output, inputCount, outputCount）
│   │   ├── TransferData.java                # 传输数据（cooldown, maxCooldown）
│   │   ├── FilterData.java                  # 过滤数据（blacklist, whitelist, tags, slots）
│   │   ├── DirectionSlotsData.java          # 方向-多槽位映射数据（slots, activeSlotIndex）
│   │   ├── DirectionTransferData.java       # 方向-传输映射数据（sourceOffset, targetOffset）
│   │   └── EnderChannelData.java            # 末影频道数据（channel, boundPlayer, routes）
│   │
│   ├── function/                            # 各活物品功能实现
│   │   ├── LivingChestFunction.java         # 活箱子：堆叠倍增模型、UUID 管理、物品存取 API（旧架构）
│   │   ├── LivingEnderChestFunction.java    # 活末影箱：双模式（路由/直连）、玩家绑定、频道管理、Tooltip
│   │   ├── LivingFurnaceFunction.java       # 活熔炉：DataComponent 直接管理 + 无状态工具类调用
│   │   ├── LivingHopperFunction.java        # 活漏斗：DataComponent 直接管理 + 无状态工具类调用
│   │   ├── LivingTntFunction.java           # 活TNT：DataComponent 直接管理 + 引信倒计时 + 爆炸
│   │   ├── LivingWaterBucketFunction.java   # 活水桶：DataComponent 直接管理 + 水流扩散
│   │   └── LivingFlintAndSteelFunction.java # 活打火石：交互触发器，无 tick 逻辑
│   │
│   ├── container/                           # 容器上下文与处理器
│   │   ├── ContainerContext.java            # 组合接口（继承 LivingContainer + SlotInfoProvider + ContainerSync + ContainerIdentity）
│   │   ├── LivingContainer.java             # 基础物品读写接口
│   │   ├── SlotInfoProvider.java            # 槽位能力接口
│   │   ├── ContainerSync.java               # 客户端同步接口
│   │   ├── ContainerIdentity.java           # 身份标识接口
│   │   ├── TickContext.java                 # Tick 级临时状态（槽位互斥、级联防护、快照、流体数据，对象池复用）
│   │   ├── SimpleContainerContext.java      # 容器上下文实现（直接基于 IItemHandler 读写）
│   │   ├── ContainerLivingItemHandler.java  # 容器扫描、分组调度、IItemHandler 去重、性能监控
│   │   ├── ContainerChunkCache.java         # 区块级容器缓存（事件驱动维护 + IItemHandler 检测）
│   │   ├── CrossContainerTransfer.java      # 跨容器传输工具类
│   │   ├── ContainerSnapshot.java           # 容器快照（预扫描活漏斗连接图，供 ItemFilterComponent 使用）
│   │   └── ContainerFluidData.java          # 容器级流体数据（实例绑定，非静态缓存）
│   │
│   ├── chest/                               # 活箱子辅助工具
│   │   ├── LivingChestStackHandler.java     # UUID 列表工具：标准化、创建、拆分、合并、数据校验
│   │   ├── LivingChestStackFlags.java       # 线程局部标志：允许跨 UUID 堆叠（GUI 操作期间）
│   │   └── ChestTransaction.java            # 事务包装器：确保多次操作间原子保存状态
│   │
│   └── core/
│       ├── SlotResolver.java                # 槽位解析：基于动态列宽的相对偏移计算（支持非9列容器）
│       │
│       ├── accessor/                        # SlotAccessor 存储后端抽象（模拟优先模式）
│       │   ├── SlotAccessor.java            # 接口：simulateExtract/simulateInsert/extract/insert/rollback + transfer()
│       │   ├── PlainSlotAccessor.java       # 普通槽位：直接读写 ContainerContext
│       │   ├── LivingChestAccessor.java     # 活箱子：通过 LivingChestFunction API 操作虚拟存储
│       │   ├── LivingEnderChestAccessor.java # 活末影箱双模式访问器
│       │   ├── NeighborSlotAccessor.java    # 邻居容器：模拟优先 + rollback优先放回原槽位
│       │   ├── FilteredSlotAccessor.java    # 过滤装饰器（Decorator）：黑白名单过滤
│       │   ├── EnderChannelRegistry.java    # 全局路由表（服务端单例）
│       │   ├── EnderChannelEntry.java       # 路由条目 record
│       │   └── SlotAccessorFactory.java     # 注册式工厂：Provider 接口 + registerProvider() + 自动包装过滤
│       │
│       ├── model/
│       │   ├── Pos2D.java                   # 不可变 2D 坐标，方向常量
│       │   └── SlotMapping.java             # 不可变槽位映射，12 种预设
│       │
│       ├── components/                      # 无状态工具类 + 旧架构组件
│       │   ├── ILivingComponent.java        # 旧架构组件接口（仅 LivingChestFunction 使用）
│       │   ├── InternalStorageComponent.java # 活箱子核心：UUID 管理、LRU 缓存、磁盘 I/O（旧架构）
│       │   ├── DirectionModeComponent.java  # 旧架构方向配置组件（SLOTS/TRANSFER 双模式 + ComponentState）
│       │   ├── ItemTransferComponent.java   # 物品传输逻辑（含跨容器传输触发 + SlotAccessor 调度）
│       │   ├── ItemFilterComponent.java     # 黑白名单过滤（链式传递 + FilterData 支持）
│       │   ├── EnderChannelComponent.java   # 活末影箱频道组件（路由清理 + Tooltip）
│       │   ├── ProgressComponent.java       # 进度工具类（tick/pauseTick/isComplete/reset，支持 ProgressData + ComponentState）
│       │   ├── FuelConsumeComponent.java    # 燃料工具类（消耗、可用性检查）
│       │   ├── ItemTransformComponent.java  # 转化工具类（配方匹配、物品转化、配方缓存）
│       │   ├── ExplosionComponent.java      # 爆炸工具类（引信倒计时、双模式爆炸、流体防爆）
│       │   └── WaterSpreadComponent.java    # 水流扩散工具类（旧架构，待迁移）
│       │
│       ├── orchestrator/                    # 旧架构编排器（仅 LivingChestFunction 使用）
│       │   ├── LivingOrchestrator.java      # 编排器接口 + 通用辅助方法
│       │   ├── SimpleOrchestrator.java      # 简单编排器
│       │   ├── ProgressOrchestrator.java    # 进度编排器
│       │   ├── FuelProgressOrchestrator.java # 燃料+进度编排器
│       │   └── Orchestrators.java           # 编排器工厂
│       │
│       ├── interaction/
│       │   ├── InteractionEntry.java        # 交互规则 record（targetItem + triggerItem + button + actionId）
│       │   ├── InteractionRegistry.java     # 交互注册表（规则查询 + 处理器注册）
│       │   ├── InteractionHandler.java      # 处理器接口（服务端执行交互逻辑）
│       │   ├── IgniteHandler.java           # 点燃槽位TNT（活打火石→活TNT）
│       │   └── IgniteCarriedHandler.java    # 点燃光标TNT（活TNT→活打火石）
│       │
│       └── config/
│           ├── ContainerCompatibilityConfig.java  # 容器兼容性配置（含 columns + findRuleBySize + findOrGenerateRule 自动推断标准布局）
│           └── TransferStrategy.java               # 传输策略
│
│   └── perf/                                # 性能监控指标
│       └── PerfMetrics.java                 # 性能监控：Tick 耗时/活物品数量/功能调用/对象池命中率/传输成功率
│
├── client/
│   ├── GuiInteractionHelper.java            # ⭐ 客户端GUI交互统一工具（查询规则+解析槽位+序列化光标+发包）
│   ├── LivingItemInputHandler.java          # 客户端输入处理：WASD 方向配置 + InputSession
│   ├── LivingItemTooltip.java               # Tooltip 渲染
│   ├── LivingHopperDecorator.java           # 活漏斗箭头叠加层（IItemDecorator，旋转绘制输入/输出箭头）
│   ├── gui/
│   │   └── LivingButton.java                # 活按钮：点击切换 IS_LIVING 标记
│   ├── icon/                                # ⭐ 活物品图标系统（组件化，声明式配置）
│   │   ├── LivingIconSpec.java              # 图标声明式配置（建造者模式：变体+谓词+叠加层）
│   │   ├── LivingIconRegistry.java          # 图标注册中心（统一管理所有活物品图标配置和模型注入）
│   │   ├── GenericLivingModelWrapper.java   # 通用模型包装器（注入自定义 ItemOverrides）
│   │   ├── GenericContextAwareModel.java    # 通用上下文切换模型（GUI 显示自定义图标，手持显示原版图标）
│   │   └── GenericLivingItemOverrides.java  # 通用覆盖解析器（根据 Variant.predicate 匹配变体模型）
│   └── mixin/
│       ├── AbstractContainerScreenMixin.java # 容器界面 Mixin（注入活按钮 + 交互拦截）
│       ├── InventoryScreenMixin.java         # 生存模式背包 Mixin（交互拦截）
│       ├── CreativeModeInventoryScreenMixin.java # 创造模式背包 Mixin（交互拦截 + SlotWrapper兼容）
│       ├── SlotWrapperAccessor.java          # SlotWrapper 访问器接口（获取 target 字段）
│       └── SpriteIconButtonMixin.java        # 按钮渲染 Mixin
│
└── network/
    ├── GuiInteractionPacket.java            # ⭐ 通用GUI交互包（客户端→服务端：slotIndex + containerSlot + actionId + carriedTag）
    ├── CarriedUpdatePacket.java             # 光标更新包（服务端→客户端：绕过创造模式光标同步限制）
    ├── LivingTagPacket.java                 # 活物品标签切换包（客户端→服务端）
    ├── HopperDirectionPacket.java           # 漏斗方向配置包（客户端→服务端，v2 格式）
    └── ServerPacketHandler.java             # 服务端包处理：更新光标物品 NBT + 同步
```

---

## 关键设计决策

### 1. 功能内聚 + DataComponent 直接管理（新架构）

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

### 2. BaseLivingFunction 基类（旧架构，仅 LivingChestFunction 使用）

> ⚠️ 新功能类不再继承 `BaseLivingFunction`，而是直接实现 `LivingItemFunction` 接口。

旧架构中 `BaseLivingFunction` 提供通用的 tick 编排和 Tooltip 实现，子类只需返回 `LivingFunctionConfig`。

新架构中，功能类直接实现 `LivingItemFunction` 接口：
- `tick(entries, context, tick, level)` — 自行实现 tick 逻辑（context 为容器上下文，tick 为 tick 级临时状态）
- `addToTooltip(context, tooltipAdder, flag, stack)` — 直接从 ItemStack 读取 DataComponent 渲染 Tooltip
- `canApply(stack)` — 判断物品是否匹配
- `getFunctionId()` — 返回功能 ID（用于注册日志）

### 3. 方向数据模型

| 模式 | 用途 | 数据结构 | 输入方式 |
|------|------|----------|----------|
| **DirectionSlotsData** | 活熔炉等需要多个命名槽位的场景 | `Map<String, Pos2D>` + `activeSlotIndex` | 代码配置，运行时可通过 WASD 切换活跃槽位 |
| **DirectionTransferData** | 活漏斗等需要动态传输方向的场景 | `sourceOffset: Pos2D` + `targetOffset: Pos2D` | WASD 键入，运行时可变 |

两种方向数据均为不可变 Record，通过 `withXxx()` 方法创建新实例。替代旧的 `DirectionModeComponent` + `ComponentState` 模式。

### 4. 不可变数据模型

所有数据模型使用 Java Record，确保数据不可变性：
- `Pos2D`、`SlotMapping` — 方向和映射
- `ProgressData`、`FuelData`、`TransformData` — 熔炉子数据
- `TransferData`、`FilterData` — 漏斗子数据
- `ExplosionData`、`WaterData` — TNT/水桶子数据
- `LivingTntData`、`LivingFurnaceData` 等 — 功能聚合数据

数据变更通过 `withXxx()` 方法创建新实例，而非修改现有实例。

### 5. 活物品隔离

所有组件在处理物品时检查 `LivingItemManager.isLivingItem()`：
- `ItemTransferComponent`：不传输活物品
- `FuelConsumeComponent`：不消耗活物品作为燃料
- `ItemTransformComponent`：不熔炼活物品

### 6. 服务端权威 + 手动同步

物品数据在服务端是权威的。活物品 tick 修改 DataComponent 后，通过 `ContainerContext.syncSlotToClients()` 主动发送 `ClientboundContainerSetSlotPacket` 同步到客户端，因为原版 `broadcastChanges()` 无法检测自定义 DataComponent 的变化。

**增量同步策略**：新架构中，功能类只在数据实际变化时才写入 DataComponent 并调用同步，减少不必要的网络传输。

**接口拆分**：同步能力独立为 `ContainerSync` 接口，功能类只需依赖此接口即可同步，无需依赖完整的 `ContainerContext`。

### 8. 光标物品操作

活漏斗方向配置时，物品被拿在光标上（`containerMenu.getCarried()`），不在任何槽位中。服务端通过 `getCarried()` 获取引用，修改后用 `ClientboundContainerSetSlotPacket(-1, stateId, -1, ...)` 同步回客户端。

### 9. 容器位置缓存（拉取模型）

`ContainerChunkCache` 采用**拉取模型**，直接维护"世界中所有容器方块的位置"列表，每 tick 遍历这些位置。

**为什么不用推送模型（活跃列表）？**
- 推送模型需要监听"活物品进入容器"的事件，但拖拽、Shift+点击、漏斗输入等场景无法监听
- 定期全量扫描是补丁，不是解决方案——新放入活物品的容器必须等 30 秒才能被发现，体验差

**缓存数据来源（事件驱动）：**
- **强事件（高权威）**：`ChunkEvent.Load`（全量扫描区块中所有方块实体）、`ChunkEvent.Unload`（移除该区块所有容器位置）
- **弱事件（增量更新）**：`BlockEvent` 及所有子类（`EntityPlaceEvent`、`BreakEvent`、`NeighborNotifyEvent`、`PistonEvent` 等），统一调用 `refreshPosition` 检查当前 IItemHandler 能力并更新缓存

**缓存一致性保障：**
- 幽灵条目（缓存有，世界没有）→ tick 时 getCapability 返回 null，自动跳过，无害
- 幽灵容器（世界有，缓存没有）→ 区块卸载后重新加载时全量扫描修正，最多持续到区块重载
- 空维度自动清理，避免内存泄漏

**性能对比：**
- 旧方案（区块缓存 + 活跃列表）：活跃路径 ~10 个位置，全量路径 ~2000 个 BE，新容器延迟 30 秒
- 新方案（容器位置缓存）：~200 个位置，新容器延迟 0（下一个 tick）

**GC 优化：**`LivingItem` 复用 `IdentityHashMap` 和 `HashSet`（实例字段，每 tick clear），避免每 tick 分配新对象。

### 10. GUI交互系统（声明式规则 + 统一拦截）

活物品间的GUI交互通过声明式规则驱动，而非硬编码物品判断：

- **规则声明**：`InteractionEntry(targetItem, triggerItem, button, actionId)` 在 `LivingFunctionConfig` 中注册
- **统一拦截**：所有 Screen Mixin 调用 `GuiInteractionHelper.tryInteract()`，查询 `InteractionRegistry` 匹配规则
- **服务端处理**：`GuiInteractionPacket` 携带 `actionId`，服务端通过 `InteractionRegistry.getHandler()` 查找处理器

新增交互类型只需两步：配置 `InteractionEntry` + 注册 `InteractionHandler`，无需修改任何 Mixin 代码。

### 11. 活物品图标系统（声明式配置 + 通用组件）

活物品图标采用组件化设计，通过 `LivingIconSpec` 声明式配置驱动：

**之前的问题**：每加一种活物品图标需要新建 3-4 个 Java 类（ContextAwareXxxModel、LivingXxxModelWrapper、LivingXxxItemOverrides），代码高度重复。

**解决方案**：
- `LivingIconSpec` — 声明式配置（建造者模式），描述变体列表和判断谓词
- 三个通用组件替代所有物品特定的类：`GenericLivingModelWrapper`、`GenericContextAwareModel`、`GenericLivingItemOverrides`
- `LivingIconRegistry` — 注册中心，统一处理模型注册、注入和叠加层

**核心原理**：
1. `ModelEvent.ModifyBakingResult` 在模型烘焙后注入 `GenericLivingModelWrapper`，替换原版物品模型
2. `GenericLivingItemOverrides.resolve()` 在渲染时根据 `Variant.predicate` 匹配当前变体
3. `GenericContextAwareModel.applyTransform()` 根据 `ItemDisplayContext` 切换：GUI 显示自定义图标，手持显示原版图标
4. `VariantModelStore` 桥接烘焙阶段和渲染阶段，存储变体模型的 `BakedModel` 引用

### 12. 创造模式光标物品同步

创造模式使用 `ItemPickerMenu`，光标物品是客户端虚拟的，服务端 `menu.getCarried()` 返回空。此外原版 `ClientboundContainerSetSlotPacket(containerId=-1)` 明确排除了 `CreativeModeInventoryScreen`。

解决方案分两层：
- **客户端→服务端**：`GuiInteractionPacket` 携带 `carriedTag`（光标物品NBT），仅在 `CreativeModeInventoryScreen` 下发送；服务端收到后 `menu.setCarried()` 恢复光标物品
- **服务端→客户端**：`CarriedUpdatePacket` 自定义包，绕过原版排除逻辑，直接更新客户端 `menu.setCarried()`

注意：创造模式打开容器（箱子等）时使用普通容器界面，光标由服务端管理，不需要 `carriedTag`。`resolveCarriedTag()` 仅在 `CreativeModeInventoryScreen` 下返回非空。

### 12. 创造模式 SlotWrapper 兼容

创造模式 INVENTORY 标签页中，快捷栏槽位被 `CreativeModeInventoryScreen.SlotWrapper` 包装：
- `hoveredSlot.index` = 客户端显示索引
- `SlotWrapper.target.index` = 服务端实际槽位索引

`GuiInteractionHelper.resolveContainerSlot()` 通过 `SlotWrapperAccessor` 获取 `target` 字段，统一处理此差异。`GuiInteractionPacket` 携带双索引（`slotIndex` + `containerSlot`），服务端优先通过 `containerSlot` 遍历匹配。

### 13. 跨容器传输方向映射

活漏斗在容器边界时触发跨容器传输，需要将容器GUI的二维方向（上下左右）转换为世界三维方向（东南西北）。

**方向映射算法**：
1. 以方块朝向北方为基准：UP→SOUTH(后方), DOWN→NORTH(前方), LEFT→EAST(右方), RIGHT→WEST(左方)
2. 根据方块实际朝向进行Y轴顺时针旋转（北0°、东90°、南180°、西270°）

**大箱子半箱选择**：
大箱子由LEFT和RIGHT两个半箱组成，不同边界的跨容器传输需要基于不同半箱的位置查找邻居：
- UP/DOWN方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI下半部分）
- LEFT/RIGHT方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI右半部分）

**防内部传输**：通过位置比较（而非实例比较）检测相邻容器是否为大箱子的另一半箱。`ChestBlock.getContainer()` 每次返回新的 CompoundContainer 实例，`==` 比较无效。

### 14. ExplosionComponent 双模式爆炸

活TNT爆炸根据数量自动选择模式：

| 模式 | TNT数量 | 方块破坏方式 | 掉落物 | 适用场景 |
|------|---------|-------------|--------|---------|
| 普通模式 | ≤64 | 原版 `setBlock()` + `onExplosionHit()` | 可选原版衰减/100%掉落 | 小规模精确爆炸 |
| 大当量模式 | >64 | 直接修改 `LevelChunkSection` 底层数据 | 无 | 大规模性能优化 |

**流体防爆**：
- 普通模式：检查 `FluidState.getExplosionResistance()`，`effectiveResistance >= 100.0F` 的方块（水、岩浆等）绝对不炸
- 大当量模式：由 `power <= effectiveResistance` 自然判断，威力足够大时可突破流体

**爆炸威力公式**：`radius = 4.0 × √(活TNT总数)`
- 1个活TNT → 半径4.0（等同原版TNT）
- 64个活TNT → 半径32.0
- 1728个活TNT → 半径166.0

### 15. 模拟优先传输模式（Simulate-First Pattern）

`SlotAccessor.transfer()` 采用"先模拟确认再真实操作"的模式，替代旧的"先提取再回滚"模式。

**为什么不用旧的"先提取再回滚"模式？**
- 旧模式中，`NeighborSlotAccessor.rollback` 使用 `ItemHandlerHelper.insertItem` 自动找槽位插入
- 当目标槽位不可用时，提取的物品被 rollback 到源容器，但 `insertItem` 可能找到其他槽位
- 导致物品被快速排序到非预期位置

**模拟优先模式流程：**
1. `simulateExtract(amount)` — 检查源能提供多少物品，不修改状态
2. `simulateInsert(stack)` — 检查目标能接受多少物品，不修改状态
3. `extract(toExtract)` — 真实提取
4. `insert(extracted)` — 真实插入
5. `rollback` — 仅作为安全兜底，正常流程不应触发

**rollback 安全兜底 + WARN 日志：**
- rollback 触发意味着 `simulateInsert` 的结果与真实 `insert` 不一致，属于模拟实现的 bug
- `transfer()` 方法在 rollback 时输出 WARN 日志，包含：模拟值 vs 实际值、哪个 Accessor 类型、什么物品
- 帮助快速定位是哪个 SlotAccessor 实现的模拟不准确

### 16. 配方缓存优化

`ItemTransformComponent` 通过 `resolveRecipe()` 公共方法实现配方缓存，避免每 tick 重复查询 `RecipeManager`。

**缓存策略：**
- 在 `ComponentState` 中缓存输入物品 ID（`KEY_CACHED_INPUT`）和配方结果（`KEY_CACHED_OUTPUT`、`KEY_CACHED_OUTPUT_COUNT`）
- 输入物品不变时直接从缓存读取，跳过 `RecipeManager.getRecipeFor()` 查询
- `canProcess()` 和 `executeTransform()` 共享 `resolveRecipe()` 方法，消除代码重复

**性能影响：**大量活熔炉同时工作时，每个活熔炉每 tick 的配方查询从 2 次降为 0 次（缓存命中），零配方查询开销。

### 17. IItemHandler 统一容器抽象

项目全面使用 NeoForge 的 `IItemHandler` 能力替代原版 `Container` 接口进行容器读写和物品交互，**无需适配器层**。

**为什么用 IItemHandler？**
- NeoForge 自动为所有原版 Container 方块注册 `IItemHandler` 能力，无需区分方块类型
- 模组容器（抽屉、精妙背包等）通过 `IItemHandler` 暴露能力，天然兼容
- `getSlotLimit(slot)` 返回每槽真实上限（抽屉 2048、精妙背包 256），远高于 `ItemStack.getMaxStackSize()` 的固定 64

**核心实现：**
- `SimpleContainerContext`：直接持有 `IItemHandler handler` 字段，`getItem()`/`setItem()` 直接调用 `handler.getStackInSlot()`/`handler.extractItem()`/`handler.insertItem()`
- `ContainerContext.getSlotLimit(slot)`：委托给 `handler.getSlotLimit(slot)`，玩家盔甲槽位（36-39）返回 `getMaxStackSize()`，允许活漏斗无视限制
- `ContainerContext.simulateInsertItem(slot, stack)`：委托给 `handler.insertItem(slot, stack, true)` 模拟插入；盔甲槽位直接按普通槽位计算，不做类型限制
- `SimpleContainerContext.setItem()`：`handler.insertItem()` 失败时，若为玩家盔甲槽位则直接 `inventory.armor.set()` 绕过限制，实现"方块放头上"等趣味玩法
- `ContainerCompatibilityConfig.findOrGenerateRule(size)`：根据 `IItemHandler.getSlots()` 自动推断标准矩形布局，无需手动注册

**传输防护（双重限制）：**
- `PlainSlotAccessor.insert()`：使用 `Math.min(slotLimit, stack.getMaxStackSize())` 同时检查槽位上限和物品最大堆叠上限，防止创建超出物品类型限制的堆叠
- `CrossContainerTransfer.tryInsert()`：统一使用 `ItemHandlerHelper.insertItemStacked()` 一行完成插入，容器自动处理分堆和上限
- `CrossContainerTransfer.hasAnySpace()`：使用 `handler.getSlotLimit(i)` 精确判断每槽空间

**容器检测：**
- `ContainerChunkCache`：通过 `Capabilities.ItemHandler.BLOCK` 检测容器（替代 `Container` 接口检查）
- `ContainerLivingItemHandler.processBlockEntities()`：使用 `IdentityHashMap<IItemHandler, Boolean>` 去重（大箱子左右半箱共享同一 `IItemHandler` 实例，天然去重）
- `ContainerLivingItemHandler.buildChestContext()`：统一使用 `IItemHandler` 获取容器，移除 `ChestBlock.getContainer()` 依赖

---

## 已完成功能

### 基础设施
- [x] 活按钮 UI 与物品活化机制（`LivingButton` + `LivingTagPacket`）
- [x] DataComponent 数据持久化系统
- [x] 容器自动扫描与 tick 分发（`ContainerChunkCache` + `ContainerLivingItemHandler`）
- [x] 多活物品并行处理（按功能分组，无冲突）
- [x] 组件化架构（`ILivingComponent` + `FunctionExecutor` 工具类）
- [x] 编排器模式（`LivingOrchestrator` + 3 种内置编排器）
- [x] 功能基类（`BaseLivingFunction`：通用 tick + Tooltip）
- [x] 不可变数据模型（`Pos2D` + `SlotMapping` record）
- [x] 活物品隔离（不传输/不熔炼/不作为燃料）

### GUI交互系统
- [x] 声明式交互规则（`InteractionEntry` record + `InteractionRegistry` 注册表）
- [x] 客户端统一拦截（`GuiInteractionHelper.tryInteract()`，所有 Screen Mixin 共用）
- [x] 通用交互网络包（`GuiInteractionPacket`：slotIndex + containerSlot + actionId + carriedTag）
- [x] 创造模式光标物品同步（`CarriedUpdatePacket` 绕过原版排除逻辑）
- [x] 创造模式 SlotWrapper 兼容（`SlotWrapperAccessor` + 双索引机制）
- [x] 交互处理器注册（`InteractionHandler` 接口 + `IgniteHandler` / `IgniteCarriedHandler`）

### 活熔炉功能
- [x] SLOTS 模式方向配置（input→LEFT, fuel→DOWN, output→RIGHT）
- [x] 配方匹配与物品转化
- [x] 燃料消耗与燃烧时间管理
- [x] 无效条件时暂停并回退进度
- [x] 跨容器状态保持（移动后保留 burnTime）
- [x] 多实例加速（不同槽位的活熔炉独立工作）

### 活漏斗功能
- [x] TRANSFER 模式方向配置（默认上传下 UP→DOWN）
- [x] WASD 键入改变传输方向（需悬停活按钮 + 拿起活漏斗）
- [x] 跨容器传输（活漏斗在容器边界时与相邻容器交互）
- [x] 跨容器方向映射（GUI方向 ↔ 世界方向，基于方块朝向旋转）
- [x] 大箱子跨容器传输（根据边界方向选择LEFT/RIGHT半箱作为基准位置）
- [x] 网络包同步（`HopperDirectionPacket` v2 格式）
- [x] Tooltip 实时显示当前传输方向
- [x] 传输冷却机制（基于物品数量动态调整）

### 活TNT功能
- [x] 引信倒计时（80 tick = 4秒，与原版TNT一致）
- [x] 两种点燃方式（活打火石右键活TNT / 活TNT右键活打火石）
- [x] 爆炸威力随数量缩放（radius = 4.0 × √数量）
- [x] 普通模式（≤64 TNT）：原版掉落物 + 可选100%掉落
- [x] 大当量模式（>64 TNT）：直接修改区块数据，高性能
- [x] 流体防爆（普通模式绝对防爆，大当量模式威力突破时可炸流体）
- [x] 实体伤害与击退（原版公式）
- [x] 创造模式/生存模式全兼容

### 活箱子功能
- [x] 基于 DataComponent 的直接存储（`CONTAINER` 组件 = `ItemContainerContents`，27 槽）
- [x] 字节容量限制（`InternalStorageComponent.MAX_STORAGE_BYTES = 16384`，防止 NBT 过大）
- [x] 快速空/满判断（`_us` 已用槽位计数，`_bu` 字节用量，O(1) 短路判断）
- [x] 漏斗自动传输（活箱子 tick 时通过漏斗推拉物品）
- [x] 跨容器传输（活箱子在容器边界时与相邻容器交互）
- [x] 活物品堆叠比较（`ItemStackMixin.isSameItemSameComponents` 忽略运行时状态组件，允许活箱子堆叠）
- [x] 创造模式中键防复制（原版行为：活箱子的含 NBT 物品不在创造物品列表中，中键拾取失败）
- [x] 铁砧重命名兼容（`isSameItemSameComponents` 只忽略运行时组件差异，保留名称等 NBT 差异）
- [x] 配方书支持（`ServerPlaceRecipeMixin` 服务端注入活箱子物品到合成栏）
- [x] 活箱子图标（`chest_living.png`）
- [ ] 方块放置自动填充（活箱子放置为实体箱子时，保留存储内容）
- [ ] 三层防护体系（禁止发射器/投掷器等自动化系统操作活箱子）

### 活末影箱功能
- [x] 路由模式（无绑定玩家）：通过全局路由表实现跨容器无线传输
- [x] 直连模式（有绑定玩家）：直接读写绑定玩家的末影箱背包
- [x] 玩家绑定机制（末影箱GUI中活化 → 绑定当前玩家UUID+名称，取消活化时清空）
- [x] 频道隔离（堆叠数 = 频道号，不同堆叠数互不干扰）
- [x] 轮询公平调度（nextIndex 指针轮流取，每个 push 端机会均等）
- [x] 末影箱容器处理（`processEnderChest` + `EnderChestContainerContext`，活物品在末影箱中正常工作）
- [x] 直连模式预加载（构造时缓存 `PlayerEnderChestContainer` 引用，离线跳过）
- [x] 黑白名单统一过滤（`FilteredSlotAccessor` 装饰器，所有传输自动遵守活漏斗黑白名单，活末影箱无需拥有 ItemFilterComponent）
- [x] 跨容器传输架构统一（`NeighborSlotAccessor` + `SlotAccessor.transfer()` 统一传输逻辑）
- [x] 反向索引路由清理（`posIndex` + `keyIndex`，O(相关路由) 清理）
- [x] 活末影箱移走后路由清理（`removeStaleEnderChestRoutes`）
- [x] 源物品移走后路由清理（`cleanStaleSourceRoutes`，使用反向索引优化）
- [x] 活漏斗移走后路由清理（`removeStaleRoutes`）
- [x] 区块卸载路由清理（`onChunkUnload`）
- [x] Tooltip 显示（路由模式：频道号+路由数量+高级模式路由详情；直连模式：绑定玩家名称）
- [x] 组件化改造（`EnderChannelComponent` 路由清理，与其他活物品架构一致；活末影箱不拥有 ItemFilterComponent，过滤由活漏斗侧统一处理）
- [x] 大箱子双重去重（`IdentityHashMap<IItemHandler>` + `HashSet<containerKey>`）
- [x] 水晶箱子黑白名单适配（`SlotResolver` 使用容器实际宽度计算槽位）
- [x] 跨容器路由注册修复（`getBasePosForDirection` 大箱子半箱选择逻辑修正）

### 容器兼容性
- [x] 标准矩形容器（27 格箱子、54 格大箱子）
- [x] 线性容器（5 格漏斗）
- [x] 边界检查与异常安全
- [x] IItemHandler 去重（大箱子左右半箱共享同一实例，天然去重）
- [x] 模组容器兼容（通过 IItemHandler 直接适配，抽屉、精妙背包等）
- [x] 自动布局推断（ContainerCompatibilityConfig.findOrGenerateRule 根据槽位数自动推断列宽）

---

## 开发进展

### 当前版本: v0.8-alpha

**最近更新** (2026-07-27):
- ✅ 重构：DataComponent 直接管理架构迁移（功能类直接管理类型化 DataComponent，替代 `ComponentState` + `LivingFunctionData` 中转层）
- ✅ 重构：组件无状态化改造（`ProgressComponent`、`FuelConsumeComponent` 等从有状态组件变为无状态工具类，接收类型化数据返回新数据）
- ✅ 新增：`data/` 包 — 不可变 Record 数据模型（`LivingTntData`、`LivingWaterBucketData`、`LivingFurnaceData`、`LivingHopperData`、`LivingEnderChestData` 及其子数据）
- ✅ 新增：`DirectionSlotsData` / `DirectionTransferData` 不可变方向数据模型，替代 `DirectionModeComponent` + `ComponentState`
- ✅ 重构：`LivingItemFunction.addToTooltip()` 移除 `CompoundTag` 参数，功能类直接从 `ItemStack` 读取 DataComponent
- ✅ 重构：`LivingTntFunction`、`LivingWaterBucketFunction`、`LivingFurnaceFunction`、`LivingHopperFunction`、`LivingEnderChestFunction` 迁移到新架构
- ✅ 重构：客户端渲染层迁移（`AbstractContainerScreenMixin` 水流渲染从 `LivingFunctionData` → `LivingWaterBucketData`；`LivingHopperDecorator` 从 `readDirectionState` → `readDirectionData`；`LivingItemInputHandler` 移除 `DirectionModeComponent` 依赖）
- ✅ 重构：`LivingItemTooltip` 简化（移除 `getFunctionData()` 调用，直接调用 `function.addToTooltip()`）
- ✅ 重构：`ItemFilterComponent.inheritFilter()` 从 `getFunctionData` 迁移到 `LivingItemManager.getHopperData()`
- ✅ 新增：`SlotMapping.fromDirections(Pos2D, Pos2D)` 工厂方法
- ✅ 修复：`LivingWaterBucketData.DEFAULT` → `LivingWaterBucketData.EMPTY`
- ✅ 修复：`ExplosionData.ignite()` 无参重载方法（默认 80 刻引信）
- ✅ 兼容：`ProgressComponent` 实现 `ILivingComponent` 接口 + `ComponentState` 适配器方法，保持旧编排器编译兼容
- ✅ **优化：TickContext 对象池**（`TickContextPool` 复用 tick 实例，减少 GC 压力，ThreadLocal 线程安全，命中率 ~87%）
- ✅ **优化：SlotAccessor 注册式工厂**（`SlotAccessorFactory.registerProvider()` 开放扩展，第三方模组可注册自定义 Accessor）
- ✅ **优化：LivingItemFunction 接口职责拆分**（5 个逻辑模块：匹配/标识/Tick/Tooltip/组件过滤，可选方法默认空实现）
- ✅ **优化：活箱子精确字节计算**（`LivingChestFunction.calculateExactByteUsage()` 替代粗糙估算，NBT 序列化获取真实大小，Tooltip 显示百分比）
- ✅ **新增：性能监控指标系统**（`PerfMetrics` 收集 Tick 耗时/活物品数量/功能调用/对象池命中率/传输成功率，每 60 秒自动打印报告）

**历史更新** (2026-07-24):
- ✅ 重构：SlotAccessor 模拟优先传输模式（`simulateExtract` → `simulateInsert` → `extract` → `insert` → `rollback` 安全兜底 + WARN 日志）
- ✅ 新增：`SlotAccessor` 接口新增 `simulateExtract()` 和 `simulateInsert()` 模拟方法（所有实现类均已实现）
- ✅ 优化：`SlotAccessor.transfer()` 改为"先模拟确认再真实操作"，彻底消除旧"先提取再回滚"模式的物品排序问题
- ✅ 优化：`rollback` 修复部分插入场景（仅在 `inserted < extracted.getCount()` 时退回剩余部分，防止物品复制）
- ✅ 优化：`NeighborSlotAccessor.rollback()` 优先放回原槽位，仅在原槽位不可用时才自动找位置插入
- ✅ 优化：配方缓存（`ItemTransformComponent.resolveRecipe()` 公共方法，`canProcess()` 和 `executeTransform()` 共享，`ComponentState` 缓存输入物品和配方结果）
- ✅ 优化：容器位置缓存（拉取模型）替代活跃列表（推送模型），`ContainerChunkCache` 直接维护所有容器位置，新容器零延迟发现
- ✅ 新增：`BlockEvent` 通用监听兜底（任何方块变化都刷新缓存状态，解决 `/setblock`、结构生成等弱事件无法捕获的场景）
- ✅ 优化：GC 压力降低（`LivingItem` 复用 `IdentityHashMap` 和 `HashSet` 实例字段，每 tick clear 而非 new）
- ✅ 优化：惰性 Tick 去重集合复用（实例字段，避免每 tick 分配新对象）

**历史更新** (2026-07-23):
- ✅ 新增：活末影箱系统（`LivingEnderChestFunction` + `LivingEnderChestAccessor` + `EnderChannelRegistry` + `EnderChannelEntry`）
- ✅ 新增：活末影箱双模式（路由模式：共享黑板无线传输 + 直连模式：绑定玩家末影箱直连）
- ✅ 新增：玩家绑定机制（末影箱GUI中活化绑定UUID+名称，取消活化清空）
- ✅ 新增：直连模式预加载（构造时缓存 `PlayerEnderChestContainer` 引用，离线跳过）
- ✅ 新增：`EnderChannelComponent` 频道组件（路由清理 + Tooltip 构建，与其他活物品架构一致）
- ✅ 新增：`ItemFilterComponent` 黑白名单过滤组件（链式传递：每 tick 沿漏斗链传播一跳，支持活漏斗+活末影箱）
- ✅ 新增：`FilteredSlotAccessor` 过滤装饰器（Decorator 模式，统一所有传输的黑白名单过滤）
- ✅ 新增：`NeighborSlotAccessor` 邻居容器访问器（跨容器传输统一接入 SlotAccessor 架构）
- ✅ 新增：`SlotAccessor.transfer()` 统一传输方法（extract→insert→rollback 三步原子传输）
- ✅ 新增：`SlotAccessorFactory.createForNeighbor()` 工厂方法（邻居容器 + 自动包装过滤装饰器）
- ✅ 新增：末影箱容器处理（`processEnderChest` + `EnderChestContainerContext`）
- ✅ 新增：反向索引路由清理（`posIndex` + `keyIndex`，O(相关路由) 清理）
- ✅ 新增：活末影箱 Tooltip（路由模式：频道+路由+详情；直连模式：绑定玩家名称）
- ✅ 重构：`CrossContainerTransfer` 使用 `NeighborSlotAccessor` + `SlotAccessor.transfer()` 统一传输
- ✅ 重构：活末影箱组件化改造（`EnderChannelComponent` + `ItemFilterComponent`，与其他活物品架构一致）
- ✅ 修复：末影箱中活物品不工作（新增 `processEnderChest` 方法处理末影箱容器）
- ✅ 修复：活漏斗往绑定玩家的活末影箱输入物品不消耗（修正 `LivingEnderChestAccessor.insert` 返回值和物品栈修改逻辑）
- ✅ 修复：末影箱中未绑定玩家的活末影箱无法建立路由
- ✅ 修复：水晶箱子（12×9）中活漏斗黑白名单无法正确生效（`SlotResolver` 使用容器实际宽度）
- ✅ 修复：大箱子中活物品一 tick 内被处理两次（双重去重：`IdentityHashMap<IItemHandler>` + `HashSet<containerKey>`）
- ✅ 修复：跨容器注册路由不生效（`getBasePosForDirection` 大箱子半箱选择逻辑修正）
- ✅ 修复：已绑定玩家的活末影箱仍显示频道信息（Tooltip 条件判断）
- ✅ 修复：活末影箱移走后路由未清理（`EnderChannelComponent.tick` → `removeStaleEnderChestRoutes`）
- ✅ 修复：源物品移走后路由未清理（`cleanStaleSourceRoutes`，使用反向索引优化）
- ✅ 修复：`EnderChannelRegistry.getChannels()` 遍历时 `ConcurrentModificationException`（返回安全副本）

**历史更新** (2026-07-22):
- ✅ 重构：全面使用 IItemHandler 统一容器抽象（替代 Container 接口检查）
- ✅ 新增：`ItemHandlerWrapper` 适配器（IItemHandler → Container 桥接，record 实现，已废弃）
- ✅ 新增：`ContainerContext.getSlotLimit(slot)` 接口方法（感知模组槽位上限）
- ✅ 优化：`CrossContainerTransfer.tryInsert()` 使用 `ItemHandlerHelper.insertItemStacked()`
- ✅ 优化：`CrossContainerTransfer.hasAnySpace()` 使用 `getSlotLimit(i)`
- ✅ 优化：`PlainSlotAccessor.insert()` / `isFull()` 使用 `getSlotLimit(slot)` 替代 `getMaxStackSize()`
- ✅ 优化：`ItemTransformComponent.calculateOutputSpace()` 使用 `getSlotLimit(outputSlot)`
- ✅ 优化：`ContainerCompatibilityConfig` 新增 `findOrGenerateRule()` 自动推断标准布局
- ✅ 优化：`ContainerLivingItemHandler.buildChestContext()` 统一使用 IItemHandler，移除 `ChestBlock.getContainer()`
- ✅ 优化：`ContainerLivingItemHandler.processBlockEntities()` 使用 `IdentityHashMap<IItemHandler>` 去重
- ✅ 优化：`ContainerChunkCache` 通过 `Capabilities.ItemHandler.BLOCK` 检测容器
- ✅ 修复：抽屉模组容器满时活漏斗仍传输导致物品消失
- ✅ 修复：精妙背包堆叠上限升级后活漏斗误判槽位已满

**历史更新** (2026-07-22):
- ✅ 重构：活漏斗传输引擎引入 SlotAccessor 统一架构（extract → insert → rollback 统一流程）
- ✅ 新增：`SlotAccessor` 接口 + `PlainSlotAccessor` + `LivingChestAccessor` + `SlotAccessorFactory`
- ✅ 删除：4 个旧传输方法（`transferBetweenSlots`/`transferToLivingChest`/`transferFromLivingChest`/`transferBetweenLivingChests`），~400 行重复代码
- ✅ 修复：混搭黑白名单链传递失效（`inheritFilter` 同时继承邻居的黑白名单）
- ✅ 修复：过滤器拦截时冷却未设置导致无限循环（冷却设置移到 if(success) 外部）

**历史更新** (2026-07-20):
- ✅ 新增：方块放置自动填充（生存模式消耗头部UUID + 填充物品，创造模式保留UUID）
- ✅ 新增：三层防护体系（split拦截 + 发射器空分发 + 投掷器选槽拦截）
- ✅ 新增：铁砧重命名堆叠修复（副本比较法）
- ✅ 统一：UUID 操作方向（头部优先存取/拆分，尾部弹出）

**历史更新** (2026-07-17):
- ✅ 新增：活箱子系统（`LivingChestFunction` + `InternalStorageComponent` + `ChestTransaction`）
- ✅ 新增：UUID 映射管理（`LivingChestStackHandler`：创建、拆分、合并、标准化）
- ✅ 新增：堆叠倍增模型（1 堆叠 = 1 虚拟箱子 = 27 槽，64 堆叠 = 1728 槽）
- ✅ 新增：LRU 缓存策略（最大 200 条，5 分钟空闲超时，脏数据先保存）
- ✅ 新增：快速短路判断（`_us` 已用槽位计数，O(1) 空/满判断，避免无效传输）
- ✅ 新增：漏斗自动传输（活箱子 tick 时通过漏斗推拉物品）
- ✅ 新增：跨容器传输（活箱子在容器边界时与相邻容器交互）
- ✅ 新增：GUI 拆分/合并 UUID 自动分配（`ItemStackMixin` 拦截堆叠操作）
- ✅ 新增：跨 UUID 堆叠支持（`LivingChestStackFlags` 线程局部标志）
- ✅ 新增：被动孤儿文件清理（防止取消活化后磁盘文件泄漏）
- ✅ 新增：事务包装器（`ChestTransaction` 确保多次操作间原子保存）
- ✅ 重构：living 文件夹按职责拆分为 `function/`、`container/`、`chest/` 子包
- ✅ 修复：堆叠活箱子时漏斗只能访问到一个活箱子的问题
- ✅ 修复：UUID 创建时机从惰性初始化改为首次 tick 主动初始化
- ✅ 修复：跨包访问权限（`saveStorageState` → public static）

**历史更新** (2026-07-11):
- ✅ 新增：活TNT功能（`LivingTntFunction` + `ExplosionComponent`）
- ✅ 新增：活打火石功能（`LivingFlintAndSteelFunction`，交互触发器，无 tick 逻辑）
- ✅ 新增：GUI交互系统（`InteractionEntry` + `InteractionRegistry` + `InteractionHandler`）
- ✅ 新增：客户端统一交互工具（`GuiInteractionHelper.tryInteract()`）
- ✅ 新增：通用交互网络包（`GuiInteractionPacket`：双索引 + actionId + carriedTag）
- ✅ 新增：创造模式光标同步（`CarriedUpdatePacket` 绕过原版排除逻辑）
- ✅ 新增：创造模式 SlotWrapper 兼容（`SlotWrapperAccessor` + 双索引机制）
- ✅ 新增：ExplosionComponent 双模式爆炸（普通模式 + 大当量模式）
- ✅ 新增：流体防爆机制（普通模式绝对防爆，大当量模式威力突破时可炸流体）
- ✅ 修复：创造模式背包中活打火石无法点燃活TNT
- ✅ 修复：创造模式光标物品消失问题（`resolveCarriedTag` 仅在 `CreativeModeInventoryScreen` 下发送）
- ✅ 修复：创造模式打开容器时光标物品消失（区分背包界面和容器界面的光标管理）
- ✅ 重构：所有 Screen Mixin 统一使用 `GuiInteractionHelper.tryInteract()`，移除硬编码物品判断
- ✅ 重构：引入编排器模式（`LivingOrchestrator` + `SimpleOrchestrator` / `ProgressOrchestrator` / `FuelProgressOrchestrator`）
- ✅ 重构：提取 `BaseLivingFunction` 基类，通用 tick 编排 + Tooltip 实现
- ✅ 重构：`LivingFurnaceFunction` 从 317 行 → 82 行（-74%），只保留配置
- ✅ 重构：`LivingHopperFunction` 从 219 行 → 75 行（-66%），只保留配置 + NBT 工具
- ✅ 重构：`FunctionExecutor` 从调度器变为纯工具类，移除 `tick()` 方法和所有业务逻辑
- ✅ 重构：`DirectionModeComponent` NBT 自治（`updateStateInStack` / `readStateFromStack`）
- ✅ 重构：`LivingFunctionConfig` 新增 `withOrchestrator()` 方法，支持声明式编排器选择
- ✅ 重构：`ComponentContext` 使用 `ResolvedSlots` 替代三个独立字段，方向解析内聚到 `DirectionModeComponent`
- ✅ 重构：`ContainerContext` 新增槽位占用机制（`getOccupiedSlots` / `getStableKey`），从 FunctionExecutor 实例字段迁移
- ✅ 简化：`LivingHopperFunction.canApply()` 移除冗余的物品匹配检查
- ✅ 简化：`LivingItemFunction` 接口新增 `appendComponentTooltips()` 默认方法，消除 Tooltip 重复代码

### 历史更新 (v0.3-alpha):
- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）
- ✅ 新增：活物品隔离（不传输/不熔炼/不作为燃料）

### 待办事项

#### 高优先级
- [ ] `LivingChestFunction` 迁移到新架构（DataComponent 直接管理 + 功能内聚）
- [ ] `LivingFlintAndSteelFunction` 迁移到新架构
- [ ] 移除旧基础设施（`ComponentState`、`LivingFunctionData`、`FunctionExecutor`、`Orchestrator` 等，待所有功能类迁移完成后）
- [ ] 更多活物品类型（活投掷器、活发射器、活酿造台等）
- [ ] 活熔炉 Tooltip 增强（显示工作模式、预计剩余时间）
- [ ] 活TNT 红石信号触发（容器被红石激活时自动点燃）
- [ ] 黑白名单传递优化（改为一 tick 传递完整条漏斗链，而非逐跳传播）

#### 中优先级
- [ ] 调试命令 `/livingitem info`
- [ ] 成就系统集成
- [ ] 音效差异化（不同状态的音效变化）
- [ ] 活TNT 尊重 `tntExplosionDropDecay` 游戏规则

#### 低优先级 / 未来规划
- [ ] 活物品状态切换机制（更多配置选项）
- [ ] 第三方模组适配器 API 开放
- [ ] JSON 配置文件支持（用户自定义容器规则）
- [ ] JSON 驱动的活物品注册（无需编写 Java 类）
- [ ] 槽位内活物品的配置支持（当前仅支持光标物品配置）

---

## 技术栈

- **Java 21** + **NeoForge 21.1.x**
- **Minecraft 1.21.1**
- 构建工具: Gradle
- 数据持久化: Minecraft DataComponent API + NBT
- 容器访问: NeoForge IItemHandler 能力（Capabilities.ItemHandler.BLOCK）
- 网络通信: NeoForge CustomPacketPayload API

---

## 设计原则

1. **功能内聚**：每个功能类直接管理其 DataComponent，自行实现 tick 逻辑和数据管理
2. **数据不可变**：所有数据模型使用 Java Record，通过 `withXxx()` 创建新实例
3. **无状态工具类**：组件作为无状态工具类，接收类型化数据返回新数据，不持有状态
4. **增量同步**：数据变化时才写入 DataComponent 并同步到客户端
5. **类型安全**：`data.progress()` 替代 `state.getInt("progress")`，编译时检查
6. **状态完全持久化**：所有数据存储在 DataComponent 中，跟随物品迁移
7. **服务端权威**：客户端只负责输入和显示，数据修改在服务端执行后同步回客户端
8. **防御性编程**：多层边界检查，优雅降级不崩溃
9. **开放扩展**：新活物品类型只需实现 `LivingItemFunction` + 定义 DataComponent + 实现逻辑

---

## 贡献指南

### 新增活物品类型（新架构）

1. 在 `data/` 包中创建不可变 Record 数据模型（如 `LivingXxxData`）及其子数据
2. 在 `LivingItemManager` 中注册 DataComponent 类型并添加 get/set 访问器
3. 在 `function/` 包中创建功能类，实现 `LivingItemFunction` 接口
4. 在 `LivingItem.commonSetup()` 中注册功能

**完整示例（以活TNT为例）**：
```java
// 1. 数据模型 (data/LivingTntData.java)
public record LivingTntData(ExplosionData explosion) {
    public static final LivingTntData EMPTY = new LivingTntData(ExplosionData.EMPTY);
    public LivingTntData withExplosion(ExplosionData explosion) { return new LivingTntData(explosion); }
}

// 2. DataComponent 注册 (LivingItemManager.java)
public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingTntData>> LIVING_TNT_DATA =
    COMPONENTS.register("living_tnt_data", () -> DataComponentType.<LivingTntData>builder()
        .persistent(LivingTntData.CODEC).networkSynchronized(LivingTntData.STREAM_CODEC).build());

// 3. 功能类 (function/LivingTntFunction.java)
public class LivingTntFunction implements LivingItemFunction {
    @Override public void tick(List<SlotEntry> entries, ContainerContext context, Level level) {
        // 直接管理 DataComponent
        LivingTntData data = LivingItemManager.getTntData(stack);
        data = data.withExplosion(data.explosion().tick());
        LivingItemManager.setTntData(stack, data);
        context.syncSlotToClients(slot, stack);
    }
    @Override public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                                        TooltipFlag flag, ItemStack stack) {
        LivingTntData data = LivingItemManager.getTntData(stack);
        // 直接从 DataComponent 读取数据渲染 Tooltip
    }
}
```

### 新增无状态工具类

1. 创建工具类，提供接收类型化数据并返回新数据的静态/实例方法
2. 在功能类的 `tick()` 方法中调用工具类处理具体逻辑
3. 工具类不持有状态，不依赖 `ComponentState` 或 `ComponentConfig`

### 代码风格

- 使用中文注释（与项目语言一致）
- 遵循现有命名约定（Data/Function/Component/Context 后缀）
- 异常处理必须使用 try-catch 包装容器操作
- 不可变数据优先使用 Java record
- 数据变更通过 `withXxx()` 方法创建新实例

---

*最后更新: 2026-07-27*
*状态: Alpha 测试阶段 - DataComponent 直接管理架构迁移已完成（活TNT/活水桶/活熔炉/活漏斗/活末影箱），活箱子待迁移，跨容器传输已实现，IItemHandler 直接驱动容器读写，兼容抽屉、精妙背包等模组容器，GUI交互系统已就绪，客户端图标系统已组件化，SlotAccessor 模拟优先传输架构已实现，活末影箱双模式（路由/直连）+ 反向索引路由清理 + FilteredSlotAccessor 统一过滤，容器位置缓存（拉取模型）实现零延迟容器发现，不可变数据模型 + 功能内聚 + 无状态工具类新架构，TickContext 对象池优化，SlotAccessor 注册式工厂，LivingItemFunction 接口职责拆分，活箱子精确字节计算，性能监控指标系统*