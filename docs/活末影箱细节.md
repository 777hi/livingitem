<!-- markdownlint-disable -->

# 草稿：活潜影箱 —— 局部演化 + 边界交换模型

> **日期**: 2026-08-14
> **状态**: 探讨中，待继续深入

---

## 1. 核心洞察：数据演化模型

活潜影箱本质上是一个物品，存的东西是 DataComponent，也就是数据。每 tick，这些数据根据自身的内部结构和周围的其它数据结构进行动态演化。

```
每个 tick：
  对于容器中的每个数据（ItemStack + DataComponent）：
    新状态 = f(自身状态, 邻居状态)
```

这就是一个**细胞自动机**。活潜影箱只是一个**包含子细胞的细胞**。

现有 `ContainerLivingItemHandler.processContext()` 已经在做这件事：
```
扫描容器 → 按功能分组 → tick 每组 → 容器级计算 → 同步写回
```

活潜影箱只是让演化可以**嵌套**。

---

## 2. 旧方案的问题：全局视图过度设计

旧方案（`ShulkerLogicalSpace` + `BoundaryBridgeMap` + `ExtendedSlotResolver`）试图构建一个**全局的扁平视图**，把多个活潜影箱的内部槽位拼成一个大数组，然后在这个大数组上跑 BFS。

这就像是为了让水从 A 流到 B，先把 A 和 B 拆开拼成一个大水池，再往里倒水。

但 Minecraft 原版不是这么做的。原版的水流、红石信号都是**局部更新**——每个方块只看自己的 4 个邻居，根据邻居状态更新自己。区块边界的水流通过**边界交换**传递，不需要全局视图。

---

## 3. 新方案：局部演化 + 边界交换

```
活潜影箱 A 的内部：          活潜影箱 B 的内部：
  [0] [1] ... [7] [8]         [0] [1] ... [7] [8]
  [9] [10]... [17][18]        [9] [10]... [17][18]
  [19] [20]... [26]           [19] [20]... [26]

每 tick：
  1. A 内部各数据根据自身+邻居演化（局部规则）
  2. B 内部各数据根据自身+邻居演化（局部规则）
  3. A 的右边界(8,17,26) ←交换→ B 的左边界(0,9,18)
     - A[8] 的右邻居 = B[0]
     - B[0] 的左邻居 = A[8]
  4. 边界交换后，受影响的细胞再次演化
```

步骤 3 的"边界交换"就是原版 Minecraft 处理区块边界的方式。不需要全局 BFS，不需要扁平视图。

---

## 4. 具体实现对比

### 旧方案（全局视图）

```
ShulkerLogicalSpace.build()
  → 扫描所有活潜影箱
  → 构建 addressMap[逻辑索引→物理地址]
  → 构建 BoundaryBridgeMap
  → 在全局逻辑空间上跑 BFS
```

需要改 ContainerFluidData 的 key（int→long），改 ContainerStressData 的坐标计算，改 ContainerRedstoneData 的邻居查询——**全量改造现有系统**。

### 新方案（局部演化 + 边界交换）

```
每个活潜影箱独立 tick：
  1. 创建内部 ContainerContext
  2. processContext(内部)     ← 完全复用现有逻辑！
  3. 边界交换                  ← 唯一新增的部分
```

**ContainerFluidData 不需要改 key！** 每个活潜影箱有自己的 `ContainerFluidData`，key 仍然是 `int`。水流在 A 内部正常 BFS，A 的边界水位通过边界交换传递给 B，B 再根据边界输入重新 BFS。

---

## 5. 边界交换机制

边界交换本质上就是：**让边界槽位能"看到"相邻活潜影箱的对应槽位**。

```java
// 在 LivingShulkerBoxFunction.tick() 中：
for (SlotEntry entry : entries) {
    ItemStack shulkerStack = entry.stack();

    // 1. 内部 tick（复用现有 processContext）
    IItemHandler innerHandler = new LivingChestItemHandler(shulkerStack);
    ContainerContext innerCtx = new SimpleContainerContext(innerHandler, ...);
    ContainerLivingItemHandler.processContext(innerCtx, level);

    // 2. 边界交换
    exchangeBoundaryData(entry.slotIndex(), shulkerStack, context, tick);

    // 3. 边界交换后，受影响的内部数据再次演化
    //    （只有边界附近的槽位需要重新计算）
}
```

### 边界交换的内容

| 数据类型 | 边界交换内容 | 方式 |
|---------|------------|------|
| **红石信号** | 边界槽位的信号强度 | A[8].signal → B[0] 作为外部输入 |
| **水流** | 边界槽位的水位 | A[8].flowLevel → B[0] 作为边界水源 |
| **物品** | 活漏斗的输出 | A 内活漏斗指向右 → 物品移到 B[0] |

红石信号和水流的边界交换是**信息传递**（只读），物品传输是**数据移动**（写操作）。

### BoundaryExchanger 接口

```java
interface BoundaryExchanger {
    // 红石：读取相邻活潜影箱边界槽位的信号强度
    int getNeighborSignal(int myInnerSlot, Pos2D direction);

    // 水流：读取相邻活潜影箱边界槽位的水位
    int getNeighborFlowLevel(int myInnerSlot, Pos2D direction);

    // 物品：向相邻活潜影箱边界槽位推入物品
    ItemStack pushItemAcrossBoundary(int myInnerSlot, Pos2D direction, ItemStack item);
}
```

活潜影箱内部的活红石粉、活水桶、活漏斗，在查找邻居时，如果邻居越界，就通过 `BoundaryExchanger` 获取。

---

## 6. 各难点的消解

### 6.1 递归 tick 性能

之前担心全局 BFS 在嵌套结构上的性能。现在每个活潜影箱**独立 BFS**，复杂度是 O(内部槽位数)，不随嵌套层数指数增长。

嵌套深度限制仍然需要，但不再是性能炸弹——4 层嵌套 = 4 次独立的 processContext，不是 4^N。

### 6.2 ContainerFluidData key 迁移

**不需要改了！** 每个活潜影箱有自己的 `ContainerFluidData`，key 仍然是 `int`。边界水流通过"边界水源"机制传递——就像原版水流从相邻区块流入时，边界方块充当"虚拟水源"。

### 6.3 不规则逻辑空间

**不需要全局逻辑空间了！** 每个活潜影箱是 9×3 的规则网格，`getNeighbors()` 完全复用现有的 `ContainerContext.getNeighbors()`。边界邻居通过边界交换补充。

"看起来连在一起但逻辑上不相邻"的问题也不存在了——如果两个活潜影箱在父容器中不相邻，它们就不交换边界。

### 6.4 边界穿透一致性

三种穿透统一为一种机制：**边界交换**（`BoundaryExchanger` 接口）。

### 6.5 DataComponent 一致性

`processContext(内部)` 修改的是活潜影箱 ItemStack 的 DataComponent。修改完后，`syncSlotToClients(parentSlot, shulkerStack)` 同步整个活潜影箱到客户端——和现有活箱子/活末影箱的逻辑完全一样。

---

## 7. 仍需深入的问题

### 7.1 边界交换的时序

```
tick 开始：
  A 内部演化 → A 的边界信号变了
  B 内部演化 → B 的边界信号变了
  边界交换 → A 和 B 互相看到对方的新边界
  但此时 A 和 B 的内部已经演化完了！
```

边界信号的变化要**等到下一个 tick 才能传播到内部**。这和原版 Minecraft 的行为一致——跨区块的红石信号也有 1 tick 延迟。

如果要求"同一个 tick 内边界信号立即传播"，就需要迭代直到收敛，但这可能导致无限循环（振荡电路）。

**建议**：接受 1 tick 延迟，和原版行为一致。

### 7.2 水流跨边界的"虚拟水源"

A 的边界槽位有水位 3，B 的对应边界槽位是空的。B 应该把 A 的边界当作"水位 4 的水源"（水流每格 +1 level，A 边界 level=3 意味着从 A 的水源流了 3 格，到 B 边界就是第 4 格）。

但 `ContainerFluidData` 目前只支持"槽位是水源"或"槽位是流动水"。需要在边界槽位注入"虚拟水源"——一个 level=4 的水源。

这需要对 `ContainerFluidData` 做一个小扩展：支持**外部注入的边界水源**。但这是增量修改，不是全量改造。

### 7.3 活漏斗跨边界传输的目标解析

活漏斗在 A 的右边界，输出方向是右。它的输出目标不在 A 内部，而在 B 的左边界。

这需要一个解析器来解析跨边界的目标槽位。但这个解析器只需要在**活漏斗 tick 时**使用，不需要全局构建。

### 7.4 父容器 ↔ 活潜影箱的信号传递

上面讨论的是活潜影箱之间的边界交换。但还有另一种场景：

```
父容器槽位 5：活红石粉（信号 12）
父容器槽位 6：活潜影箱 S

S 的左边界 = S 内部槽位 0, 9, 18
信号从槽位 5 → S 的左边界 → S 内部传播
```

这需要 `BoundaryExchanger` 也支持"父容器→活潜影箱"和"活潜影箱→父容器"的边界交换。

---

## 8. 新旧方案对比

| | 旧方案（全局视图） | 新方案（局部演化 + 边界交换） |
|---|---|---|
| 核心抽象 | `ShulkerLogicalSpace`（全局扁平数组） | `BoundaryExchanger`（边界信息交换接口） |
| ContainerFluidData | key int→long，全量改造 | 不改 key，新增边界水源注入 |
| ContainerStressData | 逻辑坐标力矩 | 不改，各活潜影箱独立计算 |
| ContainerRedstoneData | 全局逻辑空间邻居 | 不改，边界信号通过交换传递 |
| 递归 tick 性能 | 指数风险 | 线性，每层独立 |
| 不规则形状 | 需要处理空洞 | 不存在，每个都是 9×3 |
| 新增文件 | 5 个 | 2-3 个 |
| 改造现有文件 | 5 个 | 1-2 个 |

**复杂度从"重构地基"降到了"加一扇门"**。

活潜影箱不再是"把多个房间拆成一个大房间"，而是"每个房间自己运转，门打开时交换信息"。这和现实中的芯片封装是一致的——芯片内部独立工作，引脚是和外部交换信息的接口。

---

## 9. 2D↔3D 桥接：世界中的活潜影盒

> 活潜影盒放在世界里，它的 6 个面就是引脚，内部 9×3 网格就是芯片的逻辑层。
> 原版红石原件连到引脚上，就像焊在 PCB 上的走线。

### 9.1 面到边的映射

活潜影盒有朝向（facing），9×3 网格有 4 条边。根据朝向，4 条边映射到方块的 4 个面：

**朝上（facing=UP）时**——从上方俯视网格：

```
        北面(north)
     ┌─────────────┐
     │ [0] [1]...[8]│  ← top edge → 北面引脚
西面 │ [9] ...  [17]│ 东面
(west)│[18]...  [26]│ (east)
     └─────────────┘
        南面(south)
        ↑ bottom edge → 南面引脚
```

| 网格边 | 对应方块面 | 槽位 |
|-------|----------|------|
| top edge (row 0) | 北面 | 0, 1, 2, 3, 4, 5, 6, 7, 8 |
| bottom edge (row 2) | 南面 | 18, 19, 20, 21, 22, 23, 24, 25, 26 |
| left edge (col 0) | 西面 | 0, 9, 18 |
| right edge (col 8) | 东面 | 8, 17, 26 |

**朝东（facing=EAST）时**——从东面看网格：

| 网格边 | 对应方块面 |
|-------|----------|
| top edge | 上面(UP) |
| bottom edge | 下面(DOWN) |
| left edge | 北面 |
| right edge | 南面 |

朝向变了，引脚的物理位置跟着变——和现实芯片封装一样，同一个裸片(die)可以有不同的封装(package)和引脚排列。

### 9.2 三个层级的逻辑空间

```
层级 1：容器内（背包/箱子）
  - 活潜影盒在 9×6 的背包网格中相邻
  - 边界交换：A 的右边界 ↔ B 的左边界
  - 纯 2D，无 3D 交互

层级 2：世界中相邻
  - 活潜影盒方块在世界中相邻、同朝向
  - 边界交换：A 的东面引脚 ↔ B 的西面引脚
  - 仍然是 2D↔2D，但物理位置在 3D 世界

层级 3：2D↔3D 桥接（最惊艳的部分）
  - 活潜影盒的引脚面 ↔ 原版红石原件
  - 信号从 3D 世界流入 2D 逻辑空间
  - 信号从 2D 逻辑空间输出到 3D 世界
```

### 9.3 2D↔3D 桥接的具体机制

**输入：3D → 2D**

原版红石原件向活潜影盒的某个面提供信号：

```java
// LivingShulkerBoxBlockEntity.tick() 中
for (Direction dir : Direction.values()) {
    int vanillaSignal = level.getSignal(worldPos.relative(dir), dir);
    if (vanillaSignal > 0) {
        int[] boundarySlots = getBoundarySlots(facing, dir);
        for (int slot : boundarySlots) {
            innerRedstoneData.setBoundaryInput(slot, vanillaSignal);
        }
    }
}
```

**输出：2D → 3D**

活潜影盒的某个面的引脚输出信号给原版红石原件：

```java
// LivingShulkerBoxBlock 实现 RedstoneSupplier
@Override
public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
    LivingShulkerBoxBlockEntity be = ...;
    int[] boundarySlots = getBoundarySlots(be.getFacing(), dir);
    int maxSignal = 0;
    for (int slot : boundarySlots) {
        maxSignal = Math.max(maxSignal, be.getInnerRedstoneData().getSignal(slot));
    }
    return maxSignal;
}
```

### 9.4 世界中相邻活潜影盒的边界交换

和容器内完全相同的机制，只是邻居检测方式不同：

```java
// 容器内：通过槽位索引判断相邻
boolean isAdjacent = (slotA + 1 == slotB);

// 世界中：通过方块坐标和朝向判断相邻
boolean isAdjacent = worldPosA.relative(dir) == worldPosB
                     && facingA == facingB;
```

| 场景 | 查找邻居方式 | BoundaryExchanger 实现 |
|------|------------|---------------------|
| 容器内 | 槽位 ±1 / ±width | `ContainerBoundaryExchanger` |
| 世界中 | BlockPos + Direction | `WorldBoundaryExchanger` |

### 9.5 这意味着什么

**活潜影盒 = 可编程逻辑芯片**

```
输入引脚（原版红石）→ [2D 逻辑空间（红石+水流+中继器+比较器）] → 输出引脚（原版红石）
```

在活潜影盒内部搭建任意红石电路，然后把它当做黑盒原件放到 3D 世界中。外部只看到 6 个面的输入输出信号。

**多个活潜影盒 = 多芯片系统**

```
[芯片A] ←边界交换→ [芯片B] ←边界交换→ [芯片C]
  ↑                    ↑
原版红石             原版红石
```

**嵌套活潜影盒 = 芯片中的芯片（芯核封装）**

**密度提升 27 倍**——一个活潜影盒 = 27 个逻辑槽位 = 27 个"方块"的功能，但只占 1 个方块的空间。

### 9.6 实现关键点

**LivingShulkerBoxBlockEntity**：

```java
public class LivingShulkerBoxBlockEntity extends BlockEntity {
    private ItemStack shulkerStack; // 包含 DataComponent 的活潜影盒物品

    // 每 tick：
    // 1. 读取 3D 世界各面的红石输入 → 注入边界
    // 2. processContext(内部) → 内部演化
    // 3. 与相邻活潜影盒边界交换
    // 4. 各面红石输出 → 通知原版红石更新
}
```

**朝向与引脚映射**：

```java
// 给定活潜影盒朝向和方块面，返回对应的边界槽位数组
static int[] getBoundarySlots(Direction facing, Direction face) {
    // facing=UP, face=EAST → right edge → {8, 17, 26}
    // facing=UP, face=NORTH → top edge → {0, 1, 2, 3, 4, 5, 6, 7, 8}
    // facing=EAST, face=UP → top edge → {0, 1, 2, 3, 4, 5, 6, 7, 8}
}
```

**红石双向传播**：
- 原版红石是拉取式的——红石粉主动查询邻居信号强度
- 活潜影盒需实现 `BlockState.getSignal()` / `getDirectSignal()`
- 同时需在 tick 时主动查询周围原版红石变化，作为边界输入
- 可通过 `Block.neighborChanged()` 触发，或 tick 时主动查询

---

## 10. 待继续探讨

- [x] 边界交换的时序：1 tick 延迟，仿照原版跨区块行为，完全可接受
- [x] 水流跨边界：直接 `setFlowLevel(边界, aLevel+1)`，不需要虚拟水源概念；暂不实现复杂水流穿透
- [ ] 父容器 ↔ 活潜影盒的边界交换细节s
- [ ] BoundaryExchanger 的实现：如何查找相邻活潜影盒
- [ ] 活漏斗跨边界传输的具体流程
- [ ] 边界交换后是否需要局部重算（只重算边界附近的槽位）
- [ ] 嵌套活潜影盒的边界交换：A 内有 B，B 的边界交换是否需要穿透 A 的边界
- [ ] 世界中活潜影盒的 BlockEntity tick 注册方式
- [ ] 朝向与引脚映射的完整 6 种 facing 枚举
- [ ] 原版比较器能否读取活潜影盒的信号（类似读取容器内容物）
- [ ] 活潜影盒内部的红石信号变化如何通知 3D 世界更新（updateNeighbors）
- [ ] 对 7.md 的更新：用新方案替换旧方案