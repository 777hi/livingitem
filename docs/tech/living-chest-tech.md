# Living Chest (活箱子) 技术文档

> **文档版本**: 2026.09 v7.2  
> **最后更新**: 2026-09-08  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [存储系统](#2-存储系统)
3. [核心数据流](#3-核心数据流)
4. [GUI 交互与网络通信](#4-gui-交互与网络通信)
5. [配方书集成](#5-配方书集成)
6. [SlotAccessor 传输架构](#6-slotaccessor-传输架构)
7. [性能优化策略](#7-性能优化策略)
8. [已知问题与修复记录](#8-已知问题与修复记录)
9. [调试指南](#9-调试指南)

---

## 1. 架构概览

### 1.1 什么是活箱子？

活箱子是一种特殊的活物品（Living Item），它将普通箱子的物品存储功能**内嵌到物品自身的 DataComponent 中**。每个活箱子实例直接在 `CONTAINER` 组件中存储最多 27 个槽位的物品数据，无需外部文件或 UUID 映射。

### 1.2 数据流总览图

**核心原则：配置数据归物品，衍生数据归容器。**

活箱子的 NBT 只存"我是谁、我该怎么配置"（`IS_LIVING`、`CONTAINER`），而"我在当前环境下的行为"（是否已满、已用字节数、已用槽位数）由容器级缓存 `ContainerSnapshot` 实时推导。

```
┌──────────────────────────────────────────────────────────────┐
│              配置数据归物品，衍生数据归容器                     │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ItemStack (DataComponent) ← 配置数据                │   │
│  │                                                      │   │
│  │  • IS_LIVING: true          ← 活物品标记             │   │
│  │  • CONTAINER: ItemContainerContents  ← 27 槽物品数据  │   │
│  │                                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                   │
│                          ▼                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ContainerSnapshot.ChestSnapshot ← 衍生数据           │   │
│  │                                                      │   │
│  │  • usedSlots: int            ← 已用槽位数             │   │
│  │  • usedBytes: int            ← 已用字节数             │   │
│  │  • isFull: boolean           ← 槽位是否已满           │   │
│  │  • isByteFull: boolean       ← 字节是否已满           │   │
│  │                                                      │   │
│  │  每 tick 预计算一次，所有活物品共享                     │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ✅ 随物品迁移（放入背包、容器、丢出地面都保留）             │
│  ✅ 可序列化到存档（通过 DataComponent 系统）                │
│  ✅ 自动同步到客户端（原版容器同步机制）                     │
│  ✅ 衍生数据无需持久化（由容器实时推导，无一致性问题）        │
└──────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingChestFunction` | `domain/chest/LivingChestFunction.java` | 活箱子功能入口，实现 `LivingItemFunction` 接口，提供 insert/extract/canInsert 等 API |
| `LivingChestAccessor` | `domain/chest/LivingChestAccessor.java` | SlotAccessor 实现，通过 `ChestSnapshot` 判断空/满状态，统一活箱子的 extract/insert 接口 |
| `ContainerSnapshot` | `container/ContainerSnapshot.java` | 容器级缓存，每 tick 预计算所有活箱子的 `ChestSnapshot`（usedSlots/usedBytes/isFull/isByteFull） |
| `SlotAccessorFactory` | `transfer/SlotAccessorFactory.java` | 工厂类，根据物品类型创建对应的 SlotAccessor，传递 `ContainerSnapshot` 给 Accessor |
| `LivingChestAccessPacket` | `network/LivingChestAccessPacket.java` | 存取请求包（客户端→服务端） |
| `ServerPacketHandler` | `network/ServerPacketHandler.java` | 网络请求处理器 |
| `ServerPlaceRecipeMixin` | `mixin/ServerPlaceRecipeMixin.java` | Mixin：拦截配方书合成，支持从活箱子提取材料 |
| `RecipeBookComponentMixin` | `mixin/RecipeBookComponentMixin.java` | Mixin：配方书活箱子标签页（客户端） |
| `PinyinHelper` | `util/PinyinHelper.java` | 拼音搜索工具类 |
| `LivingChestTabState` | `gui/LivingChestTabState.java` | 活箱子标签页激活状态 |

---

## 2. 存储系统

### 2.1 存储位置

活箱子的所有数据存储在 ItemStack 的 DataComponent 中，无需外部文件。

**存储结构**:
```
ItemStack
├── IS_LIVING: true                          ← 活物品标记
├── CONTAINER: ItemContainerContents         ← 27 槽物品数据
│   └── [ItemStack × 27]                     ← 实际物品（空槽为 EMPTY）
```

### 2.2 CONTAINER 组件

使用原版 `DataComponents.CONTAINER`（`ItemContainerContents`）存储物品：

```java
// 读取
List<ItemStack> items = LivingChestFunction.getItems(chestStack);

// 写入
LivingChestFunction.setItems(chestStack, items);

// 清空
LivingChestFunction.clearStorage(chestStack);
```

**优势**:
- ✅ 原版组件，序列化/反序列化由 Minecraft 处理
- ✅ 自动随存档保存和加载
- ✅ 自动通过网络包同步到客户端
- ✅ 无需自定义持久化逻辑

**限制**:
- ⚠️ NBT 大小受网络包限制（2MB 硬上限），因此设置 16KB 警戒线
- ⚠️ 堆叠数 > 1 时禁止操作（防止数据分裂）

### 2.3 字节容量限制（16KB）

活箱子的 NBT 数据通过网络包同步到客户端。Minecraft 的网络栈对单个 NBT Tag 有 2MB 硬上限，超过会导致崩溃。为防止玩家向活箱子塞入大量高 NBT 物品（如成书、附魔武器等），设置了 16KB（16384 字节）的警戒线。

**设计原则**: 16KB 是"禁止线"而非"容量上限"
```
当前活箱子大小 < 16KB → 允许插入
当前活箱子大小 ≥ 16KB → 拒绝插入

15KB 时放入 2KB 物品 → 允许（当前 < 16KB）
插入后变成 17KB → 下次插入被拒绝（当前 ≥ 16KB）
```

**字节大小计算**:
```java
// 精确计算：序列化整个活箱子 ItemStack → 二进制字节
public static int getCurrentByteUsage(ItemStack chestStack, HolderLookup.Provider registries) {
    CompoundTag tag = (CompoundTag) chestStack.saveOptional(registries);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(baos)) {
        NbtIo.write(tag, dos);
    }
    return baos.size();
}
```

---

## 3. 核心数据流

### 3.1 存入物品流程

```
用户操作: 往活箱子放入钻石
       │
       ▼
LivingChestFunction.insertItem(chestStack, diamondStack)
       │
       ├─ 1. 前置检查
       │     chestStack.getCount() > 1 → 拒绝（堆叠数 >1 不允许操作）
       │     chestStack == itemToInsert → 拒绝（不能存入自己）
       │     !canInsert() → 拒绝（字节容量已满）
       │
       ├─ 2. 读取当前物品列表
       │     List<ItemStack> items = getItems(chestStack);
       │     └─ 从 CONTAINER 组件复制 27 个槽位
       │
       ├─ 3. 遍历槽位，尝试插入
       │     for each slot in items:
       │       if slot.isEmpty():
       │           slot = itemToInsert.copy()
       │           itemToInsert.setCount(0)  ← 完全消耗
       │       else if sameItemSameComponents(slot, itemToInsert):
       │           transfer = min(available, itemToInsert.count)
       │           slot.grow(transfer)
       │           itemToInsert.shrink(transfer)
       │
       ├─ 4. 写回 CONTAINER 组件
       │     if modified:
       │         setItems(chestStack, items)
       │
       └─ 5. 返回结果
              return itemToInsert.isEmpty()  ← true=完全插入, false=部分/未插入
```

### 3.2 取出物品流程

```
用户操作: 从活箱子取出物品
       │
       ▼
LivingChestFunction.extractItem(chestStack, target, amount)
       │
       ├─ 1. 前置检查
       │     chestStack.getCount() > 1 → 返回 EMPTY
       │
       ├─ 2. 读取当前物品列表
       │     List<ItemStack> items = getItems(chestStack);
       │
       ├─ 3. 遍历槽位，提取匹配物品
       │     for each slot in items:
       │       if slot.isEmpty() || !sameItemSameComponents(target, slot): continue
       │
       │       if result.isEmpty():
       │           result = slot.copyWithCount(min(amount, slot.count))
       │           slot.shrink(extracted)
       │       else if sameItemSameComponents(result, slot):
       │           toExtract = min(remaining, spaceAvailable)
       │           result.grow(toExtract)
       │           slot.shrink(toExtract)
       │
       │       if slot.isEmpty(): items.set(i, EMPTY)
       │
       ├─ 4. 写回 CONTAINER 组件
       │     if modified: setItems(chestStack, items)
       │
       └─ 5. 返回提取的物品
              return result
```

### 3.3 Tick 维护

活箱子在 tick 中不执行任何操作（空实现），因为其数据已在 `CONTAINER` 组件中，不需要每 tick 更新。

```java
@Override
public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    if (level.isClientSide) return;
    // 空实现 —— 活箱子数据已存储在 CONTAINER 组件中
}
```

### 3.4 取消活化

当活箱子取消活化时，`dropAllItems()` 将箱子内所有物品掉落到玩家位置。

**堆叠倍数返还**（2026-09-08 修复）：堆叠数 N 的活箱子语义上是
N 个内容完全相同的箱子——`CONTAINER` 组件相同才允许堆叠，且堆叠期间
存取关闭（`count > 1` 时全部操作拒绝，见 §3.1/§3.2 前置检查），
该不变量始终成立——因此取消活化必须返还 **N 份内容**，否则 N−1 份凭空蒸发。
这个场景原版背包即可触达（两个内容相同的活箱子自动堆叠），并非只有模组
超大容器才会触发。

```java
public static void dropAllItems(ItemStack chestStack, Player player) {
    if (!isLivingChest(chestStack)) return;

    List<ItemStack> drops = collectDeactivationDrops(chestStack);  // 纯计算
    // ... 逐堆生成 ItemEntity 掉落 ...
    clearStorage(chestStack);
}

/** 计算取消活化返还清单（不改组件，可单测） */
public static List<ItemStack> collectDeactivationDrops(ItemStack chestStack) {
    if (!isLivingChest(chestStack)) return List.of();
    int copies = Math.max(1, chestStack.getCount());
    // 每槽总量 = count × copies，超出物品堆叠上限的拆成多个满堆
    // 掉落实体数 = ⌈槽位数量 × N / maxStackSize⌉
}
```

---

## 4. GUI 交互与网络通信

### 4.1 通信协议

客户端通过 `LivingChestAccessPacket` 向服务端发送存取请求：

| 操作类型 | 说明 |
|---------|------|
| `DEPOSIT` | 存入物品 |
| `WITHDRAW` | 取出物品 |
| `WITHDRAW_INVENTORY` | 从玩家背包批量取出 |
| `DEPOSIT_SLOT` | 指定槽位存入 |

### 4.2 服务端处理

`ServerPacketHandler` 接收请求，调用 `LivingChestFunction` 的 API 执行操作，然后同步客户端。

---

## 5. 配方书集成

### 5.1 客户端材料表（stackedContents）注入

原版配方书用 `RecipeBookComponent.stackedContents` 统计"玩家现在有多少材料"，
以此决定哪些配方显示为可合成。**原版有且仅有两条重建这张表的路径**：

```
路径 A  initVisuals()                    ← 打开配方书 / 切换可见性时调用
          stackedContents.clear()
          inventory.fillStackedContents()
          menu.fillCraftSlotsStackedContents()
          updateCollections(false)

路径 B  updateStackedContents()          ← 背包变动计数变化 / 点击槽位时调用
          （同上三步）
          updateCollections(false)
```

`RecipeBookComponentMixin` 在**两条路径**的 `updateCollections(Z)V` 调用前各注入一次，
把背包里所有活箱子的内容 `accountStack` 进去：

| 注入方法 | 注入点 | 作用 |
|---------|--------|------|
| `beforeInitVisualsCollections` | `initVisuals` → `INVOKE updateCollections` | 覆盖"刚打开配方书" |
| `beforeUpdateCollections` | `updateStackedContents` → `INVOKE updateCollections` | 覆盖"背包变动后" |
| `accountLivingChestItems` | （公共 helper，被上面两者调用） | 遍历活箱子 `accountStack` |

> ⚠️ **两条路径缺一不可**。只注入路径 B 会导致"打开配方书时活箱子材料不被识别，
> 必须手动做点什么触发一次 `updateStackedContents()` 才恢复"。

其余 `updateCollections` 调用点（切标签、搜索、切筛选、`recipesUpdated`）都**不重建**
材料表，只是复用，因此无需注入。

### 5.2 为什么不需要额外的刷新机制

活箱子内容变化时配方书会自动更新，**无需模组自己轮询**。完整链路：

```
服务端改写活箱子 CONTAINER 组件
  ↓ AbstractContainerMenu.triggerSlotListeners()
    用 ItemStack.matches(lastStack, stack) 判定 → CONTAINER 变了 → 不相等
  ↓ 发包 → 客户端 Slot.set → Inventory.setItem → timesChanged++
  ↓ RecipeBookComponent.tick() 检测到计数变化 → 走路径 B → 注入生效
```

延迟约 1–3 tick。**这里有两个前提，都已验证成立**：

1. `ItemStack.matches` **没有被 `ItemStackMixin` 改写** —— 该 Mixin 只动了
   `isSameItemSameComponents`（活物品间能否堆叠）和 `getTooltipImage`（tooltip 图标）。
2. 活箱子必须在**当前打开菜单的槽位里**（`broadcastChanges` 只遍历 menu slots）。
   玩家背包槽位始终包含在内，而配方书也只扫玩家背包，所以对合成功能没有影响。

> ℹ️ 曾实现过一个「每 10 tick 采样活箱子内容指纹、变化则强制刷新」的保底机制，
> 后经验证确认原版链路更快（1–3 tick vs ≤10 tick）且已覆盖主流场景，
> 该机制属于冗余且引入了 4 个状态字段，已移除。**保持无状态是这个 Mixin 正确的原因**。

### 5.3 合成材料提取（服务端）

`ServerPlaceRecipeMixin` 拦截配方书合成操作，支持从活箱子提取合成材料：

```
玩家点击配方书合成
  ├─ 检查玩家背包是否有足够材料
  ├─ 材料不足 → 检查打开的容器中是否有活箱子
  └─ 从活箱子提取材料 → 完成合成
```

### 5.4 活箱子标签页

`RecipeBookComponentMixin` 在配方书 GUI 中添加活箱子标签页：
- 显示活箱子中所有物品的网格排列
- 支持拼音搜索（全拼/首字母/混合匹配）
- 支持翻页浏览
- 点击物品执行存取操作

> 标签页的激活态（`LivingChestTabState`）在 `initVisuals` 时被重置为未激活，
> 即打开配方书始终默认停留在原版标签页（合成/熔炉），这是**有意保持原版行为**。

---

## 6. SlotAccessor 传输架构

### 6.1 LivingChestAccessor

活箱子通过 `LivingChestAccessor` 实现 `SlotAccessor` 接口，统一活箱子的 extract/insert 操作。

**v6 架构变更**：`LivingChestAccessor` 不再直接调用 `LivingChestFunction.isStorageEmpty/isStorageFull/isByteFull`，而是通过构造时注入的 `ChestSnapshot` 判断空/满状态：

```java
// 构造时注入 ChestSnapshot（来自 ContainerSnapshot）
LivingChestAccessor(ContainerContext ctx, int slot, ItemStack chestStack,
                    int capacity, Set<Integer> transferredSlots,
                    ContainerSnapshot.ChestSnapshot chestSnap)

// extract → 先查 ChestSnapshot 判断是否为空
if (chestSnap.usedSlots() == 0) return ItemStack.EMPTY;
LivingChestFunction.extractItem(chestStack, amount);

// insert → 先查 ChestSnapshot 判断是否已满
if (chestSnap.isFull()) return 0;
LivingChestFunction.insertItem(chestStack, itemToInsert);

// isEmpty / isFull → 直接查 ChestSnapshot
boolean isEmpty()  → chestSnap.usedSlots() == 0
boolean isFull()   → chestSnap.isFull() || chestSnap.isByteFull()
```

**设计意图**：衍生数据（是否已满、已用槽位数等）归容器级缓存 `ContainerSnapshot`，不归物品 NBT。`ChestSnapshot` 在每 tick 的 `capture()` 阶段预计算一次，所有活漏斗/跨容器传输共享同一份缓存数据，避免每个活物品重复反序列化 `CONTAINER` 组件。

### 6.2 工厂创建

`SlotAccessorFactory.create()` 在检测到槽位中是活箱子时，从 `ContainerSnapshot` 获取 `ChestSnapshot` 并创建 `LivingChestAccessor`：

```java
// SlotAccessorFactory.create() 签名（v6 新增 snapshot 参数）
public static SlotAccessor create(MinecraftServer server, ContainerContext ctx, int slot,
                                   FilterData filterData, Set<Integer> transferredSlots,
                                   ContainerSnapshot snapshot)

// LivingChestAccessor.tryCreate() 实现
if (LivingChestFunction.isLivingChest(stack)) {
    ContainerSnapshot.ChestSnapshot chestSnap = snapshot.getChestSnapshot(slot);
    return new LivingChestAccessor(ctx, slot, stack, capacity, transferredSlots, chestSnap);
}
```

### 6.3 跨容器传输中的 ChestSnapshot 使用

`CrossContainerTransfer` 中的活箱子相关方法同样使用 `ChestSnapshot` 替代直接调用 `LivingChestFunction`：

| 方法 | 原调用 | 替换为 |
|------|--------|--------|
| `pushFromLivingChestToNeighbor` | `isStorageEmpty(chestStack)` | `tick.snapshot.getChestSnapshot(sourceSlot).usedSlots() == 0` |
| `pullFromNeighborToLivingChest` | `isStorageFull(chestStack) \|\| isByteFull(chestStack, registries)` | `chestSnap.isFull() \|\| chestSnap.isByteFull()` |

---

## 7. 性能优化策略

### 7.1 容器级缓存（ChestSnapshot）

活箱子的衍生数据（`usedSlots`/`usedBytes`/`isFull`/`isByteFull`）在 `ContainerSnapshot.capture()` 阶段统一预计算，存入 `ChestSnapshot` record。所有活漏斗、跨容器传输、`LivingChestAccessor` 共享同一份缓存数据，避免每个活物品重复反序列化 `CONTAINER` 组件。

```
new TickContext(context)
    └─ ContainerSnapshot.capture()
        └─ buildAllChestSnapshots()
            └─ 对每个活箱子槽位：
                ├─ countUsedSlots(stack)    → usedSlots
                ├─ getCurrentByteUsage(stack) → usedBytes
                ├─ usedSlots >= 27          → isFull
                └─ usedBytes >= 16384       → isByteFull
```

**性能收益**：假设容器中有 N 个活箱子，旧架构下每个活漏斗传输时都要调用 `isStorageEmpty/isStorageFull/isByteFull`（各需反序列化 `CONTAINER`），最坏情况 O(N×M) 次反序列化（M=活漏斗数）。新架构下仅 O(N) 次反序列化（在 `capture` 阶段），后续全部读缓存。

### 7.2 零 Tick 开销

活箱子在 tick 中不执行任何操作，因为数据已存储在 `CONTAINER` 组件中，无需缓存维护。

### 7.3 懒加载

物品列表只在需要时（insert/extract 实际操作）从 `CONTAINER` 组件读取，不维护常驻缓存。空/满判断使用 `ChestSnapshot` 缓存，不触发反序列化。

### 7.4 快照一致性说明

`ChestSnapshot` 是 tick 开始时的快照。如果在同一 tick 内活箱子被 extract 了物品，快照仍显示"有物品"。这在当前架构下不是问题——活漏斗的传输顺序由 `transferredTargetSlots` 互斥保护，最坏情况是 extract 返回空物品（快照误判为非空），此时 `LivingChestFunction.extractItem()` 会返回 `ItemStack.EMPTY`，不会导致数据错误。

---

## 8. 已知问题与修复记录

### 8.1 堆叠数 > 1 时数据分裂

**问题**：两个堆叠的活箱子可能因 `isSameItemSameComponents` 在不同 tick 中返回不同结果导致数据分裂。

**修复**：活箱子在 `getIgnoredComponentTypes()` 中返回运行时组件类型，确保 `ItemStackMixin` 比较时忽略运行时差异。

### 8.2 配方书翻页时物品不更新

**问题**：配方书标签页在翻页时显示的物品列表不刷新。

**修复**：在 `RecipeBookComponentMixin` 中每次翻页时重新读取活箱子物品列表。

---

### 8.3 打开配方书时活箱子材料不被识别（2026-09-05）

**现象**：打开配方书（默认停在原版合成标签页）时，活箱子里有材料、也能合成的配方
显示为"不可合成"；必须先切到活箱子标签页、再切回合成页，才恢复正常。

**根因**：原版重建 `stackedContents` 有两条路径——`initVisuals()`（打开配方书时）和
`updateStackedContents()`（背包变动时）。Mixin 只注入了后者，于是"刚打开配方书"
这一刻走的是 `initVisuals()`，活箱子物品从未被计入材料表。之后任何触发
`updateStackedContents()` 的操作（背包变动、点击槽位）都会让它"神奇地恢复正常"，
这正是"切一下标签页就好了"的真实原因——与活箱子标签页本身无关。

**修复**：
1. 新增 `beforeInitVisualsCollections`，在 `initVisuals` 的 `updateCollections(Z)V`
   调用前同样注入 `accountLivingChestItems()`，两条路径都覆盖。
2. 抽出公共 helper `accountLivingChestItems()` 供两条路径复用。
   改动净 +2 个注入点、**0 个新状态字段**——原版同步链路已负责内容变化后的刷新。

**验证清单**：
- [ ] 背包只放活箱子（材料全在箱子里）→ 打开配方书，配方直接显示为可合成
- [ ] 不做任何额外操作，直接点击可合成的配方 → 能正常合成
- [ ] 关闭再打开配方书 → 依旧直接可合成（不需要切标签页）
- [ ] 活漏斗持续往活箱子里塞材料 → 配方书可合成状态在 1–3 tick 内自动更新
- [ ] 从活箱子取出材料后 → 配方书可合成状态自动回落

### 8.4 堆叠活箱子取消活化只返还一份内容（2026-09-08）

**现象**：两个装着相同物品的活箱子在背包里堆叠成 count=2 后取消活化，
只掉落一份内容，另一半凭空蒸发。

**根因**：活箱子堆叠的条件是 `CONTAINER` 组件相同（`ItemStackMixin` 按
组件等价判定可堆叠），且堆叠期间存取关闭（`count > 1` 拒绝一切操作），
所以「count=N 的堆叠活箱子」语义上是 **N 个各含一份相同内容的箱子**。
而 `dropAllItems()` 只读取一份 `CONTAINER` 内容掉落，堆叠数被无视。
该路径原版背包即可触达，并非超大堆叠容器专属。

**修复**：返还清单计算抽成纯函数 `collectDeactivationDrops()`：
每槽总量 = `slotCount × N`，超出物品堆叠上限的拆成多个满堆掉落。
`dropAllItems()` 复用该函数。回归测试 `LivingChestFunctionTest`（6 项）。

**验证清单**：
- [ ] 两个内容相同的活箱子堆叠 → 取消活化 → 掉落两份内容
- [ ] 单个活箱子取消活化 → 行为与修复前一致（每槽一个掉落实体）
- [ ] 堆叠箱内 64 满堆 × count=3 → 掉落 3 个满堆实体，无超上限堆

---

## 9. 调试指南

### 9.1 查看存储内容

在游戏中使用 `/data get entity @p` 查看手持活箱子的 NBT 数据，关注 `components."minecraft:container"` 字段。

### 9.2 检查字节用量

Tooltip 中会显示当前字节用量和百分比，如果接近 100% 说明活箱子即将满。

### 9.3 日志输出

在 `LivingItemManager.LOGGER` 中启用 debug 日志可查看活箱子存取操作的详细记录。

---

## 附录：v6 变更记录 (2026-07-28)

**变更1：衍生数据归容器（ChestSnapshot）**

活箱子的衍生数据（`usedSlots`/`usedBytes`/`isFull`/`isByteFull`）从 `LivingChestFunction` static 方法调用迁移到 `ContainerSnapshot.ChestSnapshot` 容器级缓存。

- `LivingChestAccessor` 新增 `ChestSnapshot chestSnap` 字段，`isEmpty()`/`isFull()`/`extract()`/`insert()`/`simulateExtract()`/`simulateInsert()` 6 个方法中的 `isStorageEmpty`/`isStorageFull`/`isByteFull` 调用替换为 `chestSnap` 查询
- `SlotAccessorFactory.Provider.create()` 和 `SlotAccessorFactory.create()` 新增 `ContainerSnapshot snapshot` 参数
- `CrossContainerTransfer.pushFromLivingChestToNeighbor` 和 `pullFromNeighborToLivingChest` 新增 `TickContext tick` 参数，使用 `ChestSnapshot` 替代 `LivingChestFunction` static 调用
- `LivingEnderChestAccessor.tryCreate()` 签名对齐新增 `ContainerSnapshot snapshot` 参数

**变更2：架构原则确立**

确立"配置数据归物品，衍生数据归容器"的架构原则。活箱子的 `CONTAINER` 组件是配置数据（持久化到 NBT），`ChestSnapshot` 是衍生数据（每 tick 实时推导，不持久化）。

---

## 附录：v5 变更记录 (2026-07-28)

**变更1：Tooltip 国际化**

Tooltip 显示从硬编码字符串改为使用 `Component.translatable()` 国际化键，支持多语言。

**变更2：字节计算优化**

`calculateExactByteUsage()` 新增 `registries` 参数的重载版本，当 `registries` 为 null 时使用默认估算值（64 字节/物品），避免在无注册表上下文时崩溃。

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础存储

- [ ] 活箱子拥有 27 槽位虚拟存储
- [ ] 物品可通过活漏斗插入/提取
- [ ] 虚拟存储容量受字节限制（默认 16384 字节）
- [ ] 超出容量时拒绝插入

### 与活漏斗交互

- [ ] 活漏斗 source 指向活箱子时，从虚拟存储提取物品
- [ ] 活漏斗 target 指向活箱子时，向虚拟存储插入物品
- [ ] 活箱子间通过活漏斗传输检查过滤规则
- [ ] 白名单过滤时精确提取目标类型物品

### 容器级缓存（ChestSnapshot）

- [ ] `ContainerSnapshot.capture()` 正确预计算活箱子的 usedSlots/usedBytes/isFull/isByteFull
- [ ] `LivingChestAccessor.isEmpty()` 使用 ChestSnapshot 判断而非直接调用 LivingChestFunction
- [ ] `LivingChestAccessor.isFull()` 使用 ChestSnapshot 判断而非直接调用 LivingChestFunction
- [ ] 跨容器传输中活箱子的空/满判断使用 ChestSnapshot
- [ ] 同一 tick 内多个活漏斗共享同一份 ChestSnapshot 数据
- [ ] 快照误判（tick 内状态变化）不会导致数据错误（extract 返回空物品而非崩溃）

### 字节计算

- [ ] calculateExactByteUsage() 正确计算当前字节用量
- [ ] registries 为 null 时使用默认估算值（64 字节/物品）
- [ ] Tooltip 显示字节用量和百分比

### Tooltip 与同步

- [ ] Tooltip 显示存储内容摘要
- [ ] Tooltip 国际化（Component.translatable）
- [ ] 存储内容变化后 Tooltip 更新

### 数据持久化

- [ ] 虚拟存储通过 DataComponent 持久化到物品 NBT
- [ ] 物品离开容器后虚拟存储数据不丢失
- [ ] 多个活箱子实例间数据独立（不同物品 = 不同存储）