# Living Chest (活箱子) 技术文档

> **文档版本**: 2026.07 v5  
> **最后更新**: 2026-07-28  
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

```
┌──────────────────────────────────────────────────────────────┐
│                    单层存储架构                                │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ItemStack (DataComponent)                           │   │
│  │                                                      │   │
│  │  • IS_LIVING: true          ← 活物品标记             │   │
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

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingChestFunction` | `function/LivingChestFunction.java` | 活箱子功能入口，实现 `LivingItemFunction` 接口，提供 insert/extract/canInsert 等 API |
| `LivingChestAccessor` | `core/accessor/LivingChestAccessor.java` | SlotAccessor 实现，统一活箱子的 extract/insert 接口 |
| `SlotAccessorFactory` | `core/accessor/SlotAccessorFactory.java` | 工厂类，根据物品类型创建对应的 SlotAccessor |
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

当活箱子取消活化时，`dropAllItems()` 将箱子内所有物品掉落到玩家位置：

```java
public static void dropAllItems(ItemStack chestStack, Player player) {
    List<ItemStack> items = getItems(chestStack);
    Level level = player.level();
    BlockPos dropPos = player.blockPosition();

    for (ItemStack item : items) {
        if (!item.isEmpty()) {
            level.addFreshEntity(new ItemEntity(level, dropPos, item.copy()));
        }
    }
    clearStorage(chestStack);
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

### 5.1 合成材料提取

`ServerPlaceRecipeMixin` 拦截配方书合成操作，支持从活箱子提取合成材料：

```
玩家点击配方书合成
  ├─ 检查玩家背包是否有足够材料
  ├─ 材料不足 → 检查打开的容器中是否有活箱子
  └─ 从活箱子提取材料 → 完成合成
```

### 5.2 活箱子标签页

`RecipeBookComponentMixin` 在配方书 GUI 中添加活箱子标签页：
- 显示活箱子中所有物品的网格排列
- 支持拼音搜索（全拼/首字母/混合匹配）
- 支持翻页浏览
- 点击物品执行存取操作

---

## 6. SlotAccessor 传输架构

### 6.1 LivingChestAccessor

活箱子通过 `LivingChestAccessor` 实现 `SlotAccessor` 接口，统一活箱子的 extract/insert 操作：

```java
// extract → 从活箱子提取物品
LivingChestFunction.extractItem(chestStack, targetType, amount);

// insert → 向活箱子存入物品
LivingChestFunction.insertItem(chestStack, itemToInsert);

// rollback → 插入失败时退回物品
LivingChestFunction.insertItem(chestStack, rolledBack);
```

### 6.2 工厂创建

`SlotAccessorFactory.create()` 在检测到槽位中是活箱子时自动创建 `LivingChestAccessor`：

```java
if (LivingChestFunction.isLivingChest(stack)) {
    return new LivingChestAccessor(chestStack, slotIndex, context);
}
```

---

## 7. 性能优化策略

### 7.1 零 Tick 开销

活箱子在 tick 中不执行任何操作，因为数据已存储在 `CONTAINER` 组件中，无需缓存维护。

### 7.2 懒加载

物品列表只在需要时（insert/extract）从 `CONTAINER` 组件读取，不维护常驻缓存。

### 7.3 字节计算优化

字节容量计算仅在 `canInsert()` 检查时触发，而非每次 tick。

---

## 8. 已知问题与修复记录

### 8.1 堆叠数 > 1 时数据分裂

**问题**：两个堆叠的活箱子可能因 `isSameItemSameComponents` 在不同 tick 中返回不同结果导致数据分裂。

**修复**：活箱子在 `getIgnoredComponentTypes()` 中返回运行时组件类型，确保 `ItemStackMixin` 比较时忽略运行时差异。

### 8.2 配方书翻页时物品不更新

**问题**：配方书标签页在翻页时显示的物品列表不刷新。

**修复**：在 `RecipeBookComponentMixin` 中每次翻页时重新读取活箱子物品列表。

---

## 9. 调试指南

### 9.1 查看存储内容

在游戏中使用 `/data get entity @p` 查看手持活箱子的 NBT 数据，关注 `components."minecraft:container"` 字段。

### 9.2 检查字节用量

Tooltip 中会显示当前字节用量和百分比，如果接近 100% 说明活箱子即将满。

### 9.3 日志输出

在 `LivingItemManager.LOGGER` 中启用 debug 日志可查看活箱子存取操作的详细记录。

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
- [ ] 虚拟存储容量受字节限制（默认 65536 字节）
- [ ] 超出容量时拒绝插入

### 与活漏斗交互

- [ ] 活漏斗 source 指向活箱子时，从虚拟存储提取物品
- [ ] 活漏斗 target 指向活箱子时，向虚拟存储插入物品
- [ ] 活箱子间通过活漏斗传输检查过滤规则
- [ ] 白名单过滤时精确提取目标类型物品

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