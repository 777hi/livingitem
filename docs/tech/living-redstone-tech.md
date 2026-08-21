<!-- markdownlint-disable -->

# Living Redstone (活红石) 技术文档

> **文档版本**: 2026.08 v10
> **最后更新**: 2026-08-21
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [信号传播算法](#3-信号传播算法)
4. [活红石火把](#4-活红石火把)
5. [活中继器](#5-活中继器)
6. [活比较器](#6-活比较器)
7. [堆叠数与信号强度](#7-堆叠数与信号强度)
8. [与框架的集成](#8-与框架的集成)
9. [实施状态](#9-实施状态)

---

## 1. 架构概览

### 1.1 什么是活红石？

活红石系统在 2D 容器网格（如 9×6 背包、9×3 箱子）中实现了原版红石信号传播的等价逻辑。采用**边信号模型**：信号存储于相邻槽位间的共享边上，活红石粉通过 BFS 传播信号，活红石火把/中继器/比较器作为信号源直接向边写入信号，信号强度受堆叠数量影响。

```
┌──────────────────────────────────────────────────────────────────┐
│                    活红石 边信号传播流程                           │
│                                                                  │
│  ┌──────────────────┐    写边       ┌──────────────────┐        │
│  │ 活红石火把（点亮） │ ──────────→  │ 活红石粉（介质）  │        │
│  │ cap = 信号上限    │  set(slot,   │ 读4边max → 衰减1  │        │
│  │ 排除输入边(3边出) │   RIGHT, 15) │ 写4边 → 邻居入队  │        │
│  └──────────────────┘              └────────┬─────────┘        │
│                                            │ BFS 传播           │
│                                            ▼                    │
│                              ┌──────────────────────────┐      │
│                              │ ContainerRedstoneData     │      │
│                              │   .calculate()            │      │
│                              │   EdgeGrid + 共享边       │      │
│                              │   每 2 tick 执行一次       │      │
│                              └────────┬─────────────────┘      │
│                                       │                         │
│              ┌────────────────────────┼──────────────────┐     │
│              ▼                        ▼                  ▼     │
│        ┌──────────┐           ┌──────────┐       ┌──────────┐ │
│        │ 活红石粉  │           │ 活中继器  │       │ 活比较器  │ │
│        │ 更新信号  │           │ Phase1源  │       │ Phase1源  │ │
│        │ 4边max   │           │ 写方向边  │       │ 写方向边  │ │
│        └──────────┘           └──────────┘       └──────────┘ │
│              ▼                        ▼                  ▼     │
│        ┌──────────┐           ┌──────────┐       ┌──────────┐ │
│        │ 活红石灯  │           │ 活按钮    │       │ 活拉杆    │ │
│        │ anyOfSlot │           │ 长按输出  │       │ 切换输出  │ │
│        └──────────┘           └──────────┘       └──────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingRedstoneFunction` | `domain/redstone/LivingRedstoneFunction.java` | 活红石粉功能入口，实现 `HasContainerData`，触发容器级信号计算 |
| `LivingRedstoneTorchFunction` | `domain/redstone/LivingRedstoneTorchFunction.java` | 活红石火把功能入口，实现 `HasDirection` + `HasContainerData` |
| `LivingButtonFunction` | `domain/redstone/LivingButtonFunction.java` | 活按钮功能，右键长按输出信号 |
| `LivingLeverFunction` | `domain/redstone/LivingLeverFunction.java` | 活拉杆功能，右键切换开关 |
| `LivingRedstoneLampFunction` | `domain/redstone/LivingRedstoneLampFunction.java` | 活红石灯功能，信号消费者 |
| `LivingRepeaterFunction` | `domain/redstone/LivingRepeaterFunction.java` | 活中继器功能，延迟 + 单向 + 信号刷新 |
| `LivingComparatorFunction` | `domain/redstone/LivingComparatorFunction.java` | 活比较器功能，比较/减法 + 物品检测 |
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石信号数据，边信号 BFS 传播算法 |
| `EdgeGrid` | `domain/redstone/ContainerRedstoneData.java` | 共享边信号网格，内嵌类 |
| `LivingRedstoneData` | `domain/redstone/LivingRedstoneData.java` | 活红石粉物品级数据：信号强度 + 是否激活 |
| `LivingRedstoneTorchData` | `domain/redstone/LivingRedstoneTorchData.java` | 活红石火把物品级数据：朝向 + 是否点亮 |
| `LivingButtonData` | `domain/redstone/LivingButtonData.java` | 活按钮数据：是否按下 |
| `LivingLeverData` | `domain/redstone/LivingLeverData.java` | 活拉杆数据：是否激活 |
| `LivingRedstoneLampData` | `domain/redstone/LivingRedstoneLampData.java` | 活红石灯数据：是否点亮 |
| `LivingRepeaterData` | `domain/redstone/LivingRepeaterData.java` | 活中继器数据：方向 + 延迟 + 供电 + 计时器 |
| `LivingComparatorData` | `domain/redstone/LivingComparatorData.java` | 活比较器数据：方向 + 模式 + 供电 |
| `LivingRedstoneDecorator` | `client/render/LivingRedstoneDecorator.java` | 活红石粉物品栏图标装饰器，绘制连接纹理 + 动态着色 |

---

## 2. 数据结构

### 2.1 LivingRedstoneData — 活红石粉物品数据

```java
public record LivingRedstoneData(
    int signalStrength,    // 当前信号强度
    boolean isPowered,     // 是否被信号激活
    byte connections       // 连接状态（位掩码，4 方向）
) implements TooltipProvider {

    public static final byte CONN_UP    = 1 << 0;  // 0b0001
    public static final byte CONN_DOWN  = 1 << 1;  // 0b0010
    public static final byte CONN_LEFT  = 1 << 2;  // 0b0100
    public static final byte CONN_RIGHT = 1 << 3;  // 0b1000

    public static final LivingRedstoneData DEFAULT = new LivingRedstoneData(0, false, (byte)0);

    public LivingRedstoneData withSignal(int strength) {
        return new LivingRedstoneData(strength, isPowered, connections);
    }

    public LivingRedstoneData withPowered(boolean powered) {
        return new LivingRedstoneData(signalStrength, powered, connections);
    }

    public LivingRedstoneData withConnections(byte connections) {
        return new LivingRedstoneData(signalStrength, isPowered, connections);
    }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `signalStrength` | int | 0 | 当前信号强度 |
| `isPowered` | boolean | false | 是否被红石信号激活 |
| `connections` | byte | 0 | 连接状态位掩码，对应 CONN_UP/DOWN/LEFT/RIGHT |

**连接状态位掩码**：每个方向占 1 bit，共 4 个方向。`connections = CONN_UP | CONN_RIGHT` 表示上、右方向有连接。连接状态由 `computeDustConnections` 在 Phase 4 计算，用于 `LivingRedstoneDecorator` 绘制连接纹理。

### 2.2 LivingRedstoneTorchData — 活红石火把物品数据

```java
public record LivingRedstoneTorchData(
    Pos2D direction,    // 朝向（默认 UP）
    boolean isLit       // 是否点亮
) implements TooltipProvider {

    public static final LivingRedstoneTorchData DEFAULT =
        new LivingRedstoneTorchData(Pos2D.UP, true);

    public LivingRedstoneTorchData withDirection(Pos2D dir) {
        return new LivingRedstoneTorchData(dir, isLit);
    }

    public LivingRedstoneTorchData withLit(boolean lit) {
        return new LivingRedstoneTorchData(direction, lit);
    }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `direction` | Pos2D | UP | 火把朝向，决定输出方向和输入方向 |
| `isLit` | boolean | true | 是否点亮（默认点亮，输入端有信号时熄灭） |

### 2.3 ContainerRedstoneData — 容器级信号数据

```java
public class ContainerRedstoneData {
    private static final int PROPAGATION_INTERVAL = 2;  // 每 2 tick 传播一次

    private EdgeGrid edgeGrid;          // 当前帧边信号网格
    private EdgeGrid prevEdgeGrid;      // 上一帧边信号（用于断电检测）
    private int tickCounter;            // tick 计数器（用于传播间隔）
    private boolean processedThisTick;  // 当前 tick 是否已计算
    private long lastTickTime;          // 最后访问时间戳（用于过期清理）

    public ContainerRedstoneData(int size) { ... }
    public int getSignal(int slot) { ... }
    public void calculate(ContainerContext context, TickContext tick) { ... }
    public void resetProcessedFlag() { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `edgeGrid` | EdgeGrid | 当前帧边信号网格，存储所有槽位间边的信号值（纯内部信号） |
| `prevEdgeGrid` | EdgeGrid | 上一帧边信号，Phase 0 用于中继器断电检测 |
| `faceInput[4]` | int[4] | 外部注入信号强度（4 方向），仅用于 BFS 传播，不污染 edgeGrid |
| `faceOutput[4]` | int[4] | 内部信号在 4 个边界面的输出信号，扫描 edgeGrid 边界边得出 |
| `prevFaceOutput[4]` | int[4] | 上一帧 faceOutput，用于变化检测避免不必要的方块更新 |
| `tickCounter` | int | 自增计数器，每 2 tick 触发一次 calculate() |
| `processedThisTick` | boolean | 同一 tick 内多个 Function 触发时，保证只计算一次。每 tick 开始时由 `SimpleContainerContext.setTickContext()` 重置 |
| `lastTickTime` | long | 最后访问时间戳，`ContainerLivingItemHandler.cleanupStaleRedstoneData()` 每 120 秒清理超过 120 秒未访问的条目 |
| `PROPAGATION_INTERVAL` | int (static) | 传播间隔 = 2 tick，与原版红石更新频率一致 |

**持久化机制**：`ContainerRedstoneData` 实例通过 `ContainerLivingItemHandler.REDSTONE_DATA_CACHE`（`LinkedHashMap<String, ContainerRedstoneData>`）静态缓存持久化，以 `containerKey` 为键。`SimpleContainerContext` 每 tick 重建，但其 `getOrCreateRedstoneData()` 从缓存获取同一实例，确保 `edgeGrid`、`prevEdgeGrid`、`tickCounter` 等关键状态跨 tick 保留。`resetProcessedFlag()` 在每 tick 开始时由 `setTickContext()` 调用，确保 `processedThisTick` 被正确重置。

### 2.3.1 EdgeGrid — 共享边信号网格

信号存储在**边**上，而非槽位上。相邻槽位共享同一条边：

```
对于 W×H 的槽位网格：
  hEdges[height * (width + 1)] — 水平边（含左右边界）
  vEdges[(height + 1) * width] — 垂直边（含上下边界）

槽位 (r,c) 的 4 条边：
  LEFT  → hEdges[r * (W+1) + c]
  RIGHT → hEdges[r * (W+1) + (c+1)]
  UP    → vEdges[r * W + c]
  DOWN  → vEdges[(r+1) * W + c]
```

**共享边示例**：槽位 (0,0) 的 RIGHT 边 = 槽位 (0,1) 的 LEFT 边，是同一个数组条目。因此 A 写自己的 RIGHT 边，B 读自己的 LEFT 边自动拿到同一个值，无需显式同步。

**边界边**：容器边缘的边（如最左列 LEFT 边、最右列 RIGHT 边）也存储，为跨容器信号传输预留。

**高度计算**：`height = (size + width - 1) / width`（向上取整），兼容最后一行不满的容器（如玩家背包 41 槽 = 9×5 最后一行仅 5 槽）。

**边常量**：
```java
E_UP = 0, E_DOWN = 1, E_LEFT = 2, E_RIGHT = 3
opposite(dir) = dir ^ 1  // 0↔1, 2↔3
edgeIndex(Pos2D) → 将 Pos2D 方向转为边索引
```

**EdgeGrid API**（静态内部类，纯内部信号传播）：
| 方法 | 作用 |
|------|------|
| `get(slot, dir)` | 读槽位某方向的边，边界返回 0 |
| `set(slot, dir, value)` | 写槽位某方向的边（纯内部，不涉及外部交互） |
| `maxOfSlot(slot)` | 4 边最大值 |
| `anyOfSlot(slot)` | 任一边 > 0 |
| `zero()` | 清零所有边 |

### 2.4 LivingButtonData — 活按钮物品数据

```java
public record LivingButtonData(
    boolean pressed     // 是否按下
) implements TooltipProvider {

    public static final LivingButtonData DEFAULT = new LivingButtonData(false);

    public LivingButtonData withPressed(boolean p) {
        return new LivingButtonData(p);
    }
}
```

### 2.5 LivingLeverData — 活拉杆物品数据

```java
public record LivingLeverData(
    boolean active      // 是否激活
) implements TooltipProvider {

    public static final LivingLeverData DEFAULT = new LivingLeverData(false);

    public LivingLeverData withActive(boolean a) {
        return new LivingLeverData(a);
    }
}
```

### 2.6 LivingRedstoneLampData — 活红石灯物品数据

```java
public record LivingRedstoneLampData(
    boolean lit         // 是否点亮
) implements TooltipProvider {

    public static final LivingRedstoneLampData DEFAULT = new LivingRedstoneLampData(false);

    public LivingRedstoneLampData withLit(boolean l) {
        return new LivingRedstoneLampData(l);
    }
}
```

### 2.7 LivingRepeaterData — 活中继器物品数据

```java
public record LivingRepeaterData(
    Pos2D direction,    // 输出方向
    int delay,          // 延迟档位 1-4（红石刻 = 2 game tick）
    boolean powered,    // 是否接收到输入信号
    int delayTimer      // 剩余延迟刻数（0=就绪，到期输出）
) implements TooltipProvider {

    public static final LivingRepeaterData DEFAULT =
        new LivingRepeaterData(Pos2D.RIGHT, 1, false, 0);

    public LivingRepeaterData withDirection(Pos2D dir) { ... }
    public LivingRepeaterData withDelay(int d) { ... }
    public LivingRepeaterData withPowered(boolean p) { ... }
    public LivingRepeaterData withDelayTimer(int t) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `direction` | Pos2D | RIGHT | 输出方向，决定信号传播方向 |
| `delay` | int | 1 | 延迟档位，1-4 红石刻 |
| `powered` | boolean | false | 输入端是否有信号 |
| `delayTimer` | int | 0 | 剩余延迟刻数，0 表示就绪，>0 等待中 |

### 2.8 LivingComparatorData — 活比较器物品数据

```java
public record LivingComparatorData(
    Pos2D direction,        // 输出方向
    boolean subtractMode,   // true=减法模式，false=比较模式
    boolean powered         // 是否接收到输入信号
) implements TooltipProvider {

    public static final LivingComparatorData DEFAULT =
        new LivingComparatorData(Pos2D.RIGHT, false, false);

    public LivingComparatorData withDirection(Pos2D dir) { ... }
    public LivingComparatorData withSubtractMode(boolean sm) { ... }
    public LivingComparatorData withPowered(boolean p) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `direction` | Pos2D | RIGHT | 输出方向 |
| `subtractMode` | boolean | false | 比较模式（false）/ 减法模式（true） |
| `powered` | boolean | false | 输入端是否有信号 |

### 2.9 存储结构

活红石物品的数据存储在 ItemStack 的 DataComponent 中：

```
ItemStack (minecraft:redstone)
├── IS_LIVING: true                              ← 活物品标记
└── LIVING_REDSTONE_DATA: LivingRedstoneData     ← 信号状态
    ├─ signalStrength: int                       ← 当前信号强度
    └─ isPowered: boolean                        ← 是否激活

ItemStack (minecraft:redstone_block)
├── IS_LIVING: true                              ← 活物品标记
└── （无额外数据）                                ← 常亮信号源，无需状态数据

ItemStack (minecraft:redstone_torch)
├── IS_LIVING: true
└── LIVING_REDSTONE_TORCH_DATA: LivingRedstoneTorchData
    ├─ direction: Pos2D                          ← 朝向（UP/DOWN/LEFT/RIGHT）
    └─ isLit: boolean                            ← 是否点亮

ItemStack (minecraft:repeater)
├── IS_LIVING: true
└── LIVING_REPEATER_DATA: LivingRepeaterData
    ├─ direction: Pos2D                          ← 输出方向
    ├─ delay: int                                ← 延迟档位 1-4
    ├─ powered: boolean                          ← 是否有输入信号
    └─ delayTimer: int                           ← 剩余延迟刻数

ItemStack (minecraft:comparator)
├── IS_LIVING: true
└── LIVING_COMPARATOR_DATA: LivingComparatorData
    ├─ direction: Pos2D                          ← 输出方向
    ├─ subtractMode: boolean                     ← 比较/减法模式
    └─ powered: boolean                          ← 是否有输入信号
```

---

## 3. 信号传播算法

### 3.1 触发时机

信号传播由 `ContainerRedstoneData.calculate()` 执行，每 **2 tick** 触发一次（与原版红石更新频率一致）。传播由 `LivingRedstoneFunction` 和 `LivingRedstoneTorchFunction` 的 `tickContainerData()` 方法触发，两者均通过 `HasContainerData` 接口（优先级 2）被容器处理器调用。

### 3.2 六阶段边信号传播算法

`calculate()` 拆分为 6 个阶段，基于边信号模型：

```
calculate(context, tick):
  1. processedThisTick 去重检查
  2. tickCounter++，若 tickCounter % 2 != 0 → 直接返回（跳过）
  3. 收集所有红石组件槽位（torch/dust/button/lever/lamp/repeater/comparator/redstoneBlock）
  4. 若全部为空 → 直接返回
  5. 重置：swap edgeGrid ↔ prevEdgeGrid，清零 edgeGrid

  phase0CountdownDelays()    — 倒计时 + 断电检测（用 prevEdgeGrid 的边）
  phase1CollectSources()     — 信号源直接写边，红石粉邻居入队
  phase2Propagation()        — BFS 传播（仅红石粉入队）
  phase4PowerConductors()    — 信号源向导电活物品充能，触发第二波 BFS（先于 Phase 3 执行，确保导体信号可被中继器/比较器读取）
  phase3RecheckInputs()      — 重新检测级联输入（中继器/比较器），比较器写回边网格
  phase5UpdateDisplay()      — 更新物品显示状态（火把/灯/红石粉）

  注入点在 Phase 0 之前：
  injectExternalInputs()     — 读取外部红石信号，注入边界边（复用 CrossContainerTransfer 方向映射）
  输出通过 Mixin 完成：
  BlockStateBase.getSignal()           — 弱信号：红石粉可读（返回 getBoundarySignal()）
  BlockStateBase.getDirectSignal()     — 强信号：中继器/比较器/火把可读（返回 getBoundarySignal()）
  RedStoneWireBlock.getConnectingSide() — 让红石粉连接容器方块（注入 SIDE 返回值）
```

**与旧模型（槽位信号）的核心区别**：
- 信号存储在**共享边**上，A 的 RIGHT 边 = B 的 LEFT 边，无需显式同步
- 信号源**不入队**，直接写边；BFS 队列**仅包含红石粉**
- 每个组件通过**读写特定边**实现方向性输入/输出

**Phase 0 — 倒计时 + 断电检测**：
```
遍历中继器：
  if !powered → skip
  if delayTimer == 0 → skip（无需倒计时）
  inputDir = edgeIndex(data.direction().opposite())  // 输入方向边
  hasInput = prevEdgeGrid.get(slot, inputDir) > 0     // 用上一帧内部边信号
  // 当槽位在容器边界且输入方向指向该面时，额外检查 faceInput[dir]
  if slot 在边界 && inputDir 指向该面 → hasInput |= faceInput[dir] > 0

  if delayTimer > 0（ON_DELAY，等待开启）：
    if !hasInput → powered = false, delayTimer = 0（取消延迟）
    else → delayTimer--（继续倒计时）
  else（delayTimer < 0，OFF_DELAY，等待关闭）：
    delayTimer++（向 0 递增）
    if delayTimer == 0 → powered = false（延迟结束，关闭）

遍历按钮：
  if pressed && pulseTimer > 0 → pulseTimer -= 2
  if pulseTimer <= 0 → pressed = false
```

**delayTimer 语义**：利用正负号区分两种延迟方向，无需修改数据记录结构：
| delayTimer | 状态 | 输出信号 | 说明 |
|-----------|------|---------|------|
| > 0 | ON_DELAY | 否 | 输入已出现，等待延迟后开启输出 |
| = 0 | ON | 是 | 正常输出信号 |
| < 0 | OFF_DELAY | **是** | 输入已消失，等待延迟后关闭输出（期间继续输出） |

**Phase 1 — 收集信号源**：
```
信号源直接向自己的边写入信号，不进入队列。
只有红石粉邻居才入队。

遍历火把：
  if !isLit → skip
  cap = getSignalCap(count)
  skipDir = edgeIndex(data.direction().opposite())  // 不输出到输入边
  for 4 方向 dir：
    if dir == skipDir → continue（排除输入边）
    if cap > edgeGrid.get(slot, dir)：
      edgeGrid.set(slot, dir, cap)          // 写自己该方向的边
      if 邻居是红石粉 → 邻居入队

遍历按钮/拉杆：
  if 未激活 → skip
  cap = getSignalCap(count)
  for 4 方向 dir：
    if cap > edgeGrid.get(slot, dir)：
      edgeGrid.set(slot, dir, cap)
      if 邻居是红石粉 → 邻居入队

遍历中继器：
  if !powered || delayTimer > 0 → skip
  cap = getSignalCap(count)
  outDir = edgeIndex(data.direction())      // 仅输出方向
  if cap > edgeGrid.get(slot, outDir)：
    edgeGrid.set(slot, outDir, cap)
    if 邻居是红石粉 → 邻居入队

遍历比较器：
  output = computeComparatorOutput()
  if output <= 0 → skip
  outDir = edgeIndex(data.direction())
  if output > edgeGrid.get(slot, outDir)：
    edgeGrid.set(slot, outDir, output)
    if 邻居是红石粉 → 邻居入队

遍历红石块：
  cap = getSignalCap(count)
  for 4 方向 dir：
    if cap > edgeGrid.get(slot, dir)：
      edgeGrid.set(slot, dir, cap)
      if 邻居是红石粉 → 邻居入队

返回 BFS 队列（仅含红石粉）
```

**Phase 2 — BFS 传播**：
```
while queue not empty:
  current = queue.poll()
  if 不是红石粉 → continue

  r = current / width, c = current % width

  maxInput = edgeGrid.maxOfSlot(current)  // 4 内部边取最大值
  if r == 0:          maxInput = max(maxInput, faceInput[E_UP])     // 上边界含外部信号
  if r == height - 1: maxInput = max(maxInput, faceInput[E_DOWN])   // 下边界含外部信号
  if c == 0:          maxInput = max(maxInput, faceInput[E_LEFT])   // 左边界含外部信号
  if c == width - 1:  maxInput = max(maxInput, faceInput[E_RIGHT])  // 右边界含外部信号

  if maxInput <= 1 → continue

  output = min(maxInput - 1, getSignalCap(自身堆叠数))

  for 4 方向 dir：
    neighbor = resolveSlot(current, dir)
    isTarget = neighbor 是红石目标（粉/中继器/比较器/火把/灯）
    if !isTarget && neighbor 存在 → continue

    if output > edgeGrid.get(current, dir)：
      edgeGrid.set(current, dir, output)    // 写自己的边
      if 邻居是红石粉 → 邻居入队
```

**Phase 3 — 重新检测输入**：
```
遍历中继器：
  if powered && delayTimer > 0 → 跳过（ON_DELAY 进行中，由 Phase 0 处理）
  inputDir = edgeIndex(data.direction().opposite())
  hasInput = edgeGrid.get(slot, inputDir) > 0  // 用当前帧边信号

  // 四种过渡：
  if hasInput && !powered → powered = true, delayTimer = delay（OFF → ON_DELAY）
  if hasInput && powered && delayTimer < 0 → delayTimer = 0（OFF_DELAY → ON，输入恢复）
  if !hasInput && powered && delayTimer == 0 → delayTimer = -delay（ON → OFF_DELAY）

遍历比较器：
  output = computeComparatorOutput()
  powered = (output > 0)
```

**Phase 4 — 充能导电活物品**：

```
phase4PowerConductors(torchSlots, buttonSlots, leverSlots,
    repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, ...):

  allRedstone = 所有红石组件槽位的并集（用于排除红石组件自身）
  secondQueue = 空队列

  # 红石粉：按 connections 方向输出，衰减后信号
  for dustSlots：
    maxInput = edgeGrid.maxOfSlot(slot)
    if maxInput <= 1 → continue
    output = min(maxInput - 1, getSignalCap(count))
    conn = LivingItemManager.getRedstoneData(stack).connections()
    for 4 方向 dir：
      if conn 中 dir 无连接 → continue
      neighbor = resolveSlot(slot, dir)
      if neighbor < 0 || allRedstone.contains(neighbor) → continue
      if !isConductiveBlock(neighbor) → continue
      # 充能：向导体的所有边写入信号
      for 4 方向 d2：
        if output > edgeGrid.get(neighbor, d2)：
          edgeGrid.set(neighbor, d2, output)
          if resolveSlot(neighbor, d2) 是红石粉 → secondQueue.add(n2)

  # 火把：除输入方向外全方向输出
  for torchSlots：
    if !isLit → continue
    cap = getSignalCap(count)
    skipDir = edgeIndex(data.direction().opposite())
    for 4 方向 dir：
      if dir == skipDir → continue
      ... 同上充能逻辑 ...

  # 按钮/拉杆/红石块：全方向输出
  for buttonSlots/leverSlots/redstoneBlockSlots：
    if 未激活 → continue
    cap = getSignalCap(count)
    for 4 方向 dir：
      ... 同上充能逻辑 ...

  # 中继器：仅输出方向
  for repeaterSlots：
    if !powered || delayTimer > 0 → continue
    cap = getSignalCap(count)
    outDir = edgeIndex(data.direction())
    ... 同上充能逻辑 ...

  # 比较器：仅输出方向，信号为计算结果
  for comparatorSlots：
    output = computeComparatorOutput()
    if output <= 0 → continue
    outDir = edgeIndex(data.direction())
    ... 同上充能逻辑 ...

  # 第二波 BFS：充能导体后，相邻红石粉重新传播
  if secondQueue not empty：
    phase2Propagation(secondQueue, dustSlots, ...)
```

**导体判定**（`isConductiveBlock`）：仅活物品可被充能，复用原版 `isRedstoneConductor` 判定导电性：

```java
private static boolean isConductiveBlock(ItemStack stack) {
    if (stack.getItem() instanceof BlockItem blockItem) {
        return LivingItemManager.isLivingItem(stack)
            && blockItem.getBlock().defaultBlockState()
                .isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
    return false;
}
```

**判定顺序**：`BlockItem` → `isLivingItem` → `isRedstoneConductor`。非活物品即使原版是导体（如普通箱子）也不会被充能。

**充能规则**：导体不衰减信号，红石粉衰减 -1。中继器/比较器通过边缘网格自动读取导体边的信号，无需特殊处理。

| 信号源 | 输出方向 | 信号值 |
|--------|---------|--------|
| 红石粉 | connections 方向 | min(maxInput-1, cap) |
| 火把 | 除输入方向外 3 方向 | cap |
| 按钮/拉杆 | 4 方向 | cap |
| 红石块 | 4 方向 | cap |
| 中继器 | 朝向方向 | cap |
| 比较器 | 朝向方向 | 计算结果 |

**充能示例**：

```
[火把(15)] [红石粉A] [活箱子] [红石粉B]
              conn:      ──充能──→  conn:
              LEFT+RIGHT  所有边=14  LEFT+RIGHT

Phase 1: 火把 → 边缘[火把, UP]=15
Phase 2: BFS: 红石粉A 读取 15 → 输出 14 → 边缘[A, RIGHT]=14
Phase 4: 红石粉A 的 RIGHT 有连接 → 邻居是活箱子（导体）→ 活箱子 所有边=14
         → 活箱子 RIGHT 邻接红石粉B → 红石粉B 入 secondQueue
         第二波 BFS: 红石粉B 读取 14 → 输出 13 → 继续传播
Phase 5: 更新显示
```

**Phase 5 — 更新显示状态**：
```
遍历火把：
  inputDir = edgeIndex(data.direction().opposite())
  hasInput = edgeGrid.get(slot, inputDir) > 0
  newLit = !hasInput  // 反相：有输入→熄灭
  if isLit != newLit → 更新同步

遍历红石粉：
  maxSignal = edgeGrid.maxOfSlot(slot)
  conn = computeDustConnections(slot, ...)  // 计算连接状态
  if signalStrength != maxSignal || isPowered != (maxSignal > 0) || connections != conn → 更新同步

遍历红石灯：
  hasSignal = edgeGrid.anyOfSlot(slot)
  if lit != hasSignal → 更新同步
```

**Phase 5 之后 — 计算面输出（computeFaceOutput）**：
```
扫描 edgeGrid 的 4 条边界边，取每边最大值作为 faceOutput：
  faceOutput[E_UP]    = max(vEdges[0 .. width-1])
  faceOutput[E_DOWN]  = max(vEdges[height*width .. height*width+width-1])
  faceOutput[E_LEFT]  = max(hEdges[0, width+1, 2*(width+1)...])
  faceOutput[E_RIGHT] = max(hEdges[width, 2*width+1, ...])

仅包含内部信号（edgeGrid 不再存储外部信号），
避免外部信号被误输出导致反馈循环。
```

**设计要点**：
- **共享边**：源写自己的边，目标通过共享边自动读到，无需 `neighbor * 4 + opposite(dir)` 这种间接映射
- **BFS 仅红石粉**：队列只包含红石粉，信号源和终端不参与传播循环
- **边界边持久化**：容器边缘边也存储信号值，为跨容器信号传输预留接口
- **向上取整高度**：`(size + width - 1) / width` 兼容非满行容器（如玩家背包 41 槽）
- **导体充能**：Phase 4 通过 `isRedstoneConductor` 自动判定所有 BlockItem 的导电性，不依赖功能注册。中继器/比较器通过共享边缘网格自动读取导体的信号，无需特殊处理
- **第二波 BFS**：充能导体后触发第二轮 BFS，确保信号穿过导体继续在红石粉中传播

### 3.2.1 容器内外红石交互（面信号模型）

容器内红石信号可与外部世界红石双向交互。采用**面信号模型**：容器的 4 个水平面各有一个输入通道和一个输出通道，对标原版 `getSignal(face)` 语义。

#### 核心数据结构

```
ContainerRedstoneData
├── edgeGrid              — 边信号网格（纯内部信号，外部信号不写入）
├── faceInput[4]          — 每面的外部输入信号（4 方向）
├── faceOutput[4]         — 每面的内部输出信号（扫描 edgeGrid 边界边得出）
├── prevFaceOutput[4]     — 上一帧面输出（用于变化检测）
└── prevEdgeGrid           — 上一帧边信号（用于断电检测）
```

**面信号模型核心思想**：`edgeGrid` 回归纯粹的内部传播角色，`faceInput` / `faceOutput` 作为容器面的抽象，对标原版的 `getSignal(face)`：

| 数据 | 内容 | 用途 |
|------|------|------|
| `edgeGrid` | 纯内部信号 | 内部槽位间信号传播 |
| `faceInput[4]` | 外部注入信号 | BFS 边界槽位补充输入 |
| `faceOutput[4]` | 内部信号输出 | 对外暴露（getBoundarySignal） |

#### 输入：外部信号注入（injectExternalInputs）

在 `calculate()` 开始时（Phase 0 之前），遍历容器**所有关联方块位置**（大箱子遍历两个半箱），读取四周的红石信号：

```
injectExternalInputs(context):
  1. 获取关联方块位置列表（大箱子 = 两个半箱）
  2. 对每个位置：
     a. 获取方块的朝向（facing）
     b. 遍历 4 个世界方向（NORTH/SOUTH/WEST/EAST）
     c. 读取邻居方块的信号: level.getSignal(pos.relative(worldDir), worldDir)
     d. CrossContainerTransfer.worldToGrid(worldDir, facing) → 内部网格方向
     e. faceInput[internalDir] = max(faceInput[internalDir], signal)
  3. 仅记录到 faceInput，不写入 edgeGrid
```

**关键变化**：外部信号**不再写入 edgeGrid**，仅存储在 `faceInput[4]` 中。`edgeGrid` 保持纯内部信号，从根本上避免外部信号污染输出。

#### 内部传播：BFS 融入外部信号

在 `phase2Propagation` 中，边界槽位的 `maxInput` 计算额外纳入 `faceInput`：

```
边界红石粉槽位：
  maxInput = edgeGrid.maxOfSlot(slot)   // 3 条内部边
  + faceInput[dir]  // 补充外部信号

非边界槽位：
  maxInput = edgeGrid.maxOfSlot(slot)   // 4 条内部边（正常）
```

`seedBoundaryDust` 也改为检查 `faceInput[dir] > 0`，将边界红石粉槽位加入 BFS 队列。

#### 输出：内部信号传出（computeFaceOutput）

Phase 5 结束后，新增 `computeFaceOutput` 阶段：

```
computeFaceOutput(width, height):
  扫描 edgeGrid 的 4 条边界边，取每边最大值：
    faceOutput[E_UP]    = max(vEdges[0 .. width-1])
    faceOutput[E_DOWN]  = max(vEdges[height*width .. height*width+width-1])
    faceOutput[E_LEFT]  = max(hEdges[0, width+1, 2*(width+1)...])
    faceOutput[E_RIGHT] = max(hEdges[width, 2*width+1, ...])

  仅包含内部信号（edgeGrid 不存储外部信号），
  避免外部信号被误输出导致反馈循环。
```

**输出路径**：

```
内部信号源（火把/红石块/红石粉等）
  → phase1-5 传播 → edgeGrid 边界边
  → computeFaceOutput → faceOutput[dir]
  → getBoundarySignal(dir) → faceOutput[dir]
  → BlockStateBaseMixin.onGetSignal（Mixin 注入）
  → 容器方块对外输出红石信号
```

#### 反馈循环防护

**面信号模型的天然优势**：输入和输出是完全分离的两个通道。

```
外部输入 → faceInput[4]（仅用于 BFS 传播）
                              │
内部信号源 → edgeGrid（纯内部） → computeFaceOutput → faceOutput[4] → 对外输出
                              │
          faceInput 不会影响 faceOutput！
```

不再需要之前复杂的 `injectBoundarySignal` 绕过 `set()`、`boundaryOutput` 与 `edgeGrid` 边界边解耦等机制。`EdgeGrid` 回归**静态内部类**，`set()` 方法回归**纯数组写入**。

#### 信号变化通知（notifyBoundaryChange）

对比 `faceOutput` 与 `prevFaceOutput`，仅在变化时通知**所有关联方块**的邻居（大箱子通知两个半箱）：

```
notifyBoundaryChange(context, width, height):
  for 4 方向 dir:
    if faceOutput[dir] != prevFaceOutput[dir] → changed = true
    prevFaceOutput[dir] = faceOutput[dir]

  if changed:
    for pos in context.getAssociatedBlockPositions():  // 大箱子遍历两个半箱
      level.updateNeighborsAt(pos, state.getBlock())
```

#### 红石粉连接判定（RedStoneWireBlockMixin）

Minecraft 原版红石粉不会主动连接容器方块。通过 `RedStoneWireBlockMixin` 注入 `getConnectingSide` 方法，当邻居是容器方块时强制返回 `RedstoneSide.SIDE`，使外部红石粉能连接到容器读取信号。

#### 方向映射

复用 `CrossContainerTransfer` 的方向映射系统，自动处理容器朝向旋转：

```
输出方向：direction.getOpposite() → worldToGrid → 内部网格方向
  （BlockStateBaseMixin 中，getSignal(direction) 的 direction 是从调用者到容器，
   getOpposite() 翻转后得到从容器的哪个面输出）
输入方向：worldDir → worldToGrid → 内部网格方向
  （injectExternalInputs 中，worldDir 是从容器到邻居）
```

大箱子（CompoundContainer）左右半箱合并为无缝网格，6 个物理面自然映射为 4 个逻辑边界方向。

#### 跨容器传输

面信号模型天然支持相邻容器间的红石信号传输：

```
Container A 内部红石 → computeFaceOutput → faceOutput[dir] = 15
  → BlockStateBaseMixin: getSignal(A, worldDir) 返回 15
  → Container B: injectExternalInputs → faceInput[dir] = 15
  → seedBoundaryDust → BFS → Container B 内部传播
```

方向映射双向一致，`faceInput` / `faceOutput` 通道分离天然防止反馈循环。

##### 跨容器直读机制（超限信号传输）

原版 `level.getSignal()` 读取方块信号时受限于 0-15 强度上限，导致相邻容器间无法传输超过 15 的信号（如堆叠 4 火把输出的 16 强度信号会被截断为 15）。

**解决方案**：`injectExternalInputs` 检测邻居方块是否为容器，若是则直接读取其 `ContainerRedstoneData.faceOutput`，绕过原版 `BlockState` 的 0-15 截断：

```
injectExternalInputs(context):
  对每个关联方块位置：
    遍历 4 个世界方向：
      neighborPos = pos.relative(worldDir)
      neighborState = level.getBlockState(neighborPos)

      if neighborState 是容器方块：
        neighborData = ContainerLivingItemHandler.getRedstoneDataByPos(neighborPos)
        if neighborData != null：
          neighborFacing = CrossContainerTransfer.getBlockFacing(neighborState)
          neighborGridDir = CrossContainerTransfer.worldToGrid(worldDir.getOpposite(), neighborFacing)
          if neighborGridDir 有效：
            signal = max(signal, neighborData.getBoundarySignal(neighborInternalDir))
      else：
        signal = level.getSignal(neighborPos, worldDir)  // 原版路径（0-15）

      faceInput[internalDir] = max(faceInput[internalDir], signal)
```

**关键**：`worldDir.getOpposite()` 翻转方向——从容器的视角看向邻居，翻转为从邻居的视角看向容器，从而正确读取邻居对面那个面的 `faceOutput`。

**效果**：堆叠 4 活红石火把（信号上限 16）放在容器 A 边界 → `faceOutput[RIGHT] = 16` → 相邻容器 B 直读 → `faceInput[LEFT] = 16` → 容器 B 内部红石粉获得 16 强度信号，超限传输成功。

##### 相邻容器边界反馈环特性

当两个相邻容器的相邻边界上都有活红石粉时，会形成自然的信号衰减反馈环：

```
容器 A 边界红石粉（信号 15） → faceOutput[RIGHT] = 14（衰减 -1）
  → 容器 B 直读 faceOutput → faceInput[LEFT] = 14
  → 容器 B 边界红石粉获得 14 强度 → faceOutput[LEFT] = 13（衰减 -1）
  → 容器 A 直读 faceOutput → faceInput[RIGHT] = 13
  → 容器 A 边界红石粉获得 13 强度 → faceOutput[RIGHT] = 12
  → ... 循环衰减，每 tick 信号 -1
```

**原因**：面信号模型下，容器的 `faceOutput` 被相邻容器读取为 `faceInput`，经红石粉衰减 -1 后输出为 `faceOutput-1`，形成衰减反馈环。

**处理**：此现象为面信号模型与跨容器直读机制的自然结果，**保留为特性**而非 bug。实际使用中，相邻容器边界通常不会同时放置红石粉（一端是信号源如中继器/火把，另一端是红石粉），因此反馈环不会触发。若确实需要在相邻容器边界放置红石粉，信号会自然衰减至 0，不会造成死循环或性能问题。

### 3.3 邻居计算

```java
// ContainerRedstoneData.resolveSlot(slot, dir, size, width)
// 返回指定方向的邻居槽位索引，越界返回 -1
// 注意：resolveSlot 仅用于判断"邻居是否存在"和"邻居类型"，
// 信号值本身通过 EdgeGrid 的共享边获取，无需通过邻居索引

private static int resolveSlot(int slot, int dir, int size, int width) {
    int col = slot % width;
    int row = slot / width;
    switch (dir) {
        case E_UP:    row--; break;
        case E_DOWN:  row++; break;
        case E_LEFT:  col--; break;
        case E_RIGHT: col++; break;
    }
    if (col < 0 || col >= width || row < 0) return -1;
    int result = row * width + col;
    return result < size ? result : -1;
}
```

### 3.4 信号传播示例

```
9×4 容器中的信号传播（假设所有红石粉堆叠数为 1）：

初始状态：
  [火把] [粉A] [粉B] [粉C] [  ] [  ] [  ] [  ] [  ]
  [  ]   [  ]  [  ]  [  ]  [  ] [  ] [  ] [  ] [  ]

Phase 1：火把写自己的 RIGHT 边 = 15
          粉A 的 LEFT 边（共享边）自动 = 15
          粉A 入队

Phase 2 BFS：
  粉A：maxInput = 15（来自 LEFT 边）
       output = min(15-1, 15) = 14
       写 RIGHT 边 = 14，粉B 的 LEFT 边自动 = 14
       粉B 入队
  粉B：maxInput = 14
       output = min(14-1, 15) = 13
       写 RIGHT 边 = 13，粉C 的 LEFT 边自动 = 13
       粉C 入队
  粉C：maxInput = 13
       output = 12
       写 RIGHT 边 = 12
       邻居为空，不继续

最终结果（每条红石粉读 4 边 max）：
  [火把] [粉A] [粉B] [粉C] [  ] [  ] [  ] [  ] [  ]
   15    14    13    12    0    0    0    0    0
```

**关键**：信号值存储在边上，粉A 读自己的 LEFT 边直接拿到火把写的值，不需要通过邻居槽位索引再查一次数组。

### 3.5 连接状态计算（computeDustConnections）

`computeDustConnections` 在 Phase 4 中为每个活红石粉计算 4 方向的连接状态，结果写入 `LivingRedstoneData.connections` 位掩码，供 `LivingRedstoneDecorator` 绘制连接纹理。

**设计原则**：参照原版 `RedStoneWireBlock.shouldConnectTo` 方法，对中继器/比较器进行方向感知连接判断。

**连接规则**：

| 邻居类型 | 连接条件 |
|---------|---------|
| 活红石粉、活红石灯、活红石火把、活按钮、活拉杆、活红石块 | 无条件连接 |
| 活中继器、活比较器 | 仅当红石粉方向与中继器/比较器的输出方向或输入方向（反方向）一致时连接 |

**方向感知逻辑**（参照原版 `shouldConnectTo`）：
```java
// 中继器/比较器：仅轴线方向连接
if (repeaterSlots.contains(neighbor) || comparatorSlots.contains(neighbor)) {
    Pos2D facing = /* 获取中继器/比较器的方向 */;
    Pos2D d = DIR_POS[dir];  // 红石粉 → 邻居的方向
    if (d.equals(facing) || d.equals(facing.opposite())) {
        conn |= (1 << dir);  // 方向匹配才连接
    }
}
```

**示例**：中继器指向右（输出方向 = RIGHT），红石粉在其左侧。红石粉 → 中继器的方向 = RIGHT，与中继器的输出方向匹配 → 连接。红石粉在其上方 → 方向 = UP，与 RIGHT 不匹配 → 不连接。

**DIR_POS 映射**：`dir` 0=上→UP, 1=下→DOWN, 2=左→LEFT, 3=右→RIGHT。

**自动补全连接**（参照原版 `getConnectionState`）：

计算完实际连接后，应用自动补全规则：如果某一整条轴完全没有连接，就把那条轴的两端都补上。`conn == 0`（点状）时跳过补全。

```java
if (conn != 0) {
    boolean hasUp = (conn & 1) != 0, hasDown = (conn & 2) != 0;
    boolean hasLeft = (conn & 4) != 0, hasRight = (conn & 8) != 0;

    boolean noVertical = !hasUp && !hasDown;    // 上下轴无连接
    boolean noHorizontal = !hasLeft && !hasRight; // 左右轴无连接

    if (!hasLeft && noVertical)  conn |= 4;   // 无上下 → 补左右
    if (!hasRight && noVertical) conn |= 8;
    if (!hasUp && noHorizontal)  conn |= 1;   // 无左右 → 补上下
    if (!hasDown && noHorizontal) conn |= 2;
}
```

**自动补全示例**：

| 实际连接 | 补全后 | 形状 |
|---------|--------|------|
| UP（仅上方有火把） | UP + DOWN | 直线 |
| LEFT（仅左边有红石粉） | LEFT + RIGHT | 直线 |
| UP + LEFT（拐角） | UP + LEFT | 拐角（不变） |
| UP + RIGHT + LEFT（T形） | UP + RIGHT + LEFT | T形（不变） |
| 无连接 | 无连接 | 点（不补全） |

---

## 4. 活红石火把

### 4.1 双重身份

活红石火把同时是**信号源**和**反相器**：

| 角色 | 行为 |
|------|------|
| 信号源 | 点亮时输出 `count × 15` 强度信号，向所有方向传播 |
| 反相器 | 输入端有信号 → 熄灭（不输出）；输入端无信号 → 点亮（输出） |

输入端 = 朝向的**反方向**，通过 `Pos2D.opposite()` 计算：

```java
// Pos2D.opposite() — 框架原语
public Pos2D opposite() {
    if (this == UP)    return DOWN;
    if (this == DOWN)  return UP;
    if (this == LEFT)  return RIGHT;
    if (this == RIGHT) return LEFT;
    return this.negate();
}
```

调用方式：`data.direction().opposite()`，不再需要各 Function 类中重复定义的 `getInputDirection()`。

### 4.2 朝向配置

火把朝向通过 `HasDirection` 接口实现，由 WASD 按键配置：

```
拿起活红石火把悬浮在活按钮上 → 按 W/A/S/D → 切换朝向

W → 朝上（默认）
S → 朝下
A → 朝左
D → 朝右
```

```java
// 不再需要！已由 Pos2D.opposite() 替代
// LivingRedstoneTorchFunction 中：
//   inputDir = data.direction().opposite()
```

### 4.3 反相器逻辑

```
Phase 4 中火把状态更新：

for each torch slot:
    inputDir = edgeIndex(data.direction().opposite())
    hasInputSignal = edgeGrid.get(slot, inputDir) > 0  // 读输入边

    newLit = !hasInputSignal  // 反相：有输入 → 熄灭，无输入 → 点亮

    if isLit != newLit:
        setTorchData(stack, data.withLit(newLit))
        syncSlotToClients(slot, stack)
```

### 4.4 火把独立存在

当容器中只有活红石火把（没有活红石粉）时，`LivingRedstoneTorchFunction` 自身的 `tickContainerData()` 也会触发 `ContainerRedstoneData.calculate()`。此时火把不需要红石粉中介，直接代表信号源的存在——点亮状态表示"有信号在输出"，但因为没有红石粉作为传输介质，信号不会在容器中传播。

---

## 5. 活中继器

### 5.1 功能概述

活中继器是**延迟 + 单向 + 信号刷新**的组合器件，与原版中继器行为一致，但输出信号强度与其堆叠数相关。

### 5.2 核心逻辑

活中继器的处理分布在 Phase 0、Phase 1 和 Phase 3 中：

```
Phase 0（倒计时 + 断电）：
  1. 检测输入边（后方）prevEdgeGrid.get(slot, inputDir) > 0
  2. hasInput → 继续倒计时
  3. !hasInput → 立即断电（powered = false, delayTimer = 0）

Phase 1（作为信号源输出）：
  1. powered && delayTimer == 0 → 向输出方向边写入信号
  2. 输出值 = getSignalCap(count)（自己的堆叠数满信号）
  3. 若输出方向邻居是红石粉 → 邻居入队

Phase 3（重新检测输入）：
  1. 已被 Phase 1 输出的中继器跳过
  2. 其他中继器：检测 edgeGrid.get(slot, inputDir) > 0
  3. hasInput && !powered → powered = true, delayTimer = delay
  4. !hasInput && powered → powered = false, delayTimer = 0
```

**关键变化**：中继器不再参与 Phase 2 BFS 传播。它在 Phase 1 作为信号源直接向输出边写入信号，红石粉在 Phase 2 通过 BFS 传播该信号。这简化了中继器的处理逻辑，使其与火把、按钮、拉杆等信号源行为一致。

### 5.3 延迟档位

| 档位 | delay | 实际延迟（game tick） | 说明 |
|------|-------|---------------------|------|
| 1 | 1 | 2 | 最小延迟，与原版一致 |
| 2 | 2 | 4 | 中等延迟 |
| 3 | 3 | 6 | 较大延迟 |
| 4 | 4 | 8 | 最大延迟，与原版一致 |

右键循环切换：1 → 2 → 3 → 4 → 1

### 5.4 信号刷新

中继器**不透明传输**输入信号，而是：
- 延迟到期后，输出**自己堆叠数对应的最大信号**（`getSignalCap(count)`）
- 这是"信号刷新"——无论输入信号多弱，输出都是满信号

```
输入信号 5 → [中继器(1堆叠)] → 输出信号 15（刷新）
输入信号 5 → [中继器(4堆叠)] → 输出信号 16（刷新）
```

---

## 6. 活比较器

### 6.1 功能概述

活比较器有两种模式（右键切换），与原版比较器一致：

```
        后(A)
         ↑
    ┌────┼────┐
侧  │   比较器 │ 侧
(B) │    →    │ (B)
    └─────────┘
         ↓
       输出
```

### 6.2 比较模式（默认）

```
A ≥ B → 输出 A
A < B → 输出 0
```

**用途**：检测后方信号是否 ≥ 侧边信号。如检测后方箱子是否已满（信号达到阈值），侧边放参考信号。

### 6.3 减法模式

```
输出 A - B（最少为 0）
```

**用途**：计算两个信号的差值。

### 6.4 物品检测

活比较器可读取后方/侧边物品的信号值。当对应方向的边网格信号为 0 时，回退到读取物品：

- **非活物品** → 按堆叠充满度比例计算：
  ```
  信号 = round( (堆叠数 / 最大堆叠数) × 比较器信号上限 )，至少为 1
  ```
  其中 `比较器信号上限 = getSignalCap(比较器自身堆叠数)`，通过 `readComparatorOutput(stack, maxSignal)` 传入。
- **活物品** → 通过 `LivingItemFunction.getComparatorOutput(ItemStack)` 自定义输出，上限为比较器信号上限
  - 默认返回 0（不检测）
  - 可覆盖实现自定义检测（如熔炉进度、TNT 引信时间）

输出上限统一受 `getSignalCap(count)` 约束。

### 6.5 信号输出

活比较器的处理分布在 Phase 1 和 Phase 3 中：

```
Phase 1（作为信号源输出）：
  output = computeComparatorOutput()
  if output > 0 → 向输出方向边写入 output
  if 输出方向邻居是红石粉 → 邻居入队

Phase 3（重新检测输入，Phase 2 粉尘传播后）：
  output = computeComparatorOutput()
  powered = (output > 0)
  向输出方向边写入 output（确保 Phase 2 后更新的输入能反映到边网格）
```

**computeComparatorOutput() 逻辑**：
```java
int inputDir = edgeIndex(data.direction().opposite());  // 后方输入边
int signalA = edgeGrid.get(slot, inputDir);              // 后方信号

// 边网格无信号时，回退到读取后方物品
if (signalA == 0) {
    signalA = LivingComparatorFunction.readComparatorOutput(backStack, signalCap);
}

int[] sideDirs = perpendicularEdges(inputDir);           // 侧边（垂直于后方）
int signalB = max(edgeGrid.get(slot, sideDirs[0]),
                  edgeGrid.get(slot, sideDirs[1]));      // 侧边最大信号

// 侧边网格无信号时，回退到读取侧边物品
for (int sideDir : sideDirs) {
    if (edgeGrid.get(slot, sideDir) == 0) {
        signalB = max(signalB, readComparatorOutput(sideStack, signalCap));
    }
}

// 比较模式：A >= B → A，否则 0
// 减法模式：A - B（最少 0）
int output = subtractMode ? max(0, A - B) : (A >= B ? A : 0);
```

与中继器一样，比较器在 Phase 1 作为信号源直接写边，不参与 Phase 2 BFS 传播。但 Phase 3 会重新计算并写回边网格，确保依赖 Phase 2 粉尘传播的信号（如后方是红石粉）能正确反映到输出。

---

## 7. 堆叠数与信号强度

### 7.1 设计原则

活红石系统中，信号强度公式为 **`n == 1 ? 15 : n²`**，与原版红石不同：

- 原版红石：信号强度固定 0-15，与放置数量无关
- 活红石：1 堆叠保持原版 15 信号，高堆叠按平方增长

### 7.2 信号上限表

| 堆叠数 | 1 | 2 | 3 | 4 | 5 | 8 | 16 | 32 | 64 |
|--------|---|---|---|---|---|---|----|----|-----|-----|
| 信号上限 | 15 | 4 | 9 | 16 | 25 | 64 | 256 | 1024 | 4096 |

### 7.3 信号源

```
信号源强度 = getSignalCap(堆叠数)

1 个火把  → 信号 15
2 个火把  → 信号 4
4 个火把  → 信号 16
8 个火把  → 信号 64
64 个火把 → 信号 4096
```

### 7.4 传播上限

```
每格红石粉接收的信号上限 = getSignalCap(该红石粉的堆叠数)

newSignal = min(邻居信号 - 1, getSignalCap(自身堆叠数))
```

- 堆叠 1 的红石粉最多传递 15 强度信号 → 与原版一致
- 堆叠 4 的红石粉最多传递 16 强度信号

### 7.5 传播距离

```
堆叠 1 的红石粉串，最大传播距离 = 15 格（与原版一致）
堆叠 4 的红石粉串，最大传播距离 = 16 格
堆叠 64 的红石粉串，最大传播距离 = 4096 格
```

---

## 8. 与框架的集成

### 8.1 HasContainerData 接口

活红石系统通过 `HasContainerData` 接口融入容器级数据计算流程。所有红石功能类（红石粉、火把、中继器、比较器、按钮、拉杆、灯）均实现此接口，各自在 `tickContainerData()` 中调用 `redstoneData.calculate()`：

```java
// LivingRepeaterFunction（及其他红石功能类）
public class LivingRepeaterFunction implements LivingItemFunction, HasContainerData {

    @Override
    public int getPriority() {
        return 2;  // 在流体(0)和应力(1)之后执行
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.getOrCreateRedstoneData(ctx);
        redstoneData.calculate(ctx, tick);
    }
}
```

**关键设计**：`calculate()` 内部有 `processedThisTick` 去重守卫，确保同一 tick 内多个红石功能类调用时只执行一次计算。`ContainerRedstoneData` 实例通过 `ContainerLivingItemHandler.REDSTONE_DATA_CACHE` 静态缓存持久化，`SimpleContainerContext.setTickContext()` 在每 tick 开始时调用 `resetProcessedFlag()` 重置去重标志。

**数据获取链路**：
```
tick.getOrCreateRedstoneData(ctx)
  → TickContext.redstoneData 为 null 时
    → SimpleContainerContext.getOrCreateRedstoneData()
      → SimpleContainerContext.redstoneData 为 null 时
        → ContainerLivingItemHandler.getRedstoneData(containerKey)
          → REDSTONE_DATA_CACHE.computeIfAbsent(containerKey, ...)
          → 返回持久化的 ContainerRedstoneData 实例
```

### 8.2 容器级数据计算流程

```
ContainerLivingItemHandler.processContext()
  │
  ├─ 收集 HasContainerData 实现者
  ├─ 按优先级排序
  │   ├─ 0: LivingWaterBucketFunction  — 流体蔓延
  │   ├─ 1: LivingWaterWheelFunction   — 应力计算
  │   ├─ 2: LivingRedstoneFunction     — 红石信号传播
  │   ├─ 2: LivingRedstoneTorchFunction — 红石信号传播
  │   ├─ 2: LivingRepeaterFunction     — 中继器延迟处理
  │   └─ 2: LivingComparatorFunction   — 比较器计算
  │
  └─ 依次调用 tickContainerData()
```

### 8.3 HasDirection 接口（火把/中继器/比较器）

火把的 WASD 朝向配置通过 `HasDirection` 接口自动集成到输入处理和网络处理中，无需修改 `LivingItemInputHandler` 或 `ServerPacketHandler`。

---

## 9. 实施状态

### 已完成（P0-P2）

| 物品 | 功能 | 实现 |
|------|------|------|
| 活红石粉 | 信号传播介质，读4边max → 衰减1 → 写4边，getSignalCap 上限 | `LivingRedstoneFunction` |
| 活红石火把 | 信号源 + 反相器 + 四方向朝向 + 3边输出排除输入边 | `LivingRedstoneTorchFunction` |
| 活按钮 | 右键长按持续输出信号，4边写边 | `LivingButtonFunction` + `ButtonPressHandler` |
| 活拉杆 | 右键切换开关状态，4边写边 | `LivingLeverFunction` + `LeverToggleHandler` |
| 活红石灯 | 信号消费者，anyOfSlot 亮/灭可视化 | `LivingRedstoneLampFunction` |
| 活中继器 | 延迟 + 单向 + 信号刷新 + Phase1 写方向边 | `LivingRepeaterFunction` + `RepeaterCycleHandler` |
| 活比较器 | 比较/减法 + 物品检测 + 边网格回退读取 + Phase1/Phase3 写方向边 | `LivingComparatorFunction` + `ComparatorToggleHandler` |
| 边信号模型 | EdgeGrid 共享边 + 边界边预留 + 五阶段 BFS | `ContainerRedstoneData` |

### 计划中（P3）

| 优先级 | 物品 | 功能 |
|--------|------|------|
| P3 | 活活塞 | 推/拉相邻物品 |
| P3 | 活门 | 开/关可视化 |

### 未来扩展

- **活潜影箱集成**：信号穿透活潜影箱边界，实现层次化芯片设计
- **活铜块联动**：红石信号与电力系统电磁感应耦合

---

## 10. 图标渲染

### 10.1 活红石粉图标

活红石粉在物品栏中的图标由 `LivingRedstoneDecorator`（`IItemDecorator` 实现）负责渲染，由 `LivingIconRegistry` 声明式注册。

**渲染层次**：

```
┌─────────────────────────────────────────────┐
│  Layer 0: 基底模型（item/generated）         │
│  texture: living_item:item/redstone_dust_dot │
│  → 灰度中心点纹理（无颜色）                    │
├─────────────────────────────────────────────┤
│  Layer 1: LivingRedstoneDecorator            │
│  → 动态着色中心点 + 四方向连接线              │
│  → 使用 guiGraphics.setColor() 着色           │
└─────────────────────────────────────────────┘
```

**纹理文件**：

| 纹理 | 路径 | 说明 |
|------|------|------|
| `redstone_dust_dot.png` | `textures/item/` | 中心点，灰度图（无颜色） |
| `redstone_dust_line0.png` | `textures/item/` | 连接线，灰度图（无颜色），通过旋转覆盖四方向 |

**动态着色机制**：

原版红石粉纹理（`redstone_dust_line0.png`、`redstone_dust_dot.png`）是**【灰度图，不含颜色】**。原版方块通过模型中的 `"tintindex": 0` + `BlockColors` 注册实现着色：

```java
// 原版 BlockColors 注册（方块渲染管线的着色机制）
blockcolors.register(
    (state, level, pos, tintIndex) -> RedStoneWireBlock.getColorForPower(state.getValue(POWER)),
    Blocks.REDSTONE_WIRE
);
```

活红石粉是**物品**，无法使用 `BlockColors` 机制。因此在 `LivingRedstoneDecorator` 中手动实现等价着色：

```java
// LivingRedstoneDecorator.render() 核心逻辑
int power = data.signalStrength();
int color = RedStoneWireBlock.getColorForPower(Mth.clamp(power, 0, 15));
float r = ((color >> 16) & 0xFF) / 255.0f;
float g = ((color >> 8) & 0xFF) / 255.0f;
float b = (color & 0xFF) / 255.0f;

guiGraphics.setColor(r, g, b, 1.0f);  // 设置 shader 颜色，灰度纹理被染成红色

// 绘制中心点
guiGraphics.blit(DOT_TEXTURE, 0, 0, 0, 0, 16, 16, 16, 16);

// 绘制四方向连接线（单张纹理 + 旋转）
if (conn & CONN_UP)    drawRotatedLine(guiGraphics, 0);    // 上
if (conn & CONN_DOWN)  drawRotatedLine(guiGraphics, 180);  // 下
if (conn & CONN_LEFT)  drawRotatedLine(guiGraphics, 270);  // 左
if (conn & CONN_RIGHT) drawRotatedLine(guiGraphics, 90);   // 右

guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);  // 恢复默认颜色
```

**颜色计算**（`RedStoneWireBlock.getColorForPower`）：

| power | 颜色 | 视觉效果 |
|-------|------|---------|
| 0 | 暗红 | 无信号，暗淡 |
| 7 | 中红 | 中等信号 |
| 15 | 亮红 | 满信号，最亮 |

**连接线旋转**：只需一张 `redstone_dust_line0.png` 纹理，通过 `PoseStack` Z 轴旋转 0°/90°/180°/270° 覆盖四个方向，避免创建 4 张纹理。

**信号钳制**：`Mth.clamp(power, 0, 15)` 防止超范围值导致 `ArrayIndexOutOfBoundsException`（`getColorForPower` 内部直接 `COLORS[power]`，数组容量仅 16）。
- **客户端渲染**：信号强度颜色渐变、火把朝向指示器、红石灯亮灭动画