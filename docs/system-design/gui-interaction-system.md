# GUI 交互系统设计

> **文档版本**: 2026.08 v1
> **最后更新**: 2026-08-09
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [概述](#1-概述)
2. [数据流](#2-数据流)
3. [声明式交互规则](#3-声明式交互规则)
4. [创造模式兼容](#4-创造模式兼容)
5. [新增交互](#5-新增交互)

---

## 1. 概述

活物品间的 GUI 交互通过声明式规则驱动，而非硬编码物品判断。系统由三部分组成：

- **规则声明**：`InteractionEntry(targetItem, triggerItem, button, actionId)` 在 `LivingFunctionConfig` 中注册
- **统一拦截**：所有 Screen Mixin 调用 `GuiInteractionHelper.tryInteract()`，查询 `InteractionRegistry` 匹配规则
- **服务端处理**：`GuiInteractionPacket` 携带 `actionId`，服务端通过 `InteractionRegistry.getHandler()` 查找处理器

---

## 2. 数据流

```
玩家在容器界面鼠标点击（右键活TNT等）
    ↓
Screen Mixin (mouseClicked / mouseReleased HEAD注入)
    ├─ AbstractContainerScreenMixin  — 通用容器（箱子、潜影盒）
    ├─ InventoryScreenMixin          — 生存模式背包
    └─ CreativeModeInventoryScreenMixin — 创造模式背包
    ↓
GuiInteractionHelper.tryInteract(hoveredSlot, button, menu)
    ├─ 获取光标物品(trigger)和槽位物品(target)
    ├─ InteractionRegistry.findInteraction(trigger, target, button)
    │     遍历所有 InteractionEntry，匹配 triggerItem + targetItem + button
    │     同时验证双方都是活物品
    ├─ resolveContainerSlot() — 解析真实容器索引（兼容 SlotWrapper）
    ├─ resolveCarriedTag() — 创造模式背包下序列化光标物品NBT
    └─ 发送 GuiInteractionPacket(slotIndex, containerSlot, actionId, carriedTag)
        ↓
GuiInteractionPacket.handle() (服务端)
    ├─ 创造模式 + carriedTag非空 → menu.setCarried() 恢复光标物品
    ├─ resolveSlot() 定位目标槽位（slotIndex优先，containerSlot回退）
    ├─ InteractionRegistry.getHandler(actionId) 查找处理器
    ├─ handler.handle(player, targetSlot) 执行交互逻辑
    └─ 创造模式光标被修改 → CarriedUpdatePacket 同步回客户端
        ↓
CarriedUpdatePacket.handle() (客户端)
    └─ 直接更新 menu.setCarried()，绕过原版对 CreativeModeInventoryScreen 的排除
```

---

## 3. 声明式交互规则

```java
// LivingTntFunction.CONFIG 中声明两条交互规则：
new InteractionEntry(Items.TNT, Items.FLINT_AND_STEEL, 1, "ignite")
//  目标=活TNT, 触发器=活打火石, 右键 → actionId="ignite" → IgniteHandler

new InteractionEntry(Items.FLINT_AND_STEEL, Items.TNT, 1, "ignite_carried")
//  目标=活打火石, 触发器=活TNT, 右键 → actionId="ignite_carried" → IgniteCarriedHandler
```

---

## 4. 创造模式兼容

### 4.1 光标物品同步

创造模式使用 `ItemPickerMenu`，光标物品是客户端虚拟的，服务端 `menu.getCarried()` 返回空。此外原版 `ClientboundContainerSetSlotPacket(containerId=-1)` 明确排除了 `CreativeModeInventoryScreen`。

解决方案分两层：
- **客户端→服务端**：`GuiInteractionPacket` 携带 `carriedTag`（光标物品NBT），仅在 `CreativeModeInventoryScreen` 下发送；服务端收到后 `menu.setCarried()` 恢复光标物品
- **服务端→客户端**：`CarriedUpdatePacket` 自定义包，绕过原版排除逻辑，直接更新客户端 `menu.setCarried()`

注意：创造模式打开容器（箱子等）时使用普通容器界面，光标由服务端管理，不需要 `carriedTag`。`resolveCarriedTag()` 仅在 `CreativeModeInventoryScreen` 下返回非空。

### 4.2 SlotWrapper 兼容

创造模式 INVENTORY 标签页中，快捷栏槽位被 `CreativeModeInventoryScreen.SlotWrapper` 包装：
- `hoveredSlot.index` = 客户端显示索引
- `SlotWrapper.target.index` = 服务端实际槽位索引

`GuiInteractionHelper.resolveContainerSlot()` 通过 `SlotWrapperAccessor` 获取 `target` 字段，统一处理此差异。`GuiInteractionPacket` 携带双索引（`slotIndex` + `containerSlot`），服务端优先通过 `containerSlot` 遍历匹配。

---

## 5. 新增交互

新增交互类型只需两步：

1. 在 `LivingFunctionConfig` 中 `addInteraction(new InteractionEntry(...))`
2. 注册处理器 `InteractionRegistry.registerHandler("actionId", new XxxHandler())`

无需修改任何 Mixin 代码。

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `InteractionEntry` | 交互规则 record（targetItem + triggerItem + button + actionId） |
| `InteractionRegistry` | 交互注册表（规则查询 + 处理器注册） |
| `InteractionHandler` | 处理器接口（服务端执行交互逻辑） |
| `IgniteHandler` | 点燃槽位TNT（活打火石→活TNT） |
| `IgniteCarriedHandler` | 点燃光标TNT（活TNT→活打火石） |
| `GuiInteractionHelper` | 客户端GUI交互统一工具（查询规则+解析槽位+序列化光标+发包） |
| `GuiInteractionPacket` | 通用GUI交互包（客户端→服务端：slotIndex + containerSlot + actionId + carriedTag） |
| `CarriedUpdatePacket` | 光标更新包（服务端→客户端：绕过创造模式光标同步限制） |
| `SlotWrapperAccessor` | SlotWrapper 访问器接口（获取 target 字段） |