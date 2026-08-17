<!-- markdownlint-disable -->

# Living Redstone (活红石) 技术文档

> **文档版本**: 2026.08 v1
> **最后更新**: 2026-08-17
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [信号传播算法](#3-信号传播算法)
4. [活红石火把](#4-活红石火把)
5. [堆叠数与信号强度](#5-堆叠数与信号强度)
6. [与框架的集成](#6-与框架的集成)
7. [实施状态](#7-实施状态)

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
│  │ count × 15 = 信号 │              │ 每格 -1 传播     │        │
│  └──────────────────┘              └────────┬─────────┘        │
│                                            │ BFS 传播           │
│                                            ▼                    │
│                              ┌──────────────────────────┐      │
│                              │ ContainerRedstoneData     │      │
│                              │   .calculate()            │      │
│                              │   每 2 tick 执行一次       │      │
│                              └────────┬─────────────────┘      │
│                                       │                         │
│                          ┌────────────┴────────────┐           │
│                          ▼                         ▼           │
│                    ┌──────────┐             ┌──────────┐      │
│                    │ 活红石粉  │             │ 活红石火把 │      │
│                    │ 更新信号  │             │ 反相器逻辑 │      │
│                    │ 同步客户端 │             │ 熄灭/点亮  │      │
│                    └──────────┘             └──────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingRedstoneFunction` | `domain/redstone/LivingRedstoneFunction.java` | 活红石粉功能入口，实现 `HasContainerData`，触发容器级信号计算 |
| `LivingRedstoneTorchFunction` | `domain/redstone/LivingRedstoneTorchFunction.java` | 活红石火把功能入口，实现 `HasDirection` + `HasContainerData` |
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石信号数据，BFS 传播算法 |
| `LivingRedstoneData` | `domain/redstone/LivingRedstoneData.java` | 活红石粉物品级数据：信号强度 + 是否激活 |
| `LivingRedstoneTorchData` | `domain/redstone/LivingRedstoneTorchData.java` | 活红石火把物品级数据：朝向 + 是否点亮 |

---

## 2. 数据结构

### 2.1 LivingRedstoneData — 活红石粉物品数据

```java
public record LivingRedstoneData(
    int signalStrength,    // 当前信号强度 0-15
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
| `signalStrength` | int | 0 | 当前信号强度，范围 0-15 |
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

### 2.4 存储结构

活红石物品的数据存储在 ItemStack 的 DataComponent 中：

```
ItemStack (minecraft:redstone)
├── IS_LIVING: true                              ← 活物品标记
└── LIVING_REDSTONE_DATA: LivingRedstoneData     ← 信号状态
    ├─ signalStrength: int                       ← 当前信号强度
    └─ isPowered: boolean                        ← 是否激活

ItemStack (minecraft:redstone_torch)
├── IS_LIVING: true                              ← 活物品标记
└── LIVING_REDSTONE_TORCH_DATA: LivingRedstoneTorchData
    ├─ direction: Pos2D                          ← 朝向（UP/DOWN/LEFT/RIGHT）
    └─ isLit: boolean                            ← 是否点亮
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
  4. 若两者都为空 → 直接返回
  5. 重置 signalStrength[] 数组为全 0
  6. 初始化 BFS 队列 + visited[]
  7. 遍历所有火把槽位：
     a. 若火把点亮 → 信号 = count × 15，入队
  8. BFS 遍历：
     a. 从队列取出当前槽位
     b. 若当前信号 ≤ 1 → 跳过（信号太弱，无法继续传播）
     c. 获取四方向邻居（上下左右，边界感知）
     d. 对每个邻居：
        - 若邻居不是活红石粉 → 跳过
        - 若邻居已被访问 → 跳过
        - 新信号 = min(当前信号 - 1, 邻居堆叠数 × 15)
        - 取最大值写入 signalStrength[邻居]
        - 标记已访问，入队
  9. 遍历所有火把槽位，更新反相器状态：
     a. 计算输入方向（朝向的反方向）
     b. 检查输入端是否有信号
     c. 有信号 → 熄灭，无信号 → 点亮
     d. 若状态变化 → 同步到客户端
  10. 遍历所有红石粉槽位，同步信号强度到客户端
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

每条红石粉接收信号 = min(邻居信号 - 1, 堆叠数 × 15)
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

## 5. 堆叠数与信号强度

### 5.1 设计原则

活红石系统中，信号强度 = **堆叠数 × 15**，与原版红石不同：

- 原版红石：信号强度固定 0-15，与放置数量无关
- 活红石：信号强度 = `count × 15`，堆叠数越大信号越强

### 5.2 信号源（火把）

```
信号源强度 = 火把堆叠数 × 15

1 个火把  → 信号 15
2 个火把  → 信号 30
8 个火把  → 信号 120
64 个火把 → 信号 960
```

### 5.3 传播上限（红石粉）

```
每格红石粉接收的信号上限 = 该红石粉的堆叠数 × 15

newSignal = min(邻居信号 - 1, 自身堆叠数 × 15)
```

这意味着：
- 堆叠 1 的红石粉最多传递 15 强度信号 → 与原版一致
- 堆叠 4 的红石粉最多传递 60 强度信号 → 更强但每格仍衰减 1

### 5.4 传播距离

```
堆叠 n 的红石粉，最大传播距离 = n × 15 格

例：堆叠 4 的红石粉串，信号从 60 衰减到 0，可传播 60 格
```

---

## 6. 与框架的集成

### 6.1 HasContainerData 接口

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

### 6.2 容器级数据计算流程

```
ContainerLivingItemHandler.processContext()
  │
  ├─ 收集 HasContainerData 实现者
  ├─ 按优先级排序
  │   ├─ 0: LivingWaterBucketFunction  — 流体蔓延
  │   ├─ 1: LivingWaterWheelFunction   — 应力计算
  │   ├─ 2: LivingRedstoneFunction     — 红石信号传播  ← 新
  │   └─ 2: LivingRedstoneTorchFunction — 红石信号传播  ← 新
  │
  └─ 依次调用 tickContainerData()
```

### 6.3 HasDirection 接口（火把）

火把的 WASD 朝向配置通过 `HasDirection` 接口自动集成到输入处理和网络处理中，无需修改 `LivingItemInputHandler` 或 `ServerPacketHandler`。

---

## 7. 实施状态

### 已完成（P0）

| 物品 | 功能 | 实现 |
|------|------|------|
| 活红石粉 | 信号传播介质，每格 -1，堆叠数 × 15 上限 | `LivingRedstoneFunction` |
| 活红石火把 | 信号源 + 反相器 + 四方向朝向 | `LivingRedstoneTorchFunction` |

### 计划中（P1-P3）

| 优先级 | 物品 | 功能 |
|--------|------|------|
| P1 | 活按钮 | 脉冲信号源（右键触发，20 tick 持续） |
| P1 | 活拉杆 | 持续信号源（右键切换开关） |
| P1 | 活红石灯 | 信号消费者（亮/灭可视化） |
| P2 | 活中继器 | 延迟 + 单向 + 可调增益 |
| P2 | 活比较器 | 信号比较（A > B ? A : 0） |
| P3 | 活活塞 | 推/拉相邻物品 |
| P3 | 活门 | 开/关可视化 |

### 未来扩展

- **活潜影箱集成**：信号穿透活潜影箱边界，实现层次化芯片设计
- **活铜块联动**：红石信号与电力系统电磁感应耦合
- **客户端渲染**：信号强度颜色渐变、火把朝向指示器、红石灯亮灭动画