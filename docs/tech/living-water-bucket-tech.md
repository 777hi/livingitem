# Living Water Bucket (活水桶) 技术文档

> **文档版本**: 2026.08 v4  
> **最后更新**: 2026-08-16  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [水流机制](#3-水流机制)
4. [物品推送](#4-物品推送)
5. [水源生命周期](#5-水源生命周期)
6. [Tooltip 显示](#6-tooltip-显示)

---

## 1. 架构概览

### 1.1 什么是活水桶？

活水桶是一种**流体模拟类活物品**，放置在容器槽位中后，会在容器内模拟水流——从水源槽位向四周蔓延，推动沿途物品沿水流方向移动。

活水桶的宿主物品是 `minecraft:water_bucket`（水桶），必须同时具备活物品标记。

### 1.2 设计理念：容器级流体模拟

活水桶将 Minecraft 原版的流体力学**映射到容器网格**中：

```
原版世界                    容器网格
┌─────────────────┐       ┌─────────────────┐
│ 水源方块 (level=0)│  ←→  │ 水源槽位 (source) │
│ 流动水 (level=1~7)│  ←→  │ 流动槽位 (flow)  │
│ 空气             │  ←→  │ 空槽位           │
│ 物品实体被水推动  │  ←→  │ 物品被水流推动    │
└─────────────────┘       └─────────────────┘
```

**核心特性**：
- 水源从一个槽位向相邻 4 方向蔓延
- 流动水有 1~7 级，越远级别越高，7 级后干涸
- 水流推动槽位中的物品沿水流方向移动
- 活物品（活漏斗、活箱子等）阻挡水流但不会被推动
- 水桶移除后，水流立即消失

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingWaterBucketFunction` | `domain/water/LivingWaterBucketFunction.java` | 活水桶功能入口，管理水源注册和状态同步 |
| `WaterData` | `domain/water/WaterData.java` | 水源状态 record：位置、容器、流动信息 |
| `LivingWaterBucketData` | `domain/water/LivingWaterBucketData.java` | 活水桶数据容器：包含 WaterData |
| `ContainerFluidData` | `container/ContainerFluidData.java` | 容器级流体数据，管理水流蔓延和物品推动。由 `ContainerSnapshot` 持有引用，生命周期独立于活水桶 |
| `ContainerSnapshot` | `container/ContainerSnapshot.java` | 容器快照，持有 `ContainerFluidData` 引用，每 tick 预计算 |
| `ContainerLivingItemHandler` | `container/ContainerLivingItemHandler.java` | 容器处理器，管理 `ContainerFluidData` 的持久化缓存（`CONTAINER_DATA` 嵌套 `fluid` 字段） |

---

## 2. 数据结构

### 2.1 WaterData — 水源状态

```java
public record WaterData(
    long lastTick,        // 上次 tick 的游戏时间
    int hostSlot,         // 水桶所在的槽位索引
    int col,              // 槽位列坐标
    int row,              // 槽位行坐标
    int containerWidth,   // 容器宽度
    String containerKey,  // 容器唯一标识
    String flow           // 水流状态字符串（用于 tooltip）
)
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastTick` | long | 上次 tick 的游戏时间，用于检测容器切换/移除 |
| `hostSlot` | int | 水桶在容器中的槽位索引 |
| `col` | int | 列坐标 = `hostSlot % containerWidth` |
| `row` | int | 行坐标 = `hostSlot / containerWidth` |
| `containerKey` | String | 容器唯一标识，用于检测水桶是否被移动到其他容器 |
| `flow` | String | 水流状态，格式 `"slot:level,slot:level,..."` |

### 2.2 ContainerFluidData — 容器级流体数据

```java
public class ContainerFluidData {
    public static final int SOURCE_LEVEL = 0;      // 水源级别
    public static final int MAX_FLOW_LEVEL = 7;    // 最大流动级别
    public static final int FLOW_STEP_TICKS = 4;   // 流动间隔（ticks）

    public static class FlowEntry {
        int level;        // 水流级别（0=水源, 1~7=流动）
        boolean isSource; // 是否为水源
        int fromSlot;     // 水流来源槽位（BFS 父节点，水源为 -1）
    }

    private final Map<Integer, FlowEntry> flows = new LinkedHashMap<>();
}
```

### 2.3 存储结构

活水桶的所有数据存储在 ItemStack 的 DataComponent 中，通过 `LivingWaterBucketData` record 管理：

```
ItemStack
├── IS_LIVING: true                              ← 活物品标记
└── LIVING_WATER_BUCKET_DATA: LivingWaterBucketData  ← 功能状态（DataComponent）
    └─ water: WaterData
        ├─ lastTick: long          ← 上次 tick 时间
        ├─ hostSlot: int           ← 所在槽位
        ├─ col: int                ← 列坐标
        ├─ row: int                ← 行坐标
        ├─ w: int                  ← 容器宽度
        ├─ containerKey: String    ← 容器标识
        └─ flow: String            ← 水流状态
```

> **v2 变更**：存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingWaterBucketData`），利用 Minecraft 内置的序列化和同步机制。

---

## 3. 水流机制

### 3.1 Tick 流程

```java
@Override
public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    if (level.isClientSide) return;

    for (SlotEntry entry : entries) {
        // 1. 读取水桶数据
        LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
        WaterData water = data.water();

        // 2. 检测是否需要重置水源
        //    - 超过 2 ticks 没 tick（说明被短暂移除）
        //    - 容器 key 变化（被移动到其他容器）
        //    - 槽位变化（在容器内移动）
        if (needsReset) {
            fluidData.removeSource(water.hostSlot());  // 取消旧水源
        }

        // 3. 更新水源状态
        water = new WaterData(gameTime, slot, col, row, containerWidth, containerKey, water.flow());

        // 4. 从 ContainerSnapshot 获取流体数据并注册新水源
        ContainerSnapshot snapshot = tick.snapshot;
        ContainerFluidData fluidData = snapshot != null ? snapshot.getFluidData() : null;

        if (fluidData != null) {
            fluidData.registerSource(slot);
        }
    }
}
```

> **v2 变更**：`ContainerFluidData` 现在由 `ContainerSnapshot` 持有引用，通过 `tick.snapshot.getFluidData()` 获取。流体数据在 `TickContext.reset()` 阶段随快照一起捕获，确保活水桶 tick 时能直接读取。

### 3.2 BFS 重算机制

每个 tick 通过 BFS 从所有水源重新计算水流状态，动态适应环境变化：

```
recalculate(ctx, containerSize, width):
  1. 收集所有水源槽位（验证槽位仍有活水桶）
  2. BFS 从水源逐层扩展：
     - 遇到活物品 → 跳过（不加入队列，水流绕行）
     - 已有更优流动（level 更低）→ 跳过
     - 否则 → 创建 FlowEntry(level+1, isSource=false, fromSlot=父节点)
  3. 替换旧的 flows 映射
```

BFS 的天然特性使水流遇到障碍时自动绕行（flood fill），无需专门的拐弯逻辑。

### 3.3 水流级别

```
水源 (level=0)
  ├─ 邻居1 (level=1)
  │   ├─ 邻居1.1 (level=2)
  │   │   └─ ... (level=3~7)
  │   └─ 邻居1.2 (level=2)
  └─ 邻居2 (level=1)

最大蔓延距离：7 格（从水源算起）
超过 7 级 → 干涸消失
```

### 3.4 水源移除

水流消失有两种情况：
- **水源移除**：活水桶被取走，`recalculate()` 中验证水源槽位不再有活水桶，该水源不加入 BFS 队列，不可达的流动立即消失
- **级别耗尽**：流动槽位达到 level=7 后，BFS 不再向其邻居扩展

```
水源被移除 → recalculate() 中跳过该水源
  └─ 不可达的流动槽位不在新的 flows 映射中 → 立即消失
```

> **v3 变更**：水源移除后不可达的流动立即消失（简单实现），替代了 v2 的逐渐干涸机制。

---

## 4. 物品推送

### 4.1 推送机制

物品沿 BFS 水流树的**下游子节点**推动，自然跟随水流的弯曲路径：

```java
private void pushItems(ContainerContext ctx, int containerSize, int width) {
    // 1. 构建下游映射：fromSlot → 子节点列表
    Map<Integer, List<Integer>> downstream = new HashMap<>();
    for (var entry : flows.entrySet()) {
        int slot = entry.getKey();
        FlowEntry fe = entry.getValue();
        if (fe.fromSlot >= 0) {
            downstream.computeIfAbsent(fe.fromSlot, k -> new ArrayList<>()).add(slot);
        }
    }

    // 2. 按 level 降序处理（外层先推，形成级联）
    sorted.sort((a, b) -> Integer.compare(b.getValue().level, a.getValue().level));

    for (var entry : sorted) {
        // 3. 遍历当前槽位的下游子节点
        for (int targetSlot : children) {
            // 推到第一个空位或可堆叠的位置
        }
    }
}
```

### 4.2 推送规则

| 情况 | 行为 |
|------|------|
| 下游子节点为空槽位 | 推送到该槽位 |
| 下游子节点有同类物品且未满 | 堆叠合并 |
| 下游子节点被其他物品占用 | 尝试下一个子节点 |
| 无可用下游子节点 | 物品留在原地 |
| 槽位中是活物品 | 不推动，水流绕行 |

### 4.3 水流拐弯推动

物品沿水流树的下游路径移动，遇到障碍时自然拐弯：

```
直线推动（旧版）：物品沿 fromSlot→slot 直线方向推一步
┌───┬───┬───┬───┬───┐
│ ← │📦 │ 1 │💧│ ■ │  物品只能直走，撞墙停住
├───┼───┼───┼───┼───┤
│   │ 3 │ 2 │ 1 │ ■ │
└───┴───┴───┴───┴───┘

沿水流推动（当前）：物品被推到下游子节点，自然拐弯
┌───┬───┬───┬───┬───┐
│   │ 2 │ 1 │💧│ ■ │  物品在(1,1)，下游是(0,1)和(1,2)
├───┼───┼───┼───┼───┤
│   │📦→│ 2 │ 1 │ ■ │  物品被推到(1,2)（水流拐弯处）
├───┼───┼───┼───┼───┤
│   │ 4 │ 3 │ 2 │ 5 │  下一步→(2,2)→(3,2)→绕过■！
└───┴───┴───┴───┴───┘
```

### 4.4 水流阻挡

活物品（活漏斗、活箱子、活熔炉等）会阻挡水流：
- 水流不会蔓延到活物品所在槽位
- 活物品不会被水流推动
- 但活物品**不阻挡**水流从其他方向绕过

---

## 5. 水源生命周期

### 5.1 水源注册

```
活水桶放入容器槽位 → tick() 首次执行
  ├─ 计算槽位坐标 (col, row)
  ├─ 记录 containerKey
  └─ fluidData.registerSource(slot)
      └─ 创建 FlowEntry(level=0, timer=0, isSource=true)
```

### 5.2 水源移除

```
活水桶被移除 → 下次 tick 检测到
  ├─ lastTick 差值 > 2 → needsReset = true
  └─ fluidData.removeSource(hostSlot)
      └─ isSource = false（水源变普通流动槽位）
```

### 5.3 容器切换

```
活水桶从容器A移动到容器B：
  ├─ 容器A：containerKey 不匹配 → removeSource(旧槽位)
  └─ 容器B：新 containerKey → registerSource(新槽位)
```

### 5.4 槽位内移动

```
活水桶在容器内从槽位X移动到槽位Y：
  ├─ hostSlot 变化 → removeSource(X)
  └─ registerSource(Y)
```

---

## 6. Tooltip 显示

### 6.1 显示内容

| 字段 | 说明 |
|------|------|
| 水流状态 | "水流: N 格 (最远M格)" |

### 6.2 实现

```java
@Override
public void addToTooltip(Item.TooltipContext context,
                         Consumer<Component> tooltipAdder,
                         TooltipFlag flag,
                         ItemStack stack) {
    LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
    String flow = data.water().flow();
    // 解析 flow 字符串："slot:level,slot:level,..."
    // 统计水流格数和最大级别
    int count = 0;
    int maxLevel = 0;
    for (String part : flow.split(",")) {
        String[] kv = part.split(":");
        if (kv.length >= 2) {
            count++;
            int lvl = Integer.parseInt(kv[1]);
            if (lvl > maxLevel) maxLevel = lvl;
        }
    }
    tooltipAdder.accept(Component.literal("水流: " + count + " 格 (最远" + maxLevel + "格)")
        .withStyle(ChatFormatting.AQUA));
}
```

### 6.3 水流同步

水流状态通过 `postTickSync()` 在所有活物品 tick 完成后同步到客户端。该方法接收 `waterBucketEntries` 参数，直接遍历活水桶条目而非全容器扫描：

```java
// ContainerLivingItemHandler.processContext() 中调用
// 从 grouped 中查找水桶功能条目并传递
List<SlotEntry> waterBucketEntries = ...;
LivingWaterBucketFunction.postTickSync(context, fluidData, waterBucketEntries);

// LivingWaterBucketFunction.postTickSync()
public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData,
    List<SlotEntry> waterBucketEntries) {
    if (waterBucketEntries.isEmpty()) return;
    String flowStr = buildFlowString(fluidData);
    for (SlotEntry entry : waterBucketEntries) {
        int i = entry.slotIndex();
        ItemStack stack = ctx.getItem(i);
        if (!isLivingWaterBucket(stack)) continue;
        LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
        WaterData water = data.water().withFlow(flowStr);
        LivingItemManager.setWaterBucketData(stack, data.withWater(water));
        ctx.syncSlotToClients(i, stack);
    }
}
```

> **v2 变更**：`postTickSync()` 新增 `waterBucketEntries` 参数，从全容器扫描优化为直接遍历已知活水桶条目。

---

## 附录：容器水流示例

```
9列容器中的水流蔓延：
┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │L2 │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │L1 │L2 │L3 │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │水源│L1 │L2 │   │   │   │   │   │
│   │LV0│   │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │L1 │L2 │L3 │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │L2 │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘

LV0 = 水源槽位
L1~L7 = 流动水级别
→ 水流向 4 个方向蔓延，最多 7 格
→ 物品沿水流方向被推动
```

---

## 附录：v2 变更记录 (2026-07-28)

**变更1：ContainerFluidData 归属调整**

`ContainerFluidData` 现在由 `ContainerSnapshot` 持有引用，通过 `tick.snapshot.getFluidData()` 获取。流体数据在 `TickContext.reset()` 阶段随快照一起捕获，确保活水桶 tick 时能直接读取。

`ContainerFluidData` 的持久化缓存（`CONTAINER_DATA` 嵌套 `fluid` 字段）仍由 `ContainerLivingItemHandler` 管理，按 `containerKey` 索引。容器销毁时调用 `removeFluidData()` 清理，超过 120 秒未访问的条目自动清理。

**变更2：postTickSync 签名优化**

`postTickSync()` 新增 `List<SlotEntry> waterBucketEntries` 参数，从全容器扫描优化为直接遍历已知活水桶条目。调用方从 `ContainerLivingItemHandler.processContext()` 中查找水桶功能分组并传递。

---

## 附录：v3 变更记录 (2026-07-29)

**变更3：BFS 重算机制重构**

`ContainerFluidData.tick()` 从基于 `timer` 倒计时的逐格蔓延，重构为 BFS 重算机制：

- 移除 `FlowEntry` 的 `timer` 和 `removed` 字段，新增 `fromSlot` 记录水流来源槽位
- 将原 `tick()` 拆分为 `recalculate()`（BFS 从所有水源重算流动状态）和 `pushItems()`（沿水流方向推动物品）
- 每个 tick 通过 BFS 从所有水源重新计算流动状态，动态适应环境变化
- 水源移除后不可达的流动立即消失（简单实现）
- 多水源时每个槽位取最近水源的 level
- `exportFlowData()` 返回类型从 `Map<Integer, Integer>` 改为 `Map<Integer, int[]>`，包含 `[level, fromSlot]`

**变更4：四方向水流贴图渲染**

水流贴图从任意角度旋转改为四方向映射：

- 新增方向常量 `DIR_DOWN`/`DIR_RIGHT`/`DIR_UP`/`DIR_LEFT`
- 使用 `fromSlot` 计算真实水流方向，通过 `cardinalDirection(dx, dy)` 映射到四方向
- 用 `directionToRotation()` 实现 90° 倍数旋转，确保贴图与槽位边界对齐
- `WaterCell` 从存储 `dx/dy` 改为存储 `direction`

**变更5：创造模式物品复制修复**

**问题现象**：创造模式 INVENTORY 标签页中，物品被水流推动一格后原位置依旧有一份物品，然后两份合并导致复制。概率性发生，生存模式不会出现。

**根本原因**：`pushItems` 修改了 `Inventory` 中的物品后，没有立即同步到客户端。创造模式下，客户端的 `CreativeModeInventoryScreen` 在 `tick()` 中检测到 `ItemPickerMenu.remoteSlots` 与实际物品不一致，然后通过 `handleCreativeModeItemAdd` 发送 `ServerboundSetCreativeModeSlotPacket`，将旧物品状态同步回服务端，覆盖了 `pushItems` 的修改。

```
时序竞争：
  1. 服务端 pushItems 将物品从 slot A 推到 slot B
  2. syncSlotToClients 发包更新客户端 Inventory
  3. 但 ItemPickerMenu.remoteSlots 没有被更新（SlotWrapper 不受 InventoryMenu 同步影响）
  4. CreativeModeInventoryScreen.tick() → broadcastChanges() 检测到差异
  5. 客户端发送 ServerboundSetCreativeModeSlotPacket，将旧物品状态写回服务端
  6. 服务端收到包后，slot A 被恢复为旧物品 → 物品"复制"了！
```

**修复方案**：在 `pushItems` 中，每次推动物品后立即调用 `ctx.syncSlotToClients` 同步源槽位和目标槽位到客户端，确保客户端的 `remoteSlots` 及时更新，避免创造模式的回滚机制覆盖修改。

**变更6：创造模式水流贴图 SlotWrapper 兼容（已修复）**

**问题现象**：创造模式 INVENTORY 标签页中，水流贴图完全不显示。

**根本原因**：两层问题叠加导致：

1. **`SlotWrapper.getContainerSlot()` 返回错误索引**：INVENTORY 标签页中，快捷栏槽位被 `SlotWrapper` 包装，`getContainerSlot()` 返回 `InventoryMenu` 的菜单索引（如 36），而 `handlerFlow` 的 key 是 `Inventory` 的逻辑索引（如 0）。通过 `SlotWrapperAccessor` Mixin 获取 `target.getContainerSlot()` 解决了此问题。

2. **`SlotWrapper.index` 全部为 0（核心原因）**：`CreativeModeInventoryScreen.selectTab()` 在 INVENTORY 标签页中直接操作 `this.menu.slots` 列表添加 `SlotWrapper`，**没有调用 `AbstractContainerMenu.addSlot()`**。而 `Slot.index` 字段是在 `addSlot()` 中设置的（`slot.index = this.slots.size()`），直接添加到列表不会更新 `index`。结果所有 `SlotWrapper` 的 `index` 都是默认值 0。

   当 `cells.put(menuSlot.index, ...)` 时，所有 cell 都用 key=0 互相覆盖，最终只剩 1 个 cell，映射到 `getSlot(0)`（第一个 SlotWrapper = crafting result 槽位，位于 -2000, -2000 屏幕外），导致贴图渲染在屏幕外不可见。

```
调试日志揭示的问题：
  cells=1  （应该是 38 个，但全部覆盖为 1 个）
  menuSlotIndex=0, slot.x=-2000, slot.y=-2000  （crafting 槽位在屏幕外）
```

**修复方案**：

- 新增 `resolveContainerSlot` 方法，对 `SlotWrapper` 通过 `SlotWrapperAccessor.getTarget().getContainerSlot()` 获取真实的容器槽位索引，解决第一层问题
- 将 `handlerToMenu` 从 `Map<Integer, Slot>` 改为 `Map<Integer, Integer>`（containerSlot → menu.slots 列表的实际索引），使用列表索引 `i` 代替 `slot.index`，解决第二层问题

```java
// 修复前：使用 slot.index（INVENTORY 标签页下全部为 0）
Map<Integer, Slot> handlerToMenu = new HashMap<>();
for (Slot s : screen.getMenu().slots) {
    if (s.container == bucketContainer) {
        handlerToMenu.put(resolveContainerSlot(s), s);
    }
}
cells.put(menuSlot.index, new WaterCell(level, direction));

// 修复后：使用 menu.slots 列表的实际索引
Map<Integer, Integer> handlerToMenuIndex = new HashMap<>();
for (int i = 0; i < screen.getMenu().slots.size(); i++) {
    Slot s = screen.getMenu().slots.get(i);
    if (s.container == bucketContainer) {
        handlerToMenuIndex.put(resolveContainerSlot(s), i);
    }
}
cells.put(menuIndex, new WaterCell(level, direction));
```

**状态**：✅ 已修复

**变更7：物品沿水流树下游推动（拐弯推动）**

**问题现象**：物品沿 `fromSlot → slot` 的直线方向推一步，遇到障碍就停住，不会随水流拐弯。

**根本原因**：旧版 `pushItems` 通过计算 `fromSlot` 到 `slot` 的直线方向（dx/dy），然后将物品沿该方向推一步。这是纯几何计算，不关心水流的实际路径。

**修复方案**：利用 BFS 水流树中每个流动条目的 `fromSlot` 字段，构建**下游映射**（`fromSlot → 子节点列表`）。物品被推到其下游子节点，自然跟随水流的弯曲路径。

```java
// 旧版：直线推动
int dx = (slot % width) - (fromSlot % width);
int dy = (slot / width) - (fromSlot / width);
int step = dy * width + dx;
int target = slot + step;  // 只能直走

// 新版：沿水流树下游推动
Map<Integer, List<Integer>> downstream = new HashMap<>();
for (var entry : flows.entrySet()) {
    if (fe.fromSlot >= 0) {
        downstream.computeIfAbsent(fe.fromSlot, k -> new ArrayList<>()).add(slot);
    }
}
List<Integer> children = downstream.get(slot);  // 所有下游子节点
for (int targetSlot : children) { ... }  // 尝试推到下游
```

**效果**：物品遇到障碍时随水流拐弯绕行，而非撞墙停住。

**状态**：✅ 已实现

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础水流

- [ ] 活水桶放置后成为水源，BFS 向 4 方向蔓延
- [ ] 水流最多蔓延 7 格，级别递减
- [ ] 多个水源时水流级别取最近水源
- [ ] 活水桶被移走后水流立即消失
- [ ] 水流遇到活物品自动绕行

### 物品推动

- [ ] 水流推动物品沿水流方向移动
- [ ] 物品随水流拐弯绕行障碍
- [ ] 物品被推到容器边缘时停止
- [ ] 多个物品被推动时互不干扰
- [ ] 同类物品可堆叠合并

### 创造模式兼容

- [ ] INVENTORY 标签页水流贴图正常显示
- [ ] CATEGORY/HOTBAR 标签页水流贴图正常显示
- [ ] 物品推动不会产生复制

### 水源状态

- [ ] 活水桶被短暂移除（超过 2 ticks 未 tick）后重新放置，水源重置
- [ ] 活水桶移动到不同容器后，旧容器水源消失，新容器水源建立
- [ ] 活水桶在容器内移动槽位后，水源位置更新

### 容器级流体数据

- [ ] ContainerFluidData 由 ContainerSnapshot 持有，通过 tick.snapshot.getFluidData() 获取
- [ ] 流体数据持久化缓存（CONTAINER_DATA 嵌套 fluid 字段）按 containerKey 索引
- [ ] 容器销毁时 removeFluidData() 清理缓存
- [ ] 超过 120 秒未访问的缓存条目自动清理

### Tooltip 与同步

- [ ] 水流状态在 Tooltip 中正确显示（流向、级别）
- [ ] postTickSync 使用 waterBucketEntries 参数，不扫描全容器
- [ ] 水流状态变化后客户端 Tooltip 立即更新
- [ ] 大箱子（CompoundContainer）中水流 Tooltip 实时更新——`syncSlotToClients` 的容器归属匹配曾对大箱永远失败（菜单槽位容器是 CompoundContainer 包装对象，实例匹配不命中），导致大箱中 tooltip 停留在开箱快照、重开界面才恢复；2026-09-09 已修（`SimpleContainerContext.slotBelongsTo`，详见 living-water-wheel-tech.md §9.23）