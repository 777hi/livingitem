# Living Hopper (活漏斗) 技术文档

> **文档版本**: 2026.08 v10  
> **最后更新**: 2026-08-17  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [核心组件详解](#2-核心组件详解)
3. [传输流程](#3-传输流程)
4. [冷却机制](#4-冷却机制)
5. [级联防护](#5-级联防护)
6. [跨容器传输](#6-跨容器传输)
7. [物品过滤（黑白名单）](#7-物品过滤黑白名单)
8. [方向配置系统](#8-方向配置系统)
9. [槽位解析](#9-槽位解析)
10. [已知问题与修复记录](#10-已知问题与修复记录)
11. [调试指南](#11-调试指南)

---

## 1. 架构概览

### 1.1 什么是活漏斗？

活漏斗是一种特殊的活物品（Living Item），它将普通漏斗的物品传输功能**虚拟化**到容器内。活漏斗放置在容器（如箱子）的某个槽位中，可以从相邻槽位取出物品，放入另一个槽位，实现容器内的自动化物品传输。

活漏斗的宿主物品是 `minecraft:hopper`（漏斗），必须同时具备活物品标记。

### 1.2 组件架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                    LivingHopperFunction                          │
│                    (功能入口 + 组件注册)                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────┐  ┌──────────────────────┐             │
│  │ DirectionModeComponent│  │ ItemFilterComponent  │             │
│  │ (方向配置)            │  │ (物品过滤)            │             │
│  │                      │  │                      │             │
│  │ • TRANSFER 模式      │  │ • 黑白名单扫描        │             │
│  │ • WASD 输入解析      │  │ • allows() 过滤判断   │             │
│  │ • SlotMapping 管理   │  │ • 链式传递            │             │
│  └──────────┬───────────┘  └──────────┬───────────┘             │
│             │                         │                          │
│  ┌──────────┴─────────────────────────┴───────────┐             │
│  │              TransferPipeline                   │             │
│  │              (统一传输入口)                       │             │
│  │                                                 │             │
│  │  • 容器内传输 + 跨容器传输统一入口               │             │
│  │  • 前置检查（自环/级联/空/隔离/过滤）            │             │
│  │  • SlotAccessor 创建 + doTransfer 统一流程      │             │
│  └──────────────────────┬──────────────────────────┘             │
│                         │                                        │
│  ┌──────────────────────┴──────────────────────────┐             │
│  │               SlotAccessor 层                    │             │
│  │              (存储后端抽象)                       │             │
│  │                                                 │             │
│  │  ┌────────────────┐  ┌──────────────────────┐   │             │
│  │  │PlainSlotAccessor│  │ LivingChestAccessor  │   │             │
│  │  │ (普通槽位)      │  │ (活箱子虚拟存储)      │   │             │
│  │  └────────────────┘  └──────────────────────┘   │             │
│  │                                                 │             │
│  │  extract / insert / rollback / sync             │             │
│  └──────────────────────┬──────────────────────────┘             │
│                         │                                        │
│  ┌──────────────────────┴──────────────────────────┐             │
│  │            CrossContainerTransfer               │             │
│  │            (跨容器传输处理器)                    │             │
│  │                                                 │             │
│  │  • 拉取 (pullFromNeighbor)                     │             │
│  │  • 推送 (pushToNeighbor)                       │             │
│  │  • 邻居间传输 (transferBetweenNeighbors)       │             │
│  │  • 大箱子合并处理                               │             │
│  │  • GUI→世界方向转换                             │             │
│  └─────────────────────────────────────────────────┘             │
│                                                                  │
│  ┌──────────────────────┐  ┌──────────────────────────┐         │
│  │  HopperFilterBuilder │  │     SlotResolver         │         │
│  │  (过滤链构建)        │  │     (槽位解析器)         │         │
│  │                      │  │                          │         │
│  │ • buildAll()         │  │ • 相对方向→绝对槽位索引  │         │
│  │ • buildForSlot()     │  │ • 9列网格布局计算        │         │
│  │ • inheritFilter()    │  │ • 边界检查               │         │
│  └──────────────────────┘  └──────────────────────────┘         │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingHopperFunction` | `domain/hopper/LivingHopperFunction.java` | 活漏斗功能入口，注册组件，配置冷却/堆叠参数 |
| `TransferPipeline` | `domain/hopper/TransferPipeline.java` | 统一传输入口，合并容器内传输和跨容器传输逻辑 |
| `ItemTransferComponent` | _(已合并入 TransferPipeline)_ | 核心传输引擎，冷却管理、前置检查、SlotAccessor 调度 |
| `DirectionModeComponent` | _(已合并入 TransferPipeline)_ | 方向配置，WASD输入解析，槽位偏移管理 |
| `ItemFilterComponent` | `components/ItemFilterComponent.java` | 物品黑白名单过滤，扫描邻居活漏斗自动构建规则 |
| `CrossContainerTransfer` | `domain/hopper/CrossContainerTransfer.java` | 跨容器传输，GUI→世界方向转换，大箱子处理 |
| `HopperFilterBuilder` | `domain/hopper/HopperFilterBuilder.java` | 活漏斗过滤链构建，从 ContainerSnapshot 提取的领域逻辑 |
| `SlotResolver` | `transfer/SlotResolver.java` | 相对方向偏移→绝对槽位索引的数学计算 |
| `ContainerSnapshot` | `container/ContainerSnapshot.java` | 容器级共享缓存，每 tick 预计算 sourceOf/targetOf 数组，过滤构建委托给 HopperFilterBuilder |
| `SlotAccessor` | `transfer/SlotAccessor.java` | 槽位访问器接口，统一 extract/insert/rollback/sync 操作 |
| `PlainSlotAccessor` | `transfer/PlainSlotAccessor.java` | 普通槽位访问器，直接读写 ContainerContext |
| `LivingChestAccessor` | `domain/chest/LivingChestAccessor.java` | 活箱子访问器，通过 LivingChestFunction API 操作虚拟存储 |
| `SlotAccessorFactory` | `transfer/SlotAccessorFactory.java` | 工厂类，根据槽位物品类型创建对应 SlotAccessor 实例 |
| `SlotInteraction` | `transfer/SlotInteraction.java` | 槽位交互接口：「货物 × 目标槽」替代语义（matches 纯谓词 + interact 就地改 target + consumeAmount） |
| `SlotInteractions` | `transfer/SlotInteractions.java` | 槽位交互注册表 + 分发器（`tryInteract` 已知货物 / `tryInteractFromNeighbor` 拉取方向）+ **货物准入唯一定义点 `isEligibleCargo`**，三处传输分支唯一入口 |
| `FarmlandBonemealInteraction` | `domain/farmland/FarmlandBonemealInteraction.java` | 内置交互：骨粉 → 活耕地 = 施肥 |
| `ContainerCompatibilityConfig` | `transfer/ContainerCompatibilityConfig.java` | 容器兼容性规则注册表，支持配置不同模组容器的布局参数 |
| `SlotMapping` | `model/SlotMapping.java` | 不可变槽位映射模型，12种预设方向 |
| `Pos2D` | `model/Pos2D.java` | 不可变二维坐标，8个方向常量 |

---

## 2. 核心组件详解

### 2.1 LivingHopperFunction — 功能入口

活漏斗的功能配置类，继承 `BaseLivingFunction`。负责注册组件、配置参数。

**组件注册顺序**（按 tick 执行顺序）：
```java
// v8: 组件已合并入 TransferPipeline，注册顺序由 TransferPipeline 内部保证
// 1. ItemFilterComponent — 扫描黑白名单（仍独立存在）
// 2. DirectionModeComponent — 解析方向（已合并入 TransferPipeline）
// 3. TransferPipeline — 统一传输入口
```

**关键配置**：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `base_cooldown` | 8 | 基础冷却时间（ticks） |
| `max_transfer_per_tick` | 64 | 每次最大传输数量 |
| `stackMultiplier` | true | 堆叠加速（更多漏斗=更快） |

**工具方法**：
- `isLivingHopper(ItemStack)` — 判断物品是否为活漏斗
- `updateTransferMapping(ItemStack, SlotMapping)` — 更新传输方向（WASD输入后调用）
- `readDirectionState(ItemStack)` — 读取方向状态

### 2.2 TransferPipeline — 统一传输入口

> **v8 变化**：原 `ItemTransferComponent` 和 `DirectionModeComponent` 已合并入 `TransferPipeline`。

实现活漏斗的核心传输逻辑，是整个系统最复杂的组件。

**NBT 存储**：
| 键名 | 类型 | 说明 |
|------|------|------|
| `transfer_cooldown` | int | 剩余冷却 ticks |

**tick() 执行流程**：
```
tick()
  ├─ 读取冷却值
  ├─ 冷却 > 0 → 递减，return
  ├─ 冷却 == 0 → 继续
  ├─ 前置判断：源槽位为空 → return（不设冷却，等待物品）
  ├─ TransferPipeline.execute() → 执行传输
  └─ 无论成功与否，都设置冷却（防止过滤器拦截时无限循环）
```

**TransferPipeline.execute() 流程**：

```
TransferPipeline.execute(ctx, level, hostSlot, sourceSlot, targetSlot, ...)
  │
  ├─ 越界判定（sourceSlot/targetSlot < 0 或 >= containerSize）
  │   └─ 越界 → CrossContainerTransfer.execute() → 跨容器传输
  │
  └─ 未越界 → executeInContainer()
      │
      ├─ [1] 自环防护：sourceSlot == targetSlot → return false
      ├─ [2] 级联防护：transferredTargetSlots 包含 sourceSlot → return false
      ├─ [3] 空源检查：sourceStack.isEmpty() → return false
      ├─ [3.5] **槽位交互分发**（注册式，2026-09-15）：
      │   └─ SlotInteractions.tryInteract(源Accessor, 货物, 目标槽, level)
      │       ├─ 遍历注册条目：matches(货物, 目标槽) 命中才接管（纯谓词，无副作用）
      │       ├─ 模拟优先：simulateExtract(consumeAmount) 试算 → interact 生效 → extract 真扣
      │       ├─ 内置条目 FarmlandBonemealInteraction（骨粉 → 活耕地 = 施肥）：
      │       │   ├─ LivingFarmlandFunction.tryFertilize(耕地, 骨粉, level)
      │       │   │   ├─ forceGrowthTick（未成熟 +1 / 成熟待输出空 → 冻结产出）
      │       │   │   ├─ equals 零空转：耕地无变化 → false 不消耗骨粉（对着已冻结成熟耕地不空转烧粉）
      │       │   │   └─ 有变化 → setFarmlandPlant + 骨粉 shrink(1) → true
      │       ├─ 货物准入：isEligibleCargo（活物品不作货物，活箱子/活末影箱除外）——
      │       │   施肥属传输语义，故活骨粉（活物品）不施肥；活骨粉的手动用武之地在 GUI 右键
      │       ├─ 生效 → 双槽 syncSlotToClients（槽位引用已实时生效，仅同步组件/数量变化）
      │       │   └─ return true（漏斗 tick 自然设冷却——一次交互 = 一次传输，节奏对齐 8t 冷却）
      │       ├─ 不生效 → 不扣货不设冷却，继续下方通用路径（[4] 起）
      │       └─ 三处调用点共用本分发器：容器内 / 跨容器推送 / 跨容器拉取（§6.2.1）
      ├─ [4] 活物品隔离：isTransferableSource(sourceStack) 检查
      │   └─ 活物品且非存储容器（活箱子/活末影箱）→ return false
      ├─ [5] 物品过滤：非存储容器的普通物品 → ItemFilterComponent.allows(filter, sourceStack)
      │   └─ 不通过 → return false
      ├─ [6] Container 模拟玩家取出：getNeighborContainer(level, ctx.getBlockPos())
      │   └─ hostContainer.canTakeItem(sourceSlot, sourceStack) → return false
      │   └─ 注意：玩家背包 getBlockPos() 返回 null，getNeighborContainer 安全返回 null，跳过此检查
      ├─ [7] 创建 SlotAccessor：
      │   ├─ source = SlotAccessorFactory.create(sourceSlot, filter)
      │   └─ target = SlotAccessorFactory.create(targetSlot, null)
      │   └─ source == null || target == null → return false
      ├─ [8] transferAmount = Math.min(stackSize, maxTransfer)
      ├─ [9] Container 模拟玩家放入：source.simulateExtract(transferAmount)
      │   └─ hostContainer.canPlaceItem(targetSlot, simulated) → return false
      ├─ [10] 末影箱路由决策：EnderRouteManager.resolveTarget(source, target, ...)
      │   ├─ NOT_ENDER_CHEST → SlotAccessor.transfer(source, target, transferAmount)
      │   ├─ REJECTED → false
      │   └─ HANDLED → true（路由注册/提取已由 EnderRouteManager 完成）
      │
      └─ 返回传输结果（true/false）
```

**SlotAccessor 统一传输模型**：

传统做法需要为每种源/目标类型组合编写独立方法（普通×普通、普通×活箱子、活箱子×普通、活箱子×活箱子），共 4 种组合。引入 `SlotAccessor` 接口后，所有组合统一为 `extract → insert → rollback` 流程，传输引擎不再需要知道源和目标的具体存储类型。

| 方法 | 职责 |
|------|------|
| `extract(amount, filterType)` | 从存储后端提取指定数量的物品 |
| `insert(stack)` | 向存储后端插入物品，返回实际插入数量 |
| `rollback(stack)` | 插入失败时退回提取的物品 |
| `isEmpty()` / `isFull()` | 快速短路判断 |
| `markTransferred()` | 标记已传输（级联防护） |
| `sync()` | 同步状态到客户端 |

**活箱子提取的过滤策略**（在 `LivingChestAccessor.extract()` 中实现）：

当源是活箱子且存在过滤器时，采用"预查 + 类型提取"策略：
1. `getMergedStorage()` — 查看活箱子中所有物品（不修改）
2. 遍历找到第一个通过 `ItemFilterComponent.allows()` 的物品
3. `extractItem(targetType)` — 精确提取该类型的物品

这样避免了"提取→检查→退回→旋转"的复杂流程，过滤在查找阶段完成。

### 2.3 方向配置（原 DirectionModeComponent，已合并入 TransferPipeline）

活漏斗使用 **TRANSFER 模式**，管理源→目标的传输方向。

**默认方向**：`SlotMapping.UP_TO_DOWN`（上传下）

**12 种预设传输方向**：

| 方向 | 源 | 目标 | 符号 |
|------|-----|------|------|
| 上传下 | ↑ | ↓ | ↑→↓ |
| 下传上 | ↓ | ↑ | ↓→↑ |
| 左传右 | ← | → | ←→→ |
| 右传左 | → | ← | →→← |
| 上传左 | ↑ | ← | ↑→← |
| 上传右 | ↑ | → | ↑→→ |
| 下传左 | ↓ | ← | ↓→← |
| 下传右 | ↓ | → | ↓→→ |
| 左传上 | ← | ↑ | ←→↑ |
| 左传下 | ← | ↓ | ←→↓ |
| 右传上 | → | ↑ | →→↑ |
| 右传下 | → | ↓ | →→↓ |

**WASD 输入**：玩家通过按键修改方向，`WASDSequenceParser` 解析：
- 第1个键 = 源方向（W=↑, A=←, S=↓, D=→）
- 第2个键 = 目标方向
- 例如："WD" → 上传下，"AD" → 左传右

**NBT 存储**（TRANSFER 模式）：
| 键名 | 说明 |
|------|------|
| `src_x`, `src_y` | 源方向偏移坐标 |
| `tgt_x`, `tgt_y` | 目标方向偏移坐标 |

### 2.4 ItemFilterComponent — 物品过滤

通用组件，通过相邻活漏斗自动构建黑白名单过滤规则。任何活物品均可使用。

#### 2.4.1 过滤模式（基于堆叠数量）

活漏斗的堆叠数量决定过滤的精细程度：

| 堆叠数量 | 过滤模式 | 比较级别 | 说明 |
|---------|---------|---------|------|
| 1 | ID 模式 | 仅物品ID | `minecraft:diamond_sword`，不区分NBT |
| 2 | NBT 模式 | 物品 DataComponents | `diamond_sword@hashCode`，区分不同附魔/属性 |
| 3+ | Tag 模式 | 物品标签 | `#minecraft:swords`，按标签类别过滤 |

**设计意图**：堆叠越多 → 过滤越宽泛（Tag涵盖最广），堆叠越少 → 过滤越精确（NBT最具体）。三种模式互斥，各收集各的级别数据，不混杂。

**重要：模式由邻居决定**。活漏斗自身的堆叠数不决定自己的过滤模式，而是决定**别人扫描它时**的精度。扫描时读取邻居活漏斗的堆叠数来确定模式，而非自身堆叠数。这意味着同一个活漏斗被不同堆叠数的邻居扫描时，会以不同精度收集数据。

#### 2.4.2 过滤规则构建

过滤规则由 `HopperFilterBuilder.buildForSlot()` 方法在每 tick 的 `capture()` 阶段统一预计算（由 `ContainerSnapshot` 委托调用），直接读取预计算的 `sourceOf`/`targetOf` 数组，无需重复扫描容器或调用 `SlotResolver`。计算结果由 `LivingHopperFunction.tick()` 与物品上的 `LIVING_HOPPER_FILTER` 组件比对，**仅在规则变化时**回写并 `syncSlotToClients`（稳态零写入；漏斗搬去新容器后旧规则过期，下一 tick 用重建结果自愈），利用 Minecraft 内置的 DataComponent 同步机制推送到客户端，确保 tooltip 在任何场景下都能显示。

> **存储位置沿革**：过滤规则原先存放在 `LivingHopperData.filter` 字段内、每 tick 无条件回写；
> 「tooltip优化，nbt数据简化」（b064865）删除了回写但 tooltip 仍读旧字段 → 黑白名单显示
> 永远为空（2026-09-14 修复）。现迁至**独立组件** `LIVING_HOPPER_FILTER`（变化时回写），
> 并加入 `getIgnoredComponentTypes()`——过滤链是容器环境的派生数据（同一容器里两个漏斗的
> 规则必然不同），不忽略会破坏漏斗堆叠。`LivingHopperData.filter` 降级为遗留兼容字段
> （仅为旧存档反序列化保留，逻辑不读取）。回归测试 `HopperFilterSyncTest`（5 项）。
>
> **新鲜度**：快照按容器修订计数跨 tick 缓存，物品内容（签名）或组件（显式 bump）一变
> 即失效重建，过滤链随之更新——机制详见
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §8.6。
>
> **不落盘**：`LIVING_HOPPER_FILTER` 只有 `networkSynchronized`、无 `persistent`
> （vanilla `MAP_POST_PROCESSING` 先例）——过滤规则是每 tick 可从容器重建的派生数据，
> 持久化没有正确性价值（玩家能打开 GUI 前，容器必然已 tick 重建过），反而徒增存档
> 体积与脏写。组件照常随槽位同步包到达客户端，tooltip 展示链路不变。

**过滤规则语义**：

```
邻居活漏斗的 target 指向我 → 邻居的 source 物品 = 我的黑名单
邻居活漏斗的 source 指向我 → 邻居的 target 物品 = 我的白名单
```

**设计意图**：
- 邻居向我推送物品（target 指向我）→ 我**黑名单**邻居的 source 物品，避免从源重复拉取相同物品
- 邻居从我取物品（source 指向我）→ 我**白名单**邻居的 target 物品，确保我只拉取邻居需要的物品给它

**buildFilterForSlot() 工作流程**（在 `HopperFilterBuilder.buildForSlot()` 中实现，由 `ContainerSnapshot.capture()` 委托调用）：

```java
// HopperFilterBuilder.buildForSlot() — 容器级预计算（由 ContainerSnapshot 委托）
// sourceOf/targetOf 数组已在 capture() 前半段计算完成
for (int i = 0; i < containerSize; i++) {
    if (i == mySlot) continue;

    int neighborSourceSlot = sourceOf[i];
    int neighborTargetSlot = targetOf[i];

    // 非活漏斗槽位（sourceOf 和 targetOf 都为 -1）直接跳过
    if (neighborSourceSlot == -1 && neighborTargetSlot == -1) continue;

    // 读取邻居堆叠数确定过滤模式（无需调用 isLivingHopper/getHopperData/SlotResolver）
    ItemStack neighborStack = ctx.getItem(i);
    int mode = ItemFilterComponent.normalizeMode(neighborStack.getCount());

    // 邻居向我传输 → 邻居的 source 物品 = 我的黑名单
    if (neighborTargetSlot == mySlot) {
        addToFilter(mode, filterItem, blacklist, blComposites, blTags, ...);
    }

    // 邻居从我取物 → 邻居的 target 物品 = 我的白名单
    if (neighborSourceSlot == mySlot) {
        addToFilter(mode, filterItem, whitelist, wlComposites, wlTags, ...);
    }
}
```

**addToFilter() 辅助方法**：根据模式将物品添加到对应的数据集，同时记录来源槽位：

```java
switch (mode) {
    case MODE_COMPONENT -> compositeList.add(compositeKey);  // NBT模式
    case MODE_TAG -> {
        tagList.addAll(collectItemTags(stack));             // Tag模式
        tagSlots.add(refSlot);
    }
    default -> {
        idList.add(itemId);                                 // ID模式
        idSlots.add(refSlot);
    }
}
```

根据当前过滤模式，同一物品会被记录到不同级别的数据集中：

```
ID 模式:   blacklist/whitelist + blacklist_slots/whitelist_slots (物品ID集合 + 槽位)
NBT 模式:  bl_comp/wl_comp (compositeKey = "itemId@componentHashCode")
Tag 模式:  bl_tags/wl_tags + bl_tag_slots/wl_tag_slots (标签字符串集合 + 槽位)
```

#### 2.4.3 优先级判定模型（田忌赛马）

当同一物品同时出现在白名单和黑名单时（可能来自不同邻居活漏斗），采用**基于涵盖范围的优先级模型**解决冲突：

**优先级定义**（涵盖范围越小 → 优先级越高）：

| 级别 | 优先级 | 涵盖范围 | 示例 |
|------|--------|---------|------|
| NBT (Composite) | 3 (最高) | 仅匹配特定NBT变体 | `diamond_sword@12345` |
| ID | 2 (中等) | 匹配该物品所有变体 | `diamond_sword` |
| Tag | 1 (最低) | 匹配标签下所有物品 | `#minecraft:swords` |

**判定规则**：

```
allowsByPriority(item):
  wlPriority = 白名单匹配的最高优先级
  blPriority = 黑名单匹配的最高优先级

  双方都匹配 → 高优先级胜 (wlPriority > blPriority → 放行)
  同优先级   → 黑名单胜 (wlPriority == blPriority → 拒绝)
  仅白名单   → 放行
  仅黑名单   → 拒绝
  双方都不匹配 → 有白名单规则则拒绝，否则放行
```

**遮蔽机制**：同一列表中，NBT级条目会遮蔽ID级条目。例如白名单有 `diamond_sword@12345`（NBT级），则 `diamond_sword`（ID级）不再被视为白名单匹配——因为白名单已明确表示"只要这个特定变体"，ID级的宽泛许可被更精确的NBT级规则取代。

**典型场景**（田忌赛马）：

| 场景 | 白名单 | 黑名单 | 结果 | 原因 |
|------|--------|--------|------|------|
| NBT白 vs ID黑 | `sword@123`(3) | `sword`(2) | ✅ 放行 | 3 > 2 |
| ID白 vs Tag黑 | `sword`(2) | `#weapons`(1) | ✅ 放行 | 2 > 1 |
| NBT白 vs NBT黑 | `sword@123`(3) | `sword@123`(3) | ❌ 拒绝 | 同级黑胜 |
| ID白 vs ID黑 | `sword`(2) | `sword`(2) | ❌ 拒绝 | 同级黑胜 |
| Tag白 vs NBT黑 | `#weapons`(1) | `sword@123`(3) | ❌ 拒绝 | 1 < 3 |
| ID黑 + Tag白 | `#swords`(1) | `diamond_sword`(2) | 钻石剑❌/木剑✅ | 钻石剑: 1<2; 木剑: 1>0 |

#### 2.4.4 配置示例

**ID 模式**（堆叠1）：
```
┌───────┬───────────┬───────┐
│ 铁锭  │ 活漏斗(我) │ 金锭  │
│(source)│           │(target)│
└───────┴───────────┴───────┘
         ↑
    另一个活漏斗
    source=左, target=我
    → 邻居source物品=铁锭 → 我的白名单=["铁锭"]
```

**NBT 模式**（堆叠2）：
```
白名单: wl_comp = ["diamond_sword@12345"]
黑名单: bl_comp = ["diamond_sword@67890"]
→ 附魔A的钻石剑放行，附魔B的钻石剑拒绝
```

**Tag 模式**（堆叠3）：
```
白名单: wl_tags = ["minecraft:swords"]
黑名单: bl_tags = ["minecraft:tools"]
→ 剑类放行，工具类拒绝（如果某物品同时是剑和工具，同级黑胜→拒绝）
```

#### 2.4.5 NBT 存储

| 键名 | 类型 | 说明 |
|------|------|------|
| `blacklist` | ListTag\<String\> | 黑名单物品ID列表 |
| `whitelist` | ListTag\<String\> | 白名单物品ID列表 |
| `blacklist_slots` | ListTag\<Int\> | 黑名单物品对应槽位 |
| `whitelist_slots` | ListTag\<Int\> | 白名单物品对应槽位 |
| `bl_comp` | ListTag\<String\> | 黑名单NBT组合键列表 |
| `wl_comp` | ListTag\<String\> | 白名单NBT组合键列表 |
| `bl_tags` | ListTag\<String\> | 黑名单标签列表 |
| `wl_tags` | ListTag\<String\> | 白名单标签列表 |
| `bl_tag_slots` | ListTag\<Int\> | 黑名单Tag级对应槽位 |
| `wl_tag_slots` | ListTag\<Int\> | 白名单Tag级对应槽位 |

> **注意**：`mode` 不再作为 NBT 键持久化存储。模式由扫描时读取邻居活漏斗的堆叠数动态计算，不写入状态。

> **v8 注意**：以上 FilterData 字段由 `HopperFilterBuilder.buildAll()` 每 tick 预计算后写回 DataComponent，而非由活漏斗自身构建。存档中可能包含旧数据，但每 tick 会被覆盖为最新值。

#### 2.4.6 链式传递机制（双层继承 + 穿透混合模式）

在 v8 架构下，`HopperFilterBuilder.buildForSlot()` 为每个活漏斗独立构建过滤规则。链式传递由**两层机制**协同完成：

**第一层：buildFilterForSlot 中的邻居继承**（在遍历邻居时触发）

当邻居活漏斗指向我时，**先继承该活漏斗自身的完整 FilterData（黑白名单全部），再处理其直接 source/target 物品**：

```
buildFilterForSlot(mySlot):
  for each neighbor i:
    if neighborTargetSlot == mySlot:           ← 邻居向我推送
      if 邻居是活漏斗:
        neighborFilter = ensureFilterBuilt(i)   ← 获取邻居的完整累积FilterData
        mergeFilterData(neighborFilter, 黑名单)  ← 继承邻居的黑名单
        mergeFilterData(neighborFilter, 白名单)  ← 继承邻居的白名单
      inheritFilter(neighborSourceSlot, ...)    ← 处理邻居的直接source物品

    if neighborSourceSlot == mySlot:            ← 邻居从我取物
      if 邻居是活漏斗:
        neighborFilter = ensureFilterBuilt(i)
        mergeFilterData(neighborFilter, 黑名单)
        mergeFilterData(neighborFilter, 白名单)
      inheritFilter(neighborTargetSlot, ...)    ← 处理邻居的直接target物品
```

**为什么需要双层继承？** 活漏斗链不是"首尾相连"的——每个活漏斗的 source/target 指向的是物品槽位，而不是链上的前/后活漏斗。例如：

```
[钻石]  [漏斗1(source=钻石, target=漏斗2)]  [漏斗2(source=铁锭, target=漏斗3)]  [漏斗3]
```

漏斗2 的 source 指向铁锭（不是漏斗1），target 指向漏斗3。如果只追踪 `sourceOf[漏斗2]`，只会找到铁锭，找不到漏斗1传来的钻石。**必须通过继承漏斗2的完整 FilterData** 才能获得漏斗1传来的累积数据。

**第二层：inheritFilter 中的穿透递归**（处理链上活漏斗的 source/target 方向）

`inheritFilter()` 遇到活漏斗时，先继承其 FilterData，再沿其 source/target 方向穿透继续查找：

```
inheritFilter(slot, mode, isBlacklist, ...):
  if slot 越界 or 已访问 → return
  标记已访问

  item = ctx.getItem(slot)
  if item 为空 → return

  if item 是活漏斗:
    1. ensureFilterBuilt(slot) → 获取该活漏斗的完整 FilterData
    2. mergeFilterData(neighborFilter, isBlacklist, ...) → 合并到当前列表
    3. nextSlot = isBlacklist ? sourceOf[slot] : targetOf[slot]  ← 穿透方向
    4. hopperMode = normalizeMode(item.getCount())  ← 用该活漏斗自身的mode
    5. inheritFilter(nextSlot, hopperMode, isBlacklist, ...)  ← 递归穿透
    return

  if item 是其他活物品 → return  ← 不穿透

  addToFilter(mode, item, ...)  ← 非活物品，加入过滤规则
```

**穿透时使用活漏斗自身的 mode**，而非调用者的 mode。这样链上每个活漏斗的过滤精度都被保留：

```
场景：三段漏斗链（不同过滤模式）

  [钻石]  [漏斗1(堆叠1=ID)]  [漏斗2(堆叠2=NBT)]  [漏斗3]
              →→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→

  旧版（穿透模式）：
    漏斗3 的黑名单 = NBT级(钻石)  ← 只保留漏斗2的模式，丢失漏斗1的ID级

  新版（双层继承 + 穿透）：
    漏斗3 → buildFilterForSlot:
      邻居漏斗2的target指向漏斗3 →
        1. ensureFilterBuilt(漏斗2) → 漏斗2的FilterData已包含[ID(钻石)]
        2. mergeFilterData(漏斗2的黑名单+白名单) → 漏斗3得到[ID(钻石)] ✅
        3. inheritFilter(sourceOf[漏斗2]=铁锭, ...) → 额外物品也加入
    漏斗3 黑名单 = [ID(钻石)] + 铁锭相关规则 ✅
```

**递归构建与循环防护**：
- `ensureFilterBuilt()` 的 `building` 集合：检测循环依赖（A→B→A），返回 `FilterData.EMPTY` 阻断
- `inheritFilter()` 的 `visited` 集合：防止同一链路重复访问
- 双重防护确保任意拓扑结构下不会无限递归

> **v7 变更**：链式传递经历了三次迭代：(1)"穿透模式"（`collectFilterItems` 沿链递归找非活物品，用邻居mode重新分类）→(2)"继承模式"（`inheritFilter` 只合并邻居FilterData，但漏斗链不是首尾相连导致多跳传递失败）→(3)"双层继承+穿透混合模式"（`buildFilterForSlot` 中先继承邻居活漏斗的完整FilterData，`inheritFilter` 中再沿source/target穿透继续查找），既保留了链上每个活漏斗的过滤精度，又解决了非首尾相连链的多跳传递问题。

#### 2.4.6a 新旧架构链式传递对比

**旧架构（组件模式）**：时序传播，每 tick 传播一跳。

旧架构的 `inheritFilter()` 读取邻居活漏斗**上一 tick 写入的 ComponentState**，因此过滤规则沿链逐 tick 传播：

```
Tick 1: 漏斗1 构建 → 黑名单=[ID(钻石)]  ← 写入 ComponentState
Tick 2: 漏斗2 继承漏斗1(上一tick的状态) → 黑名单=[ID(钻石)]  ← 写入 ComponentState
Tick 3: 漏斗3 继承漏斗2(上一tick的状态) → 黑名单=[ID(钻石)]
```

旧架构的 `inheritFilter` 还有一个条件：`sourceOf[hostSlot] != slot && targetOf[hostSlot] != slot`，即**只继承非我方向的邻居活漏斗**。这看似限制了传播，但实际上每个活漏斗在 tick 中同时处理黑名单和白名单两个分支，每个分支都会触发继承，因此链上所有方向都能传播。

**新架构（ContainerSnapshot）**：即时传播，1 tick 内递归穿透完成。

新架构的 `inheritFilter()` 在 `ContainerSnapshot.capture()` 阶段递归构建，遇到活漏斗时先继承其 FilterData，再沿 source/target 穿透继续查找，1 tick 内整条链的过滤规则全部构建完成。

| 维度 | 旧架构（时序传播） | 新架构（即时传播） |
|------|-------------------|-------------------|
| **传播方式** | 每 tick 读上一 tick 的 ComponentState | 递归穿透，1 tick 内完成 |
| **N 段链延迟** | N ticks（链越长延迟越大） | 0 ticks（始终即时） |
| **数据来源** | 邻居的 ComponentState（上一 tick 写入） | ContainerSnapshot 实时递归构建 |
| **循环风险** | 无（天然隔 tick 隔离） | 需 visited + building 双重防护 |
| **一致性** | 可能短暂不一致（链中间还没传播到） | 始终一致 |
| **穿透能力** | 依赖时序累积，每跳只看邻居上一 tick 的结果 | 递归穿透，一次构建完整链 |

**新架构的关键进步**：消除了传播延迟。旧架构中，新放入一个活漏斗后，需要等待 N 个 tick 才能让过滤规则传播到链末端。新架构中，下一个 tick 所有活漏斗就能获得完整的过滤规则。

#### 2.4.7 关键方法

**HopperFilterBuilder 中的过滤方法**：
- `buildAll()` — 容器级预计算入口，遍历所有活漏斗槽位调用 `buildForSlot()`
- `buildForSlot()` — 为单个活漏斗构建完整 FilterData。当邻居活漏斗指向我时，先通过 `ensureFilterBuilt()` 继承其完整 FilterData（黑白名单全部），再通过 `inheritFilter()` 处理其直接 source/target 物品
- `inheritFilter()` — 遇到活漏斗时：先通过 `ensureFilterBuilt()` 获取其 FilterData 并合并（`mergeFilterData`），再沿该活漏斗的 source/target 方向穿透递归（用该活漏斗自身的 mode 分类）；遇到非活物品时通过 `addToFilter()` 加入过滤规则
- `ensureFilterBuilt()` — 确保指定槽位的 FilterData 已构建，支持递归构建和循环依赖检测
- `mergeFilterData()` — 将源 FilterData 的黑白名单数据合并到目标列表，去重
- `addToFilter()` — 根据过滤模式将物品添加到对应数据集，同时记录来源槽位

**ItemFilterComponent 中的判定方法**：
- `allows(FilterData, ItemStack)` — 静态方法，统一优先级判定，判断物品是否允许通过
- `allowsItemType(ComponentState, String)` — 静态方法，仅基于ID的优先级判定（无NBT匹配能力）
- `calcMatchPriority()` — 计算单侧（白/黑）匹配的最高优先级
- `hasNbtEntriesForId()` — 检测NBT级条目是否遮蔽ID级条目
- `matchesAnyTag()` — Tag级匹配检查
- `appendFilterTooltip()` — 显示过滤模式和名单内容，使用 `appendFilterEntries()` 辅助方法减少黑白名单重复渲染
- `normalizeMode()` — 根据堆叠数量确定过滤模式（1=ID, 2=NBT, 3+=Tag）
- `getCompositeKey()` — 计算物品的 NBT 复合键（itemId@componentHashCode）
- `collectItemTags()` — 收集物品的所有标签

---

## 3. 传输流程

### 3.1 完整 tick 流程

```
LivingHopperFunction.tick()                                 [每 tick]
  │
  ├─ 读取活漏斗数据 (LivingHopperData)
  │   ├─ DirectionTransferData → 解析 sourceSlot/targetSlot
  │   └─ TransferData → 检查冷却状态
  │
  ├─ 冷却中？→ tick() 递减冷却，return
  │
  ├─ filter = tick.snapshot.getFilterOf(slot)               [直接读容器快照]
  │   └─ FilterData 已在 HopperFilterBuilder.buildAll() 中预计算
  │
  ├─ TransferPipeline.execute(request)                       [统一传输入口]
  │
  ├─ TransferPipeline.execute()                             [传输执行]
  │   ├─ 越界？→ CrossContainerTransfer.execute()           [跨容器传输]
  │   │         └─ 详见第 6 章
  │   └─ 未越界 → executeInContainer()                       [容器内传输]
  │       ├─ [1] 自环防护 (sourceSlot == targetSlot)
  │       ├─ [2] 级联防护 (transferredTargetSlots 包含 sourceSlot)
  │       ├─ [3] 源槽位为空？→ return false
  │       ├─ [4] 源是活物品（非存储容器）？→ return false
  │       ├─ [5] 物品过滤检查 (ItemFilterComponent.allows())
  │       ├─ [6] Container 模拟玩家取出 (canTakeItem)
  │       ├─ [7] source = SlotAccessorFactory.create(sourceSlot)
  │       ├─ [8] target = SlotAccessorFactory.create(targetSlot)
  │       ├─ [9] transferAmount = Math.min(stackSize, maxTransfer)
  │       ├─ [10] Container 模拟玩家放入 (simulateExtract + canPlaceItem)
  │       ├─ [11] EnderRouteManager.resolveTarget() 决策
  │       │   ├─ NOT_ENDER_CHEST → SlotAccessor.transfer(source, target, transferAmount)
  │       │   │   ├─ extract → insert → rollback
  │       │   │   └─ markTransferred → sync
  │       │   ├─ REJECTED → false
  │       │   └─ HANDLED → true
  │       └─ 返回传输结果
  │
  ├─ 传输成功？→ 设置冷却 (max(1, 8 - stackCount/8))
  │
  ├─ 保存数据 + 同步客户端
  │
  └─ cleanupStaleRoutes()                                   [路由清理]
      └─ 清理失效的活末影箱路由条目
```

**关键变化**（v8 → v9）：

**v9 变化**（2026-08-17）：
- **Container 接口模拟玩家操作**：`executeInContainer()` 新增 `Container.canTakeItem()` 和 `Container.canPlaceItem()` 检查（步骤 [6] 和 [10]），通过 `CrossContainerTransfer.getNeighborContainer()` 获取容器实例，利用原版 Container 接口模拟玩家取出/放入逻辑，过滤不可交互的槽位（如幽灵槽、输出槽等），解决跨模组容器兼容性问题
- **`getNeighborContainer` 空安全**：添加 `neighborPos == null` 检查，修复玩家背包场景下 `getBlockPos()` 返回 null 导致的 NPE 崩溃
- **`transferAmount` 变量提取**：`Math.min(stackSize, maxTransfer)` 从 3 次重复计算减少为 1 次
- **末影箱决策统一**：`EnderRouteManager.resolveTarget()` 返回 `Decision` 枚举（`NOT_ENDER_CHEST` / `REJECTED` / `HANDLED`），`TransferPipeline` 通过 `switch` 分流，替代了分散的 `registerRoute` + `doTransfer` 调用
- **`isTransferableSource()` / `isStorageContainer()` 辅助方法**：提取活物品隔离判断为独立方法，提升可读性

**v8 变化**（2026-08-16）：
- **核心原则：计算归容器，展示归物品**。`FilterData` 由 `HopperFilterBuilder` 容器级预计算（衍生数据），但写回 `LivingHopperData.filter`（DataComponent），利用 Minecraft 内置同步机制推送到客户端
- `ContainerSnapshot.capture()` 中一次性计算所有活漏斗的 `sourceOf`/`targetOf`，过滤构建委托给 `HopperFilterBuilder.buildAll()`，每 tick 只算一次
- `TransferPipeline` 统一容器内传输和跨容器传输入口，消除 `LivingHopperFunction.executeTransfer` 与 `CrossContainerTransfer.execute` 的双向耦合
- `EnderRouteManager` 集中管理活末影箱路由注册/提取/验证，`TransferPipeline` 通过 `EnderRouteManager.registerRoute()` 注册路由
- `LivingHopperFunction.tick()` 从 `tick.snapshot.getFilterOf(slot)` 读取过滤规则，写回 `data.withFilter(filter).withTransfer(transfer)`
- `LivingHopperData` 保留 `FilterData filter` 字段，tooltip 直接从 `data.filter()` 读取
- `buildFilterChain`/`addToFilter` 方法已提取到 `HopperFilterBuilder`，`ContainerSnapshot` 仅委托调用
- 新增 `PerfMetrics.recordTransfer()` 性能记录

### 3.2 SlotAccessor 统一传输流程

所有同容器内传输（无论源/目标是普通槽位还是活箱子）使用同一流程：

```
source.extract(amount, filterType)
  │
  ├─ PlainSlotAccessor: 直接 ContainerContext.setItem() 移除
  ├─ LivingChestAccessor: getMergedStorage() → 过滤查找 → extractItem(targetType)
  └─ null: 活物品（非活箱子），跳过传输
      │
      ▼
  extracted.isEmpty()? → return false（无物品可提取）
      │
      ▼
target.insert(extracted)
  │
  ├─ PlainSlotAccessor: 合并到现有堆叠或放入空槽位
  ├─ LivingChestAccessor: insertItem() 原子操作
  └─ 返回实际插入数量
      │
      ├─ inserted == 0 → source.rollback(extracted) → return false
      ├─ inserted < count → source.rollback(remaining) → 退回多余
      └─ inserted == count → 完全成功
      │
      ▼
target.markTransferred() → 级联防护标记
source.sync() → 同步源槽位
target.sync() → 同步目标槽位
```

### 3.3 跨容器传输

跨容器传输通过 `CrossContainerTransfer` 处理，不经过 `SlotAccessor` 流程。详见第 6 章。

---

## 4. 冷却机制

### 4.1 冷却计算

```
baseCooldown = 8 ticks（配置项）
actualCooldown = max(1, baseCooldown - stackSize / 8)
```

**设计意图**：
- **阶梯加速**：每多堆叠 8 个活漏斗，冷却减少 1 tick
- **上限控制**：最快 1 tick（20次/秒），防止无限加速

**示例**：
| 堆叠数 | 冷却时间 | 传输速率 |
|--------|---------|---------|
| 1-7 | 8 ticks | 2.5 次/秒 |
| 8-15 | 7 ticks | 2.86 次/秒 |
| 16-23 | 6 ticks | 3.33 次/秒 |
| 24-31 | 5 ticks | 4 次/秒 |
| 32-39 | 4 ticks | 5 次/秒 |
| 40-47 | 3 ticks | 6.67 次/秒 |
| 48-55 | 2 ticks | 10 次/秒 |
| 56+ | 1 tick | 20 次/秒（上限） |

### 4.2 冷却设置策略

**关键设计**：传输后**无条件**设置冷却，无论传输是否成功。

```java
// tick() 方法
TransferPipeline.execute(request);
// 无论成功与否都设置冷却
int actualCooldown = calculateCooldown(baseCooldown, hostStack.getCount());
state.setInt(KEY_COOLDOWN, actualCooldown);
```

**原因**：如果过滤器拦截了物品，`TransferPipeline.execute` 返回 false。若不设冷却，下一 tick 立即重试，形成无限循环（特别是活箱子预查过滤场景，每 tick 都会重新查询一次）。

**特殊情况**：源槽位为空时**不设冷却**，因为此时没有物品可传输，应该等待新物品加入后立即响应。

---

## 5. 级联防护

### 5.1 问题场景

当多个活漏斗组成链式传输时（如"上传下"的活漏斗 A 上面又有"上传下"的活漏斗 B），可能出现：
```
A 将物品放入槽位 X → B 在同一 tick 内从槽位 X 取走物品
→ 物品瞬间穿过整个链，而非逐步传输
```

### 5.2 解决方案

通过 `transferredTargetSlots` 集合标记：

```
ContainerContext 维护一个 Set<Integer> transferredTargetSlots
  │
  ├─ 每次成功传输后，将目标槽位加入集合
  │   transferredTargetSlots.add(targetSlot)
  │
  └─ 后续活漏斗检查源槽位是否在集合中
      if (transferredTargetSlots.contains(sourceSlot)) → 跳过
```

**注意**：`transferredTargetSlots` 在同一 tick 内有效，下一 tick 清空。

### 5.3 跨容器级联防护

`CrossContainerTransfer.pullFromNeighbor()` 在成功拉取物品后，同样调用 `markSlotTransferred(containerCtx, targetSlot)` 标记目标槽位，防止跨容器场景下的级联穿透：

```
容器A: [物品]   容器B: [漏斗A↑↓] [漏斗B↑↓] [空槽]
                    ↑ pullFromNeighbor 从容器A拉取

修复前：漏斗A拉取→放入槽位X → 漏斗B同tick取走 → 1tick跳2格
修复后：漏斗A拉取→放入槽位X → markSlotTransferred(X) → 漏斗B跳过
```

---

## 6. 跨容器传输

### 6.1 触发条件

当活漏斗的源槽位或目标槽位**超出当前容器范围**时触发（`SlotResolver` 返回 -1）。

### 6.2 三种跨容器模式

| 模式 | 条件 | 行为 |
|------|------|------|
| `pullFromNeighbor` | 源越界，目标未越界 | 从相邻容器拉取物品到当前容器（目标槽被槽位交互接管时走交互，见 6.2.1） |
| `pushToNeighbor` | 目标越界，源未越界 | 从当前容器推送物品到相邻容器（邻居槽被槽位交互接管时走交互，见 6.2.1） |
| `transferBetweenNeighbors` | 都越界 | 在两个相邻容器之间直接传输（与 push 共用 tryPushToNeighbor） |

### 6.2.1 槽位交互分发（注册式，2026-09-15）

**「货物 × 目标槽」的替代语义**（骨粉 → 活耕地 = 施肥是最初的一条）统一走注册表
`SlotInteractions`，三处传输分支只调分发器，**不再各自硬编码方程**：

| 场景 | 入口 | 货物是否已知 |
|------|------|-------------|
| 容器内 `TransferPipeline.executeInContainer` | `SlotInteractions.tryInteract(源, 货物, 目标槽, level)` | 是（源槽物品） |
| 跨容器推送 `tryPushToNeighbor` 槽位循环内嵌（顺带覆盖邻居间直传） | 同上 | 是（模拟提取结果） |
| 跨容器拉取 `pullFromNeighbor` 前置分支 | `SlotInteractions.tryInteractFromNeighbor(邻居handler, 邻居pos, 目标槽, level, filter)` | 否（遍历邻居槽位找匹配货物） |

**协议**（三条，实现在 `SlotInteraction` 接口 javadoc）：

1. **模拟优先**：`simulateExtract(consumeAmount)` 试算 → `interact` 生效 → 才真 `extract` 扣货。
   交互返回 false（含 equals 零空转）→ **不扣货、不设冷却**，调用方继续走通用路径。
2. **equals 零空转**：耕地无实际变化（如已冻结的成熟耕地）返回 false → 不烧骨粉。
3. **只动 target**：实现方就地改目标槽组件；同步由调用方负责。

**货物准入：活物品不作货物（隔离规则，唯一定义点 `SlotInteractions.isEligibleCargo`）**

施肥的语义是「活漏斗用**传输能力**把骨粉送进活耕地」——它属**传输语义**，因此必须受
漏斗自己的货物规则约束。**活骨粉是活物品 ⇒ 不是合法货物 ⇒ 漏斗不给它施肥**
（活骨粉的手动用途在 GUI 右键，那里本就要求活化）。

| 场景 | 普通骨粉 | 活骨粉（活物品） |
|------|---------|----------------|
| 容器内 → 活耕地 | ✓ 施肥 | ✗ 不接管（`create` 对它返回 null，准入谓词也拒） |
| 跨容器推送（源在本容器）→ 邻居活耕地 | ✓ 施肥 | ✗ `pushToNeighbor` 入口按隔离规则 `return false` |
| 跨容器拉取（源在邻居）→ 本容器活耕地 | ✓ 施肥 | ✗ `tryInteractFromNeighbor` 经准入谓词拒绝 |
| 邻居间直传（源/目标各在一邻居） | ✓ 施肥 | ✗ 源邻居循环跳过活物品 |
| 活物品 → 邻居空槽/普通槽（任何货物） | — | ✗ 拒绝且不扣货（`interactionOnly` 自守分支） |

**规则表述**：`isEligibleCargo(stack) = !isLivingItem(stack) || 活箱子 || 活末影箱`
（活箱子/活末影箱是存储容器，本身可被搬运）。它是**唯一定义点**——传输层
（`TransferPipeline.isTransferableSource` 直接委托）与交互层（`canInteract` /
`tryInteract` 两个入口）共用，避免隔离规则两处漂移。

**为什么放在交互层入口而不是只靠调用点**（2026-09-15 教训）：调用点顺序是脆弱的——
容器内路径的交互分发原本在隔离检查**之前**（靠谓词没写活物品检查而「意外放行」），
一次重构就把它变成 bug。规则收在共享入口后，**任何调用点顺序变更都不会绕过它**。
`tryPushToNeighbor` 另留一道循环自守（`interactionOnly`）：非合法货物绝不进入通用插入/
合并，即使未来出现第三个调用方忘了在入口拦。

**源槽访问器怎么拿**：三处都用既有的工厂入口，无需专用变体——
容器内 `create(...)`（非箱类活物品返回 `null`，正好与准入规则同口径）、
推送 `create(...)`、拉取 `createForNeighbor(...)`。交互层不再需要「不拦活物品」的访问器
（那是上一版为「活骨粉放行」加的，随准入收紧一并移除）。

**分配守卫 `canInteract(cargo, target)`**：纯谓词查询（不建 Accessor、无副作用，已内建
准入）。**真正的分配节省在拉取方向**——`tryInteractFromNeighbor` 用它建 Accessor **之前**
筛邻居槽，绝大多数组合不匹配，每轮最多省 27 次（邻居槽数）。**容器内路径**
（`TransferPipeline`）的源槽 Accessor 在交互分发之前就已创建、且下方通用路径要复用同一
实例，故那里的 `canInteract` 只是廉价早退、不省分配；**推送方向**（`tryPushToNeighbor`）
的源槽 Accessor 同样由调用方提供，故直接调 `tryInteract`、不再前置谓词。

**过滤口径**：三处交互源槽都带 `FilterData`——黑白名单是漏斗的货物筛选口径，交互同样是
消耗货物，理应受同一约束（容器内路径原先在过滤检查之前，收编时统一到过滤之后）。
被过滤的货物在 `simulateExtract` 阶段就返回空栈 → 交互不接管、不扣货、不产生 rollback 抖动。

**为什么拉取方向需要独立入口**：该方向没有「源槽」——源在邻居容器里，货物得自己找，
所以 `tryInteractFromNeighbor` 对邻居每个槽位先跑 `canInteract` 筛选（纯谓词，纳秒级）
再走协议。

**为什么三处都必须前置到通用路径之前**（2026-09-15 实测 bug 根因）：活耕地是活物品、
非存储容器，`SlotAccessorFactory.create` 对非箱类活物品直接返回 `null` → 通用传输
（`tryPullFromNeighbor` / 通用插入）对活耕地目标槽**必然失败**。漏掉任一处调用点，
该方向就完全失效——本次就是拉取方向漏了，表现为「跨容器骨粉 → 同容器活耕地」不施肥
（反向推送正常，详见 living-farmland-tech.md §11.15）。

**扩展方式**（新增交互 = 1 个实现类 + 1 行注册，零传输代码改动）：

```java
public class MyInteraction implements SlotInteraction {
    @Override public boolean matches(ItemStack cargo, ItemStack target) {   // 纯谓词
        return cargo.is(Items.WATER_BUCKET) && target.is(Items.FARMLAND) && LivingItemManager.isLivingItem(target);
    }
    @Override public boolean interact(ItemStack cargo, ItemStack target, ServerLevel level) { ... }  // 只改 target
    // 可选：consumeAmount() 默认 1
}
SlotInteractions.register(new MyInteraction());   // 内置条目在 SlotInteractions 静态块注册
```

**其余要点**（三处共用）：

- 推送方向无专属遍历、无专属 handler 解析（原第一版曾写独立 `tryFertilizeToNeighbor`
  接管整个推送，无耕地邻居时拦断普通货物推送，已按奥卡姆剃刀重构成内嵌形态）
- 目标槽已冻结（equals 零空转）→ 不接管、不扣货；邻居无任何匹配目标 → 循环里无槽命中，
  普通货物推送照常（骨粉入箱，无回归）
- `getStackInSlot` 是 BE 容器实时引用（InvWrapper 直通），组件修改即刻生效，无需回写
  handler；推送方向邻居侧 GUI 同步由目标物品所在容器自身 tick 兜底，容器内与拉取方向
  （目标在本容器）由调用点 `syncSlotToClients` 主动推

**改动教训（2026-09-15 三次真实回归，改这一层前先读）**：

- **「路径 A 实现了 X」≠「X 覆盖所有路径」**。涉及方向 / 分支枚举的能力必须**逐条点名验证**
  —— `pushToNeighbor` 与 `pullFromNeighbor` 是两份独立实现，「一处内嵌两路径共用」不覆盖拉取方向。
- **重构提取公共入口时，逐个核对原调用点的隐式前提是否被新入口继承**。曾有一次回归的隐式前提是
  「交互货物不经过 `SlotAccessorFactory` 的活物品守卫」；测试只覆盖公共逻辑（`tryFertilize`）
  而没覆盖调用点装配（工厂选型）⇒ **全绿而回归**。
- **规则要收在共享入口，不要靠调用点顺序**：靠顺序执行时，一次重排就静默改变语义
  （容器内交互分发原本排在隔离检查之前 → 靠「谓词没写检查」意外放行）。
- **口径要问清归属**：一件事属「传输语义」还是「交互语义」，决定它受哪套规则约束。施肥曾因被当成
  「交互」而给活物品开洞，实际它是**传输语义** ⇒ 受货物准入约束。**拿不准问用户**（设计决策，非实现细节）。
- **性能回退要对照原实现数分配**，不要只看「逻辑等价」（`canInteract` 的分配节省逐路径不同，见上）。
- **口径翻转后，先 `grep` 扫掉文档里的旧结论**（`既有边界|仍被拦下|待复测` 之类），
  否则下一轮会照着错的文档再犯一次。

### 6.2.2 跨容器能力覆盖矩阵与结构规则（2026-09-15 全量审计）

> 📄 **方向转换（`Pos2D` ↔ `Direction`，即 `gridToWorld` / `worldToGrid`）的推导、映射表与相关类**
> 见 [block-facing.md §6~§7](../reference/block-facing.md) —— 本节的「方向格」依赖它。

§11.15 那个 bug 的教训值得上升为结构规则——**「特殊槽位识别只在 containerCtx 一侧生效」**：

| 侧 | 访问器创建入口 | 能识别什么 |
|----|--------------|-----------|
| 本容器侧（源或目标都算） | `SlotAccessorFactory.create` | 活箱子/活末影箱（注册式 Provider）；**且先拦掉其它活物品（return null）** |
| 邻居侧（不论源还是目标） | `SlotAccessorFactory.createForNeighbor` | **只认裸 IItemHandler**——普通插入/提取，不认活箱子/活末影箱/活耕地 |

**覆盖矩阵**（✓ = 已实现；✗ = 不支持，未在任何文档声称，属设计边界）：

| 能力 | 容器内 | 跨容器推送（源本容器） | 跨容器拉取（源在邻居） | 邻居间直传 |
|------|--------|---------------------|---------------------|-----------|
| 普通物品传输 | ✓ `executeInContainer` | ✓ `tryPushToNeighbor` | ✓ `tryPullFromNeighbor` | ✓ |
| 槽位交互（普通骨粉 → 活耕地等） | ✓ [3.5] | ✓ 内嵌循环 | ✓ `tryInteractFromNeighbor` | ✓（共用 push） |
| 槽位交互（**活物品货物**，如活骨粉） | ✗ 准入拒绝 | ✗ 入口隔离守卫 | ✗ 准入拒绝 | ✗ 源邻居跳过 |
| 活物品作为**普通货物**被传输 | ✗ | ✗ | ✗ 活物品隔离 | ✗ |
| 活箱子作**源**（取虚拟存储） | ✓ | ✓（源在本容器，工厂识别） | ✗ 活物品隔离 | ✗ 活物品隔离 |
| 活箱子作**目标**（写虚拟存储） | ✓ | ✗ 邻居槽不识别 | ✓ `pullFromNeighborToLivingChest` | ✗ |
| 活末影箱作**源**（按路由提取） | ✓ | ✓（源在本容器，工厂识别） | ✗ 活物品隔离 | ✗ |
| 活末影箱作**目标**（注册路由） | ✓ | ✗ 邻居槽不识别 | ✓ `resolveCrossContainerTarget` | ✗ |

**读法**：本容器侧的特殊槽位（不论它当源还是当目标）都有支持；邻居侧的特殊槽位
**默认全不支持**——唯一例外是槽位交互，因为它靠注册表的 `matches` 显式认目标槽，
而不是靠访问器工厂。这正解释了为什么施肥能在推送方向「自然涌现」而拉取方向必须补分支：
**它认的是邻居侧，而邻居侧没有工厂。**

**槽位交互的两处调用点已被分发器覆盖**（§6.2.1），新增交互无需再碰传输代码；
若要新增「邻居侧特殊**存储**」类能力（如把物品推进邻居容器里的活箱子），
则仍须按「容器内 / 推送 / 拉取」三处点名实现——目前矩阵中那 4 个 ✗ 是既有的、
未被需求触发的边界（活箱子虚拟存储只对同容器展开）。

**验证方式**：不要靠代码结构推断覆盖范围——`grep -n "SlotInteractions.tryInteract" src/main/java`
数调用点（应为 3：管道 1 + 跨容器 2），或写方向化单测
（`CrossContainerTransferFertilizeTest` 两个入口 + 三个方向各覆盖一遍 +
`SlotInteractionCargoGateTest` 钉住货物准入真值表与「活骨粉不施肥」）。

### 6.3 GUI→世界方向转换

容器内的 GUI 方向（上下左右）需要根据方块朝向转换为世界方向：

```
GUI方向 → 基准世界方向(朝北时) → 根据方块朝向旋转

GUI上(UP)    → 世界南(SOUTH)  → 旋转后
GUI下(DOWN)  → 世界北(NORTH)  → 旋转后
GUI左(LEFT)  → 世界东(EAST)   → 旋转后
GUI右(RIGHT) → 世界西(WEST)   → 旋转后
```

旋转规则：
- 朝北：0次旋转
- 朝东：顺时针90°
- 朝南：180°
- 朝西：逆时针90°（顺时针270°）

### 6.4 大箱子处理

大箱子（双箱合并）在世界里占 **N（=2）个方块**，跨容器传输的本质问题是：
**把 GUI 的 4 个方向映射到世界的外部面**。

1. **检测大箱子**：通过 `ChestBlock.TYPE` 属性判断（`DoubleChestPositions.find`）
2. **获取合并容器**：`ChestBlock.getContainer()` 获取双箱合并后的完整容器

**GUI 4 方向 → 世界 6 面**：两个半箱共 8 个侧面，贴合的 2 个是内部面，剩 **6 个外部面**。

| GUI 方向 | 世界轴 | 外部面数 | 说明 |
|---------|--------|---------|------|
| 左 | 连接轴（`connectedDir`） | 1 | 只有一个半箱该方向朝外；另一半箱在这个方向上看到的是**贴合面**（内部 → 挡掉） |
| 右 | 连接轴反向 | 1 | 同上，反过来 |
| 上 | facing 轴（`-facing`，背面） | **2** | 两个半箱各自的背面 —— 是**两个不同的方块** |
| 下 | facing 轴（`+facing`，正面） | **2** | 两个半箱各自的正面 —— 是**两个不同的方块** |

**基准块候选（2026-09-22 重写）**：

旧实现按**方向**选单个半箱（下/右 → 第一块，上/左 → 第二块），与漏斗所在槽位无关
⇒ 漏斗在后半箱时向下推送，推出去的是**前半箱正面**的方块：静默推错面、不报错。

新实现 `getBasePosCandidates()` 返回**按优先级排序的候选基准块列表**：

1. **首选** = 发起传输的槽位所属的那块：`hostSlot / (containerSize / 块数)`；
2. 其余块按位置顺序作**备选**；
3. 调用方**逐个尝试** —— 该面无容器（或该面是内部贴合面）就退到下一个面。

⇒ "上/下"因此能覆盖两个面（功能更丰富），"左/右"仍由内部防护挡掉指向另一半的那侧。
三方块 / 四块容器由 `containerSize / 块数` 天然通用。

**为什么这里不必为「槽位段 ↔ 位置顺序」做内容探测**：该顺序由各模组 `IItemHandler`
的合并方式决定（未必等于 vanilla `ChestBlock.getContainer` 的顺序）。有了**备选面兜底**，
假设反了最多影响优先级，功能仍然成立。对比 §10.25 的容器内路径 —— 那条路没有备选可用，
所以必须靠体系一致性探针。

**内部传输防护**：`getNeighborHandler()` 中 `chestPositions.contains(neighborPos)` →
指向另一半的方向返回 `null`。
**源=目标自传防护**：`transferBetweenNeighbors` 中若源/目标邻居位置相同则跳过
（多方块候选组合下更容易触发）。

### 6.5 活箱子/活末影箱在跨容器中的过滤

跨容器传输中涉及活箱子/活末影箱时，两者统一使用 `pushFromLivingStorageToNeighbor()` 方法：

- **pushFromLivingStorageToNeighbor**：活箱子/活末影箱→相邻容器
  - 创建 `SlotAccessor` → `simulateExtract()` 获取物品类型用于 `canPlaceItem` 过滤
  - 委托 `tryPushToNeighbor()` 遍历邻居槽位并执行 `SlotAccessor.transfer()`
- **pullFromNeighborToLivingChest**：相邻容器→活箱子
  - 在遍历相邻容器物品时直接过滤跳过
- **pullFromNeighborToLivingEnderChest**：相邻容器→活末影箱
  - 遍历相邻容器物品 → 构建 `EnderChannelEntry` → `registry.insert()`
  - 不实际提取物品，只注册路由条目，物品始终留在源容器中

---

## 7. 物品过滤（黑白名单）

### 7.1 设计理念

过滤规则由**相邻活漏斗的布局**自动推导，无需手动配置：

- 如果邻居活漏斗**向我传输**（邻居的 target 指向我），邻居的 source 物品就是我的黑名单
- 如果邻居活漏斗**从我取物**（邻居的 source 指向我），邻居的 target 物品就是我的白名单

过滤的精细程度由活漏斗堆叠数量决定（详见 2.4.1 过滤模式）。

### 7.2 过滤规则构建（ContainerSnapshot 预计算 + DataComponent 写回）

过滤规则构建逻辑位于 `HopperFilterBuilder.buildForSlot()`，作为容器级预计算的一部分（由 `ContainerSnapshot` 委托调用）。

**核心原则：计算归容器，展示归物品**。`FilterData` 是衍生数据（由邻居活漏斗的配置和物品推导），其**计算**归容器（`ContainerSnapshot` 统一预计算），但**展示**借物品的 DataComponent 通道同步到客户端。这样既保留了容器级预计算的性能优势，又确保 tooltip 在任何场景下（光标上、地上、聊天框）都能正常显示。

**构建流程**（在 `ContainerSnapshot.capture()` 中一次性完成）：

```java
// ContainerSnapshot.capture() 中：
// 1. 预计算 sourceOf/targetOf 数组（活漏斗连接图）
// 2. 预计算 filterOf 数组（每个活漏斗的过滤规则）
FilterData[] filterOf = buildAllFilters(ctx, containerSize, sourceOf, targetOf);

// buildAllFilters() 遍历所有活漏斗槽位，为每个活漏斗构建过滤规则
for (int slot = 0; slot < containerSize; slot++) {
    if (sourceOf[slot] == -1 && targetOf[slot] == -1) {
        // 非活漏斗直接跳过；活漏斗即使 source/target 都越界（角落位置），
        // 仍需构建 filter，因为其他漏斗可能连接到它
        if (!LivingHopperFunction.isLivingHopper(ctx.getItem(slot))) continue;
    }
    filterOf[slot] = buildFilterForSlot(slot, ctx, containerSize, sourceOf, targetOf);
}

// buildFilterForSlot() 与旧版 buildFilterChain() 逻辑相同
// 但作为容器级预计算，每 tick 只执行一次
for (容器中每个槽位) {
    if (槽位 == 我的槽位) continue;

    int neighborSourceSlot = sourceOf[i];
    int neighborTargetSlot = targetOf[i];

    // 非活漏斗槽位直接跳过（无需 isLivingHopper 判断）
    if (neighborSourceSlot == -1 && neighborTargetSlot == -1) continue;

    // 读取邻居堆叠数确定过滤模式（无需 getHopperData/SlotResolver.resolve）
    int mode = ItemFilterComponent.normalizeMode(neighborStack.getCount());
    if (邻居的 targetSlot == 我的槽位) {
        // 邻居向我传输 → 邻居的 source 物品 = 我的黑名单
        if (mode == MODE_TAG) {
            blTags.addAll(物品标签)
        } else if (mode == MODE_COMPONENT) {
            blComp.add(compositeKey)
        } else {
            blacklist.add(邻居source槽位的物品ID)
        }
    }

    if (邻居的 sourceSlot == 我的槽位) {
        // 邻居从我取物 → 邻居的 target 物品 = 我的白名单
        if (mode == MODE_TAG) {
            wlTags.addAll(物品标签)
        } else if (mode == MODE_COMPONENT) {
            wlComp.add(compositeKey)
        } else {
            whitelist.add(邻居target槽位的物品ID)
        }
    }
}
```

### 7.3 优先级判定

当同一物品同时出现在白名单和黑名单时，采用**田忌赛马式优先级模型**（详见 2.4.3）：

```
allowsByPriority(item):
  wlPriority = calcMatchPriority(白名单侧)  // NBT=3 > ID=2 > Tag=1
  blPriority = calcMatchPriority(黑名单侧)

  双方都匹配 → 高优先级胜
  同优先级   → 黑名单胜
  仅一方匹配 → 匹配方决定
  双方都不匹配 → 有白名单规则则拒绝，否则放行
```

### 7.4 过滤生效位置

| 传输场景 | 过滤方式 |
|---------|---------|
| 同容器内传输 | `executeInContainer()` 步骤 [5]：`ItemFilterComponent.allows(filter, sourceStack)` |
| 跨容器拉取（普通→当前容器） | `tryPullFromNeighbor()` 遍历时跳过 `LivingItemManager.isLivingItem(stack)` |
| 跨容器拉取→活箱子 | `pullFromNeighborToLivingChest()` 中 `FilteredSlotAccessor` 自动过滤 |
| 跨容器推送（活箱子/活末影箱→邻居） | `pushFromLivingStorageToNeighbor()` 中 `SlotAccessorFactory.create(..., filter)` 传递过滤 |
| 跨容器邻居间直接传输 | `transferBetweenNeighbors()` 中 `SlotAccessorFactory.createForNeighbor(..., filter)` 传递过滤 |

### 7.5 活箱子过滤的特殊处理

活箱子场景采用**预查 + 类型提取**策略，而非"先提取再检查"：

```
旧方案（复杂）：extractItem → 检查过滤 → insertItem退回 → 下次再试
新方案（干净）：getMergedStorage → 过滤查找 → extractItem(targetType)
```

**优势**：
- 不需要退回操作（无 `insertItem` 回退）
- 不需要 UUID 旋转（无 `rotateStorageHeadToTail`）
- 不需要槽位旋转（无 `insertItemAtEnd`）
- 过滤发生在"查找"阶段，天然正确

### 7.6 互相指向防护 (NEW 2026-07-22)

当两个活漏斗互相指向时（A→B 且 B→A），`inheritFilter()` 和 `ensureFilterBuilt()` 协同防止无限递归：

```
A→B 且 B→A 时：
  对 A 构建过滤规则：
    邻居 B 的 target 指向 A → 黑名单分支 → inheritFilter 遇到活漏斗 B
    → ensureFilterBuilt(B) → B 在 building 集合中 → 返回 FilterData.EMPTY
    → 无过滤规则
  对 B 构建过滤规则：
    邻居 A 的 target 指向 B → 黑名单分支 → inheritFilter 遇到活漏斗 A
    → ensureFilterBuilt(A) → A 在 building 集合中 → 返回 FilterData.EMPTY
    → 无过滤规则
  → 互相指向时双方均无过滤规则，名单物品拿走后自然消失
```

> **v7 变更**：互相指向时由 `ensureFilterBuilt()` 的 `building` 集合检测循环依赖并阻断，`inheritFilter()` 的 `visited` 集合防止同一链路重复访问。双重防护确保无无限递归。

### 7.7 角落活漏斗过滤修复 (NEW 2026-08-16)

**问题**：当活漏斗 a 位于容器角落位置、其 source 和 target 方向都越界时（如左上角的 `LEFT→UP`），`buildAllFilters()` 中的跳过逻辑 `if (sourceOf[slot] == -1 && targetOf[slot] == -1) continue` 会错误地跳过 a，导致 `filterOf[a]` 永远为 `EMPTY`。即使其他活漏斗 b 连接到 a（如 `sourceOf[b] == a`），b 的黑白名单也无法传递给 a。

**根因**：跳过条件把"非活漏斗"和"活漏斗但 source/target 都越界"混为一谈。非活漏斗确实不需要 filter，但角落活漏斗即使自身 source/target 都越界，仍然可能被其他漏斗连接，需要构建 filter。

**修复**：在 `sourceOf == -1 && targetOf == -1` 时，额外检查该槽位是否为活漏斗：

```java
if (sourceOf[slot] == -1 && targetOf[slot] == -1) {
    if (!LivingHopperFunction.isLivingHopper(ctx.getItem(slot))) continue;
}
```

**场景示例**：

```
┌───┬───┬───┐
│ a │ b │   │   a: LEFT→UP  → sourceOf[0]=-1, targetOf[0]=-1
├───┼───┼───┤   b: LEFT→RIGHT → sourceOf[1]=0(=a), targetOf[1]=2
│   │   │   │
└───┴───┴───┘
```

| | 修复前 | 修复后 |
|---|--------|--------|
| a 的 filter | EMPTY（跳过构建） | 正常构建，继承 b 的规则 |
| b 的黑白名单能否传到 a | ❌ | ✅ |

---

## 8. 方向配置系统

### 8.1 坐标系

```
容器内 GUI 坐标系（9列网格）：

   列: 0  1  2  3  4  5  6  7  8
行0: [ ][ ][ ][ ][ ][ ][ ][ ][ ]
行1: [ ][ ][ ][ ][H][ ][ ][ ][ ]  ← H=活漏斗(槽位13)
行2: [ ][ ][ ][ ][ ][ ][ ][ ][ ]

Pos2D 偏移：
  UP    = (0, -1) → 槽位 4
  DOWN  = (0,  1) → 槽位 22
  LEFT  = (-1, 0) → 槽位 12
  RIGHT = (1,  0) → 槽位 14
```

### 8.2 SlotMapping 数据结构

```java
public record SlotMapping(
    Pos2D sourceOffset,   // 源方向偏移
    Pos2D targetOffset,   // 目标方向偏移
    String displaySymbol, // 显示符号（如 "↑→↓"）
    String displayName    // 显示名称（如 "上传下"）
)
```

12 种预设方向通过 `SlotMapping.PRESETS` 列表暴露，NBT 序列化优先匹配预设（通过坐标比较），确保单例语义。

### 8.3 WASD 输入系统

```
WASDSequenceParser:
  'W' → UP    (0, -1)
  'A' → LEFT  (-1, 0)
  'S' → DOWN  (0,  1)
  'D' → RIGHT (1,  0)

输入序列 "WD" → source=UP, target=DOWN → "上传下"
输入序列 "AS" → source=LEFT, target=DOWN → "左传下"
```

---

## 9. 槽位解析与容器兼容性

### 9.1 容器宽度检测（多策略）

活漏斗需要知道容器列数（`containerWidth`）才能正确计算槽位偏移。`SimpleContainerContext.getWidth()` 按优先级依次尝试：

```
getWidth()
  ├─ 1. 标准容器 → 9
  ├─ 2. 适配器解析 → AdapterRegistry.findAdapter() → adapter.getLayout().columns()
  ├─ 3. 配置规则 → ContainerCompatibilityConfig.findRule()
  ├─ 4. 启发式猜测 → guessWidth(totalSize) → 9 或 13
  └─ 5. 默认回退 → 9
```

### 9.2 ContainerCompatibilityConfig — 配置规则

通过 JSON 配置文件注册其他模组容器的布局参数，无需修改代码。
文件为 `assets/living_item/container_rules.json`（内置，随 jar 打包）与
`config/living_item/container_rules.json`（玩家增量），格式一致：

```json
{
  "version": 1,
  "rules": [
    {"containerId": "ironchest:iron_chest", "containerSize": 54, "columns": 9, "description": "铁箱子-铁"},
    {"containerId": "ironchest:diamond_chest", "containerSize": 108, "columns": 12, "description": "铁箱子-钻石"}
  ]
}
```

游戏内可用 `/livingitem container register <columns>` 对着容器自动检测 ID 并注册；
`/livingitem container export` 把玩家增量导出为同格式 JSON，便于合并进内置资源随包发布。
分层语义与导出通道详见 [`living-item-infrastructure.md` §5.2.1](../system-design/living-item-infrastructure.md)。

### 9.3 AdapterRegistry — 适配器模式

通过 `ContainerAdapter` 接口为特殊容器（如漏斗 5 格、熔炉 3 格）提供自定义布局：

```java
public interface ContainerAdapter {
    boolean canHandle(Container container);
    ContainerLayout getLayout(Container container);
}
```

`HopperAdapter` 是内置适配器，将漏斗的 5 格线性布局映射为 1×5 网格。

适配器通过 Java ServiceLoader 机制自动发现，第三方模组可通过 `META-INF/services/` 注册自己的适配器。

### 9.4 SlotResolver 计算公式

```
result = (baseRow + direction.y) * containerWidth + (baseCol + direction.x)

其中:
  baseRow = baseSlot / containerWidth
  baseCol = baseSlot % containerWidth
  containerWidth = 9（标准容器）/ 由 getWidth() 动态计算
```

### 9.5 边界检查

| 边界 | 条件 | 返回值 |
|------|------|--------|
| 左边界 | `newCol < 0` | -1 |
| 右边界 | `newCol >= containerWidth` | -1 |
| 上边界 | `newRow < 0` | -1 |
| 下边界 | `result >= containerSize` | -1 |
| 无偏移 | `direction == NONE` | -1 |

### 9.6 跨容器触发

当 `SlotResolver.resolve()` 返回 -1 时，表示槽位超出当前容器范围，触发跨容器传输。此时 `TransferPipeline.execute()` 将控制权交给 `CrossContainerTransfer`。

---

## 10. 已知问题与修复记录

### 10.1 已修复：过滤器拦截导致无限循环

**问题**：从活箱子提取物品时，过滤器拦截后冷却未设置，导致每 tick 重复 extract→check→insert 循环。

**根因**：`tick()` 中冷却设置包裹在 `if (success)` 内，过滤器拦截返回 false 时不设冷却。

**修复**：将冷却设置移到 `if (success)` 外部，无论传输是否成功都设置冷却。

**相关提交**：2026-07-21

### 10.2 已修复：白名单物品永远轮不到

**问题**：活箱子中金锭（槽位0）和铁锭（槽位1）在同一 UUID 的虚拟箱子中，白名单为铁锭。提取永远拿到金锭，被拦截后退回，铁锭永远轮不到。

**根因**：`extractItem` 从前往后遍历槽位，始终先提取金锭。

**修复**：采用"预查 + 类型提取"策略 —— `getMergedStorage()` 遍历找到第一个通过过滤的物品，用 `extractItem(targetType)` 精确提取。

**相关提交**：2026-07-21

### 10.3 已修复：跨容器传输绕过过滤

**问题**：跨容器传输时，`CrossContainerTransfer` 的各传输方法未检查过滤器。

**修复**：将 `filterState` 传递给各子方法，在物品处理循环中添加过滤检查。

**相关提交**：2026-07-21

### 10.4 已修复：活箱子间传输不检查过滤

**问题**：`transferBetweenLivingChests` 直接提取物品后不检查过滤就插入目标。

**修复**：添加预查过滤逻辑，在提取前找到匹配的物品类型。

**相关提交**：2026-07-21

### 10.5 已修复：混搭黑白名单链传递失效

**问题**：一条链上既有黑名单活漏斗又有白名单活漏斗时，名单传递中断。

**根因**：`inheritFilter()` 只从邻居继承单一类型名单（如果邻居是黑名单就只继承黑名单，白名单同理），导致链上混搭时邻居的另一种名单被忽略。

**修复**：修改 `inheritFilter()` 方法签名，使其从邻居同时继承黑名单和白名单两个集合，确保链上混搭时名单传递不中断。

**相关提交**：2026-07-21

### 10.6 已重构：SlotAccessor 统一传输架构

**背景**：原有传输引擎按源/目标类型（普通槽位、活箱子）组合出 4 种传输方法，代码高度重复（~400 行），且新增活末影箱、活潜影箱等存储类型时需新增大量分支。

**重构方案**：引入 `SlotAccessor` 接口抽象存储后端，传输引擎统一为 `extract → insert → rollback` 流程。

**新增文件**：
- `SlotAccessor.java` — 接口定义（extract/insert/rollback/isEmpty/isFull/markTransferred/sync）
- `PlainSlotAccessor.java` — 普通槽位实现
- `LivingChestAccessor.java` — 活箱子虚拟存储实现
- `SlotAccessorFactory.java` — 工厂类，根据槽位物品类型创建对应访问器

**删除的旧方法**：
- `transferBetweenSlots()` (~70行)
- `transferToLivingChest()` (~80行)
- `transferFromLivingChest()` (~170行)
- `transferBetweenLivingChests()` (~80行)

**改造后**：`TransferPipeline` 从 ~750 行缩减到 ~350 行。新增存储类型只需实现 `SlotAccessor` 接口 + 在工厂类添加一行判断，传输引擎零改动。

**相关提交**：2026-07-22

### 10.7 已修复：跨容器 pullFromNeighbor 级联穿透

**问题**：两个活漏斗跨容器组成循环（push→pull→push），`pullFromNeighbor` 成功拉取后未标记 `transferredTargetSlots`，导致同一 tick 内物品被另一个漏斗取走，形成空循环。

**根因**：`pullFromNeighbor` 的两条成功路径（空槽位/堆叠）只做了 `containerCtx.setItem()`，未调用 `markSlotTransferred`。

**修复**：提取 `markSlotTransferred(containerCtx, targetSlot)` 方法，在两处成功路径中调用。同时精简 `findDoubleChestPositions`、`getBlockFacing`、`getBasePosForDirection` 等冗余代码。

**相关提交**：2026-07-22

### 10.8 已修复：黑白名单循环反馈永久残留

**问题**：两个活漏斗互相指向（A→B 且 B→A）时，`inheritFilter()` 形成循环反馈，导致黑白名单物品即使被拿走也永久残留。

**根因**：A 从 B 继承名单 → B 的名单含从 A 继承的 → A 再继承回来 → 无限循环。

**修复**：在 `inheritFilter()` 调用前检查 `sourceOf[hostSlot] != slot && targetOf[hostSlot] != slot`，互相指向时跳过继承。

**相关提交**：2026-07-22

### 10.9 已修复：跨容器活末影箱无法注册路由

**问题**：活漏斗 source=跨容器(越界)，target=活末影箱(容器内) 时，无法向频道添加路由。

**根因**：`TransferPipeline.execute()` 中越界检查在最前面，source 越界时直接跳转到 `CrossContainerTransfer`，跳过了 `target instanceof LivingEnderChestAccessor` 的 `EnderRouteManager.registerRoute()` 调用。而 `pullFromNeighbor` 只检查了 `targetIsChest`，未检查 `targetIsEnderChest`。

**修复**：
1. `CrossContainerTransfer` 新增 `hostSlot` 参数
2. `pullFromNeighbor` 新增 `targetIsEnderChest` 检查
3. 新增 `pullFromNeighborToLivingEnderChest()` 方法，遍历相邻容器物品，构建 `EnderChannelEntry` 并注册到全局路由表

**相关提交**：2026-07-22

### 10.10 已重构：ItemFilterComponent 统一优先级判定模型 (NEW 2026-07-25)

**背景**：原 `allows()` 方法根据过滤模式（ID/NBT/Tag）分派到三个独立方法（`allowsById`、`allowsByComposite`、`allowsByTag`），每个方法独立处理黑白名单冲突。当同一物品同时出现在白名单和黑名单且来自不同级别时（如NBT级白名单 vs ID级黑名单），缺乏统一的冲突解决机制。

**重构方案**：引入基于涵盖范围的优先级模型（田忌赛马），统一所有模式下的过滤判定：

- **优先级**：NBT(3) > ID(2) > Tag(1)，涵盖范围越小优先级越高
- **冲突规则**：高优先级胜，同优先级黑名单胜
- **遮蔽机制**：同一列表中NBT级条目遮蔽ID级条目

**删除的旧方法**：
- `allowsById()` — 仅ID级过滤
- `allowsByComposite()` — 仅NBT级过滤
- `allowsByTag()` — 仅Tag级过滤
- `allowsItemTypeById()` — 仅ID级类型过滤

**新增方法**：
- `allowsByPriority()` — 统一优先级判定入口
- `calcMatchPriority()` — 计算单侧匹配的最高优先级
- `hasNbtEntriesForId()` — NBT级遮蔽检测
- `matchesAnyTag()` — Tag级匹配检查
- `calcTagPriorityForItemId()` — 仅ID场景的Tag优先级计算

**同时清理**：移除 `FilterData.blCompIds`/`wlCompIds` 死代码，更新 `hasFilterRules()` 检查所有级别数据。移除 `mode` NBT 键 — 模式由扫描时读取邻居活漏斗的堆叠数动态计算，不再持久化存储。

**相关提交**：2026-07-25

### 10.11 已修复：Tag级继承后显示为NBT级 (NEW 2026-07-25)

**问题**：堆叠 3 的活漏斗 A 传递 Tag 白名单给堆叠 2 的活漏斗 B，B 的 tooltip 显示为 `[NBT]` 而非 `[Tag]`。

**根因**：`inheritFilter()` 中将 Tag 数据存入了 NBT 级别的 `composites` 集合，而非 `tags` 集合。

**修复**：确保 `inheritFilter()` 中 `KEY_WL_TAGS` 数据存入 `wlTags`，`KEY_BL_TAGS` 数据存入 `blTags`，NBT 级数据存入 `wlComposites`/`blComposites`，各级别数据集合完全隔离。

**相关提交**：2026-07-25

### 10.12 已修复：NBT级存在时ID级重复显示 (NEW 2026-07-25)

**问题**：白名单中 `diamond_sword@12345`（NBT级）和 `diamond_sword`（ID级）同时显示在 tooltip 中，造成重复。

**根因**：tooltip 渲染未应用遮蔽机制 — NBT级条目应遮蔽同 ID 的 ID级条目。

**修复**：在 `appendFilterEntries()` 中，遍历 ID 级条目时检查 `hasNbtEntriesForId()`，若 NBT 级已存在该 ID 的条目则跳过 ID 级显示。

**相关提交**：2026-07-25

### 10.13 已修复：Tag级槽位映射不显示 (NEW 2026-07-25)

**问题**：Tag 级条目在 tooltip 中不显示来源槽位，格式为 `标签名 [Tag]` 而非 `标签名 (槽N) [Tag]`。

**根因**：缺少 Tag 级槽位映射存储，`FilterData` 中只有 `blTagSlots`/`wlTagSlots` 字段但收集时未写入。

**修复**：新增 `KEY_BL_TAG_SLOTS`/`KEY_WL_TAG_SLOTS` NBT 键，在扫描 Tag 级数据时同步记录槽位映射，tooltip 渲染时读取显示。

**相关提交**：2026-07-25

### 10.14 已修复：inheritFilter 无条件继承 (NEW 2026-07-25)

**问题**：`inheritFilter()` 带有 `mode` 参数，继承时只继承当前模式对应的级别数据，导致链式传递中高精度数据（如 NBT）丢失。

**根因**：活漏斗自身的模式标签是给邻居扫描用的，不是控制继承范围的。继承应无条件传递所有级别数据。

**修复**：移除 `inheritFilter()` 的 `mode` 参数，无条件继承邻居所有级别（ID + NBT + Tag）的数据，确保链式传递不丢失精度。

**相关提交**：2026-07-25

### 10.15 新增：活漏斗盔甲槽绕过限制 (NEW 2026-07-25)

**背景**：活漏斗向玩家盔甲槽位（36-39）传输非盔甲物品时，`IItemHandler.isItemValid()` 会拒绝，物品直接消失。

**方案**：在 `SimpleContainerContext` 中检测盔甲槽位，全线绕过限制：

- `getSlotLimit()`：返回 `getMaxStackSize()`（64），不限制 1
- `isItemValid()`：返回 `true`，任何物品"合法"
- `simulateInsertItem()`：按普通槽位计算，不做类型限制
- `setItem()`：`handler.insertItem()` 失败后直接 `inventory.armor.set()` 写入

**效果**：活漏斗能把方块放入玩家头盔槽位，玩家头显示方块模样，增加趣味玩法。玩家手动操作不受影响。

**相关提交**：2026-07-25

### 10.16 已重构：过滤链构建移至 ContainerSnapshot (NEW 2026-07-27)

**背景**：原 `ItemFilterComponent.tick()` 负责扫描邻居活漏斗构建黑白名单，但组件架构下组件间状态传递复杂，且过滤规则需要与传输逻辑紧密配合。

**重构方案**：将过滤链构建逻辑移至 `HopperFilterBuilder.buildForSlot()`，在 `capture()` 阶段统一预计算：

**新增方法**（`HopperFilterBuilder`）：
- `buildAll(ctx, containerSize, sourceOf, targetOf)` — 容器级预计算入口，遍历所有活漏斗槽位
- `buildForSlot(mySlot, ctx, containerSize, sourceOf, targetOf)` — 为单个活漏斗构建完整 FilterData（包含 ID/NBT/Tag 三个级别的黑名单和白名单，以及对应的槽位映射）
- `addToFilter(mode, stack, idList, compositeList, tagList, idSlots, tagSlots, refSlot)` — 根据过滤模式将物品添加到对应数据集，同时记录来源槽位

**tick 流程变化**：
```java
// 旧流程：ItemFilterComponent.tick() 独立扫描，每漏斗重复 isLivingHopper + getHopperData + SlotResolver.resolve
// 新流程：ContainerSnapshot.capture() 统一预计算，tick() 直接读取
FilterData filter = tick.snapshot.getFilterOf(slot);
data = data.withTransfer(transfer).withFilter(filter);
LivingItemManager.setHopperData(stack, data);
```

**优势**：
- 过滤规则由容器级缓存统一预计算，N 个活漏斗只需 1 次遍历（O(N) 而非 O(N²)）
- 复用 `ContainerSnapshot` 预计算结果，省去 `isLivingHopper`/`getHopperData`/`SlotResolver.resolve` 三组重复操作
- 每次 tick 实时扫描，确保过滤规则始终反映最新邻居布局
- 计算结果写回 DataComponent，利用 Minecraft 内置同步机制确保 tooltip 可靠显示
- 新增 `PerfMetrics.recordTransfer()` 性能记录，便于监控传输成功率

**相关提交**：2026-07-27

### 10.17 新增：cleanupStaleRoutes 路由清理机制 (NEW 2026-07-27)

**背景**：活末影箱路由模式下，路由条目注册到全局路由表 `EnderChannelRegistry`。当容器被异常清除（如区块卸载、方块破坏）时，需要清理失效路由，避免路由表中积累无效条目。

**实现**：在 `LivingHopperFunction.tick()` 末尾调用 `cleanupStaleRoutes()`：

```java
private void cleanupStaleRoutes(List<SlotEntry> entries, ContainerContext context, Level level) {
    // 收集当前容器中所有活末影箱的槽位
    Set<Integer> activeEnderChestSlots = new HashSet<>();
    for (int i = 0; i < context.getSize(); i++) {
        if (LivingEnderChestFunction.isLivingEnderChest(context.getItem(i))) {
            activeEnderChestSlots.add(i);
        }
    }

    // 清理失效路由
    EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
    registry.removeStaleEnderChestRoutes(context.getContainerKey(), activeEnderChestSlots);
    registry.cleanStaleSourceRoutes(context);
}
```

**清理策略**：
- `removeStaleEnderChestRoutes(containerKey, activeSlots)` — 清理活末影箱被移走的路由（按容器隔离）
- `cleanStaleSourceRoutes(context)` — 清理源物品已消失或变化的路由
- `removeStaleRoutesByRegistrarKey(containerKey, activeSlots)` — 清理注册者容器区块卸载时的跨容器路由

**相关提交**：2026-07-27

### 10.18 已修复：buildFilterChain 黑白名单语义颠倒 (NEW 2026-07-27)

**问题**：`buildFilterChain()` 中黑白名单的赋值逻辑与实际语义完全相反，导致过滤链完全不工作。

**根因分析**：

过滤规则影响的是当前活漏斗**拉取**物品的行为（源槽位物品的过滤），而非接收行为。原有代码混淆了这两个方向：

| 场景 | 旧代码（错误） | 正确语义 | 修复后 |
|------|--------------|---------|--------|
| 邻居 target→我（邻居向我推送） | `addToFilter(..., whitelist)` | 邻居推 X 给我，我应**黑名单 X**，避免从源重复拉取相同物品 | `addToFilter(..., blacklist)` |
| 邻居 source→我（邻居从我取物） | `addToFilter(..., blacklist)` | 邻居要 Y 从我，我应**白名单 Y**，确保只拉取邻居需要的物品 | `addToFilter(..., whitelist)` |

**具体场景验证**：

- **场景 A**：邻居 B 的 source 指向我，B 的 target 槽位放钻石。B 想从我取钻石。
  - 旧代码：钻石 → 我的黑名单 → 我不拉钻石 → B 永远拿不到钻石 ❌
  - 修复后：钻石 → 我的白名单 → 我只拉钻石 → B 能拿到钻石 ✅

- **场景 B**：邻居 B 的 target 指向我，B 的 source 槽位放钻石。B 想向我推钻石。
  - 旧代码：钻石 → 我的白名单 → 我只拉钻石 → B 推钻石 + 我拉钻石 = 可能重复 ❌
  - 修复后：钻石 → 我的黑名单 → 我不拉钻石 → B 推钻石给我，无重复 ✅

**相关提交**：2026-07-27

### 10.19 已修复：ContainerSnapshot 跳过 DEFAULT 活漏斗导致过滤链为空 (NEW 2026-07-27)

**问题**：`buildFilterChain` 使用 `ContainerSnapshot` 后，过滤链始终为 `FilterData.EMPTY`，黑白名单不生效且 tooltip 不显示。

**根因**：`ContainerSnapshot.capture()` 中有一行跳过了所有未配置方向（`LivingHopperData.DEFAULT`）的活漏斗：

```java
// 旧：跳过 DEFAULT 数据的活漏斗
if (data == null || data == LivingHopperData.DEFAULT) continue;
```

默认方向 `(UP, DOWN)` 的活漏斗同样是有效邻居，它们的 `sourceOf`/`targetOf` 应该被预计算。跳过后这些槽位的 `sourceOf[i]` 和 `targetOf[i]` 保持 -1，`buildFilterChain` 中判断 `neighborSourceSlot == -1 && neighborTargetSlot == -1` 直接跳过，导致所有邻居被忽略。

**修复**：删除 `|| data == LivingHopperData.DEFAULT` 条件：

```java
// 新：只跳过 null
if (data == null) continue;
```

**影响链**：

```
ContainerSnapshot 跳过 DEFAULT 活漏斗
  → sourceOf[i] = -1, targetOf[i] = -1  (未捕获)
    → buildFilterChain 中 neighborSourceSlot == -1 && neighborTargetSlot == -1 → continue
      → 所有邻居被跳过 → chainFilter = EMPTY
        → 过滤不生效 + tooltip 不显示
```

**相关提交**：2026-07-27

### 10.20 已修复：data 局部变量未更新导致 FilterData 被覆盖回 EMPTY (NEW 2026-07-27)

**问题**：过滤功能在内存中生效（每 tick 重新构建 chainFilter），但 tooltip 不显示、NBT 中没有 FilterData 数据。

**根因**：`tick()` 方法中，写入 FilterData 后没有更新 `data` 局部变量，后续 `data.withTransfer(transfer)` 仍基于旧的 `data`（filter 为 EMPTY），覆盖了刚写入的 FilterData：

```
L65: data = getHopperData(stack)                    → data.filter = EMPTY
L88: setHopperData(stack, data.withFilter(chain))   → stack.filter = chainFilter ✅
L103: setHopperData(stack, data.withTransfer(xfer)) → data.filter 仍是 EMPTY → 覆盖回 EMPTY ❌
```

**修复**：在写入 FilterData 后更新 `data` 局部变量：

```java
// 旧：data 不更新，后续 withTransfer 用旧 data 覆盖
if (!chainFilter.equals(storedFilter)) {
    LivingItemManager.setHopperData(stack, data.withFilter(chainFilter));
}

// 新：data 更新，后续 withTransfer 保留 filter
if (!chainFilter.equals(storedFilter)) {
    data = data.withFilter(chainFilter);
    LivingItemManager.setHopperData(stack, data);
}
```

**教训**：`record` 类型通过 `withX()` 生成新实例而非修改原实例。在连续多次 `withX()` 调用时，必须始终基于最新的实例链式调用，否则中间的修改会被后续调用覆盖。

**相关提交**：2026-07-27

### 10.21 已优化：容器级冗余扫描消除 (NEW 2026-07-27)

**背景**：`processContext()` 在 tick 开始时扫描全容器，将活物品按功能分组到 `entries` 列表。但部分功能函数在收到 `entries` 后又重新扫描全容器做相同的事。

**优化**：

| 功能函数 | 旧代码（冗余扫描） | 新代码（复用 entries） |
|---------|-------------------|---------------------|
| `LivingEnderChestFunction.tick()` | 遍历全容器找活末影箱槽位 | 直接从 `entries` 参数读取槽位 |
| `LivingWaterBucketFunction.postTickSync()` | 遍历全容器找水桶槽位 | 接收 `waterBucketEntries` 参数，直接遍历 |
| `LivingHopperFunction.buildFilterChain()` | 遍历全容器 + `isLivingHopper` + `getHopperData` + `SlotResolver.resolve` | 读取 `ContainerSnapshot` 预计算数组 |

**具体改动**：

1. `LivingEnderChestFunction.tick()`：
```java
// 旧
for (int i = 0; i < containerSize; i++) {
    if (isLivingEnderChest(context.getItem(i))) activeEnderChestSlots.add(i);
}
// 新
for (SlotEntry entry : entries) { activeEnderChestSlots.add(entry.slotIndex()); }
```

2. `LivingWaterBucketFunction.postTickSync()`：
```java
// 旧
public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData) {
    for (int i = 0; i < ctx.getSize(); i++) { ... }
}
// 新
public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData,
    List<SlotEntry> waterBucketEntries) {
    if (waterBucketEntries.isEmpty()) return;
    for (SlotEntry entry : waterBucketEntries) { ... }
}
```

3. `ContainerLivingItemHandler.processContext()`：从 `grouped` 中查找水桶功能并传递给 `postTickSync`。

**原则**：`processContext()` 的一次扫描结果是容器级共享资源，所有功能函数应复用而非重复扫描。

**相关提交**：2026-07-27

**10.22 FilterData 容器级预计算 + DataComponent 写回（架构优化）**

**问题**：`FilterData` 作为衍生数据存储在物品 NBT 中，存在数据一致性风险（过期、覆盖），且每个活漏斗 tick 都需重复构建过滤链。

**方案演进**：

1. **v6 方案**（已废弃）：将 `FilterData` 从 NBT 完全移除，由 `ContainerSnapshot` 预计算后通过自定义 `FilterSyncPacket` 同步到客户端 `FilterDisplayCache`。
   - 问题：tooltip 在物品不在容器中时无法显示（光标上、地上、聊天框），自定义同步机制不如 Minecraft 内置 DataComponent 同步可靠，为 tooltip 花费了过多复杂度。

2. **v7 方案**（当前）：**计算归容器，展示归物品**。`FilterData` 仍由 `ContainerSnapshot` 容器级预计算（保留性能优势），但计算完后写回 `LivingHopperData.filter`（DataComponent），利用 Minecraft 内置同步机制推送到客户端。

**当前实现**：
- `ContainerSnapshot.capture()` 中新增 `buildAllFilters()`，一次性预计算所有活漏斗的过滤规则
- `buildFilterForSlot()` 为每个活漏斗构建过滤规则，遇到活漏斗时通过 `inheritFilter()` 继承其完整 FilterData
- `inheritFilter()` 遇到活漏斗时通过 `ensureFilterBuilt()` 获取其 FilterData 并合并，遇到非活物品时通过 `addToFilter()` 加入过滤规则
- `LivingHopperFunction.tick()` 从 `tick.snapshot.getFilterOf(slot)` 读取过滤规则，写回 `data.withFilter(filter).withTransfer(transfer)`
- `LivingHopperData` 保留 `FilterData filter` 字段，tooltip 直接从 `data.filter()` 读取
- 删除 `FilterSyncPacket`、`FilterDisplayCache`、`syncFilterData()`、`LAST_FILTER_CACHE` 等自定义同步机制

**核心认知**："配置数据归物品，衍生数据归容器"说的是**计算归属**，不是**存储归属**。FilterData 的**计算**归容器（ContainerSnapshot 预计算），但**展示**仍然可以借助物品的 DataComponent 通道同步到客户端。不重复造轮子。

### 10.23 已优化：容器内传输逻辑重构 + Container 接口兼容 (NEW 2026-08-17)

**背景**：活漏斗容器内传输逻辑（`executeInContainer`）存在以下问题：
1. 跨模组容器（如 reserve_storage）的 `IItemHandler` 可能暴露不可交互的槽位（幽灵槽），导致物品复制
2. 玩家背包 `getBlockPos()` 返回 null，`getNeighborContainer` 未做空检查导致 NPE
3. `Math.min(stackSize, maxTransfer)` 在代码中重复计算 3 次
4. 末影箱路由决策分散在 `registerRoute` + `doTransfer` 两处调用

**重构内容**：

| 改动 | 文件 | 说明 |
|------|------|------|
| Container 接口过滤 | `TransferPipeline.java` | 新增 `Container.canTakeItem()` 和 `Container.canPlaceItem()` 检查（步骤 [6] 和 [10]），模拟玩家操作逻辑，过滤不可交互槽位 |
| `getNeighborContainer` 空安全 | `CrossContainerTransfer.java` | 添加 `neighborPos == null` 检查，玩家背包等场景安全返回 null |
| `transferAmount` 变量提取 | `TransferPipeline.java` | `Math.min(stackSize, maxTransfer)` 从 3 次减少到 1 次 |
| 末影箱决策统一 | `TransferPipeline.java` | `EnderRouteManager.resolveTarget()` 返回 `Decision` 枚举，通过 `switch` 分流 |
| 辅助方法提取 | `TransferPipeline.java` | `isTransferableSource()` / `isStorageContainer()` 独立方法 |
| 跨容器 helper 合并 | `CrossContainerTransfer.java` | `pushFromLivingChestToNeighbor` + `pushFromLivingEnderChestToNeighbor` → `pushFromLivingStorageToNeighbor` |
| 邻居遍历提取 | `CrossContainerTransfer.java` | `tryPullFromNeighbor` / `tryPushToNeighbor` 两个核心 helper，所有传输方法复用 |

**Container 接口过滤原理**：

```java
// 步骤 [6]：取出前模拟玩家操作
Container hostContainer = CrossContainerTransfer.getNeighborContainer(level, ctx.getBlockPos());
if (hostContainer != null && !hostContainer.canTakeItem(hostContainer, sourceSlot, sourceStack)) {
    return false;  // 不可取出的槽位（如幽灵槽、输出槽）被过滤
}

// 步骤 [10]：放入前模拟玩家操作
if (hostContainer != null) {
    ItemStack simulated = source.simulateExtract(transferAmount);
    if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
        return false;  // 不可放入的槽位被过滤
    }
}
```

**相关提交**：2026-08-17

### 10.24 已修复：IItemHandler.insertItem 槽位参数不可靠导致容器内传输物品错位 (NEW 2026-08-17)

**问题**：容器内传输时，物品没有被传输到指定目标槽位，而是按槽位顺序（0→1→2→...）被放置。例如活漏斗配置源槽位=0、目标槽位=5，但物品最终出现在槽位 0 而非槽位 5。

**根因**：Forge `IItemHandler.insertItem(int slot, ItemStack stack, boolean simulate)` 的 `slot` 参数在 API 契约上只是"建议"。许多模组容器的 `IItemHandler` 实现会：

- 完全忽略 `slot` 参数，按"第一个可用槽位"插入
- 有自己的内部路由逻辑（如机器类容器自动将物品路由到输入槽）
- 静默将物品路由到正确槽位，不报错

**容器内传输链路**中，`PlainSlotAccessor.insert()` → `SimpleContainerContext.setItem()` → `handler.insertItem(logicalSlot, toInsert, false)`，`logicalSlot` 被忽略，物品进入了错误的槽位。

而跨容器传输不受影响，因为 `tryPushToNeighbor` 遍历所有槽位，逐个尝试插入，最终物品出现在第一个可用槽位是预期行为。

**修复**：在 `SimpleContainerContext.setItem()` 和 `simulateInsertItem()` 中，当 `Container` 接口可用时，优先使用原版 `Container.setItem(slot, stack)` 做精确槽位写入。`Container` 是 Minecraft 原版接口，`setItem` 是 100% 精确槽位写入，不存在歧义。

```java
// SimpleContainerContext.setItem() — 修复后
Container container = ContainerContext.getContainer(getLevel(), getBlockPos());
if (container != null && logicalSlot < container.getContainerSize()) {
    container.setItem(logicalSlot, toInsert);  // 精确槽位写入
    notifyBlockEntitiesChanged();
    return;  // Container 可用 → 直接返回，不走 IItemHandler
}
// Container 不可用 → 回退到 IItemHandler（兜底）
handler.extractItem(logicalSlot, Integer.MAX_VALUE, false);
ItemStack remaining = handler.insertItem(logicalSlot, toInsert, false);
```

```java
// SimpleContainerContext.simulateInsertItem() — 修复后
Container container = ContainerContext.getContainer(getLevel(), getBlockPos());
if (container != null && slot < container.getContainerSize()) {
    if (!container.canPlaceItem(slot, stack)) return 0;
    ItemStack existing = container.getItem(slot);
    // 手动计算可插入数量（空槽位 / 同物品堆叠）
    ...
    return result;
}
// Container 不可用 → 回退到 IItemHandler 模拟
ItemStack remaining = handler.insertItem(slot, stack.copy(), true);
return stack.getCount() - remaining.getCount();
```

**修复效果**：

```
修复前：
  PlainSlotAccessor.insert(slot=5)
    → handler.insertItem(5, stack, false)
    → slot=5 被忽略，物品进了 slot=0 ❌

修复后：
  PlainSlotAccessor.insert(slot=5)
    → container.setItem(5, stack)
    → 精确写入 slot=5 ✅
```

**降级策略**：当 `Container` 不可用时（极少数仅暴露 `IItemHandler` 能力的模组容器），回退到原有 `IItemHandler` 路径作为兜底。

**相关提交**：2026-08-17

### 10.25 已修复：大箱子（多方块容器）两套槽位体系错位 → 活漏斗静默不传输 (NEW 2026-09-22)

**问题**：大箱子里上下摆两个「上传下」活漏斗，上方那个**完全不传输**；把下方漏斗拿走就恢复；
在上方漏斗的输出槽放一个物品也能恢复；下方漏斗换成**任何朝向**都照样"锁住"上方漏斗。
同样布局在其它模组的**单方块** 54 格箱子里完全正常。

**根因**：`ContainerContext.getContainer(level, pos)` 返回的是 **pos 那一个方块实体**的容器 ——
大箱子只拿到**单个半箱（27 槽）**，而 `IItemHandler` 能力给的是**合并后的 54 槽**。
两套槽位编号体系**错位 27 格**（Container 的槽 22 = GUI 的槽 49）。

`SimpleContainerContext.simulateInsertItem()` 走 Container 分支，用 `container.getItem(slot)`
读"目标槽现在有什么" ⇒ 实际读到**另一半箱的局部槽 22 = GUI 49**（那里正好是下方漏斗）
⇒ 非空且非同类物品 ⇒ `return 0` ⇒ `SlotAccessor.transfer` 里 `canAccept <= 0`
⇒ 传输失败、且源槽位非空所以**不设冷却**，表现为静默卡死。

**为什么那三个现象都能自洽**：

| 操作 | 模拟插入读到什么 | 结果 |
|------|----------------|------|
| 下方漏斗在 GUI#49 | 槽 49 有活漏斗（非空、非同类）→ `return 0` | 上方漏斗不传 |
| 拿走下方漏斗 | 槽 49 空 → 返回可插数量 | 正常 |
| 输出槽放一个物品 | 走"合并"分支，**绕过** `simulateInsertItem` | 正常 |
| 下方漏斗换朝向 | 它仍在 GUI#49，照样被读到 | 依然"锁住" |

> **决定性线索**："不管什么朝向都锁住" ⇒ 触发条件**不是方向/连接关系**，
> 而是"另一半箱的对应格子里有东西"。`PlainSlotAccessor` 只在**目标槽为空**时才调
> `simulateInsertItem`，目标槽已有同类物品时走合并分支 —— 这正是"放一个物品就好了"的原因，
> 也解释了它为什么藏这么久（只在"目标槽空 + 另一半对应格非空"时才现形）。

**修复**：新增**槽位体系一致性探针** `SimpleContainerContext.isSameSlotSpaceAsHandler(container, slot)`，
两级判据，不过则回退 handler：

1. **槽位数一致** —— 挡住"单体 vs 合并"（大箱子 27 vs 54；三方块 24 vs 72 同理）；
2. **单槽交叉校验** —— 挡住"槽位数相同但合并顺序相反"：比对同一槽位在两套体系里的
   「空/非空 + 物品」是否一致（漏网时两体系读到同一物品，判定结果相同，无害）。

**为什么不在 `getContainer()` 里直接返回合并容器**：合并顺序由**各 mod 的 IItemHandler** 决定，
未必等于 vanilla `ChestBlock.getContainer` 的顺序 —— 猜错就是引入一道**新的**错位，更隐蔽。
探针是自适应判据，**不假设容器结构**（几方块、怎么排），对三方块 / 四块 / 任意多方块容器同样成立。

**为什么不丢语义**：`handler.insertItem(slot, …, true)` 内部本就转调容器的
`canPlaceItem` / `isItemValid`（§10.23「模拟玩家操作、过滤幽灵槽」依然生效），
而且模拟与真实写入变成**同源** —— 本次 bug 的温床正是"模拟用 Container、真实用 handler"。

**遗留**：`TransferPipeline` 的 `hostContainer.canTakeItem/canPlaceItem` 仍按单方块体系索引
（对原版箱子无害，因默认 `true`），如需彻底统一可复用同一探针。

**相关提交**：2026-09-22

---

## 11. 调试指南

### 11.1 关键日志点

`TransferPipeline` 中可通过 `LOGGER` 添加日志：

```java
// 传输执行
LOGGER.info("Transfer: sourceSlot={}, targetSlot={}, stackSize={}", sourceSlot, targetSlot, stackSize);

// 过滤器检查
LOGGER.info("Filter check: item={}, allows={}", itemId, allows);

// 冷却状态
LOGGER.info("Cooldown: {} ticks remaining", cooldown);
```

### 11.2 常见问题排查

| 症状 | 可能原因 | 检查点 |
|------|---------|--------|
| 活漏斗完全不传输 | 方向配置错误 | Tooltip 显示的方向是否正确 |
| 传输速度异常慢 | 冷却时间过长 | `base_cooldown` 配置 |
| 物品被错误过滤 | 黑白名单解析错误 | 邻居活漏斗的 source/target 是否指向正确 |
| 跨容器不工作 | 方向转换错误 | 方块朝向与 GUI 方向的对应关系 |
| 级联传输（物品瞬间穿过链） | 级联防护失效 | `transferredTargetSlots` 是否正确维护 |
| 黑白名单永久残留 | 互相指向循环反馈 | 活漏斗是否成对互相指向 |
| 活末影箱路由不注册 | 跨容器越界分支跳过 | target 是否在越界分支前被检查 |
| 黑白名单不生效但功能正常 | data 局部变量未更新 | `data.withFilter()` 后是否更新了 `data` 变量 |
| 过滤链始终为空 | ContainerSnapshot 跳过 DEFAULT | `capture()` 是否跳过了未配置方向的活漏斗 |
| 玩家背包中活漏斗崩溃 | `getNeighborContainer` NPE | `getBlockPos()` 返回 null 时是否安全处理 |
| 跨模组容器物品复制 | 未过滤不可交互槽位 | `Container.canTakeItem/canPlaceItem` 检查是否生效 |
| 容器内传输物品错位 | `IItemHandler.insertItem` 忽略 slot 参数 | `SimpleContainerContext.setItem` 是否优先使用了 `Container.setItem` |
| 大箱子里不传输、单体箱子正常（→ 10.25） | 两套槽位体系错位（单半箱 27 槽 vs handler 54 槽） | `simulateInsertItem` 是否走了与 handler 不同体系的 Container 分支；另一半箱对应格是否有物品 |

### 11.3 方向调试

在 Tooltip 中可以看到当前活漏斗的传输方向：
```
源: ↑ 目标: ↓
模式: 上传下
```

如果显示的方向与预期不符，检查：
1. 活漏斗在容器中的槽位位置
2. WASD 输入是否正确（第1键=源，第2键=目标）
3. `SlotResolver` 的边界计算是否正确

---

> **文档维护者**: Living Item Mod Team  
> **下次更新建议**: 多功能模式（PUSH/PULL/COLLECT/DISTRIBUTE）实现后同步更新第 8 章  
> **v7 变更**: FilterData 由 ContainerSnapshot 容器级预计算但写回 DataComponent、删除 FilterSyncPacket/FilterDisplayCache 自定义同步机制、核心原则修正为"计算归容器，展示归物品"、链式传递采用双层继承+穿透混合模式（buildFilterForSlot 先继承邻居活漏斗完整 FilterData，inheritFilter 再沿 source/target 穿透递归）

---

## 12. 验证清单

> 重构或架构迁移后，必须逐项验证以下用例。标注 `(→ 10.X)` 的条目来源于历史 bug，不可省略。

### 12.1 基础传输

- [ ] 活漏斗能从 source 槽位提取物品到 target 槽位
- [ ] 冷却机制正常：传输后进入冷却，冷却期间不传输
- [ ] 堆叠数影响冷却时间：堆叠越多冷却越短
- [ ] source 或 target 为空槽位时不传输
- [ ] source 是活物品（非存储类）时不传输
- [ ] source 和 target 是同一槽位时不传输

### 12.2 方向配置

- [ ] 默认方向为 上→下（source=↑, target=↓）
- [ ] WASD 输入能正确修改方向
- [ ] 方向越界（如第一行向上）时触发跨容器传输
- [ ] Tooltip 显示当前方向和模式名称

### 12.3 链式传递

- [ ] 2段链：`[物品A]→[漏斗1]→[漏斗2]→[物品B]`，漏斗2黑名单含A，漏斗1白名单含B
- [ ] 3段链：`[物品A]→[漏斗1]→[漏斗2]→[漏斗3]→[物品B]`，漏斗3黑名单含A，漏斗1白名单含B
- [ ] 混搭链：链上黑名单漏斗和白名单漏斗交替，名单不中断（→ 10.5）
- [ ] 互相指向：A→B 且 B→A 时，双方均无过滤规则，不循环反馈（→ 10.8）
- [ ] 链中间漏斗同时获得上游黑名单和下游白名单

### 12.4 过滤级别

- [ ] ID级（堆叠1）：按物品ID过滤，Tooltip 显示 `[ID]`
- [ ] NBT级（堆叠2）：按物品+组件哈希过滤，Tooltip 显示 `[NBT]`
- [ ] Tag级（堆叠3）：按物品标签过滤，Tooltip 显示 `[Tag]`
- [ ] Tag级继承后显示为 `[Tag]` 而非 `[NBT]`（→ 10.11）
- [ ] NBT级存在时，同ID的ID级条目不重复显示（→ 10.12）
- [ ] Tag级条目显示来源槽位（→ 10.13）

### 12.5 优先级冲突

- [ ] 白名单NBT级 vs 黑名单ID级 → 白名单胜（高优先级胜）
- [ ] 同优先级黑白冲突 → 黑名单胜
- [ ] 无任何规则时 → 允许所有物品通过
- [ ] 仅有白名单时 → 不在白名单中的物品被拒绝
- [ ] 仅有黑名单时 → 在黑名单中的物品被拒绝

### 12.6 跨容器传输

- [ ] source越界时跨容器拉取
- [ ] target越界时跨容器推送
- [ ] target是活末影箱时注册路由而非直接传输
- [ ] source是活末影箱时从路由表提取
- [ ] 跨容器pullFromNeighbor级联防护：同一tick内物品不被重复取走（→ 10.7）
- [ ] 跨容器传输绕过过滤：跨容器路径上过滤规则仍然生效（→ 10.3）

### 12.7 活箱子/活末影箱交互

- [ ] source是活箱子时从虚拟存储提取
- [ ] target是活箱子时插入虚拟存储
- [ ] 活箱子间传输检查过滤规则（→ 10.4）
- [ ] 活箱子中白名单过滤时精确提取目标类型（→ 10.2）
- [ ] 同频道活末影箱间不互相传输（防自循环）

### 12.8 Tooltip 与同步

- [ ] 过滤规则在 Tooltip 中正确显示（黑白名单、级别标注、来源槽位）
- [ ] 过滤规则在物品离开容器后仍可显示（光标上、地上、聊天框）
- [ ] 冷却状态在 Tooltip 中显示
- [ ] 方向和模式名称在 Tooltip 中显示

### 12.9 路由清理

- [ ] 活漏斗被移走后，相关路由被清理
- [ ] 活末影箱被移走后，相关路由被清理
- [ ] 区块卸载时，相关路由被清理
- [ ] 注册者容器区块卸载时，跨容器路由被清理（→ 12.2 末影箱文档）

---

## 13. 红石信号锁定（v10 → v19.1 语义修正）

**行为**：活漏斗任意一条边收到红石信号 > 0 时被**禁用**（跳过传输和冷却倒计时），tooltip 显示红色警告「⚠ 被红石信号禁用」；信号消失后自动恢复传输。

**读取语义（v19.1 修正）**：`LivingHopperFunction.tick` 通过 `TickContext.getSensor(context).maxSensedSignal(slot)` 读取**四方向入边**的最大值（= 邻居朝漏斗发出的出边）。

> **v15 出边模型回归**：漏斗是非红石组件——信号层从不为它写边，槽位自身出边恒 0。旧实现 `getSignal` 读自身出边（`maxOfSlot`）在 v15 后**恒 0**，红石锁定永久失效（信号源改为「电力层涂蜡采样」同款入边语义后修复）。同款修复同时覆盖活 TNT 的点燃检测（见 [living-tnt-tech.md](living-tnt-tech.md)）。
