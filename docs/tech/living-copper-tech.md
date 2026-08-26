<!-- markdownlint-disable -->

# Living Copper (活铜) 技术文档

> **文档版本**: 2026.08 v3
> **最后更新**: 2026-08-26
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [信号传播算法](#3-信号传播算法)
4. [活铜方块类型](#4-活铜方块类型)
5. [与红石系统的集成](#5-与红石系统的集成)
6. [实施状态](#6-实施状态)

---

## 1. 架构概览

### 1.1 什么是活铜？

活铜系统是活红石系统的信号层扩展，为铜方块及其变体赋予红石信号传输能力。活铜方块在 2D 容器网格中充当**无损信号线缆**，与活红石粉（衰减传输）形成互补的信号传输层。

核心特性：
- **无损传输**：铜块转发信号不衰减，`output = maxInput`（红石粉 `output = maxInput - 1`）
- **锈蚀频道隔离**：仅同锈蚀等级的铜块之间互相导通，不同锈蚀等级互不干扰
- **雕文铜块二极管**：输入/输出双方向独立设置，信号仅从输入方向接收、向输出方向传输
- **切制铜块立交桥**：水平/垂直信号独立传播，可交叉而不串扰
- **铜格栅分频器**：输入信号周期翻倍（上升沿翻转）
- **铜灯 T 触发器**：上升沿翻转 lit 状态并保持

```
┌──────────────────────────────────────────────────────────────────────┐
│                       活铜 信号层架构                                 │
│                                                                      │
│  ┌──────────────────┐        ┌──────────────────┐                   │
│  │ 活红石粉（介质）  │  注入   │ 活铜块（无损线缆）│                   │
│  │ 衰减: output-1    │ ────→  │ 转发: output=max  │                   │
│  │ 可连任意锈蚀铜块  │        │ 仅连同锈蚀铜块    │                   │
│  └──────────────────┘        └────────┬─────────┘                   │
│                                       │                              │
│              ┌────────────────────────┼──────────────────┐          │
│              ▼                        ▼                  ▼          │
│        ┌──────────┐           ┌──────────┐       ┌──────────┐      │
│        │ 雕文铜块  │           │ 切制铜块  │       │ 普通铜块  │      │
│        │ 二极管    │           │ 立交桥    │       │ 四向线缆  │      │
│        │ 输入/输出 │           │ H/V 隔离  │       │ 无损转发  │      │
│        └──────────┘           └──────────┘       └──────────┘      │
│                                                                      │
│  ┌──────────────────┐        ┌──────────────────┐                   │
│  │ 铜格栅（分频器）  │        │ 铜灯（T触发器）   │                   │
│  │ 上升沿翻转output  │        │ 上升沿翻转lit     │                   │
│  │ Phase1 输出信号   │        │ Phase1 输出信号   │                   │
│  └──────────────────┘        └──────────────────┘                   │
└──────────────────────────────────────────────────────────────────────┘
```

### 1.2 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingCopperFunction` | `domain/redstone/LivingCopperFunction.java` | 活铜块功能入口，实现 `HasContainerData` + `HasDirection`，触发容器级信号计算 |
| `LivingCutCopperData` | `domain/redstone/LivingCutCopperData.java` | 雕文铜块物品级数据：输入方向 + 输出方向 |
| `LivingGrateData` | `domain/redstone/LivingGrateData.java` | 铜格栅物品级数据：上一帧输入 + 输出状态 |
| `LivingCopperBulbData` | `domain/redstone/LivingCopperBulbData.java` | 铜灯物品级数据：点亮状态 + 上一帧输入 |
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石信号数据（含铜块传播逻辑） |

### 1.3 适用的铜方块

活铜功能适用于所有**未涂蜡**的铜方块变体，按锈蚀等级分为 4 个频道：

| 锈蚀等级 | 频道 | 普通铜块 | 雕文铜块 | 切制铜块 | 铜格栅 | 铜灯 |
|---------|------|---------|---------|---------|--------|------|
| 0 (无锈蚀) | A | Copper Block | Chiseled Copper | Cut Copper | Copper Grate | Copper Bulb |
| 1 (斑驳) | B | Exposed Copper | Exposed Chiseled Copper | Exposed Cut Copper | Exposed Copper Grate | Exposed Copper Bulb |
| 2 (锈蚀) | C | Weathered Copper | Weathered Chiseled Copper | Weathered Cut Copper | Weathered Copper Grate | Weathered Copper Bulb |
| 3 (氧化) | D | Oxidized Copper | Oxidized Chiseled Copper | Oxidized Cut Copper | Oxidized Copper Grate | Oxidized Copper Bulb |

> 涂蜡铜块**不适用**活铜功能（`canApply` 返回 `false`），仅未涂蜡铜块可变为活铜。

---

## 2. 数据结构

### 2.1 LivingCopperFunction — 功能入口

```java
public class LivingCopperFunction implements LivingItemFunction, HasContainerData, HasDirection {

    public static final String ID = "living_copper";

    public boolean canApply(ItemStack stack)  // 仅未涂蜡铜块
    public void tickContainerData(...)         // 触发 ContainerRedstoneData.calculate()

    // HasDirection 接口
    public int getDirectionKeyCount()          // 2（输入方向 + 输出方向）
    public String[] getDirectionSlotNames()    // ["input", "output"]
    public boolean updateSlotDirection(...)    // WASD 更新输入/输出方向

    // 静态类型判定
    public static boolean isUnwaxedCopperBlock(Item)  // 全部未涂蜡铜块
    public static boolean isBaseCopper(Item)          // 普通铜块 (Cable)
    public static boolean isChiseled(Item)            // 雕文铜块 (Diode)
    public static boolean isCut(Item)                 // 切制铜块 (Overpass)
    public static boolean isGrate(Item)               // 铜格栅 (Divider)
    public static boolean isBulb(Item)                // 铜灯 (T Flip-Flop)
    public static int getOxidationLevel(Item)         // 锈蚀等级 0-3
}
```

### 2.2 LivingCutCopperData — 雕文铜块二极管数据

```java
public record LivingCutCopperData(
    Pos2D inputDir,    // 输入方向（默认 DOWN）
    Pos2D outputDir    // 输出方向（默认 UP）
) implements TooltipProvider {

    public static final LivingCutCopperData DEFAULT =
        new LivingCutCopperData(Pos2D.DOWN, Pos2D.UP);

    public LivingCutCopperData withInputDir(Pos2D inputDir) { ... }
    public LivingCutCopperData withOutputDir(Pos2D outputDir) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `inputDir` | Pos2D | DOWN | 信号输入方向，仅从此方向接收信号 |
| `outputDir` | Pos2D | UP | 信号输出方向，仅向此方向转发信号 |

### 2.3 LivingGrateData — 铜格栅分频器数据

```java
public record LivingGrateData(
    boolean lastInput,  // 上一帧输入信号状态
    boolean output      // 当前输出状态
) implements TooltipProvider {

    public static final LivingGrateData DEFAULT =
        new LivingGrateData(false, false);

    public LivingGrateData withLastInput(boolean lastInput) { ... }
    public LivingGrateData withOutput(boolean output) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `lastInput` | boolean | false | 上一帧是否有输入信号，用于上升沿检测 |
| `output` | boolean | false | 当前输出状态，上升沿时翻转 |

### 2.4 LivingCopperBulbData — 铜灯 T 触发器数据

```java
public record LivingCopperBulbData(
    boolean lit,        // 是否点亮
    boolean prevInput   // 上一帧输入信号状态
) implements TooltipProvider {

    public static final LivingCopperBulbData DEFAULT =
        new LivingCopperBulbData(false, false);

    public LivingCopperBulbData withLit(boolean lit) { ... }
    public LivingCopperBulbData withPrevInput(boolean prevInput) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `lit` | boolean | false | 当前点亮状态，上升沿时翻转 |
| `prevInput` | boolean | false | 上一帧输入信号状态，用于上升沿检测 |

### 2.5 槽位类型位掩码

在 `ContainerRedstoneData` 中新增 5 个铜块相关位掩码：

```java
private static final int BIT_COPPER   = 1 << 8;   // 活铜块（信号层）
private static final int BIT_CHISELED = 1 << 9;   // 雕文铜块（二极管）
private static final int BIT_CUT      = 1 << 10;  // 切制铜块（立交桥）
private static final int BIT_GRATE    = 1 << 11;  // 铜格栅（分频器）
private static final int BIT_BULB     = 1 << 12;  // 铜灯（T触发器）
```

`MASK_REDSTONE` 已扩展为包含 `BIT_COPPER`，确保导电方块充能时跳过铜块槽位。

### 2.6 存储结构

活铜物品的数据存储在 ItemStack 的 DataComponent 中：

```
ItemStack (minecraft:copper_block)
├── IS_LIVING: true
└── （无额外数据，Cable 类型无需状态）

ItemStack (minecraft:chiseled_copper)
├── IS_LIVING: true
└── LIVING_CUT_COPPER_DATA: LivingCutCopperData
    ├─ inputDir: Pos2D                         ← 输入方向
    └─ outputDir: Pos2D                        ← 输出方向

ItemStack (minecraft:cut_copper)
├── IS_LIVING: true
└── （无额外数据，Overpass 类型无需状态）

ItemStack (minecraft:copper_grate)
├── IS_LIVING: true
└── LIVING_GRATE_DATA: LivingGrateData
    ├─ lastInput: boolean                        ← 上一帧输入
    └─ output: boolean                           ← 当前输出

ItemStack (minecraft:copper_bulb)
├── IS_LIVING: true
└── LIVING_COPPER_BULB_DATA: LivingCopperBulbData
    ├─ lit: boolean                              ← 点亮状态
    └─ prevInput: boolean                        ← 上一帧输入
```

---

## 3. 信号传播算法

### 3.1 触发与集成

活铜块的信号传播完全集成在 `ContainerRedstoneData.calculate()` 的六阶段算法中，与活红石系统共享同一 `EdgeGrid` 和传播时序。`LivingCopperFunction.tickContainerData()` 与 `LivingRedstoneFunction.tickContainerData()` 同样触发 `calculate()`，`processedThisTick` 去重确保同一 tick 内只计算一次。

### 3.2 槽位分类

在 `calculate()` 中，铜块槽位被进一步细分为子类型：

```java
Set<Integer> copperSlots = tick.getFunctionSlots(LivingCopperFunction.ID);
Set<Integer> chiseledSlots = copperSubset(copperSlots, context, LivingCopperFunction::isChiseled);
Set<Integer> cutSlots = copperSubset(copperSlots, context, LivingCopperFunction::isCut);
Set<Integer> grateSlots = copperSubset(copperSlots, context, LivingCopperFunction::isGrate);
Set<Integer> bulbSlots = copperSubset(copperSlots, context, LivingCopperFunction::isBulb);
```

所有子类型共享 `BIT_COPPER` 父类型，同时各自拥有专属位掩码（`BIT_CHISELED` 等），实现**父类型判定 + 子类型特化**的双层类型系统。

### 3.3 锈蚀频道隔离（canConnect）

铜块之间的连接受锈蚀等级约束。`canConnect()` 方法在 `phase2Propagation` 和 `propagateDir` 中调用：

```
canConnect(slot, neighbor, context):
  1. 邻居不存在或为空 → false
  2. slot 非铜块 && neighbor 是铜块 → true（红石粉可注入任意频道）
  3. slot 是铜块 && neighbor 是红石粉 → true（铜块可输出到红石粉）
  4. slot 是铜块 && neighbor 是铜块 → 检查锈蚀等级是否相同
  5. 其他情况 → true（红石粉 ↔ 红石粉等）
```

```
锈蚀频道隔离示意：

  [铜块 A]──[铜块 A]──[红石粉]──[铜块 B]──[铜块 B]
     ✓           ✓         ✓         ✓           ✓
  同频道导通  同频道导通  跨层注入  同频道导通  同频道导通

  [铜块 A]──[铜块 B]
     ✗
  不同频道隔离
```

### 3.4 Phase 1 — 收集信号源（铜块相关）

Phase 1 中两类铜块作为信号源参与：

**铜格栅 (Grate)**：当 `data.output() == true` 时，向 4 方向输出信号：
```
for slot in grateSlots:
  if !data.output() → skip
  cap = getSignalCap(count)
  for 4 方向 dir:
    if cap > edgeGrid.get(slot, dir):
      edgeGrid.set(slot, dir, cap)
      if 邻居是红石粉或铜块 → 邻居入队
```

**铜灯 (Bulb)**：当 `data.lit() == true` 时，向 4 方向输出信号：
```
for slot in bulbSlots:
  if !data.lit() → skip
  cap = getSignalCap(count)
  for 4 方向 dir:
    if cap > edgeGrid.get(slot, dir):
      edgeGrid.set(slot, dir, cap)
      if 邻居是红石粉或铜块 → 邻居入队
```

所有信号源（火把/按钮/拉杆/中继器/比较器/红石块）的邻居入队条件已从 `BIT_DUST` 扩展为 `BIT_DUST | BIT_COPPER`。

### 3.5 Phase 2 — BFS 传播（铜块核心逻辑）

Phase 2 是铜块信号传播的核心。BFS 队列现在同时包含红石粉和铜块两种介质：

```
while queue not empty:
  current = queue.poll()

  // ── 红石粉：衰减传播 ──
  if is(current, BIT_DUST):
    maxInput = edgeGrid.maxOfSlot(current)
    if maxInput <= 1 → continue
    output = min(maxInput - 1, getSignalCap(count))
    for 4 方向 dir:
      propagateDir(current, dir, output, ...)   // 衰减 -1，可连铜块

  // ── 铜块：无损转发 ──
  if is(current, BIT_COPPER):
    maxInput = edgeGrid.maxOfSlot(current)
    cap = getSignalCap(count)
    output = min(maxInput, cap)                  // 不衰减！

    // ── 雕文铜块：输入/输出双方向 ──
    if is(current, BIT_CHISELED):
      inputEdge = edgeIndex(data.inputDir())
      outputEdge = edgeIndex(data.outputDir())
      chiseledInput = edgeGrid.get(current, inputEdge)  // 仅从输入方向取信号
      chiseledOutput = min(chiseledInput, cap)
      propagateDir(current, outputEdge, chiseledOutput, ...)  // 仅向输出方向传播
      continue

    // ── 切制铜块：水平/垂直隔离 ──
    if is(current, BIT_CUT):
      maxHInput = max(edgeGrid.get(current, LEFT), edgeGrid.get(current, RIGHT))
      maxVInput = max(edgeGrid.get(current, UP), edgeGrid.get(current, DOWN))
      outputH = min(maxHInput, cap)
      outputV = min(maxVInput, cap)
      propagateDir(current, LEFT, outputH, ...)   // 水平信号仅水平传播
      propagateDir(current, RIGHT, outputH, ...)
      propagateDir(current, UP, outputV, ...)     // 垂直信号仅垂直传播
      propagateDir(current, DOWN, outputV, ...)
      continue

    // ── 普通铜块：四向无损转发 ──
    for 4 方向 dir:
      propagateDir(current, dir, output, ...)
```

**propagateDir 辅助方法**：
```
propagateDir(slot, dir, signal, queue):
  neighbor = resolveSlot(slot, dir)
  if neighbor < 0 → return
  if !canConnect(slot, neighbor) → return     // 锈蚀频道检查
  if signal <= edgeGrid.get(slot, dir) → return
  edgeGrid.set(slot, dir, signal)
  if 邻居是红石粉或铜块 → 邻居入队
```

**关键设计**：
- 普通铜块 `output = min(maxInput, cap)` 无损转发，不执行 `-1`
- 雕文铜块读取 `LivingCutCopperData.inputDir` 和 `outputDir`，仅从输入方向接收信号、仅向输出方向转发
- 切制铜块将水平/垂直方向的信号分开处理，各方向仅取该方向的最大值
- `canConnect` 在 `propagateDir` 中执行，确保锈蚀频道隔离

### 3.6 Phase 3 — 重新检测输入（铜格栅 + 铜灯）

Phase 3 新增铜格栅和铜灯的状态更新逻辑：

**铜格栅 (Grate) — 分频器**：
```
for slot in grateSlots:
  hasInput = edgeGrid.anyOfSlot(slot)  // 任意边有信号

  if hasInput && !data.lastInput():     // 上升沿
    data = data.withOutput(!data.output())  // 翻转输出

  data = data.withLastInput(hasInput)   // 更新上一帧输入

  if changed:
    LivingItemManager.setGrateData(stack, data)
    context.syncSlotToClients(slot, stack)
```

**铜灯 (Bulb) — T 触发器**：
```
for slot in bulbSlots:
  hasInput = edgeGrid.anyOfSlot(slot)  // 任意边有信号

  if hasInput && !data.prevInput():     // 上升沿
    data = data.withLit(!data.lit())    // 翻转 lit

  data = data.withPrevInput(hasInput)   // 更新上一帧输入

  if changed:
    LivingItemManager.setCopperBulbData(stack, data)
    context.syncSlotToClients(slot, stack)
```

**时序说明**：
- 分频器和 T 触发器在 Phase 3 更新状态，下一 tick 的 Phase 1 作为信号源输出
- 这保证了 1 tick 的输入→输出延迟，与中继器/比较器的 Phase 3 → 下一 tick Phase 1 模式一致

### 3.7 Phase 4 — 充能导电活物品（铜块相关）

Phase 4 新增铜块作为导电方块充能源。铜块充当**强信号源**，向相邻导电方块输出信号：

```
for slot in copperSlots:
  maxInput = edgeGrid.maxOfSlot(slot)
  if maxInput <= 0 → continue
  cap = getSignalCap(count)
  output = min(maxInput, cap)

  // 雕文铜块：仅输出方向充能，仅取输入方向信号
  if is(slot, BIT_CHISELED):
    inputEdge = edgeIndex(data.inputDir())
    outputEdge = edgeIndex(data.outputDir())
    chiseledInput = edgeGrid.get(slot, inputEdge)
    chiseledOutput = min(chiseledInput, cap)
    powerConductiveNeighbor(slot, chiseledOutput, outputEdge, ...)

  // 切制铜块：水平/垂直分别充能
  else if is(slot, BIT_CUT):
    maxHInput = max(edgeGrid.get(slot, LEFT), edgeGrid.get(slot, RIGHT))
    maxVInput = max(edgeGrid.get(slot, UP), edgeGrid.get(slot, DOWN))
    powerConductiveNeighbor(slot, min(maxHInput, cap), LEFT, ...)
    powerConductiveNeighbor(slot, min(maxHInput, cap), RIGHT, ...)
    powerConductiveNeighbor(slot, min(maxVInput, cap), UP, ...)
    powerConductiveNeighbor(slot, min(maxVInput, cap), DOWN, ...)

  // 普通铜块：四向充能
  else:
    for 4 方向 dir:
      powerConductiveNeighbor(slot, output, dir, ...)
```

### 3.8 Phase 5 — 更新显示状态（铜块相关）

Phase 5 新增铜格栅和铜灯的显示同步：

```
for slot in copperSlots:
  // 铜格栅：同步 output 状态
  if is(slot, BIT_GRATE):
    maxSignal = edgeGrid.maxOfSlot(slot)
    if data.output() != (maxSignal > 0):
      data = data.withOutput(maxSignal > 0)
      syncSlotToClients(slot, stack)

  // 铜灯：同步 lit 状态
  if is(slot, BIT_BULB):
    hasSignal = edgeGrid.anyOfSlot(slot)
    if data.lit() != hasSignal:
      data = data.withLit(hasSignal)
      syncSlotToClients(slot, stack)
```

### 3.9 红石粉连接铜块

`computeDustConnections()` 已扩展，红石粉在计算连接状态时会将铜块槽位视为可连接目标：

```java
// 邻居是铜块 → 建立连接
else if (is(neighbor, BIT_BUTTON | BIT_LEVER | BIT_TORCH
        | BIT_DUST | BIT_LAMP | BIT_BLOCK | BIT_COPPER)) {
    conn |= (1 << dir);
}
```

---

## 4. 活铜方块类型

### 4.1 普通铜块 (Cable) — 无损线缆

**对应物品**：Copper Block / Exposed Copper / Weathered Copper / Oxidized Copper

**行为**：
- 四向无损转发信号：`output = min(maxInput, cap)`
- 仅与同锈蚀等级的铜块连接
- 红石粉可向任意锈蚀等级的铜块注入信号
- Phase 4 作为强信号源充能相邻导电方块

**tooltip**：显示类型 `Cable` 和频道字母 `A/B/C/D`

### 4.2 雕文铜块 (Diode) — 输入/输出双方向二极管

**对应物品**：Chiseled Copper / Exposed Chiseled Copper / Weathered Chiseled Copper / Oxidized Chiseled Copper

**行为**：
- 信号仅从 `inputDir` 方向接收，仅向 `outputDir` 方向转发
- 输入/输出方向独立设置，支持任意方向组合（4×4=16 种）
- 不接收非输入方向的信号
- 不向非输出方向转发信号

**方向控制**：`inputDir` 和 `outputDir` 存储在 `LIVING_CUT_COPPER_DATA` DataComponent 中，默认 `inputDir=DOWN, outputDir=UP`（下传上）。通过 WASD 方向输入修改：第 1 键设置输入方向，第 2 键设置输出方向。

**方向组合示例**：
```
  输入=↓ 输出=↑  →  下传上（默认）
  输入=← 输出=→  →  左传右
  输入=↑ 输出=↓  →  上传下
  输入=← 输出=↑  →  左传上（拐角导通）
  输入=↓ 输出=→  →  下传右（拐角导通）
```

**tooltip**：显示红电信号强度、最大红电信号强度、输入方向符号、输出方向符号

### 4.3 切制铜块 (Overpass) — 立交桥【原：4.2 雕文铜块】

**对应物品**：Cut Copper / Exposed Cut Copper / Weathered Cut Copper / Oxidized Cut Copper

**行为**：
- 水平/垂直信号完全隔离
- 水平方向的信号仅沿水平方向传播，垂直方向的信号仅沿垂直方向传播
- 可实现信号交叉而不串扰

**原理**：
```
  输入信号:
    水平方向: 取 LEFT 和 RIGHT 边的最大值
    垂直方向: 取 UP 和 DOWN 边的最大值

  输出信号:
    水平信号 → LEFT + RIGHT 边
    垂直信号 → UP + DOWN 边

  水平信号和垂直信号互不干扰，实现"立交桥"效果
```

**tooltip**：显示类型 `Overpass`、频道字母和提示文字

### 4.4 铜格栅 (Divider) — 分频器

**对应物品**：Copper Grate / Exposed Copper Grate / Weathered Copper Grate / Oxidized Copper Grate

**行为**：
- 输入信号周期翻倍
- 上升沿（`!lastInput && hasInput`）时翻转 `output`
- Phase 3 更新状态，下一 tick Phase 1 输出信号

**时序示例**：
```
  tick:  0  1  2  3  4  5  6  7  8  9
  input: 0  1  1  0  0  1  1  0  0  1
  output:0  0  1  1  1  1  0  0  0  0
              ↑           ↑
           上升沿翻转   上升沿翻转
```

**tooltip**：显示类型 `Divider`、频道字母和当前 output 状态

### 4.5 铜灯 (T Flip-Flop) — T 触发器

**对应物品**：Copper Bulb / Exposed Copper Bulb / Weathered Copper Bulb / Oxidized Copper Bulb

**行为**：
- 上升沿（`!prevInput && hasInput`）时翻转 `lit` 状态
- `lit` 状态保持到下一个上升沿
- Phase 3 更新状态，下一 tick Phase 1 输出信号

**时序示例**：
```
  tick:  0  1  2  3  4  5  6  7  8  9
  input: 0  1  0  0  1  0  0  1  0  0
  lit:   0  1  1  1  0  0  0  1  1  1
              ↑        ↑        ↑
          上升沿翻转 上升沿翻转 上升沿翻转
```

**tooltip**：显示类型 `T Flip-Flop`、频道字母和当前 ON/OFF 状态

---

## 5. 与红石系统的集成

### 5.1 共享 EdgeGrid

活铜块与活红石系统共享同一个 `EdgeGrid` 实例，信号在铜块和红石粉之间无缝流转：

```
[火把(15)] → [红石粉] → [铜块 A] → [铜块 A] → [红石粉] → [红石灯]
   Phase1    衰减到14    无损14      无损14     衰减到13     Phase5
```

### 5.2 跨层注入

红石粉可向任意锈蚀等级的铜块注入信号（`canConnect` 规则 2），铜块可向红石粉输出信号（规则 3）。这保证了红石层和铜层之间的信号互通：

```
[红石粉] ──注入──→ [铜块 A]   ✓ 红石→铜块，不限锈蚀
[铜块 A] ──输出──→ [红石粉]   ✓ 铜块→红石，不限锈蚀
[铜块 A] ──连接──→ [铜块 B]   ✗ 不同锈蚀频道隔离
```

### 5.3 面信号交互

活铜块参与容器面信号输出。`computeFaceOutput()` 扫描 `edgeGrid` 边界边时，铜块贡献的边信号同样被计入：

```
内部铜块信号 → edgeGrid 边界边 → computeFaceOutput → faceOutput[dir]
→ getBoundarySignal(dir) → Mixin 注入 → 容器方块对外输出红石信号
```

### 5.4 位掩码集成

铜块在 `slotMask` 位图中同时拥有 `BIT_COPPER` 父类型和子类型位（`BIT_CHISELED` 等），支持双重判定：

```java
is(slot, BIT_COPPER)          → true  // 是铜块（任意子类型）
is(slot, BIT_CHISELED)        → true  // 是雕文铜块（二极管）
is(slot, BIT_COPPER | BIT_CUT) → true  // 是铜块且是切制铜块（立交桥）
```

---

## 6. 实施状态

### 6.1 已完成

| 特性 | 状态 | 说明 |
|------|------|------|
| 普通铜块无损线缆 | ✅ | 四向无损转发，锈蚀频道隔离 |
| 雕文铜块二极管 | ✅ | 输入/输出双方向独立设置，WASD 2键配置 |
| 切制铜块立交桥 | ✅ | 水平/垂直信号独立传播 |
| 铜格栅分频器 | ✅ | 上升沿翻转，Phase 3 状态更新 |
| 铜灯 T 触发器 | ✅ | 上升沿翻转 lit，Phase 3 状态更新 |
| 红石-铜块互通 | ✅ | canConnect 规则支持跨层注入 |
| 铜块充能导体 | ✅ | Phase 4 铜块作为强信号源 |
| 容器面信号输出 | ✅ | 铜块信号参与 faceOutput 计算 |
| Tooltip 显示 | ✅ | 类型、频道、方向、状态等 |
| 涂蜡铜块排除 | ✅ | 涂蜡铜块不适用活铜功能 |

### 6.2 待实现

| 特性 | 状态 | 说明 |
|------|------|------|
| 铜块物品栏装饰器渲染 | ⏳ | 类似 LivingRedstoneDecorator 的铜块视觉 |
| 铜块连接纹理 | ⏳ | 铜块之间的连接状态可视化 |
| 跨容器铜块信号 | ⏳ | 铜块信号通过容器边界传播 |