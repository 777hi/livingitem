# Living Chest (活箱子) 技术文档 — 完整数据流

## 目录
1. [架构概览](#1-架构概览)
2. [两层存储系统](#2-两层存储系统)
3. [核心数据流](#3-核心数据流)
4. [关键组件详解](#4-关键组件详解)
5. [GUI 交互与网络通信](#5-gui-交互与网络通信)
6. [堆叠管理系统](#6-堆叠管理系统)
7. [配方书集成](#7-配方书集成)
8. [持久化机制](#8-持久化机制)
9. [性能优化策略](#9-性能优化策略)
10. [已知问题与修复记录](#10-已知问题与修复记录)
11. [调试指南](#11-调试指南)

---

## 1. 架构概览

### 1.1 什么是活箱子？

活箱子是一种特殊的活物品（Living Item），它将普通箱子的物品存储功能**虚拟化**到独立的外部文件中。每个活箱子实例可以包含多个"虚拟箱子槽位"，每个槽位对应一个独立的 UUID 和磁盘文件。

### 1.2 数据流总览图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        运行时内存层级                                │
│                                                                     │
│  ┌──────────────┐    ┌─────────────────┐    ┌──────────────────┐  │
│  │ ItemStack    │    │ ComponentState  │    │ WorldStorage     │  │
│  │ (DataComponent)│──→│ (NBT in memory) │──→│ (LRU Cache)      │  │
│  │              │    │                 │    │                  │  │
│  │ • UUID列表   │    │ • uuids: [...]  │    │ UUID → items[]   │  │
│  │ • 缓存计数   │    │ • _cc: int      │    │ dirty: boolean   │  │
│  └──────────────┘    └─────────────────┘    └────────┬─────────┘  │
│                                                     │             │
│                          磁盘持久化层                ▼             │
│                     ┌────────────────────────────────────────┐    │
│                     │ 存档目录/data/living_chests/           │    │
│                     │  ├── 00/                              │    │
│                     │  │   ├── uuid-xxxx.dat               │    │
│                     │  │   └── uuid-yyyy.dat               │    │
│                     │  ├── 01/                              │    │
│                     │  │   └── ...                         │    │
│                     │  └── ff/ (共256个分片)                │    │
│                     └────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

#### 核心存储系统
| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingChestFunction` | `living/LivingChestFunction.java` | 活箱子功能入口，提供 insert/extract API |
| `InternalStorageComponent` | `core/components/InternalStorageComponent.java` | 内部存储组件，管理 UUID 列表和 WorldStorage 交互 |
| `WorldStorage` | `InternalStorageComponent.java` 内部类 | 全局单例，管理磁盘文件 I/O 和 LRU 缓存 |
| `ItemTransferComponent` | `core/components/ItemTransferComponent.java` | 物品传输组件，处理活漏斗↔活箱子的传输 |

#### 基础设施
| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `BaseLivingFunction` | `living/BaseLivingFunction.java` | 基类，负责 tick 编排和状态保存 |
| `ContainerContext` | `living/ContainerContext.java` | 容器上下文接口，抽象容器操作 |
| `ComponentState` | `core/ComponentState.java` | 组件状态包装器，类型安全的 NBT 读写 |

#### GUI 交互与网络通信（第5章）
| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingChestAccessPacket` | `network/LivingChestAccessPacket.java` | 存取请求包（客户端→服务端）: LOAD/DEPOSIT/WITHDRAW |
| `LivingChestContentsPacket` | `network/LivingChestContentsPacket.java` | 内容同步包（服务端→客户端）: 同步活箱子物品列表 |
| `ServerPacketHandler` | `network/ServerPacketHandler.java` | 网络请求处理器，分发并执行存取操作 |

#### 堆叠管理系统（第6章）
| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingChestStackFlags` | `living/LivingChestStackFlags.java` | ThreadLocal 标志，标记允许跨 UUID 堆叠的 GUI 操作上下文 |
| `AbstractContainerMenuMixin` | `mixin/AbstractContainerMenuMixin.java` | Mixin：拦截容器点击，设置/清理堆叠标志 |
| `ItemStackMixin` | `mixin/ItemStackMixin.java` | Mixin：拦截堆叠判定、拆分/合并时的 UUID 分配逻辑 |
| `LivingChestStackHandler` | `living/LivingChestStackHandler.java` | UUID 列表工具类：标准化、合并、拆分、一致性校验 |

#### 配方书集成（第7章）
| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `ServerPlaceRecipeMixin` | `mixin/ServerPlaceRecipeMixin.java` | Mixin：拦截配方书合成，支持从活箱子提取材料 |

---

## 2. 两层存储系统

活箱子的数据存储分为两个层次，理解这两层的交互是掌握整个系统的关键。

### 2.1 第一层：物品 NBT 层（ItemStack DataComponent）

**存储位置**: `ItemStack → LivingFunctionData → living_chest → internal_storage`

**存储内容**:
```json
{
  "internal_storage": {
    "uuids": [
      "550e8400-e29b-41d4-a716-446655440000",
      "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    ],
    "_cc": 2
  }
}
```

**关键字段**:
- **`uuids`** (ListTag): 虚拟箱子的 UUID 列表。每个 UUID 对应一个独立的存储单元。
- **`_cc`** (int): Cached Count，缓存的堆叠数量。用于 tick 时快速判断是否需要调整。

**特点**:
- ✅ 随物品迁移（放入背包、容器、丢出地面都保留）
- ✅ 可序列化到存档（通过 DataComponent 系统）
- ❌ 不存储实际物品数据（只存 UUID 引用）

### 2.2 第二层：磁盘文件层（WorldStorage）

**存储位置**: `<存档>/data/living_chests/<分片>/<uuid>.dat`

**文件格式**: 压缩的 NBT (gzip + CompoundTag)

**文件内容示例**:
```json
{
  "items": [
    {"id":"minecraft:diamond","count":64,"components":{}},
    {"id":"minecraft:iron_ingot","count":32,"components":{}},
    {},  // 空槽位
    // ... 共 capacityPerChest 个元素
  ]
}
```

**特点**:
- ✅ 每个 UUID 对应一个独立文件
- ✅ 支持二级目录分片（256 个子目录）
- ✅ 使用 LRU + 超时双策略缓存
- ⚠️ 需要显式调用 `markDirty()` + `saveAllDirty()` 才会写入磁盘

### 2.3 两层数据同步机制

```
┌─────────────────────────────────────────────────────────────┐
│                    数据同步时序图                             │
│                                                             │
│  时间轴 ─────────────────────────────────────────────────→  │
│                                                             │
│  [操作]         [NBT层]          [WorldStorage]    [磁盘]    │
│                                                             │
│  insertItem()  │ 读取UUID列表     │ getOrCreate()    │        │
│                │ ↓               │ ↓                │        │
│                │ 修改WorldStorage │ markDirty()      │        │
│                │ ↓               │ ↓                │        │
│                │ saveStorageState│ (缓存标记脏)      │        │
│                │ (写回NBT)       │                  │        │
│                                                             │
│  LevelEvent.Save│                │ saveAllDirty()   │ 写入   │
│                │                │ (遍历脏条目)      │ 磁盘   │
│                                                             │
│  ServerStopping│                │ saveAllDirty()   │ 最终   │
│                │                │ (确保所有数据保存)│ 保存   │
└─────────────────────────────────────────────────────────────┘
```

**核心原则**:
1. **NBT 层是"索引"**: 只存储 UUID 列表，不存储实际物品
2. **WorldStorage 是"缓存"**: 内存中的物品数据，需要 markDirty 才会被保存
3. **磁盘是"真相来源"**: 重启后从磁盘加载数据

---

## 3. 核心数据流

### 3.1 初始化流程（首次使用）

当活漏斗第一次往空活箱子存入物品时触发。

**触发条件**: `insertItem()` 发现 `uuids.isEmpty() == true`

```
用户操作: 用活漏斗往空活箱子放入钻石
       │
       ▼
LivingChestFunction.insertItem(server, chestStack, diamondStack, 27)
       │
       ├─ 1. getStorageState(chestStack)
       │     └─ 从 ItemStack 的 DataComponent 读取 ComponentState
       │        └─ 结果: state = { uuids: [], _cc: -1 }
       │
       ├─ 2. InternalStorageComponent.insertItem(server, state, diamond, 27, stackCount)
       │     │
       │     ├─ 检测到 uuids 为空
       │     │
       │     ├─ 创建新的 UUID 列表:
       │     │    for i in 0..stackCount:
       │     │        uuid = WorldStorage.createAndRegister(server, 27)
       │     │        uuids.add(uuid)
       │     │
       │     ├─ saveUuids(state, uuids)  ← 更新 ComponentState
       │     ├─ state.setInt(KEY_CACHED_COUNT, stackCount)
       │     │
       │     ├─ 将物品插入第一个 UUID 的存储:
       │     │    chestSlots = storage.getOrCreate(uuid[0], 27)
       │     │    chestSlots[0] = diamond.copy()
       │     │    diamond.setCount(0)  ← 消耗源物品
       │     │
       │     └─ storage.markDirty(uuid[0])  ← 标记为脏数据
       │
       └─ 3. saveStorageState(chestStack, state)
              └─ 将更新后的 ComponentState 写回 ItemStack 的 DataComponent
```

**结果**:
- NBT 层: `{ uuids: ["xxx"], _cc: 1 }`
- WorldStorage 缓存: `{"xxx": [diamond, empty, ..., empty]}`
- 磁盘: 尚未写入（等待下次 saveAllDirty）

### 3.2 存入物品流程（已有 UUID）

当活漏斗往已有数据的活箱子继续存入物品时。

**触发场景**: `transferToLivingChest()` 被调用

```
用户操作: 活漏斗再次往活箱子放入铁锭
       │
       ▼
ItemTransferComponent.transferToLivingChest(ctx, containerCtx,
                                             sourceSlot, targetSlot,
                                             sourceStack, stackSize, maxTransfer)
       │
       ├─ 1. 获取目标活箱子引用
       │     targetChestStack = containerCtx.getItem(targetSlot)
       │
       ├─ 2. 计算传输数量
       │     transferAmount = min(sourceStack.count, stackSize, maxTransfer)
       │     toInsert = sourceStack.copy()
       │     toInsert.setCount(transferAmount)
       │
       ├─ 3. 调用 LivingChestFunction.insertItem()
       │     LivingChestFunction.insertItem(server, targetChestStack, toInsert, 27)
       │     │
       │     ├─ getStorageState(chestStack)
       │     │    └─ 读取当前 UUID 列表: ["xxx"]
       │     │
       │     ├─ InternalStorageComponent.insertItem()
       │     │    │
       │     │    ├─ 检查 uuids.size() vs hostStackCount
       │     │    │   └─ 如果匹配，跳过调整
       │     │    │
       │     │    ├─ 遍历 UUID 列表，尝试插入物品:
       │     │    │   for each uuid in uuids:
       │     │    │       chestSlots = storage.getOrCreate(uuid, 27)
       │     │    │       for each slot in chestSlots:
       │     │    │           if slot.isEmpty():
       │     │    │               slot = toInsert.copy()
       │     │    │               toInsert.setCount(0)  ← 完全消耗
       │     │    │           else if sameItem(slot, toInsert):
       │     │    │               transfer = min(available, toInsert.count)
       │     │    │               slot.grow(transfer)
       │     │    │               toInsert.shrink(transfer)
       │     │    │
       │     │    └─ storage.markDirty(uuid)  ← 标记修改过的 UUID
       │     │
       │     └─ saveStorageState(chestStack, state)
       │        └─ 将更新后的状态写回 ItemStack
       │
       ├─ 4. 计算实际传输数量
       │     actuallyTransferred = originalCount - toInsert.getCount()
       │
       ├─ 5. 更新源槽位
       │     sourceStack.shrink(actuallyTransferred)
       │     containerCtx.setItem(sourceSlot, sourceStack)
       │
       └─ 6. 标记目标槽位（防止级联传输）
              transferredTargetSlots.add(targetSlot)
```

**关键点**:
- `toInsert` 是副本，修改它不会影响原始 `sourceStack`
- 只有实际被插入的物品才会从源槽位移除
- 如果活箱子已满，`actuallyTransferred == 0`，源物品不变

### 3.3 取出物品流程

当活漏斗从活箱子取出物品时。

**触发场景**: `transferFromLivingChest()` 被调用

```
用户操作: 活漏斗从活箱子取出物品到相邻容器
       │
       ▼
ItemTransferComponent.transferFromLivingChest(ctx, containerCtx,
                                               sourceSlot, targetSlot,
                                               targetStack, stackSize, maxTransfer)
       │
       ├─ 1. 获取源活箱子引用
       │     sourceChestStack = containerCtx.getItem(sourceSlot)
       │
       ├─ 2. 计算提取数量
       │     extractAmount = min(stackSize, maxTransfer)
       │
       ├─ 3. 调用 LivingChestFunction.extractItem()
       │     LivingChestFunction.extractItem(server, sourceChestStack,
       │                                      extractAmount, 27, hostStackCount)
       │     │
       │     ├─ getStorageState(chestStack)
       │     │    └─ 读取 UUID 列表: ["xxx"]
       │     │
       │     ├─ InternalStorageComponent.extractItem()
       │     │    │
       │     │    ├─ 遍历 UUID 列表，按顺序提取:
       │     │    │   for each uuid in uuids:
       │     │    │       chestSlots = storage.getOrCreate(uuid, 27)
       │     │    │       for each slot in chestSlots:
       │     │    │           if slot.isEmpty(): continue
       │     │    │
       │     │    │           if result.isEmpty():
       │     │    │               result = slot.copyWithCount(min(amount, slot.count))
       │     │    │               slot.shrink(extracted)
       │     │    │           else if sameItem(result, slot):
       │     │    │               extracted = min(remaining, spaceAvailable)
       │     │    │               result.grow(extracted)
       │     │    │               slot.shrink(extracted)
       │     │    │
       │     │    │           if slot.isEmpty():
       │     │    │               chestSlots[j] = EMPTY
       │     │    │
       │     │    └─ storage.markDirty(uuid)  ← 标记修改过的 UUID
       │     │
       │     └─ saveStorageState(chestStack, state)  ← ⚠️ 必须调用！
       │
       └─ 4. 将提取的物品放入目标槽位
              if targetStack.isEmpty:
                  containerCtx.setItem(targetSlot, extracted)
              else if canMerge:
                  targetStack.grow(transferAmount)
                  containerCtx.setItem(targetSlot, targetStack)
```

**⚠️ 重要**: `extractItem()` 必须调用 `saveStorageState()`！否则：
- WorldStorage 中的数据已更新（物品被移除）
- 但 NBT 层的状态未保存（如果后续 tick 覆盖了状态，可能导致不一致）

### 3.4 Tick 调整流程

每次服务端 tick，活箱子都会检查是否需要调整虚拟箱子数量。

**触发时机**: `BaseLivingFunction.tick()` → `InternalStorageComponent.tick()`

```
每 tick 执行:
       │
       ▼
InternalStorageComponent.tick(ctx, hostSlot, hostStack, state, config)
       │
       ├─ 1. 快速路径检查
       │     cachedCount = state.getInt("_cc", -1)
       │     expectedCount = hostStack.getCount()
       │     if cachedCount == expectedCount:
       │         return  ← 无需调整
       │
       ├─ 2. 加载 UUID 列表
       │     uuids = getUuids(state)
       │     actualCount = uuids.size()
       │
       ├─ 3. 数量不足时（拆分/增加堆叠）
       │     while actualCount < expectedCount:
       │         newUuid = WorldStorage.createAndRegister(server, capacity)
       │         uuids.add(newUuid)
       │         actualCount++
       │         changed = true
       │
       ├─ 4. 数量过多时（合并/减少堆叠）
       │     removedUuids = uuids.subList(expectedCount, actualCount)
       │     for uuid in removedUuids:
       │         chestItems = storage.getOrCreate(uuid, capacity)
       │         for item in chestItems:
       │             if !item.isEmpty:
       │                 掉落物品到世界 (ItemEntity)
       │         storage.remove(uuid)  ← 删除磁盘文件
       │     uuids = uuids.subList(0, expectedCount)
       │     changed = true
       │
       ├─ 5. 保存变更
       │     if changed:
       │         saveUuids(state, uuids)
       │
       └─ 6. 更新缓存计数
              state.setInt("_cc", expectedCount)
```

**设计意图**:
- `_cc` 字段避免每 tick 都解析 UUID 列表（性能优化）
- 只在堆叠数量变化时才执行实际的增删操作
- 减少堆叠时会掉落物品（防止数据丢失）

### 3.5 活箱子间传输流程

当两个活箱子之间直接传输物品时。

**触发场景**: `transferBetweenLivingChests()` 被调用

```
活箱子A → 活箱子B 传输
       │
       ▼
transferBetweenLivingChests(ctx, containerCtx, sourceSlot, targetSlot,
                              stackSize, maxTransfer)
       │
       ├─ 1. 从源活箱子提取物品
       │     extracted = LivingChestFunction.extractItem(
       │                    server, sourceChestStack, transferAmount, capacity)
       │     if extracted.isEmpty(): return false
       │
       ├─ 2. 尝试插入目标活箱子
       │     originalCount = extracted.getCount()
       │     LivingChestFunction.insertItem(server, targetChestStack, extracted, capacity)
       │     actuallyInserted = originalCount - extracted.getCount()
       │
       ├─ 3. 处理部分插入失败
       │     if actuallyInserted <= 0:
       │         LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacity)
       │         return false  ← 物品退回源箱子
       │
       ├─ 4. 处理部分插入成功
       │     if !extracted.isEmpty:
       │         LivingChestFunction.insertItem(server, sourceChestStack, extracted, capacity)
       │         return true  ← 剩余物品退回源箱子
       │
       └─ 5. 标记目标槽位（防止级联传输）
              transferredTargetSlots.add(targetSlot)
```

**原子性保证**:
- 提取失败时不影响任何一方
- 插入失败时自动回滚（退回源箱子）
- 部分插入时剩余物品退回源箱子

---

## 4. 关键组件详解

### 4.1 WorldStorage（全局存储管理器）

**设计模式**: 单例模式 + 工厂模式

```java
public static class WorldStorage {
    private static final String DIR_NAME = "living_chests";
    private static final long UNLOAD_TIMEOUT_MS = 5 * 60 * 1000;  // 5分钟
    private static final int MAX_CACHE_SIZE = 200;  // 最大缓存200个箱子
    private static final int SHARD_BITS = 8;  // 8位分片 = 256个子目录

    private static WorldStorage instance;  // 全局单例
    private final MinecraftServer server;
    private final Path storageDir;
    private final LinkedHashMap<UUID, CachedStorage> cache;  // LRU缓存
    private final Set<UUID> dirtyKeys;  // 脏数据追踪集合
}
```

**核心方法**:

#### `getOrCreate(UUID uuid, int capacity)`
```
输入: UUID + 槽位数
输出: List<ItemStack> （可修改的引用）

流程:
1. 检查缓存命中 → 直接返回（更新访问时间）
2. 尝试从磁盘加载 → loadFromDisk(uuid)
3. 磁盘不存在 → 创建空槽位 createEmptySlots(capacity)
4. 放入缓存 → putCache(uuid, items, false)
5. 返回 items 引用
```

**⚠️ 注意**: 返回的是内部列表的**直接引用**，修改它会直接影响缓存！

#### `markDirty(UUID uuid)`
```
作用: 标记指定 UUID 的数据已被修改

流程:
1. 在 cache 中查找 uuid
2. 设置 cached.dirty = true
3. 将 uuid 加入 dirtyKeys 集合
4. 日志: 记录当前缓存大小和脏数据数量
```

#### `saveAllDirty()`
```
作用: 将所有脏数据写入磁盘

触发时机:
- LevelEvent.Save (主世界保存时)
- ServerStoppingEvent (服务器停止时)

流程:
1. 检查 dirtyKeys 是否为空 → 是则返回
2. 遍历 dirtyKeys:
   for uuid in dirtyKeys:
       cached = cache.get(uuid)
       if cached && cached.dirty:
           saveToDisk(uuid, cached.items)  ← 写入磁盘
           cached.dirty = false
3. 清空 dirtyKeys
```

#### `saveToDisk(UUID uuid, List<ItemStack> items)`
```
作用: 将物品列表序列化为 NBT 并写入磁盘

文件格式:
{
  "items": [
    <ItemStack NBT>,  // 非空物品
    {},               // 空槽位（空的 CompoundTag）
    ...
  ]
}

原子性保证:
1. 先写入临时文件: xxx.tmp
2. 再原子替换: Files.move(tmp, path, ATOMIC_MOVE)
3. ATOMIC_MOVE 失败时降级为普通移动
```

**🔴 关键 Bug 修复 (2024)**:
```java
// 错误写法（旧版本）:
CompoundTag itemTag = new CompoundTag();
stack.save(provider, itemTag);  // ❌ 返回值被忽略！
itemsList.add(itemTag);  // itemTag 仍然是空的！

// 正确写法（修复后）:
if (!stack.isEmpty()) {
    Tag saved = stack.save(provider, new CompoundTag());  // ✅ 使用返回值
    itemsList.add(saved);
} else {
    itemsList.add(new CompoundTag());
}
```

**原因**: `ItemStack.save()` 内部使用 `Codec.encode()`，而 `NbtOps.mergeToMap()` 会创建浅拷贝，不会修改传入的 tag！

### 4.2 ComponentState（组件状态包装器）

**设计原则**: 类型安全的 NBT 读写接口

```java
public class ComponentState {
    private final CompoundTag data;

    public int getInt(String key, int defaultValue);
    public void setInt(String key, int value);
    public ListTag getList(String key, int type);
    public void putList(String key, ListTag list);
    public CompoundTag toNBT();  // 导出深拷贝
    public static ComponentState fromNBT(CompoundTag tag);  // 从NBT创建
}
```

**在活箱子中的使用**:

| Key | Type | 说明 |
|-----|------|------|
| `uuids` | ListTag (String) | 虚拟箱子的 UUID 列表 |
| `_cc` | int | 缓存的堆叠数量 |

**不可变性注意**:
- `ComponentState` 本身不是不可变的（可修改 data）
- 但 `toNBT()` 返回深拷贝，修改不影响原对象
- `fromNBT()` 创建新实例，不共享引用

### 4.3 LivingChestFunction（功能入口）

**职责**: 提供简洁的 API，隐藏内部复杂性

```java
public class LivingChestFunction extends BaseLivingFunction {

    // ========== 公开 API ==========

    public static boolean insertItem(MinecraftServer server,
                                     ItemStack chestStack,
                                     ItemStack itemToInsert,
                                     int capacityPerChest);

    public static ItemStack extractItem(MinecraftServer server,
                                        ItemStack chestStack,
                                        int amount,
                                        int capacityPerChest);

    public static List<UUID> getUuids(ItemStack stack);

    public static boolean hasStorage(ItemStack stack);

    // ========== 内部方法 ==========

    private static void saveStorageState(ItemStack stack, ComponentState state);
    private static ComponentState getStorageState(ItemStack stack);
}
```

**API 设计模式**:
1. 所有公开方法都是 `static`，无需实例化
2. 自动处理状态加载和保存（getStorageState + saveStorageState）
3. 参数清晰：server、chestStack、操作参数

---

## 5. GUI 交互与网络通信

活箱子支持通过 GUI 界面进行物品存取操作，这需要客户端与服务端之间的网络通信。

### 5.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        GUI 交互系统架构                              │
│                                                                     │
│  客户端 (Client)                                                     │
│  ┌─────────────┐    ┌───────────────────┐    ┌──────────────────┐  │
│  │ 活箱子 GUI   │───▶│ LivingChestAccess │───▶│ LivingChest      │  │
│  │ (用户操作)   │    │ Packet (请求包)    │    │ ContentsPacket   │  │
│  └─────────────┘    └───────────────────┘    │ (响应/同步包)     │  │
│                                                 └────────┬────────┘  │
│                                                          │           │
│  服务端 (Server)                                          ▼           │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                    ServerPacketHandler                         │ │
│  │              (处理客户端请求并返回响应)                          │ │
│  └─────────────────────────────────┬──────────────────────────────┘ │
│                                    │                                │
│                                    ▼                                │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                  LivingChestFunction                            │ │
│  │            (insertItem / extractItem / getMergedStorage)        │ │
│  └─────────────────────────────────┬──────────────────────────────┘ │
│                                    │                                │
│                                    ▼                                │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │               InternalStorageComponent → WorldStorage          │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 核心文件说明

#### 5.2.1 LivingChestAccessPacket（存取请求包）

**文件位置**: `network/LivingChestAccessPacket.java`

**功能**: 客户端向服务端发送活箱子的存取请求

**操作类型**:
| 操作 | 值 | 说明 |
|------|-----|------|
| `LOAD` | 0 | 请求加载活箱子的完整内容列表 |
| `DEPOSIT` | 1 | 将光标上的物品存入活箱子 |
| `WITHDRAW` | 2 | 从活箱子取出指定物品到光标 |
| `WITHDRAW_INVENTORY` | 3 | 从活箱子取出物品到玩家背包 |

**数据结构**:
```java
public record LivingChestAccessPacket(
    int action,           // 操作类型 (LOAD/DEPOSIT/WITHDRAW)
    @Nullable CompoundTag itemTag,  // 物品 NBT（DEPOSIT/WITHDRAW 时使用）
    int amount            // 数量（DEPOSIT/WITHDRAW 时使用）
)
```

**使用流程**:
```
客户端: 用户点击"存入"按钮
       │
       ├─ 1. 获取光标物品的 NBT 和数量
       ├─ 2. 构造 LivingChestAccessPacket(DEPOSIT, itemTag, amount)
       └─ 3. 发送到服务端

服务端: ServerPacketHandler.handleLivingChestAccess()
       │
       ├─ 1. 解析操作类型和参数
       ├─ 2. 根据 action 分发处理:
       │     case DEPOSIT:
       │         LivingChestFunction.insertItem(server, chestStack, itemStack, capacity)
       │     case WITHDRAW:
       │         LivingChestFunction.extractItem(server, chestStack, amount, capacity)
       │     case LOAD:
       │         items = LivingChestFunction.getMergedStorage(server, chestStack, capacity)
       └─ 3. 发送 LivingChestContentsPacket 响应给客户端
```

#### 5.2.2 LivingChestContentsPacket（内容同步包）

**文件位置**: `network/LivingChestContentsPacket.java`

**功能**: 服务端向客户端同步活箱子的物品内容

**触发时机**:
1. 客户端发送 `LOAD` 请求后
2. 客户端执行 `DEPOSIT` 或 `WITHDRAW` 操作后

**数据结构**:
```java
public record LivingChestContentsPacket(
    List<CompoundTag> itemTags  // 活箱子内所有物品的 NBT 列表
)
```

**客户端处理**:
```java
public static void handle(LivingChestContentsPacket packet, IPayloadContext context) {
    context.enqueueWork(() -> {
        List<ItemStack> items = new ArrayList<>();
        for (CompoundTag tag : packet.itemTags) {
            ItemStack stack = ItemStack.parse(registryAccess, tag).orElse(EMPTY);
            if (!stack.isEmpty()) items.add(stack);
        }
        LivingChestContentsCache.set(items);  // 更新本地缓存
    });
}
```

### 5.3 网络通信时序图

```
时间轴 ─────────────────────────────────────────────────────────→

[客户端]                    [服务端]                     [磁盘]

  │                           │                           │
  │  ① 用户打开活箱子 GUI      │                           │
  │──────────────────────────▶│                           │
  │  LivingChestAccessPacket  │                           │
  │  (action=LOAD)            │                           │
  │                           │                           │
  │                           │ ② 加载活箱子内容           │
  │                           │──────────────────────────▶│
  │                           │  WorldStorage.getOrCreate()│
  │                           │◀───────────────────────────│
  │                           │                           │
  │  ③ 返回内容列表            │                           │
  │◀──────────────────────────│                           │
  │  LivingChestContentsPacket│                           │
  │                           │                           │
  │  ④ 用户存入物品            │                           │
  │──────────────────────────▶│                           │
  │  LivingChestAccessPacket  │                           │
  │  (action=DEPOSIT)         │                           │
  │                           │                           │
  │                           │ ⑤ 执行存入操作             │
  │                           │  InternalStorageComponent  │
  │                           │  .insertItem()             │
  │                           │  markDirty()               │
  │                           │                           │
  │  ⑥ 同步更新后的内容        │                           │
  │◀──────────────────────────│                           │
  │  LivingChestContentsPacket│                           │
  │                           │                           │
  │  ⑦ LevelEvent.Save        │                           │
  │                           │──────────────────────────▶│
  │                           │  saveAllDirty()           │
  │                           │  (写入磁盘)                │
```

### 5.4 设计要点

#### 为什么需要自定义网络包？

原版 Minecraft 的容器系统基于槽位索引（slot index），但活箱子的存储是虚拟化的：
- **不在任何容器的实际槽位中**
- **数据存储在外部文件中**
- **需要特殊的序列化/反序列化逻辑**

因此无法复用原版的容器同步机制，必须实现自定义网络通信。

#### 数据一致性保障

1. **服务端权威**: 所有修改操作都在服务端执行
2. **原子性**: 每次操作后立即同步完整状态
3. **乐观更新**: 客户端先更新 UI，收到确认后修正

---

## 6. 堆叠管理系统

活箱子的特殊之处在于：**每个堆叠的物品都对应一个独立的 UUID 和存储单元**。这导致原版的堆叠判定逻辑失效——两个相同类型的活箱子如果 UUID 不同，默认情况下无法堆叠。

堆叠管理系统的目标是：**在玩家手动操作时允许跨 UUID 堆叠，同时保持世界交互的安全性**。

### 6.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                       堆叠管理系统架构                               │
│                                                                     │
│  触发源                                                             │
│  ┌──────────────────────┐                                         │
│  │ AbstractContainerMenu│                                         │
│  │     Mixin            │                                         │
│  └──────────┬───────────┘                                         │
│             │ 拦截 clicked() 方法                                   │
│             │ 设置 ALLOW_STACK = true                              │
│             ▼                                                     │
│  ┌──────────────────────┐                                         │
│  │ LivingChestStackFlags│ ◀── ThreadLocal<Boolean>                 │
│  │  (上下文标志)         │                                         │
│  └──────────┬───────────┘                                         │
│             │ 被 isSameItemSameComponents 检查                     │
│             ▼                                                     │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │                      ItemStack Mixin                         │ │
│  │                                                               │ │
│  │  ┌─────────────────────────────────────────────────────────┐ │ │
│  │  │ isSameItemSameComponents()                              │ │ │
│  │  │ • 检查是否为活箱子                                       │ │ │
│  │  │ • 如果 ALLOW_STACK=true → 返回 true（允许堆叠）          │ │ │
│  │  │ • 否则 → 走原版逻辑（不同 NBT 不堆叠）                   │ │ │
│  │  ├─────────────────────────────────────────────────────────┤ │ │
│  │  │ split(amount)                                           │ │ │
│  │  │ • HEAD: 捕获原始 UUID 列表                              │ │ │
│  │  │ • RETURN: 按比例拆分 UUID（remain + split）              │ │ │
│  │  ├─────────────────────────────────────────────────────────┤ │ │
│  │  │ grow(amount)                                            │ │ │
│  │  │ • 与 shrink 配对完成 UUID 转移                           │ │ │
│  │  │ • 支持两种合并顺序（grow-first / shrink-first）          │ │ │
│  │  ├─────────────────────────────────────────────────────────┤ │ │
│  │  │ shrink(amount)                                          │ │ │
│  │  │ • HEAD 注入：读取旧状态并计算被移除的 UUID               │ │ │
│  │  │ • 立即更新源堆和目标堆的 UUID 列表                       │ │ │
│  │  ├─────────────────────────────────────────────────────────┤ │ │
│  │  │ copyWithCount(count)                                    │ │ │
│  │  │ • 处理右键拖拽分发场景                                   │ │ │
│  │  │ • 按 count 拆分 UUID 列表                               │ │ │
│  │  └─────────────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                          │                                        │
│                          ▼                                        │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │                  LivingChestStackHandler                     │ │
│  │                                                               │ │
│  │  • normalizeUuidList() — 排序标准化                          │ │
│  │  • mergeUuidLists() — 合并去重                               │ │
│  │  • splitUuidList() — 按顺序截取                             │ │
│  │  • getUuids() / setUuids() — 读写 UUID                      │ │
│  └──────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 核心文件说明

#### 6.2.1 LivingChestStackFlags（堆叠标志）

**文件位置**: `living/LivingChestStackFlags.java`

**功能**: ThreadLocal 布尔标志，标记当前线程是否允许活箱子跨 UUID 堆叠

**设计意图**:
```java
/**
 * 仅当玩家通过鼠标/键盘操作物品时，活箱子才允许跨 UUID 堆叠。
 * 掉落物、漏斗等世界交互不受影响，按原版逻辑处理（不同 NBT 无法堆叠）。
 */
public final class LivingChestStackFlags {
    public static final ThreadLocal<Boolean> ALLOW_STACK = new ThreadLocal<>();
}
```

**生命周期**:
```
玩家点击容器槽位
       │
       ▼
AbstractContainerMenuMixin.onClickedHead()
       │
       ├─ LivingChestStackFlags.ALLOW_STACK.set(true)
       │
       ▼
执行 clicked() 方法体（包含堆叠判定）
       │
       ├─ ItemStackMixin.isSameItemSameComponents()
       │    └─ 检查 ALLOW_STACK.get() != null → 允许堆叠
       │
       ▼
AbstractContainerMenuMixin.onClickedReturn()
       │
       └─ LivingChestStackFlags.ALLOW_STACK.remove()  ← 清理！
```

**⚠️ 安全性**:
- 使用 `ThreadLocal` 确保多线程安全
- 必须在 finally 中清理（当前实现在 RETURN 处清理）
- 避免标志泄漏到其他操作

#### 6.2.2 AbstractContainerMenuMixin（容器拦截器）

**文件位置**: `mixin/AbstractContainerMenuMixin.java`

**功能**: Mixin 拦截 `AbstractContainerMenu.clicked()` 方法

**注入点**:
```java
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"))
    private void onClickedHead(...) {
        LivingChestStackFlags.ALLOW_STACK.set(true);
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void onClickedReturn(...) {
        LivingChestStackFlags.ALLOW_STACK.remove();
    }
}
```

**为什么选择 HEAD/RETURN 而不是 AROUND？**
- **HEAD**: 在方法执行前设置标志，确保整个方法体内可用
- **RETURN**: 在方法返回后清理，覆盖所有代码路径（包括异常）
- **避免 AROUND**: 减少对原方法执行的干扰

#### 6.2.3 ItemStackMixin（核心拦截器）

**文件位置**: `mixin/ItemStackMixin.java`

**功能**: 拦截 ItemStack 的关键方法，维护 UUID 一致性

**四大职责**:

##### 职责 1: 堆叠判定 (`isSameItemSameComponents`)
```java
@Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
private static void onIsSameItemSameComponents(ItemStack stack, ItemStack other, 
                                                CallbackInfoReturnable<Boolean> cir) {
    // 1. 非活箱子 → 走原版逻辑
    if (!isLivingChest(stack) || !isLivingChest(other)) return;
    
    // 2. 不同物品类型 → 不可堆叠
    if (stack.getItem() != other.getItem()) {
        cir.setReturnValue(false);
        return;
    }
    
    // 3. 玩家 GUI 操作 → 忽略 UUID 差异，允许堆叠
    if (LivingChestStackFlags.ALLOW_STACK.get() != null) {
        cir.setReturnValue(true);
    }
    // 4. 其他情况 → 走原版逻辑（检查所有 DataComponent）
}
```

**效果对比**:
| 场景 | ALLOW_STACK | 结果 |
|------|-------------|------|
| 玩家 Shift+左键整理背包 | `true` | ✅ 不同 UUID 的活箱子可以堆叠 |
| 活箱子掉落物自动堆叠 | `null` | ❌ 不同 UUID 无法堆叠（安全） |
| 漏斗输出活箱子到另一个活箱子 | `null` | ❌ 无法堆叠（走原版逻辑） |

##### 职责 2: 拆分 UUID 分配 (`split`)
```
场景: 玩家从 64 个活箱子堆中拿起 16 个

原始状态:
  ItemStack(count=64, uuids=[A,B,C,D,...,X])  ← 64个UUID

split(16) 后:
  原堆: ItemStack(count=48, uuids=[A,B,C,D,...,L])  ← 前48个UUID
  新堆: ItemStack(count=16, uuids=[M,N,...,X])       ← 后16个UUID
```

**实现细节**:
```java
// HEAD: 捕获原始 UUID 列表（防止 onShrink 修改）
private static final ThreadLocal<List<UUID>> PRE_SPLIT_UUIDS = new ThreadLocal<>();

@Inject(method = "split", at = @At("HEAD"))
private void onSplitHead(int amount, CallbackInfoReturnable<ItemStack> cir) {
    List<UUID> uuids = LivingChestStackHandler.getUuids(self);
    PRE_SPLIT_UUIDS.set(new ArrayList<>(uuids));  // 快照
}

// RETURN: 执行实际的拆分逻辑
@Inject(method = "split", at = @At("RETURN"))
private void onSplitReturn(int amount, CallbackInfoReturnable<ItemStack> cir) {
    List<UUID> originalUuids = PRE_SPLIT_UUIDS.get();  // 使用快照
    
    SplitResult result = LivingChestStackHandler.splitUuidList(originalUuids, newCount);
    
    LivingChestStackHandler.setUuids(original, result.remain());   // 原堆保留前N个
    LivingChestStackHandler.setUuids(newStack, result.split());    // 新堆获得后M个
}
```

**⚠️ 为什么要在 HEAD 捕获快照？**
- `split()` 内部会调用 `shrink()`，而 `shrink()` 会触发 `onShrink`
- `onShrink` 会修改 UUID 列表
- 如果不捕获快照，`onSplitReturn` 会拿到被修改过的数据，导致拆分错误

##### 职责 3: 合并 UUID 转移 (`grow` / `shrink`)

**问题场景**: 两个活箱子堆合并时的 UUID 转移

**场景 A: 左键合并（先 grow 后 shrink）**
```
玩家将活箱子A拖到活箱子B上（左键）

1. B.grow(A.count)  ← 目标堆增长
2. A.shrink(A.count) ← 源堆缩减

UUID 流转:
  B.uuids += A.uuids的前N个  (在 grow 中完成)
  A.uuids = A.uuids的后M个    (在 shrink 中完成)
```

**场景 B: 右键/漏斗合并（先 shrink 后 grow）**
```
玩家右键活箱子A放到活箱子B上

1. A.shrink(halfCount)  ← 源堆先缩减
2. B.grow(halfCount)    ← 目标堆后增长

UUID 流转:
  pending = A.uuids的被移除部分  (在 shrink 中暂存)
  B.uuids += pending             (在 grow 中消费)
```

**协调机制 - MergeTransfer 记录**:
```java
private record MergeTransfer(ItemStack target, int amount, List<UUID> uuids) {}

private static final ThreadLocal<MergeTransfer> PENDING_TRANSFER = new ThreadLocal<>();

// grow() 的处理逻辑
@Inject(method = "grow", at = @At("HEAD"))
private void onGrow(int amount, CallbackInfo ci) {
    MergeTransfer pending = PENDING_TRANSFER.get();
    
    if (pending != null && pending.uuids() != null) {
        // 场景B: shrink已执行，消费暂存的UUID
        mergeUuids(self, pending.uuids());
        PENDING_TRANSFER.remove();
    } else {
        // 场景A: grow先执行，记录待转移信息
        PENDING_TRANSFER.set(new MergeTransfer(self, amount, null));
    }
}

// shrink() 的处理逻辑
@Inject(method = "shrink", at = @At("HEAD"))
private void onShrink(int amount, CallbackInfo ci) {
    List<UUID> removedUuids = calculateRemovedUuids(self, amount);
    
    MergeTransfer pending = PENDING_TRANSFER.get();
    
    if (pending != null && pending.uuids() == null) {
        // 场景A: grow已执行，直接转移到目标
        transferUuidsToTarget(pending.target(), removedUuids);
        PENDING_TRANSFER.remove();
    } else {
        // 场景B: shrink先执行，暂存被移除的UUID
        PENDING_TRANSFER.set(new MergeTransfer(null, amount, removedUuids));
    }
    
    updateSourceUuids(self, remainingUuids);
}
```

##### 职责 4: 右键拖拽分发 (`copyWithCount`)

**场景**: 玩家按住右键从活箱子堆中分发物品到其他槽位

**为什么需要单独处理？**
- 右键拖拽使用 `copyWithCount()` 而非 `split()`
- 因此 `onSplitHead/onSplitReturn` 不会触发
- 需要单独拦截以正确分配 UUID

```java
@Inject(method = "copyWithCount", at = @At("RETURN"))
private void onCopyWithCount(int count, CallbackInfoReturnable<ItemStack> cir) {
    // 类似 split 的逻辑：按 count 拆分 UUID
    SplitResult result = LivingChestStackHandler.splitUuidList(originalUuids, count);
    LivingChestStackHandler.setUuids(original, result.remain());
    LivingChestStackHandler.setUuids(copy, result.split());
}
```

#### 6.2.4 LivingChestStackHandler（UUID 工具类）

**文件位置**: `living/LivingChestStackHandler.java`

**功能**: 提供 UUID 列表的标准化操作

**核心方法**:

| 方法 | 功能 | 使用场景 |
|------|------|----------|
| `normalizeUuidList(uuids)` | 按自然顺序排序 | 保证集合相等则列表完全相同 |
| `mergeUuidLists(a, b)` | 合并去重 + 标准化 | 多个堆合并时 |
| `splitUuidList(source, n)` | 按顺序截取 | 拆分堆叠时 |
| `getUuids(stack)` | 从 ItemStack 读取 UUID | 所有需要 UUID 的地方 |
| `setUuids(stack, uuids)` | 写入 UUID 到 ItemStack | 修改 UUID 后保存 |
| `isConsistent(stack)` | 校验数量一致性 | 调试和数据修复 |

**设计原则**:
- **不可变输入**: 所有方法接收副本或创建新列表
- **标准化输出**: 所有返回值都是排序后的不可变列表
- **原子操作**: `setUuids()` 是完整的读-改-写事务

### 6.3 完整操作流程示例

#### 示例 1: Shift+左键整理背包

```
初始状态:
  槽位0: 活箱子×32 (uuids=[A,B,...,AF])
  槽位5: 活箱子×16 (uuids=[G,H,...,P])
  槽位9: 活箱子×8  (uuids=[Q,R,...,X])

玩家按 Shift+左键
       │
       ▼
AbstractContainerMenuMixin.onClickedHead()
       │
       └─ ALLOW_STACK = true
       
遍历所有槽位，尝试堆叠:
       │
       ├─ 槽位0 vs 槽位5: 
       │    isSameItemSameComponents(槽0, 槽5)
       │    └─ ALLOW_STACK!=null → true → 可以堆叠!
       │    槽5.grow(16) → 槽5.uuids += [G,H,...,P]
       │    槽0.shrink(16) → 槽0.uuids = [Q,R,...,X] (剩余)
       │
       ├─ 槽位0(剩余16) vs 槽位9:
       │    isSameItemSameComponents(槽0, 槽9)
       │    └─ ALLOW_STACK!=null → true → 可以堆叠!
       │    槽9.grow(8) → 槽9.uuids += [Q,R,...,X]的前8个
       │    槽0.shrink(8) → 槽0.uuids = [U,V,W,X] (最后4个)
       │
       ▼
最终状态:
  槽位0: 活箱子×4  (uuids=[U,V,W,X])
  槽位5: 活箱子×32 (uuids=[A,B,...,AF] + [G,H,...,P])
  槽位9: 活箱子×16 (uuids=[原9的8个] + [Q,R,...,T])

AbstractContainerMenuMixin.onClickedReturn()
       │
       └─ ALLOW_STACK.remove()  ← 清理
```

#### 示例 2: 活箱子掉落物堆叠

```
场景: 两个不同 UUID 的活箱子掉落物相遇

掉落物A: 活箱子×10 (uuids=[1,2,...,A])
掉落物B: 活箱子×20 (uuids=[B,C,...,U])

原版逻辑调用: isSameItemSameComponents(A, B)
       │
       ├─ ItemStackMixin 检测到两者都是活箱子
       ├─ 检查 ALLOW_STACK.get() → null（不是GUI操作）
       └─ 返回 cir 未设置 → 走原版逻辑
          └─ 比较 DataComponent（包括 UUID 列表）
             └─ [1,2,...,A] != [B,C,...,U] → false → 不堆叠!

结果: 两个掉落物保持分离 ✅（符合预期，保证安全性）
```

### 6.4 安全策略总结

| 交互方式 | ALLOW_STACK | 堆叠行为 | 安全性 |
|---------|-------------|----------|--------|
| 玩家鼠标/键盘操作 | `true` | ✅ 允许跨UUID堆叠 | 安全（用户可控） |
| 掉落物自动合并 | `null` | ❌ 不堆叠 | 安全（世界物理规则） |
| 漏斗输出/输入 | `null` | ❌ 不堆叠 | 安全（红石系统隔离） |
| 命令生成 | `null` | ❌ 不堆叠 | 安全（管理员操作） |

---

## 7. 配方书集成

活箱子可以作为合成材料的来源参与配方书的自动合成功能。这使得玩家可以通过配方书一键合成，材料会自动从活箱子中提取。

### 7.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                     配方书集成系统架构                               │
│                                                                     │
│  玩家操作                                                           │
│  ┌──────────────────────┐                                         │
│  │ 点击配方书的"合成"按钮 │                                         │
│  └──────────┬───────────┘                                         │
│             │                                                   │
│             ▼                                                   │
│  ┌──────────────────────┐                                         │
│  │ ServerPlaceRecipe    │                                         │
│  │     Mixin            │                                         │
│  └──────────┬───────────┘                                         │
│             │                                                   │
│             ├──────────────────────────────────┐                  │
│             ▼                                  ▼                  │
│  ┌────────────────────┐            ┌────────────────────┐        │
│  │ recipeClicked()    │            │ moveItemToGrid()   │        │
│  │ 拦截点             │            │ 拦截点             │        │
│  └────────┬───────────┘            └────────┬───────────┘        │
│           │                                 │                    │
│           ▼                                 ▼                    │
│  ┌──────────────────┐          ┌────────────────────┐           │
│  │ addLivingChest   │          │ extractFromLiving  │           │
│  │ ItemsToStacked   │          │ Chests            │           │
│  │ Contents()       │          │ ()                │           │
│  └────────┬─────────┘          └────────┬───────────┘           │
│           │                             │                       │
│           ▼                             ▼                       │
│  ┌────────────────────────────────────────────────┐            │
│  │              LivingChestFunction               │            │
│  │  getMergedStorage() / extractItem()            │            │
│  └────────────────────────────────────────────────┘            │
│                           │                                     │
│                           ▼                                     │
│  ┌────────────────────────────────────────────────┐            │
│  │         StackedContents (原版配方系统)          │            │
│  │    accountStack() — 注册可用物品               │            │
│  └────────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 核心文件说明

#### ServerPlaceRecipeMixin（配方书 Mixin）

**文件位置**: `mixin/ServerPlaceRecipeMixin.java`

**功能**: 拦截配方书的材料计算和物品放置逻辑

**两大拦截点**:

##### 拦截点 1: `recipeClicked()` — 材料注册

**目的**: 将活箱子内的物品注册到配方系统的材料计数器中

**原版流程**:
```
recipeClicked()
  → inventory.fillStackedContents(stackedContents)
    → 遍历玩家背包，调用 stackedContents.accountStack(item)
```

**Mixin 增强**:
```java
@Inject(method = "recipeClicked", at = @At(...))
private void afterFillStackedContents(ServerPlayer player, ...) {
    addLivingChestItemsToStackedContents(player);
}

private void addLivingChestItemsToStackedContents(ServerPlayer player) {
    for (ItemStack invStack : this.inventory.items) {
        // 只处理有存储的活箱子
        if (!LivingChestFunction.isLivingChest(invStack)) continue;
        if (!LivingChestFunction.hasStorage(invStack)) continue;
        
        // 合并所有虚拟箱子的物品
        var merged = LivingChestFunction.getMergedStorage(server, invStack, 27);
        
        // 注册到 StackedContents（让配方系统知道这些物品可用）
        for (ItemStack chestItem : merged) {
            if (!chestItem.isEmpty()) {
                this.stackedContents.accountStack(chestItem);
            }
        }
    }
}
```

**效果**:
```
玩家背包:
  槽位0: 活箱子×3 (内部存储: 64钻石 + 32铁锭 + 16金锭)

配方书查询"钻石剑"合成:
  原版只看到背包里的显式物品 → ❌ 材料不足
  
  Mixin增强后:
  StackedContents 包含:
    - 64个钻石 (来自活箱子)
    - 32个铁锭 (来自活箱子)
    - 16个金锭 (来自活箱子)
  → ✅ 显示可合成!
```

##### 拦截点 2: `moveItemToGrid()` — 物品提取与放置

**目的**: 当配方书需要材料时，从活箱子中提取物品放入工作台

**原版流程**:
```
moveItemToGrid(slot, stack, maxAmount)
  → 在背包中查找匹配的物品
  → 移动到工作台槽位
  → 返回实际移动数量
```

**Mixin 增强**:
```java
@Inject(method = "moveItemToGrid", at = @At("HEAD"), cancellable = true)
private void onMoveItemToGrid(Slot slot, ItemStack stack, int maxAmount, ...) {
    // 先尝试从普通背包获取
    int slotIndex = this.inventory.findSlotMatchingUnusedItem(stack);
    if (slotIndex != -1) {
        return;  // 背包里有，走原版逻辑
    }
    
    // 背包里没有，尝试从活箱子提取
    ItemStack extracted = extractFromLivingChests(stack, maxAmount);
    if (extracted.isEmpty()) {
        cir.setReturnValue(-1);  // 无法提取，通知配方系统
        return;
    }
    
    // 放置到工作台
    if (slot.getItem().isEmpty()) {
        slot.set(extracted);
    } else {
        slot.getItem().grow(extracted.getCount());
    }
    
    cir.setReturnValue(maxAmount - extracted.getCount());  // 返回提取数量
}

private ItemStack extractFromLivingChests(ItemStack requested, int maxAmount) {
    // 遍历背包中的活箱子
    for (ItemStack invStack : this.inventory.items) {
        if (!LivingChestFunction.isLivingChest(invStack)) continue;
        
        // 尝试提取指定物品
        ItemStack extracted = LivingChestFunction.extractItem(
            server, invStack, requested, maxAmount, 27);
        
        if (!extracted.isEmpty()) {
            return extracted;  // 成功提取
        }
    }
    return ItemStack.EMPTY;  // 所有活箱子都没有该物品
}
```

**优先级逻辑**:
```
1. 先检查普通背包 (findSlotMatchingUnusedItem)
   └─ 有 → 走原版逻辑（消耗普通物品）
   
2. 普通背包没有 → 检查活箱子
   ├─ 有 → 从活箱子提取（消耗活箱子存储）
   └─ 没有 → 返回 -1（材料不足）
```

### 7.3 完整操作流程

**场景**: 玩家通过配方书合成 10 个铁镐

```
前置条件:
  玩家背包:
    槽位0: 活箱子×2 (内部: 64木棍 + 63铁锭)
    其他槽位: 空

步骤1: 玩家打开配方书，浏览铁镐配方
       │
       ▼
步骤2: 玩家点击"合成"按钮（shift+左键）
       │
       ▼
步骤3: ServerPlaceRecipe.recipeClicked() 被调用
       │
       ├─ 3.1 原版: inventory.fillStackedContents(stackedContents)
       │    └─ 遍历背包，发现只有活箱子（无显式物品）
       │       └─ StackedContents 为空
       │
       ├─ 3.2 Mixin: addLivingChestItemsToStackedContents(player)
       │    ├─ 发现槽位0是活箱子且有存储
       │    ├─ getMergedStorage() → [木棍×64, 铁锭×63]
       │    ├─ stackedContents.accountStack(木棍×64)
       │    └─ stackedContents.accountStack(铁锭×63)
       │
       └─ 3.3 配方系统判断: 可合成 10 个铁镐（需30木棍+30铁锭）
       
步骤4: 循环调用 moveItemToGrid() 放置材料（共10次）
       │
       ├─ 第1次: 放置3木棍+2铁锭
       │    ├─ findSlotMatchingUnusedItem(木棍) → -1（背包没有）
       │    ├─ extractFromLivingChests(木棍, 3)
       │    │    └─ LivingChestFunction.extractItem(活箱子0, 木棍, 3)
       │    │       └─ 从内部存储移除3木棍 → 返回 木棍×3
       │    └─ slot.set(木棍×3) ✓
       │
       ├─ ... (重复类似逻辑)
       │
       └─ 第10次: 放置最后一批材料
              └─ 活箱子0内部剩余: 木棍×34 + 铁锭×33
              
步骤5: 工作台显示 10 个铁镐成品
       │
       ▼
步骤6: 玩家拿走成品（或 shift+左键全部拿走）
       
最终结果:
  工作台: 10个铁镐
  活箱子0: 内部存储 [木棍×34, 铁锭×43]  (已被消耗26木棍+20铁锭)
```

### 7.4 设计考量

#### 为什么需要 Mixin 拦截？

原版配方系统的限制：
1. **只扫描显式物品**: `fillStackedContents()` 只遍历 `inventory.items`
2. **不支持虚拟存储**: 不知道活箱子内部有物品
3. **无法提取外部数据**: `moveItemToGrid()` 只能移动已有 ItemStack

Mixin 解决方案：
1. **扩展材料来源**: 在 `fillStackedContents()` 后追加活箱子内容
2. **智能回退**: 优先使用背包物品，不足时才从活箱子提取
3. **透明集成**: 对配方系统完全透明，无需修改原版代码

#### 性能影响

| 操作 | 额外开销 | 影响 |
|------|---------|------|
| 打开配方书 | 遍历背包+读取活箱子存储 | 低（仅 GUI 操作） |
| 点击合成 | 可能多次提取物品 | 中（取决于合成数量） |
| 非配方操作 | 无 | 无 |

**优化措施**:
- 只在 `recipeClicked()` 时才扫描活箱子（不是每次 tick）
- 提取物品时短路返回（找到即停止）
- 缓存 `getMergedStorage()` 结果（如果频繁调用）

---

## 8. 持久化机制

### 8.1 触发时机

| 事件 | 触发条件 | 处理逻辑 |
|------|---------|---------|
| `LevelEvent.Save` | 主世界保存时 | `saveAllDirty()` + `cleanupIdle()` |
| `ServerStoppingEvent` | 服务器停止时 | `saveAllDirty()` |
| `cleanupIdle()` | 每20次 putCache 或手动调用 | 卸载超时数据并保存 |

### 5.2 持久化流程图

```
服务器运行中
     │
     ├─ 每tick: 活漏斗/活箱子 tick
     │    └─ 修改 WorldStorage 缓存
     │    └─ markDirty(uuid)
     │
     ├─ 定期自动保存 (Minecraft 默认 ~45秒一次)
     │    └─ LevelEvent.Save 触发
     │    └─ saveAllDirty()
     │       └─ 遍历 dirtyKeys
     │          └─ saveToDisk(uuid, items)
     │             └─ 写入临时文件 .tmp
     │             └─ 原子替换为 .dat
     │
     ├─ 玩家点击"保存并退出"
     │    └─ ServerStoppingEvent 触发
     │    └─ saveAllDirty()  ← 最终保存
     │
     └─ 服务器崩溃/强制关闭
          └─ ⚠️ 未保存的脏数据丢失！
             (这是正常行为，类似原版存档机制)
```

### 5.3 文件结构

```
<存档>/
├── level.dat
├── data/
│   ├── living_chests/           ← 活箱子根目录
│   │   ├── 00/                  ← 分片目录 (UUID最低8位 = 0x00)
│   │   │   ├── 550e8400-e29b-41d4-a716-446655440000.dat
│   │   │   └── ...
│   │   ├── 01/
│   │   │   └── ...
│   │   ├── ...                  ← 共256个分片 (0x00 - 0xFF)
│   │   └── ff/
│   │       └── ...
│   └── ...
├── region/
└── ...
```

**分片算法**:
```java
private int shardIndex(UUID uuid) {
    return (int) (uuid.getLeastSignificantBits() & 0xFF);  // 取最低8位
}
```

**为什么需要分片?**
- 单目录下文件数超过 1000 会显著降低文件系统性能
- Windows FAT32/exFAT 单目录上限 65534 个文件
- 分片后每个目录平均只存放 1/256 的文件

### 5.4 数据迁移（Legacy → Sharded）

首次启动时，自动检测并迁移旧格式文件：

```
旧路径: data/living_chests/<uuid>.dat
新路径: data/living_chests/<xx>/<uuid>.dat  (xx = 分片索引)

迁移逻辑:
1. 扫描根目录下所有 .dat 文件
2. 解析文件名为 UUID
3. 移动到对应的分片目录
4. 只执行一次（migrated 标志位）
```

---

## 9. 性能优化策略

### 9.1 LRU 缓存 + 超时淘汰

```
┌─────────────────────────────────────────┐
│            LRU Cache (max=200)          │
│                                         │
│  [最近访问] ← ← ← ← ← ← [最久未访问]   │
│                                         │
│  淘汰条件 (满足任一):                   │
│  1. 超过 5 分钟未被访问                  │
│  2. 缓存数 > 200 且有新数据加入          │
│                                         │
│  淘汰前动作:                            │
│  if dirty → saveToDisk()  ← 保存脏数据  │
│  cache.remove(uuid)                     │
│  dirtyKeys.remove(uuid)                 │
└─────────────────────────────────────────┘
```

**为什么选择 LRU?**
- 局部性原理：最近使用的箱子很可能再次被访问
- 活漏斗通常持续向同一个活箱子传输
- 避免频繁的磁盘 I/O

### 9.2 精确脏标记

```
传统方案 (低效):
  saveAll() → 遍历所有缓存条目 → 检查dirty → 保存
  问题: 即使只有1个脏数据，也要遍历全部

优化方案 (高效):
  dirtyKeys = Set<UUID>  ← 独立追踪脏数据
  saveAllDirty() → 只遍历 dirtyKeys
  时间复杂度: O(dirtyCount) 而非 O(cacheSize)
```

### 9.3 二级目录分片

```
问题: 10000个活箱子 → 10000个文件在同一目录
解决: 256个子目录 → 平均每个目录39个文件

性能提升:
- 文件创建/删除: O(1) vs O(N)
- 目录扫描: O(N/256) vs O(N)
- 文件系统友好: 避免单目录爆炸
```

### 6.4 原子写入

```
传统写入 (风险):
  1. 打开文件
  2. 写入数据
  3. 关闭文件
  ⚠️ 步骤2-3之间崩溃 → 文件损坏

原子写入 (安全):
  1. 写入临时文件 xxx.tmp
  2. 原子替换: rename(tmp, dat)
  ✅ 要么完全写入，要么完全不写入
  ✅ 即使崩溃也不会损坏文件
```

### 6.5 快速路径优化

```java
// tick() 中的快速路径
int cachedCount = state.getInt(KEY_CACHED_COUNT, -1);
if (cachedCount == expectedCount) {
    return;  // 跳过所有逻辑
}
```

**效果**: 
- 正常情况下（堆叠数不变），tick 开销 ≈ 0
- 只有在玩家合并/拆分活箱子时才执行完整逻辑

---

## 10. 已知问题与修复记录

### 10.1 🔴 严重 Bug: ItemStack.save() 返回值被忽略

**影响版本**: 初版 ~ 2024年修复前

**问题描述**:
推出存档重进后，活箱子内物品丢失，但 UUID 不变。

**根本原因**:
```java
// 错误代码
CompoundTag itemTag = new CompoundTag();
if (!stack.isEmpty()) {
    stack.save(provider, itemTag);  // 返回值被忽略！
}
itemsList.add(itemTag);  // 添加的是空标签！
```

`ItemStack.save()` 在 NeoForge 1.21.1 中返回编码后的 Tag，传入的参数不会被修改（因为使用了 shallowCopy）。

**修复方案**:
```java
// 正确代码
if (!stack.isEmpty()) {
    Tag saved = stack.save(provider, new CompoundTag());  // 使用返回值
    itemsList.add(saved);
} else {
    itemsList.add(new CompoundTag());
}
```

**影响范围**: 所有通过 `saveToDisk()` 保存的数据都会丢失物品内容。

### 10.2 🟡 中等 Bug: extractItem() 未保存状态

**影响版本**: 初版 ~ 2024年修复前

**问题描述**:
从活箱子取物品后，如果后续 tick 覆盖了状态，可能导致 UUID 列表不一致。

**修复方案**:
```java
public static ItemStack extractItem(...) {
    ComponentState state = getStorageState(chestStack);
    ItemStack result = InternalStorageComponent.extractItem(...);
    saveStorageState(chestStack, state);  // ← 添加此行
    return result;
}
```

### 7.3 🟢 小改进: 异常静默吞掉

**问题描述**:
`saveToDisk()` 和 `loadFromDisk()` 中的 IOException 被 `catch (IOException ignored)` 吞掉，导致无法排查问题。

**修复方案**:
```java
catch (IOException e) {
    LOGGER.error("Failed to save/load living chest data for UUID={}", uuid, e);
    // 可以选择抛出或返回默认值
}
```

### 7.4 🟢 功能增强: syncMergedToStorage() 未保存状态

**问题描述**:
GUI 操作同步后未保存状态，可能导致 GUI 显示与实际数据不一致。

**修复方案**: 同 `extractItem()`，添加 `saveStorageState()` 调用。

---

## 11. 调试指南

### 11.1 启用调试日志

在 `InternalStorageComponent.java` 中已添加的关键日志：

```java
// 初始化日志
LOGGER.info("Living chest storage initialized at {}", storageDir);

// 操作日志
LOGGER.info("insertItem: uuids={}, item={}, capacity={}", ...);
LOGGER.info("extractItem: uuids={}, amount={}, capacity={}", ...);

// 脏标记日志
LOGGER.debug("Marked dirty: UUID={}, cacheSize={}, dirtyKeys={}", ...);

// 持久化日志
LOGGER.info("Saving {} dirty living chest entries", dirtyKeys.size());
LOGGER.debug("Saved living chest data for UUID={}, items={}", ...);

// 加载日志
LOGGER.debug("Loaded storage from disk for UUID={}, slots={}", ...);
LOGGER.debug("Creating new empty storage for UUID={}, capacity={}", ...);
```

**日志级别配置**:
- `INFO`: 关键操作（初始化、保存、insert/extract）
- `DEBUG`: 详细信息（脏标记、缓存命中、文件I/O）
- `ERROR`: 异常情况（IO失败）

### 11.2 常见问题排查

#### 问题1: 推出重进后物品丢失

**排查步骤**:
1. 检查日志是否有 `"Saving X dirty living chest entries"`
2. 检查磁盘文件是否存在: `data/living_chests/<xx>/<uuid>.dat`
3. 检查文件大小是否正常（应该 > 0 bytes）
4. 检查文件内容是否有效（可以用 NBT Explorer 查看）

**可能原因**:
- `saveToDisk()` 中的 bug（见 7.1）
- 服务器异常关闭（未触发 saveAllDirty）
- 文件权限问题

#### 问题2: 活箱子无法存入物品

**排查步骤**:
1. 检查 `insertItem` 日志是否输出
2. 检查 `markDirty` 是否被调用
3. 检查 `capacityPerChest` 是否正确（应该是容器大小）
4. 检查 `hostStackCount` 是否正确（应该是活箱子堆叠数）

**可能原因**:
- UUID 列表为空且创建失败
- 容器大小获取错误（返回0或负数）
- `WorldStorage` 未正确初始化

#### 问题3: 性能问题（大量活箱子卡顿）

**排查步骤**:
1. 检查缓存命中率（`getOrCreate` 日志）
2. 检查 `cleanupIdle` 是否频繁执行
3. 检查 `saveAllDirty` 耗时
4. 检查磁盘 I/O 延迟

**优化建议**:
- 增加 `MAX_CACHE_SIZE`（如果内存充足）
- 减少 `UNLOAD_TIMEOUT_MS`（释放更多内存）
- 使用 SSD 存储（减少 IO 延迟）

### 11.3 手动检查工具

#### 查看活箱子 UUID 列表

```java
// 在游戏中执行（命令或调试模式）
List<UUID> uuids = LivingChestFunction.getUuids(chestStack);
System.out.println("UUID count: " + uuids.size());
for (UUID uuid : uuids) {
    System.out.println("  " + uuid);
}
```

#### 检查磁盘文件

```bash
# Windows PowerShell
cd <存档>\data\living_chests
Get-ChildItem -Recurse -Filter "*.dat" | Measure-Object | Select-Object Count

# Linux/Mac
find <存档>/data/living_chests -name "*.dat" | wc -l
```

#### 手动触发保存

```java
// 在调试代码中强制保存
InternalStorageComponent.WorldStorage storage =
    InternalStorageComponent.WorldStorage.get(server);
storage.saveAllDirty();  // 保存所有脏数据
storage.cleanupIdle();   // 清理空闲缓存
```

---

## 附录 A: 数据流速查表

| 操作 | NBT层 | WorldStorage | 磁盘 | 备注 |
|------|-------|-------------|------|------|
| 首次insert | 创建UUID列表 | 创建+填充 | 待保存 | markDirty |
| 后续insert | 可能调整UUID | 修改物品 | 待保存 | markDirty |
| extract | 不变 | 修改物品 | 待保存 | **必须saveStorageState** |
| tick调整 | 更新UUID+_cc | 可能增删 | 待保存 | 减少时掉落物品 |
| saveAllDirty | 不变 | 清除dirty标记 | **写入磁盘** | 原子写入 |
| cleanupIdle | 不变 | 移除缓存条目 | 可能写入 | 仅脏数据写入 |
| 服务器重启 | 从物品加载 | 重建缓存 | 从磁盘加载 | 完整恢复 |

## 附录 B: API 速查

### LivingChestFunction (静态方法)

```java
// 存入物品
boolean success = LivingChestFunction.insertItem(
    server,           // MinecraftServer
    chestStack,       // 活箱子物品栈
    itemToInsert,     // 要存入的物品（会被修改）
    capacityPerChest  // 每个虚拟箱子的槽数
);

// 取出物品
ItemStack result = LivingChestFunction.extractItem(
    server,           // MinecraftServer
    chestStack,       // 活箱子物品栈
    amount,           // 最大取出数量
    capacityPerChest  // 每个虚拟箱子的槽数
);

// 按类型取出
ItemStack result = LivingChestFunction.extractItem(
    server, chestStack,
    targetItem,  // 目标物品类型（过滤用）
    amount, capacityPerChest
);

// 查询
List<UUID> uuids = LivingChestFunction.getUuids(chestStack);
boolean hasStorage = LivingChestFunction.hasStorage(chestStack);
boolean isChest = LivingChestFunction.isLivingChest(stack);
```

### InternalStorageComponent (静态方法)

```java
// UUID 管理
List<UUID> uuids = InternalStorageComponent.getUuids(state);
InternalStorageComponent.saveUuids(state, uuids);

// 批量操作
List<ItemStack> merged = InternalStorageComponent.getMergedStorage(
    server, state, capacityPerChest
);
InternalStorageComponent.syncMergedToStorage(
    server, state, merged, capacityPerChest
);

// 创建新UUID
UUID newUuid = InternalStorageComponent.createAndRegisterNewUuid(
    server, capacity
);

// 弹出最后一个UUID
UUID popped = InternalStorageComponent.popUuid(server, state);
```

### WorldStorage (实例方法)

```java
// 获取实例
WorldStorage storage = WorldStorage.get(server);

// 缓存操作
List<ItemStack> items = storage.getOrCreate(uuid, capacity);
storage.contains(uuid);
storage.remove(uuid);

// 脏标记
storage.markDirty(uuid);
storage.saveAllDirty();

// 维护
storage.cleanupIdle();
```

---

*文档版本: 2024.12*
*最后更新: 修复 ItemStack.save() 返回值 bug*
*维护者: Living Item Mod Team*