<!-- markdownlint-disable -->

# Living Redstone (活红石) 技术文档

> **文档版本**: 2026.09 v15
> **最后更新**: 2026-09-02
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

活红石系统在 2D 容器网格（如 9×6 背包、9×3 箱子）中实现了原版红石信号传播的等价逻辑。采用**边信号模型**：信号存储于每条边上，每个槽位拥有**自己朝 4 个方向发出的 4 条出边**（`edges[slot*4+dir]`），活红石粉通过 BFS 传播信号，活红石火把/中继器/比较器作为信号源直接向自己的出边写入信号，信号强度受堆叠数量影响。

> **v15 边模型重构**：旧版为「共享边」模型（相邻两槽共用同一条 `hEdges`/`vEdges` 数组条目，A 的 RIGHT 边 = B 的 LEFT 边）。新版改为「每槽自有出边」模型——边归**发出方**所有，某槽读某方向输入时读的是**邻居的对应出边**（`inputAt(slot,dir)=edgeGrid.get(neighbor,oppositeDir(dir))`）。详见 §2.3.1 与 §3.2.1.1。

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
│                              │   EdgeGrid + 每槽自有出边  │      │
│                              │   每 tick 传播 + 输出     │      │
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
| `EdgeGrid` | `domain/redstone/ContainerRedstoneData.java` | 每槽自有出边信号网格，内嵌类（v15 起 `edges[slot*4+dir]`） |
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
    private static final int TICKS_PER_REPEATER_STEP = 2;  // 中继器 1 档 = 2 game tick

    private EdgeGrid edgeGrid;          // 当前帧边信号网格
    private EdgeGrid prevEdgeGrid;      // 上一帧边信号（用于断电检测）
    private int[] slotMask;             // 槽位元件类型位图（热路径去装箱）
    private boolean processedThisTick;  // 当前 tick 是否已计算
    private long lastTickTime;          // 最后访问时间戳（用于过期清理）

    public ContainerRedstoneData() { ... }  // 无参构造，edgeGrid 在首次 calculate() 时按需创建
    public void calculate(ContainerContext context, TickContext tick) { ... }
    public void resetProcessedFlag() { ... }
    public int getSlotSignal(int slot, int size, int width) { ... }  // 查询槽位有效信号（含外部输入）
    public int getBoundarySignal(int dir) { ... }  // 查询指定边界面的输出信号
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `edgeGrid` | EdgeGrid | 当前帧边信号网格，存储所有槽位间边的信号值（纯内部信号） |
| `prevEdgeGrid` | EdgeGrid | 上一帧边信号，Phase 0 用于中继器断电检测 |
| `faceInput[4]` | int[4] | 外部注入信号强度（4 方向），仅用于组件状态检测（火把烧毁/中继器输入/比较器等），不参与红石粉传播 |
| `faceOutput[4]` | int[4] | 内部信号在 4 个边界面的输出信号，扫描 edgeGrid 边界边得出，每 tick 更新 |
| `prevFaceOutput[4]` | int[4] | 上一帧 faceOutput，用于变化检测避免不必要的方块更新 |
| `slotMask` | int[] | 每槽位的元件类型位图（`BIT_DUST`/`BIT_TORCH`/…），每 tick 由 `buildSlotMask()` 重建。传播热路径的类型判定走 O(1) 数组访问，替代 `Set<Integer>.contains` 的装箱 + 哈希 |
| `processedThisTick` | boolean | 同一 tick 内多个 Function 触发时，保证只计算一次。每 tick 开始时由 `SimpleContainerContext.setTickContext()` 重置 |
| `lastTickTime` | long | 最后访问时间戳，`ContainerLivingItemHandler.cleanupStaleRedstoneData()` 每 120 秒清理超过 120 秒未访问的条目 |
| `TICKS_PER_REPEATER_STEP` | int (static) | 中继器档位到 game tick 的换算系数。档位 N 充能时 `delayTimer = N × 2`，保持原版「1 红石刻 = 2 game tick」语义 |

> **时间分辨率 = 1 game tick**。早期实现每 2 tick 才传播一次（先用容器私有 `tickCounter`，
> 后改为 `getGameTime() % 2`），这把容器内最快振荡周期限制在 4 tick。
> 实测满载 54 格容器单次传播约 4.9μs（不足单 tick 预算的 0.01%），
> 因此取消跳帧，改为每 tick 传播，元件延迟统一以 game tick 计数。

**持久化机制**：`ContainerRedstoneData` 实例通过 `ContainerLivingItemHandler.REDSTONE_DATA_CACHE`（`LinkedHashMap<String, ContainerRedstoneData>`）静态缓存持久化，以 `containerKey` 为键。`SimpleContainerContext` 每 tick 重建，但其 `getOrCreateRedstoneData()` 从缓存获取同一实例，确保 `edgeGrid`、`prevEdgeGrid` 等关键状态跨 tick 保留。`resetProcessedFlag()` 在每 tick 开始时由 `setTickContext()` 调用，确保 `processedThisTick` 被正确重置。

### 2.3.1 EdgeGrid — 每槽自有出边信号网格

信号存储在**边**上，而非槽位上。v15 起从共享边模型改为**每槽自有出边**模型：

```
对于 W×H 的槽位网格（slotCount = W×H）：
  edges[slotCount * 4] — 每槽 4 条出边，连续排布
  edges[slot * 4 + dir] — 槽位 slot 朝 dir 方向【发出】的边信号

  dir: E_UP=0, E_DOWN=1, E_LEFT=2, E_RIGHT=3
```

**出边归发出方所有**：源槽用 `set(slot, dir, v)` 写**自己**朝 `dir` 方向发出的边；某槽读 `dir` 方向输入时，读的是**邻居的对应出边**：

```
inputAt(slot, dir):
  neighbor = resolveSlot(slot, dir)
  if neighbor < 0 → return faceInput[dir]        // 边界外转外部注入
  return edgeGrid.get(neighbor, oppositeDir(dir)) // 邻居朝本槽方向的出边
```

**共享边 vs 每槽出边（v15 变更要点）**：

| 维度 | 旧·共享边 | 新·每槽出边 |
|------|-----------|-------------|
| 存储 | `hEdges[height*(W+1)]` + `vEdges[(H+1)*W]` | `edges[slotCount*4]` |
| A→B 边 | A 的 RIGHT 边 = B 的 LEFT 边（同一数组项） | `edges[A*4+RIGHT]` 与 `edges[B*4+LEFT]` 是**两个独立项** |
| 写值方 | A、B 都可能写同一共享项（谁最后写谁赢） | 只有发出方写自己的出边 |
| 读输入 | `edgeGrid.get(slot, dir)`（共享项） | `inputAt(slot, dir)`（邻居出边） |
| 外部边界 | 存储边界边行/列 | 边界槽位的对应出边即面边界（`computeFaceOutput` 见 §3.2.1） |

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
| `get(slot, dir)` | 读槽位朝 `dir` 发出的出边（**注意：是本槽的出边，非入边**），越界返回 0 |
| `set(slot, dir, value)` | 写槽位朝 `dir` 发出的出边（纯内部，不涉及外部交互） |
| `maxOfSlot(slot)` | 4 条出边最大值（用于显示/面输出） |
| `anyOfSlot(slot)` | 任一出边 > 0 |
| `zero()` | 清零所有出边 |

> **读输入一律走 `inputAt`**：所有「读某槽某方向受到的信号」都收敛到 `inputAt/maxInputOfSlot/anyInputOfSlot` 抽象助手（§3.2.1.1），内部在旧模型下 `inputAt ≡ get(slot,dir)`，新模型下 `inputAt = get(neighbor,oppositeDir(dir))`。无线/电力层通过 `getEdgeValue(slot,dir)` 读到的也是**本槽出边**，重构后值更纯净（不再混入邻居反向写回）。

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
        new LivingRepeaterData(Pos2D.UP, 1, false, 0);

    public LivingRepeaterData withDirection(Pos2D dir) { ... }
    public LivingRepeaterData withDelay(int d) { ... }
    public LivingRepeaterData withPowered(boolean p) { ... }
    public LivingRepeaterData withDelayTimer(int t) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `direction` | Pos2D | UP | 输出方向，决定信号传播方向 |
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
        new LivingComparatorData(Pos2D.UP, false, false);

    public LivingComparatorData withDirection(Pos2D dir) { ... }
    public LivingComparatorData withSubtractMode(boolean sm) { ... }
    public LivingComparatorData withPowered(boolean p) { ... }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `direction` | Pos2D | UP | 输出方向 |
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

信号传播由 `ContainerRedstoneData.calculate()` 执行，每 **2 tick** 触发一次全量传播重算。内部逻辑分为两个层级：

- **传播 tick（偶数 tick）**：`injectExternalInputs` → `reset` → 6 阶段传播 → `computeFaceOutput` → `notifyBoundaryChange`
- **非传播 tick（奇数 tick）**：直接返回，不做任何操作。`edgeGrid` 不变，`faceOutput` 必然不变，无需重复计算
- **无红石物品时**：立即 `edgeGrid.zero()` → `computeFaceOutput`（全 0）→ `notifyBoundaryChange`（通知世界信号消失）

`calculate()` 由 `LivingRedstoneFunction` 和 `LivingRedstoneTorchFunction` 的 `tickContainerData()` 方法触发，两者均通过 `HasContainerData` 接口（优先级 2）被容器处理器调用。当容器内无任何红石物品时，`ContainerLivingItemHandler.processContext()` 也会调用 `calculate()` 以清零残留信号。

**非传播 tick 直接返回**：`tickCounter % 2 != 0` 时不做任何操作，`injectExternalInputs`、`computeFaceOutput`、`notifyBoundaryChange` 均只在传播 tick 执行。因为非传播 tick 上 `edgeGrid` 不变，`faceOutput` 必然不变，执行这些操作是冗余的。

### 3.2 六阶段边信号传播算法

`calculate()` 拆分为 6 个阶段，基于边信号模型。**6 阶段传播重算仅在偶数 tick 执行**，非传播 tick 直接返回：

```
calculate(context, tick):
  1. processedThisTick 去重检查
  2. tickCounter++
  3. 收集所有红石组件槽位（torch/dust/button/lever/lamp/repeater/comparator/redstoneBlock）
  4. 若 edgeGrid 与容器尺寸不匹配 → 重建

  5. 若全部为空 → edgeGrid.zero() → computeFaceOutput → notifyBoundaryChange → 返回
     （无红石物品时立即清零信号并通知邻居）

  6. 若 tickCounter % 2 != 0 → 直接返回
     （非传播 tick：edgeGrid 不变，无需任何操作）

  ——— 以下仅在传播 tick（偶数 tick）执行 ———

  7. 重置：swap edgeGrid ↔ prevEdgeGrid，清零 edgeGrid
  8. faceInput 刷新：injectExternalInputs(context)

  phase0CountdownDelays()    — 倒计时 + 断电检测（用 prevEdgeGrid 的边）
  phase1CollectSources()     — 信号源直接写边，红石粉邻居入队
  phase2Propagation()        — BFS 传播（仅红石粉入队，faceInput 不再参与）
  phase4PowerConductors()    — 信号源向导电活物品充能，触发第二波 BFS
  phase3RecheckInputs()      — 重新检测级联输入（中继器/比较器），比较器写回边网格
  phase5UpdateDisplay()      — 更新物品显示状态（火把/灯/红石粉）

  9. computeFaceOutput(width, height)
  10. notifyBoundaryChange(context, ...)  （变化时才通知世界）

  输出通过 Mixin 完成：
  BlockStateBase.getSignal()           — 弱信号（返回 getBoundarySignal()）
  BlockStateBase.getDirectSignal()     — 强信号（返回 getBoundarySignal()）
  RedStoneWireBlock.getConnectingSide() — 让红石粉连接容器方块
```

**与旧模型（槽位信号）的核心区别**：
- 信号存储在**每槽自有出边**上（`edges[slot*4+dir]`），A 朝 B 发出 → `edges[A*4+RIGHT]`，B 读入 → `inputAt(B,LEFT)=edges[A*4+RIGHT]`，无需显式同步
- 信号源**不入队**，直接写自己的出边；BFS 队列**仅包含红石粉**
- 每个组件通过**写自己的出边 / 读邻居出边**实现方向性输入/输出

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

  maxInput = edgeGrid.maxOfSlot(current)  // 仅 4 条内部边取最大值（faceInput 不再参与）

  if maxInput <= 1 → continue

  output = min(maxInput - 1, getSignalCap(自身堆叠数))

  for 4 方向 dir：
    neighbor = resolveSlot(current, dir)

    if output > edgeGrid.get(current, dir)：
      edgeGrid.set(current, dir, output)    // 始终写边（无论邻居是什么）
      if neighbor 是红石粉 → 邻居入队       // 仅红石粉入队继续传播
```

> **设计变更（v12）**：Phase 2 中 `maxInput` 不再纳入 `faceInput`。外部信号仅通过 `getEffectiveInput()` 影响组件状态检测（火把烧毁、中继器输入、比较器输入等），不参与红石粉 BFS 传播。这从根本上打破了容器输出信号 → 外部世界 → 外部信号重新注入 → 内部传播的反馈回路。

**活漏斗红石信号控制**：
活漏斗在 `LivingHopperFunction.tick()` 中通过 `ContainerRedstoneData.getSignal(slot)` 检测槽位 4 条边是否有信号。任意边信号 > 0 时，漏斗被禁用（跳过传输和冷却倒计时），tooltip 显示红色警告。信号消失后自动恢复传输。此机制使用 `edgeGrid.maxOfSlot()` 读取边信号，与 Phase 2 的边写入解耦直接相关。

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
  // v15：比较器出边的写值已移至 Phase 4（powerConductiveNeighbor 前直接 set，
  // 可抬可压），此处不再用 Phase 3 的 != 兜底——旧共享边模型下该兜底会误压
  // 下游粉的 -1 衰减信号。详见 §3.2.1.1 与 §6.5。
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
    outDir = edgeIndex(data.direction())
    # v15：直接在 powerConductiveNeighbor 前 set 本槽出边（可抬可压），
    # 替代原 Phase 3 的 != 兜底。关闭时出边归零 → 下游粉下一 tick 自然衰减。
    edgeGrid.set(slot, outDir, output)
    if output <= 0 → continue
    ... 同上充能逻辑（把 output 灌入下游导体/粉）...

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
每槽自有出边模型下，朝外的面信号 = 边界槽位朝该方向发出的出边最大值：
  faceOutput[E_UP]    = max( slot 在顶行 c ∈ [0,W) 的 edges[c*4+E_UP] )
  faceOutput[E_DOWN]  = max( slot 在底行 c ∈ [0,W) 的 edges[(H-1)*W+c)*4+E_DOWN] )
  faceOutput[E_LEFT]  = max( slot 在左列 r ∈ [0,H) 的 edges[r*W*4+E_LEFT] )
  faceOutput[E_RIGHT] = max( slot 在右列 r ∈ [0,H) 的 edges[(r*W+W-1)*4+E_RIGHT] )

仅包含内部信号（edgeGrid 不再存储外部信号），
避免外部信号被误输出导致反馈循环。
```

**设计要点**：
- **每槽自有出边（v15 起）**：源写自己的出边 `set(slot,dir,v)`，目标读输入走 `inputAt(slot,dir)=get(neighbor,oppositeDir(dir))`，二者在旧共享边模型下语义等价、新模型下各自独立。详见 §3.2.1.1
- **BFS 仅红石粉**：队列只包含红石粉，信号源和终端不参与传播循环
- **边界出边即面边界**：边界槽位朝外的出边（`edges[slot*4+边界dir]`）即面信号来源，`computeFaceOutput` 扫描这些出边得出 `faceOutput`
- **向上取整高度**：`(size + width - 1) / width` 兼容非满行容器（如玩家背包 41 槽）
- **导体充能**：Phase 4 通过 `isRedstoneConductor` 自动判定所有 BlockItem 的导电性，不依赖功能注册。中继器/比较器通过邻居出边自动读取导体的信号，无需特殊处理
- **第二波 BFS**：充能导体后触发第二轮 BFS，确保信号穿过导体继续在红石粉中传播

### 3.2.1 容器内外红石交互（面信号模型）

容器内红石信号可与外部世界红石双向交互。采用**面信号模型**：容器的 4 个水平面各有一个输入通道和一个输出通道，对标原版 `getSignal(face)` 语义。

#### 3.2.1.1 边模型重构（v15：共享边 → 每槽自有出边）

v15 把 EdgeGrid 从「相邻两槽共用一条 `hEdges`/`vEdges`」重构为「每槽拥有自己朝 4 方向发出的 4 条出边 `edges[slot*4+dir]`」。这是纯存储层重构，传播算法与元件行为不变。

**重构动机（共享边模型的局限）**：
- 同一物理边由两个端点共享，谁最后写谁赢，无法区分「A 发给 B」与「B 反向发给 A」；
- 比较器/中继器读输出方向边时，会读到下游粉经共享边**反向写回**的 -1 衰减信号，迫使 phase3 用 `!=` 兜底去纠正，逻辑耦合脆弱；
- 电力层 `runBfs` 读 `getEdgeValue(current,dir)` 时，共享边值混合了双向写入，语义不纯。

**重构策略（增量抽象层）**：分两步，行为全程由测试护驾。
1. **Step A — 引入 input 抽象层**：所有「读某槽某方向受到的信号」收敛到 `inputAt/maxInputOfSlot/anyInputOfSlot` 助手。抽象层内部在共享边模型下 `inputAt(slot,dir) ≡ edgeGrid.get(slot,dir)`，在新模型下 `inputAt(slot,dir) = edgeGrid.get(neighbor, oppositeDir(dir))`——调用方无需感知。Step A 合入后全测试绿，证明行为等价。
2. **Step B — 翻 EdgeGrid 存储**：`hEdges`/`vEdges` → `edges[slot*4+dir]`，`get/set` 改为返回本槽出边。写方（信号源、propagateDir、powerConductiveNeighbor）本就写「自己朝 dir 发出的出边」，无需改动；读方已全部走 inputAt。Step B 合入后信号层 + 电力层测试全绿。

**读写语义对照**：
```
写（发出方）：  edgeGrid.set(slot, dir, v)      → edges[slot*4+dir] = v
读输入：        inputAt(slot, dir)              → edges[neighbor*4 + oppositeDir(dir)]
                （neighbor<0 时回退 faceInput[dir]）
读本槽出边：    getEdgeValue(slot, dir)         → edges[slot*4+dir]   （电力层上升沿采样用）
面输出：        computeFaceOutput 扫描边界槽位出边
```

**配套清理（v15）**：比较器出边的写值从 phase3 的 `!=` 兜底移至 phase4（`powerConductiveNeighbor` 前直接 `set` 本槽出边，可抬可压）。原因：共享边下 `!=` 会误压下游粉 -1 衰减信号；每槽出边下该出边只归比较器自己所有，不再有污染，且 phase4 直接 set 能保证关闭时出边归零、下游粉自然衰减。详见 §6.5。

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
| `faceInput[4]` | 外部注入信号 | 组件状态检测（火把烧毁/中继器输入/比较器输入等） |
| `faceOutput[4]` | 内部信号输出 | 对外暴露（getBoundarySignal），每 tick 更新 |

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

#### 外部信号用途：组件状态检测（getEffectiveInput）

外部信号（`faceInput`）**不再参与红石粉 BFS 传播**，仅通过 `getEffectiveInput()` 影响组件状态检测：

```
getEffectiveInput(slot, dir, width, height):
  edgeVal = edgeGrid.get(slot, dir)  // 优先使用内部边信号
  if edgeVal > 0 → return edgeVal
  
  // 内部边无信号，检查边界外部信号
  if slot 在边界 && dir 指向该面 → return faceInput[dir]
  return 0
```

`getEffectiveInput()` 被以下组件使用：

| 组件 | Phase | 用途 |
|------|-------|------|
| 中继器 | Phase 0 (倒计时) | 边界中继器检测输入是否持续 |
| 中继器 | Phase 3 (重新检测) | 判断中继器是否被供电 |
| 火把 | Phase 4 (充能导体) | 判断火把是否应该烧毁 |
| 火把 | Phase 5 (更新显示) | 更新火把 lit 显示状态 |
| 比较器 | computeComparatorOutput | 比较器主输入信号检测 |
| 红石灯 | Phase 5 (更新显示) | 边界红石灯是否点亮 |

**关键**：`seedBoundaryDust` 方法已被移除。外部信号不再将边界红石粉加入 BFS 队列，从根本上切断了外部信号重新注入内部传播的路径。

#### 输出：内部信号传出（computeFaceOutput）

传播 tick 结束时执行 `computeFaceOutput`，扫描 edgeGrid 边界边：

```
computeFaceOutput(width, height):
  扫描边界槽位的出边，取每面最大值：
    faceOutput[E_UP]    = max(顶行槽位 edges[slot*4+E_UP])
    faceOutput[E_DOWN]  = max(底行槽位 edges[slot*4+E_DOWN])
    faceOutput[E_LEFT]  = max(左列槽位 edges[slot*4+E_LEFT])
    faceOutput[E_RIGHT] = max(右列槽位 edges[slot*4+E_RIGHT])

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

**v12 改进**：通过三层机制彻底打破反馈循环：

```
外部输入 → faceInput[4] → getEffectiveInput() → 组件状态检测（仅此用途）
                              │
                              ✗ 不再参与 phase2Propagation（maxInput 不含 faceInput）
                              ✗ 不再调用 seedBoundaryDust（方法已移除）
                              │
内部信号源 → edgeGrid（纯内部） → computeFaceOutput → faceOutput[4] → 对外输出
                              │
          faceInput 无法再驱动 edgeGrid 传播！
```

**三层防护**：
1. `seedBoundaryDust` 移除：外部信号不再将边界红石粉加入 BFS 队列
2. `phase2Propagation` 中 `maxInput` 不再包含 `faceInput`：外部信号无法影响红石粉传播
3. `edgeGrid` 保持纯内部信号：`faceInput` 永不写入 `edgeGrid`

**信号消失时序**：当内部信号源被移除后，下一个传播 tick 会重新计算 edgeGrid（清零后无信号源写入），faceOutput 变为 0，notifyBoundaryChange 检测到变化并通知世界。外部世界可能仍残留一 tick 的旧信号，但该信号无法通过 faceInput 重新注入 edgeGrid 传播，反馈回路被切断。

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

面信号模型天然支持相邻容器间的红石信号传输。以下详细说明跨容器信号传输的完整数据流。

##### 总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                         容器 A                                       │
│                                                                      │
│  信号源（火把/红石块/红石粉等）                                        │
│    │ Phase 1-5 传播                                                  │
│    ▼                                                                 │
│  edgeGrid 边界边信号                                                  │
│    │ computeFaceOutput()                                              │
│    ▼                                                                 │
│  faceOutput[4]                                                       │
│    │ ① notifyBoundaryChange() → level.updateNeighborsAt(pos, block)  │
│    ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  外部世界                                                        │ │
│  │                                                                  │ │
│  │  红石线收到 neighborChanged 通知，重新计算信号                     │ │
│  │    │                                                             │ │
│  │    ▼ 查询 BlockState.getSignal(容器A_pos, direction)               │ │
│  │  BlockStateBaseMixin 拦截 → 返回 faceOutput[dir]                   │ │
│  │    │                                                             │ │
│  │    ▼ 红石线被充能，强度 = faceOutput 值                            │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                         容器 B                                   │ │
│  │                                                                  │ │
│  │  ② injectExternalInputs() 读取外部信号                            │ │
│  │    │ 遍历容器四个水平方向:                                        │ │
│  │    │                                                             │ │
│  │    ├── level.getSignal(neighborPos, worldDir)                     │ │
│  │    │     ├── 邻居是红石线? → 返回线的信号强度                      │ │
│  │    │     └── 邻居是容器A? → Mixin拦截 → 返回 faceOutput            │ │
│  │    │                                                             │ │
│  │    ├── 额外直读：邻居容器A                                        │ │
│  │    │     ContainerRedstoneData neighborData =                     │ │
│  │    │         getRedstoneDataByPos(level, neighborPos)             │ │
│  │    │     if (neighborData != null) {                              │ │
│  │    │         signal = max(signal,                                 │ │
│  │    │           neighborData.getBoundarySignal(                     │ │
│  │    │             worldToGrid(worldDir.getOpposite(),               │ │
│  │    │                        neighborFacing)))                     │ │
│  │    │     }                                                        │ │
│  │    │                                                             │ │
│  │    └── signal > 0?                                               │ │
│  │          worldToGrid(worldDir, facing) → faceInput[dir] = signal  │ │
│  │                                                                  │ │
│  │  ③ faceInput[dir] 被消费:                                        │ │
│  │    getEffectiveInput(slot, dir)                                   │ │
│  │      ├── 边界位置? → max(edgeGrid信号, faceInput[dir])            │ │
│  │      └── 非边界   → edgeGrid信号                                  │ │
│  │                                                                  │ │
│  │    被以下组件使用:                                                │ │
│  │    ├── 火把: 检测输入侧是否被充能 → 决定亮/灭                     │ │
│  │    ├── 中继器: 检测输入侧信号 → 延迟计数                          │ │
│  │    └── 比较器: 检测输入侧信号 → 比较/减法模式                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

##### 方向映射（CrossContainerTransfer.worldToGrid）

容器方块的世界方向 (NORTH/SOUTH/EAST/WEST) 通过 `worldToGrid(worldDir, blockFacing)` 映射为容器网格方向 (UP/DOWN/LEFT/RIGHT)：

```java
// 根据容器方块 FACING 旋转世界方向，映射到网格方向
// 容器朝 NORTH: NORTH→DOWN, SOUTH→UP, WEST→RIGHT, EAST→LEFT
// 容器朝 EAST:  NORTH→RIGHT, SOUTH→LEFT, WEST→DOWN, EAST→UP
// 以此类推（逆时针旋转 FACING 的 ordinal 次）
```

**关键细节**：Mixin 中调用 `worldToGrid(direction.getOpposite(), facing)`，用了 `getOpposite()`，因为 `getSignal(pos, direction)` 的 `direction` 是查询者所在的方向，需要反转才能得到信号从容器哪个面输出。同样，`injectExternalInputs` 中直读邻居容器时使用 `worldToGrid(worldDir.getOpposite(), neighborFacing)`，翻转方向以从邻居容器视角读取对应面。

##### 输出路径（容器 A → 世界）

```
edgeGrid 边界边信号
  → computeFaceOutput() → 扫描边界边，取每面最大值
  → faceOutput[UP/DOWN/LEFT/RIGHT]
  → notifyBoundaryChange() → 比较 faceOutput vs prevFaceOutput
    → changed → level.updateNeighborsAt(pos, block)
  → 世界红石线收到 neighborChanged 通知
  → 红石线调用 BlockState.getSignal(容器pos, direction)
  → BlockStateBaseMixin.onGetSignal() 拦截
    → 查 REDSTONE_DATA_CACHE → ContainerRedstoneData
    → worldToGrid(direction.getOpposite(), facing) → gridDir
    → 返回 faceOutput[gridDir]
  → 红石线被充能，强度 = faceOutput 值
```

##### 输入路径（世界 → 容器 B）

```
injectExternalInputs() —— 每传播周期调用
  → 获取容器所有关联方块位置（大箱子遍历两个半箱）
  → 对每个位置，遍历 4 个水平方向:
    → level.getSignal(neighborPos, worldDir)  // 原版路径
    → 额外检查邻居是否为容器:
      → getRedstoneDataByPos(level, neighborPos)  // 直接读邻居容器数据
      → neighborData.getBoundarySignal(dir)  // 绕过原版 0-15 截断
    → worldToGrid(worldDir, facing) → faceInput[dir] = signal
```

##### 直连场景（两容器紧贴）

```
  ┌─────────┐          ┌─────────┐
  │ 容器 A   │          │ 容器 B   │
  │ 火把 ON  │  faceOut │         │
  │         │  →→→→→→→ │         │
  │ 输出→───┼──────────┼───→输入  │
  │  faceOut│   红石线  │ faceIn  │
  │  [UP]=15│  =15     │  [DOWN] │
  │         │          │  =15    │
  └─────────┘          └─────────┘
       │                    │
       │ ① notifyBoundary   │ ② injectExternal
       │    updateNeighbors │    level.getSignal
       │                    │    + getBoundarySignal
       ▼                    ▼
   红石线收到通知         faceInput[DOWN]=15
   查询容器A getSignal     边界火把读到输入
   返回 faceOutput[UP]=15   → 火把灭
```

两个容器之间不一定需要红石线——`injectExternalInputs` 中会直接读取邻居容器的 `getBoundarySignal()`，所以面对面贴着的两个容器也能直接传输信号。

##### 时序说明

| 操作 | 频率 | 说明 |
|------|------|------|
| 内部传播 (6 phase) | 每 tick | `calculate()` 触发 |
| `computeFaceOutput` | 每 tick | `calculate()` 末尾 |
| `notifyBoundaryChange` | 每 tick | `calculate()` 末尾 |
| `injectExternalInputs` | 每 tick | `calculate()` 开头 |
| Mixin `getSignal` 拦截 | 每次世界查询时 | `BlockStateBaseMixin` |

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

##### 相邻容器边界反馈环（v12 已修复）

**v12 之前**：相邻容器边界的红石粉会形成衰减反馈环：
```
容器 A 边界红石粉（信号 15） → faceOutput[RIGHT] = 14（衰减 -1）
  → 容器 B 直读 faceOutput → faceInput[LEFT] = 14
  → 容器 B 边界红石粉获得 14 强度 → faceOutput[LEFT] = 13（衰减 -1）
  → 容器 A 直读 faceOutput → faceInput[RIGHT] = 13
  → 容器 A 边界红石粉获得 13 强度 → faceOutput[RIGHT] = 12
  → ... 循环衰减
```

**v12 修复**：由于 `faceInput` 不再参与红石粉 BFS 传播（`phase2Propagation` 的 `maxInput` 不含 `faceInput`，`seedBoundaryDust` 已移除），相邻容器边界的红石粉**不会**从对方容器的 `faceOutput` 获取信号并重新传播。反馈环已被彻底切断。

容器间的信号传输仍然正常工作——信号源（火把/中继器/红石块等）在 Phase 1 直接写边，红石粉在 Phase 2 传播，最终通过 `faceOutput` 输出到相邻容器。相邻容器的组件通过 `getEffectiveInput()` 读取外部信号，但外部信号不会重新驱动红石粉传播。

### 3.3 邻居计算

```java
// ContainerRedstoneData.resolveSlot(slot, dir, size, width)
// 返回指定方向的邻居槽位索引，越界返回 -1
// 注意：resolveSlot 仅用于判断"邻居是否存在"和"邻居类型"，
// 信号值本身通过 EdgeGrid 的每槽出边获取（inputAt 读邻居出边），无需通过邻居索引

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

Phase 1：火把写自己的 RIGHT 出边 = 15
          粉A 经 inputAt(LEFT) 从火把出边读到 15
          粉A 入队

Phase 2 BFS：
  粉A：maxInput = 15（来自火把出边，经 inputAt 读入）
       output = min(15-1, 15) = 14
       写 RIGHT 出边 = 14，粉B 经 inputAt(LEFT) 读到 14
       粉B 入队
  粉B：maxInput = 14
       output = min(14-1, 15) = 13
       写 RIGHT 出边 = 13，粉C 经 inputAt(LEFT) 读到 13
       粉C 入队
  粉C：maxInput = 13
       output = 12
       写 RIGHT 边 = 12
       邻居为空，不继续

最终结果（每条红石粉读 4 边 max）：
  [火把] [粉A] [粉B] [粉C] [  ] [  ] [  ] [  ] [  ]
   15    14    13    12    0    0    0    0    0
```

**关键**：信号值存储在出边上，粉A 经 `inputAt(LEFT)` 直接读火把的出边拿到 15，不需要通过邻居槽位索引再查一次数组。

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

### 5.5 中继器锁定机制

活中继器支持原版的中继器锁定机制。当一个中继器的侧面（垂直于输出方向的两个方向）有另一个处于**已输出**状态（`powered && delayTimer == 0`）的中继器时，该中继器会被锁定。

```
         [中继器B]  ← 已输出状态
              │
              │ 侧面锁定
              ↓
[输入] → [中继器A] → [输出]  ← 被锁定，状态冻结
```

**锁定行为**：
- 被锁定的中继器，其 `powered` 和 `delayTimer` 状态被冻结，不再随输入变化
- Phase 0（倒计时）和 Phase 3（状态转移）均跳过被锁定的中继器
- Phase 1（输出）不受影响，被锁定的中继器继续输出其冻结时的状态

**用途**：锁存器/存储器——用两个中继器即可实现 1-bit 存储单元，是时序电路的基础。

```
中继器锁定检测逻辑（checkRepeaterLocked）：
  1. 获取中继器的输出方向 D
  2. 确定两个垂直方向（UP/DOWN 的垂直方向 = LEFT/RIGHT，反之亦然）
  3. 检查每个垂直方向的邻居槽位是否为中继器
  4. 若邻居中继器 powered && delayTimer == 0 → 返回 true（锁定）
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

活比较器的处理分布在 Phase 1 和 Phase 4 中（v15 起出边写值从 Phase 3 的 `!=` 兜底移入 Phase 4）：

```
Phase 1（作为信号源输出，仅抬高）：
  output = computeComparatorOutput()
  if output > 0 → 向输出方向出边写入 output（仅当更高时，guard: output > 当前）
  if 输出方向邻居是红石粉 → 邻居入队

Phase 3（重新检测输入，Phase 2 粉尘传播后）：
  output = computeComparatorOutput()
  powered = (output > 0)
  // 只更新 powered 状态，不再写边（出边写值见 Phase 4）

Phase 4（充能导电活物品，抬/压都处理）：
  output = computeComparatorOutput()
  outDir = edgeIndex(data.direction())
  edgeGrid.set(slot, outDir, output)        // 直接同步本槽出边（可抬可压）
  if output > 0 → powerConductiveNeighbor 灌下游导体/粉
```

> **为什么出边必须在 Phase 4 同步（而非仅 Phase 1 抬高）**：若只在 Phase 1 用 `>` 抬高，比较器关闭（output=0）时出边不会归零，下游粉下一 tick 仍读到高 `maxInput` 而卡死。Phase 4 的 `set(slot, outDir, output)` 既抬高也压低，关闭时出边归零，下游粉经 `maxInput` 自然衰减。旧模型的 Phase 3 `!=` 兜底做同样的事，但在共享边模型下会误压下游粉的 -1 衰减信号，故 v15 改为 Phase 4 直接 set 本槽出边后删除。

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

### 8.4 僵尸数据清理（v12 修复）

**背景**：当容器内所有红石物品被移除后，`ContainerRedstoneData` 缓存中的 `faceOutput` 可能残留旧信号值，导致外部世界持续读取到已经不存在的红石信号。

**v12 修复**：
1. `ContainerLivingItemHandler.processContext()` 中，当 `grouped.isEmpty()`（无任何红石物品）时，移除 `rd.getSize() > 0` 的无效守卫条件（该条件因 `ContainerRedstoneData(0)` 初始化 `slotCount=0` 而永远为 `false`），直接调用 `calculate()` 触发清零逻辑
2. `calculate()` 中 `hasAny=false` 分支执行 `edgeGrid.zero()` → `computeFaceOutput`（全 0）→ `notifyBoundaryChange`（通知邻居信号消失）

**流程**：
```
容器内所有红石物品被移除
  → processContext() 检测 grouped.isEmpty()
  → 获取缓存的 ContainerRedstoneData
  → rd.calculate(context, tick)  // hasAny=false
  → edgeGrid.zero() → faceOutput = 0 → notifyBoundaryChange
  → 世界邻居收到更新，重新读取信号 → 0 ✓
```

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
| 边信号模型 | EdgeGrid 每槽自有出边（v15）+ 边界出边即面边界 + 六阶段 BFS + 面信号模型 + 僵尸数据清理 | `ContainerRedstoneData` |

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