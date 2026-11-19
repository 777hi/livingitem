# Living Furnace (活熔炉) 技术文档

> **适用版本**: Minecraft 1.21.1

## 目录

- [§0 玩法定义](#0-玩法定义)
- [1. 架构概览](#1-架构概览)
  - [1.1 什么是活熔炉？](#11-什么是活熔炉)
  - [1.2 数据流总览图](#12-数据流总览图)
  - [1.3 关键类和职责](#13-关键类和职责)
  - [1.4 存储结构](#14-存储结构)
- [2. 核心流程](#2-核心流程)
  - [2.1 Tick 主循环](#21-tick-主循环)
  - [2.2 关键配置](#22-关键配置)
- [3. 熔炼流程](#3-熔炼流程)
  - [3.1 前置检查 `checkCanProgress()`](#31-前置检查-checkcanprogress)
  - [3.2 进度推进](#32-进度推进)
  - [3.3 暂停回退](#33-暂停回退)
- [4. 燃料机制](#4-燃料机制)
  - [4.1 燃料消耗](#41-燃料消耗)
  - [4.2 燃料值获取](#42-燃料值获取)
  - [4.3 堆叠加速消耗](#43-堆叠加速消耗)
- [5. 进度机制](#5-进度机制)
  - [5.1 进度完成判断](#51-进度完成判断)
  - [5.2 进度重置](#52-进度重置)
- [6. 物品转化](#6-物品转化)
  - [6.1 配方缓存 `tickTransform()`](#61-配方缓存-ticktransform)
  - [6.2 转化执行 `executeTransform()`](#62-转化执行-executetransform)
  - [6.3 输出空间计算](#63-输出空间计算)
- [7. 方向配置系统](#7-方向配置系统)
  - [7.1 DirectionSlotsData](#71-directionslotsdata)
  - [7.2 槽位布局示例](#72-槽位布局示例)
  - [7.3 槽位解析](#73-槽位解析)
- [8. 已知问题与修复记录](#8-已知问题与修复记录)
  - [8.3 配方缓存未命中导致重复查询](#83-配方缓存未命中导致重复查询)
  - [8.4 燃料消耗与进度不同步](#84-燃料消耗与进度不同步)
  - [8.5 配方缓存机制优化 (NEW 2026-07-27)](#85-配方缓存机制优化-new-2026-07-27)
  - [8.6 堆叠加速计算方式 (v4 2026-07-27, v5 收拢到 Data 类)](#86-堆叠加速计算方式-v4-2026-07-27-v5-收拢到-data-类)
  - [8.7 输出槽满时仍在消耗燃料 (NEW 2026-07-28)](#87-输出槽满时仍在消耗燃料-new-2026-07-28)
  - [8.8 数据类纯状态转换收拢 (NEW 2026-07-28)](#88-数据类纯状态转换收拢-new-2026-07-28)
  - [8.9 脏槽位批量同步 (2026-08-16)](#89-脏槽位批量同步-2026-08-16)
  - [8.10 岩浆桶整桶被吞 (FIXED 2026-09-07)](#810-岩浆桶整桶被吞-fixed-2026-09-07)
  - [8.11 熔炉图标不切换 active/idle (FIXED 2026-09-09)](#811-熔炉图标不切换-activeidle-fixed-2026-09-09)
- [9. 调试指南](#9-调试指南)
  - [9.1 查看状态](#91-查看状态)
  - [9.2 常见问题](#92-常见问题)
- [附录：v4 变更记录 (2026-07-28)](#附录v4-变更记录-2026-07-28)
- [附录：验证清单](#附录验证清单)
  - [基础熔炼](#基础熔炼)
  - [燃料系统](#燃料系统)
  - [配方匹配](#配方匹配)
  - [方向配置](#方向配置)
  - [Tooltip 与同步](#tooltip-与同步)
  - [数据持久化与运行时边界](#数据持久化与运行时边界)

---

## §0 玩法定义

> **实现入口**：`living/domain/furnace/` —— 关键类：`LivingFurnaceFunction`（功能入口）·
> `FurnaceRegistration`（域注册）· `FurnaceSegment`（runtime 遥测片段）

> **性质**：本节「规则」是**契约**（违反即 bug），不是实现细节 —— 它是本子系统
> **功能需求的来源**与**行为对错的判据**。⚠️ **只写规则与语义**；数值与操作步骤归实现层。

**语义**：**活熔炉 = 容器内的虚拟熔炉**。它把原版熔炉的熔炼功能**虚拟化**进容器槽位 ——
从输入槽取原料、烧燃料槽的燃料、把产物放进输出槽。

**获取**：原版熔炉 + 活化。

**核心循环**：`input` + `fuel` →（进度累积）→ `output`；槽位方向可配（见 §2）。

**规则**（违反即 bug）：

| # | 规则 | 为什么 / 后果 |
|---|---|---|
| 1 | **三槽语义**：input / fuel / output 各司其职 | 方向配置化，见 §2 |
| 2 | **活物品不作燃料** | 活物品是「设备」不是「燃料」（与活漏斗「不作货物」同族） |
| 3 | 燃料消耗**带残留物语义**（如岩浆桶 → 空桶） | 与原版一致 |
| 4 | **堆叠加速**：燃烧倍率随堆叠数 | 一堆 = 一台快炉 |
| 5 | 进度 **`progress ≥ total`** 即完成，完成后**重置 0** | — |
| 6 | **可暂停**：无法继续时回退进度 + 消耗余热 | 不「白烧」燃料 |
| 7 | 燃烧标志**仅网络同步、不落盘**（派生数据） | 稳态零写入（与活耕地「世界轴节拍」同族） |
| 8 | 运行时进度 / `burnTime` / 配方缓存只存 `ContainerRuntimeCache`，**不随熔炉物品搬运，不补偿余热** | DataComponent 不承载运行状态；不套用于活酿造台 |

**能力清单**：✅ 熔炼（配方缓存匹配）· ✅ 燃料（含残留物）· ✅ 进度 / 暂停 / 余热
· ✅ 物品转化 · ✅ 燃烧标志（图标 active/idle）· ✅ 堆叠加速

**验证入口**：

| 规则 / 能力 | 守卫 |
|---|---|
| 2 活物品不作燃料 · 3 残留物 · 4 堆叠加速 · 5 进度 | `LivingFurnaceFunctionTest` |
| 7 燃烧标志（派生 / 不落盘） | `FurnaceBurningFlagTest` |
| 8 运行状态不随物品搬运 / 不补偿余热 | `LivingFurnaceFunction.tick()` · `ContainerRuntimeCache` · 本文附录「数据持久化与运行时边界」 |

---

## 1. 架构概览

### 1.1 什么是活熔炉？

活熔炉是一种特殊的活物品（Living Item），它将普通熔炉的熔炼功能**虚拟化**到容器内。活熔炉放置在容器（如箱子）的某个槽位中，从输入槽位取原料，消耗燃料槽位的燃料，将产物放入输出槽位，实现容器内的自动化熔炼。

活熔炉的宿主物品是 `minecraft:furnace`（熔炉），必须同时具备活物品标记。

### 1.2 数据流总览图

```
┌──────────────────────────────────────────────────────────────────┐
│                    LivingFurnaceFunction                         │
│                    (功能入口 · 实现 LivingItemFunction)           │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  tick() 流程:                                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ 1. 读取方向配置 → 解析 input/fuel/output 槽位              │   │
│  │ 2. checkCanProgress() → 检查燃料+输入+配方                │   │
│  │ 3. tickTransform() → 配方缓存匹配                         │   │
│  │ 4. canProgress?                                           │   │
│  │    ├─ YES → tickProgress() + tickFuel()                   │   │
│  │    │       └─ progress完成? → executeTransform()          │   │
│  │    └─ NO  → pauseTick() (回退进度 + 消耗余热)             │   │
│  │ 5. 写运行时缓存；方向 / 燃烧标志变化时同步组件               │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  运行状态由 ContainerRuntimeCache 按容器 / 槽位管理；不随物品搬运 │
│  ┌─────────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐  │
│  │ FuelData     │  │ProgressData│ │TransformData│ │DirectionSlots│ │
│  │ burnTime     │  │ progress  │  │ 配方缓存   │  │ directions  │  │
│  │ runtime-only │  │ runtime-only│ │ runtime-only│ │ 物品组件    │  │
│  └─────────────┘  └──────────┘  └──────────┘  └─────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────┐           │
│  │                 SlotResolver                      │           │
│  │                 (槽位解析器)                       │           │
│  │                                                   │           │
│  │  • 相对方向 → 绝对槽位索引                         │           │
│  │  • 9列网格布局计算                                │           │
│  └──────────────────────────────────────────────────┘           │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingFurnaceFunction` | `domain/furnace/LivingFurnaceFunction.java` | 活熔炉功能入口，实现 `LivingItemFunction` 接口，所有熔炼逻辑集中在此 |
| `LivingFurnaceData` | `domain/furnace/LivingFurnaceData.java` | 运算用 record；物品组件保留方向配置，fuel/progress/transform 字段不保存运行时快照 |
| `FuelData` | `domain/furnace/FuelData.java` | 燃料状态 record：运行时 burnTime |
| `ProgressData` | `domain/furnace/ProgressData.java` | 进度状态 record：progress + total，运行时进度不随物品搬运 |
| `TransformData` | `domain/furnace/TransformData.java` | 转化状态 record：运行时配方缓存、输入/输出物品 ID |
| `ContainerRuntimeCache` / `FurnaceSegment` | `runtime/` / `domain/furnace/` | 按容器 / 物理槽保存与回读 FurnaceRuntime，不落盘、不随熔炉物品搬运 |
| `DirectionSlotsData` | `domain/furnace/DirectionSlotsData.java` | 方向配置 record：input/fuel/output 的 Pos2D 偏移 |
| `SlotResolver` | `transfer/SlotResolver.java` | 相对方向→绝对槽位索引的数学计算 |

### 1.4 存储结构

活熔炉的**方向配置**由 ItemStack 的 `LivingFurnaceData` DataComponent 保存；
运行时进度、`burnTime` 与配方缓存由 `ContainerRuntimeCache` 保存，不是物品上的持久化快照。

```
ItemStack
├── IS_LIVING: true
├── LIVING_FURNACE_BURNING: Boolean       ← 派生图标标志，仅同步、不落盘
└── LIVING_FURNACE_DATA: LivingFurnaceData
    ├─ direction: DirectionSlotsData     ← 持久配置：input / fuel / output
    ├─ fuel: FuelData                    ← 组件字段保留默认值，不保存运行时 burnTime
    ├─ progress: ProgressData            ← 组件字段保留默认值，不保存运行时进度
    └─ transform: TransformData          ← 组件字段保留默认值，不保存运行时配方缓存

ContainerRuntimeCache（容器缓存键 + 物理槽）
└── FurnaceRuntime
    ├─ progress / total                  ← 运行时进度 / 遥测
    ├─ burnTime                          ← 运行时剩余燃烧时间
    └─ transform: TransformData          ← 运行时输入 / 输出及配方缓存
```

| 数据 | 存储 / 约束 |
|---|---|
| 方向配置 | DataComponent 持久化，随物品搬运 |
| 运行时进度 | `ContainerRuntimeCache`，不落盘、不随熔炉物品搬运 |
| `burnTime` / 燃料余热 | `ContainerRuntimeCache`，不随熔炉物品搬运，已消耗的燃料不补偿 |
| 配方缓存 | `ContainerRuntimeCache`，不随熔炉物品搬运；新运行上下文重新匹配 |
| 燃烧标志 | 派生布尔组件，仅供图标同步，不承载 `burnTime` 或余热 |

> **历史（v4）**：存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingFurnaceData`），利用 Minecraft 内置的序列化和同步机制。
>
> **运行时缓存分工**（b064865）：progress/fuel/transform 的每 tick 变化只写服务端
> `ContainerRuntimeCache` 并经 `LivingItemSyncPacket` 下发给 tooltip，**不写 DataComponent**
> （瞬态数据不影响物品堆叠）；`LIVING_FURNACE_BURNING` 是唯一例外——图标谓词在渲染线程
> 只能读 ItemStack，故燃烧状态翻转时写这个轻量布尔组件（§8.11）。
>
> **运行时边界**：缓存按容器 / 槽位回读上一 tick 的状态，不提供随熔炉物品迁移的状态。
> 搬走熔炉不保存进度、剩余燃烧时间或配方缓存，不返还已耗燃料、不补偿余热。
>
> ⚠️ **适用范围（2026-10-28，`D-container-19`）**：上述「不随物品搬运」指**容器身份变化**
> （熔炉方块被拆成物品、再放到新位置 = 新容器）。**物品形态容器（活背包）换宿主不适用**：
> 背包是内容物的主容器宿主，`cuuid` 不变 ⇒ 进度随背包保留并继续（放箱子里也照常 tick）。
> 两条并存：方块容器看「身份是否变了」，物品形态看「背包是否还在」。
> **本口径仅适用于活熔炉**；活酿造台燃料由自身组件保存，不按本表统一。

---

## 2. 核心流程

### 2.1 Tick 主循环

仅服务端执行，逐槽处理（流程总览见 §1.2）。实现以
`LivingFurnaceFunction.tick()` 源码为准，每槽顺序：

1. 从 `ContainerRuntimeCache` 回读瞬态 progress / fuel / transform（缓存键带维度前缀，
   裸容器键会让主世界/下界同坐标箱子串燃料）
2. 方向配置为空时回填 `DEFAULT_DIRECTION`；`SlotResolver` 解析 input / fuel / output 槽位
3. `tickTransform()` 更新配方缓存 → `checkCanProgress()` 前置检查（§3.1）
4. 可推进：进度 `advanceBy(1 + count/8)` + `tickFuel()`；进度完成且在燃烧 →
   `executeTransform()`，成功才 `reset()`。不可推进：`pauseTick()` 回退进度 + 消耗余热
5. 写运行时缓存（tooltip 数据源，**不写 DataComponent**）
6. **仅变化时写组件**：燃烧状态翻转 → 写 `LIVING_FURNACE_BURNING` + 同步（稳态零写入，
   标志过期自愈）；方向变化 → 写方向组件 + 同步

### 2.2 关键配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `DEFAULT_COOKING_TIME` | 200 | 完成一次熔炼所需的总 ticks（10 秒） |
| 堆叠加速 | stackCount / 8 + 1 | 进度推进倍率 |
| 燃料加速 | stackCount | 燃料消耗倍率（堆叠数越多烧得越快） |

---

## 3. 熔炼流程

### 3.1 前置检查 `checkCanProgress()`

```
checkCanProgress(ctx, level, inputSlot, fuelSlot, outputSlot, data)
  ├─ inputSlot < 0 或 outputSlot < 0 → return false
  ├─ 输入槽为空 或 是活物品 → return false
  ├─ 燃料检查（仅在未燃烧时）
  │   ├─ fuelSlot 无效 / 燃料槽为空 → return false
  │   └─ 燃料值 <= 0 或 燃料是活物品 → return false
  ├─ hasMatchingRecipe() → 无配方 return false
  └─ 输出槽预检（缓存产物非空且输出槽非空时）
      ├─ 物品 ID 与缓存产物不同 → return false
      └─ 组件级空间判断：取真实配方产物探针，calculateOutputSpace <= 0 → return false
```

⚠️ **输出预检必须组件级**（2026-10-27 审查 P1-2）：曾只比物品 ID ⇒ 输出槽放「同物品不同组件」
（改名铁锭 / 活铁锭）时预检放行、完成写不进 ⇒ 进度卡 `isComplete` 不复位、燃料无限烧、零产出。
探针与写入判据（`isSameItemSameComponents`）同源；代价是产出槽非空期间每 tick 一次配方查询。

### 3.2 进度推进

分工：**Function 负责游戏规则倍率**（`step = 1 + stackCount / 8`），
**`ProgressData.advanceBy(amount)` 负责纯数学推进**（`min(total, progress + amount)`，
不关心游戏规则）。倍率见 §2.2，实现见源码。

| 堆叠数 | 倍率 | 完成时间 |
|--------|------|---------|
| 1 | 1x | 200 ticks (10s) |
| 8 | 2x | 100 ticks (5s) |
| 16 | 3x | 67 ticks (3.3s) |
| 32 | 5x | 40 ticks (2s) |
| 56 | 8x | 25 ticks (1.25s) |

### 3.3 暂停回退

当活熔炉无法继续处理时（无输入/无燃料/输出满），`pauseTick()` 调用
`ProgressData.recede()`（回退 1）和 `FuelData.tick(1)`（消耗 1 余热）：

| 状态 | 行为 |
|------|------|
| 进度 > 0 | progress -= 1（模拟余热消散） |
| 燃料在燃烧 | burnTime -= 1（模拟余热消耗） |

---

## 4. 燃料机制

### 4.1 燃料消耗

`tickFuel()`（实现见源码）：

- **正在燃烧** → `FuelData.tick(max(1, stackCount))`，消耗倍率 = 堆叠数（§4.3）
- **未燃烧** → 燃料槽有效、燃料值 > 0 且非活物品时，先 `consumeFuel()` 扣燃料，
  **扣成功才点燃**（`new FuelData(fuelValue)`）；一件没扣到（容器违约）本拍不点燃

**合成残留物**（对齐原版 `AbstractFurnaceBlockEntity#serverTick`）：点燃燃料时若燃料带
crafting remainder，**燃料槽整体替换为残留物**（岩浆桶 → 空桶）；普通燃料只扣 1 个、
耗尽后清空槽位。

`consumeFuel()` 走容器数量原语，返回 boolean（false = 没扣到，调用方不得点燃）：

- 带残留物 → `ctx.transform(fuelSlot, ItemStack::getCraftingRemainingItem)`：同槽 1:1 替换；无精确写回面或拒写不点燃，不通过抽插回退绕过限制（共同契约见 [API §1.7](../system-design/api-contract.md#17-槽位数量操作原语2026-11-05-生效)）
  带回读。**不走 `produce` 的准入**——燃料槽在原版语义下拒收空桶
- 普通燃料 → `ctx.consume(fuelSlot, 1) > 0`：抽取权限 + 写后回读，扣不到返回 0

### 4.2 燃料值获取

通过 NeoForge 的 `IItemExtension.getBurnTime(stack, recipeType)` 接口查询，支持原版和模组的燃料物品。

```
原版燃料值示例：
  煤炭/木炭 → 1600 ticks (80秒)
  木板 → 300 ticks (15秒)
  木棍 → 100 ticks (5秒)
  熔岩桶 → 20000 ticks (1000秒)
```

### 4.3 堆叠加速消耗

燃料消耗速度与活熔炉堆叠数成正比：

| 堆叠数 | 消耗倍率 | 煤炭燃烧时间 |
|--------|---------|-------------|
| 1 | 1x | 1600 ticks |
| 8 | 8x | 200 ticks |
| 16 | 16x | 100 ticks |

---

## 5. 进度机制

### 5.1 进度完成判断

完成条件：`progress >= total && total > 0`（`ProgressData.isComplete()`，默认 total = 200）。

### 5.2 进度重置

转化成功后 `ProgressData.reset()` 进度归零（total 保留）。

---

## 6. 物品转化

### 6.1 配方缓存 `tickTransform()`

为避免每 tick 查询配方管理器，活熔炉使用缓存机制（优化背景见 §8.5 历史记录）。
现行规则（实现见 `LivingFurnaceFunction.tickTransform()`）：

- 输入槽无效 / 为空 / 是活物品 → 缓存清空（`TransformData.EMPTY`）
- **相同输入且 `cachedResult ∈ {0, 1}` → 命中，不查配方**。⚠️ **「无配方」也缓存**
  （2026-10-27 审查 P2-3：曾只认 1 ⇒ 无配方物品每 tick 重查；曾要求 inputItem 非空 ⇒
  全新熔炉塞泥土永远写不进缓存）
- 未命中 → 查询 `RecipeManager`，写 `withInputItem` / `withOutputItem` / `withCache`
  （有配方 = 1，无配方 = 0）

### 6.2 转化执行 `executeTransform()`

```
executeTransform(ctx, level, data, inputSlot, outputSlot, stackCount)
  ├─ 输入/输出槽位有效？→ 否 → return false
  ├─ 输入是活物品？→ return false
  ├─ RecipeManager 查询配方（取真实产物，不只读缓存）
  ├─ 无配方 → return false
  ├─ 计算转化数量
  │   ├─ outputSpace = calculateOutputSpace(输出槽, 产物)
  │   ├─ maxByOutput = outputSpace / resultCount
  │   └─ transformCount = min(stackCount, inputCount, maxByOutput)
  ├─ transformCount <= 0 → return false
  ├─ consume 先行：ctx.consume(inputSlot, transformCount)，扣减经回读核验
  │   └─ consumed <= 0 → return false
  └─ 按实际扣减量生成：ctx.produce(outputSlot, result, consumed × resultCount)（long 运算防回绕）
```

⚠️ **consume 先行**（I-C13 分批加工）：只扣实际处理量，产物按实际扣减量放置。
若 `produce` 少交付（容器 simulate 撒谎），损失方向是「少产出」且原语内部已 WARN——
绝不反向多产出。**丢 ≤ 复制**，所以不先写产物再扣输入。

### 6.3 输出空间计算

**容量问容器**（I-C14 ①）：`calculateOutputSpace` 直接委托
`ctx.insertableSpace(outputSlot, result)`——超堆叠槽的产出批量不再被物品上限钳住。
不自算 `min(slotLimit, maxStackSize)`；选型见 [槽位操作选型](../guides/slot-ops.md)。

---

## 7. 方向配置系统

### 7.1 DirectionSlotsData

活熔炉使用 `DirectionSlotsData` 管理三个功能槽位的方向：

```java
public static final DirectionSlotsData DEFAULT_DIRECTION = DirectionSlotsData.DEFAULT_FURNACE;
// 默认方向：input=LEFT, fuel=DOWN, output=RIGHT
```

### 7.2 槽位布局示例

在 9 列箱子中，默认方向：
```
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │   │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│原料│   │   │熔炉│   │   │产物│   │   │
│(input)│   │(slot)│   │(output)│   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │燃料│   │   │   │   │   │   │
│   │   │(fuel)│   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘
```

### 7.3 槽位解析

`SlotResolver.resolve()` 将相对方向转换为绝对槽位索引：

```java
int inputSlot = SlotResolver.resolve(slot, dir.getDirection("input"), containerSize, containerWidth);
int fuelSlot = SlotResolver.resolve(slot, dir.getDirection("fuel"), containerSize, containerWidth);
int outputSlot = SlotResolver.resolve(slot, dir.getDirection("output"), containerSize, containerWidth);
```

---

## 8. 已知问题与修复记录

> **历史修复记录（冻结）**：本节保留各次修复时的表述；当前运行状态存储与搬运约束以 §1.4 为准。

### 8.3 配方缓存未命中导致重复查询

**问题**：输入物品变化时，缓存未及时更新，导致每 tick 都查询配方管理器。

**修复**：在 `tickTransform()` 中检测输入物品变化时立即更新缓存。

### 8.4 燃料消耗与进度不同步

**问题**：燃料耗尽但进度未回退，导致下次补充燃料后立即完成熔炼（进度卡在 99%）。

**修复**：在 `pauseTick()` 中同时回退进度和消耗燃料余热。

### 8.5 配方缓存机制优化 (NEW 2026-07-27)

**背景**：原版 `RecipeManager.getRecipeFor()` 查询开销较大，每 tick 查询会影响性能。

**优化方案**：引入 `TransformData` 缓存机制：

```java
// 缓存命中：输入物品没变，直接使用缓存结果
if (inputKey.equals(transform.cachedInput()) && transform.cachedResult() == 1) {
    return data;  // 跳过配方查询
}

// 缓存未命中 → 查询配方 → 更新缓存
var recipeHolderOpt = level.getRecipeManager()
    .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(inputStack), level);
if (recipeHolderOpt.isPresent()) {
    transform = transform.withCache(inputKey, 1, outputKey, resultCount, cookingTime);
} else {
    transform = transform.withCache(inputKey, 0, "", 0, 0);  // 缓存"无配方"
}
```

**缓存字段**：
- `cachedInput`：缓存的输入物品 ID
- `cachedResult`：是否有配方（1=有，0=无）
- `cachedOutput`：缓存的输出物品 ID
- `cachedOutputCount`：缓存的输出数量
- `cachedCookingTime`：缓存的熔炼时间

**效果**：相同输入物品只查询一次配方，后续 tick 直接使用缓存，大幅降低 CPU 开销。

### 8.6 堆叠加速计算方式 (v4 2026-07-27, v5 收拢到 Data 类)

活熔炉的堆叠数量影响熔炼速度，计算逻辑拆分：Data 类负责纯数学转换，Function 负责游戏规则倍率。

**进度推进**（Function 计算倍率 → `ProgressData.advanceBy(step)`）：
```java
int step = 1 + stackCount / 8;
data = data.withProgress(data.progress().advanceBy(step));
// 堆叠 1-7 → 1x 速度
// 堆叠 8-15 → 2x 速度
// ...
```

**燃料消耗**（Function 计算倍率 → `FuelData.tick(count)`）：
```java
data = data.withFuel(fuel.tick(Math.max(1, stackCount)));
// 堆叠 1 → 1x 消耗
// 堆叠 8 → 8x 消耗
// ...
```

**设计意图**：堆叠越多 → 熔炼越快，但燃料消耗也越快，形成平衡。

### 8.7 输出槽满时仍在消耗燃料 (NEW 2026-07-28)

**问题**：`checkCanProgress()` 只检查了输入槽、燃料、配方，未检查输出槽是否可用。当输出槽已满或被不同物品占据时，`canProgress` 仍返回 `true`，每 tick 消耗燃料但 `executeTransform()` 始终失败。

**修复**：
1. 调换 `tickTransform()` 顺序（移到 `checkCanProgress()` 之前），确保配方缓存可用
2. 在 `checkCanProgress()` 末尾增加输出槽检查：有不同类型物品 → 返回 false；已满（达堆叠上限）→ 返回 false

### 8.8 数据类纯状态转换收拢 (NEW 2026-07-28)

**背景**：遵循"配置数据归物品，衍生数据归容器"原则，将纯状态转换逻辑从 `LivingFurnaceFunction` 收拢到各 data 类。Data 类只负责"数据怎么变"，Function 负责"游戏规则"（如堆叠加速倍率）。

**新增方法**：

| Data 类 | 方法 | 说明 |
|---------|------|------|
| `ProgressData` | `advanceBy(int amount)` | 纯数学推进，`min(total, progress + amount)` |
| `ProgressData` | `recedeBy(int amount)` | 纯数学回退，`max(0, progress - amount)` |
| `ProgressData` | `recede()` | 便捷方法，等价于 `recedeBy(1)` |
| `FuelData` | `tick(int count)` | 已有，`max(0, burnTime - count)` |
| `TransformData` | `canAcceptOutput(itemId, count, slotLimit, maxStackSize)` | 检查输出槽是否可接受缓存产物 |

**游戏规则仍在 Function 中**：
```java
// Function 决定"推进多少"（堆叠加速规则）
int step = 1 + stackCount / 8;
data = data.withProgress(data.progress().advanceBy(step));

// Function 决定"消耗多少"（堆叠加速规则）
data = data.withFuel(fuel.tick(Math.max(1, stackCount)));
```

**效果**：`ProgressData` 和 `FuelData` 可被任何活物品以任意规则复用，不绑定"堆叠加速"这一特定游戏机制。

### 8.9 脏槽位批量同步 (2026-08-16)

**背景**：旧文档曾把「改用 SlotAccessor」的设想写成现状，2026-10-27 已纠正。
2026-11-05 数量操作收编后，熔炉的输入消耗 / 输出生成走 `ctx.consume()` / `ctx.produce()`
（`executeTransform`），不在领域内直接调用 `setItem`，也不走传输专用的 SlotAccessor。

**实际机制**：

1. **数量操作与写回**：`executeTransform` 先 `consume` 输入，再按实际扣减量 `produce` 产物。
   槽位容量、写后核验及脏槽标记由容器数量原语承担，契约见
   [槽位操作选型](../guides/slot-ops.md)。

2. **脏槽位批量同步**：`SimpleContainerContext.syncSlotToClients()` 在 tick 内只标记脏槽位到 `TickContext.dirtySlots`，tick 结束时由 `ContainerLivingItemHandler` 统一调用 `flushDirtySlots()` 批量发送同步包。同一 tick 内同一槽位多次修改只发送一次同步包。

同步生命周期由 `ContainerLivingItemHandler.processContext` 统一管理：关联 TickContext、运行功能、
最终 flush 脏槽并解除关联。不要在各域另设同步队列；实现以该共同入口为准。

**效果**：
- 同一 tick 同一槽位多次修改只发一次同步包
- 同步时机统一，不再有遗漏 `syncSlotToClients` 的风险
- 活箱子作为熔炉的输入/输出，由物品自身作为容器被 tick（`ContainerLivingItemHandler` 统一处理），不需要熔炉侧走 SlotAccessor

### 8.10 岩浆桶整桶被吞 (FIXED 2026-09-07)

**问题**：岩浆桶作为燃料时被整个消耗，没有像原版一样留下空桶。

**原因**：`tickFuel()` 消耗燃料只做了 `shrink(1)`，没有检查燃料的 crafting remainder。
岩浆桶注册时带 `craftRemainder(BUCKET)`，原版熔炉在点燃瞬间把整个燃料槽替换为残留物
（`AbstractFurnaceBlockEntity#serverTick`），活熔炉漏掉了这一步。

**修复**：燃料消耗收拢到 `consumeFuel()`：带残留物的燃料整槽替换为残留物（岩浆桶 → 空桶），
普通燃料扣 1 个、耗尽清空。回归测试 `LivingFurnaceFunctionTest`（4 项）。

### 8.11 熔炉图标不切换 active/idle (FIXED 2026-09-09)

**问题**：活熔炉熔炼时，物品图标始终显示 `furnace_idle.png`，不会切到 `furnace_active.png`。

**原因**：图标谓词 `LivingFurnaceFunction.isBurning(stack)` 读物品的 DataComponent，
而「tooltip优化，nbt数据简化」（b064865，2026-09-03）把 burnTime 从 DataComponent
迁到了服务端运行时缓存（`ContainerRuntimeCache` + `LivingItemSyncPacket`）——该链路的
唯一消费方是 tooltip（悬停时经 `LivingItemClientCache` 合并显示），客户端 ItemStack 上的
`LIVING_FURNACE_DATA` 燃料恒为默认值 → `isBurning()` 恒 false → 图标永远 idle。
（旁证：活TNT 图标正常，因为 TNT 数据未迁运行时缓存，仍每 tick 写组件+同步。）

**修复**（轻量标志组件，不动运行时缓存架构）：
- 新增 Boolean 组件 `LIVING_FURNACE_BURNING`（`LivingItemManager` 注册 +
  `isFurnaceBurning/setFurnaceBurning` 访问器，熄灭即移除组件）
- `tick()` 在燃烧状态**翻转时**（点燃/熄灭，约每次熔炼才变一次，非每 tick）写标志并
  `syncSlotToClients`；稳态不写零同步；标志与实际不符时自愈（跨容器搬运后旧标志过期，
  下一 tick 修正）
- `isBurning()` 改读标志组件；组件加入 `getIgnoredComponentTypes()`（第一个真实使用者，
  `ItemStackMixin` 堆叠比较忽略之——燃烧中/熄灭的熔炉仍可堆叠）

回归测试 `FurnaceBurningFlagTest`（5 项：点燃写入/熄灭移除/稳态零同步/自愈/堆叠兼容）。

---

## 9. 调试指南

### 9.1 查看状态

Tooltip 中显示：
- 燃料状态：燃烧中/剩余时间
- 进度状态：百分比 + 时间
- 转化状态：输入物品 → 输出物品

### 9.2 常见问题

| 问题 | 可能原因 | 排查方法 |
|------|---------|---------|
| 不熔炼 | 输入方向错误 | 检查 Tooltip 中的方向配置 |
| 不消耗燃料 | 燃料槽位方向错误 | 检查燃料槽位中是否有燃料物品 |
| 产物不生成 | 输出槽已满 | 检查输出槽位空间 |
| 速度慢 | 堆叠数不足 | 增加活熔炉堆叠数以加速 |

---

## 附录：v4 变更记录 (2026-07-28)

> **历史记录（冻结）**：本节保留当时表述，不作为当前运行状态持久化口径；现状见 §1.4。

**变更1：DataComponent 迁移**

存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingFurnaceData`），利用 Minecraft 内置的序列化和同步机制，无需手动管理 NBT 读写。

**变更2：Tooltip 国际化**

Tooltip 显示从硬编码字符串改为使用 `Component.translatable()` 国际化键，支持多语言。方向配置、燃料状态、进度、转化信息均使用翻译键。

**变更3：配方缓存字段扩展**

`TransformData` 新增 `cachedOutputCount` 和 `cachedCookingTime` 字段，缓存完整的配方结果信息，减少运行时查询。

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础熔炼

- [ ] 输入物品放入后自动开始熔炼
- [ ] 熔炼进度每 tick 递增，达到 cookingTime 后产出成品
- [ ] 堆叠数影响熔炼速度：堆叠越多越快
- [ ] 无输入物品时不熔炼
- [ ] 输出槽已有不同类型物品时不熔炼
- [ ] 输出槽已满（堆叠达上限）时不熔炼

### 燃料系统

- [ ] 燃料物品放入燃料槽后自动燃烧
- [ ] 燃烧时间与原版燃料一致
- [ ] 燃料耗尽且无新燃料时停止推进，按 `pauseTick()` 回退进度，不承诺保留
- [ ] 无燃料时不熔炼
- [ ] 多个燃料物品顺序燃烧

### 配方匹配

- [ ] 正确匹配原版熔炼配方
- [ ] 无匹配配方时不熔炼
- [ ] TransformData 缓存配方结果（outputItem、cachedOutputCount、cachedCookingTime）
- [ ] 输入物品变化时重新查询配方，更新缓存

### 方向配置

- [ ] 输入/燃料/输出方向正确配置
- [ ] 活漏斗能向正确方向插入输入/燃料物品
- [ ] 活漏斗能从输出方向提取成品

### Tooltip 与同步

- [ ] Tooltip 显示燃料状态（燃烧中/剩余时间）
- [ ] Tooltip 显示进度（百分比 + 时间）
- [ ] Tooltip 显示转化信息（输入→输出）
- [ ] Tooltip 国际化（Component.translatable）
- [ ] 方向配置在 Tooltip 中显示

### 数据持久化与运行时边界

- [ ] 方向配置通过 LivingFurnaceData DataComponent 持久化；组件不保存运行时进度 / burnTime / 配方缓存
- [ ] 运行时进度、burnTime、TransformData 配方缓存只由 ContainerRuntimeCache 按容器 / 槽位回读
- [ ] 熔炉物品跨槽位或跨容器搬运不携带运行时进度、剩余燃烧时间或配方缓存
- [ ] 搬运不返还已消耗燃料、不补偿余热；新运行上下文重新匹配配方
- [ ] 燃烧标志不承载余热，下一次 tick 按实际运行状态修正
- [ ] 活酿造台的燃料持久化仍遵循其自身文档，不按活熔炉口径统一