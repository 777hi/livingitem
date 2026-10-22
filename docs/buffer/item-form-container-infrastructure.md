<!-- markdownlint-disable -->

# 物品形态容器基础设施（活潜影箱第一阶段）

> **状态：探讨中（2026-10-22）** —— 由「物品形态的背包里流体不渲染」引出，
> 与用户讨论后确认它是 [活潜影箱实现细节](活潜影箱实现细节.md) 的**第一阶段（地基）**，
> 不是独立需求。本文只写「地基」部分；边界交换 / 嵌套治理 / 2D↔3D 桥接属活潜影箱本体，见该文档。
>
> **本文要防的事**：把 B 做成一次性代码。B 与活潜影箱共享「物品形态容器的一等公民身份」
> （ctx / 索引 / 回填写回），**只有身份模型按活潜影箱的标准做，B 才是铺路**；否则 C 立项时
> 必须换掉身份，B 全部作废。

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

## 4. 身份与数据流（关键设计）

**方块侧不换键**。位置键和 UUID 键**从不直接对话**，中间永远隔着物品组件：

```
方块形态：位置键 chest_x_y_z（现状不变）
   │  破坏 / 拾取：moveOut 把数据写进物品组件（2026-10-21 已实现）
   ▼
物品形态活跃期：UUID 键，Open 时从组件回填
   │  Close：写回物品组件
   ▼
再放置：EntityPlaceEvent 从物品读 UUID → 写进 BE 附件（下次破坏还找得到同一个 UUID）
```

⇒ 四张静态表只是**多认一种键形**，不替换；存量存档与所有方块容器零影响。

### 4.1 UUID 的真正价值：跨「活跃期」保持身份

不只是跨形态。没有 UUID，每次打开 GUI 都是一条新键：熔炉烧到 60%、关闭、再打开从零开始
（Close 写回组件的是那一 tick 的快照，组件是静态的，不会自己演化）。
有 UUID，关闭再开是同一条内存表记录，进度天然续上。

这也正是活潜影箱需要的：潜影盒躺在背包里没人开，它的数据得有个**不依赖“谁在看它”**的身份。

### 4.2 生命周期

```
Open  （PlayerContainerEvent.Open）   → 从手持/菜单解析出容器物品 → 读 UUID（没有则生成）→ 回填内存表
tick  （viewer 驱动，见 §5）          → 正常 processContext
Close （PlayerContainerEvent.Close）  → 内存表 → 写回物品组件（含 UUID 本身）
```

⚠️ **UUID 生成时机未定案**（见 §7）：「第一次跨形态时生成」vs「首次被纳入活跃期时生成」。
倾向前者——不必给世界上几百万个从未跨形态的空潜影盒补数据。

---

## 5. 改动面

| 层 | 改动 |
|---|---|
| L1 组件 | 新 `DataComponentType<UUID>`（容器 UUID），注册进 `LivingComponents` |
| L1 键 | `ContainerKeys` 新键形 `container_<uuid>`；`getCacheKey` 认它；四张静态表天然支持（键只是字符串） |
| L1 交接 | **复用 `ContainerHandoff`** —— 从「搬一整份数据」降级为「搬一个 UUID」；`Mover.moveOut/moveIn` 各加一条 UUID 搬运 |
| L2 生命周期 | 新 `ItemFormContainerTracker`：Open 注册 / Close 注销 / 每 tick 驱动已注册的活跃容器 |
| L3 领域 | `ContainerDataMigration` / `PhaseDataMigration` 的 moveOut/moveIn 增加 UUID 搬运（同一份实现，事件路径与交接路径共用） |
| L4 接线 | `PlayerContainerEvent` 订阅；viewer 匹配扩展（物品形态容器按「玩家 + UUID」判） |
| 渲染/交互 | `FluidFlowServerSync.viewersOf` 加一条分支；`MenuSlotOwners` 加物品形态解析（否则 tooltip / 倒汲仍不通） |

**不碰**：位置键体系、`ContainerFluidData` 的槽位 key（仍是 int）、任何现有容器的索引。

---

## 6. 与活潜影箱的接口（C 立项时要用的）

B 交付后，C 直接复用的是：

1. **容器 UUID 组件** —— C 的环检测天然成立（UUID 不同即不同实例，不需要值语义判等）；
2. **按 UUID 索引的容器级数据** —— C 的每个潜影箱独立一份，`ContainerFluidData` 的 key 仍是 int；
3. **回填 / 写回** —— C 从「Close 时写回一次」变成「每 tick 后写回组件」，机制相同；
4. **「手动 tick 一个 ctx」** —— C 由功能内部递归触发（`LivingShulkerBoxFunction.tick` 里自建 innerCtx），
   与 B 的 viewer 驱动共用同一个入口。

C 额外要做的（**不在本文范围**）：`BoundaryExchanger`（三种穿透）、嵌套深度预算、
活漏斗跨边界目标解析、水流边界水位、2D↔3D 引脚映射。

---

## 7. 未定案（拍板前不要动工）

1. **UUID 生成时机**：第一次跨形态时 vs 首次活跃期时。（倾向前者）
2. **是否只给「有容器级数据」的容器生成 UUID**：避免无谓组件写入；代价是判据多一处。
3. **物品形态活跃期的 tick 范围**：只 tick 该容器内的活物品（活熔炉/活漏斗/活流体…），
   还是也要容器级计算（流体蔓延 / 相位 capture）？倾向前者 + 后者按需。
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
