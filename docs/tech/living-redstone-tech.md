<!-- markdownlint-disable -->

# Living Redstone (活红石) 技术文档

> **文档版本**: 2026.08 v2
> **最后更新**: 2026-08-18
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

活红石系统在 2D 容器网格（如 9×6 背包、9×3 箱子）中实现了原版红石信号传播的等价逻辑。活红石粉传递信号，活红石火把作为信号源和反相器，信号强度受堆叠数量影响。

```
┌──────────────────────────────────────────────────────────────────┐
│                    活红石 信号传播流程                             │
│                                                                  │
│  ┌──────────────────┐    信号源     ┌──────────────────┐        │
│  │ 活红石火把（点亮） │ ──────────→  │ 活红石粉（介质）  │        │
│  │ cap = 信号上限    │              │ 每格 -1 传播     │        │
│  └──────────────────┘              └────────┬─────────┘        │
│                                            │ BFS 传播           │
│                                            ▼                    │
│                              ┌──────────────────────────┐      │
│                              │ ContainerRedstoneData     │      │
│                              │   .calculate()            │      │
│                              │   每 2 tick 执行一次       │      │
│                              └────────┬─────────────────┘      │
│                                       │                         │
│              ┌────────────────────────┼──────────────────┐     │
│              ▼                        ▼                  ▼     │
│        ┌──────────┐           ┌──────────┐       ┌──────────┐ │
│        │ 活红石粉  │           │ 活中继器  │       │ 活比较器  │ │
│        │ 更新信号  │           │ 延迟输出  │       │ 比较/减法 │ │
│        └──────────┘           └──────────┘       └──────────┘ │
│              ▼                        ▼                  ▼     │
│        ┌──────────┐           ┌──────────┐       ┌──────────┐ │
│        │ 活红石灯  │           │ 活按钮    │       │ 活拉杆    │ │
│        │ 亮/灭    │           │ 长按输出  │       │ 切换输出  │ │
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
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石信号数据，BFS 传播算法 |
| `LivingRedstoneData` | `domain/redstone/LivingRedstoneData.java` | 活红石粉物品级数据：信号强度 + 是否激活 |
| `LivingRedstoneTorchData` | `domain/redstone/LivingRedstoneTorchData.java` | 活红石火把物品级数据：朝向 + 是否点亮 |
| `LivingButtonData` | `domain/redstone/LivingButtonData.java` | 活按钮数据：是否按下 |
| `LivingLeverData` | `domain/redstone/LivingLeverData.java` | 活拉杆数据：是否激活 |
| `LivingRedstoneLampData` | `domain/redstone/LivingRedstoneLampData.java` | 活红石灯数据：是否点亮 |
| `LivingRepeaterData` | `domain/redstone/LivingRepeaterData.java` | 活中继器数据：方向 + 延迟 + 供电 + 计时器 |
| `LivingComparatorData` | `domain/redstone/LivingComparatorData.java` | 活比较器数据：方向 + 模式 + 供电 |

---

## 2. 数据结构

### 2.1 LivingRedstoneData — 活红石粉物品数据

```java
public record LivingRedstoneData(
    int signalStrength,    // 当前信号强度
    boolean isPowered      // 是否被信号激活
) implements TooltipProvider {

    public static final LivingRedstoneData DEFAULT = new LivingRedstoneData(0, false);

    public LivingRedstoneData withSignal(int strength) {
        return new LivingRedstoneData(strength, isPowered);
    }

    public LivingRedstoneData withPowered(boolean powered) {
        return new LivingRedstoneData(signalStrength, powered);
    }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `signalStrength` | int | 0 | 当前信号强度 |
| `isPowered` | boolean | false | 是否被红石信号激活 |

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

    private int[] signalStrength;   // 每个槽位的信号强度
    private int tickCounter;        // tick 计数器（用于传播间隔）

    public ContainerRedstoneData(int size) { ... }
    public int getSignal(int slot) { ... }
    public void calculate(ContainerContext context, TickContext tick) { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `signalStrength[]` | int[] | 每个槽位当前信号强度，长度为容器槽位数 |
| `tickCounter` | int | 自增计数器，每 2 tick 触发一次 `calculate()` |
| `PROPAGATION_INTERVAL` | int (static) | 传播间隔 = 2 tick，与原版红石更新频率一致 |

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

### 3.2 BFS 传播算法

```
calculate(context, tick):
  1. tickCounter++，若 tickCounter % 2 != 0 → 直接返回（跳过）
  2. 获取容器中所有活红石火把槽位 → torchSlots
  3. 获取容器中所有活红石粉槽位 → dustSlots
  4. 获取容器中所有活中继器槽位 → repeaterSlots
  5. 获取容器中所有活比较器槽位 → comparatorSlots
  6. 若所有槽位都为空 → 直接返回
  7. 重置 signalStrength[] 数组为全 0
  8. 初始化 BFS 队列 + visited[]
  9. 遍历所有火把/按钮/拉杆槽位：
     a. 若点亮/按下/激活 → 信号 = getSignalCap(count)，入队
  10. 初始 BFS 遍历（仅红石粉）：
     a. 从队列取出当前槽位
     b. 若当前信号 ≤ 1 → 跳过
     c. 获取四方向邻居
     d. 对每个邻居：
        - 若邻居不是活红石粉 → 跳过
        - 若邻居已被访问 → 跳过
        - 新信号 = min(当前信号 - 1, getSignalCap(邻居堆叠数))
        - 取最大值写入 signalStrength[邻居]
        - 标记已访问，入队
  11. 中继器处理（独立 visited 数组）：
     a. 检查输入端（后方）是否有 signal > 0
     b. 有信号 → delayTimer = delay
     c. 每红石刻 delayTimer--
     d. delayTimer == 0 → 输出 cap = getSignalCap(count)，沿输出方向传播
     e. 中继器输出不衰减（信号刷新），后续每格 -1
     f. 输入信号消失 → 立即归零 delayTimer 和输出
  12. 比较器处理（独立 visited 数组）：
     a. 读取输入端 A（后方）信号
     b. 读取侧边最大信号 B
     c. 比较模式：A ≥ B → 输出 A，否则 0
     d. 减法模式：输出 A - B（最少 0）
     e. 输出上限 = getSignalCap(count)
  13. 遍历所有火把槽位，更新反相器状态
  14. 遍历所有红石灯槽位，更新亮/灭状态
  15. 遍历所有红石粉槽位，同步信号强度到客户端
```

### 3.3 邻居计算

```java
// ContainerContext.getNeighbors(slot, containerSize, width)
// 返回四方向邻居（上下左右），自动处理边界

static int[] getNeighbors(int slot, int containerSize, int width) {
    // 左：slot - 1（若 slot 不在最左列）
    // 右：slot + 1（若 slot 不在最右列）
    // 上：slot - width（若 slot 不在第一行）
    // 下：slot + width（若 slot 不在最后一行）
}
```

### 3.4 信号传播示例

```
9×6 容器中的信号传播（假设所有红石粉堆叠数为 1）：

初始状态：
  [火把] [粉] [粉] [粉] [  ] [  ] [  ] [  ] [  ]
  [  ]   [  ] [  ] [  ] [  ] [  ] [  ] [  ] [  ]
  ...

BFS 传播后：
  [火把] [粉] [粉] [粉] [  ] [  ] [  ] [  ] [  ]
   15    14   13   12   0    0    0    0    0
  [  ]   [  ] [  ] [  ] [  ] [  ] [  ] [  ] [  ]
   0     0    0    0    0    0    0    0    0
  ...

每条红石粉接收信号 = min(邻居信号 - 1, getSignalCap(堆叠数))
```

---

## 4. 活红石火把

### 4.1 双重身份

活红石火把同时是**信号源**和**反相器**：

| 角色 | 行为 |
|------|------|
| 信号源 | 点亮时输出 `count × 15` 强度信号，向所有方向传播 |
| 反相器 | 输入端有信号 → 熄灭（不输出）；输入端无信号 → 点亮（输出） |

输入端 = 朝向的**反方向**，即：
- 朝上 → 输入端在下方
- 朝下 → 输入端在上方
- 朝左 → 输入端在右方
- 朝右 → 输入端在左方

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
// LivingRedstoneTorchFunction
public static Pos2D getInputDirection(Pos2D facing) {
    if (facing.equals(Pos2D.UP))    return Pos2D.DOWN;
    if (facing.equals(Pos2D.DOWN))  return Pos2D.UP;
    if (facing.equals(Pos2D.LEFT))  return Pos2D.RIGHT;
    if (facing.equals(Pos2D.RIGHT)) return Pos2D.LEFT;
    return Pos2D.DOWN;
}
```

### 4.3 反相器逻辑

```
calculate() 中火把状态更新：

for each torch slot:
    facing = torchData.direction()
    inputDir = getInputDirection(facing)
    inputSlot = resolveSlot(slot, inputDir)
    hasInputSignal = inputSlot 存在且 signalStrength[inputSlot] > 0

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

```
1. 输入端（后方）检测：
   - 检查后方槽位是否有 signal > 0
   - 有信号 → powered = true, delayTimer = delay
   - 无信号 → powered = false, delayTimer = 0, 停止输出

2. 延迟计时：
   - 每红石刻（2 game tick）delayTimer--
   - delayTimer == 0 → 输出信号

3. 信号输出：
   - 输出 cap = getSignalCap(count)（满信号刷新）
   - 沿输出方向传播，第一跳不衰减
   - 后续每格 -1，上限受 getSignalCap 约束
   - 使用独立 visited 数组，不与初始 BFS 冲突
```

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

活比较器可读取侧边物品的信号值：

- **非活物品** → 返回堆叠数（原版行为）
- **活物品** → 通过 `LivingItemFunction.getComparatorOutput(ItemStack)` 自定义输出
  - 默认返回 0（不检测）
  - 可覆盖实现自定义检测（如熔炉进度、TNT 引信时间）

输出上限统一受 `getSignalCap(count)` 约束。

### 6.5 信号输出

- 输出信号沿输出方向传播
- 使用独立 visited 数组，不与初始 BFS 冲突
- 输出上限 = `getSignalCap(count)`

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

活红石系统通过 `HasContainerData` 接口融入容器级数据计算流程：

```java
// LivingRedstoneFunction
public class LivingRedstoneFunction implements LivingItemFunction, HasContainerData {

    @Override
    public int getPriority() {
        return 2;  // 在流体(0)和应力(1)之后执行
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.redstoneData;
        if (redstoneData == null) {
            redstoneData = new ContainerRedstoneData(ctx.getSize());
            tick.redstoneData = redstoneData;
        }
        redstoneData.calculate(ctx, tick);
    }
}
```

`LivingRedstoneTorchFunction` 同样实现了 `HasContainerData`（优先级 2），确保火把独立存在时也能触发信号传播。

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
| 活红石粉 | 信号传播介质，每格 -1，getSignalCap 上限 | `LivingRedstoneFunction` |
| 活红石火把 | 信号源 + 反相器 + 四方向朝向 | `LivingRedstoneTorchFunction` |
| 活按钮 | 右键长按持续输出信号 | `LivingButtonFunction` + `ButtonPressHandler` |
| 活拉杆 | 右键切换开关状态 | `LivingLeverFunction` + `LeverToggleHandler` |
| 活红石灯 | 信号消费者，亮/灭可视化 | `LivingRedstoneLampFunction` |
| 活中继器 | 延迟 + 单向 + 信号刷新 + 右键调档位 | `LivingRepeaterFunction` + `RepeaterCycleHandler` |
| 活比较器 | 比较/减法 + 物品检测 + 右键切换模式 | `LivingComparatorFunction` + `ComparatorToggleHandler` |

### 计划中（P3）

| 优先级 | 物品 | 功能 |
|--------|------|------|
| P3 | 活活塞 | 推/拉相邻物品 |
| P3 | 活门 | 开/关可视化 |

### 未来扩展

- **活潜影箱集成**：信号穿透活潜影箱边界，实现层次化芯片设计
- **活铜块联动**：红石信号与电力系统电磁感应耦合
- **客户端渲染**：信号强度颜色渐变、火把朝向指示器、红石灯亮灭动画