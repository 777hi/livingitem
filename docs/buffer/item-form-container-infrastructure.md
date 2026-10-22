<!-- markdownlint-disable -->

# 物品形态容器基础设施（活潜影箱第一阶段）

> **状态：探讨中（2026-10-22，含第二轮修正）** —— 由「物品形态的背包里流体不渲染」引出，
> 与用户讨论后确认它是 [活潜影箱实现细节](活潜影箱实现细节.md) 的**第一阶段（地基）**，
> 不是独立需求。本文只写「地基」部分；边界交换 / 嵌套治理 / 2D↔3D 桥接属活潜影箱本体，见该文档。
>
> **两轮讨论的结论变化**（保留痕迹，别按早期结论动手）：
> ① 早期让数据「经物品组件过渡」——**已废**。组件里只该剩 UUID，数据进 SavedData（§4）；
> ② UUID 生成时机、复制、存档瘦身三项，2026-10-22 读精妙 `BackpackStorage` 后定案（§4.3）。
>
> **本文要防的事**：把 B 做成一次性代码。B 与活潜影箱共享「物品形态容器的一等公民身份」
> （ctx / 按 UUID 索引 / SavedData 持久层），**只有身份模型按活潜影箱的标准做，B 才是铺路**；
> 否则 C 立项时必须换掉身份，B 全部作废。

---

## 1. 现象与根因

**现象**：精妙背包拿在手上打开 GUI（或潜影盒打开 GUI），里面的流体层不渲染。

**根因不是数据丢失** —— 数据在物品组件上（`CONTAINER_FLUID_DATA_ITEM`，
2026-10-21 的双向交接已保证它跨形态存活）。根因是**渲染轨不认识这个容器**：

```
LivingFluidFunction.tick ──► FluidFlowServerSync.flushAfterTick(ctx, fluidData)
                                    │
                                    ▼
                          客户端按 cacheKey 画流体层
```

整条链要求 `ctx` 来自 tick 管线，而 tick 管线的容器来源只有三类
（方块 BE `ItemHandler.BLOCK` / 实体白名单 / 玩家背包，`D-container-08` + `D-container-10`）。
**物品形态容器三者都不是**：

- 精妙 `BackpackItem.use` 打开 GUI，槽位容器是它自己的 `BackpackInventoryHandler`；
  原版潜影盒的 GUI 槽位也是自建的 `ShulkerBoxContainer`；
- ⇒ `ContainerContexts.resolve(player, slot)` 解析不出（不是玩家背包 / BE / 实体 / 大箱包装）
  ⇒ **viewer 匹配失败，包发不出去**；
- ⇒ 即便解析出，tick 管线也不 tick 它（`D-container-08`：框架只 tick 方块 + 实体 + 玩家背包）。

⇒ **所有「物品形态容器」都不渲染**（潜影盒、精妙背包、旅行者背包、任何 mod 背包），
tooltip 与 GUI 倒汲同此路径，一并失效。

---

## 2. 为什么这是活潜影箱的第一阶段

`D-container-08` 否掉的是「**扫描展开**」——没人看也 tick。而「玩家正开着 GUI」是另一种情况：
**viewer 驱动的活跃容器**。这条边界从没被否过，是全新场景。

它与活潜影箱共享地基、不共享上层：

| 能力 | B 本文 | C 活潜影箱 | 关系 |
|---|---|---|---|
| 物品形态容器的**上下文**（ctx：读写 / getWidth / getDataHosts） | ✅ | ✅ | **完全重叠** |
| 容器级数据**按物品索引**（而非位置） | ✅ | ✅ | **完全重叠** |
| **回填 / 写回**（物品组件 ↔ 内存表） | ✅ Open/Close 各一次 | ✅ 每 tick 后写回 | **完全重叠** |
| 「手动 tick 一个 ctx」的入口 | ✅ viewer 驱动 | ✅ 功能内部递归 | 触发源不同，机制可共用 |
| `BoundaryExchanger` 边界交换 | ❌ | ✅ | C 独有 |
| 嵌套治理（深度预算 + 环检测） | ❌ | ✅ | C 独有 |
| 2D↔3D 桥接（世界中的潜影盒 = 6 引脚） | ❌ | ✅ | C 独有 |
| 跨边界目标解析（活漏斗输出到隔壁） | ❌ | ✅ | C 独有 |

⇒ **B 是 C 的真子集基础设施**。C「远不止自主 tick」的部分全在上层（门），
不在房间——这与活潜影箱设计稿 §2.4 的核心洞察一致（局部演化 + 边界交换，
不改 `ContainerFluidData` 的 key）。

---

## 3. UUID 的适用范围

**判据只有一条**：

> UUID 只对「**有稳定物品形态、且内容随物品走**」的容器有意义。

UUID 要能跨形态存活，前提是这个容器**变成物品后还是它自己**。内容随方块消失的容器
（原版箱子），破坏时数据本就不迁移（I-C9），给它 UUID 没有意义。

### 3.1 适用

| 容器 | 物品形态成立？ | 说明 |
|---|---|---|
| 潜影盒（原版 + 各模组） | ✅ | 内容在 `minecraft:container` 组件里，物品就是完整容器 |
| 精妙背包 | ✅ | 内容在 SavedData，按 contentsUuid 索引 |
| 无尽箱子 | ✅ | `CLUSTER_CONTAINER` 隐式组件 |
| 旅行者背包等 mod 背包 | ✅ | 同理 |

**适用集与 `container_rules.json` 的 `contentRetaining` 天然重合** —— 不是巧合：
两者问的是同一个问题（“内容跟不跟物品走”）。

### 3.2 不适用

| 容器 | 为什么不需要 |
|---|---|
| 玩家背包 / 末影箱 | **已经是 UUID 身份**（`player_<uuid>`），不需要第二个 |
| 掉落物（ItemEntity） | 身份是实体 UUID 且每次生成都变；数据本来就在物品组件上，不依赖它 |
| 原版箱子 / 铁箱子 / 炼金箱子 | 物品形态是空壳，数据随方块消失，给了 UUID 也无物可挂 |
| 大箱子 | **见 3.3，是受益者不是难点** |

### 3.3 大箱子

现状位置键 `chest_x1_y1_z1_x2_y2_z2` 是**一个方块身份对应两个物品**，
这正是 TODO §3.1「大箱相位各得一份」的根源。按 UUID 走：破坏时两个掉落物各生成一个
UUID、各带各的内容 —— 这本来就是正确语义，**§3.1 会顺便消失**。

---

## 4. 三层模型：身份 / 数据 / 缓存（关键设计）

**先纠一个本稿早期的错误结论**：早期版本让数据「经物品组件过渡」（`moveOut` 写组件、
Open 从组件回填）。那是在**没有持久层**时的替代方案，本质上是把 SavedData 的活儿
推给了事件挂点。既然有了 UUID，组件里就只该剩 UUID 本身：

```
身份   UUID            BE 附件（方块）/ 物品组件（物品）   ← 唯一的跨形态信物
数据   SavedData       按 UUID 索引，全局一份             ← 唯一的跨形态真相
缓存   CONTAINER_DATA  内存表，按 UUID 键                ← 热数据，LRU 可弃
```

形态变化时**数据零搬运**，只让 UUID 跟着走：

- 破坏 / 拾取：BE 附件的 UUID → 写进掉落物 / 手持物品的组件（一个字符串）
- 放置：手持读 UUID → 写进新 BE 附件
- 数据从头到尾待在 SavedData 里，没人动它

⇒ 2026-10-21 实现的 `moveOut`/`moveIn` **数据搬运可以退场**，
`CONTAINER_FLUID_DATA_ITEM` / `CONTAINER_PHASE_SNAPSHOT_ITEM` 两个组件不再需要。
`ContainerHandoff` 保留，职责从「搬一整份数据」缩成「搬一个 UUID」，失败面小一个数量级。

⚠️ **但不要删已实现的组件方案**：它是「UUID 还没生成时的兜底」与老存档的兼容路径，
落地后角色收缩成“BE 附件有数据但 UUID 未补”时用一次（见 §4.4）。

**方块侧不换键**。位置键与 UUID 键**从不直接对话**，中间隔着 SavedData：

```
方块形态：位置键 chest_x_y_z（现状不变，数据仍在内存表 + BE 附件）
   │  首次需要持久化：懒创建 UUID → 写 BE 附件 + SavedData
   ▼
SavedData：container_<uuid>  ← 形态变化时唯一的常量
   │
物品形态活跃期：UUID 键，Open 时从 SavedData 回填
   │  Close：脏数据已由每 tick setDirty 落盘，无需额外写回
   ▼
再放置：EntityPlaceEvent 从物品读 UUID → 写进 BE 附件
```

⇒ 四张静态表只是**多认一种键形**，不替换；存量存档与所有方块容器零影响。

### 4.1 UUID 的真正价值：跨「活跃期」保持身份

不只是跨形态。没有 UUID，每次打开 GUI 都是一条新键：熔炉烧到 60%、关闭、再打开从零开始。
有 UUID，关闭再开是同一条内存表记录，进度天然续上。

这也正是活潜影箱需要的：潜影盒躺在背包里没人开，它的数据在 SavedData 里等着——
**B 铺的路比原计划深**，「无人观看也 tick」需要的持久化已经就位，C 只差“谁去 tick”。

### 4.2 生命周期

```
首次持久化   → 懒创建 UUID → 写 BE 附件 + SavedData（见 §4.3）
Open         → 从手持/菜单解析出容器物品 → 读 UUID → 从 SavedData 回填内存表
tick         → 正常 processContext + setDirty（MC 自动保存周期落盘，无需自管策略）
Close        → 注销活跃期登记（数据已在 SavedData，无需写回组件）
```

⚠️ **落盘是免费的**：`SavedData.setDirty()` + MC 自己的保存周期 ⇒ 我们不需要设计落盘策略；
`setDirty` 只是打标记，每 tick 调也不碰磁盘。破坏方块 / 卸载容器时应主动标脏保证边界。

### 4.3 精妙背包的实现对照（2026-10-22 读码）

`libs/src/SophisticatedBackpacks-1.21.x` 的 `BackpackStorage extends SavedData` ——
同一套思路的成熟实现，**四条直接借用、两条自己补**：

| 方面 | 精妙的实现 | 我们 |
|---|---|---|
| 存哪 | **Overworld 的 DataStorage**，文件名 = mod id ⇒ 全局一份不分维度 | ✅ 借用（潜影盒会跨维度被带走，全局一份才对） |
| 索引 | `Map<UUID, CompoundTag>` | ✅ 借用结构，但值**类型化**：有 CODEC，`save/load` 里过 `NbtOps`（项目已有先例） |
| 创建 | `computeIfAbsent(factory, name)` + `setDirty()` | ✅ 借用 |
| UUID 载体 | 物品组件 `STORAGE_UUID`（`DataComponentType<UUID>`） | ✅ 借用；BE 侧用**附件**存（我们没有“内部物品栈”，见下） |
| **懒创建** | `getOrCreateContentsUuid()`：第一次真正需要才生成并写组件 | ✅ 借用 ⇒ **§7-1「何时生成」就此定案：懒创建**，不需要“首次 tick 生成”，老存档无需专门迁移命令 |
| **复制** | `cloneBackpack()` 先 `remove(STORAGE_UUID)` 再 copy ⇒ 复制品下次拿到**新 UUID、数据独立** | ✅ 借用为**强制范式**（见 §4.5） |
| **存档瘦身** | 加载时 `isPlayerBackpackOrNotEmpty` 过滤：无物品/无其它数据的条目不进内存 | ✅ 借用 ⇒ 我们只保留 `!isEmpty()` 的流体/相位条目，空的不写盘 |
| 客户端 | 独立空壳 `clientStorageCopy`，`LevelEvent.Load` 清空；GUI 数据走网络包 | ✅ 同构（我们本来也是服务端权威 + 发包） |
| 访问日志 | `accessLogRecords` 记“这个 UUID 属于谁”，防清理误删 | ❌ 不需要（无所有者概念），**但要防 LRU 回收丢脏数据**：回收的只是缓存，SavedData 那份必须在 |

**我们没有、要自己补的一处**：精妙的 `BackpackBlockEntity` 内部持有一个「背包物品栈」
（wrapper 的 backpack），UUID 天然在那个栈上。我们的 BE 没有“内部物品栈”概念
⇒ 用 **BE 附件存 UUID** 作等效替代，懒创建时机相同。

### 4.4 老存档与兜底

存量存档的容器数据在 BE 附件里、没有 UUID。**不需要专门迁移命令**——懒创建天然覆盖：
第一次需要持久化时生成 UUID、把附件数据搬进 SavedData、清附件。搬完就走新路径。

2026-10-21 的组件方案（`CONTAINER_FLUID_DATA_ITEM` 等）保留为兜底：
只在「BE 附件有数据但 UUID 还没补」的窗口期内使用一次。

### 4.5 强制范式：复制必须 remove UUID

> **`ContainerHandoff` 搬运 UUID 时，不得绕过「复制 remove」范式。**

任何「复制一个容器物品」的路径（功能内复制、模板发放、GUI 拖拽出的副本）必须先
`remove` UUID 组件再 copy，让复制品在下次懒创建时拿到新 UUID、数据独立。
反例：直接 `stack.copy()` 会把 UUID 带过去 ⇒ 两个物品共享一份水，且**改一个另一个也变**。

⇒ 落为 `ContainerHandoffTest` 的一条守卫（见 §8）。

⚠️ 精妙对「创造模式中键裸 copy」也没防（会共享），我们**不背这个锅**——但功能内复制必须防。

### 4.6 与 §3 的关系：UUID 适用集不变

懒创建让 §3 的适用集判据更硬：**只有“有稳定物品形态且内容随物品走”的容器才值得有 UUID**。
空箱子给了 UUID 也无物可挂（数据随方块消失，I-C9），懒创建正好让它永远拿不到 UUID。

---

## 5. 改动面

| 层 | 改动 |
|---|---|
| L1 组件 | 新 `DataComponentType<UUID>`（容器 UUID），注册进 `LivingComponents` |
| **L1 持久层（新）** | `ContainerDataSavedData extends SavedData`：`Map<UUID, ...>` 存 Overworld DataStorage；`save/load` 走 CODEC + `NbtOps`；加载时丢弃空条目（§4.3） |
| L1 键 | `ContainerKeys` 新键形（前缀待定，见 §7）；`getCacheKey` 认它；四张静态表天然支持（键只是字符串） |
| L1 交接 | **复用 `ContainerHandoff`** —— 职责缩成「搬一个 UUID」；`Mover.moveOut/moveIn` 各加一条 UUID 搬运，**遵守复制 remove 范式（§4.5）** |
| L1 缓存 | `CONTAINER_DATA` 接 SavedData 回填路径；LRU 回收只丢缓存不丢脏数据（§4.3 末行） |
| L2 生命周期 | 新 `ItemFormContainerTracker`：Open 注册 / Close 注销 / 每 tick 驱动已注册的活跃容器 |
| L3 领域 | `ContainerDataMigration` / `PhaseDataMigration` 的数据搬运**退场为兜底**（§4.4），新增 UUID 搬运 |
| L4 接线 | `PlayerContainerEvent` 订阅；viewer 匹配扩展（物品形态容器按「玩家 + UUID」判） |
| 渲染/交互 | `FluidFlowServerSync.viewersOf` 加一条分支；`MenuSlotOwners` 加物品形态解析（否则 tooltip / 倒汲仍不通） |

**不碰**：位置键体系、`ContainerFluidData` 的槽位 key（仍是 int）、任何现有容器的索引、
SavedData 的保存策略（MC 自己的周期）。

---

## 6. 与活潜影箱的接口（C 立项时要用的）

B 交付后， C 直接复用的是：

1. **容器 UUID 组件** —— C 的环检测天然成立（UUID 不同即不同实例，不需要值语义判等）；
2. **SavedData 持久层** —— C 的每个潜影箱独立一份按 UUID 存，**躺着没人开数据也在**，
   `ContainerFluidData` 的 key 仍是 int；
3. **回填路径** —— C 从「Open 时回填一次」变成「每次要 tick 前回填」，机制相同；
4. **「手动 tick 一个 ctx」** —— C 由功能内部递归触发（`LivingShulkerBoxFunction.tick` 里自建 innerCtx），
   与 B 的 viewer 驱动共用同一个入口。

C 额外要做的（**不在本文范围**）：`BoundaryExchanger`（三种穿透）、嵌套深度预算、
活漏斗跨边界目标解析、水流边界水位、2D↔3D 引脚映射。

---

## 7. 未定案（拍板前不要动工）

五个旧项里三个已由精妙实现定案（懒创建 / 复制 remove / 存档瘦身，见 §4.3），剩两个：

1. **SavedData 粒度**：全局一份（精妙先例，潜影盒跨维度带走也对）vs 按维度分。
   倾向全局一份，但要想清楚「跨维度带走后原维度的存档体积」——其实没影响，条目按 UUID 与维度无关。
2. **键形前缀**：`container_<uuid>` 会撞既有 `container_<dim>_x_y_z` 的 `ContainerKeys.kindOf`
   分支 ⇒ 需换前缀（如 `cuuid_<uuid>`）。**必须先定这个再动工**，否则 `kindOf` 要在两个分支间猜。
3. **物品形态活跃期的 tick 范围**：只 tick 该容器内的活物品（活熔炉/活漏斗/活流体…），
   还是也要容器级计算（流体蔓延 / 相位 capture）？倾向前者 + 后者按需——
   后者每 tick 改 SavedData 标脏，成本可接受但要想清楚。

---

## 8. 验证入口

| 项 | 守卫 |
|---|---|
| UUID 跨形态守恒 | 破坏 → 物品有同一 UUID → 放置 → BE 附件有同一 UUID（`ContainerHandoffTest` 扩展） |
| **复制 remove 范式** | 复制容器物品 ⇒ 副本无 UUID、数据独立；改副本不影响原件（§4.5，`ContainerHandoffTest`） |
| 活跃期身份稳定 | 同一条 UUID 开合两次 GUI，内存表记录不变（活熔炉进度续接） |
| SavedData 往返 | 存档 → 重进，流体/相位按 UUID 读回；空条目不写盘 |
| 渲染 | 手持精妙背包打开 GUI，流体层出现且每 tick 刷新 |
| 不回归 | 方块形态容器（箱子/船/矿车）渲染与数据零变化 |
| 大箱子 | §3.3 场景：两个掉落物各得独立 UUID，数据不复制 |
| LRU 不丢脏数据 | 容器超时无 tick 被回收缓存后，再打开能从 SavedData 回填 |
4. **多个玩家同时打开同一个物品容器**：物品在一个人手里，理论上不可能；
   但精妙的「linked storage」可能让多人看同一份内容 ⇒ 若成立，UUID 身份天然支持（同 UUID 同记录）。
5. **键形**：`container_<uuid>` 与既有的 `container_<dim>_x_y_z` **同名不同义**，
   会撞 `ContainerKeys.kindOf` 的分支 ⇒ 可能需要 `cuuid_<uuid>` 之类的前缀。**必须先定这个再动工。**

---

## 8. 验证入口

| 项 | 守卫 |
|---|---|
| UUID 跨形态守恒 | 破坏 → 物品有同一 UUID → 放置 → BE 附件有同一 UUID（`ContainerHandoffTest` 扩展） |
| 活跃期身份稳定 | 同一条 UUID 开合两次 GUI，内存表记录不变（活熔炉进度续接） |
| 渲染 | 手持精妙背包打开 GUI，流体层出现且每 tick 刷新 |
| 不回归 | 方块形态容器（箱子/船/矿车）渲染与数据零变化 |
| 大箱子 | §3.1 场景：两个掉落物各得独立 UUID，数据不复制 |
