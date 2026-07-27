# Living Water Bucket (活水桶) 技术文档

> **文档版本**: 2026.07 v2  
> **最后更新**: 2026-07-28  
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
- 水桶移除后，水流逐渐干涸（而非立即消失）

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingWaterBucketFunction` | `function/LivingWaterBucketFunction.java` | 活水桶功能入口，管理水源注册和状态同步 |
| `WaterData` | `data/WaterData.java` | 水源状态 record：位置、容器、流动信息 |
| `LivingWaterBucketData` | `data/LivingWaterBucketData.java` | 活水桶数据容器：包含 WaterData |
| `ContainerFluidData` | `container/ContainerFluidData.java` | 容器级流体数据，管理水流蔓延和物品推动。由 `ContainerSnapshot` 持有引用，生命周期独立于活水桶 |
| `ContainerSnapshot` | `container/ContainerSnapshot.java` | 容器快照，持有 `ContainerFluidData` 引用，每 tick 预计算 |
| `ContainerLivingItemHandler` | `container/ContainerLivingItemHandler.java` | 容器处理器，管理 `ContainerFluidData` 的持久化缓存（`FLUID_DATA_CACHE`） |

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
        int timer;        // 流动计时器
        boolean isSource; // 是否为水源
        boolean removed;  // 是否标记移除
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

### 3.2 水源蔓延

水源在 `ContainerFluidData.tick()` 中每 4 ticks 向相邻 4 方向蔓延一次：

```
tickSource(slot, containerSize, width):
  for each neighbor in [上, 下, 左, 右]:
    if neighbor 未被水流覆盖:
      if neighbor 为空槽:
        → 创建流动槽位 (level=1, timer=4)
      if neighbor 有物品（非活物品）:
        → 尝试推动物品
        → 物品被推动后，创建流动槽位
```

### 3.3 流动传播

流动槽位每 4 ticks 检查一次：

```
tickFlow(slot, flowEntry, containerSize, width):
  ├─ 槽位中有活物品？→ 标记移除（活物品阻挡水流）
  │
  ├─ timer > 0？→ timer--，等待
  │
  ├─ 有更低级别的邻居水流？→ 不蔓延（水往低处流）
  │
  ├─ level >= 7？→ 标记移除（干涸）
  │
  └─ level < 7 → 向空槽位蔓延
      └─ 创建流动槽位 (level+1, timer=4)
```

### 3.4 水流级别

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

### 3.5 干涸机制

水流干涸有两种情况：
- **级别耗尽**：流动槽位达到 level=7 后，下一 tick 标记移除
- **水源移除**：活水桶被取走，`removeSource()` 取消水源标记，流动槽位不再有水源补充，逐渐干涸

```
水源被移除 → removeSource(slot)
  ├─ 该槽位的 isSource 设为 false
  └─ 后续 tick 中，流动槽位检测到无更低级别邻居
      └─ level 逐渐增加 → 到达 7 → 干涸
```

---

## 4. 物品推送

### 4.1 推送触发

当水流蔓延到有物品的槽位时，触发物品推动：

```java
private int pushItem(ContainerContext ctx, int waterSlot, int itemSlot,
                     int containerSize, int width) {
    // 1. 计算推动方向（水流方向）
    int dx = (itemSlot % width) - (waterSlot % width);
    int dy = (itemSlot / width) - (waterSlot / width);
    int step = dy * width + dx;

    // 2. 尝试推送到水流方向的下一个槽位
    int primaryTarget = itemSlot + step;
    if (primaryTarget 有效 && primaryTarget 为空) {
        ctx.setItem(primaryTarget, ctx.getItem(itemSlot).copy());
        ctx.setItem(itemSlot, ItemStack.EMPTY);
        return primaryTarget;
    }

    // 3. 如果正前方被阻挡，寻找最近的空槽位
    for (所有槽位) {
        if (空槽位 && 距离更近) → 推送到此槽位
    }
}
```

### 4.2 推送规则

| 情况 | 行为 |
|------|------|
| 正前方空槽位 | 推送到正前方 |
| 正前方被占用 | 寻找最近的空槽位 |
| 槽位中是活物品 | 不推动，水流被阻挡 |
| 容器已满 | 物品留在原地 |

### 4.3 水流阻挡

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

`ContainerFluidData` 的持久化缓存（`FLUID_DATA_CACHE`）仍由 `ContainerLivingItemHandler` 管理，按 `containerKey` 索引。容器销毁时调用 `removeFluidData()` 清理，超过 120 秒未访问的条目自动清理。

**变更2：postTickSync 签名优化**

`postTickSync()` 新增 `List<SlotEntry> waterBucketEntries` 参数，从全容器扫描优化为直接遍历已知活水桶条目。调用方从 `ContainerLivingItemHandler.processContext()` 中查找水桶功能分组并传递。
```

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础水流

- [ ] 活水桶放置后成为水源，每 4 ticks 向 4 方向蔓延
- [ ] 水流最多蔓延 7 格，级别递减
- [ ] 多个水源时水流级别取最大值
- [ ] 活水桶被移走后水源消失，水流逐步退去

### 物品推动

- [ ] 水流推动物品沿水流方向移动
- [ ] 物品被推到容器边缘时停止
- [ ] 多个物品被推动时互不干扰

### 水源状态

- [ ] 活水桶被短暂移除（超过 2 ticks 未 tick）后重新放置，水源重置
- [ ] 活水桶移动到不同容器后，旧容器水源消失，新容器水源建立
- [ ] 活水桶在容器内移动槽位后，水源位置更新

### 容器级流体数据

- [ ] ContainerFluidData 由 ContainerSnapshot 持有，通过 tick.snapshot.getFluidData() 获取
- [ ] FLUID_DATA_CACHE 持久化缓存按 containerKey 索引
- [ ] 容器销毁时 removeFluidData() 清理缓存
- [ ] 超过 120 秒未访问的缓存条目自动清理

### Tooltip 与同步

- [ ] 水流状态在 Tooltip 中正确显示（流向、级别）
- [ ] postTickSync 使用 waterBucketEntries 参数，不扫描全容器
- [ ] 水流状态变化后客户端 Tooltip 立即更新