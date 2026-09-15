# RecipeBookComponentMixin 数据流架构总览

> 📄 **中文拼音搜索**由本 Mixin 与 `client/util/PinyinHelper.java` 提供：
> 功能说明、技术架构、性能与测试见 [pinyin-search.md](../reference/pinyin-search.md)。
> （2026-09-16：删除了文件开头遗留的 AI 对话记录，正文未改动。）

## 🏗️ **系统架构层次图**

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用户交互层 (User Interaction)                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────────┐ │
│  │ 鼠标点击  │  │ 键盘输入  │  │ 窗口调整  │  │ 搜索框文本变化      │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──────────┬──────────┘ │
└───────┼─────────────┼─────────────┼───────────────────┼────────────┘
        ↓             ↓             ↓                   ↓
┌─────────────────────────────────────────────────────────────────────┐
│                      Mixin 注入层 (Injection Points)                 │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ @Inject(method="initVisuals")     → 初始化活箱子标签按钮      │   │
│  │ @Inject(method="render",HEAD)    → 渲染前拦截（显示活箱子）    │   │
│  │ @Inject(method="render",TAIL)    → 渲染后处理（Tooltip等）    │   │
│  │ @Inject(method="mouseClicked",HEAD) → 拦截鼠标事件           │   │
│  │ @Redirect(method="render")       → 替换原版配方书渲染        │   │
│  │ @Redirect(method="renderTooltip")→ 替换原版 Tooltip 渲染     │   │
│  │ @Redirect(method="mouseClicked") → 替换原版配方书点击事件    │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        业务逻辑层 (Business Logic)                    │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌──────────────┐  │、
│  │ 标签切换逻辑│  │ 分页管理器  │  │ 搜索过滤引擎│  │ 坐标自适应系统│  │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └──────┬───────┘  │
└────────┼───────────────┼───────────────┼─────────────────┼──────────┘
         ↓               ↓               ↓                 ↓
┌─────────────────────────────────────────────────────────────────────┐
│                         数据缓存层 (Data Caching)                     │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ LivingChestContentsCache (全局静态)                            │   │
│  │ ├─ contents: List<ItemStack>  (原始物品列表)                  │   │
│  │ ├─ dirty: boolean            (脏标记)                       │   │
│  │ └─ get()/set()/markDirty()                                  │   │
│  └──────────────────────┘  └────────────────────────────────────┘   │
│                                                                     
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ filteredContents (实例字段)                                    │   │
│  │ ├─ 搜索过滤后的结果缓存                                       │   │
│  │ ├─ lastSearchText: String (上次搜索词)                       │   │
│  │ └─ forceRefresh: boolean (强制刷新标志)                      │   │
│  └──────────────────────┘  └────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        网络通信层 (Network Communication)             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ LivingChestAccessPacket (客户端 → 服务器)                     │   │
│  │ ├─ LOAD:      请求同步最新内容                                 │   │
│  │ ├─ DEPOSIT:   存入物品操作                                     │   │
│  │ ├─ WITHDRAW:  取出物品操作（到手持）                           │   │
│  │ └─ WITHDRAW_INVENTORY: 取出物品操作（到背包）                  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ LivingChestAccessPacket (服务器 → 客户端)                   │   │
│  │ └─ itemTags: List<CompoundTag> (最新物品数据)                │   │
│  │   → LivingChestContentsCache.set(items) 更新缓存              │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│                          渲染层 (Rendering)                          │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Slot 对象池 (livingChestSlots)                                │   │
│  │ ├─ 25个虚拟Slot对象 (5列×4行)                                │   │
│  │ ├─ 绝对屏幕坐标 (left + offset_x, top + offset_y)            │   │
│  │ └─ 窗口自适应重建机制                                         │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ UI 控件                                                       │   │
│  │ ├─ backButton: Button (上一页)                               │   │
│  │ ├─ forwardButton: Button (下一页)                             │   │
│  │ └─ livingChestTab: StateSwitchingButton (标签按钮)          │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔄 **核心数据流详解**

### **1️⃣ 初始化流程 (Initialization)**

```
游戏启动 → 打开容器界面 → RecipeBookComponent.init()
         ↓
    [@Inject initVisuals TAIL]
         ↓
    onInitVisuals()
    ├── 创建 livingChestTab 按钮 (StateSwitchingButton)
    │   └── 添加到 tabButtons 列表
    ├── 初始化状态变量:
    │   ├── livingChestTabActive = false
    │   ├── currentPage = 0
    │   ├── lastSearchText = ""
    │   ├── filteredContents = []
    │   ├── forceRefresh = false
    │   ├── lastRenderLeft = MIN_VALUE
    │   └── lastRenderTop = MIN_VALUE
    └── 准备就绪，等待用户交互 ✅
```

**关键代码位置**: [L370-L403](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L370-L403)

---

### **2️⃣ 渲染流程 (Rendering Pipeline)**

```
每帧调用 RecipeBookComponent.render()
         ↓
    [📍 拦截点 1: @Inject render HEAD]
         ↓
    onRenderBeforePop(guiGraphics, mouseX, mouseY, partialTick)
    ├── 检查 livingChestTabActive?
    │   ├── true  → 执行活箱子渲染流程 ⬇️
    │   └── false → 跳过，继续原版流程
    │
    └── [条件成立时]
        ├── 获取配方书位置 (left, top)
        ├── 调用 renderLivingChestIcon()  ← 渲染标题图标
        └── 调用 renderLivingChestContents()  ← 核心渲染 ⬇️

    renderLivingChestContents(left, top, mouseX, mouseY)
    ├── 步骤 0: 数据获取与过滤
    │   ├── contents = LivingChestContentsCache.get()  ← 从全局缓存读取
    │   └── displayContents = applySearchFilter(contents)  ← 应用搜索过滤 ⬇️
    │
    ├── 步骤 1: 计算分页
    │   ├── totalPages = ceil(displayContents.size() / TOTAL_SLOTS)
    │   ├── currentPage = min(currentPage, totalPages - 1)
    │   └── startIdx = currentPage * TOTAL_SLOTS
    │
    ├── 步骤 2: 更新 Slot 对象
    │   └── updateLivingChestSlots(left, top, displayContents, startIdx) ⬇️
    │
    ├── 步骤 3: 渲染所有槽位
    │   for i in 0..livingChestSlots.size():
    │       └── renderSlot(slot, mouseX, mouseY, i) ⬇️
    │
    ├── 步骤 4: 渲染 Tooltip
    │   if hoveredSlotIndex >= 0:
    │       └── renderSlotTooltip(hoveredSlot, mouseX, mouseY) ⬇️
    │
    └── 步骤 5: 渲染分页控件
        if totalPages > 1:
            └── setupPageButtons(left, top, totalPages) ⬇️


    applySearchFilter(originalList)  ← 搜索过滤引擎
    ├── 读取搜索词: searchText = searchBox.getValue().trim().toLowerCase()
    │
    ├── 🆕 强制刷新检查:
    │   if forceRefresh && isDirty():
    │       └── return []  (空列表，等待服务器响应)
    │
    ├── 缓存命中检查:
    │   if searchText == lastSearchText && !filteredContents.isEmpty():
    │       └── return filteredContents  (快速路径)
    │
    ├── 空搜索词:
    │   if searchText.isEmpty():
    │       └── return originalList  (显示全部)
    │
    └── 执行过滤:
        for stack in originalList:
            if matchesSearchText(stack, searchText):
                filteredContents.add(stack)
        return filteredContents


    updateLivingChestSlots(left, top, contents, startIdx)  ← Slot 管理
    ├── 🆕 窗口大小检测:
    │   positionChanged = (left != lastRenderLeft || top != lastRenderTop)
    │   if positionChanged:
    │       ├── lastRenderLeft = left
    │       ├── lastRenderTop = top
    │       └── livingChestSlots.clear()  (强制重建)
    │
    ├── 增量更新优化:
    │   if slots.size() == TOTAL_SLOTS && !positionChanged:
    │       for i in 0..TOTAL_SLOTS:
    │           if 物品变化:
    │               └── recreateSlotAtIndex(i, newStack)
    │   else:
    │       for slot in 0..TOTAL_SLOTS:
    │           └── createLivingChestSlot(left, top, slot, stack)
    │
    └── 结果: 25个 Slot 对象准备就绪


    createLivingChestSlot(left, top, slotIndex, stack)  ← 单个槽位创建
    ├── 计算网格坐标:
    │   col = slotIndex % COLUMNS  (0-4)
    │   row = slotIndex / COLUMNS  (0-3)
    │   x = left + GRID_OFFSET_X + col * (SLOT_SIZE + SLOT_GAP)
    │   y = top + GRID_OFFSET_Y + row * (SLOT_SIZE + SLOT_GAP)
    │
    └── 创建虚拟 Slot 对象:
        return new Slot(null, slotIndex, x, y) {
            mayPlace() → false  (只读)
            mayPickup() → true  (允许取出)
            getItem()  → stack.copy()  (返回副本)
        }


    renderSlot(guiGraphics, slot, mouseX, mouseY, slotIndex)  ← 单个槽位渲染
    ├── 悬停检测:
    │   if isHovering(mouseX, mouseY, slot.x, slot.y, SLOT_SIZE, SLOT_SIZE):
    │       └── hoveredSlotIndex = slotIndex
    │
    ├── 绘制背景 (25×25):
    │   guiGraphics.blit(RECIPE_BOOK_SLOT_TEXTURE, x, y, ...)
    │
    ├── 绘制物品 (偏移4,4):
    │   if !slot.getItem().isEmpty():
    │       guiGraphics.renderFakeItem(stack, x+ITEM_OFFSET, y+ITEM_OFFSET)
    │       guiGraphics.renderItemDecorations(font, stack, x+ITEM_OFFSET, y+ITEM_OFFSET)
    │
    └── 绘制悬停高亮:
        if isHovering:
            guiGraphics.fill(x, y, x+SLOT_SIZE, y+SLOT_SIZE, 0x80FFFFFF)


    [📍 拦截点 2: @Redirect render → RecipeBookPage.render]
    redirectRecipeBookPageRender(instance, guiGraphics, mouseX, mouseY, ...)
    ├── 检查 livingChestTabActive?
    │   ├── true  → 直接返回 (阻止原版配方书渲染) 🔒
    │   └── false → 调用原版方法 (正常显示合成/熔炉配方)
    │
    └── 效果: 活箱子标签页不显示原版配方


    [📍 拦截点 3: @Inject render TAIL]
    onRenderTail(guiGraphics, mouseX, mouseY, partialTick)
    └── 当前为空实现 (预留扩展点)
```

**关键代码位置**:
- [L407-L458](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L407-L458) - 渲染前拦截
- [L498-L548](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L498-L548) - 内容渲染
- [L573-L625](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L573-L625) - 搜索过滤
- [L719-L734](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L719-L734) - Slot 更新
- [L852-L905](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L852-L905) - 槽位渲染

---

### **3️⃣ 交互流程 (User Interaction)**

```
用户鼠标点击
         ↓
    [📍 拦截点: @Inject mouseClicked HEAD]
    onMouseClicked(mouseX, mouseY, button, cir)
    ├── 检查 livingChestTabActive?
    │   ├── false → 不拦截，继续原版流程
    │   └── true  → 处理活箱子交互 ⬇️
    │
    └── handleLivingChestItemClick(mouseX, mouseY, button)
        ├── 遍历 livingChestSlots:
        │   for i in 0..slots.size():
        │       slot = slots.get(i)
        │       if isHovering(mouseX, mouseY, slot.x, slot.y, ...):
        │           └── 处理点击 ⬇️
        │
        └── 三种情况:

            【情况 1: 槽位有物品】
            if !stack.isEmpty():
                └── executeSlotAction(stack, button) ⬇️

            【情况 2: 空槽位 + 手持物品】
            elif !carried.isEmpty():
                ├── 发送 DEPOSIT 包 (左键全部/右键单个)
                └── triggerSearchRefresh() ⬇️

            【情况 3: 空槽位 + 空手】
            else:
                └── return false (无操作)


    executeSlotAction(slotStack, button)  ← 操作执行
    ├── carried = player.containerMenu.getCarried()
    ├── shift = hasShiftDown()
    │
    ├── 左键 (button == 0):
    │   ├── 空手 → 取出操作:
    │   │   amount = slotStack.getMaxStackSize()
    │   │   发送 WITHDRAW / WITHDRAW_INVENTORY 包
    │   │
    │   └── 手持物品 → 存入操作:
    │       amount = carried.getCount()
    │       发送 DEPOSIT 包
    │
    ├── 右键 (button == 1):
    │   ├── 空手 → 取出操作:
    │   │   amount = shift ? 1 : maxStackSize/2
    │   │   发送 WITHDRAW / WITHDRAW_INVENTORY 包
    │   │
    │   └── 手持物品 → 存入操作:
    │       amount = 1
    │       发送 DEPOSIT 包
    │
    └── triggerSearchRefresh()  ← 触发刷新 ⬇️


    triggerSearchRefresh()  ← 统一刷新入口
    ├── 步骤 1: 强制重新过滤
    │   ├── forceRefresh = true
    │   ├── lastSearchText = ""
    │   └── filteredContents.clear()
    │
    ├── 步骤 2: 标记内容缓存失效
    │   └── LivingChestContentsCache.markDirty()
    │
    ├── 步骤 3: 请求服务器同步
    │   └── PacketDistributor.sendToServer(LOAD包)
    │
    └── 步骤 4: 重置页码
        └── currentPage = 0


    [📍 拦截点: @Redirect mouseClicked → RecipeBookPage.mouseClicked]
    redirectRecipeBookPageMouseClick(instance, mouseX, mouseY, ...)
    ├── 检查 livingChestTabActive?
    │   ├── true  → 返回 false (阻止原版配方书点击) 🔒
    │   └── false → 调用原版方法
    │
    └── 效果: 活箱子标签页的点击不被原版处理
```

**关键代码位置**:
- [L1170-L1195](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L1170-L1195) - 鼠标拦截
- [L1340-L1420](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L1340-L1420) - 点击处理
- [L1430-L1485](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L1430-L1485) - 操作执行
- [L1496-L1520](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L1496-L1520) - 触发刷新

---

### **4️⃣ 网络通信流程 (Network Communication)**

```
客户端操作触发
         ↓
    发送 LivingChestAccessPacket (客户端 → 服务器)
    ├── LOAD 操作:
    │   └── ServerPacketHandler.handleLoad(player, server)
    │       └── sendLivingChestContents(player, server) ⬇️
    │
    ├── DEPOSIT 操作:
    │   └── ServerPacketHandler.handleDeposit(player, server, packet)
    │       ├── 从 carried 取出 amount 个物品
    │       ├── 存入活箱子存储
    │       ├── syncCarriedToClient(player)  (更新手持物品)
    │       └── sendLivingChestContents(player, server) ⬇️
    │
    ├── WITHDRAW 操作:
    │   └── ServerPacketHandler.handleWithdraw(player, server, packet)
    │       ├── 从存储中取出物品
    │       ├── 放入玩家手持或背包
    │       ├── syncCarriedToClient(player)
    │       └── sendLivingChestContents(player, server) ⬇️
    │
    └── WITHDRAW_INVENTORY 操作:
        └── 类似 WITHDRAW，但放入背包


    sendLivingChestContents(player, server)  ← 服务器数据收集
    ├── 遍历玩家背包:
    │   for invStack in player.inventory.items:
    │       if isLivingChest(invStack) && hasStorage(invStack):
    │           merged = getMergedStorage(server, invStack, 27)
    │           for chestItem in merged:
    │               if !chestItem.isEmpty():
    │                   itemTags.add(chestItem.save(...))
    │
    └── 发送到客户端:
        PacketDistributor.sendToPlayer(player, LivingChestAccessPacket(itemTags))


    LivingChestAccessPacket.handle(packet, context)  ← 客户端接收处理
    ├── 解析物品数据:
    │   items = []
    │   for tag in packet.itemTags:
    │       stack = ItemStack.parse(registryAccess(), tag)
    │       if !stack.isEmpty(): items.add(stack)
    │
    └── 更新全局缓存:
        LivingChestContentsCache.set(items)
        ├── contents.clear()
        ├── contents.addAll(items)
        └── dirty = false  ✅ (脏标记清除)
```

**关键代码位置**:
- [ServerPacketHandler.java L99-L111](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\network\ServerPacketHandler.java#L99-L111) - 服务端处理
- [ServerPacketHandler.java L207-L221](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\network\ServerPacketHandler.java#L207-L221) - 数据收集发送
- [LivingChestAccessPacket.java L58-L73](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\network\LivingChestAccessPacket.java#L58-L73) - 客户端接收

---

### **5️⃣ 搜索过滤流程 (Search & Filter Engine)**

```
用户在搜索框输入文本
         ↓
    每帧自动触发 applySearchFilter(contents)
         ↓
    读取当前搜索词: searchText = searchBox.getValue().trim().toLowerCase()
         ↓
    ┌─────────────────────────────────────────┐
    │ 🆕 强制刷新检查 (v5.1 新增)              │
    ├─────────────────────────────────────────┤
    │ if forceRefresh:                        │
    │   ├── if LivingChestContentsCache.isDirty():│
    │   │   └── return []  (空列表，等待服务器) │
    │   └── else:                             │
    │       ├── forceRefresh = false          │
    │       ├── lastSearchText = ""           │
    │       └── filteredContents.clear()      │
    └─────────────────────────────────────────┘
         ↓
    ┌─────────────────────────────────────────┐
    │ 缓存命中检查 (性能优化)                  │
    ├─────────────────────────────────────────┤
    │ if searchText == lastSearchText         │
    │    && !filteredContents.isEmpty():      │
    │   └── return filteredContents  (快速)   │
    └─────────────────────────────────────────┘
         ↓
    ┌─────────────────────────────────────────┐
    │ 空搜索词处理                            │
    ├─────────────────────────────────────────┤
    │ if searchText.isEmpty():                │
    │   └── return originalList  (全部显示)   │
    └─────────────────────────────────────────┘
         ↓
    ┌─────────────────────────────────────────┐
    │ 执行过滤 (支持拼音搜索)                  │
    ├─────────────────────────────────────────┤
    │ filteredContents.clear()                │
    │ for stack in originalList:              │
    │   if matchesSearchText(stack, searchText):│
    │       filteredContents.add(stack)       │
    │ return filteredContents                 │
    └─────────────────────────────────────────┘
         ↓
    matchesSearchText(stack, searchText)  ← 匹配算法
    ├── 获取物品名称: hoverName = stack.getHoverName().getString()
    ├── 获取物品ID: itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
    ├── 获取描述文本: lore = getLoreLines(stack)
    │
    └── 多维度匹配 (任一匹配即通过):
        ├── 直接文本匹配: text.contains(searchText)
        ├── 完整拼音匹配: PinyinHelper.isPinyinMatch(text, searchText)
        ├── ID 匹配: itemId.contains(searchText)
        └── Lore 匹配: any(loreLine.contains(searchText))
```

**关键代码位置**:
- [L573-L625](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L573-L625) - 过滤主逻辑
- [L654-L718](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L654-L718) - 匹配算法
- [PinyinHelper.java](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\util\PinyinHelper.java) - 拼音转换工具

---

### **6️⃣ 窗口自适应流程 (Window Resize Handling)**

```
用户调整窗口大小 / 改变 GUI 缩放
         ↓
    Minecraft 重新计算 GUI 坐标
         ↓
    RecipeBookComponent.render() 使用新的 (left, top)
         ↓
    updateLivingChestSlots(newLeft, newTop, ...) 被调用
         ↓
    ┌─────────────────────────────────────────┐
    │ 🆕 坐标变化检测 (v5.2 新增)              │
    ├─────────────────────────────────────────┤
    │ positionChanged =                      │
    │   (newLeft != lastRenderLeft) ||       │
    │   (newTop != lastRenderTop)            │
    │                                        │
    │ if positionChanged:                    │
    │   ├── lastRenderLeft = newLeft         │
    │   ├── lastRenderTop = newTop           │
    │   └── livingChestSlots.clear() (重建)  │
    └─────────────────────────────────────────┘
         ↓
    使用新的坐标创建所有 Slot 对象
         ↓
    setupPageButtons(newLeft, newTop, totalPages) 被调用
         ↓
    ┌─────────────────────────────────────────┐
    │ 🆕 按钮位置更新 (v5.2 新增)              │
    ├─────────────────────────────────────────┤
    │ if backButton != null:                  │
    │   └── backButton.setPosition(newX, newY)│
    │ if forwardButton != null:               │
    │   └── forwardButton.setPosition(newX,newY)│
    └─────────────────────────────────────────┘
         ↓
    所有 UI 元素正确适配新窗口尺寸 ✅
```

**关键代码位置**:
- [L719-L734](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L719-L734) - Slot 坐标检测
- [L992-L1033](file:///G:\777hi\mc\mymods\livingitem-template-1.21.1\src\main\java\com\qiqi\li\client\mixin\RecipeBookComponentMixin.java#L992-L1033) - 按钮位置更新

---

## 📦 **状态变量生命周期**

| 变量名 | 类型 | 初始值 | 修改时机 | 作用 |
|--------|------|--------|----------|------|
| `livingChestTabActive` | boolean | false | 点击标签按钮 | 控制是否显示活箱子视图 |
| `currentPage` | int | 0 | 翻页/重置 | 当前页码 |
| `lastSearchText` | String | "" | 过滤后/刷新时 | 上次搜索词（缓存比较） |
| `filteredContents` | List\<ItemStack\> | [] | 过滤执行时 | 搜索结果缓存 |
| `forceRefresh` | boolean | false | 操作后/刷新时 | 强制重过滤标志 |
| `hoveredSlotIndex` | int | -1 | 每帧渲染时 | 当前悬停槽位 |
| `lastRenderLeft/Top` | int | MIN_VALUE | 每帧渲染时 | 窗口坐标记录 |
| `livingChestSlots` | List\<Slot\> | [] | Slot更新时 | 虚拟槽位对象池 |

---

## 🎯 **设计模式总结**

### **1. Mixin 注入模式**
- **@Inject (HEAD/TAIL)**: 在原方法前后插入逻辑（初始化、渲染、点击）
- **@Redirect**: 替换原方法调用（阻止原版渲染和交互）
- **@Shadow**: 访问原类私有成员（tabButtons、searchBox等）

### **2. 双层缓存策略**
- **Layer 1**: `LivingChestContentsCache` (全局静态，服务器数据源)
- **Layer 2**: `filteredContents` (实例级别，搜索过滤结果)

### **3. 乐观更新 vs 被动刷新**
- ~~乐观更新~~ ❌ (已移除，会导致数据不一致)
- **被动刷新** ✅ (等待服务器响应，保证正确性)

### **4. 脏数据保护机制**
```java
if (forceRefresh && isDirty()) {
    return [];  // 返回空列表，避免残影
}
```

### **5. 坐标自适应系统**
- 检测窗口大小变化
- 强制重建 UI 元素
- 确保多分辨率兼容性

---

## 🔗 **外部依赖关系图**

```
RecipeBookComponentMixin
    ├── LivingChestContentsCache (数据源)
    │   └── 由 LivingChestAccessPacket 更新
    │       └── 由 ServerPacketHandler.sendLivingChestContents() 发送
    │           └── 由 LivingChestAccessPacket.LOAD 触发
    │
    ├── LivingChestAccessPacket (操作请求)
    │   ├── LOAD / DEPOSIT / WITHDRAW / WITHDRAW_INVENTORY
    │   └── 由 ServerPacketHandler 处理
    │
    ├── PinyinHelper (拼音搜索)
    │   └── 提供中文到拼音转换功能
    │
    └── Minecraft 原版组件
        ├── RecipeBookComponent (被 Mixin 的目标类)
        ├── RecipeBookPage (被 Redirect 的渲染对象)
        ├── Slot (虚拟槽位基类)
        ├── Button / StateSwitchingButton (UI 控件)
        └── EditBox (搜索框，通过 @Shadow 访问)
```

---

## ⚡ **性能优化要点**

1. **缓存命中检查**: 搜索词不变时直接返回缓存
2. **增量更新**: 只重建变化的 Slot 对象
3. **脏数据跳过**: 服务器未响应时返回空列表（避免错误渲染）
4. **坐标缓存**: 只在窗口变化时才重建 UI 元素

---

这就是 `RecipeBookComponentMixin.java` 的**完整数据流架构**！整个系统从初始化到渲染、交互、网络通信、搜索过滤、窗口自适应形成了完整的闭环。🎯