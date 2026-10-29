# GUI 交互系统设计

> **文档版本**: 2026.08 v1
> **最后更新**: 2026-10-12
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x

## 目录

1. [概述](#1-概述)
2. [数据流](#2-数据流)
3. [声明式交互规则](#3-声明式交互规则)
4. [创造模式兼容](#4-创造模式兼容)
5. [新增交互](#5-新增交互)
6. [方向配置与活按钮交互](#6-方向配置与活按钮交互)
7. [槽位悬停提示（客户端反馈）](#7-槽位悬停提示客户端反馈)

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
- **输入主通道 = `ScreenEvent.KeyPressed.Pre`（键码 → 方向字符），字符事件只是兜底**。
  `CharacterTyped` 只由 GLFW 的**字符回调**驱动，触屏 / 软键盘的屏幕按键只产生**按键回调**
  ⇒ 只挂字符事件时，这类设备上方向配置完全无响应（2026-10-29 真机反馈）。
  按键阶段同时解决屏蔽时序：GLFW 按键事件**先于**字符事件，等字符阶段 `Q`（丢弃）/ `E`（关界面）已经生效。
  两条通道共用同一判据 `LivingItemInputHandler#shouldCaptureDirectionInput`（含文本框焦点守卫，
  不影响铁砧重命名），且**同一次按键只消费一次**（`CHAR_DEDUPE_MS` 时间窗）——
  字符通道额外归一化全角字母（输入法提交的 `ｗ` / `Ｗ`）。
- **按住不放的自动重复只屏蔽、不重复计入会话**：同一键码两次有效按下间隔 < 250ms 视为重复
  （`KEY_REPEAT_MS`；不依赖按键释放事件 —— 部分触屏启动器只合成按下）。
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

### 6.2.1 生存背包：小人框底居中 + 与射线微调的优先级（2026-10-13）

- **默认位 = 原版小人渲染矩形 (26,8)-(75,78) 的框底居中 (42,70)**。矩形是
  `InventoryScreen.renderBg` 的写死实参，常量提取到 `LivingButtonLayout.INVENTORY_PREVIEW_*`
  （射线微调调用点同源，勿重复写魔法数）；已核对 `InventoryMenu` 全部槽位坐标，
  框内无槽位（盔甲列 x8-24、副手 (77,62)、合成格 x98+ 全在框外）。
- ⚠️ **与射线微调同矩形 → 按钮优先**：框内点击本属 `ToolRayTuningClicks`
  （左 = 锚点分区 / 右 = 朝向跟随），且其 HEAD 拦截**先于控件分发** ——
  不避让则门禁命中（空手 + 主手记忆活工具）时按钮永远点不到。
  修法：`InventoryScreenMixin` 微调判定前加「光标在 LivingButton 上则跳过」；
  微调只损失框底一条（按钮占 y70-78），「躯干底部」锚点在按钮上方照常可点。
- ⚠️ **init 时序坑**：`InventoryScreen.init` 在 `super.init()`（initButtons 挂载点，
  `AbstractContainerScreen.init` TAIL）**之后**才应用配方书 leftPos 偏移
  （`updateScreenPosition`）⇒ 书展开时打开背包，initButtons 拿到偏移前的 leftPos，
  按钮先错位 ≈77px、下个 containerTick 才自愈（闪一帧）。
  修法：`InventoryScreen.init` TAIL 重摆注入（同一套 resolve 偏移；resize 走同一
  init，同款时序一并覆盖）。
- **配方书三形态**（均已核原版源码）：书关 = GUI 居中，正常 ✓；
  书展开 + 窗口 ≥379 = GUI 右移与书**并排**，按钮跟随 leftPos（containerTick 链路
  靠 `InventoryScreenMixin.callContainerTick` 补的 `super.containerTick()` 已通）✓；
  书展开 + 窄窗口 <379 = **原版书独占模式**（`super.render` 与 `super.mouseClicked`
  都不跑）⇒ 按钮（连同整个 GUI）不可见不可点 —— **与原版槽位行为一致，非缺陷**。
- **创造界面维持排除（原因补考证）**：37bde65（2026-07-14）起排除但无记录。真实原因 =
  **活化链路不通** —— `LivingTagPacket` 服务端读 `containerMenu.getCarried()`，而创造的
  carried 是客户端虚拟物品（服务端从不打开创造界面，拾取只走
  `ServerboundSetCreativeModeSlotPacket` 写槽位）⇒ 服务端视角恒空，点按钮必然静默无效。
  修复方向：**carriedTag 往返**（复用 `GuiInteractionPacket` 先例，服务端权威 + 结果回包）
  或**客户端活化**（判定/钩子跑客户端，创造语境可接受但口径脏）—— 待定稿单独开工；
  另有标签页维度问题（`selectedTab` 是 static，`selectTab` 切页不重跑 init、不改
  leftPos ⇒ 现有 leftPos 变化监听不触发，需按 `isInventoryOpen()` 同步按钮显隐）。
  创造的小人框矩形也不同：(73,6)-(105,49)（32×43，scale 20，仅 INVENTORY 页）。

### 6.2.2 活按钮：当前容器基本信息（2026-10-26）

**口径**：按钮能活化光标物品，不代表这个界面是运行宿主。普通悬停显示服务端当前上下文
的容器形态、槽数、列数与向上取整行数；启发式必须明确警示「推测」，高级提示补来源与
可取得的类型 ID。玩家背包含装备等槽，所以使用「上下文槽位」，不称作全部可用存储格。

- **状态分离**：服务端已解析上下文 ⇒ 展示信息；已知工作台/铁砧/交易等临时工具菜单 ⇒
  明确不作为运行容器；未知第三方菜单或信息异常 ⇒ 暂不可用。后者不能当不兼容。
  提示只说明现状，不改变活化能力、宿主准入或 tick 行为。
- **来源同源**：生产上下文委托共同 `ContainerLayoutResolver` 同时产出列数与来源，
  内建固定值也在共同链内；默认未声明来源的外部实现标 UNKNOWN，客户端不再猜来源。
  方块优先级不变，实体/物品/末影箱保留已有答案。
- **只读当前菜单**：请求仅含菜单 ID 与请求编号，服务端核对玩家实际菜单，复用
  `ContainerContexts.resolve`，排除外部菜单的玩家背包槽。空箱也可查询，不依赖活漏斗
  遥测、容器里是否有活物品或框架是否已 tick。
- **查询时机**：按钮首次渲染请求一次，悬停时最多每秒刷新，服务端每玩家最多每 10 tick
  接受一次。玩家按 E 不触发服务端 Open 事件，客户端查询覆盖这一路。限频表退出时
  删除玩家、停止服务端时清空。
- **回包生命周期**：信息只存在当前按钮实例，菜单 ID 与最新请求编号必须同时匹配；编号
  跨实例递增，原版复用菜单 ID 时旧包也不能命中。关闭/resize/新界面没有沿用旧按钮缓存，
  初始为暂不可用。客户端注册接收者，公共网络包不直接依赖客户端按钮类。
- **方向 HUD**：HUD 可见时按钮自己的 tooltip 仍让位，容器段合入同一 HUD，避免重叠。
- **范围**：不做配置工具或框选工作区域；方块校准继续使用现有指令且沿用权限。
  没有类型 ID 的物品内容视图不反射猜 ID；已知临时菜单清单以实现为准，未知菜单保守返回暂不可用。

**验证入口**：`ContainerMenuInfosTest`（空容器/背包/归属过滤/状态/来源优先级）、
`ContainerInfoSessionTest`（请求频率/重试/错菜单/晚包/ID复用）、`ContainerInfoTooltipTest`
（普通与高级行、推测警示与未知分离）、`ContainerMenuInfoPacketTest`（codec/请求核对/限频/退出）。
负向验证：临时去掉会话校验、推测警示和状态区分，六个断言 FAIL；已还原并全量复跑。

**游戏反馈（2026-10-26）**：用户确认整体效果良好，实测指出驴信息暂不可用，末影箱/箱船/箱子矿车
显示启发式。驴的真因是 `HorseInventoryMenu` 没有注册 MenuType，`getType()` 会抛
`UnsupportedOperationException`，查询在实体反查前就退出。现只容忍这条无类型菜单路径，
继续既有槽位反查；真实 HorseInventoryMenu 回归可得到 15 存储槽、5 列及 LIVE 来源。
末影箱与原版车船现已采用明确布局（见基础设施 §6.2），不是仅改提示标签。

**本轮修复待游戏复验**：驴信息恢复、末影箱/箱船/箱子矿车/漏斗矿车不再提示启发式。
其它尚未逐项反馈的切换/关闭重开/resize/方向 HUD 与 F3+H 不因总体好评自动视为全部验收。

### 6.3 ⚠️ 重写 `AbstractWidget` 鼠标事件的两条铁律（2026-10-10 实测踩坑）

1. **命中判定要自己补**：基类只有 `mouseClicked` 做了 `active && visible && clicked(...)` 检查；
   `mouseDragged` / `mouseReleased` **只看按键**。重写 `mouseClicked` 时漏判 ⇒
   空手在界面**任意位置**按下都会启动拖动手势。
2. **不要盲目委托 `super`**：`AbstractWidget.mouseDragged` 对**左键恒返回 `true`**
   （只检查 `isValidClickButton`、不看拖动状态）⇒ 若把它当作「我没处理」的返回值，
   会取消所有左键拖动、**打断原版快速合成**。不在手势中时**明确 `return false`**。

---

## 7. 槽位悬停提示（客户端反馈）

> 2026-10-12 落地，首个消费者 = 活耕地种植提示（`FarmlandPlantHint`）。
> 本节写**可复用模式与契约**；玩法侧（消耗语义）见
> [living-farmland-tech.md](../tech/living-farmland-tech.md) §3.2。

**问题**：原版 `AbstractContainerScreen#renderTooltip` 对「光标非空」的悬停
**不渲染任何 tooltip**（源码判据 `this.menu.getCarried().isEmpty()`）⇒
手持触发型交互（活种子 → 活耕地）悬停时零说明；交互**静默失败**（数量不足 /
状态不符，见 §5.1 的「只拦种得成」策略）时玩家也得不到任何反馈 ——
那是该策略的已知代价，用本模式补上。

**模式（三件套，新增悬停提示照此办理）**：

1. **纯函数出行**：`tryBuildLines(carried, target, …)` 返回 `List<Component>` 或
   `null`（门槛不过）—— 不读 Minecraft 全局状态 ⇒ 可单测（画面本身交游戏实测）。
2. **门槛与交互同源**：「能不能成」复用交互的 `triggerFilter` 谓词
   （如 `PlantCropHandler::canPlantWith`），**不得另写一套数量比较** ——
   否则提示说能种、交互却不接，比没有提示更糟。数量不足时提示**差额**
   （需要 N / 持有 M），状态不符（已种植）给对应说明。
3. **渲染挂 `render` TAIL**：`guiGraphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY)`
   —— 与方向配置 HUD（§6.1）同款批量队列：按调用顺序排在所有物品图标之后
   ⇒ 天然在其上，无需 `flush()`。⚠️ 自绘 `fill` 默认 **z=0** 会被深度裁掉
   （§6.1 同坑），必须走 `renderTooltip`。

**与原版 tooltip 的关系**：原版 slot tooltip 在光标非空时**本就被抑制** ⇒
本提示与原版 tooltip **永不重叠**，**不需要**照搬展开地图那套
`renderTooltip` HEAD 取消（`living_item$suppressTooltipOnExpandedMap` 是给
「光标空手也要抑制」的场景用的）。光标空手悬停时本提示不激活（门槛要求光标持
触发物），原版 tooltip 照常。

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
| `FarmlandPlantHint` | 活耕地种植悬停提示行（纯函数可单测；门槛复用 `canPlantWith`，渲染由 `AbstractContainerScreenMixin` 挂 `render` TAIL） |
| `GuiInteractionPacket` | 通用GUI交互包（客户端→服务端：slotIndex + containerSlot + actionId + carriedTag） |
| `CarriedUpdatePacket` | 光标更新包（服务端→客户端：绕过创造模式光标同步限制） |
| `LivingItemSlotWrapperAccessor` | SlotWrapper 访问器接口（获取 target 字段；命名铁律见 AGENTS.md「Mixin 铁律」） |