# 配方书活箱子标签页（RecipeBookComponentMixin）架构

> 活箱子标签页 = 在原版配方书 GUI 里加一个「活箱子」标签：显示背包中所有活箱子的内容网格
> （5×4 分页 + 搜索过滤），支持点击存取（发包给服务端），并把活箱子物品计入配方材料统计。
> 拼音搜索能力见 [pinyin-search-tech.md](pinyin-search-tech.md)。
>
> 📌 2026-10-29 按现架构重写（2026-10-03 的勘误已过时——旧文描述的
> `LivingChestContentsCache` / `@Redirect` / `forceRefresh` 均已不存在）；
> 同日逻辑从 mixin 拆出为普通类（见下）。

## 类结构（三层）

```
client/mixin/RecipeBookComponentMixin.java   ← 薄委托：@Shadow 访问原版私有状态 + 7 个注入点
    └── client/gui/LivingChestRecipeTab.java ← 有状态视图（标签按钮 / 网格 / 分页 / 点击发包）
            └── client/util/LivingChestTabSearch.java ← 纯函数搜索过滤（配 PinyinHelper）
```

- **mixin 只做两件事**：@Shadow（`tabButtons` / `selectedTab` / `width` / `height` / `xOffset` /
  `stackedContents` / `searchBox` / `isVisible()`）与注入点委托。原版私有状态全部以参数传入视图，
  视图不反向引用 mixin。
- **视图生命周期**：mixin 在首次 `initVisuals` 时懒创建；创建前所有注入点在 mixin 早退
  （等价于旧实现对按钮实例的 null 检查时序）。
- **`collectLivingChestItems()` 是静态方法**：材料统计路径在视图创建之前也会调用。

## 注入点（7 处）

| 注入 | 目标 | 作用 |
|---|---|---|
| `initVisuals` TAIL | 懒创建视图；按 `width/xOffset/height` 算标签位置（末尾之后垂直堆叠 27px/个）；重置标签为未激活 | 打开配方书默认停留在原版标签页（**有意保持原版行为**） |
| `initVisuals` INVOKE `updateCollections` | 材料统计（见下） | 覆盖「刚打开配方书」路径 |
| `updateStackedContents` INVOKE `updateCollections` | 材料统计（见下） | 覆盖「背包变动 / 点槽位」路径 |
| `render` INVOKE `popPose` | 标签激活时渲染内容网格（此时变换矩阵仍有效） | 网格主体 |
| `render` TAIL | 渲染标签按钮 + 箱子图标（禁深度测试防遮挡） | 标签常显 |
| `mouseClicked` HEAD (cancellable) | 优先级：标签按钮 > 翻页按钮 > 内容槽位；命中即短路原版 | 交互入口 |
| `mouseClicked` RETURN | 原版处理了本次点击 ⇒ 自动退出活箱子标签（防误退：点在搜索框内不算） | 隐式退出 |

> ⚠️ **两条材料统计路径缺一不可**（`initVisuals` + `updateStackedContents` 的
> `updateCollections(Z)V` 调用前各注入一次）：只注入后者会导致「刚打开配方书时活箱子材料
> 不被识别，必须做点什么触发一次刷新才恢复」。统计 = 遍历
> `LivingChestRecipeTab.collectLivingChestItems()` 逐个 `stackedContents.accountStack()`。

## 关键约束

1. **槽位坐标必须是绝对屏幕坐标**（对齐原版 RecipeBookPage：`x = left + 11 + 25×(i%5)`，
   `y = top + 31 + 25×(i/5)`）——漏加 left/top 会画到屏幕左上角。
2. **渲染口径对齐原版 RecipeButton**：25×25 槽位 + `renderFakeItem` + (4,4) 图标偏移 +
   `recipe_book/slot_*` 纹理；点击命中区也是 25×25（不是容器界面的 18×18）。
3. **翻页按钮位置对齐原版 RecipeBookPage**：上一页 x+38、下一页 x+93、y+137，12×17。
4. **count>1 的活箱子跳过**（存取锁定的箱子不进网格、不进材料统计）。
5. **RETURN 自动退出的原判据保留**：须 `selectedTab != null` 才退出（历史行为，未改动）。
6. **空物品 + 非空搜索词 ⇒ 匹配**（原 `matchesSearchText` 守卫分支即如此；实际路径由
   `filter` 先剔除空栈，分支不可达）——`LivingChestTabSearchTest` 钉住该语义。

## 点击 → 发包映射（`LivingChestAccessPacket`）

| 操作 | 空手 | 手持物品 |
|---|---|---|
| 左键 | WITHDRAW（一组）/ Shift ⇒ WITHDRAW_INVENTORY（全部取出入背包） | DEPOSIT（全部存入）/ Shift ⇒ 存 1 |
| 右键 | WITHDRAW 半组 / Shift ⇒ 取 1 | DEPOSIT 存 1 |

服务端处理见 `ServerPacketHandler.handleLivingChestAccess`（DEPOSIT / WITHDRAW /
WITHDRAW_INVENTORY / DEPOSIT_SLOT；LOAD 已废弃）。UI 即时性：操作发包后下一帧重扫背包重绘，
无需服务端回包。

## 已知边界

- `collectLivingChestItems()` **每帧扫描玩家背包**（渲染帧调用）——性能待办已在
  [TODO.md](../../TODO.md) 性能节登记。
- 渲染 / 点击行为依赖真实 GUI，单测只覆盖纯函数部分（搜索过滤），其余由游戏内实测把关。
