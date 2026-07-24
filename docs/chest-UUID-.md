好的，让我先深入理解当前架构，再分析具体改动方案。
The user wants me to analyze the proposal in their 777.md file. They want to simplify the living chest implementation by:

1. Storing items directly in the chest's NBT (like creative mode ctrl+middle-click copy)
2. Removing the UUID-based virtual storage system
3. Giving up stacking ability for chests with different NBT
4. This eliminates countless bugs related to UUID management, stacking, and transfer

Let me first understand the current architecture thoroughly, then analyze what needs to change.

现在让我看看哪些文件引用了活箱子相关的 mixin 和 UUID 系统：

好了，我已经完整理解了当前架构。下面是详细分析：

---

## 改造分析：活箱子从 UUID 外部存储 → NBT 内嵌存储

### 一、当前架构

```
活箱子 ItemStack
  ├── IS_LIVING = true
  └── LIVING_FUNCTION_DATA
        └── living_chest
              └── internal_storage
                    ├── uuids: ["uuid1", "uuid2", ...]  ← UUID 索引
                    ├── _cc: 3                           ← 缓存堆叠数
                    └── _us: 15                          ← 已用槽位数

磁盘: data/living_chests/00/uuid1.dat  ← 实际物品数据
      data/living_chests/00/uuid2.dat
```

**核心问题**：UUID 是外部引用，堆叠时需要动态管理 UUID 转移 → 大量 Mixin 拦截 → 无尽 bug

### 二、目标架构

```
活箱子 ItemStack (count=1, 不可堆叠)
  ├── IS_LIVING = true
  └── LIVING_FUNCTION_DATA
        └── living_chest
              └── internal_storage
                    ├── items: [物品NBT列表]    ← 直接内嵌 27 槽物品
                    └── _us: 15                 ← 已用槽位数
```

**关键变化**：
- 物品数据直接存在 ItemStack 的 DataComponent 里（NBT 内嵌）
- 不再需要 UUID、磁盘文件、WorldStorage、LRU 缓存
- 不再需要堆叠 → 不再需要 ItemStackMixin/AbstractContainerMenuMixin/BlockItemMixin 的 UUID 转移逻辑
- NBT 不同的箱子自然无法堆叠（原版 `isSameItemSameComponents` 行为）

### 三、受影响的文件清单

| 类别 | 文件 | 改动 |
|------|------|------|
| **核心存储** | `InternalStorageComponent.java` | **重写**：删除 WorldStorage/UUID/磁盘 IO，改为纯 NBT 读写 |
| **功能入口** | `LivingChestFunction.java` | **重写**：所有 API 改为直接读写 NBT 内嵌物品 |
| **活化按钮** | `LivingItemManager.setLiving()` | **简化**：删除 UUID 创建逻辑 |
| **堆叠系统** | `LivingChestStackHandler.java` | **删除**：不再需要 |
| **堆叠标志** | `LivingChestStackFlags.java` | **删除**：不再需要 |
| **事务管理** | `ChestTransaction.java` | **简化**：只需备份/恢复 NBT |
| **Mixin** | `ItemStackMixin.java` | **大幅简化**：删除所有 UUID 转移逻辑 |
| **Mixin** | `AbstractContainerMenuMixin.java` | **删除**：不再需要 ALLOW_STACK |
| **Mixin** | `BlockItemMixin.java` | **重写**：改为从 NBT 直接读取物品填充实体箱子 |
| **Mixin** | `DropperBlockMixin.java` | **保留**：仍需阻止活箱子被发射 |
| **Mixin** | `ServerPlaceRecipeMixin.java` | **检查**：可能需调整 |
| **访问器** | `LivingChestAccessor.java` | **重写**：改为直接读写 NBT |
| **访问器** | `SlotAccessorFactory.java` | **微调**：适配新 API |
| **指令** | `LivingChestCommand.java` | **重写**：删除 recover/orphaned，改为简单状态查询 |
| **网络** | `ServerPacketHandler.java` | **调整**：存取 API 变更 |
| **网络** | `LivingChestAccessPacket.java` | **保留**：协议不变 |
| **网络** | `LivingChestContentsPacket.java` | **调整**：数据来源变更 |
| **客户端** | `LivingChestContentsCache.java` | **保留**：缓存逻辑不变 |
| **客户端** | `AbstractContainerScreenMixin.java` | **检查**：可能需调整 |
| **客户端** | `RecipeBookComponentMixin.java` | **保留**：读取缓存不变 |
| **容器** | `ContainerLivingItemHandler.java` | **微调**：tick 逻辑简化 |
| **容器** | `CrossContainerTransfer.java` | **检查**：通过 SlotAccessor 间接使用 |

### 四、具体实现步骤

#### 步骤 1：重写 InternalStorageComponent

```java
// 新的 NBT 结构（在 living_chest → internal_storage 下）：
// {
//   "items": [                    ← ListTag，27 个 CompoundTag
//     { id:"minecraft:diamond", count:64, ... },  // 槽位 0
//     {},                                           // 槽位 1（空）
//     ...
//   ],
//   "_us": 15                     ← 已用槽位数
// }

public class InternalStorageComponent implements ILivingComponent {
    public static final String ID = "internal_storage";
    public static final String KEY_ITEMS = "items";
    public static final String KEY_USED_SLOTS = "_us";
    public static final int CAPACITY = 27;

    // 删除: KEY_UUIDS, KEY_CACHED_COUNT
    // 删除: WorldStorage 内部类（整个磁盘 IO 系统）
    // 删除: getUuids(), saveUuids(), createAndRegister(), popUuid()
    // 删除: 所有磁盘相关方法

    // 新增: 直接从 ComponentState 读写物品列表
    public static List<ItemStack> getItems(ComponentState state) { ... }
    public static void setItems(ComponentState state, List<ItemStack> items) { ... }
    
    // 保留但简化: insertItem, extractItem, isStorageFull, isStorageEmpty
    // 这些方法直接操作 state 中的 items 列表，不再需要 server 参数
    public static boolean insertItem(ComponentState state, ItemStack itemToInsert) { ... }
    public static ItemStack extractItem(ComponentState state, int amount) { ... }
    
    // tick: 简化为只更新 _us 计数
    @Override
    public void tick(...) { ... }
}
```

#### 步骤 2：重写 LivingChestFunction

```java
// 删除所有 UUID 相关方法:
//   getUuids(), hasStorage(), clearStorage(), dropAllItems()
//   findOrphanedUuids(), collectReferencedUuids(), countItemsOnDisk()
//   createRecoveryChest(), createRecoveryChestBatch()
//   scanContainerForUuids()

// 简化所有 API:
public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert) {
    ComponentState state = getStorageState(chestStack);
    boolean result = InternalStorageComponent.insertItem(state, itemToInsert);
    saveStorageState(chestStack, state);
    return result;
}
// 不再需要 server 参数！

// 新增: 放置时读取物品列表（替代 UUID → 磁盘读取）
public static List<ItemStack> getItems(ItemStack stack) {
    ComponentState state = getStorageState(stack);
    return InternalStorageComponent.getItems(state);
}
```

#### 步骤 3：简化 LivingItemManager.setLiving()

```java
public static void setLiving(ItemStack stack, boolean living) {
    if (living) {
        stack.set(IS_LIVING.value(), true);
    } else {
        clearLivingData(stack);
    }
    // 删除整个 if (isLivingChest) 块 — 不再需要创建 UUID
    // 活箱子首次 tick 时会自动初始化空物品列表
    // ... 熔炉逻辑保留 ...
}
```

#### 步骤 4：重写 BlockItemMixin

```java
// 之前: UUID → 磁盘读取 → 填充实体箱子
// 之后: NBT 直接读取 → 填充实体箱子

@Inject(method = "place", at = @At("RETURN"))
private void onPlaceReturn(BlockPlaceContext context, ...) {
    // 不再需要 BLOCK_PLACING_UUIDS 标志
    // 不再需要 LivingChestStackHandler
    
    ItemStack stack = context.getItemInHand();
    if (!LivingChestFunction.isLivingChest(stack)) return;
    
    // 直接从 NBT 读取物品
    List<ItemStack> items = LivingChestFunction.getItems(stack);
    
    // 填充到实体箱子
    ChestBlockEntity chest = ...;
    for (int i = 0; i < items.size(); i++) {
        chest.setItem(i, items.get(i).copy());
    }
    
    // 清空活箱子的物品数据（生存模式下物品已转移到方块）
    if (!isCreative) {
        LivingChestFunction.clearStorage(stack);
    }
}
```

#### 步骤 5：简化 ItemStackMixin

```java
// 删除:
//   onIsSameItemSameComponents — 不再需要 ALLOW_STACK 跨 UUID 堆叠
//   onSetCount — 不再需要 UUID 转移
//   onGrow — 不再需要 UUID 转移
//   onShrink — 不再需要 UUID 转移
//   onSplit / onCopyWithCount — 不再需要 UUID 拆分
//   mergeIntoTargetUpToCount — 不再需要
//   PENDING_TRANSFER / PRE_SPLIT_UUIDS 等所有 ThreadLocal

// 保留（可能）:
//   如果需要阻止活箱子与普通箱子堆叠，可以保留一个简单的 isSameItemSameComponents 拦截
//   但实际上，活箱子有 IS_LIVING=true + LIVING_FUNCTION_DATA，原版比较已经会阻止堆叠
```

#### 步骤 6：删除文件

- `LivingChestStackHandler.java` — 整个文件删除
- `LivingChestStackFlags.java` — 整个文件删除
- `AbstractContainerMenuMixin.java` — 整个文件删除

#### 步骤 7：重写 LivingChestCommand

```java
// 删除: orphaned, recover, recoverall, list 子命令
// 保留/新增:
//   /livingchest info — 显示当前手持活箱子的物品数量
//   /livingchest clear — 清空活箱子内容
//   /livingchest export — 导出物品列表（调试用）
```

#### 步骤 8：调整网络包

```java
// ServerPacketHandler.handleDeposit/handleWithdraw:
// 不再需要 server 参数调用 LivingChestFunction.insertItem/extractItem
// 改为: LivingChestFunction.insertItem(invStack, toInsert)

// sendLivingChestContents:
// 不再需要 server 参数调用 getMergedStorage
// 改为: LivingChestFunction.getItems(invStack)
```

### 五、风险与注意事项

1. **存档兼容性**：旧存档中的 UUID 数据无法自动迁移。需要提供一个迁移工具或指令，将磁盘上的 UUID 数据读取后转为 NBT 内嵌格式
2. **NBT 大小限制**：27 槽物品的 NBT 数据量约 1-5KB，远低于 Minecraft 的 NBT 大小限制（约 2MB），不会成为问题
3. **网络同步**：`LivingFunctionData` 的 `STREAM_CODEC` 已经支持 CompoundTag 同步，无需额外修改
4. **活漏斗交互**：`LivingChestAccessor` 的 API 签名会变（去掉 server 参数），但 `SlotAccessor` 接口不变，上层代码无需修改
5. **客户端 tooltip**：`_us` 字段仍保留在 NBT 中，客户端可以正常显示已用/可用槽位

### 六、优势总结

| 之前 | 之后 |
|------|------|
| UUID + 磁盘文件 + LRU 缓存 + 异步 IO | 纯 NBT 内嵌 |
| ~700 行 InternalStorageComponent | ~200 行 |
| ~700 行 ItemStackMixin | ~50 行（或删除） |
| 5 个 Mixin 文件 | 1-2 个 |
| 堆叠时 UUID 转移 bug | 不存在 |
| recover/orphaned 指令 | 不需要 |
| 磁盘文件泄漏/孤儿 | 不存在 |
| 放置时 UUID→实体箱子填充 | 直接从 NBT 读取 |
| 可堆叠（但有 bug） | 不可堆叠（但稳定） |

**核心权衡**：放弃堆叠能力，换来整个系统的稳定性和可维护性。活箱子本质上就是一个"便携箱子"，和创造模式 Ctrl+中键复制的行为一致，只是多了一个活物品的 tick 能力（可以被活漏斗传输等）。

---

需要我开始实施这些改动吗？