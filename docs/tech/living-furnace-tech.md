# Living Furnace (活熔炉) 技术文档

> **文档版本**: 2026.07 v4  
> **最后更新**: 2026-07-28  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [核心流程](#2-核心流程)
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
│  │ 5. 保存状态 + 同步客户端                                   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  数据通过 LivingItemManager 管理，使用 record 类序列化：          │
│  ┌─────────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐  │
│  │ FuelData     │  │ProgressData│ │TransformData│ │DirectionSlots│ │
│  │ burnTime     │  │ progress  │  │ inputItem  │  │ directions  │  │
│  │              │  │ total     │  │ outputItem │  │             │  │
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
| `LivingFurnaceFunction` | `function/LivingFurnaceFunction.java` | 活熔炉功能入口，实现 `LivingItemFunction` 接口，所有熔炼逻辑集中在此 |
| `LivingFurnaceData` | `data/LivingFurnaceData.java` | 活熔炉数据容器 record：包含 FuelData、ProgressData、TransformData、DirectionSlotsData |
| `FuelData` | `data/FuelData.java` | 燃料状态 record：burnTime |
| `ProgressData` | `data/ProgressData.java` | 进度状态 record：progress + total |
| `TransformData` | `data/TransformData.java` | 转化状态 record：配方缓存、输入/输出物品 ID |
| `DirectionSlotsData` | `data/DirectionSlotsData.java` | 方向配置 record：input/fuel/output 的 Pos2D 偏移 |
| `SlotResolver` | `core/SlotResolver.java` | 相对方向→绝对槽位索引的数学计算 |

### 1.4 存储结构

活熔炉的所有数据存储在 ItemStack 的 DataComponent 中，通过 `LivingFurnaceData` record 管理：

```
ItemStack
├── IS_LIVING: true                          ← 活物品标记
└── LIVING_FURNACE_DATA: LivingFurnaceData   ← 功能状态（DataComponent）
    ├─ direction: DirectionSlotsData          ← 方向配置
    │   ├─ input: Pos2D
    │   ├─ fuel: Pos2D
    │   └─ output: Pos2D
    ├─ fuel: FuelData                         ← 燃料状态
    │   └─ burnTime: int
    ├─ progress: ProgressData                 ← 进度状态
    │   ├─ progress: int
    │   └─ total: int
    └─ transform: TransformData               ← 转化状态
        ├─ inputItem: String
        ├─ outputItem: String
        ├─ cachedInput: String
        ├─ cachedResult: int
        ├─ cachedOutput: String
        ├─ cachedOutputCount: int
        └─ cachedCookingTime: int
```

> **v4 变更**：存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingFurnaceData`），利用 Minecraft 内置的序列化和同步机制。

---

## 2. 核心流程

### 2.1 Tick 主循环

```java
@Override
public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    if (level.isClientSide) return;

    for (SlotEntry entry : entries) {
        ItemStack stack = entry.stack();
        LivingFurnaceData data = LivingItemManager.getFurnaceData(stack);

        // 1. 解析方向 → 计算槽位
        DirectionSlotsData dir = data.direction();
        int inputSlot = SlotResolver.resolve(slot, dir.getDirection("input"), containerSize, containerWidth);
        int fuelSlot = SlotResolver.resolve(slot, dir.getDirection("fuel"), containerSize, containerWidth);
        int outputSlot = SlotResolver.resolve(slot, dir.getDirection("output"), containerSize, containerWidth);

        // 2. 检查是否可以继续熔炼
        boolean canProgress = checkCanProgress(context, level, inputSlot, fuelSlot, outputSlot, data);

        // 3. 更新配方缓存
        data = tickTransform(context, data, inputSlot, level);

        // 4. 推进或回退
        if (canProgress) {
            data = tickProgress(data, stack.getCount());
            data = tickFuel(context, data, fuelSlot, stack.getCount());

            if (data.progress().isComplete() && data.fuel().isBurning()) {
                boolean success = executeTransform(context, level, data, inputSlot, outputSlot, stack.getCount());
                if (success) {
                    data = data.withProgress(data.progress().reset());
                }
            }
        } else {
            data = pauseTick(data);
        }

        // 5. 保存状态
        LivingItemManager.setFurnaceData(stack, data);
        context.syncSlotToClients(slot, stack);
    }
}
```

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
  ├─ 燃料检查
  │   ├─ 正在燃烧？→ 通过
  │   ├─ fuelSlot 无效？→ return false
  │   ├─ 燃料槽为空？→ return false
  │   ├─ 燃料值 <= 0？→ return false
  │   └─ 燃料是活物品？→ return false
  └─ hasMatchingRecipe() → 返回配方匹配结果
```

### 3.2 进度推进 `tickProgress()`

```
tickProgress(data, stackCount)
  ├─ multiplier = 1 + stackCount / 8
  └─ progress = min(total, progress + multiplier)
```

| 堆叠数 | 倍率 | 完成时间 |
|--------|------|---------|
| 1 | 1x | 200 ticks (10s) |
| 8 | 2x | 100 ticks (5s) |
| 16 | 3x | 67 ticks (3.3s) |
| 32 | 5x | 40 ticks (2s) |
| 56 | 8x | 25 ticks (1.25s) |

### 3.3 暂停回退 `pauseTick()`

当活熔炉无法继续处理时（无输入/无燃料/输出满），走暂停路径：

| 状态 | 行为 |
|------|------|
| 进度 > 0 | progress -= 1（模拟余热消散） |
| 燃料在燃烧 | burnTime -= 1（模拟余热消耗） |

---

## 4. 燃料机制

### 4.1 燃料消耗

```java
private LivingFurnaceData tickFuel(ContainerContext ctx, LivingFurnaceData data, int fuelSlot, int stackCount) {
    FuelData fuel = data.fuel();
    if (fuel.isBurning()) {
        // 正在燃烧 → 消耗燃料
        int multiplier = Math.max(1, stackCount);
        return data.withFuel(fuel.tick(multiplier));
    }

    // 燃料耗尽 → 尝试消耗新燃料
    ItemStack fuelStack = ctx.getItem(fuelSlot);
    int fuelValue = getFuelValue(fuelStack);
    if (fuelValue > 0 && !LivingItemManager.isLivingItem(fuelStack)) {
        fuelStack.shrink(1);  // 消耗 1 个燃料物品
        ctx.setItem(fuelSlot, fuelStack.copy());
        return data.withFuel(new FuelData(fuelValue));
    }
    return data;
}
```

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

```java
public boolean isComplete() {
    return progress >= total;
}
```

完成条件：`progress >= total`（默认 total = 200）。

### 5.2 进度重置

转化成功后，进度重置为 0：

```java
data = data.withProgress(data.progress().reset());
```

---

## 6. 物品转化

### 6.1 配方缓存 `tickTransform()`

为避免每 tick 查询配方管理器，活熔炉使用缓存机制：

```java
private LivingFurnaceData tickTransform(ContainerContext ctx, LivingFurnaceData data, int inputSlot, Level level) {
    ItemStack inputStack = ctx.getItem(inputSlot);
    String inputKey = BuiltInRegistries.ITEM.getKey(inputStack.getItem()).toString();
    TransformData transform = data.transform();

    // 缓存命中：输入物品没变
    if (inputKey.equals(transform.cachedInput()) && transform.cachedResult() == 1) {
        return data;  // 直接使用缓存
    }

    // 缓存未命中 → 查询配方
    var recipeHolderOpt = level.getRecipeManager()
        .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(inputStack), level);
    if (recipeHolderOpt.isPresent()) {
        // 缓存配方结果
        transform = transform.withCache(inputKey, 1, outputKey, resultCount, cookingTime);
    } else {
        // 缓存"无配方"
        transform = transform.withCache(inputKey, 0, "", 0, 0);
    }
    return data.withTransform(transform);
}
```

### 6.2 转化执行 `executeTransform()`

```
executeTransform(ctx, level, data, inputSlot, outputSlot, stackCount)
  ├─ 输入/输出槽位有效？→ 否 → return false
  ├─ 输入是活物品？→ return false
  ├─ RecipeManager 查询配方
  ├─ 无配方 → return false
  ├─ 计算转化数量
  │   ├─ outputSpace = 输出槽可用空间
  │   ├─ maxByOutput = outputSpace / resultCount
  │   └─ transformCount = min(stackCount, inputCount, maxByOutput)
  ├─ transformCount <= 0 → return false
  ├─ 消耗输入：inputStack.shrink(transformCount)
  └─ 生成产物：outputStack.grow(transformCount * resultCount)
```

### 6.3 输出空间计算

```java
private int calculateOutputSpace(ContainerContext ctx, int outputSlot, ItemStack result) {
    ItemStack outputStack = ctx.getItem(outputSlot);
    int slotLimit = ctx.getSlotLimit(outputSlot);
    if (outputStack.isEmpty()) {
        return Math.min(slotLimit, result.getMaxStackSize());
    }
    if (ItemStack.isSameItemSameComponents(outputStack, result)) {
        int maxCount = Math.min(slotLimit, outputStack.getMaxStackSize());
        return maxCount - outputStack.getCount();
    }
    return 0;  // 不同物品，无空间
}
```

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

### 8.6 堆叠加速计算方式 (NEW 2026-07-27)

活熔炉的堆叠数量影响熔炼速度：

**进度推进**：
```java
int multiplier = 1 + stackCount / 8;
// 堆叠 1-7 → 1x 速度
// 堆叠 8-15 → 2x 速度
// 堆叠 16-23 → 3x 速度
// ...
```

**燃料消耗**：
```java
int multiplier = Math.max(1, stackCount);
// 堆叠 1 → 1x 消耗（正常速度）
// 堆叠 8 → 8x 消耗（燃料烧得更快）
// 堆叠 16 → 16x 消耗
```

**设计意图**：堆叠越多 → 熔炼越快，但燃料消耗也越快，形成平衡。

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
- [ ] 燃料耗尽后停止熔炼，进度保留
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

### 数据持久化

- [ ] LivingFurnaceData 通过 DataComponent 持久化
- [ ] 熔炼进度在物品离开容器后保留
- [ ] 燃料剩余时间在物品离开容器后保留
- [ ] 配方缓存（TransformData）在物品离开容器后保留