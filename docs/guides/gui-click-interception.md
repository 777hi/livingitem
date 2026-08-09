# 容器GUI点击拦截问题排查指南

## 问题描述

在容器GUI界面中，鼠标指针持有活打火石右键活TNT时，两者槽位被替换（活TNT被拿到光标上，活打火石进入槽位），而不是触发自定义的点燃逻辑。

---

## 原版Minecraft点击事件流

```
GLFW鼠标事件
  → MouseHandler.mouseButtonCallback()
    → Screen.mouseClicked(mouseX, mouseY, button)
      → AbstractContainerScreen.mouseClicked()
        → slotClicked(slot, slotIndex, button, clickType)
          → 发送 handleInventoryMouseClick 网络包到服务端
            → 服务端执行物品交换逻辑
    → Screen.mouseReleased(mouseX, mouseY, button)    ← 注意：独立触发！
      → AbstractContainerScreen.mouseReleased()
        → slotClicked(...)                              ← 也会触发物品交换！
```

**关键点**：`mouseClicked` 和 `mouseReleased` 是**独立触发**的两个阶段，取消其中一个不会阻止另一个。

---

## 排查过程与踩坑记录

### 坑1：NeoForge事件取消不可靠

**尝试**：使用 `ScreenEvent.MouseButtonPressed.Pre` 事件取消点击

```java
@SubscribeEvent
static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
    event.setCanceled(true);  // 取消事件
    PacketDistributor.sendToServer(new TntIgnitePacket(slotIndex));
}
```

**结果**：日志显示拦截触发了，但物品还是交换了。

**原因**：
1. 事件取消后 `mouseClicked()` 可能仍被调用（事件传递链不够可靠）
2. `mouseReleased()` 是独立触发的，不受 `mouseClicked` 取消的影响

**教训**：不要依赖 NeoForge 事件来拦截容器GUI的点击操作，事件机制在复杂场景下不够可靠。

---

### 坑2：Mixin `@Inject(HEAD, cancellable=true)` 可能不生效

**尝试**：在 `AbstractContainerScreen.mouseClicked()` 头部注入，使用 `cancellable=true` 取消方法执行

```java
@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
private void living_item$interceptMouseClicked(..., CallbackInfoReturnable<Boolean> cir) {
    if (条件满足) {
        cir.setReturnValue(true);
        return;
    }
}
```

**结果**：日志显示 HEAD 拦截触发了，但方法体仍然继续执行。

**原因**：Mixin 对返回值方法的 `cancellable` 处理在某些 JVM/类加载器环境下可能不够可靠。`cir.setReturnValue(true)` 理论上应该跳过方法体，但实际测试发现并非如此。

**教训**：`cancellable=true` 不是万能的，不能假设它一定能阻止方法执行。

---

### 坑3：逻辑bug导致标志位未设置

**尝试**：使用 `skipSlotClick` 标志 + `@Redirect` 拦截 `slotClicked`

```java
// HEAD注入：设置标志
@Inject(method = "mouseClicked", at = @At("HEAD"))
private void onHead(...) {
    if (TNT+打火石) {
        cir.setReturnValue(true);  // ← 直接return了
        return;                     // ← 没有设置 skipSlotClick = true！
    }
    skipSlotClick = true;           // ← 只有其他情况才设置
}

// Redirect：检查标志
@Redirect(method = "mouseClicked", at = @At(value = "INVOKE", target = "slotClicked"))
private void redirectSlotClicked(...) {
    if (skipSlotClick) return;
    // 正常调用
}
```

**结果**：Redirect 完全没生效。

**原因**：TNT+打火石的情况走的是 `cir.setReturnValue(true) + return` 分支，**根本没有设置 `skipSlotClick = true`**。

**教训**：当多个拦截点协作时，确保所有路径都正确设置了标志位。

---

### 坑4：子类覆盖导致super调用链断裂

`InventoryScreen` 重写了 `mouseClicked`，有两个路径**不调用 super**：

```java
// InventoryScreen.mouseClicked()
if (this.recipeBookComponent.mouseClicked(...)) return true;  // 不调super
if (this.widthTooNarrow && this.recipeBookComponent.isVisible()) return false;  // 不调super
return super.mouseClicked(...);  // 只有这条路径调super
```

`CreativeModeInventoryScreen` 也有类似问题。

**结果**：注入在 `AbstractContainerScreen` 上的 Mixin 在某些代码路径下不会被触发。

**解决**：为 `InventoryScreen` 和 `CreativeModeInventoryScreen` 分别添加独立的 Mixin，在它们自己的 `mouseClicked`/`mouseReleased` 头部拦截，不依赖 `super` 调用链。

**教训**：当目标类有子类覆盖时，需要在子类上也做注入，不能只依赖父类的 Mixin。

---

### 坑5：创造模式光标物品是纯客户端虚拟的

| | 生存模式 | 创造模式 |
|---|---|---|
| **光标物品存储** | 服务端 `menu.getCarried()` | **纯客户端本地管理** |
| **服务端能查到吗** | ✅ 能 | ❌ 返回空 |
| **物品来源** | 从背包/容器取出 | 从创造模式物品栏"无限复制" |

创造模式下，从创造物品栏拿起一个物品，这个物品**只存在于客户端内存中**，服务端完全不知道。所以 `menu.getCarried()` 返回空，服务端验证失败。

**解决**：创造模式下跳过服务端光标验证，信任客户端 Mixin 的检查结果。

**教训**：创造模式的物品管理机制和生存模式完全不同，服务端验证逻辑需要分模式处理。

---

### 坑6：创造模式客户端和服务端菜单类不同

```
客户端: CreativeModeInventoryScreen.ItemPickerMenu
服务端: InventoryMenu
```

客户端的 `slot.index = 0` 在 `ItemPickerMenu` 中指向物品栏第一格，但服务端 `InventoryMenu` 的槽位 0 是**合成结果槽**（空的）。

**解决**：发送两个索引值 `TntIgnitePacket(slotIndex, containerSlot)`：
- `slotIndex`（菜单索引）— 客户端和服务端菜单类型相同时直接匹配
- `containerSlot`（容器内部索引）— 菜单类型不同时，遍历服务端菜单找 `getContainerSlot()` 匹配的槽位

```java
// 服务端查找逻辑
Slot targetSlot = null;

// 优先用slotIndex直接查找（适用于菜单类型相同的场景）
if (slotIndex >= 0 && slotIndex < menu.slots.size()) {
    Slot directSlot = menu.getSlot(slotIndex);
    if (是活TNT) targetSlot = directSlot;
}

// 回退到containerSlot遍历查找（适用于创造模式等菜单类型不同的场景）
if (targetSlot == null) {
    for (Slot s : menu.slots) {
        if (s.getContainerSlot() == containerSlot && 是活TNT) {
            targetSlot = s;
            break;
        }
    }
}
```

**教训**：创造模式的菜单系统是独立实现的，不能假设客户端和服务端的槽位索引一一对应。

---

### 坑7：`@Shadow` 对private方法可能无法正确转发

**尝试**：使用 `@Shadow` 访问 `AbstractContainerScreen` 的私有方法 `findSlot`

```java
@Shadow
private Slot findSlot(double mouseX, double mouseY) { throw new AssertionError(); }
```

**结果**：运行时返回 null，条件检查 `hoveredSlot == null` 直接 return，拦截逻辑从未生效。

**解决**：改用 `@Shadow protected Slot hoveredSlot` 字段。这个字段在 `render()` 中每帧更新，比调用 `findSlot` 方法更可靠。

**教训**：`@Shadow` 对 private 方法可能无法正确转发调用，优先使用 `@Shadow` 字段或 `protected` 方法。

---

### 坑8：`@Invoker` 方法必须声明为abstract

**尝试**：在非抽象 Mixin 类中使用 `@Invoker`

```java
@Invoker("findSlot")
private Slot living_item$findSlot(double mouseX, double mouseY) { return null; }
```

**错误**：`@Invoker method living_item$findSlot is not abstract`

**原因**：`@Invoker` 和 `@Accessor` 要求方法声明为 abstract，由 Mixin 框架在编译时生成实现。

**解决**：改用 `@Shadow` 字段代替 `@Invoker` 方法。

---

## 最终解决方案

### 三层拦截架构

```
第一层：mouseClicked HEAD → 检测条件，取消默认行为 + 发送自定义网络包
第二层：mouseReleased HEAD → 取消默认行为（防止松开鼠标时触发交换）
第三层：slotClicked 调用 → @Redirect 拦截，跳过原版槽位交互（双保险）
```

### 三个Mixin类

| Mixin类 | 目标类 | 作用 |
|---|---|---|
| `AbstractContainerScreenMixin` | `AbstractContainerScreen` | 容器GUI（箱子、熔炉等） |
| `InventoryScreenMixin` | `InventoryScreen` | 生存模式背包 |
| `CreativeModeInventoryScreenMixin` | `CreativeModeInventoryScreen` | 创造模式背包 |

### 服务端处理双路径

| 模式 | 槽位查找 | 打火石验证 | 耐久消耗 |
|---|---|---|---|
| 生存模式 | `slotIndex` 直接匹配 | `menu.getCarried()` | ✅ 消耗 |
| 创造模式 | `containerSlot` 回退匹配 | 信任客户端 | ❌ 不消耗 |

---

## 通用经验总结

1. **不要依赖事件取消**：NeoForge 事件在复杂场景下不够可靠，优先使用 Mixin 直接拦截
2. **`cancellable=true` 不是万能的**：需要配合 `@Redirect` 在更深层做双保险
3. **鼠标双阶段都要拦截**：`mouseClicked` + `mouseReleased` 都会触发槽位交互
4. **子类覆盖要单独处理**：`InventoryScreen`/`CreativeModeInventoryScreen` 有不调 `super` 的路径
5. **创造模式光标虚拟化**：服务端无法验证客户端的虚拟光标物品
6. **菜单索引不对应**：创造模式客户端和服务端使用不同的菜单类，需要双索引策略
7. **`@Shadow` 优先于 `@Invoker`**：private 方法的 `@Shadow` 可能不可靠，优先使用 protected 字段
8. **`@Invoker`/`@Accessor` 必须声明 abstract**：非抽象 Mixin 类中不能使用带方法体的 `@Invoker`