# Living Chest (活箱子) 技术文档

## 目录
1. [架构概览](#1-架构概览)
2. [存储系统](#2-存储系统)
3. [核心数据流](#3-核心数据流)
4. [关键组件详解](#4-关键组件详解)
5. [GUI 交互与网络通信](#5-gui-交互与网络通信)
6. [配方书集成](#6-配方书集成)
7. [SlotAccessor 传输架构](#7-slotaccessor-传输架构)
8. [性能优化策略](#8-性能优化策略)
9. [已知问题与修复记录](#9-已知问题与修复记录)
10. [调试指南](#10-调试指南)

---

## 1. 架构概览

### 1.1 什么是活箱子？

活箱子是一种特殊的活物品（Living Item），它将普通箱子的物品存储功能**内嵌到物品自身的 DataComponent 中**。每个活箱子实例直接在 `CONTAINER` 组件中存储最多 27 个槽位的物品数据，无需外部文件或 UUID 映射。

### 1.2 数据流总览图

```
┌──────────────────────────────────────────────────────────────┐
│                    单层存储架构                                │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ItemStack (DataComponent)                           │   │
│  │                                                      │   │
│  │  • IS_LIVING: true          ← 活物品标记             │   │
│  │  • LIVING_FUNCTION_DATA:    ← 功能状态               │   │
│  │    └─ living_chest                                   │   │
│  │       └─ internal_storage                            │   │
│  │          ├─ _us: int        ← 已用槽位数             │   │
│  │          ├─ _bu: int        ← 字节用量               │   │
│  │          └─ _ch: int        ← 容器哈希（脏检查）     │   │
│  │  • CONTAINER: ItemContainerContents  ← 27 槽物品数据  │   │
│  │                                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ✅ 随物品迁移（放入背包、容器、丢出地面都保留）             │
│  ✅ 可序列化到存档（通过 DataComponent 系统）                │
│  ✅ 自动同步到客户端（原版容器同步机制）                     │
│  ✅ 无外部文件、无 UUID、无缓存一致性问题                    │
└──────────────────────────────────────────────────────────────┘
```

**与旧版 UUID 架构的对比**:

| 特性 | 旧版（UUID + WorldStorage） | 当前版（DataComponent 直存） |
|------|---------------------------|---------------------------|
| 存储位置 | 磁盘文件 `data/living_chests/xx/uuid.dat` | ItemStack 的 `CONTAINER` 组件 |
| 数据同步 | 需要自定义网络包 | 原版容器同步自动处理 |
| 缓存管理 | LRU 缓存 + 脏标记 + 定期保存 | 无需缓存 |
| 堆叠支持 | UUID 拆分/合并 + 6 个 ThreadLocal | `isSameItemSameComponents` 忽略运行时组件（LIVING_FUNCTION_DATA），不同物品的活箱子不堆叠 |
| 持久化 | 显式 `saveAllDirty()` | 随存档自动保存 |
| 复杂度 | 高（两层存储 + 线程安全 + 文件 I/O） | 低（单层存储 + 原版机制） |

### 1.3 关键类和职责

#### 核心存储系统
| 类名 | 职责 |
|------|------|
| `LivingChestFunction` | 活箱子功能入口，提供 insert/extract/canInsert 等 API |
| `InternalStorageComponent` | 内部存储组件，管理 CONTAINER 组件的读写、字节容量限制 |

#### 传输架构
| 类名 | 职责 |
|------|------|
| `ItemTransferComponent` | 物品传输组件，处理活漏斗↔活箱子的传输 |
| `LivingChestAccessor` | SlotAccessor 实现，统一活箱子的 extract/insert 接口 |
| `SlotAccessorFactory` | 工厂类，根据物品类型创建对应的 SlotAccessor |

#### GUI 交互与网络通信
| 类名 | 职责 |
|------|------|
| `LivingChestAccessPacket` | 存取请求包（客户端→服务端）: DEPOSIT/WITHDRAW/WITHDRAW_INVENTORY/DEPOSIT_SLOT |
| `ServerPacketHandler` | 网络请求处理器，分发并执行存取操作 |

#### 配方书集成
| 类名 | 职责 |
|------|------|
| `ServerPlaceRecipeMixin` | Mixin：拦截配方书合成，支持从活箱子提取材料 |
| `RecipeBookComponentMixin` | Mixin：配方书活箱子标签页（客户端），网格显示、搜索、翻页、点击存取 |
| `PinyinHelper` | 拼音搜索工具类，20924 字符映射，支持全拼/首字母/混合匹配 |
| `LivingChestTabState` | 活箱子标签页激活状态（跨 Mixin 共享） |

#### 堆叠与比较
| 类名 | 职责 |
|------|------|
| `ItemStackMixin` | Mixin：活物品堆叠比较（忽略运行时组件差异）+ 工具提示渲染 |
| `LivingChestTooltipComponent` | 工具提示数据记录，传递物品列表给渲染器 |

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
└── LIVING_FUNCTION_DATA: CompoundTag        ← 功能状态
    └─ living_chest
       └─ internal_storage
          ├─ _us: int                        ← 已用槽位数（O(1) 快速判断）
          ├─ _bu: int                        ← 字节用量（16KB 限制）
          └─ _ch: int                        ← 容器哈希（脏检查优化）
```

### 2.2 CONTAINER 组件

使用原版 `DataComponents.CONTAINER`（`ItemContainerContents`）存储物品：

```java
// 读取
ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
contents.copyInto(list);

// 写入
NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
// ... 填充物品 ...
stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));

// 清空
stack.remove(DataComponents.CONTAINER);
```

**优势**:
- ✅ 原版组件，序列化/反序列化由 Minecraft 处理
- ✅ 自动随存档保存和加载
- ✅ 自动通过网络包同步到客户端
- ✅ 无需自定义持久化逻辑

**限制**:
- ⚠️ NBT 大小受网络包限制（2MB 硬上限），因此设置 16KB 警戒线
- ⚠️ 堆叠数 > 1 时禁止操作（防止数据分裂）

### 2.3 初始化

当物品被标记为活箱子时，自动添加空的 CONTAINER 组件：

```java
// LivingItemManager.setLiving()
if (stack.is(Items.CHEST) && !stack.has(DataComponents.CONTAINER)) {
    stack.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
}
```

### 2.4 字节容量限制（16KB）

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

// 估算（无 registries 时的后备）：物品数 × 64字节
private static int estimateByteUsage(ItemStack chestStack) {
    int count = countUsedSlots(chestStack);
    return count * 64;
}
```

**脏检查机制**（避免每 tick 序列化）:
```java
// tick() 中
int prevHash = state.getInt(KEY_CONTAINER_HASH, 0);
int curHash = containerHash(chestStack);  // ItemContainerContents.hashCode()
if (curHash != prevHash) {
    state.setIntSilent(KEY_CONTAINER_HASH, curHash);
    updateByteUsage(chestStack, state, ctx.level());  // 变了才序列化
}
```

| 检查方式 | 开销 | 精确度 |
|---------|------|--------|
| `hashCode()` 比较 | 纳秒级 | 检测任何变化 |
| 序列化计算字节 | 微秒级 | 精确字节数 |

99.9% 的 tick 只做 `hashCode()` 比较（零序列化开销），只有物品变化时才触发一次序列化。

---

## 3. 核心数据流

### 3.1 存入物品流程

```
用户操作: 往活箱子放入钻石
       │
       ▼
InternalStorageComponent.insertItem(chestStack, diamondStack, registries)
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
InternalStorageComponent.extractItem(chestStack, target, amount)
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

### 3.3 Tick 维护流程

每次服务端 tick，`InternalStorageComponent.tick()` 更新缓存状态：

```
每 tick 执行:
       │
       ▼
InternalStorageComponent.tick(ctx, hostSlot, hostStack, state, config)
       │
       ├─ 1. 客户端跳过
       │     if ctx.level().isClientSide() → return
       │
       ├─ 2. 哈希脏检查
       │     prevHash = state.getInt(KEY_CONTAINER_HASH, 0)
       │     curHash = containerHash(hostStack)
       │     if curHash != prevHash:
       │         updateByteUsage(hostStack, state, ctx.level())
       │
       └─ 3. 更新已用槽位数
              state.setInt(KEY_USED_SLOTS, countUsedSlots(hostStack))
```

**设计意图**:
- `_ch` 哈希脏检查避免每 tick 序列化（性能优化）
- `_us` 已用槽位计数提供 O(1) 快速判断
- `_bu` 字节用量仅在物品变化时重新计算

---

## 4. 关键组件详解

### 4.1 InternalStorageComponent（内部存储组件）

**职责**: 管理 CONTAINER 组件的读写、字节容量限制、缓存状态更新

**核心方法**:

| 方法 | 功能 | 返回值 |
|------|------|--------|
| `getItems(chestStack)` | 读取 27 槽物品列表 | `List<ItemStack>`（副本） |
| `setItems(chestStack, items)` | 写入 27 槽物品列表 | void |
| `insertItem(chestStack, item, registries)` | 存入物品 | `boolean`（是否完全插入） |
| `extractItem(chestStack, amount)` | 按数量取出 | `ItemStack` |
| `extractItem(chestStack, target, amount)` | 按类型取出 | `ItemStack` |
| `canInsert(chestStack, item, registries)` | 检查是否可插入 | `boolean` |
| `isStorageEmpty(chestStack)` | 存储是否为空 | `boolean` |
| `isStorageFull(chestStack)` | 存储是否已满 | `boolean` |
| `isByteFull(chestStack, registries)` | 字节容量是否已满 | `boolean` |
| `getCurrentByteUsage(chestStack, registries)` | 当前字节用量 | `int` |
| `clearStorage(chestStack)` | 清空存储 | void |

**安全检查**:
- `chestStack.getCount() > 1` → 拒绝存取操作（`insertItem`/`extractItem`/`canInsert`），堆叠活箱子处于"冻结"状态
- `chestStack == itemToInsert` → 拒绝存入（防止存入自己）
- `isByteFull()` → 拒绝存入（16KB 限制）

**注意**: `isStorageEmpty`/`isStorageFull` 是纯查询方法，反映 CONTAINER 的实际状态，不受 `count > 1` 影响。这意味着堆叠活箱子如果有物品，`isStorageEmpty` 返回 `false`（工具提示会显示内容预览），但存取操作仍被拦截。

### 4.2 LivingChestFunction（功能入口）

**职责**: 提供简洁的 API，委托给 `InternalStorageComponent`

```java
public class LivingChestFunction extends BaseLivingFunction {
    public static final String ID = "living_chest";
    public static final int CHEST_SLOTS = 27;

    // 所有静态方法直接委托给 InternalStorageComponent
    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert, ...) {
        return InternalStorageComponent.insertItem(chestStack, itemToInsert, ...);
    }
    // ...
}
```

**额外功能**:
- `isLivingChest(stack)`: 判断是否为活箱子（`Items.CHEST` + `isLivingItem`）
- `hasStorage(stack)`: 判断是否有 CONTAINER 组件
- `dropAllItems(chestStack, player)`: 取消活化时掉落所有物品
- `getStorageState(stack)` / `saveStorageState(stack, state)`: 读写功能状态

### 4.3 ItemStackMixin（堆叠比较）

**职责**: 活物品堆叠比较时忽略运行时状态组件的差异

**两大注入**:

#### 注入 1: `getTooltipImage` — 工具提示渲染

当活箱子有物品时，显示缩略图：

```java
@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
private void onGetTooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
    ItemStack self = (ItemStack) (Object) this;
    if (!LivingChestFunction.isLivingChest(self)) return;
    if (InternalStorageComponent.isStorageEmpty(self)) return;
    cir.setReturnValue(Optional.of(new LivingChestTooltipComponent(
        InternalStorageComponent.getItems(self), 9, 3)));
}
```

#### 注入 2: `isSameItemSameComponents` — 堆叠判定

活物品比较时，收集所有适用功能的 `getIgnoredComponentTypes()`，在比较时跳过这些组件：

```java
@Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
private static void onIsSameItemSameComponents(ItemStack stack1, ItemStack stack2, ...) {
    if (!LivingItemManager.isLivingItem(stack1) || !LivingItemManager.isLivingItem(stack2)) return;
    if (stack1.getItem() != stack2.getItem()) { cir.setReturnValue(false); return; }

    Set<DataComponentType<?>> ignoredTypes = new HashSet<>();
    for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(stack1)) {
        ignoredTypes.addAll(func.getIgnoredComponentTypes());
    }
    if (ignoredTypes.isEmpty()) return;

    // 逐组件比较，跳过 ignoredTypes
    DataComponentMap map1 = stack1.getComponents();
    DataComponentMap map2 = stack2.getComponents();
    // ... 比较逻辑 ...
    cir.setReturnValue(true);
}
```

**效果**:
- 两个活箱子（不同内部物品）→ 不堆叠（CONTAINER 差异不被忽略，物品数据直接存储在组件中）
- 两个活箱子（相同内部物品，不同运行时状态）→ 可以堆叠（忽略 LIVING_FUNCTION_DATA 差异）
- 重命名 vs 无名称 活箱子 → 不堆叠（保留自定义名称差异）
- 不同类型活物品 → 不堆叠

---

## 5. GUI 交互与网络通信

### 5.1 系统架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                     GUI 交互系统架构                              │
│                                                                  │
│  客户端 (Client)                                                  │
│  ┌──────────────────┐    ┌────────────────────┐                 │
│  │ 配方书活箱子标签页 │───▶│ LivingChestAccess  │                 │
│  │ (RecipeBookComp   │    │ Packet (请求包)     │                 │
│  │  onentMixin)      │    └────────────────────┘                 │
│  └──────────────────┘                                             │
│  ┌──────────────────┐    ┌────────────────────┐                 │
│  │ Shift+左键快速存入│───▶│ LivingChestAccess  │                 │
│  │ (InventoryScreen  │    │ Packet             │                 │
│  │  Mixin等)         │    │ (DEPOSIT_SLOT)     │                 │
│  └──────────────────┘    └────────────────────┘                 │
│                                                                  │
│  服务端 (Server)                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    ServerPacketHandler                    │   │
│  │  handleDeposit() / handleWithdraw() / handleDepositFrom  │   │
│  │  Slot() / handleWithdrawToInventory()                    │   │
│  └──────────────────────────────────────────────────────────┘   │
│                          │                                       │
│                          ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         LivingChestFunction → InternalStorageComponent   │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

**关键设计**: 客户端直接从 ItemStack 的 CONTAINER 组件读取物品数据（无需请求服务端），操作（存取）则通过网络包发送到服务端执行。

### 5.2 LivingChestAccessPacket（存取请求包）

**操作类型**:
| 操作 | 值 | 说明 |
|------|-----|------|
| `LOAD` | 0 | 请求加载活箱子内容（当前未使用，客户端直接读取） |
| `DEPOSIT` | 1 | 将光标物品存入活箱子 |
| `WITHDRAW` | 2 | 从活箱子取出指定物品到光标 |
| `WITHDRAW_INVENTORY` | 3 | 从活箱子取出物品到玩家背包 |
| `DEPOSIT_SLOT` | 4 | 从玩家背包指定槽位存入物品到活箱子（Shift+左键快速存入） |

**数据结构**:
```java
public record LivingChestAccessPacket(
    int action,
    @Nullable CompoundTag itemTag,  // 物品 NBT（DEPOSIT/WITHDRAW 时使用）
    int amount                       // 数量
)
```

### 5.3 服务端处理逻辑

#### handleDeposit（光标物品存入）

```
1. 获取光标物品 carried
2. carried.split(amount) 分离出 toInsert
3. 遍历背包找活箱子:
   isLivingChest && hasStorage && canInsert → insertItem
4. 插入失败 → carried.grow(toInsert.getCount()) 退回
5. syncCarriedToClient + broadcastChanges
```

#### handleWithdraw（取出到光标）

```
1. 解析目标物品 target = ItemStack.parse(registries, itemTag)
2. 检查光标兼容性（空或同类）
3. 遍历背包找活箱子:
   isLivingChest && hasStorage → extractItem(invStack, target, amount)
4. 设置光标或增长光标
5. syncCarriedToClient + broadcastChanges
```

#### handleDepositFromSlot（Shift+左键快速存入）

```
1. 从背包找到匹配物品 invStack
2. toInsert = invStack.copyWithCount(transfer); invStack.shrink(transfer)
3. 遍历背包找活箱子:
   isLivingChest && count==1 && hasStorage && !isStorageFull && canInsert
   → insertItem
4. 插入失败 → 放回背包
5. broadcastChanges
```

#### handleWithdrawToInventory（取出到背包）

```
1. 解析目标物品
2. 遍历背包找活箱子 → extractItem
3. player.getInventory().add(extracted)
4. broadcastChanges
```

---

## 6. 配方书集成

### 6.1 配方书合成材料提取

#### ServerPlaceRecipeMixin

**两大拦截点**:

##### 拦截点 1: `recipeClicked()` — 材料注册

在原版 `fillStackedContents()` 之后，将活箱子内的物品注册到配方系统的材料计数器：

```java
@Inject(method = "recipeClicked", at = @At(
    value = "INVOKE",
    target = "...Inventory;fillStackedContents...",
    shift = At.Shift.AFTER
))
private void afterFillStackedContents(ServerPlayer player, RecipeHolder recipe, boolean placeAll, CallbackInfo ci) {
    addLivingChestItemsToStackedContents();
}
```

**效果**: 配方书能识别活箱子内的物品，显示"可合成"标记。

##### 拦截点 2: `moveItemToGrid()` — 物品提取

当背包中没有所需物品时，从活箱子提取物品放入合成栏：

```java
@Inject(method = "moveItemToGrid", at = @At("HEAD"), cancellable = true)
private void onMoveItemToGrid(Slot slot, ItemStack stack, int maxAmount, ...) {
    int slotIndex = this.inventory.findSlotMatchingUnusedItem(stack);
    if (slotIndex != -1) return;  // 背包有，走原版

    ItemStack extracted = extractFromLivingChests(stack, maxAmount);
    if (extracted.isEmpty()) { cir.setReturnValue(-1); return; }

    if (slot.getItem().isEmpty()) slot.set(extracted);
    else slot.getItem().grow(extracted.getCount());

    cir.setReturnValue(maxAmount - extracted.getCount());
}
```

**优先级**: 普通背包 > 活箱子

### 6.2 配方书活箱子标签页

#### RecipeBookComponentMixin

在原版配方书中注入"活箱子"标签页，提供网格显示、搜索、翻页、点击存取功能。

**UI 布局**:
```
┌─────────────────────────────┐
│ [合成] [熔炉] [活箱子]      │ ← 标签栏
├─────────────────────────────┤
│ ┌─┬─┬─┬─┬─┐               │
│ │ │ │ │ │ │               │ ← 5列 × 4行 物品网格
│ ├─┼─┼─┼─┼─┤               │
│ │ │ │ │ │ │               │
│ ├─┼─┼─┼─┼─┤               │
│ │ │ │ │ │ │               │
│ ├─┼─┼─┼─┼─┤               │
│ │ │ │ │ │ │               │
│ └─┴─┴─┴─┴─┘               │
│     [<] 1/3 [>]           │ ← 分页导航
└─────────────────────────────┘
```

**数据读取**: 直接从客户端玩家背包的 ItemStack 读取 CONTAINER 组件，无需网络请求。

**交互操作**:
| 操作 | 行为 |
|------|------|
| 左键点击物品 | 取出 1 组到光标 |
| 右键点击物品 | 取出 1 个到光标 |
| Shift+左键点击背包物品 | 快速存入活箱子（DEPOSIT_SLOT） |

**搜索过滤**: 支持拼音搜索（全拼/首字母/混合匹配），通过 `PinyinHelper` 实现。

**标签页状态**: 通过 `LivingChestTabState`（静态布尔值）跨 Mixin 共享，供 `InventoryScreenMixin` 和 `AbstractContainerScreenMixin` 判断是否启用 Shift+左键快速存入。

### 6.3 Shift+左键快速存入

**触发条件**（同时满足）:
1. 配方书已打开且活箱子标签页激活
2. 点击的是背包槽位（非活箱子物品）
3. 玩家背包中有活箱子（`count==1`）

**实现**: `InventoryScreenMixin` 和 `AbstractContainerScreenMixin` 拦截 Shift+左键，发送 `DEPOSIT_SLOT` 网络包。

**服务端优先级**: 已有物品的活箱子 > 空活箱子

---

## 7. SlotAccessor 传输架构

### 7.1 架构概览

活漏斗与活箱子之间的物品传输通过 `SlotAccessor` 接口统一抽象，传输引擎不关心槽位背后是普通物品、活箱子还是活末影箱。

```
ItemTransferComponent.tick()
  → executeTransfer()
    → SlotAccessorFactory.create(sourceSlot)  → LivingChestAccessor
    → SlotAccessorFactory.create(targetSlot)  → PlainSlotAccessor
    → SlotAccessor.transfer(source, target, amount)
      → source.simulateExtract() → target.simulateInsert()
      → source.extract() → target.insert()
      → rollback on failure
```

### 7.2 LivingChestAccessor

**职责**: 将活箱子的 `InternalStorageComponent` API 适配为 `SlotAccessor` 接口

| 方法 | 实现 |
|------|------|
| `extract(amount, filterType)` | `LivingChestFunction.extractItem(chestStack, amount)` |
| `insert(stack)` | `LivingChestFunction.insertItem(chestStack, stack, registries)` |
| `simulateExtract(amount)` | 读取第一个非空物品，返回副本 |
| `simulateInsert(stack)` | 估算可用空间（freeSlots × maxStackSize） |
| `rollback(stack)` | `LivingChestFunction.insertItem(chestStack, stack)` |
| `isEmpty()` | `InternalStorageComponent.isStorageEmpty(chestStack)` |
| `isFull()` | `isStorageFull() \|\| isByteFull()` |

### 7.3 模拟优先传输模式

`SlotAccessor.transfer()` 采用"模拟优先"模式，确保物品不丢失：

```
1. source.simulateExtract(amount) → 检查源能提供多少
2. target.simulateInsert(simulated) → 检查目标能接受多少
3. source.extract(toExtract) → 实际提取
4. target.insert(extracted) → 实际插入
5. 如果 insert 失败 → source.rollback(leftover) → 安全退回
```

---

## 8. 性能优化策略

### 8.1 哈希脏检查

`InternalStorageComponent.tick()` 使用 `ItemContainerContents.hashCode()` 检测变化，避免每 tick 序列化：

```
99.9% 的 tick: hashCode 比较 → 纳秒级 → 无变化 → 跳过
0.1% 的 tick:  物品变化 → 序列化计算字节 → 微秒级 → 更新 _bu
```

### 8.2 已用槽位计数

`_us` 字段提供 O(1) 的空/满判断，避免遍历 27 个槽位：

```java
// tick() 中更新
state.setInt(KEY_USED_SLOTS, countUsedSlots(hostStack));

// 外部查询时可直接读取（如果信任缓存）
// 或通过 isStorageEmpty/isStorageFull 实时计算
```

### 8.3 拼音搜索二分查找

`PinyinHelper` 使用二分查找替代 HashMap，在 20924 个汉字的映射中实现 O(log n) 查找：

```
数据结构:
  CHARS: 按Unicode排序的汉字字符串
  PINYINS: 逗号分隔拼音字符串

查找流程:
  输入字符 c → binarySearch(c) → O(log 20924) ≈ 15次比较 → 拼音
```

**选择二分查找的原因**: 零额外对象分配，内存紧凑，缓存友好。

### 8.4 配方书标签页实时过滤

搜索过滤采用实时计算，不使用缓存：

```
玩家背包活箱子数量 ≤ 36
每个活箱子物品数 ≤ 27
总物品数 ≤ 972

过滤开销: 遍历 972 个物品 × isPinyinMatch() ≈ 微秒级
缓存维护成本 > 缓存性能收益 → 不缓存
```

### 8.5 创造模式中键防复制

原版行为天然防止复制：活箱子的含 NBT 物品不在创造物品列表中，中键拾取失败或拾到普通箱子（不带活物品数据）。无需额外代码。

---

## 9. 已知问题与修复记录

### 9.1 ✅ 已修复: 配方书物品无法自动放入合成栏

**问题**: 配方书显示活箱子物品可以合成，但点击后不会把物品自动放到合成栏。

**根因**: `ServerPlaceRecipeMixin` 中 `ENABLED = false`，功能被禁用。

**修复**: 将 `ENABLED` 改为 `true`。

### 9.2 ✅ 已修复: 配方书翻页越界

**问题**: 翻页按钮使用 lambda 捕获 `totalPages` 局部变量，物品数量变化后页数上限仍为旧值。

**修复**: 将 `totalPages` 改为 Mixin 实例字段。

### 9.3 ✅ 已修复: 搜索结果缓存不一致

**问题**: 搜索缓存存在多个失效问题（空列表不命中、存取后未失效等）。

**修复**: 彻底移除缓存机制，改为实时过滤。

### 9.4 ✅ 已修复: 关闭配方书后 Shift+左键仍触发快速存入

**问题**: 关闭配方书后 `LivingChestTabState.isActive()` 仍为 true。

**修复**: 在 Shift+左键拦截逻辑中增加配方书可见性检查。

### 9.5 ✅ 已修复: 铁砧重命名堆叠异常

**问题**: 重命名的活箱子能和未命名的活箱子堆叠。

**根因**: `isSameItemSameComponents` Mixin 无条件返回 true，忽略所有 NBT 差异。

**修复**: 改为"忽略运行时组件差异"策略——收集 `getIgnoredComponentTypes()`，只跳过这些组件的比较，保留名称等差异。

### 9.6 ✅ 功能增强: 拼音搜索

支持全拼/首字母/混合匹配，覆盖 20924 个汉字。

### 9.7 ✅ 功能增强: Shift+左键快速存入

配方书活箱子标签页激活时，Shift+左键点击背包物品可快速存入活箱子。

### 9.8 ✅ 功能增强: 活箱子套娃存放

允许活箱子存放其他活箱子，但禁止将自己存入自己（`chestStack == itemToInsert` 检查）。

### 9.9 ✅ 功能增强: 16KB 字节容量限制

防止活箱子 NBT 过大导致网络包超限。

### 9.10 ✅ 已修复: 多活箱子存入时物品复制 Bug

**问题**: `handleDeposit` 和 `handleDepositFromSlot` 中使用 `toInsert.copy()` 传递给 `insertItem`，导致当多个活箱子存在且第一个活箱子部分接受物品时，后续活箱子仍尝试插入原始数量的物品，造成物品复制。

**根因**:
```
toInsert 有 10 个钻石
Chest1: insertItem(chest1, toInsert.copy()) → 插入 7 个，返回 false
  toInsert 仍有 10 个（因为传了副本）
Chest2: insertItem(chest2, toInsert.copy()) → 插入 10 个，返回 true
  总计插入 17 个，但原始只有 10 个 → 复制 Bug！
```

**修复**: 移除 `.copy()`，让 `insertItem` 直接修改 `toInsert`，后续活箱子只尝试插入剩余物品：
```
toInsert 有 10 个钻石
Chest1: insertItem(chest1, toInsert) → 插入 7 个，toInsert 剩余 3 个
Chest2: insertItem(chest2, toInsert) → 插入 3 个，toInsert 剩余 0 个
  总计插入 10 个 → 正确！
```

### 9.11 ✅ 已优化: isStorageFull/isStorageEmpty 减少对象分配

**优化前**: `isStorageFull` 和 `isStorageEmpty` 各自分配 `NonNullList` 并遍历 27 个槽位。

**优化后**: 统一委托给 `countUsedSlots()`，避免重复分配。`countUsedSlots` 也从调用 `getItems()`（分配 ArrayList）改为直接使用 `contents.copyInto()`。

### 9.12 ✅ 已优化: LivingChestAccessor.simulateInsert 精确估算

**优化前**: `simulateInsert` 使用 `freeSlots × maxStackSize` 估算可用空间，不考虑已有物品的堆叠空间，严重高估容量。

**优化后**: 遍历槽位，精确计算空槽位和同类物品的可用堆叠空间，与 `insertItem` 的实际插入逻辑一致。

---

## 10. 调试指南

### 10.1 常见问题排查

#### 问题1: 物品无法存入活箱子

**排查步骤**:
1. 检查活箱子堆叠数是否 > 1（`chestStack.getCount() > 1` → 拒绝所有操作）
2. 检查字节容量是否已满（`isByteFull()` → 16KB 限制）
3. 检查是否尝试存入自己（`chestStack == itemToInsert`）
4. 检查活箱子是否有 CONTAINER 组件（`hasStorage()`）

#### 问题2: 配方书不显示活箱子物品

**排查步骤**:
1. 检查 `ServerPlaceRecipeMixin.ENABLED` 是否为 true
2. 检查活箱子堆叠数是否 > 1（`RecipeBookComponentMixin.collectLivingChestItems` 跳过 `count > 1` 的活箱子）
3. 检查活箱子是否有 CONTAINER 组件

#### 问题3: 字节用量显示异常

**排查步骤**:
1. 检查 `_ch` 哈希是否正确更新（tick 中的脏检查）
2. 检查 `registries` 是否可用（服务端有，客户端估算）
3. 检查 `estimateByteUsage` 的估算是否合理（物品数 × 64 字节）

### 10.2 手动检查工具

```java
// 检查活箱子状态
boolean isChest = LivingChestFunction.isLivingChest(stack);
boolean hasStorage = LivingChestFunction.hasStorage(stack);
boolean isEmpty = LivingChestFunction.isStorageEmpty(stack);
boolean isFull = LivingChestFunction.isStorageFull(stack);
int byteUsage = LivingChestFunction.getCurrentByteUsage(stack, registries);
int usedSlots = InternalStorageComponent.countUsedSlots(stack);

// 读取物品列表
List<ItemStack> items = LivingChestFunction.getItems(stack);
```

---

## 附录 A: API 速查

### LivingChestFunction (静态方法)

```java
// 判断
boolean isChest = LivingChestFunction.isLivingChest(stack);
boolean hasStorage = LivingChestFunction.hasStorage(stack);
boolean isEmpty = LivingChestFunction.isStorageEmpty(stack);
boolean isFull = LivingChestFunction.isStorageFull(stack);
boolean isByteFull = LivingChestFunction.isByteFull(stack);

// 存取
boolean success = LivingChestFunction.insertItem(chestStack, itemToInsert, registries);
ItemStack result = LivingChestFunction.extractItem(chestStack, amount);
ItemStack result = LivingChestFunction.extractItem(chestStack, target, amount);

// 查询
List<ItemStack> items = LivingChestFunction.getItems(chestStack);
int byteUsage = LivingChestFunction.getCurrentByteUsage(chestStack, registries);
int maxBytes = LivingChestFunction.getMaxStorageBytes();  // 16384

// 操作
LivingChestFunction.clearStorage(chestStack);
LivingChestFunction.dropAllItems(chestStack, player);
```

### InternalStorageComponent (静态方法)

```java
// 存取（核心实现）
boolean success = InternalStorageComponent.insertItem(chestStack, itemToInsert, registries);
ItemStack result = InternalStorageComponent.extractItem(chestStack, amount);
ItemStack result = InternalStorageComponent.extractItem(chestStack, target, amount);

// 查询
List<ItemStack> items = InternalStorageComponent.getItems(chestStack);
int byteUsage = InternalStorageComponent.getCurrentByteUsage(chestStack, registries);
boolean canInsert = InternalStorageComponent.canInsert(chestStack, itemToInsert, registries);

// 操作
void InternalStorageComponent.setItems(chestStack, items);
void InternalStorageComponent.clearStorage(chestStack);
```

---

*文档版本: 2026.07 v7*
*架构: DataComponent 直存（无 UUID、无 WorldStorage、无磁盘文件）*
*更新: 修复多活箱子存入复制 Bug、优化 simulateInsert 精确度、减少对象分配*
*维护者: Living Item Mod Team*