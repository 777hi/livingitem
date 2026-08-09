# 创造模式背包栏物品同步问题

## 概述

活漏斗在创造模式背包栏（INVENTORY 标签页）中，用光标/数字键移走输出槽位物品后，活漏斗会停止传输并不断重试。切换到生存模式后，用光标往输出槽位点击会凭空拿取一组物品。

## 受影响版本

1.21.1 NeoForge

## 现象

1. **活漏斗不停尝试传输**：创造模式下，光标拿走活漏斗输出槽位物品后，活漏斗的 tooltip 显示 tick 一直在循环，说明活漏斗在反复尝试传输。
2. **切换生存模式凭空拿取物品**：切换到生存模式后，用光标往活漏斗输出槽位点击，会凭空拿取一组物品，然后后续传输正常。
3. **容器界面和生存模式背包栏正常**：只有单独打开创造背包栏时会出现问题。

## 根因

### 创造模式 INVENTORY 标签页的特殊性

创造模式背包界面 `CreativeModeInventoryScreen` 有两个标签页：

1. **主标签页（CREATIVE）**：显示所有创造模式物品，快捷栏槽位是原生的 `Slot`
2. **INVENTORY 标签页**：显示玩家背包，快捷栏槽位被 `SlotWrapper` 包装

INVENTORY 标签页中，`slotClicked` 方法的通用点击分支（PICKUP/SWAP/QUICK_MOVE）存在一个关键缺陷：

```java
// CreativeModeInventoryScreen.slotClicked() - INVENTORY 标签页的通用分支
} else {
    this.minecraft.player.inventoryMenu.clicked(
        slot == null ? slotId : ((SlotWrapper)slot).target.index,
        mouseButton,
        type,
        this.minecraft.player
    );
    this.minecraft.player.inventoryMenu.broadcastChanges();
    // ⚠️ 缺少 handleCreativeModeItemAdd() 调用！
}
```

这个分支**只修改了客户端本地状态**（`inventoryMenu.clicked()` + `broadcastChanges()`），**没有调用 `handleCreativeModeItemAdd()` 将变更同步到服务端**。

作为对比，同一个方法中的 THROW 分支正确调用了同步：

```java
// THROW 分支 - 正确同步
} else if (type == ClickType.THROW && slot != null && slot.hasItem()) {
    ItemStack itemstack = slot.remove(mouseButton == 0 ? 1 : slot.getItem().getMaxStackSize());
    ItemStack itemstack1 = slot.getItem();
    this.minecraft.player.drop(itemstack, true);
    this.minecraft.gameMode.handleCreativeModeItemDrop(itemstack);
    this.minecraft.gameMode.handleCreativeModeItemAdd(itemstack1, ((SlotWrapper)slot).target.index);
    // ✅ 正确同步
}
```

### 数据流分析

```
创造模式 INVENTORY 标签页点击流程：

客户端（本地）：
  slotClicked() → inventoryMenu.clicked() → 修改本地 InventoryMenu 状态
  slotClicked() → inventoryMenu.broadcastChanges() → 刷新本地 UI
  ❌ 没有发送 ServerboundSetCreativeModeSlotPacket

服务端：
  ❌ 不知道槽位被修改
  ❌ 活漏斗认为输出槽位仍有物品 → 阻塞 → 不停重试 tick

切换生存模式时：
  服务端重新同步 Inventory 到客户端 → 物品状态恢复（因为服务端从未修改）
  → 客户端凭空出现物品
```

### 另一个因素：SlotWrapper 索引映射

INVENTORY 标签页使用 `SlotWrapper` 包装原版 `Slot`，其索引映射关系为：

- `slot.index`：屏幕布局索引（如 38）
- `((SlotWrapper)slot).target.index`：真实容器槽位索引（如 2）

服务端 `handleCreativeModeItemAdd` 需要真实容器索引，而 `inventoryMenu.clicked()` 使用 `target.index`。如果直接用 `slot.index` 会定位到错误的槽位。

## 修复方案

### 修复 1：CreativeModeInventoryScreenMixin（客户端）

**文件**：`src/main/java/com/qiqi/li/client/mixin/CreativeModeInventoryScreenMixin.java`

在 `slotClicked` 方法返回后注入，对 INVENTORY 标签页的通用点击补发 `handleCreativeModeItemAdd`：

```java
@Inject(method = "slotClicked", at = @At("RETURN"))
private void living_item$syncCreativeInventorySlot(Slot slot, int slotId, int mouseButton, ClickType type, CallbackInfo ci) {
    if (slot == null) return;
    if (type == ClickType.THROW) return;  // 原版已处理
    if (!(slot instanceof SlotWrapperAccessor)) return;  // 非 INVENTORY 标签页

    SlotWrapperAccessor wrapper = (SlotWrapperAccessor) slot;
    int index = wrapper.getTarget().index;  // 真实容器索引
    ItemStack item = slot.getItem();
    this.minecraft.gameMode.handleCreativeModeItemAdd(item, index);
}
```

**关键设计决策**：

- 使用 `@At("RETURN")` 而非 `@At.INVOKE(target=...)`：后者在无 refmap 的 dev 环境中解析失败（"No refMap loaded"）
- 通过 `slot instanceof SlotWrapperAccessor` 判断是否在 INVENTORY 标签页，避免对 `@Shadow` 的依赖
- 排除 `THROW` 类型，因为原版代码已正确处理

### 修复 2：ServerGamePacketListenerImplMixin（服务端）

**文件**：`src/main/java/com/qiqi/li/living/mixin/ServerGamePacketListenerImplMixin.java`

在服务端处理 `ServerboundSetCreativeModeSlotPacket` 后，同步被修改的活物品槽位：

```java
@Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
private void onHandleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
    if (player == null) return;

    Inventory inv = player.getInventory();
    int slotNum = packet.slotNum();
    ItemStack stack = inv.getItem(slotNum);

    if (LivingItemManager.isLivingItem(stack)) {
        SimpleContainerContext ctx = new SimpleContainerContext(inv);
        ctx.syncSlotToClients(slotNum, stack);
    }
}
```

**为什么需要这个修复**：

即使修复 1 确保了 `ServerboundSetCreativeModeSlotPacket` 被发送，活物品的 DataComponent 状态（如 tooltip、tick 计数）仍可能不同步。此修复确保每次创造模式槽位操作后，活物品的 DataComponent 能正确同步到客户端。

## 修复后的数据流

```
创造模式 INVENTORY 标签页点击流程（修复后）：

客户端：
  slotClicked() → inventoryMenu.clicked() → 修改本地状态
  slotClicked() → inventoryMenu.broadcastChanges() → 刷新本地 UI
  [新增] slotClicked() RETURN → handleCreativeModeItemAdd(item, index)
  → 发送 ServerboundSetCreativeModeSlotPacket

服务端：
  handleSetCreativeModeSlot() → 修改服务端 Inventory
  [新增] handleSetCreativeModeSlot() RETURN → syncSlotToClients(slotNum, stack)
  → 发送 ClientboundContainerSetSlotPacket(containerId=-2, slotNum, stack)
  → 活漏斗看到输出槽已空 → 继续传输
```

## 测试验证

| 场景 | 预期行为 |
|------|----------|
| 创造模式光标拿走活漏斗输出槽物品 | 活漏斗正常继续传输 |
| 创造模式数字键交换活漏斗输出槽物品 | 活漏斗正常继续传输 |
| 创造模式传输完成后切换到生存模式 | 无凭空物品出现 |
| 容器界面（箱子/漏斗GUI） | 不受影响，正常传输 |
| 生存模式背包栏 | 不受影响，正常传输 |

## 相关文件

| 文件 | 作用 |
|------|------|
| `CreativeModeInventoryScreenMixin.java` | 客户端：补发同步包 |
| `ServerGamePacketListenerImplMixin.java` | 服务端：同步活物品 DataComponent |
| `SlotWrapperAccessor.java` | 访问器：获取 SlotWrapper 的 target 字段 |
| `ItemTransferComponent.java` | 传输逻辑：每次传输后同步源/目标槽位 |
| `SimpleContainerContext.java` | 容器上下文：syncSlotToClients / syncInventoryMenuSlot |

## 技术要点

1. **`containerId=-2`**：`ClientboundContainerSetSlotPacket` 中 `containerId=-2` 表示直接写入玩家 Inventory，不经过任何容器菜单。这是将活物品 DataComponent 同步到客户端的最可靠方式。

2. **`SlotWrapper` 索引映射**：`SlotWrapper.target.index` 才是服务端 `handleCreativeModeItemAdd` 需要的真实容器索引，不能用 `slot.index`（屏幕布局索引）。

3. **Mixin 注入点选择**：`@At.INVOKE(target=...)` 依赖 refmap 映射，在 dev 环境可能失败。`@At("RETURN")` 是更可靠的选择，配合运行时类型检查过滤不需要的调用。

4. **级联防护**：`ItemTransferComponent` 中的 `transferredTargetSlots` 集合防止同 tick 内物品级联传输，与创造模式同步修复无关但共同保障传输稳定性。