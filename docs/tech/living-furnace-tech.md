# Living Furnace (活熔炉) 技术文档

> **文档版本**: 2026.07 v1  
> **最后更新**: 2026-07-22  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [核心组件详解](#2-核心组件详解)
3. [熔炼流程](#3-熔炼流程)
4. [燃料机制](#4-燃料机制)
5. [进度机制](#5-进度机制)
6. [物品转化](#6-物品转化)
7. [方向配置系统](#7-方向配置系统)
8. [已知问题与修复记录](#8-已知问题与修复记录)
9. [调试指南](#9-调试指南)

---

## 1. 架构概览

### 1.1 什么是活熔炉？

活熔炉是一种特殊的活物品（Living Item），它将普通熔炉的熔炼功能**虚拟化**到容器内。活熔炉放置在容器（如箱子）的某个槽位中，从输入槽位取原料，消耗燃料槽位的燃料，将产物放入输出槽位，实现容器内的自动化熔炼。

活熔炉的宿主物品是 `minecraft:furnace`（熔炉），必须同时具备活物品标记。

### 1.2 组件架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                    LivingFurnaceFunction                         │
│                    (功能入口 + 组件注册)                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────┐  ┌──────────────────────┐             │
│  │ DirectionModeComponent│  │ FuelConsumeComponent │             │
│  │ (方向配置)            │  │ (燃料管理)            │             │
│  │                      │  │                      │             │
│  │ • SLOTS 模式         │  │ • 燃烧时间管理        │             │
│  │ • WASD 输入解析      │  │ • 燃料消耗            │             │
│  │ • input/fuel/output  │  │ • isBurning() 检查    │             │
│  └──────────┬───────────┘  └──────────┬───────────┘             │
│             │                         │                          │
│  ┌──────────┴─────────────────────────┴───────────┐             │
│  │              FuelProgressOrchestrator            │             │
│  │              (编排器)                            │             │
│  │                                                 │             │
│  │  • checkCanProgress() — 燃料+输入有效性检查     │             │
│  │  • tickAllComponents() / pauseTickComponents()  │             │
│  │  • handleCompletion() — 进度完成时执行转化       │             │
│  └──┬──────────────┬──────────────┬────────────────┘             │
│     │              │              │                               │
│  ┌──┴──────────┐ ┌─┴───────────┐ ┌┴──────────────────┐          │
│  │ProgressComp │ │FuelConsume  │ │ItemTransform      │          │
│  │ (进度)      │ │ (燃料)      │ │ (转化)            │          │
│  │             │ │             │ │                   │          │
│  │ • tick      │ │ • tick      │ │ • canProcess()    │          │
│  │ • pauseTick │ │ • pauseTick │ │ • executeTransform│          │
│  │ • isComplete│ │ • isBurning │ │ • 配方匹配        │          │
│  │ • reset     │ │ • hasUsable │ │ • 输出空间计算    │          │
│  └─────────────┘ └─────────────┘ └───────────────────┘          │
│                                                                  │
│  ┌──────────────────────────────────────────────────┐            │
│  │                 SlotResolver                      │            │
│  │                 (槽位解析器)                       │            │
│  │                                                   │            │
│  │  • 相对方向 → 绝对槽位索引                         │            │
│  │  • 9列网格布局计算                                │            │
│  └──────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingFurnaceFunction` | `function/LivingFurnaceFunction.java` | 活熔炉功能入口，注册组件，配置参数 |
| `FuelProgressOrchestrator` | `core/orchestrator/FuelProgressOrchestrator.java` | 编排器：协调燃料、进度、转化三个组件 |
| `FuelConsumeComponent` | `core/components/FuelConsumeComponent.java` | 燃料管理：燃烧时间维护、燃料消耗 |
| `ProgressComponent` | `core/components/ProgressComponent.java` | 进度管理：推进/回退/完成判断 |
| `ItemTransformComponent` | `core/components/ItemTransformComponent.java` | 物品转化：配方匹配、输入消耗、产物生成 |
| `DirectionModeComponent` | `core/components/DirectionModeComponent.java` | 方向配置：SLOTS 模式，input/fuel/output 槽位 |
| `SlotResolver` | `core/SlotResolver.java` | 相对方向→绝对槽位索引的数学计算 |

---

## 2. 核心组件详解

### 2.1 LivingFurnaceFunction — 功能入口

活熔炉的功能配置类，继承 `BaseLivingFunction`。负责注册组件、配置参数。

**组件注册顺序**：
```java
.addComponent(new DirectionModeComponent(Map.of(
    "input", Pos2D.LEFT,
    "fuel", Pos2D.DOWN,
    "output", Pos2D.RIGHT
)))
.addComponent(FuelConsumeComponent.class,
    ComponentConfig.of("recipe_type", RecipeType.SMELTING))
.addComponent(ProgressComponent.class,
    ComponentConfig.of("total_ticks", 200))
.addComponent(ItemTransformComponent.class,
    ComponentConfig.of("recipe_type", RecipeType.SMELTING))
```

**关键配置**：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `total_ticks` | 200 | 完成一次熔炼所需的总 ticks（10 秒） |
| `stackMultiplier` | true | 堆叠加速（更多熔炉=更快） |
| `orchestrator` | FUEL_PROGRESS | 编排策略 |

**槽位布局示例**（在 9 列箱子中，默认方向）：
```
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │   │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│原料│   │   │熔炉│   │   │产物│   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │燃料│   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘
```

### 2.2 FuelConsumeComponent — 燃料管理

管理活熔炉的燃料消耗和燃烧时间。

**NBT 存储**：
| 键名 | 类型 | 说明 |
|------|------|------|
| `burn_time` | int | 剩余燃烧时间（ticks） |

**tick() 执行流程**：
```
tick()
  ├─ 有燃烧时间？
  │   └─ burn_time -= multiplier（堆叠加速消耗）
  │       └─ return
  └─ 燃烧时间耗尽
      ├─ 燃料槽位有效？
      ├─ 燃料物品有燃料值？（getBurnTime > 0）
      ├─ 燃料不是活物品？
      └─ 消耗 1 个燃料 → 设置 burn_time = 燃料值
```

**关键方法**：
- `isBurning(state)` — 检查是否正在燃烧（burn_time > 0）
- `hasUsableFuel(ctx, config)` — 检查燃料槽位是否有可用燃料
- `pauseTick(state)` — 暂停时每 tick -1（模拟余热消耗）

**燃料值获取**：通过 NeoForge 的 `IItemExtension.getBurnTime(stack, recipeType)` 接口查询，支持原版和模组的燃料物品。

### 2.3 ProgressComponent — 进度管理

管理活熔炉的熔炼进度。

**NBT 存储**：
| 键名 | 类型 | 说明 |
|------|------|------|
| `progress` | int | 当前进度值（ticks） |
| `total` | int | 总进度值（ticks） |

**tick() 执行流程**：
```
tick()
  └─ progress += multiplier（堆叠加速推进）
      └─ progress = min(total, progress)（不超上限）
```

**堆叠加速机制**：
```
progress += multiplier（multiplier = hostStack.getCount()）
例如：8 个活熔炉堆叠时，每 tick 进度 +8，200 ticks 的配方只需 25 ticks
```

| 堆叠数 | 完成时间 | 速度 |
|--------|---------|------|
| 1 | 200 ticks (10s) | 1x |
| 2 | 100 ticks (5s) | 2x |
| 4 | 50 ticks (2.5s) | 4x |
| 8 | 25 ticks (1.25s) | 8x |

**关键方法**：
- `isComplete(state, config)` — 检查进度是否完成（progress >= total）
- `reset(state)` — 重置进度（转化成功后调用）
- `pauseTick(state)` — 暂停时每 tick -1（模拟余热消散，避免进度卡在 99%）

### 2.4 ItemTransformComponent — 物品转化

实现配方匹配与物品转化逻辑。

**NBT 存储**：
| 键名 | 类型 | 说明 |
|------|------|------|
| `input_item` | string | 当前配方的输入物品 ID |
| `output_item` | string | 当前配方的输出物品 ID |
| `cached_input` | string | 上次配方检查的输入 ID（缓存） |
| `cached_result` | int | 上次配方检查的结果（0=无配方, 1=有配方） |
| `cached_output` | string | 上次配方检查的输出 ID（缓存，用于输出空间检查） |

**canProcess() 执行流程**：
```
canProcess(ctx, state)
  ├─ hasValidInput() && hasValidOutput()？→ 否 → return false
  ├─ 输入为空 或 是活物品？→ return false
  ├─ 缓存命中？
  │   ├─ cached_result == 0？→ return false
  │   └─ hasOutputSpace(ctx, cached_output)？→ 返回结果
  └─ 缓存未命中
      ├─ 查询 RecipeManager 匹配配方
      ├─ 无配方 → 缓存结果=0 → return false
      ├─ 有配方 → 缓存结果=1 + 缓存产物 ID
      └─ hasOutputSpace(ctx, outputKey)？→ 返回结果
```

**hasOutputSpace() 检查逻辑**：
```
hasOutputSpace(ctx, outputItemId)
  ├─ 输出槽位为空 → true（有空间）
  ├─ 输出物品 ≠ 产物 → false（不同物品，无空间）
  └─ 输出物品 == 产物 → count < slotLimit？（有空间/已满）
```

**executeTransform() 执行流程**：
```
executeTransform(ctx, hostStack, config, progress, transformState)
  ├─ 输入/输出槽位有效？→ 否 → return false
  ├─ 输入是活物品？→ return false
  ├─ RecipeManager 查询配方
  ├─ 无配方 → return false
  ├─ 计算转化数量
  │   └─ transformCount = min(stackMultiplier, inputCount, outputSpace/resultCount)
  ├─ transformCount <= 0 → return false
  ├─ 消耗输入：inputStack.shrink(transformCount)
  └─ 生成产物：outputStack.grow(transformCount * resultCount)
```

---

## 3. 熔炼流程

### 3.1 完整 tick 流程

```
BaseLivingFunction.tick()                                    [每 tick]
  │
  ├─ FunctionExecutor 创建 ComponentContext
  │   ├─ 读取 DirectionModeComponent 状态
  │   ├─ 调用 resolveSlots() → 解析 inputSlot/fuelSlot/outputSlot
  │   └─ 收集所有组件状态 → allComponentStates
  │
  └─ FuelProgressOrchestrator.orchestrate()
      │
      ├─ 1. isInputSlotOccupied() → 已占用则跳过
      │
      ├─ 2. checkCanProgress()
      │   ├─ 燃料检查
      │   │   ├─ isBurning()？→ 是 → 通过
      │   │   └─ hasUsableFuel()？→ 是 → 通过
      │   │                    → 否 → return false
      │   └─ 输入检查
      │       └─ canProcess()？→ 是 → return true
      │                        → 否 → return false
      │
      ├─ 3. canProgress == true？
      │   ├─ 是 → tickAllComponents()
      │   │   ├─ DirectionModeComponent.tick()（空操作）
      │   │   ├─ FuelConsumeComponent.tick()
      │   │   │   ├─ burn_time > 0 → burn_time -= multiplier
      │   │   │   └─ burn_time == 0 → 尝试消耗新燃料
      │   │   ├─ ProgressComponent.tick()
      │   │   │   └─ progress += multiplier
      │   │   └─ ItemTransformComponent.tick()
      │   │       └─ 无输入时清除配方信息
      │   └─ 否 → pauseTickComponents()
      │       ├─ ProgressComponent.pauseTick() → progress -= 1
      │       ├─ FuelConsumeComponent.pauseTick() → burn_time -= 1
      │       └─ 其他组件正常 tick()
      │
      ├─ 4. handleCompletion()
      │   ├─ progressComp.isComplete()？→ 否 → 跳过
      │   ├─ fuelComp.isBurning()？→ 否 → 跳过
      │   └─ transformComp.executeTransform()
      │       ├─ 成功 → progressComp.reset() + 保存配方信息
      │       └─ 失败 → 进度不回退（wait for output space）
      │
      └─ 5. markInputSlotOccupied()
```

### 3.2 暂停回退机制

当活熔炉无法继续处理时（无输入/无燃料/输出满），编排器走 `pauseTick` 路径：

| 组件 | 正常 tick | pauseTick |
|------|----------|-----------|
| `ProgressComponent` | progress += multiplier | progress -= 1 |
| `FuelConsumeComponent` | burn_time -= multiplier | burn_time -= 1 |
| 其他组件 | 正常 tick | 正常 tick |

**设计意图**：
- **进度回退**：模拟余热消散，避免进度卡在 199/200 等待燃料
- **燃料回退**：避免无输入时燃料白白消耗
- 回退速度固定为 1/tick，远慢于正常推进（multiplier/tick），所以不会在短暂中断后丢失全部进度

---

## 4. 燃料机制

### 4.1 燃料消耗流程

```
燃料槽位有物品
  │
  ├─ burn_time > 0？
  │   └─ 是 → burn_time -= multiplier（堆叠加速消耗）
  │       └─ 继续熔炼
  │
  └─ burn_time == 0？
      ├─ 燃料槽位有可用燃料？
      │   ├─ 是 → 消耗 1 个燃料物品
      │   │   └─ burn_time = getBurnTime(fuelStack)
      │   └─ 否 → canProgress = false
      │       └─ 走 pauseTick 路径
      └─ 燃料是活物品？→ 跳过
```

### 4.2 堆叠加速对燃料的影响

堆叠加速不仅加快进度，也加快燃料消耗：

| 堆叠数 | 进度速度 | 燃料消耗速度 | 燃料效率 |
|--------|---------|-------------|---------|
| 1 | 1x | 1x | 1:1 |
| 8 | 8x | 8x | 1:1 |

燃料效率不变（1 个煤炭始终能熔炼 8 个物品），只是速度更快。

---

## 5. 进度机制

### 5.1 进度计算

```
progress += multiplier（每 tick）
total = 200（配置项，针对原版熔炉配方）

完成 = progress >= total
```

### 5.2 进度完成 → 转化 → 重置

```
progress >= total
  │
  ├─ 燃料还在燃烧？
  │   └─ 否 → 等待燃料（进度保持，不重置）
  │
  └─ 燃料在燃烧
      └─ executeTransform()
          ├─ 成功 → progress = 0（重置）
          └─ 失败（输出满）→ 进度保持（等待输出空间）
```

### 5.3 进度 Tooltip

Tooltip 显示当前进度百分比和时间：
```
进度: 50% (5.0s/10.0s)
```

---

## 6. 物品转化

### 6.1 配方匹配

使用 Minecraft 原版的 `RecipeManager.getRecipeFor()` 查询配方，支持 `RecipeType.SMELTING`（熔炼配方）。

**缓存机制**：`canProcess()` 缓存上次的输入物品 ID 和查表结果，避免重复查表：
- 输入物品未变 → 直接用缓存结果
- 输入物品变化 → 重新查表

### 6.2 转化数量计算

```
transformCount = min(
    stackMultiplier,      // 堆叠加速上限
    inputStack.getCount(), // 输入物品数量
    outputSpace / resultCount  // 输出空间上限
)
```

**示例**：8 个活熔炉，输入 64 个铁矿石，输出 1 个铁锭（64 个空间）：
```
transformCount = min(8, 64, 64/1) = 8
→ 一次转化 8 个铁矿石 → 8 个铁锭
```

### 6.3 转化规则

- 活物品不能作为输入（`LivingItemManager.isLivingItem()` 检查）
- 产物数量 = `transformCount * resultCount`（配方本身可能产出多个物品）
- 输入物品全部消耗完→自动停止

---

## 7. 方向配置系统

### 7.1 SLOTS 模式

活熔炉使用 `DirectionModeComponent` 的 **SLOTS 模式**，管理三个槽位方向：

| 槽位 | 默认方向 | 偏移 |
|------|---------|------|
| input（输入） | LEFT | (-1, 0) |
| fuel（燃料） | DOWN | (0, 1) |
| output（输出） | RIGHT | (1, 0) |

### 7.2 WASD 输入

通过 WASD 序列修改方向，格式为 `键+槽位ID`：

```
W + 熔炉  → 修改当前选中槽位的方向为 UP
A + 熔炉  → 修改为 LEFT
S + 熔炉  → 修改为 DOWN
D + 熔炉  → 修改为 RIGHT
```

每次按键修改一个槽位方向，按顺序轮换 input → fuel → output。

### 7.3 槽位解析

与活漏斗共用 `SlotResolver`，通过相对方向偏移计算绝对槽位索引：
```
result = (baseRow + direction.y) * containerWidth + (baseCol + direction.x)
```

---

## 8. 已知问题与修复记录

### 8.1 已修复：输出满时进度仍增长

**问题**：输出槽位已满时，`canProcess()` 仍返回 `true`，进度继续推进，但 `executeTransform()` 发现输出满后直接返回 `false`，进度被浪费。

**根因**：`canProcess()` 只检查输出槽位是否存在（`hasValidOutput()` = `outputSlot >= 0`），不检查输出空间。

**修复**：
1. 新增 `hasOutputSpace(ctx, outputItemId)` 方法，检查输出槽位是否有空间
2. 新增 `KEY_CACHED_OUTPUT` 缓存键，让缓存路径也能检查输出空间
3. `canProcess()` 在两个路径都调用 `hasOutputSpace()`

**相关提交**：2026-07-22

---

## 9. 调试指南

### 9.1 关键日志点

```java
// 燃料状态
LOGGER.info("Burn time: {} ticks remaining", burnTime);

// 进度状态
LOGGER.info("Progress: {}/{} ({}%)", progress, total, percent);

// 转化执行
LOGGER.info("Transform: {}x {} -> {}x {}",
    transformCount, inputId, transformCount * resultCount, outputId);
```

### 9.2 常见问题排查

| 症状 | 可能原因 | 检查点 |
|------|---------|--------|
| 活熔炉完全不工作 | 方向配置错误 | Tooltip 显示的方向是否正确 |
| 进度不推进 | 无燃料/无输入 | 燃料槽位是否有燃料，输入槽位是否有可熔炼物品 |
| 有燃料但进度不推进 | 输入物品无配方 | 输入物品是否可熔炼 |
| 进度卡在 99% | 燃料耗尽 | 燃料槽位是否还有燃料 |
| 输出物品未生成 | 输出槽位已满 | 输出槽位是否还有空间 |
| 进度推进但输出满 | 输出空间检查失效 | `canProcess()` 是否正确检查输出空间 |
| 速度异常慢 | 堆叠数不足 | 活熔炉堆叠数是否足够 |

### 9.3 Tooltip 调试

在 Tooltip 中可以看到当前状态：
```
状态: 熔炼中
进度: 50% (5.0s/10.0s)
燃烧: 80.0s
原料: [铁矿石] → [铁锭]
```

---

> **文档维护者**: Living Item Mod Team  
> **下次更新建议**: 支持更多配方类型（BLASTING/SMOKING）后同步更新第 6 章