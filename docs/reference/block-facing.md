# 方块朝向参考

本文档整理 Minecraft 中方块朝向系统的核心概念，以及世界方向与容器槽位网格方向的转换逻辑。

---

## 1. Direction 枚举

`net.minecraft.core.Direction` 是 Minecraft 表示空间方向的核心枚举，定义了 6 个基本方向。

### 枚举值与坐标偏移

| 枚举值 | 含义 | `getStepX()` | `getStepY()` | `getStepZ()` |
|--------|------|-------------|-------------|-------------|
| `DOWN`  | 下 (Y-) | 0  | -1 | 0  |
| `UP`    | 上 (Y+) | 0  | +1 | 0  |
| `NORTH` | 北 (Z-) | 0  | 0  | -1 |
| `SOUTH` | 南 (Z+) | 0  | 0  | +1 |
| `WEST`  | 西 (X-) | -1 | 0  | 0  |
| `EAST`  | 东 (X+) | +1 | 0  | 0  |

### 常用方法

| 方法 | 说明 |
|------|------|
| `getStepX()` / `getStepY()` / `getStepZ()` | 返回该方向在对应轴上的单位偏移（-1, 0, +1） |
| `getOpposite()` | 返回反方向（如 NORTH → SOUTH） |
| `getAxis()` | 返回该方向所在的轴（X / Y / Z） |
| `getClockWise(Axis)` / `getCounterClockWise(Axis)` | 绕指定轴顺/逆时针旋转90度 |
| `fromNormal(int x, int y, int z)` | 根据法线向量获取方向 |
| `fromAxisAndDirection(Axis, AxisDirection)` | 根据轴和正/负方向获取方向 |

---

## 2. 世界坐标系

Minecraft 世界坐标系为左手坐标系：

```
        +Y (上)
         |
         |
         +-------- +X (东)
        /
       /
     +Z (南)
```

- **X 轴**：东(+X) / 西(-X)
- **Y 轴**：上(+Y) / 下(-Y)
- **Z 轴**：南(+Z) / 北(-Z)

### 俯视图（从上往下看）

```
          -Z (北)
           |
           |
  -X (西) --+-- +X (东)
           |
           |
          +Z (南)
```

---

## 3. 方块朝向属性

有朝向的方块通过 `BlockState` 中的属性存储朝向信息。不同方块使用不同类型的属性。

### 3.1 水平朝向方块（HorizontalDirectionalBlock）

只有 4 个水平方向，适用于大多数容器和功能方块。

- **属性**：`BlockStateProperties.HORIZONTAL_FACING`
- **可选值**：`north`, `south`, `east`, `west`
- **代码获取**：`state.getValue(BlockStateProperties.HORIZONTAL_FACING)` 返回 `Direction`

**常见方块**：箱子、陷阱箱、熔炉、高炉、烟熏炉、投掷器

### 3.2 全方向方块（DirectionalBlock）

有 6 个方向（含上下），适用于需要朝上/朝下放置的方块。

- **属性**：`BlockStateProperties.FACING`
- **可选值**：`north`, `south`, `east`, `west`, `up`, `down`
- **代码获取**：`state.getValue(BlockStateProperties.FACING)` 返回 `Direction`

**常见方块**：发射器、观察者、木桶

### 3.3 轴向方块

只有 3 个轴方向，适用于原木、柱子等方块。

- **属性**：`BlockStateProperties.AXIS`
- **可选值**：`x`, `y`, `z`

**常见方块**：原木、石英柱、避雷针

---

## 4. 箱子的朝向

箱子（Chest）是 `HorizontalDirectionalBlock` 的子类。

### 4.1 facing 属性的含义

箱子的 `facing` 属性表示**闩（锁扣）的朝向**，即箱子的"正面"方向。

```
facing=north        facing=east
┌─────────┐        ┌─────────┐
│    ▪    │        │▪        │
│  (闩)   │        │  (闩)   │
└─────────┘        └─────────┘
  闩朝北              闩朝东
```

### 4.2 放置逻辑

箱子放置时，`facing` 设为玩家水平朝向的**反方向**（箱子的正面朝向玩家）：

```java
// 原版 ChestBlock 的放置代码
Direction direction = ctx.getHorizontalPlayerFacing().getOpposite();
```

即：玩家面朝北放置 → 箱子 facing=north（正面朝向玩家，即朝北）。

### 4.3 大箱子连接

当两个箱子相邻且朝向一致时，合并成大箱子。通过 `ChestType` 区分：

| ChestType | 含义 |
|-----------|------|
| `SINGLE` | 单个箱子 |
| `LEFT`   | 大箱子的左半部分 |
| `RIGHT`  | 大箱子的右半部分 |

**获取连接方向**：

```java
Direction connectedDir = ChestBlock.getConnectedDirection(state);
// 返回从当前箱子指向另一半箱子的方向
```

`getConnectedDirection` 的逻辑（从当前箱子指向另一半箱子的方向）：

| facing | LEFT | RIGHT |
|--------|------|-------|
| north  | EAST | WEST  |
| south  | WEST | EAST  |
| east   | SOUTH| NORTH |
| west   | NORTH| SOUTH |

> 注意：LEFT/RIGHT 是从玩家面对箱子正面时的视角定义的。

---

## 5. 其他容器方块的朝向

| 方块 | 朝向类型 | facing 含义 | 备注 |
|------|---------|------------|------|
| 箱子 (Chest) | 水平4方向 | 闩的朝向 | 可组成大箱子 |
| 陷阱箱 (Trapped Chest) | 水平4方向 | 闩的朝向 | 可组成大箱子 |
| 木桶 (Barrel) | 全6方向 | 打开面的朝向 | 不可组合 |
| 漏斗 (Hopper) | 水平4方向+下 | 输出口的朝向 | 无 UP 方向 |
| 熔炉 (Furnace) | 水平4方向 | 正面（燃烧面）朝向 | — |
| 高炉 (Blast Furnace) | 水平4方向 | 正面朝向 | — |
| 烟熏炉 (Smoker) | 水平4方向 | 正面朝向 | — |
| 发射器 (Dispenser) | 全6方向 | 发射面朝向 | — |
| 投掷器 (Dropper) | 水平4方向 | 发射面朝向 | — |
| 酿造台 (Brewing Stand) | 无朝向 | — | 固定朝向 |
| 潜影盒 (Shulker Box) | 全6方向 | 打开面朝向 | — |

---

## 6. 世界方向与容器槽位网格方向的转换

这是跨容器交互的核心问题。`Pos2D` 表示容器槽位网格中的相对偏移（行列方向），`Direction` 表示世界空间中的方向。两者之间的映射取决于方块的 `facing`。

### 6.1 容器槽位网格

容器槽位以 9 列网格布局（`SlotResolver.CONTAINER_WIDTH = 9`）：

```
列:  0  1  2  3  4  5  6  7  8
行0: [  ][  ][  ][  ][  ][  ][  ][  ][  ]
行1: [  ][  ][  ][  ][  ][  ][  ][  ][  ]
行2: [  ][  ][  ][  ][  ][  ][  ][  ][  ]
```

`Pos2D` 方向定义：

| Pos2D 常量 | x (列偏移) | y (行偏移) | 含义 |
|------------|-----------|-----------|------|
| `UP`    | 0  | -1 | 行减小方向 |
| `DOWN`  | 0  | +1 | 行增大方向 |
| `LEFT`  | -1 | 0  | 列减小方向 |
| `RIGHT` | +1 | 0  | 列增大方向 |
| `NONE`  | 0  | 0  | 无偏移 |

### 6.2 转换原理

当方块放置在世界中时，其"正面"朝向由 `facing` 决定。容器的槽位布局是相对于方块正面定义的。当方块旋转时，槽位在世界中的实际位置也随之旋转。

**关键规则**：从方块正面看过去时，容器的槽位布局始终是"上=行减小，下=行增大，左=列减小，右=列增大"。

### 6.3 转换映射表

以下映射表描述了：当方块 `facing` 为某方向时，世界空间中的 `Direction` 对应容器网格中的 `Pos2D` 方向。

#### facing=north（正面朝北）

从正面看：GUI 上方对应箱子后方，下方对应箱子前方，左方对应东方，右方对应西方

| 世界方向 (Direction) | 容器方向 (Pos2D) | 说明 |
|---------------------|-----------------|------|
| NORTH | DOWN | 前方 = 行增大 |
| SOUTH | UP | 后方 = 行减小 |
| WEST | RIGHT | 西方 = 列增大 |
| EAST | LEFT | 东方 = 列减小 |

#### facing=south（正面朝南）

从正面看：左右翻转，上下不变

| 世界方向 (Direction) | 容器方向 (Pos2D) | 说明 |
|---------------------|-----------------|------|
| NORTH | UP | 前方 = 行减小 |
| SOUTH | DOWN | 后方 = 行增大 |
| WEST | LEFT | 西方 = 列减小 |
| EAST | RIGHT | 东方 = 列增大 |

#### facing=east（正面朝东）

从正面看：顺时针旋转90度

| 世界方向 (Direction) | 容器方向 (Pos2D) | 说明 |
|---------------------|-----------------|------|
| NORTH | RIGHT | 北方 = 列增大 |
| SOUTH | LEFT | 南方 = 列减小 |
| WEST | UP | 西方 = 行减小 |
| EAST | DOWN | 东方 = 行增大 |

#### facing=west（正面朝西）

从正面看：逆时针旋转90度（或270度顺时针）

| 世界方向 (Direction) | 容器方向 (Pos2D) | 说明 |
|---------------------|-----------------|------|
| NORTH | LEFT | 北方 = 列减小 |
| SOUTH | RIGHT | 南方 = 列增大 |
| WEST | DOWN | 西方 = 行增大 |
| EAST | UP | 东方 = 行减小 |

### 6.4 通用转换算法

```java
/**
 * 将世界方向转换为容器槽位网格方向。
 *
 * @param worldDir 世界空间中的方向
 * @param blockFacing 方块的 facing 属性（正面朝向）
 * @return 容器网格中的 Pos2D 方向，若无水平映射则返回 NONE
 */
public static Pos2D worldToGrid(Direction worldDir, Direction blockFacing) {
    if (worldDir.getAxis() == Direction.Axis.Y) return Pos2D.NONE;
    if (blockFacing.getAxis() == Direction.Axis.Y) return Pos2D.NONE;

    int rotations = getRotationCount(blockFacing);

    Direction normalized = worldDir;
    for (int i = 0; i < rotations; i++) {
        normalized = normalized.getCounterClockWise(Direction.Axis.Y);
    }

    return switch (normalized) {
        case NORTH -> Pos2D.DOWN;    // 前方
        case SOUTH -> Pos2D.UP;      // 后方
        case WEST  -> Pos2D.RIGHT;   // 右方
        case EAST  -> Pos2D.LEFT;     // 左方
        default    -> Pos2D.NONE;
    };
}

/**
 * 获取从 NORTH 到 blockFacing 需要顺时针旋转的次数。
 */
private static int getRotationCount(Direction blockFacing) {
    return switch (blockFacing) {
        case NORTH -> 0;
        case EAST  -> 1;
        case SOUTH -> 2;
        case WEST  -> 3;
        default    -> 0;
    };
}
```

**算法原理**：

1. 以 `facing=north` 为基准（0 次旋转），建立标准映射
2. 当 `facing` 顺时针旋转了 N 次 90 度时，将世界方向逆旋转 N 次后查标准映射
3. 这等价于"将世界方向变换到方块的局部坐标系中"

### 6.5 反向转换（容器方向 → 世界方向）

```java
public static Direction gridToWorld(Pos2D gridDir, Direction blockFacing) {
    int rotations = getRotationCount(blockFacing);

    Direction normalized = switch (gridDir) {
        case Pos2D p when p.equals(Pos2D.UP)    -> Direction.SOUTH;    // 上=后方
        case Pos2D p when p.equals(Pos2D.DOWN)  -> Direction.NORTH;    // 下=前方
        case Pos2D p when p.equals(Pos2D.LEFT)  -> Direction.EAST;     // 左=东方
        case Pos2D p when p.equals(Pos2D.RIGHT) -> Direction.WEST;     // 右=西方
        default -> null;
    };

    if (normalized == null) return null;

    for (int i = 0; i < rotations; i++) {
        normalized = normalized.getClockWise(Direction.Axis.Y);
    }

    return normalized;
}
```

---

## 7. 项目中的相关代码

| 文件 | 用途 |
|------|------|
| `Pos2D.java` | 容器槽位网格的二维方向模型 |
| `SlotResolver.java` | 基于 Pos2D 的槽位索引解析 |
| `DirectionModeComponent.java` | 活物品的方向配置组件 |
| `ContainerLivingItemHandler.java` | 使用 `ChestBlock.getConnectedDirection()` 处理大箱子 |
| `HybridContainerResolver.java` | 使用 DirectionModeComponent 解析槽位方向 |
| `ContainerCacheManager.java` | 缓存槽位映射，指纹包含方向配置 |
| CrossContainerTransfer.java | 跨容器传输核心类，实现 gridToWorld/worldToGrid 方向转换和相邻容器交互 |

---

## 8. 跨容器交互的应用场景

方块朝向转换在以下场景中至关重要：

1. **活漏斗跨容器传输**：活漏斗在 A 容器中，需要向相邻的 B 容器传输物品。需要知道 B 容器相对于 A 容器的世界方向，再结合 B 容器的 `facing` 转换为 B 容器中的槽位方向。

2. **活熔炉燃料供给**：活熔炉需要从相邻容器获取燃料，需要根据熔炉的 `facing` 确定哪个世界方向对应"燃料槽方向"。

3. **大箱子槽位映射**：大箱子由两个小箱子组成，槽位索引需要根据 `ChestType` 和 `facing` 正确合并。

4. **自动化流水线**：多个活物品在不同容器间协作，需要统一的世界方向 → 容器方向转换。