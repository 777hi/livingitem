# 重构完成报告

> **完成日期**: 2026-08-16  
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [重构总览](#1-重构总览)
2. [阶段 0：包结构重组](#2-阶段-0包结构重组)
3. [阶段 1：传输管道统一](#3-阶段-1传输管道统一)
4. [阶段 2：活末影箱路由解耦](#4-阶段-2活末影箱路由解耦)
5. [阶段 3：活熔炉同步生命周期统一](#5-阶段-3活熔炉同步生命周期统一)
6. [补齐项](#6-补齐项)
7. [重构后文件结构](#7-重构后文件结构)
8. [未做的事](#8-未做的事)

---

## 1. 重构总览

### 1.1 重构目标

将活物品模组从"功能散布在多个包"的初始架构，重组为"领域内聚 + 基础设施共享"的分层架构，为未来 lib 化提供基础。

### 1.2 核心原则

| 原则 | 说明 |
|------|------|
| **领域内聚** | 每个活物品的 tick 编排、领域逻辑、数据模型聚合在 `domain/` 下 |
| **基础设施共享** | `container/` 和 `transfer/` 只放跨活物品共享的基础设施 |
| **数据跟着领域走** | 领域专属数据迁入 `domain/`，跨领域共享数据留在 `transfer/` |
| **每个阶段独立可编译** | 阶段间无依赖，任意阶段可独立回退 |

### 1.3 完成状态

| 阶段 | 状态 | 风险 | 耗时 |
|------|------|------|------|
| 阶段 0：包结构重组 | ✅ 完成 | 低 | ~1h |
| 阶段 1：传输管道统一 | ✅ 完成 | 中 | ~2h |
| 阶段 2：活末影箱路由解耦 | ✅ 完成 | 中 | ~1.5h |
| 阶段 3：活熔炉同步生命周期统一 | ✅ 完成 | 低 | ~1h |
| 补齐项 A：HopperFilterBuilder 提取 | ✅ 完成 | 低 | ~0.5h |
| 补齐项 B：LivingEnderChestAccessor 精简 | ✅ 完成 | 低 | ~0.5h |
| 补齐项 C：脏槽位批量同步 | ✅ 完成 | 低 | ~0.5h |

---

## 2. 阶段 0：包结构重组

### 2.1 目标

让每个活物品的代码内聚到 `domain/`，`container/` 和 `transfer/` 只留真正的跨活物品基础设施。

### 2.2 执行的迁移

#### 活漏斗领域 → `domain/hopper/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingHopperFunction` | `function/` | `domain/hopper/` |
| `CrossContainerTransfer` | `container/` | `domain/hopper/` |
| `TransferStrategy` | `transfer/` | `domain/hopper/` |
| `LivingHopperData` | `data/` | `domain/hopper/` |
| `TransferData` | `data/` | `domain/hopper/` |
| `DirectionTransferData` | `data/` | `domain/hopper/` |

#### 活箱子领域 → `domain/chest/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingChestFunction` | `function/` | `domain/chest/` |
| `LivingChestAccessor` | `domain/ender/` | `domain/chest/` |
| `LivingChestItemHandler` | `domain/ender/` | `domain/chest/` |
| `LivingChestTooltipComponent` | `domain/ender/` | `domain/chest/` |

#### 活末影箱领域 → `domain/ender/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingEnderChestFunction` | `function/` | `domain/ender/` |
| `LivingEnderChestData` | `data/` | `domain/ender/` |
| `EnderChannelData` | `data/` | `domain/ender/` |

#### 活熔炉领域 → `domain/furnace/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingFurnaceFunction` | `function/` | `domain/furnace/` |
| `LivingFurnaceData` | `data/` | `domain/furnace/` |
| `ProgressData` | `data/` | `domain/furnace/` |
| `FuelData` | `data/` | `domain/furnace/` |
| `TransformData` | `data/` | `domain/water/` | → `domain/furnace/` |
| `DirectionSlotsData` | `data/` | `domain/furnace/` |

#### 活水领域 → `domain/water/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingWaterBucketFunction` | `function/` | `domain/water/` |
| `LivingWaterWheelFunction` | `function/` | `domain/water/` |
| `LivingWaterBucketData` | `data/` | `domain/water/` |
| `WaterData` | `data/` | `domain/water/` |
| `LivingWaterWheelData` | `data/` | `domain/water/` |
| `WaterWheelData` | `data/` | `domain/water/` |

#### 活TNT领域 → `domain/tnt/`

| 文件 | 原位置 | 新位置 |
|------|--------|--------|
| `LivingTntFunction` | `function/` | `domain/tnt/` |
| `LivingTntData` | `data/` | `domain/tnt/` |
| `ExplosionData` | `data/` | `domain/tnt/` |

#### 跨领域共享数据 → `transfer/`

| 文件 | 原位置 | 新位置 | 原因 |
|------|--------|--------|------|
| `FilterData` | `data/` | `transfer/` | 传输过滤规则，被 `FilteredSlotAccessor` 消费 |

### 2.3 删除的目录

- **`data/`** — 全部 17 个文件迁出后删除。数据跟着领域走，跨领域共享的跟着基础设施走。

### 2.4 精简后的包

| 包 | 变化 | 保留内容 |
|-----|------|---------|
| `container/` | 移出 `CrossContainerTransfer` | `ContainerContext`, `SimpleContainerContext`, `ContainerLivingItemHandler`, `ContainerChunkCache`, `ContainerSnapshot`, `ContainerSync`, `SlotInfoProvider`, `ContainerIdentity`, `TickContext` |
| `transfer/` | 移出 `TransferStrategy`，迁入 `FilterData` | `SlotAccessor`, `PlainSlotAccessor`, `FilteredSlotAccessor`, `NeighborSlotAccessor`, `SlotAccessorFactory`, `SlotResolver`, `ContainerCompatibilityConfig`, `FilterData` |
| `function/` | 移出所有复杂活物品 | `LivingFlintAndSteelFunction` |

---

## 3. 阶段 1：传输管道统一

### 3.1 目标

消除 `CrossContainerTransfer` 与 `LivingHopperFunction` 的双向耦合，统一传输入口。

### 3.2 产出

- **`TransferPipeline`**（`domain/hopper/TransferPipeline.java`）：统一传输入口，合并 `CrossContainerTransfer.execute` + `LivingHopperFunction.executeTransfer` 的逻辑
- `LivingHopperFunction.tick` 只构造传输请求 + 调用 `TransferPipeline.execute`
- `CrossContainerTransfer` 精简为跨容器方向解析和大箱子处理，传输逻辑委托给 `TransferPipeline`

### 3.3 架构变化

**重构前**：

```
LivingHopperFunction.executeTransfer()
  ├─ 越界 → CrossContainerTransfer.execute()
  │           ├─ pullFromNeighbor()        ← 600+ 行
  │           ├─ pushToNeighbor()
  │           └─ transferBetweenNeighbors()
  └─ 未越界 → SlotAccessor.transfer()
```

**重构后**：

```
LivingHopperFunction.tick()
  └─ TransferPipeline.execute(request)
      ├─ 越界 → CrossContainerTransfer.resolveDirection()  ← 只做方向解析
      │         + TransferPipeline.doTransfer()             ← 统一传输
      └─ 未越界 → TransferPipeline.doTransfer()             ← 同一入口
```

---

## 4. 阶段 2：活末影箱路由解耦

### 4.1 目标

`LivingEnderChestAccessor` 只做 SlotAccessor 该做的事，路由逻辑提取到 `EnderRouteManager`。

### 4.2 产出

- **`EnderRouteManager`**（`domain/ender/EnderRouteManager.java`）：集中管理路由注册、提取、验证
  - `registerRoute()` — 从 `LivingEnderChestAccessor` 迁移路由注册逻辑
  - `validateRoutes()` — 统一路由验证，替代旧版 5 个独立清理方法
  - 同通道防护、偏好类型设置、直连模式处理
- `LivingEnderChestAccessor` 精简为 ~150 行，只保留直连模式 + 路由模式的 extract/insert 委托
- 消除 `CrossContainerTransfer.pullFromNeighborToLivingEnderChest` 中的手动路由注册

### 4.3 架构变化

**重构前**：

```
LivingEnderChestAccessor (300+ 行)
  ├─ registerRoute()        ← 路由注册逻辑
  ├─ extract()              ← 路由提取 + 直连提取
  ├─ rollback()             ← 路由回滚 + 通知源方块实体
  ├─ notifySourceChanged()  ← 手动 setChanged()
  └─ ... 各种路由管理逻辑

CrossContainerTransfer
  └─ pullFromNeighborToLivingEnderChest()  ← 手动路由注册
```

**重构后**：

```
EnderRouteManager (集中管理)
  ├─ registerRoute()        ← 路由注册（从 Accessor 迁移）
  ├─ validateRoutes()       ← 统一验证
  └─ 同通道防护 / 偏好类型 / 直连模式

LivingEnderChestAccessor (精简 ~150 行)
  ├─ extract() → 委托 EnderRouteManager
  ├─ insert()  → 直连模式直接操作
  └─ rollback() → 委托 EnderRouteManager
```

---

## 5. 阶段 3：活熔炉同步生命周期统一

### 5.1 目标

活熔炉支持活箱子作为输入/输出，同步时机统一管理。

### 5.2 产出

- `LivingFurnaceFunction.executeTransform` 改用 `SlotAccessor`，支持活箱子作为输入/输出
- `TickContext` 添加脏槽位集合 `dirtySlots`，tick 结束后批量同步
- `SimpleContainerContext` 新增 `currentTickContext` 字段和 `flushDirtySlots()` 方法
- 消除散布各处的手动 `context.syncSlotToClients` 调用

### 5.3 脏槽位批量同步机制

**问题**：之前每次 `setItem` 后立即发送同步包，一个 tick 内可能发送大量冗余网络包。

**方案**：在 `TickContext` 中维护脏槽位集合，tick 结束时统一发送。

```java
// SimpleContainerContext.syncSlotToClients()
if (currentTickContext != null) {
    currentTickContext.dirtySlots.add(logicalSlot);  // 延迟：只标记脏
} else {
    flushSlotSync(logicalSlot, stack);               // 立即：无 TickContext 时直接发
}

// ContainerLivingItemHandler.tick() 末尾
simpleCtx.flushDirtySlots();   // 批量发送
simpleCtx.setTickContext(null);
```

**效果**：同一 tick 内同一槽位多次修改只发送一次同步包，减少网络冗余。

---

## 6. 补齐项

### 6.1 补齐项 A：HopperFilterBuilder 提取

**问题**：`ContainerSnapshot.buildAllFilters` 是 150+ 行的活漏斗专属过滤链构建逻辑，放在 `ContainerSnapshot`（容器基础设施）中违反内聚原则。

**产出**：`HopperFilterBuilder`（`domain/hopper/HopperFilterBuilder.java`）
- `buildAll()` — 容器级预计算入口
- `buildForSlot()` — 为单个活漏斗构建完整 FilterData
- `inheritFilter()` — 穿透递归，处理链上活漏斗的 source/target 方向
- `ContainerSnapshot` 精简：移除 `buildAllFilters`，委托给 `HopperFilterBuilder`

### 6.2 补齐项 B：LivingEnderChestAccessor 精简

**问题**：路由注册逻辑散布在 `LivingEnderChestAccessor` 和 `CrossContainerTransfer` 中。

**产出**：
- 路由注册逻辑迁入 `EnderRouteManager.registerRoute()`
- `LivingEnderChestAccessor` 精简为纯 SlotAccessor 职责
- `CrossContainerTransfer.pullFromNeighborToLivingEnderChest` 中的手动路由注册消除

### 6.3 补齐项 C：脏槽位批量同步

**问题**：每次 `setItem` 后立即发送同步包，网络冗余。

**产出**：
- `TickContext.dirtySlots` 字段
- `SimpleContainerContext.flushDirtySlots()` 方法
- `ContainerLivingItemHandler.tick()` 中集成 TickContext 生命周期
- 同一 tick 内同一槽位多次修改只发送一次同步包

---

## 7. 重构后文件结构

```
src/main/java/com/qiqi/li/living/
├── api/
│   ├── LivingItemFunction.java
│   └── LivingItemManager.java
│
├── container/
│   ├── ContainerContext.java
│   ├── SimpleContainerContext.java       ← 增强：TickContext 集成 + 脏槽位同步
│   ├── ContainerLivingItemHandler.java   ← 增强：TickContext 生命周期管理
│   ├── ContainerChunkCache.java
│   ├── ContainerSnapshot.java            ← 精简：过滤构建委托给 HopperFilterBuilder
│   ├── ContainerSync.java
│   ├── SlotInfoProvider.java
│   ├── ContainerIdentity.java
│   ├── LivingContainer.java
│   └── TickContext.java                  ← 增强：脏槽位集合
│
├── transfer/
│   ├── SlotAccessor.java
│   ├── PlainSlotAccessor.java
│   ├── FilteredSlotAccessor.java
│   ├── NeighborSlotAccessor.java
│   ├── SlotAccessorFactory.java
│   ├── SlotResolver.java
│   ├── ContainerCompatibilityConfig.java
│   └── FilterData.java                  ← 从 data/ 迁入（跨领域共享）
│
├── domain/
│   ├── hopper/
│   │   ├── LivingHopperFunction.java    ← 从 function/ 迁入
│   │   ├── TransferPipeline.java        ← 新：统一传输入口
│   │   ├── CrossContainerTransfer.java  ← 从 container/ 迁入，精简
│   │   ├── HopperFilterBuilder.java     ← 新：从 ContainerSnapshot 提取
│   │   ├── TransferStrategy.java        ← 从 transfer/ 迁入
│   │   ├── LivingHopperData.java        ← 从 data/ 迁入
│   │   ├── DirectionTransferData.java   ← 从 data/ 迁入
│   │   └── TransferData.java           ← 从 data/ 迁入
│   │
│   ├── chest/
│   │   ├── LivingChestFunction.java     ← 从 function/ 迁入
│   │   ├── LivingChestAccessor.java     ← 从 domain/ender/ 迁入
│   │   ├── LivingChestItemHandler.java  ← 从 domain/ender/ 迁入
│   │   └── LivingChestTooltipComponent.java
│   │
│   ├── ender/
│   │   ├── LivingEnderChestFunction.java ← 从 function/ 迁入
│   │   ├── LivingEnderChestAccessor.java ← 精简：路由逻辑迁入 EnderRouteManager
│   │   ├── LivingEnderChestItemHandler.java
│   │   ├── EnderRouteManager.java       ← 新：路由逻辑集中
│   │   ├── EnderChannelRegistry.java
│   │   ├── EnderChannelEntry.java
│   │   ├── EnderChannelClientCache.java
│   │   ├── EnderChannelData.java        ← 从 data/ 迁入
│   │   └── LivingEnderChestData.java    ← 从 data/ 迁入
│   │
│   ├── furnace/
│   │   ├── LivingFurnaceFunction.java   ← 从 function/ 迁入，改用 SlotAccessor
│   │   ├── LivingFurnaceData.java       ← 从 data/ 迁入
│   │   ├── ProgressData.java            ← 从 data/ 迁入
│   │   ├── FuelData.java               ← 从 data/ 迁入
│   │   ├── TransformData.java          ← 从 data/ 迁入
│   │   └── DirectionSlotsData.java     ← 从 data/ 迁入
│   │
│   ├── water/
│   │   ├── LivingWaterBucketFunction.java ← 从 function/ 迁入
│   │   ├── LivingWaterWheelFunction.java ← 从 function/ 迁入
│   │   ├── ContainerFluidData.java
│   │   ├── ContainerStressData.java
│   │   ├── LivingWaterBucketData.java   ← 从 data/ 迁入
│   │   ├── WaterData.java              ← 从 data/ 迁入
│   │   ├── LivingWaterWheelData.java   ← 从 data/ 迁入
│   │   └── WaterWheelData.java         ← 从 data/ 迁入
│   │
│   ├── map/
│   │   ├── LivingEnderPearlFunction.java
│   │   ├── ItemFrameMapTeleportHandler.java
│   │   ├── LivingMapClientCache.java
│   │   ├── LivingMapEventHandler.java
│   │   ├── MapCoordHelper.java
│   │   ├── MapTeleportExecutor.java
│   │   ├── StructureMapDecorator.java
│   │   └── TeleportHelper.java
│   │
│   └── tnt/
│       ├── LivingTntFunction.java       ← 从 function/ 迁入
│       ├── LivingTntData.java           ← 从 data/ 迁入
│       └── ExplosionData.java           ← 从 data/ 迁入
│
├── function/
│   └── LivingFlintAndSteelFunction.java ← 只留简单活物品
│
├── components/
│   ├── ExplosionComponent.java
│   └── ItemFilterComponent.java
│
├── compat/
│   ├── create/
│   │   ├── CreateCompat.java
│   │   ├── CreateIntegration.java
│   │   ├── CreateMixinPlugin.java
│   │   ├── LivingItemStressOutput.java
│   │   └── ModCreate.java
│   └── sable/
│       ├── ModSable.java
│       ├── SableCompat.java
│       └── SableIntegration.java
│
├── interaction/
│   ├── IgniteCarriedHandler.java
│   ├── IgniteHandler.java
│   ├── InteractionEntry.java
│   ├── InteractionHandler.java
│   └── InteractionRegistry.java
│
├── mixin/
│   ├── create/
│   │   └── KineticBlockEntityMixin.java
│   ├── AbstractContainerScreenMixin.java
│   ├── ItemStackMixin.java
│   └── ServerPlaceRecipeMixin.java
│
├── model/
│   ├── Pos2D.java
│   ├── ResolvedSlots.java
│   └── SlotMapping.java
│
└── perf/
    └── PerfMetrics.java
```

---

## 8. 未做的事

| 不做 | 原因 |
|------|------|
| 提前设计 `api/primitive/` | 没有第 2 个流体活物品，接口设计会偏离 |
| 把 `LivingItemFunction` 拆成多个小接口 | 当前契约已经足够简洁 |
| 泛型化 `ContainerFluidData` | 只有一种流体类型时泛型是过度设计 |
| 把 `domain/` 再拆成 `domain/core/` + `domain/impl/` | 每个领域模块已经内聚，不需要再分层 |
| 领域原语提取（`GridFlowSimulator`、`FluidBehavior`） | 等第 2 个流体活物品出现，从重复中提炼 |
| 活熔炉跨容器熔炼 | 活熔炉只管容器内，跨容器是活漏斗的职责 |