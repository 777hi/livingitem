# GUI 交互系统设计

> **文档版本**: 2026.08 v1
> **最后更新**: 2026-10-10
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [概述](#1-概述)
2. [数据流](#2-数据流)
3. [声明式交互规则](#3-声明式交互规则)
4. [创造模式兼容](#4-创造模式兼容)
5. [新增交互](#5-新增交互)
6. [方向配置与活按钮交互](#6-方向配置与活按钮交互)

---

## 1. 概述

活物品间的 GUI 交互通过声明式规则驱动，而非硬编码物品判断。系统由三部分组成：

- **规则声明**：`InteractionEntry(targetItem, triggerItem, button, actionId)` 由 `InteractionRuleConfig` 从 JSON 加载（内置资源 + 玩家差异）
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

`GuiInteractionHelper.resolveContainerSlot()` 通过 `LivingItemSlotWrapperAccessor` 获取 `target` 字段，统一处理此差异。`GuiInteractionPacket` 携带双索引（`slotIndex` + `containerSlot`），服务端优先通过 `containerSlot` 遍历匹配。

---

## 5. 新增交互

新增交互类型只需两步：

1. 在 `interaction_rules.json` 中新增 `InteractionEntry` 条目（内置 `assets/living_item/` 或玩家 `config/living_item/`）
2. 注册处理器 `InteractionRegistry.registerHandler("actionId", new XxxHandler())`

无需修改任何 Mixin 代码。

### 5.1 ⚠️ 通配条目（trigger=null）的遮蔽坑（2026-09-13 实测踩坑）

`triggerItem = null` 的通配条目语义是「任意光标可触发」。当同一目标物品同时挂
通配条目与精确条目（triggerItem 指定物品）时，**必须依赖 findInteraction 的
两趟优先级匹配**：第一趟只找精确条目，第二趟才找通配条目——保证精确触发永不被
通配截胡。

事故现场：活耕地的 `plant_crop`（通配，任意活种子）与 `bonemeal`（精确，BONE_MEAL）
共用 target=FARMLAND + button=1。旧实现按注册顺序首配，骨粉右键永远命中先注册的
plant_crop，BonemealHandler 成为死代码（游戏实测「活骨粉不能催熟」）。

新增交互时的规则：**光标是特定物品的交互必须注册精确触发器**
（`new InteractionEntry(target, 具体物品, button, actionId)`）；光标是「某一类物品」的
交互必须提供 **triggerFilter 谓词**把拦截面收窄到该类（如种植：
`new InteractionEntry(FARMLAND, null, 1, "plant_crop", false,
CropClassifier::isSeedPlantableOnFarmland)`）；**只有真·任意光标自交互**（按钮按压、
拉杆切换）才留裸通配。不要在通配 handler 里靠 if/else 区分光标——客户端拦截发生在
原版逻辑之前，不命中时的静默返回仍吞掉了原版的拿起/放置/分堆操作
（2026-09-13 实测反馈：活耕地空手右键分堆被拦，加 triggerFilter 后只拦种子）。

回归守卫：`InteractionRegistryTest.preciseEntryBeatsWildcardEvenIfRegisteredLater`
（故意按最坏注册顺序断言精确优先）。

### 5.2 ⚠️ 按下拦截与释放阶段（坑 10，交互通用）

手持物品右键 = 「按下拦截 + `skipNextRelease` 置位」。原版对光标非空的放置/交换
发生在**释放阶段**，按下取消拦不住它；三个 Screen mixin 的按下拦截成功处已统一
置位。新增交互类型无需关心（框架已处理），但新增**其他 Screen 的 mixin 拦截**时
必须复制该置位。详见 [gui-click-interception.md](../guides/gui-click-interception.md) 坑 10。

---

## 6. 方向配置与活按钮交互

> 2026-10-10 落地（设计稿 [gui-interaction-redesign.md](../buffer/gui-interaction-redesign.md)，
> 缘起活酿造台的 5 槽位需求）。本节只写**契约与坑**，玩法取舍见设计稿。

### 6.1 方向配置：九宫格键位

- 键位 = 键盘左上角物理布局：`Q W E / A S D / Z C`（`X` 弃用 —— 与 `S`=下的直觉冲突，用户拍板）。
  一个键对应一个方向（含 4 个对角），**零歧义**。
- **拦截点必须在 `ScreenEvent.KeyPressed.Pre`**，不能只在 `CharacterTyped`：GLFW 按键事件**先于**
  字符事件，等字符阶段 `Q`（丢弃）/ `E`（关界面）已经生效。两者共用同一判据
  `LivingItemInputHandler#shouldCaptureDirectionInput`（含文本框焦点守卫，不影响铁砧重命名）。
- **对角是声明式能力**：`HasDirection#supportsDiagonal` 默认 `false`，需要斜向布局的功能自行 override
  （活酿造台返回 `true`）。未声明者：对角键**既不屏蔽也不认**（`Q`/`E` 保持原版行为），
  HUD 与按钮提示只显示 `W A S D`。
- **HUD 生命周期**：悬停活按钮即显示（预览态）；**鼠标离开按钮即重置**（无超时 —— 取代早期的 3 秒超时）。
- **HUD 渲染必须走 `renderTooltip`**：自绘 `fill` 默认 **z=0**（比物品 z≈150 远）会被深度裁掉
  （表现为「被物品图标挡住」），而 `flush()` 只改绘制顺序、改不了深度 ⇒ 救不了。
  机制详见 [icon-system.md](icon-system.md) 的 z 深度订正。
- 方向配置 HUD 可见时，活按钮自己的 tooltip **让位**（两者同为 tooltip，必然重叠）。
- **方向符号走专用字体**（2026-10-11 实现）—— 解决「对角箭头 `↖↗↙↘` 看着比基本箭头小」：
  原版点阵字体**只含 4 个基本方向箭头**（U+2190–U+2194 `←↑→↓`，在 `nonlatin_european.png`），
  **不含对角箭头**（U+2196–2199，三张点阵图都没有）⇒ 对角符号落到**兜底字体**、风格尺寸都不一致。
  解法：模组自带字体 `living_item:direction`（`assets/living_item/font/direction.json`）——
  provider 1 = **自绘点阵**（`textures/font/direction_arrows.png`，补 4 个对角字形；
  ⚠️ **必须放第一位**，字形查询取**第一个命中**的 provider）；provider 2 = `reference: minecraft:default`
  （原样拿到全部原版字形，含空格宽度）。符号文本经 `DirectionSymbols.literal / of` 挂字体
  （HUD + 熔炉/酿造台方向 tooltip）⇒ **8 个符号风格统一，其它文字完全不受影响**。

  **机制依据（均已读源码确认）**：`Font` 渲染时按 `style.getFont()` 取字体集；
  `Style.applyFormat` 保留 `font` 字段 ⇒ **染色不会覆盖字体**；
  `FontManager` 用 `DependencySorter` 按**依赖顺序**解析 `reference`（`UnresolvedBuilderBundle.dependencies`）
  ⇒ 引用 `minecraft:default` 是安全的。

  🔴 **必踩的坑：`file` 字段必须带 `.png` 后缀**。`file` 是普通 `ResourceLocation.CODEC`
  （**不会自动补后缀**），而 `BitmapProvider.load` 只是 `file.withPrefix("textures/")` 后直接开资源。
  漏后缀（写成 `living_item:font/direction_arrows`）⇒ 贴图找不到 ⇒ provider 加载抛错
  ⇒ **整个字体集退化成 `missingFontSet` ⇒ 所有箭头（连原版有的基本箭头）全变缺字方块**。
  ⇒ 写自定义字体时**照抄原版字段格式**：`"file": "minecraft:font/nonlatin_european.png"`（带后缀）。

  **点阵图规格**：每格 **8×8**、PNG 像素**只有「纯白不透明 / 全透明」两种**
  （颜色由游戏按字色**自动染色**，所以不能画彩色）、推进宽度自动取「最右非透明像素」、`ascent` 同原版箭头为 7。
  字形对齐原版的三个指标：**墨量 ≈13**、**最右列 ≤ 6**（决定推进宽度）、**最下行 ≤ 6**（不压基线）。

### 6.2 活按钮：可拖动改位置

- **持久化**：按 **Screen 类名 + GUI 尺寸**分别存（`LivingButtonLayout`，`config/living_item/button_layout.json`）；
  坐标 = **相对 GUI 左上角的偏移**（`getGuiLeft` / `getGuiTop`）⇒ 界面位置变化时按钮跟随。
  ⚠️ **必须带尺寸**：原版**单箱与大箱是同一个 `ChestScreen` 类**（只差 `ChestMenu` 槽位数）
  ⇒ 只用类名会让两者共享同一份位置，而两者 GUI 高度差 56px（166 / 222）。
  思路参考 TrashSlot，只取骨架（吸附 / 碰撞 / 显示开关三者不做）。
- **手势**：**空手**左键按在按钮上并移动（阈值 2px）⇒ 拖动；屏幕内夹取；松手写盘。
  手持物品点击仍是活化 —— 两者靠「光标是否空手」天然分开。
- ⚠️ **应用位置时必须再夹一次**（`LivingButtonLayout.clampToScreen`）：拖动时的夹取只管
  **当时那一次**；玩家之后**缩小窗口 / 调大 GUI scale** ⇒ 逻辑屏幕变小，而保存的偏移是
  **相对 GUI** 的 ⇒ 换算出的屏幕坐标可能落到屏外（按钮"消失"，2026-10-12 实测反馈）。
  **只夹显示位置、不回写配置** —— 恢复窗口后按钮回到原位。
- ⚠️ **拖动事件必须自己转发**：`AbstractContainerScreen.mouseDragged` **整个重写了**
  `ContainerEventHandler` 的默认转发（焦点 + `isDragging` + 左键 ⇒ 转发焦点控件）**且不调 `super`**、
  恒返回 `true` ⇒ 子控件永远收不到 `mouseDragged`。原版凡要拖动自己控件的容器界面
  （`CreativeModeInventoryScreen` / `LoomScreen` / `BookEditScreen`）**也都各自重写 `mouseDragged`**
  ⇒ **这是既定设计，不是漏写**。
  解法：`LivingItemInputHandler#onMouseDragged` 订阅 `ScreenEvent.MouseDragged.Pre` ——
  该事件在 `MouseHandler` 里、`screen.mouseDragged()` **之前**触发（`ClientHooks.onScreenMouseDragPre`，
  取消即跳过原版），**天然绕开那处重写，且不必新增 mixin**。
  仅当按钮真的处于拖动手势时才取消事件 ⇒ 手持物品的原版快速合成拖动不受影响。
  （对比：`mouseReleased` 能到子控件，是因为 Forge 补丁在 `AbstractContainerScreen.mouseReleased`
  里补了 `super.mouseReleased`。）

### 6.3 ⚠️ 重写 `AbstractWidget` 鼠标事件的两条铁律（2026-10-10 实测踩坑）

1. **命中判定要自己补**：基类只有 `mouseClicked` 做了 `active && visible && clicked(...)` 检查；
   `mouseDragged` / `mouseReleased` **只看按键**。重写 `mouseClicked` 时漏判 ⇒
   空手在界面**任意位置**按下都会启动拖动手势。
2. **不要盲目委托 `super`**：`AbstractWidget.mouseDragged` 对**左键恒返回 `true`**
   （只检查 `isValidClickButton`、不看拖动状态）⇒ 若把它当作「我没处理」的返回值，
   会取消所有左键拖动、**打断原版快速合成**。不在手势中时**明确 `return false`**。

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `InteractionEntry` | 交互规则 record（targetItem + triggerItem + button + actionId） |
| `InteractionRegistry` | 交互注册表（规则查询 + 处理器注册；两趟优先级匹配） |
| `InteractionHandler` | 处理器接口（服务端执行交互逻辑） |
| `IgniteHandler` | 点燃槽位TNT（活打火石→活TNT） |
| `IgniteCarriedHandler` | 点燃光标TNT（活TNT→活打火石） |
| `TillToFarmlandHandler` | 活锄头耕活土→活耕地（物品转换型；锄头 = `canPerformAction(HOE_TILL)`，产物查 `Tillables` 映射） |
| `PlantCropHandler` | 活种子种植（通配条目 + handler 内校验） |
| `BonemealHandler` | 活骨粉催熟（精确触发器条目） |
| `GuiInteractionHelper` | 客户端GUI交互统一工具（查询规则+解析槽位+序列化光标+发包） |
| `GuiInteractionPacket` | 通用GUI交互包（客户端→服务端：slotIndex + containerSlot + actionId + carriedTag） |
| `CarriedUpdatePacket` | 光标更新包（服务端→客户端：绕过创造模式光标同步限制） |
| `LivingItemSlotWrapperAccessor` | SlotWrapper 访问器接口（获取 target 字段；命名铁律见 AGENTS.md「Mixin 铁律」） |